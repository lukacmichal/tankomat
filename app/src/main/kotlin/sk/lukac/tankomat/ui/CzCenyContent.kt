package sk.lukac.tankomat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import sk.lukac.tankomat.data.model.CzCenaRiadok
import sk.lukac.tankomat.data.model.DistanceSource
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.model.UiState
import sk.lukac.tankomat.ui.theme.LossRed
import sk.lukac.tankomat.ui.theme.WarnOrange
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Záložka „CZ ceny": pumpy z benzin.cz v okruhu zoradené podľa vzdialenosti,
 * pri každej cena a deň nahlásenia. Bez SK ceny a bez výpočtu úspory.
 *
 * Kilometre sa dajú opraviť aj odtiaľto ([onEditDistance]) — ten istý dialóg
 * a ten istý zápis ako v Porovnaní, takže stačí raz. Jediné miesto pre pumpu,
 * ku ktorej appka cestu nepozná: v Porovnaní taká nie je.
 */
@Composable
internal fun CzCenyContent(state: UiState.Ready, onEditDistance: (CzCenaRiadok) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ChybaObnoveniaBanner(state) }
        item {
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = "${state.fuel.labelSk} v okolí",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${state.czCeny.size} pumpov, zoradené podľa cesty autom. " +
                        "benzin.cz pri cene uvádza deň nahlásenia, hodinu nie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                MbenzinOdkaz()
            }
        }
        if (state.czCeny.isEmpty()) {
            item {
                Text(
                    text = "Zatiaľ žiadne ceny z benzin.cz — potiahni dole na obnovenie.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            items(state.czCeny, key = { it.station.kluc }) { CzCenaCard(it, onEditDistance) }
        }
    }
}

@Composable
private fun CzCenaCard(r: CzCenaRiadok, onEditDistance: (CzCenaRiadok) -> Unit) {
    val tlmene = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = r.station.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val miesto = listOf(r.station.town, r.station.address)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                if (miesto.isNotBlank()) {
                    Text(
                        text = miesto,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tlmene,
                    )
                }
                val km = r.oneWayKm?.let { kmFmt.format(it) }
                // Celý riadok s kilometrami je tlačidlo, rovnako ako v Porovnaní.
                val (kmText, kmColor) = when (r.distanceSource) {
                    null -> "cesta nezistená — zadaj ručne" to WarnOrange
                    DistanceSource.MANUAL -> "$km km (ručne)" to MaterialTheme.colorScheme.primary
                    DistanceSource.MEASURED, DistanceSource.ROUTED -> "$km km" to tlmene
                    DistanceSource.VILLAGE -> "≈ $km km (podľa obce)" to tlmene
                    DistanceSource.CITY_FALLBACK -> "≈ $km km (podľa mesta)" to tlmene
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onEditDistance(r) },
                ) {
                    Text(
                        text = kmText,
                        style = MaterialTheme.typography.bodyMedium,
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
                    text = "${priceFmt.format(r.cena.priceCzk)} Kč",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val den = Casy.den(r.cena.updatedOn) ?: "neznámo kedy"
                Text(
                    text = when (r.cena.freshness) {
                        PriceFreshness.CURRENT -> den
                        PriceFreshness.UNCERTAIN -> "⚠ $den"
                        PriceFreshness.STALE -> "⚠ stará, $den"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (r.cena.freshness) {
                        PriceFreshness.CURRENT -> tlmene
                        PriceFreshness.UNCERTAIN -> WarnOrange
                        PriceFreshness.STALE -> LossRed
                    },
                )
            }
        }
    }
}

private val skSymbols = DecimalFormatSymbols(Locale("sk"))
private val priceFmt = DecimalFormat("#0.00", skSymbols)
private val kmFmt = DecimalFormat("#0.#", skSymbols)
