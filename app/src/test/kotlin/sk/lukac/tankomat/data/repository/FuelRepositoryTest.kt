package sk.lukac.tankomat.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sk.lukac.tankomat.data.model.DistanceSource
import sk.lukac.tankomat.data.model.StationDistances

class FuelRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `sken z 1_21 sa nacita ako stary format`() {
        // 1.21 mala v `stations` odhady benzinmapy a pole neznameMesta.
        val stary = """
            {"stations":[],"skReference":{"source":"test",
            "pricesEur":{"DIESEL":1.55},"updatedAt":0},
            "czkToEur":0.04,"fetchedAt":0,"neznameMesta":[]}
        """.trimIndent()
        val snapshot = json.decodeFromString(FuelRepository.Snapshot.serializer(), stary)
        assertEquals(1, snapshot.format)
        assertTrue(snapshot.format < FuelRepository.FORMAT)
        assertNull(snapshot.mbenzinFuel)
    }

    @Test
    fun `sken z 1_22 s benzinmapou sa nacita bez nej`() {
        val s122 = """
            {"stations":[{"name":"Trako","town":"Brumov","address":"Kloboucká 1397",
            "distanceFromCityCenterKm":null,"prices":{"DIESEL":{"priceCzk":47.5,
            "updatedOn":"25.10.2026","freshness":"CURRENT"}},"townSlug":"Brumov",
            "townDistanceFromHomeKm":24.8,"id":"18019","lat":49.1,"lng":18.0,"brand":"Trako"}],
            "skReference":{"source":"test","pricesEur":{"DIESEL":1.55},"updatedAt":0},
            "czkToEur":0.04,"fetchedAt":0,"benzinmapa":[],"mbenzinFuel":"DIESEL",
            "mbenzinAt":0,"format":2}
        """.trimIndent()
        val snapshot = json.decodeFromString(FuelRepository.Snapshot.serializer(), s122)
        // 1.22–1.24 mali pri pumpách mimo tabuľky vzdušnú čiaru × 1,25 —
        // taký sken sa od 1.25 nenačíta.
        assertEquals(2, snapshot.format)
        assertTrue(snapshot.format < FuelRepository.FORMAT)
        assertEquals("18019", snapshot.stations.single().id)
    }

    @Test
    fun `sken z 1_25 nesie suradnice a trasu`() {
        val s125 = """
            {"stations":[{"name":"Nová","town":"Kdesi","address":"Hlavná 1",
            "distanceFromCityCenterKm":null,"prices":{},"townSlug":"Kdesi",
            "townDistanceFromHomeKm":null,"id":"1","lat":49.2,"lng":17.7,"roadKm":44.4}],
            "skReference":{"source":"test","pricesEur":{"DIESEL":1.55},"updatedAt":0},
            "czkToEur":0.04,"fetchedAt":0,"mbenzinFuel":"DIESEL","format":3}
        """.trimIndent()
        val st = json.decodeFromString(FuelRepository.Snapshot.serializer(), s125).stations.single()
        assertEquals(44.4, st.roadKm!!, 0.001)
        assertNull(st.townDistanceFromHomeKm)
        assertEquals(DistanceSource.ROUTED, StationDistances.resolve(st, emptyMap())!!.source)
    }
}
