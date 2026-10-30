package sk.lukac.tankomat.data.remote

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test
import sk.lukac.tankomat.data.model.FuelType

class DalioilScraperTest {

    @Test
    fun `parses domovské mesto fixture for natural and diesel and lpg`() {
        val html = readFixture("dalioil_domov.html")
        val doc = Jsoup.parse(html)
        val scraper = DalioilScraper()

        val prices = scraper.parse(doc)

        assertEquals(1.734, prices[FuelType.NATURAL_95]!!, 0.0001)
        assertEquals(1.814, prices[FuelType.DIESEL]!!, 0.0001)
        assertEquals(0.849, prices[FuelType.LPG]!!, 0.0001)
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) {
            "fixture $name missing"
        }.bufferedReader().use { it.readText() }
}
