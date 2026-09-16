package sk.lukac.tankomat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vzdialenosti sú jediný vstup, ktorý appka nevie skontrolovať za behu —
 * z ceny sa pomýlka pozná (je na stránke), z kilometrov nie. Preto testy.
 */
class StationDistancesTest {

    private fun station(
        name: String,
        town: String,
        address: String = "",
        cityCenterKm: Double? = null,
        townFromHome: Double = 99.0,
    ) = CzStation(
        name = name,
        town = town,
        address = address,
        distanceFromCityCenterKm = cityCenterKm,
        prices = mapOf(FuelType.DIESEL to FuelPrice(33.0, "17.07.2026", PriceFreshness.CURRENT)),
        townSlug = "x",
        townDistanceFromHomeKm = townFromHome,
    )

    private fun resolve(s: CzStation, manual: Map<String, Double> = emptyMap()) =
        StationDistances.resolve(s, manual)

    @Test
    fun `presna vzdialenost pumpy ma prednost pred vsetkym ostatnym`() {
        val s = station("Trako", "Brumov", "Kloboucká 1397", cityCenterKm = 11.5)
        assertEquals(17.8, s.estimatedOneWayKm, 0.001)
        assertEquals(DistanceSource.MEASURED, resolve(s).source)
    }

    @Test
    fun `pumpa v meste nie je zrazu o desat kilometrov dalej`() {
        // Presne toto bola nahlásená chyba: pumpa so sídlom v Brumove mala
        // vzdialenosť mesta + 11,5 km. Skutočnosť bola výrazne menej.
        val trako = station("Trako", "Brumov", "Kloboucká 1397", cityCenterKm = 11.5)
        val stareChybne = 20.0 + 11.5
        assertTrue(
            "nová vzdialenosť musí byť výrazne menšia než starý odhad",
            trako.estimatedOneWayKm < stareChybne - 10,
        )
    }

    /**
     * Chyba nahlásená 20. 8. 2026 — appka ukazovala paušál mesta, Google Maps dvojnásobok.
     *
     * mbenzin.cz premenoval pumpe obec (`Zádveřice` → `Řasnice`), takže presný
     * kľúč prestal sedieť a appka spadla až na paušál mesta, ktorého stránku
     * práve čítala. Ulica sa pritom nezmenila — na tú sa treba chytiť.
     */
    @Test
    fun `zmena obce na stranke nesmie zhodit vzdialenost`() {
        val stara = station("Tank ONO", "Zádveřice", "Zádveřice 427", townFromHome = 20.0)
        val nova = station("Tank ONO", "Řasnice", "Zádveřice 427", townFromHome = 20.0)

        assertEquals(nova.estimatedOneWayKm, stara.estimatedOneWayKm, 0.001)
        assertEquals(DistanceSource.MEASURED, resolve(nova).source)
        assertTrue(
            "Tank ONO je ďaleko, nie paušál mesta",
            nova.estimatedOneWayKm > 45.0,
        )
    }

    /** Prečíslovanie adresy (462 → 497) je preklep zdroja, nie iná pumpa. */
    @Test
    fun `zmena cisla popisneho v adrese pumpu nestrati`() {
        val s = station("G.A.S. Petroleum", "Vsetín", "Rokytnice 497", townFromHome = 30.0)
        assertEquals(DistanceSource.MEASURED, resolve(s).source)
        assertTrue("Vsetín je ďalej, než hovorí paušál mesta", s.estimatedOneWayKm > 50.0)
    }

    /** Diakritika a veľkosť písmen sa na stránke menia; kľúč to nesmie cítiť. */
    @Test
    fun `kluc sa najde aj bez diakritiky`() {
        val s = station("TRAKO", "brumov", "Kloboucka 1397")
        assertEquals(17.8, resolve(s).oneWayKm, 0.001)
    }

    @Test
    fun `neznama pumpa v znamej obci dostane vzdialenost tej obce`() {
        val nova = station("Úplne nová pumpa", "Valašské Klobouky", "Nejaká 1")
        assertEquals(31.2, nova.estimatedOneWayKm, 0.001)
        // Je to odhad, nie meranie k tejto pumpe — a tak sa to aj ukáže.
        assertEquals(DistanceSource.VILLAGE, resolve(nova).source)
        assertFalse(nova.hasMeasuredDistance)
    }

    @Test
    fun `obec s cislom popisnym sa najde tiez`() {
        // mbenzin.cz píše obec ako "Vlachovice 381" alebo "Lípa 269".
        assertEquals(29.4, station("Nová", "Vlachovice 381").estimatedOneWayKm, 0.001)
        assertEquals(51.3, station("Nová", "Lípa 269").estimatedOneWayKm, 0.001)
    }

    @Test
    fun `mestska cast spadne na materske mesto`() {
        assertNotNull(StationDistances.oneWayKm("nic", "Slavičín-Hrádek"))
    }

    @Test
    fun `uplne neznama obec spadne na vzdialenost mesta z konfiguracie`() {
        val s = station("Kdesi", "Vymyslená Lhota", townFromHome = 42.0)
        assertEquals(42.0, s.estimatedOneWayKm, 0.001)
        assertEquals(DistanceSource.CITY_FALLBACK, resolve(s).source)
        assertFalse(s.hasMeasuredDistance)
        assertNull(StationDistances.oneWayKm("nic", "Vymyslená Lhota"))
    }

    /**
     * Ručne zadané číslo je posledné slovo. Prebíja aj nameranú tabuľku —
     * človek, ktorý tam jazdí, vie o objazde, ktorý OSRM nepozná.
     */
    @Test
    fun `rucne zadana vzdialenost prebije aj nameranu tabulku`() {
        val s = station("Trako", "Brumov", "Kloboucká 1397")
        val vysledok = resolve(s, mapOf(s.manualKey to 40.0))

        assertEquals(40.0, vysledok.oneWayKm, 0.001)
        assertEquals(DistanceSource.MANUAL, vysledok.source)
        assertFalse(vysledok.isEstimate)
    }

    /**
     * Kľúč ručného zápisu je meno + ulica, nie celá identita — inak by
     * používateľ o opravené kilometre prišiel pri najbližšom premenovaní
     * obce na stránke (viď `Tank ONO` vyššie).
     */
    @Test
    fun `rucny zapis prezije premenovanie obce`() {
        val stara = station("Tank ONO", "Zádveřice", "Zádveřice 427")
        val nova = station("Tank ONO", "Řasnice", "Zádveřice 427")
        assertEquals(stara.manualKey, nova.manualKey)

        val manual = mapOf(stara.manualKey to 55.0)
        assertEquals(55.0, resolve(nova, manual).oneWayKm, 0.001)
    }

    @Test
    fun `vsetky vzdialenosti su v rozumnom rozsahu`() {
        // domovské mesto leží pri hranici; najvzdialenejšia sledovaná pumpa je
        // v Zlíne. Čokoľvek mimo tohto pásma je zlý geokód (rovnomenná obec
        // inde v Česku) — presne to sa pri generovaní tabuľky trikrát stalo.
        for (obec in listOf(
            "Brumov", "Slavičín", "Valašské Klobouky", "Bojkovice", "Zlín",
            "Uherský Brod", "Vlachovice", "Lípa", "Drslavice", "Vsetín",
        )) {
            val km = StationDistances.oneWayKm("nic", obec)
            assertNotNull("chýba vzdialenosť pre $obec", km)
            assertTrue("$obec = $km km je mimo rozsahu", km!! in 15.0..80.0)
        }
    }

    @Test
    fun `tabulka nie je prazdna`() {
        assertTrue(StationDistances.knownStations >= 150)
        assertTrue(StationDistances.knownTowns >= 60)
    }

    /**
     * Zhrnutie oboch nahlásených chýb v jednom teste: pumpa zo stránky
     * vzdialeného mesta nesmie dostať vzdialenosť toho mesta, keď o nej
     * appka vie viac.
     */
    @Test
    fun `pumpy z hlasenia maju realne kilometre`() {
        val tankOno = station("Tank ONO", "Řasnice", "Zádveřice 427", townFromHome = 20.0)
        val gas = station("G.A.S. Petroleum", "Vsetín", "Rokytnice 462", townFromHome = 30.0)

        // Hodnoty z tabuľky (vo verejnej kópii vymyslené).
        assertEquals(47.6, tankOno.estimatedOneWayKm, 3.0)
        assertEquals(61.3, gas.estimatedOneWayKm, 3.0)
    }
}
