package ua.odesa.scrossdash.data.network

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class FuelChain(val key: String, val labelUk: String, val aliases: List<String>) {
    OKKO("OKKO", "OKKO", listOf("окко", "okko")),
    WOG("WOG", "WOG", listOf("wog", "вог")),
    UKRNAFTA("UKRNAFTA", "Укрнафта", listOf("ukrnafta", "укрнафта")),
    UPG("UPG", "UPG", listOf("upg")),
    SOCAR("SOCAR", "SOCAR", listOf("socar", "сокар")),
    AVG_UA("AVG_UA", "Середня UA", emptyList());

    companion object {
        fun fromKey(key: String?): FuelChain {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: OKKO
        }
    }
}

enum class FuelGrade(val key: String, val labelUk: String) {
    A95("A95", "А-95"),
    A95_PLUS("A95_PLUS", "А-95+");

    companion object {
        fun fromKey(key: String?): FuelGrade {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: A95
        }
    }
}

data class FuelChainPrices(
    val a95: Float?,
    val a95Plus: Float?
)

data class FuelPriceFetchResult(
    val success: Boolean,
    val priceUah: Float? = null,
    val chain: FuelChain,
    val grade: FuelGrade,
    val allChains: Map<FuelChain, FuelChainPrices> = emptyMap(),
    val messageUk: String = ""
)

object UaFuelPriceClient {

    private const val CHAINS_URL = "https://index.minfin.com.ua/ua/markets/fuel/tm/"
    private const val SUMMARY_URL = "https://index.minfin.com.ua/ua/markets/fuel/"
    private const val TIMEOUT_MS = 8_000

    private val trRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val cellRegex = Regex("<t[hd][^>]*>(.*?)</t[hd]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tagRegex = Regex("<[^>]+>")

    /**
     * Parses Minfin operator fuel prices table (`https://index.minfin.com.ua/ua/markets/fuel/tm/`).
     * Table rows may have 6 cells (Operator, A95+, A95, A92, DP, Gas) or 7 cells with a spacer cell after Operator.
     */
    fun parseMinfinChainsHtml(html: String): Map<FuelChain, FuelChainPrices> {
        val result = linkedMapOf<FuelChain, FuelChainPrices>()
        val allA95 = mutableListOf<Float>()
        val allA95Plus = mutableListOf<Float>()

        for (trMatch in trRegex.findAll(html)) {
            val rawCells = cellRegex.findAll(trMatch.groupValues[1])
                .map { cleanHtmlCell(it.groupValues[1]) }
                .toList()
            if (rawCells.size < 4) continue

            val operatorRaw = rawCells[0].lowercase(Locale.ROOT).trim()
            if (operatorRaw.isEmpty() || operatorRaw.contains("оператор")) continue

            // Minfin often emits an empty column right after the operator logo/name
            val priceCells = if (rawCells.size >= 7 && rawCells[1].isEmpty()) {
                rawCells.drop(2)
            } else {
                rawCells.drop(1)
            }
            if (priceCells.size < 2) continue

            val a95Plus = parsePrice(priceCells.getOrNull(0))
            val a95 = parsePrice(priceCells.getOrNull(1))
            if (a95 == null && a95Plus == null) continue

            a95?.let { allA95.add(it) }
            a95Plus?.let { allA95Plus.add(it) }

            val matchedChain = FuelChain.entries.firstOrNull { chain ->
                chain.aliases.any { alias -> operatorRaw.contains(alias) }
            }
            if (matchedChain != null) {
                result[matchedChain] = FuelChainPrices(a95 = a95, a95Plus = a95Plus)
            }
        }

        if (allA95.isNotEmpty() || allA95Plus.isNotEmpty()) {
            val avgA95 = allA95.takeIf { it.isNotEmpty() }?.let { (it.sum() / it.size) }
            val avgA95Plus = allA95Plus.takeIf { it.isNotEmpty() }?.let { (it.sum() / it.size) }
            result[FuelChain.AVG_UA] = FuelChainPrices(
                a95 = avgA95?.let { ((it * 100f).toInt() / 100f) },
                a95Plus = avgA95Plus?.let { ((it * 100f).toInt() / 100f) }
            )
        }

        return result
    }

    /**
     * Parses Minfin Ukraine summary table (`https://index.minfin.com.ua/ua/markets/fuel/`).
     */
    fun parseMinfinSummaryHtml(html: String): FuelChainPrices? {
        var a95: Float? = null
        var a95Plus: Float? = null

        for (trMatch in trRegex.findAll(html)) {
            val cells = cellRegex.findAll(trMatch.groupValues[1])
                .map { cleanHtmlCell(it.groupValues[1]) }
                .filter { it.isNotEmpty() }
                .toList()
            if (cells.size < 2) continue

            val title = cells[0].lowercase(Locale.ROOT)
            val price = cells.drop(1).firstNotNullOfOrNull { parsePrice(it) } ?: continue
            when {
                title.contains("95") && (title.contains("прем") || title.contains("+")) -> {
                    if (a95Plus == null) a95Plus = price
                }
                title.contains("а-95") || title.contains("а 95") -> {
                    if (a95 == null) a95 = price
                }
            }
        }
        return if (a95 != null || a95Plus != null) {
            FuelChainPrices(a95 = a95, a95Plus = a95Plus)
        } else {
            null
        }
    }

    fun fetchFuelPrice(chain: FuelChain, grade: FuelGrade): FuelPriceFetchResult {
        return try {
            if (chain == FuelChain.AVG_UA) {
                val summaryHtml = downloadHtml(SUMMARY_URL)
                val summary = summaryHtml?.let { parseMinfinSummaryHtml(it) }
                val summaryPrice = summary?.let { if (grade == FuelGrade.A95_PLUS) it.a95Plus else it.a95 }
                if (summaryPrice != null) {
                    return FuelPriceFetchResult(
                        success = true,
                        priceUah = summaryPrice,
                        chain = chain,
                        grade = grade,
                        allChains = mapOf(FuelChain.AVG_UA to summary),
                        messageUk = String.format(Locale.US, "%s %s: %.2f грн/л", chain.labelUk, grade.labelUk, summaryPrice)
                    )
                }
            }

            val chainsHtml = downloadHtml(CHAINS_URL)
                ?: return FuelPriceFetchResult(
                    success = false,
                    chain = chain,
                    grade = grade,
                    messageUk = "Не вдалося завантажити ціни АЗС (перевірте інтернет)"
                )

            val parsed = parseMinfinChainsHtml(chainsHtml)
            val entry = parsed[chain] ?: parsed[FuelChain.AVG_UA]
            val selected = entry?.let {
                if (grade == FuelGrade.A95_PLUS) it.a95Plus ?: it.a95 else it.a95 ?: it.a95Plus
            }

            if (selected != null) {
                FuelPriceFetchResult(
                    success = true,
                    priceUah = selected,
                    chain = chain,
                    grade = grade,
                    allChains = parsed,
                    messageUk = String.format(Locale.US, "%s %s: %.2f грн/л", chain.labelUk, grade.labelUk, selected)
                )
            } else {
                FuelPriceFetchResult(
                    success = false,
                    chain = chain,
                    grade = grade,
                    allChains = parsed,
                    messageUk = "Не знайдено ціну ${chain.labelUk} (${grade.labelUk}) у таблиці Мінфіну"
                )
            }
        } catch (e: Exception) {
            FuelPriceFetchResult(
                success = false,
                chain = chain,
                grade = grade,
                messageUk = "Помилка оновлення цін АЗС: ${e.message ?: "мережевий збій"}"
            )
        }
    }

    private fun downloadHtml(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun cleanHtmlCell(raw: String): String {
        return raw
            .replace("&nbsp;", " ")
            .replace(tagRegex, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parsePrice(cell: String?): Float? {
        if (cell.isNullOrBlank()) return null
        val match = Regex("(\\d{2,3}[.,]\\d{1,2})").find(cell) ?: return null
        val value = match.groupValues[1].replace(',', '.').toFloatOrNull() ?: return null
        return value.takeIf { it in 30f..200f }
    }
}
