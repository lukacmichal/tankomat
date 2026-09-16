package sk.lukac.tankomat.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.FuelPrice
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.model.SkReferencePrice
import sk.lukac.tankomat.data.repository.FuelRepository
import java.io.File

class SnapshotStoreTest {

    private fun snapshot() = FuelRepository.Snapshot(
        stations = listOf(
            CzStation(
                name = "EuroOil",
                town = "Slavičín",
                address = "Luhačovská 1352",
                distanceFromCityCenterKm = 1.16,
                prices = mapOf(
                    FuelType.DIESEL to FuelPrice(38.50, "17.07.2026", PriceFreshness.CURRENT),
                    FuelType.NATURAL_95 to FuelPrice(39.90, "17.07.2026", PriceFreshness.CURRENT),
                ),
                townSlug = "Slavicin",
                townDistanceFromHomeKm = 33.0,
            ),
        ),
        skReference = SkReferencePrice(
            source = "dalioil.sk/domovske-mesto",
            pricesEur = mapOf(FuelType.DIESEL to 1.669),
            updatedAt = 1_753_000_000_000,
        ),
        czkToEur = 0.0405,
        fetchedAt = 1_753_000_000_000,
    )

    @Test
    fun `save and load round trip`() = runBlocking {
        val file = File.createTempFile("snapshot", ".json").apply { deleteOnExit() }
        val store = SnapshotStore(file)

        store.save(snapshot())
        val loaded = store.load()

        assertEquals(snapshot(), loaded)
    }

    @Test
    fun `missing file loads as null`() = runBlocking {
        val file = File(System.getProperty("java.io.tmpdir"), "neexistuje-${System.nanoTime()}.json")
        assertNull(SnapshotStore(file).load())
    }

    @Test
    fun `corrupted file loads as null instead of crashing`() = runBlocking {
        val file = File.createTempFile("snapshot", ".json").apply {
            deleteOnExit()
            writeText("{ toto nie je validny json ][")
        }
        assertNull(SnapshotStore(file).load())
    }
}
