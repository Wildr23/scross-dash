package ua.odesa.scrossdash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.odesa.scrossdash.data.SettingsRepository
import ua.odesa.scrossdash.domain.TelemetryEngine
import ua.odesa.scrossdash.model.RawObdSnapshot
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.sin

class Elm327BluetoothManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val engine: TelemetryEngine
) {
    companion object {
        // Standard Serial Port Profile (SPP) UUID for Classic Bluetooth ELM327 v1.5 (PIC18F25K80)
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerJob: Job? = null
    private var socket: BluetoothSocket? = null
    private var pendingDtcScan = false
    private var pendingDtcClear = false

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    init {
        refreshPairedDevices()
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices(): List<BluetoothDevice> {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = bm?.adapter ?: return emptyList()
        val list = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        _pairedDevices.value = list

        // Auto-select OBDII device if not yet selected in settings
        val currentMac = settingsRepo.settings.value.selectedDeviceMac
        if (currentMac.isBlank() && list.isNotEmpty()) {
            val autoCandidate = list.firstOrNull { dev ->
                val name = try { dev.name ?: "" } catch (_: SecurityException) { "" }
                name.contains("OBD", ignoreCase = true) ||
                name.contains("ELM", ignoreCase = true) ||
                name.contains("PIC18F", ignoreCase = true)
            }
            if (autoCandidate != null) {
                val devName = try { autoCandidate.name ?: "OBDII" } catch (_: SecurityException) { "OBDII" }
                settingsRepo.updateDevice(autoCandidate.address, devName)
            }
        }
        return list
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        return refreshPairedDevices()
    }

    fun start() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (isActive) {
                val settings = settingsRepo.settings.value
                if (settings.demoMode) {
                    runDemoLoop()
                } else {
                    runBluetoothLoop(settings.selectedDeviceMac)
                }
                delay(2000L)
            }
        }
    }

    fun stop() {
        workerJob?.cancel()
        closeSocket()
        engine.updateConnectionStatus(false, false, "Відключено")
    }

    fun requestDtcScan() {
        pendingDtcScan = true
    }

    fun requestDtcClear() {
        pendingDtcClear = true
    }

    @SuppressLint("MissingPermission")
    private suspend fun runBluetoothLoop(macAddress: String) {
        refreshPairedDevices()
        val currentMac = if (macAddress.isBlank()) {
            settingsRepo.settings.value.selectedDeviceMac
        } else {
            macAddress
        }

        if (currentMac.isBlank()) {
            engine.updateConnectionStatus(false, false, "Оберіть адаптер OBDII у налаштуваннях")
            delay(2000L)
            return
        }

        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null || !adapter.isEnabled) {
            engine.updateConnectionStatus(false, false, "Увімкніть Bluetooth на телефоні")
            return
        }

        try {
            engine.updateConnectionStatus(false, false, "Підключення до PIC18F25K80...")
            val device = adapter.getRemoteDevice(currentMac)
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
            }

            // Connection with RFCOMM channel 1 reflection fallback for PIC18F25K80 clones
            var btSocket: BluetoothSocket? = null
            try {
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                btSocket.connect()
            } catch (e: Exception) {
                try {
                    btSocket?.close()
                } catch (_: Exception) {}

                // Reflection fallback to channel 1
                try {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    val fallbackSocket = m.invoke(device, 1) as BluetoothSocket
                    fallbackSocket.connect()
                    btSocket = fallbackSocket
                } catch (fallbackEx: Exception) {
                    throw e
                }
            }
            val activeSocket = btSocket ?: throw java.io.IOException("Socket initialization failed")
            socket = activeSocket

            val input = activeSocket.inputStream
            val output = activeSocket.outputStream

            // Fast initialization tailored for PIC18F25K80 & 2024 Suzuki S-Cross (CAN 11-bit 500k)
            sendCommand(input, output, "ATZ", 600L)
            sendCommand(input, output, "ATE0", 100L)
            sendCommand(input, output, "ATL0", 80L)
            sendCommand(input, output, "ATS0", 80L)
            sendCommand(input, output, "ATH0", 80L)
            sendCommand(input, output, "ATAT2", 80L)
            sendCommand(input, output, "ATSP6", 180L) // ISO 15765-4 CAN (11 bit ID, 500 kbaud)

            engine.updateConnectionStatus(true, false, "Підключено до Suzuki S-Cross (CAN 500k)")

            var baroKpa = 101
            var lastSlowPollMs = 0L
            var cachedFuelPct: Float? = null
            var isEcuInSleep = false
            var lastValidCoolantC = 25
            var hasValidCoolant = false
            var lastValidIntakeC = 25
            var lastValidVoltage = 12.6f
            var lastValidLambda = 1.0f

            while (currentCoroutineContext().isActive && !settingsRepo.settings.value.demoMode) {
                val now = System.currentTimeMillis()

                // Handle on-demand DTC commands
                if (pendingDtcClear) {
                    pendingDtcClear = false
                    sendCommand(input, output, "04", 400L)
                    engine.updateDtcCodes(emptyList())
                }
                if (pendingDtcScan) {
                    pendingDtcScan = false
                    val dtcResp = sendCommand(input, output, "03", 500L)
                    engine.updateDtcCodes(parseDtcResponse(dtcResp))
                }

                // Fast loop PIDs
                val rpmRaw = sendCommand(input, output, "010C")
                if (isIgnitionOffResponse(rpmRaw)) {
                    if (!isEcuInSleep) {
                        isEcuInSleep = true
                        engine.updateConnectionStatus(true, false, "Запалювання вимкнено (STANDBY)")
                    }
                    engine.onEcuSleepTick(3.0f)
                    // Keep socket open, poll gently every 3000ms
                    delay(3000L)
                    continue
                } else if (isEcuInSleep) {
                    // ECU woke up!
                    isEcuInSleep = false
                    engine.onEcuWake()
                    engine.updateConnectionStatus(true, false, "Підключено до Suzuki S-Cross (CAN 500k)")
                }

                val rpm = parseRpm(rpmRaw)
                val speed = parseSingleByte(sendCommand(input, output, "010D"))
                val parsedCoolant = parseTemp(sendCommand(input, output, "0105"))
                if (parsedCoolant != null && parsedCoolant in -30..135) {
                    val isSpuriousShutdownReset = rpm == 0 && hasValidCoolant && parsedCoolant == 25 && lastValidCoolantC > 40
                    if (!isSpuriousShutdownReset) {
                        lastValidCoolantC = parsedCoolant
                        hasValidCoolant = true
                    }
                }
                val maf = parseMaf(sendCommand(input, output, "0110"))
                val map = parseSingleByte(sendCommand(input, output, "010B")).coerceAtLeast(25)
                val throttle = (parseSingleByte(sendCommand(input, output, "0111")) * 100f) / 255f
                val load = (parseSingleByte(sendCommand(input, output, "0104")) * 100f) / 255f

                // Medium/slow loop PIDs (every 5 seconds), retaining last valid values across fast ticks
                if (now - lastSlowPollMs > 5000L) {
                    lastSlowPollMs = now
                    val parsedIntake = parseTemp(sendCommand(input, output, "010F"))
                    if (parsedIntake != null && parsedIntake in -35..120) {
                        lastValidIntakeC = parsedIntake
                    }
                    val parsedVoltage = parseVoltage(sendCommand(input, output, "0142"))
                    if (parsedVoltage != null && parsedVoltage in 8.0f..16.5f) {
                        lastValidVoltage = parsedVoltage
                    }
                    val parsedBaro = parseSingleByte(sendCommand(input, output, "0133"))
                    if (parsedBaro in 85..108) baroKpa = parsedBaro

                    val lambdaRaw = parseTwoBytes(sendCommand(input, output, "0144"))
                    if (lambdaRaw > 0) lastValidLambda = lambdaRaw / 32768f

                    val fuelRaw = parseSingleByte(sendCommand(input, output, "012F"))
                    if (fuelRaw in 1..255) {
                        cachedFuelPct = (fuelRaw * 100f) / 255f
                    }
                }

                engine.processSnapshot(
                    RawObdSnapshot(
                        rpm = rpm,
                        speedKmh = speed,
                        coolantTempC = lastValidCoolantC,
                        intakeTempC = lastValidIntakeC,
                        mafGramsPerSec = maf,
                        mapKpa = map,
                        baroKpa = baroKpa,
                        throttlePct = throttle,
                        engineLoadPct = load,
                        voltage12v = lastValidVoltage,
                        lambdaRatio = lastValidLambda,
                        rawFuelLevelPct = cachedFuelPct,
                        timestampMs = System.currentTimeMillis()
                    )
                )

                delay(80L)
            }
        } catch (e: Exception) {
            engine.onDisconnect()
        } finally {
            closeSocket()
        }
    }

    private fun isIgnitionOffResponse(raw: String): Boolean {
        if (raw.isBlank()) return true
        val upper = raw.uppercase()
        return upper.contains("NO DATA") ||
               upper.contains("UNABLE TO CONNECT") ||
               upper.contains("STOPPED") ||
               upper.contains("CAN ERROR") ||
               upper.contains("BUS INIT: ERROR") ||
               upper.contains("BUS BUSY") ||
               upper.contains("ERR")
    }

    private suspend fun runDemoLoop() {
        engine.updateConnectionStatus(true, true, "Демо: 1.4 BoosterJet 48V")
        var tick = 0
        while (currentCoroutineContext().isActive && settingsRepo.settings.value.demoMode) {
            tick++
            val phase = (tick % 80) / 80.0
            val wave = sin(phase * Math.PI * 2.0).toFloat()

            val isStop = (tick % 80) in 62..74
            val isCoasting = (tick % 80) in 48..61

            val speed = when {
                isStop -> 0
                isCoasting -> (55 - ((tick % 80 - 48) * 3.5f)).toInt().coerceAtLeast(15)
                else -> (48 + (wave * 24f)).toInt().coerceIn(10, 82)
            }
            val rpm = when {
                isStop -> 0 // Start-Stop active!
                isCoasting -> 1450
                else -> (1750 + (wave * 650f)).toInt().coerceIn(1100, 2900)
            }
            val maf = when {
                isStop -> 0f
                isCoasting -> 1.8f
                else -> (9.5f + (wave * 5.2f)).coerceAtLeast(3.2f)
            }
            val map = if (isStop || isCoasting) 98 else (122 + (wave * 28f)).toInt()
            val throttle = if (isStop || isCoasting) 14f else (28f + wave * 18f)

            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = rpm,
                    speedKmh = speed,
                    coolantTempC = (78 + (tick / 20)).coerceAtMost(89),
                    intakeTempC = 32,
                    mafGramsPerSec = maf,
                    mapKpa = map,
                    baroKpa = 101,
                    throttlePct = throttle,
                    engineLoadPct = if (isStop) 0f else (38f + wave * 20f),
                    voltage12v = 14.2f,
                    lambdaRatio = 1.0f,
                    rawFuelLevelPct = null,
                    timestampMs = System.currentTimeMillis()
                )
            )
            delay(250L)
        }
    }

    private fun sendCommand(
        input: InputStream,
        output: OutputStream,
        cmd: String,
        waitMs: Long = 45L
    ): String {
        while (input.available() > 0) input.read()
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()

        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 900L
        Thread.sleep(waitMs)

        var lastWasNewline = false
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val ch = input.read().toChar()
                if (ch == '>') break
                if (ch == '\r' || ch == '\n') {
                    if (!lastWasNewline && sb.isNotEmpty()) {
                        sb.append('\n')
                        lastWasNewline = true
                    }
                } else if (ch != ' ') {
                    sb.append(ch)
                    lastWasNewline = false
                }
            } else {
                Thread.sleep(10L)
            }
        }
        return sb.toString().trim().uppercase()
    }

    private fun extractPayload(raw: String, modePrefix: String): String {
        // In multiline responses, search each line for modePrefix (e.g. "41")
        val lines = raw.split('\n')
        for (line in lines) {
            val idx = line.indexOf(modePrefix)
            if (idx >= 0) {
                return line.substring(idx + modePrefix.length)
            }
        }
        return ""
    }

    private fun parseSingleByte(raw: String): Int {
        val hex = extractPayload(raw, "41")
        if (hex.length < 4) return 0
        return hex.substring(2, 4).toIntOrNull(16) ?: 0
    }

    private fun parseTwoBytes(raw: String): Int {
        val hex = extractPayload(raw, "41")
        if (hex.length < 6) return 0
        val a = hex.substring(2, 4).toIntOrNull(16) ?: 0
        val b = hex.substring(4, 6).toIntOrNull(16) ?: 0
        return (a * 256) + b
    }

    private fun parseRpm(raw: String): Int = parseTwoBytes(raw) / 4

    private fun parseTemp(raw: String): Int? {
        val hex = extractPayload(raw, "41")
        if (hex.length < 4) return null
        val v = hex.substring(2, 4).toIntOrNull(16) ?: return null
        return v - 40
    }

    private fun parseMaf(raw: String): Float = parseTwoBytes(raw) / 100f

    private fun parseVoltage(raw: String): Float? {
        val mv = parseTwoBytes(raw)
        return if (mv > 0) mv / 1000f else null
    }

    fun parseDtcResponse(raw: String): List<String> {
        val codes = mutableListOf<String>()
        val lines = raw.split('\n')
        for (line in lines) {
            val idx = line.indexOf("43")
            if (idx < 0) continue
            val after43 = line.substring(idx + 2).trim()
            if (after43.length < 2) continue

            // ISO 15765-4: Byte immediately after 43 is the number of stored DTCs
            val count = after43.substring(0, 2).toIntOrNull(16) ?: 0
            if (count == 0) {
                // No trouble codes reported by this ECU (e.g. 7E8: 4300 or 7E9: 4300)
                continue
            }

            // Each DTC is 2 bytes (4 hex characters) starting at index 2
            val dtcHex = after43.substring(2)
            var i = 0
            var found = 0
            while (i + 3 < dtcHex.length && found < count) {
                val b1 = dtcHex.substring(i, i + 2).toIntOrNull(16) ?: break
                val b2 = dtcHex.substring(i + 2, i + 4).toIntOrNull(16) ?: break
                if (b1 != 0 || b2 != 0) {
                    val prefix = when ((b1 shr 6) and 0x03) {
                        0 -> "P"
                        1 -> "C"
                        2 -> "B"
                        else -> "U"
                    }
                    val d2 = (b1 shr 4) and 0x03
                    val d3 = b1 and 0x0F
                    val d4 = (b2 shr 4) and 0x0F
                    val d5 = b2 and 0x0F
                    val code = "$prefix$d2${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}${d5.toString(16).uppercase()}"
                    if (!codes.contains(code)) {
                        codes.add(code)
                    }
                    found++
                }
                i += 4
            }
        }
        return codes
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
