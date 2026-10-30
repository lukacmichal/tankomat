package sk.lukac.tankomat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sk.lukac.tankomat.data.model.DefaultTowns
import sk.lukac.tankomat.data.model.DistanceSource
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.Gps
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.model.StationDistances
import sk.lukac.tankomat.data.repository.ScrapeFormatException
import java.time.LocalDate

/** Vzorka sú skutočné dáta mapy mbenzin.cz pre naftu z 28. 10. 2026. */
class MbenzinScraperTest {

    private val dnes = LocalDate.of(2026, 10, 28)
    private val scraper = MbenzinScraper(klic = "test")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) {
            "chýba fixture $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private val stanice by lazy {
        scraper.parse(fixture("mbenzin_mapa_nafta.json"), FuelType.DIESEL, 50.0, DefaultTowns, dnes)
    }

    private fun podlaId(id: String) = stanice.single { it.id == id }

    @Test
    fun `pumpy mimo okruhu vypadnu`() {
        assertEquals(10, stanice.size)
        assertTrue(stanice.none { it.id == "17266" }) // F1 Gas v Aši
    }

    @Test
    fun `obec a ulica z adresy dostanu diakritiku`() {
        val euroOil = podlaId("17010")
        assertEquals("EuroOil", euroOil.name)
        assertEquals("Valašské Klobouky", euroOil.town)
        assertEquals("Cyrilometodějská 666", euroOil.address)
    }

    @Test
    fun `meno z viacerych slov sa z ulice odreze`() {
        val tankOno = podlaId("18485")
        assertEquals("Tank ONO", tankOno.name)
        assertEquals("Zádveřice 427", tankOno.address)
    }

    @Test
    fun `pumpy z mapy trafia namerane vzdialenosti`() {
        val trako = StationDistances.resolve(podlaId("18019"), emptyMap())!!
        assertEquals(DistanceSource.MEASURED, trako.source)
        assertEquals(17.8, trako.oneWayKm, 0.01)

        // Tank ONO má v tabuľke inú obec (Řasnice) — chytí ho meno + ulica.
        val tankOno = StationDistances.resolve(podlaId("18485"), emptyMap())!!
        assertEquals(DistanceSource.MEASURED, tankOno.source)
        assertEquals(47.6, tankOno.oneWayKm, 0.01)
    }

    @Test
    fun `cena ma den a cerstvost podla veku`() {
        val dnesnaCena = podlaId("17010").prices.getValue(FuelType.DIESEL)
        assertEquals(47.9, dnesnaCena.priceCzk, 0.001)
        assertEquals("28.10.2026", dnesnaCena.updatedOn)
        assertEquals(PriceFreshness.CURRENT, dnesnaCena.freshness)

        assertEquals("27.10.2026", podlaId("18692").prices.getValue(FuelType.DIESEL).updatedOn)
        assertEquals(PriceFreshness.UNCERTAIN, podlaId("17412").prices.getValue(FuelType.DIESEL).freshness)
        assertEquals(PriceFreshness.STALE, podlaId("17787").prices.getValue(FuelType.DIESEL).freshness)
    }

    @Test
    fun `pumpa mimo tabulky nedostane vzdusnu ciaru ale suradnice na trasu`() {
        // PH-Mobil v Bohuslaviciach u Zlína tabuľka nepozná a jej obec nie je
        // v Nastaveniach. Do 1.24 tu bola vzdušná čiara × 1,25; od 1.25 nič —
        // cestu jej doplní TrasyKPumpam zo súradníc.
        val phMobil = podlaId("17905")
        assertNull(phMobil.townDistanceFromHomeKm)
        assertNull(phMobil.roadKm)
        assertEquals(Gps.Bod(49.171432, 17.639865), phMobil.bod)
    }

    @Test
    fun `stranka namiesto dat znamena zly kluc`() {
        try {
            scraper.parse("<!doctype html><html>vrátnica</html>", FuelType.DIESEL, 50.0, DefaultTowns, dnes)
            fail("mal vyhodiť chybu")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("kľúč"))
        }
    }

    @Test(expected = ScrapeFormatException::class)
    fun `prazdne data su zmena formatu`() {
        scraper.parse("""{"markers":[]}""", FuelType.DIESEL, 50.0, DefaultTowns, dnes)
    }

    @Test
    fun `den ceny z textu mapy`() {
        assertEquals(dnes, MbenzinScraper.denCeny("Dnes", dnes))
        assertEquals(dnes.minusDays(1), MbenzinScraper.denCeny("Včera", dnes))
        assertEquals(LocalDate.of(2026, 10, 6), MbenzinScraper.denCeny("6.10.2026", dnes))
        assertNull(MbenzinScraper.denCeny("—", dnes))
    }

    @Test
    fun `palivo v adrese`() {
        assertEquals("diesel", MbenzinScraper.palivo(FuelType.DIESEL))
        assertEquals("n95", MbenzinScraper.palivo(FuelType.NATURAL_95))
    }
}
