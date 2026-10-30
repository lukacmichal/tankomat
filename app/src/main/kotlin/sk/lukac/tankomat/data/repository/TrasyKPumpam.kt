package sk.lukac.tankomat.data.repository

import kotlinx.coroutines.CancellationException
import sk.lukac.tankomat.data.local.RouteStore
import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.Gps
import sk.lukac.tankomat.data.model.StationDistances
import sk.lukac.tankomat.data.remote.OsrmRouter

/**
 * Doplní pumpám, ktoré tabuľka nameraných vzdialeností nepozná, skutočnú
 * cestu autom ([CzStation.roadKm]) — z disku, a čo tam ešte nie je, dopytom
 * na OSRM. Do 1.24 dostali také pumpy vzdušnú čiaru × 1,25.
 *
 * Keď OSRM nejde (bez siete, server preťažený), sken sa kvôli tomu nezahodí:
 * pumpy ostanú bez trasy, pri ďalšom obnovení sa appka spýta znova a dovtedy
 * počíta s cestou do obce či mesta z Nastavení — alebo pumpu nezaráta.
 */
class TrasyKPumpam(
    private val router: OsrmRouter,
    private val store: RouteStore,
    private val domov: Gps.Bod = Gps.DOMOV,
) {
    suspend fun dopln(stanice: List<CzStation>): List<CzStation> {
        val bezTabulky = stanice.filter { it.bod != null && StationDistances.zTabulky(it) == null }
        if (bezTabulky.isEmpty()) return stanice

        val ulozene = store.nacitaj()
        val chybaju = bezTabulky.mapNotNull { it.bod }.distinct()
            .filter { RouteStore.kluc(domov, it) !in ulozene }
        val nove = if (chybaju.isEmpty()) emptyMap() else spytaj(chybaju)
        val trasy = ulozene + nove
        if (nove.isNotEmpty()) store.uloz(trasy)

        return stanice.map { st ->
            val km = st.bod?.let { trasy[RouteStore.kluc(domov, it)] }
            if (km != null && StationDistances.zTabulky(st) == null) st.copy(roadKm = km) else st
        }
    }

    private suspend fun spytaj(body: List<Gps.Bod>): Map<String, Double> = try {
        body.zip(router.kmPoCeste(domov, body))
            .mapNotNull { (bod, km) -> km?.let { RouteStore.kluc(domov, bod) to it } }
            .toMap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }
}
