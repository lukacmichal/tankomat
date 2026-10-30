package sk.lukac.tankomat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.FuelPrice
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.PriceFreshness

class SavingsCalculatorTest {

    /**
     * Pumpa v obci, o ktorej `StationDistances` nič nevie — vzdialenosť teda
     * padne na `townDistanceFromHomeKm` a testy vedia počítať s okrúhlym
     * číslom. Pre pumpy Z TABUĽKY je vlastná trieda nižšie.
     */
    private fun station(
        name: String,
        priceCzk: Double,
        distanceKm: Double,
        fuel: FuelType = FuelType.DIESEL,
        cityCenterKm: Double? = null,
        freshness: PriceFreshness = PriceFreshness.CURRENT,
    ) = CzStation(
        name = name,
        town = "Vymyslená Lhota",
        address = "x",
        distanceFromCityCenterKm = cityCenterKm,
        prices = mapOf(fuel to FuelPrice(priceCzk, "17.07.2026", freshness)),
        townSlug = "Vymyslena-Lhota",
        townDistanceFromHomeKm = distanceKm,
    )

    @Test
    fun `positive savings when CZ price clearly cheaper`() {
        val s = station("Cheap", priceCzk = 33.0, distanceKm = 30.0)
        val r = SavingsCalculator.calculate(
            station = s,
            fuel = FuelType.DIESEL,
            skPriceEur = 1.55,
            czkToEurRate = 0.04, // 1 CZK = 0.04 EUR
            tankVolumeLiters = 50.0,
            consumptionLPer100Km = 6.5,
        )
        assertNotNull(r)
        // Hrubá = (1.55 - 33*0.04) × 50 = (1.55 - 1.32) × 50 = 11.5
        assertEquals(11.5, r!!.grossSavingsEur, 0.001)
        // Náklady = 60/100 × 6.5 × 1.55 = 6.045
        assertEquals(6.045, r.extraFuelCostEur, 0.001)
        // Net = 11.5 - 6.045 = 5.455
        assertEquals(5.455, r.netSavingsEur, 0.001)
        // Break-even = náklady / zisk na liter = 6.045 / (1.55 - 1.32) = 26.28 l
        assertEquals(26.283, r.breakEvenLiters!!, 0.001)
        // Čerstvosť a dátum sa prenesú do výsledku.
        assertEquals(PriceFreshness.CURRENT, r.priceFreshness)
        assertEquals("17.07.2026", r.priceUpdatedOn)
    }

    @Test
    fun `st-km zo stranky sa uz nepripocitava`() {
        // Do 16. 8. 2026 sa `st-km` bralo ako "vzdialenosť od centra mesta"
        // a pripočítavalo sa. Nie je to ono (viď CzStation.distanceFromCityCenterKm),
        // takže z 25 + 3.7 musí byť opäť 25.
        val s = station("Javorník", priceCzk = 33.0, distanceKm = 25.0, cityCenterKm = 3.7)
        val r = SavingsCalculator.calculate(
            station = s,
            fuel = FuelType.DIESEL,
            skPriceEur = 1.55,
            czkToEurRate = 0.04,
            tankVolumeLiters = 30.0,
            consumptionLPer100Km = 6.5,
        )!!
        assertEquals(25.0, r.oneWayKm, 0.001)
        assertEquals(50.0, r.roundTripKm, 0.001)
    }

    @Test
    fun `small volume turns a cheap station into a loss`() {
        // Tá istá pumpa ako vyššie, ale tankujem len 10 l namiesto 50.
        val s = station("Cheap", priceCzk = 33.0, distanceKm = 30.0)
        val r = SavingsCalculator.calculate(
            station = s,
            fuel = FuelType.DIESEL,
            skPriceEur = 1.55,
            czkToEurRate = 0.04,
            tankVolumeLiters = 10.0,
            consumptionLPer100Km = 6.5,
        )!!
        // Hrubá = 0.23 × 10 = 2.30; net = 2.30 - 6.045 = -3.745 → strata
        assertEquals(-3.745, r.netSavingsEur, 0.001)
        assertTrue(r.isLoss)
        assertEquals(3.745, r.lossEur, 0.001)
        // Break-even sa nemení s objemom — závisí len od cien a vzdialenosti.
        assertEquals(26.283, r.breakEvenLiters!!, 0.001)
    }

    @Test
    fun `no break even when CZ fuel is more expensive than home`() {
        val s = station("Pricey", priceCzk = 42.0, distanceKm = 20.0)
        val r = SavingsCalculator.calculate(
            station = s,
            fuel = FuelType.DIESEL,
            skPriceEur = 1.55,
            czkToEurRate = 0.04, // 42 × 0.04 = 1.68 €/l > 1.55 doma
            tankVolumeLiters = 10.0,
            consumptionLPer100Km = 6.5,
        )!!
        assertTrue(r.isLoss)
        assertNull(r.breakEvenLiters)
    }

    @Test
    fun `returns null when station does not have requested fuel`() {
        val s = station("OnlyNatural", priceCzk = 40.0, distanceKm = 10.0, fuel = FuelType.NATURAL_95)
        val r = SavingsCalculator.calculate(
            station = s,
            fuel = FuelType.DIESEL,
            skPriceEur = 1.55,
            czkToEurRate = 0.04,
            tankVolumeLiters = 50.0,
            consumptionLPer100Km = 6.5,
        )
        assertNull(r)
    }

    @Test
    fun `rank sorts by net savings descending`() {
        val far = station("Far", priceCzk = 33.0, distanceKm = 100.0)
        val near = station("Near", priceCzk = 35.0, distanceKm = 25.0)
        val sorted = SavingsCalculator.rank(
            stations = listOf(far, near),
            fuel = FuelType.DIESEL,
            skPriceEur = 1.55,
            czkToEurRate = 0.04,
            tankVolumeLiters = 50.0,
            consumptionLPer100Km = 6.5,
        )
        assertEquals(2, sorted.size)
        // Near má vyššiu cenu ale kratšiu cestu → asi vyhrá
        assertTrue(
            "expected Near first, got ${sorted.first().station.name}",
            sorted.first().station.name == "Near",
        )
    }
}
