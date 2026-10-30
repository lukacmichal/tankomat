package sk.lukac.tankomat.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sk.lukac.tankomat.data.model.Gps

class OsrmRouterTest {

    private val odkial = Gps.Bod(49.0, 18.0)

    @Test
    fun `adresa ma dlzku pred sirkou a pyta len prvy riadok tabulky`() {
        val url = OsrmRouter.url(odkial, listOf(Gps.Bod(49.171432, 17.639865)))
        assertEquals(
            "https://router.project-osrm.org/table/v1/driving/" +
                "18.000000,49.000000;17.639865,49.171432" +
                "?sources=0&annotations=distance&skip_waypoints=true",
            url,
        )
    }

    /** Tvar odpovede z ostrého servera 30. 10. 2026 (z bodu 49/18, nie z domu). */
    @Test
    fun `metre na kilometre a null ked cesta neexistuje`() {
        val km = OsrmRouter.parse("""{"code":"Ok","distances":[[0,60309,43592.4,null]]}""", 3)
        assertEquals(listOf(60.3, 43.6, null), km)
    }

    @Test
    fun `chyba servera nie je nula kilometrov`() {
        try {
            OsrmRouter.parse("""{"code":"TooBig","message":"Too many table coordinates"}""", 2)
            fail("mal vyhodiť chybu")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("TooBig"))
        }
    }

    @Test
    fun `vela pump ide po davkach a vysledok drzi poradie`() = runBlocking {
        val adresy = mutableListOf<String>()
        val router = OsrmRouter { url ->
            adresy += url
            val pocet = url.substringAfter("driving/").substringBefore('?').split(';').size - 1
            """{"code":"Ok","distances":[[0${",1000".repeat(pocet)}]]}"""
        }
        val ciele = List(OsrmRouter.DAVKA + 3) { Gps.Bod(49.0 + it / 1000.0, 17.5) }

        val km = router.kmPoCeste(odkial, ciele)

        assertEquals(2, adresy.size)
        assertEquals(ciele.size, km.size)
        assertTrue(km.all { it == 1.0 })
    }

    @Test
    fun `ziadna pumpa bez trasy neprejde ako nula`() {
        val km = OsrmRouter.parse("""{"code":"Ok","distances":[[0,null]]}""", 1)
        assertNull(km.single())
    }
}
