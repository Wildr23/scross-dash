package ua.odesa.scrossdash.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import ua.odesa.scrossdash.service.ObdTelemetryService

class CarBluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val deviceName = try { device?.name ?: "" } catch (_: SecurityException) { "" }
        val deviceMac = device?.address ?: ""

        // Target: Suzuki S-Cross Head Unit ("Suzuki CAR", F8:96:FE:49:7C:42) or wireless adapter ("AAWireless")
        val isTargetCarDevice = deviceName.contains("Suzuki", ignoreCase = true) ||
                deviceName.contains("AAWireless", ignoreCase = true) ||
                deviceMac.equals("F8:96:FE:49:7C:42", ignoreCase = true)

        if (!isTargetCarDevice) return

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val serviceIntent = Intent(context, ObdTelemetryService::class.java).apply {
                    this.action = ObdTelemetryService.ACTION_CAR_BT_CONNECTED
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (!ObdTelemetryService.isRunning) return
                val serviceIntent = Intent(context, ObdTelemetryService::class.java).apply {
                    this.action = ObdTelemetryService.ACTION_CAR_BT_DISCONNECTED
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (_: Exception) {
                    try {
                        context.startService(serviceIntent)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
