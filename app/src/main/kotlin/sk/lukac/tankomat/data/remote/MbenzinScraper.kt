package sk.lukac.tankomat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sk.lukac.tankomat.BuildConfig
import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.CzTownConfig
import sk.lukac.tankomat.data.model.FuelPrice
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.Gps
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.model.StationDistances
import sk.lukac.tankomat.data.repository.ScrapeFormatException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Ceny z mbenzin.cz cez dáta ich mapy cien (`/Mapa-cen/data`).
 *
 * Jeden dopyt vráti všetky pumpy v ČR pre jedno palivo aj so súradnicami
 * (2 383 pri nafte, 28. 10. 2026: 103 kB cez gzip, rozbalené 453 kB).
 * Stránky miest by na to isté okolie potrebovali štyri dopyty. Server
 * neposiela ETag ani Last-Modified, takže každé skutočné stiahnutie je
 * celé — šetrí len cache v [HttpClient].
 *
 * Prístup je cez osobný kľúč (od 28. 10. 2026) v hlavičke `X-Mbenzin-Klic`,
 * nikdy v adrese. Podmienka kľúča: pri cenách uvádzať „Ceny: mbenzin.cz".
 *
 * Čo mapa nemá:
 *  • **obec a ulicu s diakritikou** — vyčítajú sa z adresy detailu pumpy
 *    (`/Ceny-benzinu-a-nafty/{obec}/{meno-ulica}/{id}`) a diakritiku vráti
 *    [StationDistances.sDiakritikou];
 *  • **farbu čerstvosti** — len deň poslednej ceny („Dnes", „Včera",
 *    „24.10.2026"), hodinu nie. Prahy [cerstvost] sú zo stránky Brumova
 *    28. 10. 2026: ceny staré 4 dni boli zelené, 13 dní oranžová, 19 dní
 *    červená.
 */
class MbenzinScraper(private val klic: String = BuildConfig.MBENZIN_KLIC) {

    suspend fun fetchStations(
        fuel: FuelType,
        radiusKm: Double,
        towns: List<CzTownConfig>,
    ): List<CzStation> {
        check(klic.isNotBlank()) {
            "Appka nemá prístupový kľúč k mbenzin.cz (MBENZIN_KLIC v nas-credentials.local)."
        }
        val json = HttpClient.fetchText(
            url = "$BASE/Mapa-cen/data?fuel=${palivo(fuel)}&ccs=0",
            maxVekMs = HttpClient.TTL_MBENZIN_MS,
            hlavicky = mapOf(HLAVICKA_KLUCA to klic),
        )
        return withContext(Dispatchers.Default) {
            parse(json, fuel, radiusKm, towns, LocalDate.now(PRAHA))
        }
    }

    internal fun parse(
        json: String,
        fuel: FuelType,
        radiusKm: Double,
        towns: List<CzTownConfig>,
        dnes: LocalDate,
    ): List<CzStation> {
        val text = json.trimStart()
        // Bez platného kľúča mbenzin.cz pošle namiesto dát stránku s ochranou.
        check(text.startsWith("{")) {
            "mbenzin.cz neposlal ceny, ale stránku — prístupový kľúč asi neplatí alebo bol obmedzený."
        }
        val markers = runCatching {
            Json.parseToJsonElement(text).jsonObject["markers"]?.jsonArray
        }.getOrNull()
        if (markers.isNullOrEmpty()) throw ScrapeFormatException("mbenzin.cz")

        val mesta = towns.associateBy { StationDistances.norm(it.displayName) }
        val out = mutableListOf<CzStation>()
        for (prvok in markers) {
            val m = prvok as? JsonObject ?: continue
            val lat = m.cislo("lat") ?: continue
            val lng = m.cislo("lng") ?: continue
            // Len predvýber z celého Česka: cesta nie je nikdy kratšia než
            // vzdušná čiara, takže pumpa za okruhom tu nemôže byť v okruhu ani
            // po ceste. Skutočný okruh (po ceste) stráži MainViewModel.
            if (Gps.odDomu(lat, lng) > radiusKm) continue

            val cena = m.cislo("price")?.takeIf { it > 0 } ?: continue
            val retazec = m.text("chain")
            val meno = m.text("name").ifBlank { retazec }
            if (meno.isBlank()) continue

            val (obec, ulica) = zAdresy(m.text("url"), meno)
            val den = denCeny(m.text("fresh"), dnes)
            val mesto = mesta[StationDistances.norm(obec)]

            out += CzStation(
                name = meno,
                town = obec,
                address = ulica,
                distanceFromCityCenterKm = null,
                prices = mapOf(
                    fuel to FuelPrice(
                        priceCzk = cena,
                        updatedOn = den?.let(::datum),
                        freshness = cerstvost(den, dnes),
                    ),
                ),
                townSlug = mesto?.slug ?: obec,
                townDistanceFromHomeKm = mesto?.distanceFromHomeKm,
                id = m.text("id"),
                lat = lat,
                lng = lng,
            )
        }
        return out.distinctBy { it.kluc }
    }

    private fun JsonObject.text(meno: String): String =
        this[meno]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.cislo(meno: String): Double? =
        this[meno]?.jsonPrimitive?.doubleOrNull

    companion object {
        private const val BASE = "https://www.mbenzin.cz"
        private const val HLAVICKA_KLUCA = "X-Mbenzin-Klic"
        private val PRAHA: ZoneId = ZoneId.of("Europe/Prague")
        private val DATUM = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")

        /** Do koľkých dní je cena zelená a do koľkých oranžová. */
        const val AKTUALNA_DNI = 5L
        const val NEISTA_DNI = 14L

        internal fun palivo(fuel: FuelType): String = when (fuel) {
            FuelType.NATURAL_95 -> "n95"
            FuelType.DIESEL -> "diesel"
            FuelType.LPG -> "lpg"
            FuelType.CNG -> "cng"
        }

        /**
         * Obec a ulica z adresy detailu:
         * `/Ceny-benzinu-a-nafty/Valasske-Klobouky/EuroOil-Cyrilometodejska-666/17010`
         * → („Valašské Klobouky", „Cyrilometodějská 666"). Druhá časť je meno
         * pumpy a za ním ulica; meno sa odreže po slovách.
         */
        internal fun zAdresy(url: String, meno: String): Pair<String, String> {
            val casti = url.trim('/').split('/')
            val obec = casti.getOrNull(1).orEmpty().replace('-', ' ')
            val slova = casti.getOrNull(2).orEmpty().split('-').filter { it.isNotBlank() }
            val menoSlova = StationDistances.norm(meno).split(' ').filter { it.isNotBlank() }
            val zaMenom = if (
                slova.size >= menoSlova.size &&
                slova.take(menoSlova.size).map(StationDistances::norm) == menoSlova
            ) {
                slova.drop(menoSlova.size)
            } else {
                slova
            }
            return StationDistances.sDiakritikou(obec) to
                StationDistances.sDiakritikou(zaMenom.joinToString(" "))
        }

        internal fun denCeny(fresh: String, dnes: LocalDate): LocalDate? =
            when (StationDistances.norm(fresh)) {
                "dnes" -> dnes
                "vcera" -> dnes.minusDays(1)
                else -> DATUM.find(fresh)?.destructured?.let { (d, m, r) ->
                    runCatching { LocalDate.of(r.toInt(), m.toInt(), d.toInt()) }.getOrNull()
                }
            }

        internal fun cerstvost(den: LocalDate?, dnes: LocalDate): PriceFreshness {
            if (den == null) return PriceFreshness.UNCERTAIN
            val dni = ChronoUnit.DAYS.between(den, dnes)
            return when {
                dni <= AKTUALNA_DNI -> PriceFreshness.CURRENT
                dni <= NEISTA_DNI -> PriceFreshness.UNCERTAIN
                else -> PriceFreshness.STALE
            }
        }

        internal fun datum(den: LocalDate): String = "${den.dayOfMonth}.${den.monthValue}.${den.year}"
    }
}
