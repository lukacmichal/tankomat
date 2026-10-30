package sk.lukac.tankomat.data.remote

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sk.lukac.tankomat.BuildConfig
import sk.lukac.tankomat.data.model.Gps
import java.util.Locale

/**
 * Cesta autom od domu k pumpám cez OSRM (OpenStreetMap) — ten istý smerovač
 * a ten istý profil `driving`, ktorým `tools/generuj_vzdialenosti.py` vyrobil
 * tabuľku nameraných vzdialeností.
 *
 * Služba `table` vráti vzdialenosti od domu ku všetkým cieľom jedným dopytom
 * (30. 10. 2026: dva ciele = 45 B odpovede). Verejný server chce slušné
 * správanie — vlastný User-Agent, nanajvýš dopyt za sekundu, žiadne hromadné
 * sťahovanie — preto po [DAVKA] cieľoch na dopyt a appka si výsledky drží
 * natrvalo (`RouteStore`): každá pumpa sa pýta raz.
 */
class OsrmRouter(
    private val stiahni: suspend (String) -> String = { url ->
        HttpClient.fetchText(url, maxVekMs = 0, hlavicky = mapOf("User-Agent" to UA))
    },
) {
    /**
     * Km po ceste od [odkial] ku každému z [ciele], v tom istom poradí; null =
     * OSRM k cieľu cestu nenašiel. Výnimka = dopyt zlyhal (sieť, server).
     */
    suspend fun kmPoCeste(odkial: Gps.Bod, ciele: List<Gps.Bod>): List<Double?> {
        val vysledok = mutableListOf<Double?>()
        ciele.chunked(DAVKA).forEachIndexed { i, davka ->
            if (i > 0) delay(1_100) // pravidlá servera: nanajvýš dopyt za sekundu
            vysledok += parse(stiahni(url(odkial, davka)), davka.size)
        }
        return vysledok
    }

    companion object {
        private const val BASE = "https://router.project-osrm.org"

        /** Cieľov na jeden dopyt — server má strop na veľkosť tabuľky. */
        const val DAVKA = 50

        private val UA = "Tankomat/${BuildConfig.VERSION_NAME} (Android app)"

        internal fun url(odkial: Gps.Bod, ciele: List<Gps.Bod>): String {
            // OSRM chce poradie „dĺžka,šírka".
            val body = (listOf(odkial) + ciele).joinToString(";") {
                String.format(Locale.ROOT, "%.6f,%.6f", it.lng, it.lat)
            }
            return "$BASE/table/v1/driving/$body?sources=0&annotations=distance&skip_waypoints=true"
        }

        /** Metre z prvého riadku tabuľky → km na desatiny. Prvý stĺpec je domov sám so sebou. */
        internal fun parse(json: String, pocet: Int): List<Double?> {
            val koren = Json.parseToJsonElement(json).jsonObject
            val kod = koren["code"]?.jsonPrimitive?.contentOrNull
            check(kod == "Ok") { "OSRM odpovedal $kod" }
            val riadok = koren["distances"]?.jsonArray?.firstOrNull()?.jsonArray
                ?: error("OSRM neposlal vzdialenosti")
            check(riadok.size == pocet + 1) {
                "OSRM poslal ${riadok.size - 1} vzdialeností namiesto $pocet"
            }
            return riadok.drop(1).map { prvok ->
                prvok.jsonPrimitive.doubleOrNull?.let { Math.round(it / 100.0) / 10.0 }
            }
        }
    }
}
