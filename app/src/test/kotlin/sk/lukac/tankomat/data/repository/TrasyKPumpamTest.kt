package sk.lukac.tankomat.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sk.lukac.tankomat.data.local.RouteStore
import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.DistanceSource
import sk.lukac.tankomat.data.model.Gps
import sk.lukac.tankomat.data.model.StationDistances
import sk.lukac.tankomat.data.remote.OsrmRouter
import java.io.File
import java.nio.file.Files

class TrasyKPumpamTest {

    private val domov = Gps.Bod(49.0, 18.0)
    private val subor = File(Files.createTempDirectory("trasy").toFile(), "trasy.json")

    private fun pumpa(meno: String, lat: Double?, lng: Double?, obec: String = "Vymyslená Lhota") =
        CzStation(
            name = meno,
            town = obec,
            address = "Hlavná 1",
            distanceFromCityCenterKm = null,
            prices = emptyMap(),
            townSlug = obec,
            id = meno,
            lat = lat,
            lng = lng,
        )

    /** OSRM, ktorý každej pumpe povie 12,3 km a zapisuje si, na čo sa ho pýtali. */
    private class Pocitadlo {
        val dopyty = mutableListOf<String>()
        val router = OsrmRouter { url ->
            dopyty += url
            val pocet = url.substringAfter("driving/").substringBefore('?').split(';').size - 1
            """{"code":"Ok","distances":[[0${",12300".repeat(pocet)}]]}"""
        }
    }

    @Test
    fun `pumpa mimo tabulky dostane trasu a druhy raz sa uz nepyta`() = runBlocking {
        val osrm = Pocitadlo()
        val trasy = TrasyKPumpam(osrm.router, RouteStore(subor), domov)
        val nova = pumpa("Nová", 49.2, 17.7)

        val prvy = trasy.dopln(listOf(nova)).single()
        assertEquals(12.3, prvy.roadKm!!, 0.001)
        assertEquals(DistanceSource.ROUTED, StationDistances.resolve(prvy, emptyMap())!!.source)

        // Nová inštancia, ten istý súbor — ako po reštarte appky.
        val druhy = TrasyKPumpam(osrm.router, RouteStore(subor), domov).dopln(listOf(nova)).single()
        assertEquals(12.3, druhy.roadKm!!, 0.001)
        assertEquals(1, osrm.dopyty.size)
    }

    @Test
    fun `pumpa z tabulky sa na trasu nepyta`() = runBlocking {
        val osrm = Pocitadlo()
        val trako = pumpa("Trako", 49.1, 18.0, obec = "Brumov").copy(address = "Kloboucká 1397")
        assertTrue(StationDistances.zTabulky(trako) != null)

        val vysledok = TrasyKPumpam(osrm.router, RouteStore(subor), domov).dopln(listOf(trako)).single()

        assertNull(vysledok.roadKm)
        assertTrue(osrm.dopyty.isEmpty())
    }

    @Test
    fun `vypadok osrm sken nezhodi a vzdusnu ciaru nepodstrci`() = runBlocking {
        val padajuci = OsrmRouter { error("HTTP 503") }
        val nova = pumpa("Nová", 49.2, 17.7)

        val vysledok = TrasyKPumpam(padajuci, RouteStore(subor), domov).dopln(listOf(nova)).single()

        assertNull(vysledok.roadKm)
        assertNull(StationDistances.resolve(vysledok, emptyMap()))
        assertTrue(!subor.exists())
    }

    @Test
    fun `pumpa bez suradnic sa na trasu nepyta`() = runBlocking {
        val osrm = Pocitadlo()
        val vysledok = TrasyKPumpam(osrm.router, RouteStore(subor), domov)
            .dopln(listOf(pumpa("Stará", null, null))).single()
        assertNull(vysledok.roadKm)
        assertTrue(osrm.dopyty.isEmpty())
    }
}
