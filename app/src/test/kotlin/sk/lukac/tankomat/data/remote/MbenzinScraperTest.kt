package sk.lukac.tankomat.data.remote

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sk.lukac.tankomat.data.model.CzTownConfig
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.PriceFreshness

class MbenzinScraperTest {

    private val town = CzTownConfig("Slavicin", "Slavičín", 30.0)

    @Test
    fun `parses Slavicin fixture and finds expected stations`() {
        val html = readFixture("mbenzin_slavicin.html")
        val doc = Jsoup.parse(html, "https://www.mbenzin.cz/Ceny-benzinu-a-nafty/Slavicin")
        val scraper = MbenzinScraper()

        val stations = scraper.parse(doc, town)

        assertTrue("expected at least 5 stations, got ${stations.size}", stations.size >= 5)

        val euroOilSlavicin = stations.firstOrNull {
            it.name == "EuroOil" && it.town == "Slavičín"
        }
        assertNotNull("EuroOil Slavičín not parsed", euroOilSlavicin)
        val benzin = euroOilSlavicin!!.prices[FuelType.NATURAL_95]!!
        val nafta = euroOilSlavicin.prices[FuelType.DIESEL]!!
        assertEquals(42.10, benzin.priceCzk, 0.001)
        assertEquals(42.50, nafta.priceCzk, 0.001)
        assertEquals(PriceFreshness.CURRENT, benzin.freshness)
        assertEquals("07.08.2026", benzin.updatedOn)
        assertEquals("Luhačovská 1352", euroOilSlavicin.address)
        assertEquals(6.60, euroOilSlavicin.distanceFromCityCenterKm!!, 0.001)
        assertEquals(town.slug, euroOilSlavicin.townSlug)
        assertEquals(town.distanceFromHomeKm, euroOilSlavicin.townDistanceFromHomeKm, 0.001)
        // Vzdialenosť od domu je NAMERANÁ (StationDistances), nie mesto + st-km.
        // Do 16. 8. 2026 tu vychádzalo mesto + 6,6 km, hoci skutočná cesta
        // k tejto pumpe je kratšia — `st-km` nie je vzdialenosť od centra.
        assertEquals(36.40, euroOilSlavicin.estimatedOneWayKm, 0.001)
    }

    @Test
    fun `stale price is marked STALE with its last reported date`() {
        val html = readFixture("mbenzin_slavicin.html")
        val doc = Jsoup.parse(html)
        val scraper = MbenzinScraper()

        val stations = scraper.parse(doc, town)

        // Orlen v Luhačoviciach: nafta je „old" — dlouho nikdo nenahlásil.
        val orlen = stations.firstOrNull { it.name == "Orlen" && it.town == "Luhačovice" }
        assertNotNull("Orlen Luhačovice not parsed", orlen)
        val nafta = orlen!!.prices[FuelType.DIESEL]!!
        assertEquals(39.90, nafta.priceCzk, 0.001)
        assertEquals(PriceFreshness.STALE, nafta.freshness)
        assertEquals("20.07.2026", nafta.updatedOn)
        // Benzín tej istej pumpy je čerstvý.
        assertEquals(PriceFreshness.CURRENT, orlen.prices[FuelType.NATURAL_95]!!.freshness)
        assertEquals(10.20, orlen.distanceFromCityCenterKm!!, 0.001)
    }

    @Test
    fun `parses Trako station price`() {
        val html = readFixture("mbenzin_slavicin.html")
        val doc = Jsoup.parse(html)
        val scraper = MbenzinScraper()

        val stations = scraper.parse(doc, town)

        val trako = stations.firstOrNull { it.name == "Trako" && it.town == "Slavičín" }
        assertNotNull(trako)
        assertEquals(40.90, trako!!.prices[FuelType.NATURAL_95]!!.priceCzk, 0.001)
        assertEquals(43.50, trako.prices[FuelType.DIESEL]!!.priceCzk, 0.001)
    }

    @Test
    fun `unknown page structure yields no stations`() {
        // Simulácia zmeny formátu: stránka bez Schema.org značiek.
        val doc = Jsoup.parse(
            "<html><body><div class='whatever'>Ceny palív</div></body></html>",
        )
        val stations = MbenzinScraper().parse(doc, town)
        assertTrue("expected empty on unknown structure, got ${stations.size}", stations.isEmpty())
    }

    @Test
    fun `every parsed station has at least one price`() {
        val html = readFixture("mbenzin_slavicin.html")
        val doc = Jsoup.parse(html)
        val scraper = MbenzinScraper()

        val stations = scraper.parse(doc, town)

        stations.forEach { s ->
            assertTrue("Station ${s.name} has no prices", s.prices.isNotEmpty())
        }
    }

    // ── neznáme mesto: mbenzin nevráti chybu, ale celoštátny zoznam ──────────

    @Test
    fun `unknown town is rejected instead of showing nationwide stations`() {
        val html = readFixture("mbenzin_celostatny.html")
        val doc = Jsoup.parse(html, "https://www.mbenzin.cz/Ceny-benzinu-a-nafty/Horni-Lidec")
        val horniLidec = CzTownConfig("Horni-Lidec", "Horní Lideč", 40.0)

        val e = assertThrows(UnknownTownException::class.java) {
            MbenzinScraper().parse(doc, horniLidec)
        }
        assertEquals("Horní Lideč", e.townName)
    }

    @Test
    fun `nationwide page never yields stations for a town`() {
        // Jadro chyby: bez kontroly by tieto tri pumpy (Kolešov, České
        // Budějovice, Písek — stovky km od domu) dostali 40 km, teda
        // vzdialenosť, ktorú si používateľ zadal k Hornej Lidči.
        val doc = Jsoup.parse(readFixture("mbenzin_celostatny.html"))
        val horniLidec = CzTownConfig("Horni-Lidec", "Horní Lideč", 40.0)

        val stations = runCatching { MbenzinScraper().parse(doc, horniLidec) }
            .getOrDefault(emptyList())

        assertTrue("cudzie pumpy prešli do zoznamu: $stations", stations.isEmpty())
    }

    @Test
    fun `real town page passes the check despite diacritics in slug`() {
        // Slug je bez diakritiky ("Slavicin"), nadpis stránky tiež — ale
        // displayName ju má. Kontrola nesmie zhodiť platné mesto.
        val doc = Jsoup.parse(readFixture("mbenzin_slavicin.html"))
        val stations = MbenzinScraper().parse(doc, CzTownConfig("Slavicin", "Slavičín", 30.0))
        assertTrue(stations.isNotEmpty())
    }

    @Test
    fun `town with hyphen in slug matches spaced heading`() {
        // „Brumov-Bylnice" má na stránke nadpis „Brumov Bylnice" (bez pomlčky).
        val doc = Jsoup.parse(
            "<html><head><title>x</title></head><body>" +
                "<h1>Ceny benzínu a nafty Brumov Bylnice</h1></body></html>",
        )
        // Nehádže — 0 pumpov rieši FuelRepository, nie táto kontrola.
        MbenzinScraper().parse(doc, CzTownConfig("Brumov-Bylnice", "Brumov-Bylnice", 19.7))
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) {
            "fixture $name missing"
        }.bufferedReader().use { it.readText() }
}
