package sk.lukac.tankomat.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import sk.lukac.tankomat.data.local.SnapshotStore
import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.CzTownConfig
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.SkReferencePrice
import sk.lukac.tankomat.data.remote.CnbExchangeRate
import sk.lukac.tankomat.data.remote.UnknownTownException
import sk.lukac.tankomat.data.remote.DalioilScraper
import sk.lukac.tankomat.data.remote.MbenzinScraper

/**
 * Vyhodí sa, keď sa stránka mbenzin.cz stiahla v poriadku, ale nepodarilo
 * sa z nej vyčítať ani jednu pumpu — takmer isto to znamená, že stránka
 * zmenila HTML formát a scraper treba aktualizovať. UI to premení na
 * zrozumnú hlášku namiesto tichého prázdna.
 */
class ScrapeFormatException(source: String) : Exception(
    "Zdroj $source sa načítal, ale nedali sa z neho prečítať žiadne ceny — " +
        "asi zmenil formát stránky. Treba aktualizovať appku (scraper).",
)

/**
 * Vyhodí sa, keď sa neozvalo ani jedno mesto — takmer isto výpadok siete
 * (alebo beh na pozadí, ktorý chytil Wi-Fi na odchode).
 *
 * Podstatné je, čo sa vtedy NEstane: prázdny sken sa neuloží. Posledné
 * stiahnuté ceny ostávajú na disku a appka ich ukazuje ďalej — inak by jeden
 * neúspešný pokus (a od 7. 9. 2026 beží jeden každé ráno sám) zmazal jediné
 * čísla, ktoré človek v aute bez signálu má.
 */
class NoDataException : Exception(
    "Ceny sa nepodarilo stiahnuť — neozvalo sa ani jedno mesto. " +
        "Ukazujem posledné uložené ceny.",
)

/**
 * Stručný repository agregujúci všetky vzdialené volania.
 *
 * Cache: in-memory + posledný úspešný sken na disku ([SnapshotStore]).
 * Nikdy sa neinvaliduje automaticky — nové dáta sa stiahnu výhradne
 * ručným obnovením, dovtedy sa ukazuje posledný sken.
 */
class FuelRepository(
    private val mbenzin: MbenzinScraper,
    private val dalioil: DalioilScraper,
    private val store: SnapshotStore,
) {
    @Serializable
    data class Snapshot(
        val stations: List<CzStation>,
        val skReference: SkReferencePrice,
        val czkToEur: Double,
        val fetchedAt: Long,
        /**
         * Mestá, ktoré mbenzin.cz nepozná — vrátil na ne celoštátny zoznam.
         *
         * Predvolená hodnota je tu kvôli snapshotom uloženým pred 19. 8. 2026;
         * bez nej by sa staršie súbory nedali načítať a appka by po aktualizácii
         * začala prázdna.
         */
        val neznameMesta: List<String> = emptyList(),
    )

    @Volatile
    private var cached: Snapshot? = null

    /** In-memory cache; ak je prázdna (čerstvý štart), skús disk. */
    suspend fun lastSnapshot(): Snapshot? =
        cached ?: store.load()?.also { cached = it }

    /** Načíta všetky dáta paralelne. */
    suspend fun refresh(
        towns: List<CzTownConfig>,
        homePriceOverride: Double?,
        fuelTypeForFallback: FuelType,
    ): Snapshot = coroutineScope {
        // Posledný dobrý sken je záloha pre SK cenu nižšie — a zároveň to,
        // čo tu ostane ležať, keď sa nič nestiahne.
        val predosly = lastSnapshot()
        val stationsDeferred = towns.map { town ->
            async { runCatching { mbenzin.fetchStations(town) } }
        }
        val rateDeferred = async { CnbExchangeRate.fetchCzkToEur() }
        val skDeferred = async {
            if (homePriceOverride != null) {
                SkReferencePrice(
                    source = "ručne zadané",
                    pricesEur = mapOf(fuelTypeForFallback to homePriceOverride),
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                runCatching { dalioil.fetchPrices() }.getOrElse {
                    // Poradie je podstatné: posledná SKUTOČNE stiahnutá cena
                    // je lepší odhad než vymyslené čísla nižšie — a odkedy
                    // sa sťahuje aj na pozadí, tie vymyslené by sa do verdiktu
                    // dostali bez toho, aby o tom niekto vedel. Dátum aj zdroj
                    // ostávajú pôvodné, takže v appke je vidieť, aká je stará.
                    predosly?.skReference ?: SkReferencePrice(
                        source = "dalioil.sk (chyba — fallback)",
                        pricesEur = mapOf(
                            FuelType.NATURAL_95 to 1.50,
                            FuelType.DIESEL to 1.55,
                            FuelType.LPG to 0.80,
                        ),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
        }

        val stationResults = stationsDeferred.map { it.await() }
        // Neznáme mesto nie je výpadok siete — je to preklep v nastaveniach
        // a treba ho povedať nahlas, inak sa mesto len ticho stratí zo zoznamu.
        val nezname = stationResults.mapNotNull {
            (it.exceptionOrNull() as? UnknownTownException)?.townName
        }
        val stations = dedupeAcrossTowns(
            stationResults.flatMap { it.getOrElse { emptyList() } },
        )
        // Fetch prešiel aspoň pre jedno mesto, ale 0 pumpov → zmena formátu
        // (odlíšené od výpadku siete, keď zlyhajú všetky fetche).
        if (looksLikeFormatChange(stationResults.map { it.isSuccess }, stations.size)) {
            throw ScrapeFormatException("mbenzin.cz")
        }
        // Nič sa neozvalo → sieť. Skončiť sa musí VÝNIMKOU, nie prázdnym
        // snapshotom: `store.save` nižšie by prepísal posledné dobré ceny
        // a appka by po jednom nevydarenom ráne ostala prázdna.
        if (looksLikeNetworkOutage(stationResults.map { it.isSuccess })) {
            throw NoDataException()
        }

        val rate = rateDeferred.await()
        val sk = skDeferred.await()

        Snapshot(
            stations = stations,
            skReference = sk,
            czkToEur = rate,
            fetchedAt = System.currentTimeMillis(),
            neznameMesta = nezname,
        ).also {
            cached = it
            store.save(it)
        }
    }

    internal companion object {
        /**
         * Tá istá pumpa býva na stránkach viacerých miest (stránka mesta
         * obsahuje aj okolie) — bez deduplikácie by sa vo výsledkoch objavila
         * viackrát, zakaždým s inou vzdialenosťou. Nechávame výskyt
         * s najkratšou odhadovanou vzdialenosťou od domu (najkratšia cesta
         * je tá, ktorou by si na ňu reálne išiel).
         */
        fun dedupeAcrossTowns(stations: List<CzStation>): List<CzStation> =
            stations
                .groupBy { it.identity }
                .map { (_, dups) -> dups.minBy { it.estimatedOneWayKm } }

        /**
         * True = aspoň jeden fetch prešiel, ale nevyparsovala sa ani jedna
         * pumpa → takmer isto zmena HTML formátu (nie výpadok siete).
         * Keď zlyhajú všetky fetche (žiadny success), je to sieťová chyba,
         * nie formát — vtedy false.
         */
        fun looksLikeFormatChange(fetchOutcomes: List<Boolean>, stationCount: Int): Boolean =
            fetchOutcomes.any { it } && stationCount == 0

        /**
         * True = neprešiel ani jeden fetch → výpadok siete, nie zmena formátu.
         * Prázdny zoznam miest sem nepatrí (nie je čo sťahovať) — to nie je
         * výpadok a nemá zmysel kvôli nemu hádzať výnimku.
         */
        fun looksLikeNetworkOutage(fetchOutcomes: List<Boolean>): Boolean =
            fetchOutcomes.isNotEmpty() && fetchOutcomes.none { it }
    }
}
