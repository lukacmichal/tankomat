package sk.lukac.tankomat.domain

import sk.lukac.tankomat.data.model.CzStation
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.StationDistances
import sk.lukac.tankomat.data.model.StationSavings

/**
 * Výpočet úspory pri natankovaní v ČR.
 *
 * Úvaha:
 *  • Hrubá úspora    = (cena_doma − cena_v_ČR_v_EUR) × objem_tankovania
 *  • Spotreba navyše = 2 × vzdialenosť_one_way / 100 × spotreba_l × cena_doma
 *    (palivo na cestu tam aj späť počítam za cenu doma — auto musí dokázať zájsť aj keby v CR pumpa nebola)
 *  • Čistá úspora    = hrubá − spotreba navyše
 *
 * Vzorec úmyselne nezahŕňa amortizáciu, čas, mýto — sú to konštanty,
 * ktoré nevedia meniť poradie pumpov medzi sebou (len posúvajú prah, kedy
 * sa cesta vôbec oplatí). Užívateľ to vie kompenzovať `minSavingsEurToShow`.
 */
object SavingsCalculator {

    fun calculate(
        station: CzStation,
        fuel: FuelType,
        skPriceEur: Double,
        czkToEurRate: Double,
        tankVolumeLiters: Double,
        consumptionLPer100Km: Double,
        manualKm: Map<String, Double> = emptyMap(),
    ): StationSavings? {
        val fuelPrice = station.prices[fuel] ?: return null
        val czPriceCzk = fuelPrice.priceCzk
        val czPriceEur = czPriceCzk * czkToEurRate

        // Ručne zadané > nameraná cesta k pumpe > cesta do jej obce >
        // paušál mesta. Odkiaľ číslo je, ide ďalej do UI — z odhadu sa nesmie
        // stať tvrdenie (viď StationDistances).
        val distance = StationDistances.resolve(station, manualKm)
        val oneWay = distance.oneWayKm
        val roundTrip = 2.0 * oneWay
        val extraLiters = (roundTrip / 100.0) * consumptionLPer100Km
        val extraCostEur = extraLiters * skPriceEur

        val gross = (skPriceEur - czPriceEur) * tankVolumeLiters
        val net = gross - extraCostEur

        // Od koľkých litrov pokryje rozdiel v cene náklady na cestu.
        // Ak je palivo v ČR drahšie (alebo rovnaké), neoplatí sa nikdy → null.
        val perLiterGain = skPriceEur - czPriceEur
        val breakEven = if (perLiterGain > 0.0) extraCostEur / perLiterGain else null

        return StationSavings(
            station = station,
            fuelType = fuel,
            skPriceEur = skPriceEur,
            czPriceCzk = czPriceCzk,
            czPriceEur = czPriceEur,
            tankVolumeLiters = tankVolumeLiters,
            oneWayKm = oneWay,
            distanceSource = distance.source,
            roundTripKm = roundTrip,
            extraFuelCostEur = extraCostEur,
            grossSavingsEur = gross,
            netSavingsEur = net,
            priceUpdatedOn = fuelPrice.updatedOn,
            priceFreshness = fuelPrice.freshness,
            breakEvenLiters = breakEven,
        )
    }

    fun rank(
        stations: List<CzStation>,
        fuel: FuelType,
        skPriceEur: Double,
        czkToEurRate: Double,
        tankVolumeLiters: Double,
        consumptionLPer100Km: Double,
        manualKm: Map<String, Double> = emptyMap(),
    ): List<StationSavings> = stations
        .mapNotNull {
            calculate(
                it, fuel, skPriceEur, czkToEurRate, tankVolumeLiters,
                consumptionLPer100Km, manualKm,
            )
        }
        .sortedByDescending { it.netSavingsEur }
}
