package sk.lukac.tankomat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Odklad do rannej hodiny.
 *
 * Cas sa zadava ako miestny cas Bratislavy a prepocitava na milisekundy —
 * inak by test hovoril nieco ine na stroji v inej zone nez na telefone.
 * Kontroluje sa to, na com sa taka funkcia lame: hranica polnoci, presne ta
 * hodina a zmena casu. Dvojca v appke `pocasie` (ObnovaCasTest) je rovnaka.
 */
class RannaObnovaCasTest {

    private val zona: ZoneId = ZoneId.of("Europe/Bratislava")

    private fun ms(rok: Int, mesiac: Int, den: Int, hodina: Int, minuta: Int = 0): Long =
        LocalDateTime.of(rok, mesiac, den, hodina, minuta)
            .atZone(zona).toInstant().toEpochMilli()

    private fun hodin(msec: Long): Double = msec / 3_600_000.0

    @Test
    fun `pred sestou sa caka do dnesnej sestej`() {
        assertEquals(1.5, hodin(RannaObnova.odkladDoHodiny(6, ms(2026, 9, 7, 4, 30), zona)), 0.001)
    }

    @Test
    fun `po sestej sa mieri na zajtrajsiu sestu`() {
        assertEquals(23.5, hodin(RannaObnova.odkladDoHodiny(6, ms(2026, 9, 7, 6, 30), zona)), 0.001)
    }

    @Test
    fun `presne o sestej sa nespusta hned, ale zajtra`() {
        // Nula by znamenala, ze kazdy start appky v tu minutu stiahne znova.
        assertEquals(24.0, hodin(RannaObnova.odkladDoHodiny(6, ms(2026, 9, 7, 6, 0), zona)), 0.001)
    }

    @Test
    fun `tesne pred polnocou sa caka do rana, nie do vcerajska`() {
        assertEquals(6.5, hodin(RannaObnova.odkladDoHodiny(6, ms(2026, 9, 7, 23, 30), zona)), 0.001)
    }

    @Test
    fun `pri jarnej zmene casu ostava sesta sestou`() {
        // V noci na 29. 3. 2026 sa hodiny posuvaju o hodinu dopredu, takze
        // z 22:00 do 6:00 nie je osem hodin, ale sedem.
        assertEquals(7.0, hodin(RannaObnova.odkladDoHodiny(6, ms(2026, 3, 28, 22, 0), zona)), 0.001)
    }

    @Test
    fun `hodina mimo rozsahu sa orezava, nie hadze vynimku`() {
        val odklad = RannaObnova.odkladDoHodiny(99, ms(2026, 9, 7, 12, 0), zona)
        assertTrue("odklad bol $odklad", odklad > 0)
    }
}
