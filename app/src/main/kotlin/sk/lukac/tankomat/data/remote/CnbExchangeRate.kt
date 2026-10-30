package sk.lukac.tankomat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Oficiálny denný kurz ČNB. Plain-text súbor, pipe-delimited.
 *
 * Príklad obsahu:
 * ```
 * 04.05.2026 #84
 * země|měna|množství|kód|kurz
 * ...
 * EMU|euro|1|EUR|24,395
 * ...
 * ```
 *
 * Vraciame koeficient na prevod **CZK → EUR**, čiže `1 / kurz_v_riadku`.
 */
object CnbExchangeRate {

    private const val URL =
        "https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/kurzy-devizoveho-trhu/denni_kurz.txt"

    /** Konzervatívny fallback ak ČNB nedostupné. */
    private const val FALLBACK_CZK_PER_EUR = 24.5

    suspend fun fetchCzkToEur(): Double = withContext(Dispatchers.IO) {
        runCatching { parseCzkPerEur(HttpClient.fetchText(URL, HttpClient.TTL_KURZ_MS)) }
            .getOrDefault(FALLBACK_CZK_PER_EUR)
            .let { 1.0 / it }
    }

    internal fun parseCzkPerEur(text: String): Double {
        for (line in text.lineSequence()) {
            val parts = line.split('|')
            if (parts.size >= 5 && parts[3].trim() == "EUR") {
                val amount = parts[2].trim().toDoubleOrNull() ?: 1.0
                val rate = parts[4].trim().replace(',', '.').toDoubleOrNull() ?: continue
                return rate / amount
            }
        }
        return FALLBACK_CZK_PER_EUR
    }
}
