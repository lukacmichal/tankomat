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
import sk.lukac.tankomat.data.remote.DalioilScraper
import sk.lukac.tankomat.data.remote.MbenzinScraper

/**
 * Zdroj sa stiahol v poriadku, ale nedali sa z neho prečítať žiadne ceny —
 * takmer isto zmenil formát a scraper treba aktualizovať. UI to premení na
 * zrozumnú hlášku namiesto tichého prázdna.
 */
class ScrapeFormatException(source: String) : Exception(
    "Zdroj $source sa načítal, ale nedali sa z neho prečítať žiadne ceny — " +
        "asi zmenil formát. Treba aktualizovať appku.",
)

/**
 * Ceny z benzin.cz sa nepodarilo stiahnuť ([dovod] je krátko prečo).
 *
 * Podstatné je, čo sa vtedy NEstane: prázdny sken sa neuloží. Posledné
 * stiahnuté ceny ostávajú na disku a appka ich ukazuje ďalej — inak by jeden
 * neúspešný pokus (každé ráno beží jeden sám) zmazal jediné čísla, ktoré
 * človek v aute bez signálu má.
 */
class NoDataException(val dovod: String) : Exception(
    "Ceny z benzin.cz sa nepodarilo stiahnuť: $dovod.",
)

/**
 * Agreguje všetky vzdialené volania: benzin.cz (mbenzin.cz) pre CZ,
 * dalioil.sk pre SK, ČNB pre kurz — pri jednom obnovení po jednom dopyte.
 * Pumpám mimo tabuľky vzdialeností k tomu [trasy] doplnia cestu autom (OSRM).
 *
 * Cache: in-memory + posledný úspešný sken na disku ([SnapshotStore]).
 */
class FuelRepository(
    private val mbenzin: MbenzinScraper,
    private val dalioil: DalioilScraper,
    private val store: SnapshotStore,
    private val trasy: TrasyKPumpam,
) {
    @Serializable
    data class Snapshot(
        /** Pumpy z benzin.cz; ceny len pre palivo [mbenzinFuel]. */
        val stations: List<CzStation>,
        val skReference: SkReferencePrice,
        val czkToEur: Double,
        val fetchedAt: Long,
        val mbenzinFuel: FuelType? = null,
        /**
         * 1 = do 1.21 (v `stations` mohli byť odhady benzinmapy), 2 = 1.22–1.24
         * (pumpy mimo tabuľky mali vzdušnú čiaru × 1,25), 3 = od 1.25 (len cesty).
         * Starší sken sa nenačíta, aby sa odhady neukázali ako ceny či kilometre.
         */
        val format: Int = 1,
    )

    @Volatile
    private var cached: Snapshot? = null

    /** In-memory cache; ak je prázdna (čerstvý štart), skús disk. */
    suspend fun lastSnapshot(): Snapshot? =
        cached ?: store.load()?.takeIf { it.format >= FORMAT }?.also { cached = it }

    /** Načíta CZ ceny, SK cenu a kurz paralelne. Pri neúspechu benzin.cz nič neuloží. */
    suspend fun refresh(
        fuel: FuelType,
        towns: List<CzTownConfig>,
        searchRadiusKm: Double,
        homePriceOverride: Double?,
    ): Snapshot = coroutineScope {
        val predosly = lastSnapshot()
        val stationsDeferred = async {
            runCatching { mbenzin.fetchStations(fuel, searchRadiusKm, towns) }
        }
        val rateDeferred = async { CnbExchangeRate.fetchCzkToEur() }
        val skDeferred = async {
            if (homePriceOverride != null) {
                SkReferencePrice(
                    source = "ručne zadané",
                    pricesEur = mapOf(fuel to homePriceOverride),
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                runCatching { dalioil.fetchPrices() }.getOrElse {
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

        val stations = trasy.dopln(
            stationsDeferred.await().getOrElse { t ->
                throw if (t is ScrapeFormatException) t else NoDataException(popis(t))
            },
        )

        Snapshot(
            stations = stations,
            skReference = skDeferred.await(),
            czkToEur = rateDeferred.await(),
            fetchedAt = System.currentTimeMillis(),
            mbenzinFuel = fuel,
            format = FORMAT,
        ).also {
            cached = it
            store.save(it)
        }
    }

    private fun popis(t: Throwable): String = when (t) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketTimeoutException,
        -> "bez spojenia so serverom"
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    companion object {
        const val FORMAT = 3
    }
}
