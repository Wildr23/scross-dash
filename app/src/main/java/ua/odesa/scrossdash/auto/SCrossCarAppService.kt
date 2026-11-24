package ua.odesa.scrossdash.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class SCrossCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // Allows testing on Desktop Head Unit (DHU) and real vehicle head units
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return SCrossSession()
    }
}
