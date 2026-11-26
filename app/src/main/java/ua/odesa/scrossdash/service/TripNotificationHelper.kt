package ua.odesa.scrossdash.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ua.odesa.scrossdash.MainActivity
import ua.odesa.scrossdash.R
import ua.odesa.scrossdash.data.db.AutoRefuelEvent
import ua.odesa.scrossdash.data.db.TripRecord
import ua.odesa.scrossdash.receiver.RefuelActionReceiver
import java.util.Locale
import kotlin.math.roundToInt

class TripNotificationHelper(private val context: Context) {

    companion object {
        const val SUMMARY_CHANNEL_ID = "scross_trip_summary_channel"
        const val SUMMARY_NOTIFICATION_ID = 4802
        const val REFUEL_NOTIFICATION_ID = 4803
        private const val REFUEL_CONFIRM_REQ_CODE = 4804
        private const val REFUEL_DISMISS_REQ_CODE = 4805
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SUMMARY_CHANNEL_ID,
                "Підсумки поїздок",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Тихі сповіщення з підсумками завершеної поїздки"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun postArrivalSummary(trip: TripRecord) {
        try {
            ensureChannel()

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_INITIAL_TAB, 1) // Open Archive & Analytics tab
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                SUMMARY_NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val savedStr = if (trip.hybridSavedLiters < 0.10f) {
                "+${(trip.hybridSavedLiters * 1000f).roundToInt()} мл"
            } else {
                String.format(Locale.US, "+%.2f л", trip.hybridSavedLiters)
            }

            val title = String.format(
                Locale.US,
                "Поїздка завершена: %.1f км • %.1f л/100 (%.0f грн)",
                trip.distanceKm,
                trip.avgConsumption100Km,
                trip.fuelCostUah
            )
            val body = "Водіння ${trip.ecoScore}/100 • Догляд ${trip.careScore} • MHEV $savedStr"

            val notification = NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_scross)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.notify(SUMMARY_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        } catch (_: Exception) {
        }
    }

    fun postAutoRefuelNotice(event: AutoRefuelEvent) {
        try {
            ensureChannel()

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_INITIAL_TAB, 2) // Open Expenses & Maintenance tab
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                REFUEL_NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val fullSuffix = if (event.isFullTank) " • Повний бак" else ""
            val title = String.format(
                Locale.US,
                "⛽ Заправка: +%.1f л (~%d грн)%s",
                event.addedLiters,
                event.estimatedCostUah.roundToInt(),
                fullSuffix
            )
            val body = "Підтвердіть або скасуйте, перш ніж додати у «Витрати»"

            val confirmIntent = Intent(context, RefuelActionReceiver::class.java).apply {
                action = RefuelActionReceiver.ACTION_CONFIRM_REFUEL
            }
            val confirmPendingIntent = PendingIntent.getBroadcast(
                context,
                REFUEL_CONFIRM_REQ_CODE,
                confirmIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val dismissIntent = Intent(context, RefuelActionReceiver::class.java).apply {
                action = RefuelActionReceiver.ACTION_DISMISS_REFUEL
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                REFUEL_DISMISS_REQ_CODE,
                dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_scross)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .addAction(0, "Підтвердити", confirmPendingIntent)
                .addAction(0, "Скасувати", dismissPendingIntent)
                .setAutoCancel(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.notify(REFUEL_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+
        } catch (_: Exception) {
        }
    }

    fun cancelRefuelNotice() {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.cancel(REFUEL_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}
