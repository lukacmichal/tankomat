package sk.lukac.tankomat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.FuelPrice
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.remote.UnknownTownException

class FuelRepositoryTest {

    private fun javornik(townSlug: String, townKm: Double, centerKm: Double) = CzStation(
        name = "Javorník",
        town = "Štítná nad Vláří 414",
        address = "",
        distanceFromCityCenterKm = centerKm,
        prices = mapOf(
            FuelType.DIESEL to FuelPrice(40.30, "25.06.2026", PriceFreshness.STALE),
        ),
        townSlug = townSlug,
        townDistanceFromHomeKm = townKm,
    )

    @Test
    fun `same station from two town pages collapses to one row`() {
        // Javorník je na stránke Brumova aj Slavičína. Od 16. 8. 2026 dostane
        // v oboch prípadoch TÚ ISTÚ vzdialenosť — berie sa z nameranej tabuľky
        // podľa pumpy, nie z mesta, na ktorého stránke sa práve objavila.
        // Predtým to bolo rôzne podľa toho, odkiaľ sa načítala.
        val fromBrumov = javornik("Brumov-Bylnice", 20.0, 3.67)
        val fromSlavicin = javornik("Slavicin", 30.0, 8.32)

        val deduped = FuelRepository.dedupeAcrossTowns(listOf(fromSlavicin, fromBrumov))

        assertEquals(1, deduped.size)
        assertEquals(26.2, deduped.first().estimatedOneWayKm, 0.001)
        assertEquals(
            "z ktorej stránky sa načítala, už nesmie meniť vzdialenosť",
            fromBrumov.estimatedOneWayKm,
            fromSlavicin.estimatedOneWayKm,
            0.001,
        )
    }

    @Test
    fun `different stations are kept`() {
        val a = javornik("Brumov-Bylnice", 20.0, 3.67)
        val b = a.copy(name = "Iná pumpa")

        assertEquals(2, FuelRepository.dedupeAcrossTowns(listOf(a, b)).size)
    }

    @Test
    fun `fetch ok but zero stations is a format change`() {
        // Aspoň jedno mesto sa stiahlo (true), ale 0 pumpov.
        assertTrue(FuelRepository.looksLikeFormatChange(listOf(true, false), 0))
        assertTrue(FuelRepository.looksLikeFormatChange(listOf(true), 0))
    }

    @Test
    fun `all fetches failed is a network error, not format change`() {
        // Žiadny fetch neprešiel → sieť, nie formát.
        assertFalse(FuelRepository.looksLikeFormatChange(listOf(false, false), 0))
        assertFalse(FuelRepository.looksLikeFormatChange(emptyList(), 0))
    }

    @Test
    fun `some stations parsed is not a format change`() {
        assertFalse(FuelRepository.looksLikeFormatChange(listOf(true, true), 5))
    }

    // ── výpadok siete nesmie zmazať posledné ceny ────────────────────────────

    @Test
    fun `no fetch succeeded is a network outage, not a format change`() {
        // Toto je jediný prípad, keď sa prázdny sken NESMIE uložiť: keby sa
        // uložil, jedno nevydarené ráno (a od 7. 9. 2026 beží jedno každý deň
        // samo) zmaže posledné ceny a appka v aute bez signálu ostane prázdna.
        assertTrue(FuelRepository.looksLikeNetworkOutage(listOf(false, false)))
        assertFalse(FuelRepository.looksLikeNetworkOutage(listOf(true, false)))
        assertFalse(
            "zmena formátu sa má hlásiť ako zmena formátu, nie ako výpadok",
            FuelRepository.looksLikeNetworkOutage(listOf(true)),
        )
    }

    @Test
    fun `no towns configured is not an outage`() {
        // Nie je čo sťahovať — hádzať kvôli tomu výnimku by len zakrylo
        // skutočný stav ("nemáš vybrané žiadne mesto").
        assertFalse(FuelRepository.looksLikeNetworkOutage(emptyList()))
    }

    // ── neznáme mesto sa musí dostať až na obrazovku ─────────────────────────

    @Test
    fun `unknown town is reported, not silently dropped`() {
        // Keby sa len preskočilo, mesto zmizne zo zoznamu bez vysvetlenia
        // a človek hľadá chybu u seba. Preto ho Snapshot nesie ďalej.
        val vysledky = listOf(
            Result.success(listOf(javornik("Slavicin", 30.0, 6.6))),
            Result.failure<List<CzStation>>(UnknownTownException("Horní Lideč")),
        )
        val nezname = vysledky.mapNotNull {
            (it.exceptionOrNull() as? UnknownTownException)?.townName
        }
        assertEquals(listOf("Horní Lideč"), nezname)

        // A nesmie sa tváriť ako zmena formátu — pumpy z ostatných miest prišli.
        val stanice = vysledky.flatMap { it.getOrElse { emptyList() } }
        assertFalse(
            FuelRepository.looksLikeFormatChange(vysledky.map { it.isSuccess }, stanice.size),
        )
    }
}
