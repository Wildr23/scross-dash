package ua.odesa.scrossdash.data.log

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CsvLogStats(
    val currentFileName: String = "",
    val rowCount: Int = 0,
    val fileSizeBytes: Long = 0L,
    val totalLogFilesCount: Int = 0
)

interface CsvLogSink {
    fun startSession(): String?
    fun closeSession()
    fun discardSession()
    fun flush()
    fun getCurrentFileName(): String?
    fun logEvent(eventNote: String)
    fun logRow(
        isDemo: Boolean,
        connState: String,
        rpm: Int,
        speedKmh: Int,
        accelKmhPerSec: Float,
        coolantC: Int,
        intakeC: Int,
        mafGps: Float,
        mapKpa: Int,
        baroKpa: Int,
        boostBar: Float,
        throttlePct: Float,
        engineLoadPct: Float,
        voltage12v: Float,
        lambda: Float,
        rawFuelPct: Float?,
        isFuelCut: Boolean,
        instantFuelVal: String,
        instantFuelUnit: String,
        tripDistKm: Float,
        tripFuelL: Float,
        avgL100km: Float,
        tankRemL: Float,
        tankToFullL: Float,
        hybridMode: String,
        hybridSavedMl: Int,
        ecoTotal: Int,
        ecoAccel: Int,
        ecoBrake: Int,
        ecoCruise: Int,
        careTotal: Int,
        careWarmup: Int,
        careLspi: Int,
        careThermal: Int,
        turboCooldownSec: Int,
        dtcCodes: List<String>,
        eventNote: String = ""
    )
}

class TelemetryCsvLogger(private val context: Context) : CsvLogSink {

    companion object {
        const val CSV_HEADER = "timestamp_iso,elapsed_sec,is_demo,conn_state,rpm,speed_kmh,accel_kmh_s,coolant_c,intake_c,maf_gps,map_kpa,baro_kpa,boost_bar,throttle_pct,engine_load_pct,voltage_12v,lambda,raw_fuel_pct,is_fuel_cut,instant_fuel_val,instant_fuel_unit,trip_dist_km,trip_fuel_l,avg_l_100km,tank_rem_l,tank_to_full_l,hybrid_mode,hybrid_saved_ml,eco_total,eco_accel,eco_brake,eco_cruise,care_total,care_warmup,care_lspi,care_thermal,turbo_cooldown_sec,dtc_codes,event_note"
        const val MAX_LOG_FILES = 50
        private const val FLUSH_INTERVAL_ROWS = 10
    }

    // Single-threaded IO dispatcher guarantees strict chronological ordering of session open / row write / close
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val logDir = File(context.filesDir, "logs")

    private var activeFile: File? = null
    private var writer: PrintWriter? = null
    private var sessionStartTimeMs = 0L
    private var rowCount = 0
    private var unwrittenBufferCount = 0

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val _stats = MutableStateFlow(CsvLogStats())
    val stats: StateFlow<CsvLogStats> = _stats.asStateFlow()

    init {
        scope.launch {
            synchronized(this@TelemetryCsvLogger) {
                if (!logDir.exists()) logDir.mkdirs()
                cleanupOldLogs()
                updateStats()
            }
        }
    }

    @Synchronized
    override fun startSession(): String {
        val now = System.currentTimeMillis()
        val fileName = "scross_telemetry_${fileDateFormat.format(Date(now))}.csv"
        val file = File(logDir, fileName)
        closeActiveWriter()
        if (!logDir.exists()) logDir.mkdirs()
        cleanupOldLogs()

        sessionStartTimeMs = now
        rowCount = 0
        unwrittenBufferCount = 0
        activeFile = file

        scope.launch {
            synchronized(this@TelemetryCsvLogger) {
                if (activeFile?.name == fileName && writer == null) {
                    try {
                        val pw = PrintWriter(FileWriter(file, false))
                        pw.println(CSV_HEADER)
                        pw.flush()
                        writer = pw
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    updateStats()
                }
            }
        }
        return fileName
    }

    @Synchronized
    override fun getCurrentFileName(): String? = activeFile?.name

    @Synchronized
    override fun closeSession() {
        scope.launch {
            synchronized(this@TelemetryCsvLogger) {
                closeActiveWriter()
                activeFile = null
                updateStats()
            }
        }
    }

    @Synchronized
    override fun discardSession() {
        val target = activeFile
        activeFile = null
        rowCount = 0
        unwrittenBufferCount = 0
        scope.launch {
            synchronized(this@TelemetryCsvLogger) {
                closeActiveWriter()
                try {
                    if (target != null && target.exists()) {
                        target.delete()
                    }
                } catch (_: Exception) {}
                updateStats()
            }
        }
    }

    private fun ensureWriterReadyLocked() {
        if (writer != null && activeFile != null) return
        if (!logDir.exists()) logDir.mkdirs()
        cleanupOldLogs()

        val target = activeFile ?: run {
            val now = System.currentTimeMillis()
            sessionStartTimeMs = now
            rowCount = 0
            unwrittenBufferCount = 0
            File(logDir, "scross_telemetry_${fileDateFormat.format(Date(now))}.csv").also {
                activeFile = it
            }
        }

        try {
            val alreadyExists = target.exists() && target.length() > 0L
            val pw = PrintWriter(FileWriter(target, alreadyExists))
            if (!alreadyExists) {
                pw.println(CSV_HEADER)
                pw.flush()
            }
            writer = pw
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updateStats()
    }

    override fun logRow(
        isDemo: Boolean,
        connState: String,
        rpm: Int,
        speedKmh: Int,
        accelKmhPerSec: Float,
        coolantC: Int,
        intakeC: Int,
        mafGps: Float,
        mapKpa: Int,
        baroKpa: Int,
        boostBar: Float,
        throttlePct: Float,
        engineLoadPct: Float,
        voltage12v: Float,
        lambda: Float,
        rawFuelPct: Float?,
        isFuelCut: Boolean,
        instantFuelVal: String,
        instantFuelUnit: String,
        tripDistKm: Float,
        tripFuelL: Float,
        avgL100km: Float,
        tankRemL: Float,
        tankToFullL: Float,
        hybridMode: String,
        hybridSavedMl: Int,
        ecoTotal: Int,
        ecoAccel: Int,
        ecoBrake: Int,
        ecoCruise: Int,
        careTotal: Int,
        careWarmup: Int,
        careLspi: Int,
        careThermal: Int,
        turboCooldownSec: Int,
        dtcCodes: List<String>,
        eventNote: String
    ) {
        val now = System.currentTimeMillis()
        scope.launch {
            synchronized(this@TelemetryCsvLogger) {
                ensureWriterReadyLocked()
                val w = writer ?: return@launch
                val elapsedSec = if (sessionStartTimeMs > 0) ((now - sessionStartTimeMs) / 1000f) else 0f
                val dtcJoined = if (dtcCodes.isEmpty()) "" else dtcCodes.joinToString(";")

                val row = buildString {
                    append(isoFormat.format(Date(now))).append(',')
                    append(String.format(Locale.US, "%.2f", elapsedSec)).append(',')
                    append(isDemo).append(',')
                    append(connState.replace(',', ';')).append(',')
                    append(rpm).append(',')
                    append(speedKmh).append(',')
                    append(String.format(Locale.US, "%.2f", accelKmhPerSec)).append(',')
                    append(coolantC).append(',')
                    append(intakeC).append(',')
                    append(String.format(Locale.US, "%.2f", mafGps)).append(',')
                    append(mapKpa).append(',')
                    append(baroKpa).append(',')
                    append(String.format(Locale.US, "%.2f", boostBar)).append(',')
                    append(String.format(Locale.US, "%.1f", throttlePct)).append(',')
                    append(String.format(Locale.US, "%.1f", engineLoadPct)).append(',')
                    append(String.format(Locale.US, "%.2f", voltage12v)).append(',')
                    append(String.format(Locale.US, "%.3f", lambda)).append(',')
                    append(rawFuelPct?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(',')
                    append(isFuelCut).append(',')
                    append(instantFuelVal).append(',')
                    append(instantFuelUnit).append(',')
                    append(String.format(Locale.US, "%.2f", tripDistKm)).append(',')
                    append(String.format(Locale.US, "%.3f", tripFuelL)).append(',')
                    append(String.format(Locale.US, "%.2f", avgL100km)).append(',')
                    append(String.format(Locale.US, "%.1f", tankRemL)).append(',')
                    append(String.format(Locale.US, "%.1f", tankToFullL)).append(',')
                    append(hybridMode).append(',')
                    append(hybridSavedMl).append(',')
                    append(ecoTotal).append(',')
                    append(ecoAccel).append(',')
                    append(ecoBrake).append(',')
                    append(ecoCruise).append(',')
                    append(careTotal).append(',')
                    append(careWarmup).append(',')
                    append(careLspi).append(',')
                    append(careThermal).append(',')
                    append(turboCooldownSec).append(',')
                    append(dtcJoined).append(',')
                    append(eventNote)
                }

                w.println(row)
                rowCount++
                unwrittenBufferCount++

                if (unwrittenBufferCount >= FLUSH_INTERVAL_ROWS || eventNote.isNotBlank()) {
                    w.flush()
                    unwrittenBufferCount = 0
                    updateStats()
                }
            }
        }
    }

    override fun logEvent(eventNote: String) {
        logRow(
            isDemo = false,
            connState = "",
            rpm = 0,
            speedKmh = 0,
            accelKmhPerSec = 0f,
            coolantC = 0,
            intakeC = 0,
            mafGps = 0f,
            mapKpa = 0,
            baroKpa = 0,
            boostBar = 0f,
            throttlePct = 0f,
            engineLoadPct = 0f,
            voltage12v = 0f,
            lambda = 1f,
            rawFuelPct = null,
            isFuelCut = false,
            instantFuelVal = "",
            instantFuelUnit = "",
            tripDistKm = 0f,
            tripFuelL = 0f,
            avgL100km = 0f,
            tankRemL = 0f,
            tankToFullL = 0f,
            hybridMode = "",
            hybridSavedMl = 0,
            ecoTotal = 0,
            ecoAccel = 0,
            ecoBrake = 0,
            ecoCruise = 0,
            careTotal = 0,
            careWarmup = 0,
            careLspi = 0,
            careThermal = 0,
            turboCooldownSec = 0,
            dtcCodes = emptyList(),
            eventNote = eventNote
        )
    }

    @Synchronized
    override fun flush() {
        try {
            writer?.flush()
            unwrittenBufferCount = 0
            updateStats()
        } catch (_: Exception) {}
    }

    fun getLatestLogFile(): File? {
        synchronized(this) {
            writer?.flush()
            if (activeFile != null && activeFile!!.exists() && activeFile!!.length() > 0) {
                return activeFile
            }
            val files = logDir.listFiles { _, name -> name.endsWith(".csv") }
            return files?.maxByOrNull { it.lastModified() }
        }
    }

    fun getLogFileByName(fileName: String): File? {
        if (fileName.isBlank()) return null
        synchronized(this) {
            writer?.flush()
            val file = File(logDir, File(fileName).name)
            return if (file.exists() && file.length() > 0L) file else null
        }
    }

    fun logFileExists(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        val file = File(logDir, File(fileName).name)
        return file.exists() && file.length() > 0L
    }

    fun getAllLogFiles(): List<File> {
        synchronized(this) {
            writer?.flush()
            val files = logDir.listFiles { _, name -> name.endsWith(".csv") } ?: return emptyList()
            return files.filter { it.exists() && it.length() > 0L }.sortedByDescending { it.lastModified() }
        }
    }

    fun getLogDir(): File = logDir

    fun clearAllLogs() {
        scope.launch {
            synchronized(this@TelemetryCsvLogger) {
                closeActiveWriter()
                logDir.listFiles()?.forEach { it.delete() }
                activeFile = null
                rowCount = 0
                updateStats()
            }
        }
    }

    private fun closeActiveWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
    }

    private fun cleanupOldLogs() {
        val files = logDir.listFiles { _, name -> name.endsWith(".csv") } ?: return
        if (files.size > MAX_LOG_FILES) {
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_LOG_FILES)
                .forEach { it.delete() }
        }
    }

    private fun updateStats() {
        val curFile = activeFile
        val fileList = logDir.listFiles { _, name -> name.endsWith(".csv") }
        _stats.value = CsvLogStats(
            currentFileName = curFile?.name ?: (fileList?.maxByOrNull { it.lastModified() }?.name ?: "Немає активного логу"),
            rowCount = rowCount,
            fileSizeBytes = curFile?.length() ?: 0L,
            totalLogFilesCount = fileList?.size ?: 0
        )
    }
}
