package ua.odesa.scrossdash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import ua.odesa.scrossdash.R
import ua.odesa.scrossdash.SCrossApplication

class ObdTelemetryService : Service() {

    companion object {
        const val NOTIFICATION_ID = 4801
        const val CHANNEL_ID = "scross_telemetry_channel"
        const val ACTION_CAR_BT_CONNECTED = "ua.odesa.scrossdash.action.CAR_BT_CONNECTED"
        const val ACTION_CAR_BT_DISCONNECTED = "ua.odesa.scrossdash.action.CAR_BT_DISCONNECTED"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var carConnection: CarConnection? = null
    private var isProjectionActive = false
    private var idleTimeoutJob: Job? = null
    private var disconnectWatchdogJob: Job? = null

    private val carConnectionObserver = Observer<Int> { connectionType ->
        when (connectionType) {
            CarConnection.CONNECTION_TYPE_PROJECTION -> {
                onProjectionStarted()
            }
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> {
                if (isProjectionActive) {
                    onProjectionEnded()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        // Start in foreground immediately to satisfy Android foreground service requirements
        val initialNotification = buildNotification("Очікування проекції Android Auto...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Start observing Android Auto connection state
        carConnection = CarConnection(applicationContext).apply {
            type.observeForever(carConnectionObserver)
        }

        // Idle Timeout Guard: If woken up by Bluetooth but projection does not start within 20s, shut down
        startIdleTimeoutGuard()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAR_BT_CONNECTED -> {
                // If woken up by Bluetooth, reset idle timer and wait for projection
                if (!isProjectionActive) {
                    startIdleTimeoutGuard()
                }
            }
            ACTION_CAR_BT_DISCONNECTED -> {
                // Fallback: car Bluetooth disconnected
                onProjectionEnded()
            }
        }
        return START_STICKY
    }

    private fun startIdleTimeoutGuard() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = serviceScope.launch {
            delay(20_000L) // 20 seconds
            val app = application as SCrossApplication
            if (!isProjectionActive && !app.telemetryEngine.isTripActiveNow() && !app.telemetryEngine.isObdConnectedAndAwake()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun onProjectionStarted() {
        isProjectionActive = true
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
        disconnectWatchdogJob?.cancel()
        disconnectWatchdogJob = null

        val app = application as SCrossApplication
        app.telemetryEngine.setAndroidAutoProjectionActive(true)
        app.obdManager.start()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(
            NOTIFICATION_ID,
            buildNotification("S-Cross 48V: запис телеметрії в Android Auto")
        )
    }

    private fun onProjectionEnded() {
        isProjectionActive = false
        idleTimeoutJob?.cancel()

        val app = application as SCrossApplication
        app.telemetryEngine.setAndroidAutoProjectionActive(false)

        if (app.telemetryEngine.isObdConnectedAndAwake() || app.telemetryEngine.isTripActiveNow()) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(
                NOTIFICATION_ID,
                buildNotification("S-Cross 48V: фоновий запис поїздки (OBDII)")
            )
            startDisconnectWatchdog()
        } else {
            shutdownServiceCleanly()
        }
    }

    private fun startDisconnectWatchdog() {
        disconnectWatchdogJob?.cancel()
        disconnectWatchdogJob = serviceScope.launch {
            val app = application as SCrossApplication
            var idleAfterFinishSec = 0
            while (isActive && !isProjectionActive) {
                delay(5_000L)
                if (isProjectionActive) break
                if (app.telemetryEngine.checkDisconnectedTimeout()) {
                    shutdownServiceCleanly()
                    break
                }
                if (!app.telemetryEngine.isTripActiveNow() && !app.telemetryEngine.isObdConnectedAndAwake()) {
                    idleAfterFinishSec += 5
                    if (idleAfterFinishSec >= 15) {
                        shutdownServiceCleanly()
                        break
                    }
                } else {
                    idleAfterFinishSec = 0
                }
            }
        }
    }

    private fun shutdownServiceCleanly() {
        disconnectWatchdogJob?.cancel()
        disconnectWatchdogJob = null
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null

        val app = application as SCrossApplication
        app.csvLogger.flush()
        app.obdManager.stop()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "S-Cross 48V Телеметрія",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Запис телеметрії та поїздок в Android Auto"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S-Cross 48V: запис телеметрії в Android Auto")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_scross)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        idleTimeoutJob?.cancel()
        disconnectWatchdogJob?.cancel()
        carConnection?.type?.removeObserver(carConnectionObserver)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
