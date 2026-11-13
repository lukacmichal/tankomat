package sk.lukac.tankomat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kluce.parsuj musi citat presne to, co zapisuje nahraj-na-nas.ps1 (docs/shared-standard.md 2.2b). */
class KluceTest {

    @Test
    fun beriePrvyRovnaSaPreskociKomentareAPrazdne() {
        val text = "# komentar\nA=1\nB = x=y \nC=\n\n  D=a\\b\nriadok bez rovna sa\n=bez mena\n"
        assertEquals(mapOf("A" to "1", "B" to "x=y", "D" to "a\\b"), Kluce.parsuj(text))
    }

    @Test
    fun znesieBomAWindowsKonceRiadkov() {
        val bom = Char(0xFEFF)
        assertEquals(mapOf("K" to "v", "L" to "w"), Kluce.parsuj("${bom}K=v\r\nL=w\r\n"))
    }

    @Test
    fun tvarZNahravaciehoSkriptu() {
        val text = "# Kluce pre appku kurenie - zapisal nahraj-na-nas.ps1 z nas-credentials.local.\n" +
            "# Rucne nemenit: zdroj je ten subor a appka si odtialto berie kluce sama.\n" +
            "NETATMO_CLIENT_ID=abc\nNETATMO_REFRESH_TOKEN=5f00|9e1b\n"
        assertEquals(
            mapOf("NETATMO_CLIENT_ID" to "abc", "NETATMO_REFRESH_TOKEN" to "5f00|9e1b"),
            Kluce.parsuj(text),
        )
    }
}
