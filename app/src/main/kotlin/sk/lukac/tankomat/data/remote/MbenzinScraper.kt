package sk.lukac.tankomat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.CzTownConfig
import sk.lukac.tankomat.data.model.FuelPrice
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.PriceFreshness

/**
 * Scraper pre mbenzin.cz.
 *
 * URL pattern: https://www.mbenzin.cz/Ceny-benzinu-a-nafty/{slug}
 * Stránka obsahuje pumpy v meste + okolí (~30 ks).
 *
 * ⚠ Stránka mení HTML pomerne často (v priebehu 2026 už trikrát). Aby bol
 * scraper čo najodolnejší, blok pumpy aj adresu čítame cez **Schema.org
 * značky** (`itemtype=LocalBusiness`, `itemprop`) — tie prežijú vizuálny
 * redizajn lepšie než CSS triedy. Ceny sémantické značenie nemajú, tie
 * čítame cez CSS triedy aktuálneho dizajnu (august 2026):
 *  • Blok pumpy = `[itemtype*=LocalBusiness]`.
 *  • Meno = `[itemprop=name]`.
 *  • Mesto = `[itemprop=addressLocality]`, ulica = `[itemprop=streetAddress]`
 *    (ulica nemusí byť pri každej pumpe).
 *  • Vzdialenosť od centra = `span.st-km` ("3,90 km"); fallback = prvé
 *    "X km" v texte bloku.
 *  • Ceny: bloky `div.st-price`, každý má `span.lbl` (Benzín/Nafta/LPG/CNG)
 *    a `span.val` s cenou. Cena môže chýbať — vtedy má `val` triedu `na`
 *    a text "–" (taká sa preskočí).
 *  • Čerstvosť ceny = trieda na `span.val`:
 *      – `fresh`  → CURRENT   („Cena je aktuální (k DD.MM.YYYY).")
 *      – `unsure` → UNCERTAIN („Cenou si nejsme úplně jistí…")
 *      – `old`    → STALE     („Aktuální cenu už nám tady dlouho nikdo nenahlásil…")
 *  • Dátum aktualizácie: prvý dátum DD.MM.YYYY v `title` atribúte `div.st-price`.
 *
 * Ak sa formát zmení tak, že sa nenačíta nič, [FuelRepository] to rozpozná
 * (fetch prešiel, ale 0 pumpov) a UI zobrazí zrozumnú hlášku namiesto ticha.
 *
 * ⚠ **Neznáme mesto nevráti chybu.** Keď slug neexistuje, mbenzin.cz odpovie
 * HTTP 200 a presmeruje na celoštátny zoznam — 58 pumpov z celého Česka
 * (Louny, Písek, České Budějovice…). Bez kontroly ich appka brala ako pumpy
 * v okolí a každej priradila vzdialenosť, ktorú si k tomu mestu zadal.
 * Overené 19. 8. 2026: `Horni-Lidec`, `Zadverice` aj vymyslený
 * `Uplne-Vymysleny-Nesmysl-123` vrátili bajt po bajte tú istú stránku.
 * Preto [overStranku] — celoštátnu stránku spozná podľa nadpisu.
 */
/**
 * Slug, ktorý mbenzin.cz nepozná — namiesto chyby poslal celoštátny zoznam.
 *
 * Nesie meno mesta, aby vedelo UI povedať, ktorý riadok v nastaveniach je zlý.
 */
class UnknownTownException(val townName: String) : Exception(
    "mbenzin.cz nepozná mesto „$townName“ — vrátil celoštátny zoznam pumpov",
)

class MbenzinScraper(private val http: HttpClient = HttpClient) {

    suspend fun fetchStations(town: CzTownConfig): List<CzStation> = withContext(Dispatchers.Default) {
        val url = "https://www.mbenzin.cz/Ceny-benzinu-a-nafty/${town.slug}"
        val html = HttpClient.fetchText(url)
        val doc = Jsoup.parse(html, url)
        parse(doc, town)
    }

    internal fun parse(doc: Document, town: CzTownConfig): List<CzStation> {
        overStranku(doc, town)
        val blocks = doc.select("[itemtype*=LocalBusiness]")

        return blocks.mapNotNull { block ->
            val name = block.selectFirst("[itemprop=name]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            val locality = block.selectFirst("[itemprop=addressLocality]")?.text()?.trim().orEmpty()
            val street = block.selectFirst("[itemprop=streetAddress]")?.text()?.trim().orEmpty()
            if (locality.isEmpty() && street.isEmpty()) return@mapNotNull null

            val distanceKm = parseDistanceKm(block)

            val prices = parsePrices(block)
            // Ak pumpa nemá ani jednu z nami sledovaných cien, nemá zmysel ju vracať.
            if (prices.isEmpty()) return@mapNotNull null

            CzStation(
                name = name,
                town = locality,
                address = street,
                distanceFromCityCenterKm = distanceKm,
                prices = prices,
                townSlug = town.slug,
                townDistanceFromHomeKm = town.distanceFromHomeKm,
            )
        }.distinctBy { it.identity }
    }

    /**
     * Je táto stránka naozaj o meste, ktoré sme pýtali?
     *
     * Nadpis stránky mesta znie „Ceny benzínu a nafty Vsetin", celoštátnej
     * „Ceny benzínu a nafty v ČR". Porovnáva sa bez diakritiky a bez ohľadu
     * na veľkosť písmen, lebo slug ju nemá („Brumov-Bylnice" vs nadpis
     * „Brumov Bylnice") a mesto z nastavení môže byť napísané ako chce.
     */
    private fun overStranku(doc: Document, town: CzTownConfig) {
        val nadpis = bezDiakritiky(
            doc.selectFirst("h1")?.text() ?: doc.title().orEmpty(),
        )
        // Nadpis sa nenašiel vôbec — to je zmena formátu, nie neznáme mesto;
        // tú rieši FuelRepository podľa počtu pumpov, tu sa do nej nemiešame.
        if (nadpis.isBlank()) return

        val hladane = listOf(town.slug, town.displayName)
            .map(::bezDiakritiky)
            .filter { it.isNotBlank() }
        if (hladane.none { nadpis.contains(it) }) {
            throw UnknownTownException(town.displayName.ifBlank { town.slug })
        }
    }

    private fun bezDiakritiky(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun parseDistanceKm(block: Element): Double? {
        // Primárne CSS trieda aktuálneho dizajnu, fallback = prvé "X km" v texte.
        val txt = block.selectFirst("span.st-km")?.text()
            ?: KM_REGEX.find(block.text())?.value
            ?: return null
        return txt.replace("km", "").replace(",", ".").trim().toDoubleOrNull()
    }

    private fun parsePrices(block: Element): Map<FuelType, FuelPrice> {
        val cells = block.select("div.st-price")
        val out = mutableMapOf<FuelType, FuelPrice>()
        for (cell in cells) {
            val label = cell.selectFirst("span.lbl")?.text()?.trim() ?: continue
            val fuel = labelToFuel(label) ?: continue
            val priceEl = cell.selectFirst("span.val") ?: continue
            // Chýbajúca cena má triedu "na" a text "–" — preskoč.
            if (priceEl.hasClass("na")) continue
            val price = priceEl.text().trim().replace(",", ".").toDoubleOrNull() ?: continue

            val freshness = when {
                priceEl.hasClass("fresh") -> PriceFreshness.CURRENT
                priceEl.hasClass("unsure") -> PriceFreshness.UNCERTAIN
                priceEl.hasClass("old") -> PriceFreshness.STALE
                // Neznáme značenie — radšej priznať neistotu než tvrdiť aktuálnosť.
                else -> PriceFreshness.UNCERTAIN
            }
            val updatedOn = DATE_REGEX.find(cell.attr("title"))?.value

            out[fuel] = FuelPrice(priceCzk = price, updatedOn = updatedOn, freshness = freshness)
        }
        return out
    }

    private fun labelToFuel(label: String): FuelType? = when (label.lowercase()) {
        "benzín", "benzin", "natural", "natural 95" -> FuelType.NATURAL_95
        "nafta", "diesel" -> FuelType.DIESEL
        "lpg" -> FuelType.LPG
        "cng" -> FuelType.CNG
        else -> null
    }

    private companion object {
        val DATE_REGEX = Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")
        val KM_REGEX = Regex("\\d+[.,]\\d+ km")
    }
}
