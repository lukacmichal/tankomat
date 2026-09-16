package sk.lukac.tankomat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sk.lukac.tankomat.R
import sk.lukac.tankomat.data.model.DistanceSource
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.model.StationSavings
import sk.lukac.tankomat.data.model.UiState
import sk.lukac.tankomat.data.model.UserConfig
import sk.lukac.tankomat.ui.components.StationCard
import sk.lukac.tankomat.ui.theme.LossRed
import sk.lukac.tankomat.ui.theme.SavingsGreen
import sk.lukac.tankomat.viewmodel.MainViewModel
import sk.lukac.tankomat.viewmodel.UpdateState
import androidx.compose.ui.graphics.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by vm.uiState.collectAsState()
    val config by vm.configFlow.collectAsState(initial = UserConfig())
    val remote by vm.remoteVersion.collectAsState()
    val update by vm.updateState.collectAsState()

    /** Pumpa, ktorej sa práve prepisuje vzdialenosť; null = dialóg je zavretý. */
    var upravovana by remember { mutableStateOf<StationSavings?>(null) }

    Scaffold(
        // Pätička s verziou je `bottomBar`, aby držala úplne dole a nerolovala
        // s obsahom — rovnako ako vo všetkých ostatných appkách (docs/shared-standard.md 2.6).
        bottomBar = {
            VersionFooter(
                remote = remote,
                newer = update is UpdateState.Available,
                onClick = { vm.checkForUpdateSilently() },
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_main),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${config.fuelType.labelSk} • " +
                                "${formatDouble(config.consumptionLPer100Km)} l/100 km • " +
                                "tank ${formatDouble(config.tankVolumeLiters)} l",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        // Potiahnutie dole = obnov. Rovnaké gesto majú všetky appky (docs/shared-standard.md
        // 2.6), tlačidlo ↻ hore ostáva pre tých, čo ho už majú v ruke.
        PullToRefreshBox(
            isRefreshing = state is UiState.Loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is UiState.Idle -> EmptyMessage("Zatiaľ žiadne dáta — potiahni dole alebo stlač ↻.")
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> ErrorMessage(s.message, onRetry = { vm.refresh() })
                is UiState.Ready -> ReadyContent(s, onEditDistance = { upravovana = it })
            }
        }
    }

    upravovana?.let { savings ->
        DistanceDialog(
            savings = savings,
            hasManual = config.manualKm.containsKey(savings.station.manualKey),
            onDismiss = { upravovana = null },
            onSave = { km ->
                vm.setManualKm(savings.station.manualKey, km)
                upravovana = null
            },
            onReset = {
                vm.setManualKm(savings.station.manualKey, null)
                upravovana = null
            },
        )
    }
}

/**
 * Ručná oprava vzdialenosti k jednej pumpe.
 *
 * Prečo to appka vôbec ponúka: kilometre sú jediné číslo, ktoré si sama
 * nevie overiť (cenu vidno na stránke, vzdialenosť nie), a pritom z nich
 * vychádza celý verdikt. Keď tabuľka minie — stránka premenuje obec, pumpa
 * je nová, alebo tam človek jazdí inou cestou — musí to vedieť prebiť bez
 * čakania na novú verziu appky.
 */
@Composable
private fun DistanceDialog(
    savings: StationSavings,
    hasManual: Boolean,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember(savings.station.identity) {
        mutableStateOf(intFmt.format(savings.oneWayKm))
    }
    // Prijmi aj čiarku — slovenská klávesnica ju dá skôr než bodku.
    val km = text.replace(',', '.').trim().toDoubleOrNull()
    val platne = km != null && km > 0.0 && km < 1000.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vzdialenosť k pumpe") },
        text = {
            Column {
                Text(
                    text = "${savings.station.name} • ${savings.station.town}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (savings.distanceSource) {
                        DistanceSource.MANUAL -> "Teraz počítam s tvojím číslom."
                        DistanceSource.MEASURED ->
                            "Teraz počítam s nameranou cestou k tejto pumpe."
                        DistanceSource.VILLAGE ->
                            "Teraz počítam s cestou do obce — pumpu samu tabuľka nepozná."
                        DistanceSource.CITY_FALLBACK ->
                            "Teraz počítam s paušálom mesta z Nastavení. To býva " +
                                "výrazne mimo — stránka mesta nesie aj pumpy 30 km ďalej."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Jednosmerne (km)") },
                    singleLine = true,
                    isError = !platne,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    text = "Počíta sa cesta tam aj späť, teda dvojnásobok.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { km?.let(onSave) }, enabled = platne) { Text("Uložiť") }
        },
        dismissButton = {
            Row {
                if (hasManual) {
                    TextButton(onClick = onReset) { Text("Vrátiť pôvodnú") }
                }
                TextButton(onClick = onDismiss) { Text("Zrušiť") }
            }
        },
    )
}

@Composable
private fun ReadyContent(
    state: UiState.Ready,
    onEditDistance: (StationSavings) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { NeznameMestaBanner(state.neznameMesta) }
        item { VerdictBanner(state.bestOverall) }
        item { HeaderInfo(state) }
        if (state.results.isEmpty()) {
            item {
                Text(
                    text = "Žiadna pumpa nespĺňa minimálnu úsporu.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                )
            }
        } else {
            items(state.results) { savings ->
                StationCard(savings, onEditDistance = onEditDistance)
            }
        }
    }
}

/**
 * Veľký verdikt hore: OPLATÍ SA / NEOPLATÍ SA. Berie najvýhodnejšiu pumpu
 * spomedzi všetkých (aj keď je pod filtrom), aby odpovedal na základnú
 * otázku „šetrím alebo míňam?" jednou vetou.
 */
@Composable
private fun VerdictBanner(best: StationSavings?) {
    if (best == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(
                text = "Žiadne CZ ceny na porovnanie.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }

    val worth = best.netSavingsEur > 0
    val container = if (worth) SavingsGreen else LossRed
    val onContainer = Color.White
    val net = moneyFmt.format(kotlin.math.abs(best.netSavingsEur))
    val vol = intFmt.format(best.tankVolumeLiters)
    val fuel = best.fuelType.labelSk.lowercase(Locale("sk"))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = if (worth) "OPLATÍ SA ✅" else "NEOPLATÍ SA ❌",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = onContainer,
            )
            Text(
                text = if (worth) {
                    "Na $vol l $fuel ušetríš $net €"
                } else {
                    "Na $vol l $fuel preplatíš $net €"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = onContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = if (worth) {
                    "Najlepšie: ${best.station.name}, ${best.station.town} " +
                        "— ${kmLabel(best)} od domu, palivo na cestu " +
                        "tam-späť už odrátané."
                } else {
                    "Ani najlacnejšia CZ pumpa (${best.station.name}, " +
                        "${kmLabel(best)} od domu) sa neoplatí " +
                        "— po odrátaní paliva na cestu si v mínuse. Natankuj doma."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 8.dp),
            )
            if (best.priceFreshness != PriceFreshness.CURRENT) {
                Text(
                    text = "⚠ Pozor: cena tejto pumpy nemusí byť aktuálna" +
                        (best.priceUpdatedOn?.let { " (naposledy $it)" } ?: "") + ".",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!worth) {
                val hint = best.breakEvenLiters?.let { litre ->
                    "Pri tomto rozdiele cien by sa cesta oplatila až od " +
                        "${intFmt.format(litre)} l — ty počítaš s $vol l."
                } ?: "Palivo je v ČR momentálne drahšie ako doma."
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * Upozornenie na mestá, na ktoré mbenzin.cz nemá stránku.
 *
 * Bez neho by sa také mesto len ticho stratilo zo zoznamu — a to je horšie než
 * predtým, keď appka aspoň niečo ukázala (aj keď to boli pumpy z celého Česka
 * s vymyslenou vzdialenosťou).
 */
@Composable
private fun NeznameMestaBanner(mesta: List<String>) {
    if (mesta.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (mesta.size == 1) {
                "mbenzin.cz nepozná mesto „${mesta.first()}“ — oprav mu názov " +
                    "v Nastaveniach, inak sa z neho nedajú načítať ceny."
            } else {
                "mbenzin.cz nepozná mestá: ${mesta.joinToString(", ")} — oprav " +
                    "im názvy v Nastaveniach, inak sa z nich nedajú načítať ceny."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun HeaderInfo(state: UiState.Ready) {
    // Sken môže byť aj pár dní starý (ukladá sa na disk), preto aj dátum.
    val time = SimpleDateFormat("d.M.yyyy HH:mm", Locale("sk")).format(Date(state.refreshedAt))
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = "Posledný sken: $time (obnovíš ↻ hore)",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "SK referencia: ${state.skReference.source} " +
                "• kurz 1 € = ${formatDouble(1 / state.exchangeRate)} Kč",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorMessage(text: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "⚠",
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text("Skúsiť znova")
        }
    }
}

private val df = DecimalFormat("#0.0#")
private fun formatDouble(d: Double) = df.format(d)

/**
 * „52 km" pri nameranej vzdialenosti, „≈ 38 km" pri odhade. Vlnovka nie je
 * ozdoba: pri odhade rozhoduje verdikt o ceste číslo, ktoré appka nemá
 * odkiaľ potvrdiť, a to musí byť vidieť.
 */
private fun kmLabel(s: StationSavings): String {
    val km = "${intFmt.format(s.oneWayKm)} km"
    return when (s.distanceSource) {
        DistanceSource.MEASURED -> km
        DistanceSource.MANUAL -> "$km (ručne)"
        else -> "≈ $km"
    }
}

private val skSymbols = DecimalFormatSymbols(Locale("sk"))
private val moneyFmt = DecimalFormat("#0.00", skSymbols)
private val intFmt = DecimalFormat("#0.#", skSymbols)
