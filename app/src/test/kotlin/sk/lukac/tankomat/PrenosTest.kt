package sk.lukac.tankomat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrenosTest {

    private val usek = Prenos.USEK_MS

    @Test
    fun `zaciatok sa zarovna na cely usek Androidu`() {
        assertEquals(10 * usek, Prenos.zarovnaj(10 * usek))
        assertEquals(10 * usek, Prenos.zarovnaj(10 * usek + 1))
        assertEquals(10 * usek, Prenos.zarovnaj(11 * usek - 1))
    }

    @Test
    fun `vynulovanie v ramci historie odpocita zaklad`() {
        val teraz = 1_000 * usek
        val (od, zaklad) = Prenos.zaciatokAZaklad(teraz - 5 * usek, teraz)
        assertEquals(teraz - 5 * usek, od)
        assertTrue(zaklad)
    }

    @Test
    fun `starsie nez historia sa rata od najstarsieho bez zakladu`() {
        val teraz = 10_000 * usek
        val (od, zaklad) = Prenos.zaciatokAZaklad(0L, teraz)
        assertEquals(teraz - Prenos.HISTORIA_MS, od)
        assertFalse(zaklad)
    }

    @Test
    fun `bajty v tisickach ako systemove nastavenia`() {
        assertEquals("999 B", Prenos.bajty(999))
        assertEquals("103 kB", Prenos.bajty(102_756))
        assertEquals("1,5 MB", Prenos.bajty(1_500_000))
    }

    @Test
    fun `bez statistiky to povie a necha nulu`() {
        assertEquals("Android spotrebu dát nedal.", Prenos.popis(null))
        assertTrue(Prenos.popis(Prenos.Stav(0, 0, 0)).contains("Mobilné dáta: 0 B"))
    }
}
