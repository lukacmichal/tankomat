package sk.lukac.tankomat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sk.lukac.tankomat.data.model.DistanceSource
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.model.StationSavings
import sk.lukac.tankomat.ui.theme.LossRed
import sk.lukac.tankomat.ui.theme.SavingsGreen
import sk.lukac.tankomat.ui.theme.WarnOrange
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val priceFormat = DecimalFormat("#0.00", DecimalFormatSymbols(Locale("sk")))
private val moneyFormat = DecimalFormat("+#0.00;-#0.00", DecimalFormatSymbols(Locale("sk")))
private val kmFormat = DecimalFormat("#0.#", DecimalFormatSymbols(Locale("sk")))

@Composable
fun StationCard(
    savings: StationSavings,
    onEditDistance: (StationSavings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isProfit = savings.netSavingsEur > 0
    val accent = if (isProfit) SavingsGreen else LossRed

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = savings.station.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = listOf(savings.station.town, savings.station.address)
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    // „≈" len pri odhade. Nameraná vzdialenosť je dĺžka cesty,
                    // ktorou tam naozaj pôjdeš — vlnovka by z nej robila
                    // menej, než je, a práve dôvera v toto číslo rozhoduje,
                    // či sa človek vyberie alebo nie.
                    //
                    // Celý riadok je tlačidlo: kilometre sú jediný vstup, ktorý
                    // si appka nevie overiť, a keď sú zle, je zle aj verdikt.
                    // Oprava musí byť na jedno ťuknutie tam, kde tú chybu vidíš.
                    val km = kmFormat.format(savings.oneWayKm)
                    val (kmText, kmColor) = when (savings.distanceSource) {
                        DistanceSource.MANUAL ->
                            "$km km od domu (ručne)" to MaterialTheme.colorScheme.primary
                        DistanceSource.MEASURED ->
                            "$km km od domu" to MaterialTheme.colorScheme.onSurface
                        DistanceSource.VILLAGE ->
                            "≈ $km km od domu (odhad podľa obce)" to WarnOrange
                        DistanceSource.CITY_FALLBACK ->
                            "≈ $km km od domu (hrubý odhad — over)" to WarnOrange
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onEditDistance(savings) },
                    ) {
                        Text(
                            text = kmText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = kmColor,
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Upraviť vzdialenosť",
                            tint = kmColor.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 6.dp).size(15.dp),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isProfit) "Úspora" else "Strata",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    Text(
                        text = "${moneyFormat.format(savings.netSavingsEur)} €",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    Text(
                        text = "za ${priceFormat.format(savings.tankVolumeLiters)} l",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricBlock(
                    label = "CZ cena",
                    primary = "${priceFormat.format(savings.czPriceCzk)} Kč",
                    secondary = "≈ ${priceFormat.format(savings.czPriceEur)} €/l",
                )
                MetricBlock(
                    label = "SK cena",
                    primary = "${priceFormat.format(savings.skPriceEur)} €/l",
                    secondary = "domáca",
                )
                MetricBlock(
                    label = "Cesta tam-späť",
                    primary = "≈ ${kmFormat.format(savings.roundTripKm)} km",
                    secondary = "palivo −${priceFormat.format(savings.extraFuelCostEur)} €",
                )
            }

            // Prečo je to strata + kedy by sa to zlomilo do plusu.
            if (!isProfit) {
                val reason = savings.breakEvenLiters?.let { litre ->
                    "Palivo je tam lacnejšie, ale cesta to zožerie — " +
                        "oplatilo by sa až od ${priceFormat.format(litre)} l."
                } ?: "V ČR je toto palivo drahšie ako doma — neoplatí sa pri žiadnom objeme."
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = LossRed,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            // Dátum a čerstvosť ceny — vždy, aby bolo jasné, čomu veríš.
            val (freshnessText, freshnessColor) = when (savings.priceFreshness) {
                PriceFreshness.CURRENT -> Pair(
                    "cena aktuálna" + (savings.priceUpdatedOn?.let { " (k $it)" } ?: ""),
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                PriceFreshness.UNCERTAIN -> Pair(
                    "⚠ cenou si mbenzin.cz nie je istý" +
                        (savings.priceUpdatedOn?.let { " (posledná aktualizácia $it)" } ?: ""),
                    WarnOrange,
                )
                PriceFreshness.STALE -> Pair(
                    "⚠ NEAKTUÁLNA cena — naposledy nahlásená " +
                        (savings.priceUpdatedOn ?: "dávno"),
                    LossRed,
                )
            }
            Text(
                text = freshnessText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (savings.priceFreshness == PriceFreshness.CURRENT) {
                    FontWeight.Normal
                } else {
                    FontWeight.SemiBold
                },
                color = freshnessColor,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MetricBlock(label: String, primary: String, secondary: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            text = primary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = secondary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}
