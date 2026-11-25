package ua.odesa.scrossdash.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ua.odesa.scrossdash.SCrossApplication

class RefuelActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIRM_REFUEL = "ua.odesa.scrossdash.action.CONFIRM_REFUEL"
        const val ACTION_DISMISS_REFUEL = "ua.odesa.scrossdash.action.DISMISS_REFUEL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SCrossApplication ?: return
        when (intent.action) {
            ACTION_CONFIRM_REFUEL -> {
                app.expenseRepository.confirmPendingRefuel()
                app.tripNotificationHelper.cancelRefuelNotice()
            }
            ACTION_DISMISS_REFUEL -> {
                app.expenseRepository.dismissPendingRefuel()
                app.tripNotificationHelper.cancelRefuelNotice()
            }
        }
    }
}
