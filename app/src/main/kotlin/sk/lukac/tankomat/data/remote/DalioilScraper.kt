package sk.lukac.tankomat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.SkReferencePrice

/**
 * Scraper pre dalioil.sk — slúži ako referenčná „domáca cena" v EUR.
 *
 * Stránka má ceny ako carousel obrázkov, kde:
 *   `<img alt="1,734 €" src=".../natural-ultra-95-1.png" />`
 * Z URL filename rozpoznáme typ paliva, z `alt` extrahujeme cenu.
 *
 * URL pattern: https://dalioil.sk/{mesto}/  (napr. domovske-mesto)
 */
class DalioilScraper(private val http: HttpClient = HttpClient) {

    suspend fun fetchPrices(citySlug: String = "domovske-mesto"): SkReferencePrice =
        withContext(Dispatchers.Default) {
            val url = "https://dalioil.sk/$citySlug/"
            val html = HttpClient.fetchText(url)
            val doc = Jsoup.parse(html, url)
            SkReferencePrice(
                source = "dalioil.sk/$citySlug",
                pricesEur = parse(doc),
                updatedAt = System.currentTimeMillis(),
            )
        }

    internal fun parse(doc: Document): Map<FuelType, Double> {
        // Vezmeme všetky <img> v carouseloch s alt obsahujúcim "€".
        val imgs = doc.select("img[alt*=€]")
        val out = mutableMapOf<FuelType, Double>()
        for (img in imgs) {
            val src = img.attr("src")
            val alt = img.attr("alt")
            val fuel = filenameToFuel(src) ?: continue
            val price = Regex("([0-9]+[,.][0-9]+)").find(alt)
                ?.groupValues?.getOrNull(1)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?: continue
            // Ak by sa to zopakovalo, ber prvú hodnotu (carousel niekedy duplikuje).
            out.putIfAbsent(fuel, price)
        }
        return out
    }

    private fun filenameToFuel(src: String): FuelType? {
        val lower = src.lowercase()
        return when {
            "natural-ultra-95" in lower || "natural95" in lower -> FuelType.NATURAL_95
            // "Diesel Ultra 55" je špeciálka, "diesel-ultra" je bežná. Obe mapujem na DIESEL,
            // ale Diesel Ultra 55 prepíše bežnú diesel-ultra cenu nižšie cez putIfAbsent
            // → ber prvú = bežný Diesel Ultra (poradie v HTML).
            "diesel-ultra-55" in lower -> null
            "diesel-ultra" in lower || "diesel" in lower -> FuelType.DIESEL
            "lpg" in lower -> FuelType.LPG
            "super-ultra-100" in lower -> null // Super 100 sa nepoužíva ako referencia
            "adblue" in lower -> null
            else -> null
        }
    }
}
