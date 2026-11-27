package ua.odesa.scrossdash.data.network

import ua.odesa.scrossdash.data.db.ConsumableItem
import ua.odesa.scrossdash.data.db.ExpenseCategory
import ua.odesa.scrossdash.data.db.ExpenseRecord
import ua.odesa.scrossdash.data.db.TireSeason
import ua.odesa.scrossdash.data.db.TripDatabaseHelper
import ua.odesa.scrossdash.data.db.TripRecord
import ua.odesa.scrossdash.model.AppSettings
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtSec: Long,
    val userId: String,
    val email: String
)

data class SupabaseAuthResult(
    val success: Boolean,
    val session: SupabaseSession? = null,
    val needsEmailConfirmation: Boolean = false,
    val messageUk: String = ""
)

data class SupabaseSyncResult(
    val success: Boolean,
    val tripsSynced: Int = 0,
    val expensesSynced: Int = 0,
    val consumablesSynced: Int = 0,
    val authExpired: Boolean = false,
    val updatedSession: SupabaseSession? = null,
    val messageUk: String = ""
)

data class CloudSettingsSnapshot(
    val odometerKm: Float,
    val fuelPriceUah: Float,
    val mafCalibration: Float,
    val tankCapacityLiters: Float,
    val remainingTankLiters: Float,
    val isTankCalibrated: Boolean,
    val lastStationaryFuelPct: Float,
    val washReminderDays: Int,
    val preferredFuelChain: String,
    val preferredFuelGrade: String,
    val parkingLat: Double?,
    val parkingLng: Double?,
    val parkingTimestampMs: Long
)

data class SupabaseRestoreResult(
    val success: Boolean,
    val trips: List<TripRecord> = emptyList(),
    val expenses: List<ExpenseRecord> = emptyList(),
    val consumables: List<ConsumableItem> = emptyList(),
    val cloudSettings: CloudSettingsSnapshot? = null,
    val authExpired: Boolean = false,
    val updatedSession: SupabaseSession? = null,
    val messageUk: String = ""
)

object SupabaseSyncClient {

    private const val TIMEOUT_MS = 10_000
    private const val BATCH_SIZE = 200

    // ──────── Auth Endpoints (Supabase Auth / GoTrue Email + Password) ────────

    fun signUp(
        baseUrl: String,
        apiKey: String,
        email: String,
        password: String
    ): SupabaseAuthResult {
        val cleanEmail = email.trim()
        if (!cleanEmail.contains('@') || password.length < 6) {
            return SupabaseAuthResult(
                success = false,
                messageUk = "Введіть коректний email та пароль (мін. 6 символів)"
            )
        }
        val url = "${baseUrl.trimEnd('/')}/auth/v1/signup"
        val body = """{"email":${jsonString(cleanEmail)},"password":${jsonString(password)}}"""
        val resp = httpRequest("POST", url, apiKey, accessToken = null, body = body)
        if (resp.code !in 200..299) {
            return SupabaseAuthResult(
                success = false,
                messageUk = mapAuthErrorUk(extractErrorMessage(resp.body, resp.error))
            )
        }
        val session = parseSessionJson(resp.body, fallbackEmail = cleanEmail)
        return if (session != null) {
            SupabaseAuthResult(
                success = true,
                session = session,
                messageUk = "Реєстрація успішна: ${session.email}"
            )
        } else {
            SupabaseAuthResult(
                success = true,
                needsEmailConfirmation = true,
                messageUk = "Перевірте пошту ($cleanEmail) та підтвердіть email, після чого натисніть «Увійти»"
            )
        }
    }

    fun signInWithPassword(
        baseUrl: String,
        apiKey: String,
        email: String,
        password: String
    ): SupabaseAuthResult {
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty() || password.isEmpty()) {
            return SupabaseAuthResult(success = false, messageUk = "Введіть email та пароль")
        }
        val url = "${baseUrl.trimEnd('/')}/auth/v1/token?grant_type=password"
        val body = """{"email":${jsonString(cleanEmail)},"password":${jsonString(password)}}"""
        val resp = httpRequest("POST", url, apiKey, accessToken = null, body = body)
        if (resp.code !in 200..299) {
            return SupabaseAuthResult(
                success = false,
                messageUk = mapAuthErrorUk(extractErrorMessage(resp.body, resp.error))
            )
        }
        val session = parseSessionJson(resp.body, fallbackEmail = cleanEmail)
            ?: return SupabaseAuthResult(success = false, messageUk = "Не вдалося прочитати токен сесії")
        return SupabaseAuthResult(
            success = true,
            session = session,
            messageUk = "Вхід виконано: ${session.email}"
        )
    }

    fun refreshSession(
        baseUrl: String,
        apiKey: String,
        refreshToken: String,
        fallbackEmail: String
    ): SupabaseAuthResult {
        if (refreshToken.isBlank()) {
            return SupabaseAuthResult(success = false, messageUk = "Сесія відсутня — увійдіть повторно")
        }
        val url = "${baseUrl.trimEnd('/')}/auth/v1/token?grant_type=refresh_token"
        val body = """{"refresh_token":${jsonString(refreshToken)}}"""
        val resp = httpRequest("POST", url, apiKey, accessToken = null, body = body)
        if (resp.code !in 200..299) {
            return SupabaseAuthResult(
                success = false,
                messageUk = "Термін сесії вийшов — увійдіть у Supabase повторно"
            )
        }
        val session = parseSessionJson(resp.body, fallbackEmail = fallbackEmail)
            ?: return SupabaseAuthResult(success = false, messageUk = "Помилка оновлення сесії")
        return SupabaseAuthResult(success = true, session = session)
    }

    fun signOut(baseUrl: String, apiKey: String, accessToken: String) {
        if (accessToken.isBlank()) return
        val url = "${baseUrl.trimEnd('/')}/auth/v1/logout"
        httpRequest("POST", url, apiKey, accessToken = accessToken, body = "{}")
    }

    // ──────── Cloud Backup & Restore (PostgREST + RLS) ────────

    fun backupAll(
        baseUrl: String,
        apiKey: String,
        session: SupabaseSession,
        trips: List<TripRecord>,
        expenses: List<ExpenseRecord>,
        consumables: List<ConsumableItem>,
        settings: AppSettings,
        remainingTankLiters: Float,
        isTankCalibrated: Boolean,
        lastStationaryFuelPct: Float,
        nowSec: Long = System.currentTimeMillis() / 1000L
    ): SupabaseSyncResult {
        val activeSession = ensureFreshSession(baseUrl, apiKey, session, nowSec)
            ?: return SupabaseSyncResult(
                success = false,
                authExpired = true,
                messageUk = "Сесія Supabase закінчилась — увійдіть заново"
            )
        var currentSession = activeSession

        val uniqueTrips = trips.distinctBy { it.startTimeMs }
        val uniqueExpenses = expenses.distinctBy { TripDatabaseHelper.expenseDedupKey(it) }
        val uniqueConsumables = consumables.distinctBy { TripDatabaseHelper.consumableDedupKey(it) }

        val headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        val cleanBase = baseUrl.trimEnd('/')

        for (chunk in uniqueTrips.chunked(BATCH_SIZE)) {
            val res = postWithAuthRetry(
                baseUrl = cleanBase,
                apiKey = apiKey,
                session = currentSession,
                pathAndQuery = "/rest/v1/scross_trips?on_conflict=user_id,start_time_ms",
                extraHeaders = headers
            ) { s -> buildTripsJsonArray(chunk, s.userId) }
            currentSession = res.session ?: currentSession
            if (res.authExpired) {
                return SupabaseSyncResult(success = false, authExpired = true, messageUk = "Сесія вийшла — увійдіть повторно")
            }
            if (!res.ok) {
                return SupabaseSyncResult(
                    success = false,
                    updatedSession = currentSession,
                    messageUk = "Помилка бекапу поїздок: ${res.errorMsg}"
                )
            }
        }

        for (chunk in uniqueExpenses.chunked(BATCH_SIZE)) {
            val res = postWithAuthRetry(
                baseUrl = cleanBase,
                apiKey = apiKey,
                session = currentSession,
                pathAndQuery = "/rest/v1/scross_expenses?on_conflict=user_id,dedup_key",
                extraHeaders = headers
            ) { s -> buildExpensesJsonArray(chunk, s.userId) }
            currentSession = res.session ?: currentSession
            if (res.authExpired) {
                return SupabaseSyncResult(success = false, authExpired = true, messageUk = "Сесія вийшла — увійдіть повторно")
            }
            if (!res.ok) {
                return SupabaseSyncResult(
                    success = false,
                    updatedSession = currentSession,
                    messageUk = "Помилка бекапу витрат: ${res.errorMsg}"
                )
            }
        }

        for (chunk in uniqueConsumables.chunked(BATCH_SIZE)) {
            val res = postWithAuthRetry(
                baseUrl = cleanBase,
                apiKey = apiKey,
                session = currentSession,
                pathAndQuery = "/rest/v1/scross_consumables?on_conflict=user_id,dedup_key",
                extraHeaders = headers
            ) { s -> buildConsumablesJsonArray(chunk, s.userId) }
            currentSession = res.session ?: currentSession
            if (res.authExpired) {
                return SupabaseSyncResult(success = false, authExpired = true, messageUk = "Сесія вийшла — увійдіть повторно")
            }
            if (!res.ok) {
                return SupabaseSyncResult(
                    success = false,
                    updatedSession = currentSession,
                    messageUk = "Помилка бекапу ТО: ${res.errorMsg}"
                )
            }
        }

        val settingsRes = postWithAuthRetry(
            baseUrl = cleanBase,
            apiKey = apiKey,
            session = currentSession,
            pathAndQuery = "/rest/v1/scross_user_settings?on_conflict=user_id",
            extraHeaders = headers
        ) { s ->
            buildUserSettingsJsonObject(
                settings = settings,
                remainingTankLiters = remainingTankLiters,
                isTankCalibrated = isTankCalibrated,
                lastStationaryFuelPct = lastStationaryFuelPct,
                userId = s.userId
            )
        }
        currentSession = settingsRes.session ?: currentSession
        if (settingsRes.authExpired) {
            return SupabaseSyncResult(success = false, authExpired = true, messageUk = "Сесія вийшла — увійдіть повторно")
        }
        if (!settingsRes.ok) {
            return SupabaseSyncResult(
                success = false,
                updatedSession = currentSession,
                messageUk = "Помилка бекапу профілю авто: ${settingsRes.errorMsg}"
            )
        }

        return SupabaseSyncResult(
            success = true,
            tripsSynced = uniqueTrips.size,
            expensesSynced = uniqueExpenses.size,
            consumablesSynced = uniqueConsumables.size,
            updatedSession = currentSession,
            messageUk = "Хмарний бекап OK: ${uniqueTrips.size} поїздок, ${uniqueExpenses.size} витрат, ${uniqueConsumables.size} ТО"
        )
    }

    fun restoreAll(
        baseUrl: String,
        apiKey: String,
        session: SupabaseSession,
        nowSec: Long = System.currentTimeMillis() / 1000L
    ): SupabaseRestoreResult {
        val activeSession = ensureFreshSession(baseUrl, apiKey, session, nowSec)
            ?: return SupabaseRestoreResult(
                success = false,
                authExpired = true,
                messageUk = "Сесія Supabase закінчилась — увійдіть заново"
            )
        var currentSession = activeSession
        val cleanBase = baseUrl.trimEnd('/')

        val tripsRes = getWithAuthRetry(cleanBase, apiKey, currentSession, "/rest/v1/scross_trips?select=*&order=start_time_ms.desc&limit=5000")
        currentSession = tripsRes.session ?: currentSession
        if (tripsRes.authExpired) return SupabaseRestoreResult(success = false, authExpired = true, messageUk = "Сесія вийшла — увійдіть повторно")
        if (!tripsRes.ok) return SupabaseRestoreResult(success = false, updatedSession = currentSession, messageUk = "Помилка завантаження поїздок: ${tripsRes.errorMsg}")

        val expRes = getWithAuthRetry(cleanBase, apiKey, currentSession, "/rest/v1/scross_expenses?select=*&order=timestamp_ms.desc&limit=5000")
        currentSession = expRes.session ?: currentSession
        if (expRes.authExpired) return SupabaseRestoreResult(success = false, authExpired = true, messageUk = "Сесія вийшла — увійдіть повторно")
        if (!expRes.ok) return SupabaseRestoreResult(success = false, updatedSession = currentSession, messageUk = "Помилка завантаження витрат: ${expRes.errorMsg}")

        val consRes = getWithAuthRetry(cleanBase, apiKey, currentSession, "/rest/v1/scross_consumables?select=*&limit=500")
        currentSession = consRes.session ?: currentSession
        if (consRes.authExpired) return SupabaseRestoreResult(success = false, authExpired = true, messageUk = "Сесія вийшла — увійдіть повторно")
        if (!consRes.ok) return SupabaseRestoreResult(success = false, updatedSession = currentSession, messageUk = "Помилка завантаження ТО: ${consRes.errorMsg}")

        val setRes = getWithAuthRetry(cleanBase, apiKey, currentSession, "/rest/v1/scross_user_settings?select=*&limit=1")
        currentSession = setRes.session ?: currentSession
        if (setRes.authExpired) return SupabaseRestoreResult(success = false, authExpired = true, messageUk = "Сесія вийшла — увійдіть повторно")

        val trips = parseTripsJsonArray(tripsRes.body)
        val expenses = parseExpensesJsonArray(expRes.body)
        val consumables = parseConsumablesJsonArray(consRes.body)
        val cloudSettings = if (setRes.ok) parseUserSettingsJsonArray(setRes.body) else null

        return SupabaseRestoreResult(
            success = true,
            trips = trips,
            expenses = expenses,
            consumables = consumables,
            cloudSettings = cloudSettings,
            updatedSession = currentSession,
            messageUk = "Завантажено з хмари: ${trips.size} поїздок, ${expenses.size} витрат, ${consumables.size} ТО"
        )
    }

    // ──────── Pure Kotlin JSON Serializers ────────

    fun buildTripsJsonArray(trips: List<TripRecord>, userId: String): String {
        return buildString {
            append('[')
            trips.forEachIndexed { idx, t ->
                if (idx > 0) append(',')
                append('{')
                append("\"user_id\":").append(jsonString(userId)).append(',')
                append("\"start_time_ms\":").append(t.startTimeMs).append(',')
                append("\"end_time_ms\":").append(t.endTimeMs).append(',')
                append("\"distance_km\":").append(fmt(t.distanceKm, 3)).append(',')
                append("\"fuel_used_liters\":").append(fmt(t.fuelUsedLiters, 4)).append(',')
                append("\"fuel_cost_uah\":").append(fmt(t.fuelCostUah, 2)).append(',')
                append("\"avg_consumption\":").append(fmt(t.avgConsumption100Km, 2)).append(',')
                append("\"hybrid_saved_liters\":").append(fmt(t.hybridSavedLiters, 4)).append(',')
                append("\"hybrid_saved_uah\":").append(fmt(t.hybridSavedUah, 2)).append(',')
                append("\"eco_score\":").append(t.ecoScore).append(',')
                append("\"care_score\":").append(t.careScore).append(',')
                append("\"remaining_tank_liters\":").append(fmt(t.remainingTankLiters, 2)).append(',')
                append("\"csv_file_name\":").append(t.csvFileName?.let { jsonString(it) } ?: "null")
                append('}')
            }
            append(']')
        }
    }

    fun buildExpensesJsonArray(expenses: List<ExpenseRecord>, userId: String): String {
        return buildString {
            append('[')
            expenses.forEachIndexed { idx, e ->
                if (idx > 0) append(',')
                append('{')
                append("\"user_id\":").append(jsonString(userId)).append(',')
                append("\"dedup_key\":").append(jsonString(TripDatabaseHelper.expenseDedupKey(e))).append(',')
                append("\"timestamp_ms\":").append(e.timestampMs).append(',')
                append("\"category\":").append(jsonString(e.category.key)).append(',')
                append("\"title\":").append(jsonString(e.title)).append(',')
                append("\"amount_uah\":").append(fmt(e.amountUah, 2)).append(',')
                append("\"odometer_km\":").append(fmt(e.odometerKm, 2)).append(',')
                append("\"liters\":").append(e.liters?.let { fmt(it, 3) } ?: "null").append(',')
                append("\"price_per_liter\":").append(e.pricePerLiter?.let { fmt(it, 2) } ?: "null").append(',')
                append("\"is_full_tank\":").append(e.isFullTank).append(',')
                append("\"is_auto_detected\":").append(e.isAutoDetected).append(',')
                append("\"linked_item_id\":").append(e.linkedItemId?.toString() ?: "null").append(',')
                append("\"note\":").append(jsonString(e.note))
                append('}')
            }
            append(']')
        }
    }

    fun buildConsumablesJsonArray(items: List<ConsumableItem>, userId: String): String {
        return buildString {
            append('[')
            items.forEachIndexed { idx, c ->
                if (idx > 0) append(',')
                append('{')
                append("\"user_id\":").append(jsonString(userId)).append(',')
                append("\"dedup_key\":").append(jsonString(TripDatabaseHelper.consumableDedupKey(c))).append(',')
                append("\"name\":").append(jsonString(c.name)).append(',')
                append("\"category\":").append(jsonString(c.category.key)).append(',')
                append("\"interval_km\":").append(c.intervalKm?.toString() ?: "null").append(',')
                append("\"interval_months\":").append(c.intervalMonths?.toString() ?: "null").append(',')
                append("\"last_done_km\":").append(fmt(c.lastDoneKm, 1)).append(',')
                append("\"last_done_time_ms\":").append(c.lastDoneTimeMs).append(',')
                append("\"accumulated_km\":").append(fmt(c.accumulatedKm, 2)).append(',')
                append("\"is_tire_set\":").append(c.isTireSet).append(',')
                append("\"tire_season\":").append(c.tireSeason?.let { jsonString(it.key) } ?: "null").append(',')
                append("\"is_mounted\":").append(c.isMounted).append(',')
                append("\"purchase_price_uah\":").append(c.purchasePriceUah?.let { fmt(it, 2) } ?: "null").append(',')
                append("\"note\":").append(jsonString(c.note))
                append('}')
            }
            append(']')
        }
    }

    fun buildUserSettingsJsonObject(
        settings: AppSettings,
        remainingTankLiters: Float,
        isTankCalibrated: Boolean,
        lastStationaryFuelPct: Float,
        userId: String
    ): String {
        return buildString {
            append('{')
            append("\"user_id\":").append(jsonString(userId)).append(',')
            append("\"odometer_km\":").append(fmt(settings.odometerKm, 2)).append(',')
            append("\"fuel_price_uah\":").append(fmt(settings.fuelPriceUah, 2)).append(',')
            append("\"maf_calibration\":").append(fmt(settings.mafCalibration, 3)).append(',')
            append("\"tank_capacity_liters\":").append(fmt(settings.tankCapacityLiters, 1)).append(',')
            append("\"remaining_tank_liters\":").append(fmt(remainingTankLiters, 2)).append(',')
            append("\"is_tank_calibrated\":").append(isTankCalibrated).append(',')
            append("\"last_stationary_fuel_pct\":").append(fmt(lastStationaryFuelPct, 2)).append(',')
            append("\"wash_reminder_days\":").append(settings.washReminderDays).append(',')
            append("\"preferred_fuel_chain\":").append(jsonString(settings.preferredFuelChain)).append(',')
            append("\"preferred_fuel_grade\":").append(jsonString(settings.preferredFuelGrade)).append(',')
            append("\"parking_lat\":").append(settings.parkingLat?.let { fmt(it, 6) } ?: "null").append(',')
            append("\"parking_lng\":").append(settings.parkingLng?.let { fmt(it, 6) } ?: "null").append(',')
            append("\"parking_timestamp_ms\":").append(settings.parkingTimestampMs)
            append('}')
        }
    }

    // ──────── Pure Kotlin JSON Deserializers ────────

    fun parseSessionJson(json: String, fallbackEmail: String = ""): SupabaseSession? {
        val root = SimpleJson.parse(json) as? Map<*, *> ?: return null
        val accessToken = root.str("access_token") ?: return null
        val refreshToken = root.str("refresh_token") ?: return null
        val userObj = root["user"] as? Map<*, *>
        val userId = userObj?.str("id") ?: root.str("user_id") ?: return null
        val email = userObj?.str("email") ?: root.str("email") ?: fallbackEmail
        val expiresAt = root.longVal("expires_at")
            ?: (System.currentTimeMillis() / 1000L + (root.longVal("expires_in") ?: 3600L))
        return SupabaseSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtSec = expiresAt,
            userId = userId,
            email = email
        )
    }

    fun parseTripsJsonArray(json: String): List<TripRecord> {
        val arr = SimpleJson.parse(json) as? List<*> ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val startMs = obj.longVal("start_time_ms") ?: return@mapNotNull null
            TripRecord(
                id = 0L,
                startTimeMs = startMs,
                endTimeMs = obj.longVal("end_time_ms") ?: startMs,
                distanceKm = obj.floatVal("distance_km") ?: 0f,
                fuelUsedLiters = obj.floatVal("fuel_used_liters") ?: 0f,
                fuelCostUah = obj.floatVal("fuel_cost_uah") ?: 0f,
                avgConsumption100Km = obj.floatVal("avg_consumption") ?: 0f,
                hybridSavedLiters = obj.floatVal("hybrid_saved_liters") ?: 0f,
                hybridSavedUah = obj.floatVal("hybrid_saved_uah") ?: 0f,
                ecoScore = obj.intVal("eco_score") ?: 0,
                careScore = obj.intVal("care_score") ?: 100,
                remainingTankLiters = obj.floatVal("remaining_tank_liters") ?: 0f,
                csvFileName = obj.str("csv_file_name")
            )
        }
    }

    fun parseExpensesJsonArray(json: String): List<ExpenseRecord> {
        val arr = SimpleJson.parse(json) as? List<*> ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val ts = obj.longVal("timestamp_ms") ?: return@mapNotNull null
            ExpenseRecord(
                id = 0L,
                timestampMs = ts,
                category = ExpenseCategory.fromKey(obj.str("category")),
                title = obj.str("title") ?: "",
                amountUah = obj.floatVal("amount_uah") ?: 0f,
                odometerKm = obj.floatVal("odometer_km") ?: 0f,
                liters = obj.floatVal("liters"),
                pricePerLiter = obj.floatVal("price_per_liter"),
                isFullTank = obj.boolVal("is_full_tank") ?: false,
                isAutoDetected = obj.boolVal("is_auto_detected") ?: false,
                linkedItemId = obj.longVal("linked_item_id"),
                note = obj.str("note") ?: ""
            )
        }
    }

    fun parseConsumablesJsonArray(json: String): List<ConsumableItem> {
        val arr = SimpleJson.parse(json) as? List<*> ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            ConsumableItem(
                id = 0L,
                name = name,
                category = ExpenseCategory.fromKey(obj.str("category")),
                intervalKm = obj.intVal("interval_km"),
                intervalMonths = obj.intVal("interval_months"),
                lastDoneKm = obj.floatVal("last_done_km") ?: 0f,
                lastDoneTimeMs = obj.longVal("last_done_time_ms") ?: 0L,
                accumulatedKm = obj.floatVal("accumulated_km") ?: 0f,
                isTireSet = obj.boolVal("is_tire_set") ?: false,
                tireSeason = TireSeason.fromKey(obj.str("tire_season")),
                isMounted = obj.boolVal("is_mounted") ?: false,
                purchasePriceUah = obj.floatVal("purchase_price_uah"),
                note = obj.str("note") ?: ""
            )
        }
    }

    fun parseUserSettingsJsonArray(json: String): CloudSettingsSnapshot? {
        val arr = SimpleJson.parse(json) as? List<*> ?: return null
        val obj = arr.firstOrNull() as? Map<*, *> ?: return null
        return CloudSettingsSnapshot(
            odometerKm = obj.floatVal("odometer_km") ?: 0f,
            fuelPriceUah = obj.floatVal("fuel_price_uah") ?: 56.5f,
            mafCalibration = obj.floatVal("maf_calibration") ?: 1.0f,
            tankCapacityLiters = obj.floatVal("tank_capacity_liters") ?: 47.0f,
            remainingTankLiters = obj.floatVal("remaining_tank_liters") ?: 32.0f,
            isTankCalibrated = obj.boolVal("is_tank_calibrated") ?: false,
            lastStationaryFuelPct = obj.floatVal("last_stationary_fuel_pct") ?: 0f,
            washReminderDays = obj.intVal("wash_reminder_days") ?: 14,
            preferredFuelChain = obj.str("preferred_fuel_chain") ?: "OKKO",
            preferredFuelGrade = obj.str("preferred_fuel_grade") ?: "A95",
            parkingLat = obj.doubleVal("parking_lat"),
            parkingLng = obj.doubleVal("parking_lng"),
            parkingTimestampMs = obj.longVal("parking_timestamp_ms") ?: 0L
        )
    }

    // ──────── Internal Networking + Token Refresh Helpers ────────

    private data class AuthedCallResult(
        val ok: Boolean,
        val body: String = "",
        val errorMsg: String = "",
        val authExpired: Boolean = false,
        val session: SupabaseSession? = null
    )

    private fun ensureFreshSession(
        baseUrl: String,
        apiKey: String,
        session: SupabaseSession,
        nowSec: Long
    ): SupabaseSession? {
        if (session.accessToken.isBlank() || session.userId.isBlank()) return null
        if (session.expiresAtSec > 0L && nowSec + 60L >= session.expiresAtSec) {
            val refreshed = refreshSession(baseUrl, apiKey, session.refreshToken, session.email)
            return if (refreshed.success) refreshed.session else null
        }
        return session
    }

    private fun postWithAuthRetry(
        baseUrl: String,
        apiKey: String,
        session: SupabaseSession,
        pathAndQuery: String,
        extraHeaders: Map<String, String>,
        bodyBuilder: (SupabaseSession) -> String
    ): AuthedCallResult {
        val url = "$baseUrl$pathAndQuery"
        var current = session
        var resp = httpRequest("POST", url, apiKey, current.accessToken, bodyBuilder(current), extraHeaders)
        if (resp.code == 401) {
            val refreshed = refreshSession(baseUrl, apiKey, current.refreshToken, current.email)
            if (!refreshed.success || refreshed.session == null) {
                return AuthedCallResult(ok = false, authExpired = true, errorMsg = refreshed.messageUk)
            }
            current = refreshed.session
            resp = httpRequest("POST", url, apiKey, current.accessToken, bodyBuilder(current), extraHeaders)
            if (resp.code == 401) {
                return AuthedCallResult(ok = false, authExpired = true, errorMsg = "HTTP 401")
            }
        }
        return if (resp.code in 200..299) {
            AuthedCallResult(ok = true, body = resp.body, session = current)
        } else {
            AuthedCallResult(
                ok = false,
                session = current,
                errorMsg = extractErrorMessage(resp.body, resp.error.ifEmpty { "HTTP ${resp.code}" })
            )
        }
    }

    private fun getWithAuthRetry(
        baseUrl: String,
        apiKey: String,
        session: SupabaseSession,
        pathAndQuery: String
    ): AuthedCallResult {
        val url = "$baseUrl$pathAndQuery"
        var current = session
        var resp = httpRequest("GET", url, apiKey, current.accessToken, body = null)
        if (resp.code == 401) {
            val refreshed = refreshSession(baseUrl, apiKey, current.refreshToken, current.email)
            if (!refreshed.success || refreshed.session == null) {
                return AuthedCallResult(ok = false, authExpired = true, errorMsg = refreshed.messageUk)
            }
            current = refreshed.session
            resp = httpRequest("GET", url, apiKey, current.accessToken, body = null)
            if (resp.code == 401) {
                return AuthedCallResult(ok = false, authExpired = true, errorMsg = "HTTP 401")
            }
        }
        return if (resp.code in 200..299) {
            AuthedCallResult(ok = true, body = resp.body, session = current)
        } else {
            AuthedCallResult(
                ok = false,
                session = current,
                errorMsg = extractErrorMessage(resp.body, resp.error.ifEmpty { "HTTP ${resp.code}" })
            )
        }
    }

    private data class HttpResp(val code: Int, val body: String, val error: String = "")

    private fun httpRequest(
        method: String,
        urlStr: String,
        apiKey: String,
        accessToken: String?,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Accept", "application/json")
                if (!accessToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }
                for ((k, v) in extraHeaders) {
                    setRequestProperty(k, v)
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { os ->
                        os.write(body.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResp(code = code, body = text)
        } catch (e: Exception) {
            HttpResp(code = 0, body = "", error = e.message ?: "мережевий збій")
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractErrorMessage(body: String, fallback: String): String {
        val obj = SimpleJson.parse(body) as? Map<*, *>
        return obj?.str("error_description")
            ?: obj?.str("msg")
            ?: obj?.str("message")
            ?: obj?.str("error")
            ?: fallback
    }

    private fun mapAuthErrorUk(raw: String): String {
        val low = raw.lowercase(Locale.US)
        return when {
            low.contains("invalid login credentials") -> "Невірний email або пароль"
            low.contains("email not confirmed") -> "Спочатку підтвердіть email за посиланням із листа"
            low.contains("already registered") || low.contains("already been registered") ->
                "Цей email вже зареєстровано — натисніть «Увійти»"
            low.contains("password") && low.contains("least") -> "Пароль має містити щонайменше 6 символів"
            low.contains("rate limit") -> "Забагато спроб, зачекайте хвилину"
            else -> raw.ifBlank { "Помилка авторизації Supabase" }
        }
    }

    private fun fmt(v: Float, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)
    private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

    private fun jsonString(s: String): String = buildString(s.length + 4) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun Map<*, *>.str(key: String): String? = this[key] as? String
    private fun Map<*, *>.longVal(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<*, *>.intVal(key: String): Int? = (this[key] as? Number)?.toInt()
    private fun Map<*, *>.floatVal(key: String): Float? = (this[key] as? Number)?.toFloat()
    private fun Map<*, *>.doubleVal(key: String): Double? = (this[key] as? Number)?.toDouble()
    private fun Map<*, *>.boolVal(key: String): Boolean? = this[key] as? Boolean

    /**
     * Minimal dependency-free recursive-descent JSON parser for pure JVM unit tests & Android runtime.
     */
    object SimpleJson {
        fun parse(text: String): Any? {
            if (text.isBlank()) return null
            return runCatching { Parser(text).parseValue() }.getOrNull()
        }

        private class Parser(private val src: String) {
            private var pos = 0

            fun parseValue(): Any? {
                skipWs()
                if (pos >= src.length) return null
                return when (val ch = src[pos]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't', 'f' -> parseBoolean()
                    'n' -> parseNull()
                    '-', in '0'..'9' -> parseNumber()
                    else -> throw IllegalArgumentException("Unexpected char '$ch' at $pos")
                }
            }

            private fun parseObject(): Map<String, Any?> {
                pos++ // skip '{'
                val map = linkedMapOf<String, Any?>()
                skipWs()
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    return map
                }
                while (pos < src.length) {
                    skipWs()
                    val key = parseString()
                    skipWs()
                    if (pos < src.length && src[pos] == ':') pos++
                    val value = parseValue()
                    map[key] = value
                    skipWs()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                    } else if (pos < src.length && src[pos] == '}') {
                        pos++
                        break
                    } else {
                        break
                    }
                }
                return map
            }

            private fun parseArray(): List<Any?> {
                pos++ // skip '['
                val list = mutableListOf<Any?>()
                skipWs()
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    return list
                }
                while (pos < src.length) {
                    list.add(parseValue())
                    skipWs()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                    } else if (pos < src.length && src[pos] == ']') {
                        pos++
                        break
                    } else {
                        break
                    }
                }
                return list
            }

            private fun parseString(): String {
                if (pos < src.length && src[pos] == '"') pos++
                val sb = StringBuilder()
                while (pos < src.length) {
                    val ch = src[pos++]
                    when (ch) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            if (pos >= src.length) break
                            when (val esc = src[pos++]) {
                                '"', '\\', '/' -> sb.append(esc)
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    if (pos + 4 <= src.length) {
                                        val hex = src.substring(pos, pos + 4)
                                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                        pos += 4
                                    }
                                }
                                else -> sb.append(esc)
                            }
                        }
                        else -> sb.append(ch)
                    }
                }
                return sb.toString()
            }

            private fun parseBoolean(): Boolean {
                return if (src.startsWith("true", pos)) {
                    pos += 4
                    true
                } else {
                    pos += 5
                    false
                }
            }

            private fun parseNull(): Any? {
                pos += 4
                return null
            }

            private fun parseNumber(): Number {
                val start = pos
                while (pos < src.length && src[pos] in "-+0123456789.eE") {
                    pos++
                }
                val raw = src.substring(start, pos)
                return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                    raw.toDouble()
                } else {
                    raw.toLong()
                }
            }

            private fun skipWs() {
                while (pos < src.length && src[pos].isWhitespace()) {
                    pos++
                }
            }
        }
    }
}
