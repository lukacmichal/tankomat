package sk.lukac.tankomat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import android.app.Activity
import android.widget.Toast
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import sk.lukac.tankomat.Prenos
import sk.lukac.tankomat.Vzhlad
import sk.lukac.tankomat.R
import sk.lukac.tankomat.data.model.CzTownConfig
import sk.lukac.tankomat.data.model.FuelType
import sk.lukac.tankomat.data.model.UserConfig
import sk.lukac.tankomat.data.model.VolumePresets
import sk.lukac.tankomat.update.ApkInstaller
import sk.lukac.tankomat.viewmodel.MainViewModel
import sk.lukac.tankomat.viewmodel.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val config by vm.configFlow.collectAsState(initial = UserConfig())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nastavenia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { VzhladSection() }
            item { CarSection(config, vm) }
            item { HomePriceSection(config, vm) }
            item { TownsSection(config, vm) }
            item { ManualKmSection(config, vm) }
            item { StahovanieSection(config, vm) }
            item { PrenosSection() }
            item { UpdateSection(config, vm) }
        }
    }
}

/**
 * Ranne stahovanie cien.
 *
 * Vlastna sekcia hned nad pocitadlom dat: je to jedine miesto, kde appka chodi
 * na siet sama od seba, a clovek, ktory si data strazi, ma obe veci pri sebe.
 */
@Composable
private fun StahovanieSection(config: UserConfig, vm: MainViewModel) {
    SettingsCard("Sťahovanie") {
        SwitchRow(
            label = "Stiahnuť ráno cez Wi-Fi",
            description = "Zapnuté = raz denne o zvolenej hodine sa stiahnu " +
                "najnovšie ceny, ale výhradne cez Wi-Fi. Keď v tú hodinu Wi-Fi " +
                "nie je, sťahovanie počká na najbližšiu — cez mobilné dáta sa " +
                "nespustí nikdy. Posledné stiahnuté ceny medzitým platia ďalej " +
                "a nezmažú sa.",
            checked = config.rannaObnova,
            onChange = { vm.updateConfig { c -> c.copy(rannaObnova = it) } },
        )
        if (config.rannaObnova) {
            HodinaRow(
                hodina = config.rannaHodina,
                onChange = { vm.updateConfig { c -> c.copy(rannaHodina = it) } },
            )
        }
    }
}

/**
 * Hodina po jednej hore-dole.
 *
 * Minuty tu nie su zamerne: presnu minutu WorkManager aj tak nesluby — pracu
 * spusta v okne a ked v tu hodinu nie je Wi-Fi, caka na nu.
 */
@Composable
private fun HodinaRow(hodina: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("O ktorej hodine", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(((hodina - 1) + 24) % 24) }) { Text("−") }
        Text(
            text = if (hodina < 10) "0$hodina:00" else "$hodina:00",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = { onChange((hodina + 1) % 24) }) { Text("+") }
    }
}

/**
 * Prenesene data — Wi-Fi zvlast, mobilne zvlast.
 *
 * Nadpis, text aj tlacidlo pyta [Prenos]: sekcia je vo vsetkych appkach
 * rovnaka a devat kopii tych istych retazcov by sa rozislo uz pri prvej
 * oprave preklepu. Tu ma zmysel preto, ze appka chodi na tri weby s cenami
 * a bez cisla sa neda povedat, ci to na mobilnych datach nieco stoji.
 */
@Composable
private fun PrenosSection() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(Prenos.popis(context)) }
    SettingsCard(Prenos.NADPIS) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Text(Prenos.POZNAMKA, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            Prenos.vynuluj(context)
            text = Prenos.popis(context)
        }) {
            Text(Prenos.VYNULOVAT)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CarSection(config: UserConfig, vm: MainViewModel) {
    SettingsCard("Auto") {
        FuelTypeDropdown(config.fuelType) { newType ->
            vm.updateConfig { it.copy(fuelType = newType) }
        }
        NumericField(
            label = "Spotreba (l / 100 km)",
            value = config.consumptionLPer100Km,
            onChange = { vm.updateConfig { c -> c.copy(consumptionLPer100Km = it) } },
        )
        Text(
            text = "Koľko litrov počítať",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "Všetky úspory aj straty sa rátajú na tento objem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VolumePresets.forEach { litre ->
                FilterChip(
                    selected = config.tankVolumeLiters == litre,
                    onClick = { vm.updateConfig { c -> c.copy(tankVolumeLiters = litre) } },
                    label = { Text("${litre.toPlainString()} l") },
                )
            }
        }
        NumericField(
            label = "…alebo vlastný objem (l)",
            value = config.tankVolumeLiters,
            onChange = { vm.updateConfig { c -> c.copy(tankVolumeLiters = it) } },
        )
        SwitchRow(
            label = "Zobraziť aj stratové pumpy",
            description = "Zapnuté = uvidíš aj o koľko by si prerobil, ak by bola CZ cena vyššia.",
            checked = config.showLosingStations,
            onChange = { vm.updateConfig { c -> c.copy(showLosingStations = it) } },
        )
        SwitchRow(
            label = "Ignorovať neaktuálne ceny",
            description = "Pumpy s dávno nenahlásenou cenou (červené na mbenzin.cz) " +
                "sa vôbec nepočítajú — ani do verdiktu hore.",
            checked = config.ignoreStalePrices,
            onChange = { vm.updateConfig { c -> c.copy(ignoreStalePrices = it) } },
        )
    }
}

@Composable
private fun HomePriceSection(config: UserConfig, vm: MainViewModel) {
    SettingsCard("Domáca cena (SK)") {
        Text(
            text = "Necháva prázdne = scrape z dalioil.sk domovské mesto. " +
                "Inak override v EUR/l pre zvolené palivo.",
            style = MaterialTheme.typography.bodyMedium,
        )
        NullableNumericField(
            label = "Override cena (€/l)",
            value = config.homePriceEurOverride,
            onChange = { vm.updateConfig { c -> c.copy(homePriceEurOverride = it) } },
        )
    }
}

@Composable
private fun TownsSection(config: UserConfig, vm: MainViewModel) {
    SettingsCard("CZ mestá") {
        Text(
            text = "Slug = časť URL z mbenzin.cz/Ceny-benzinu-a-nafty/{slug}. " +
                "Vzdialenosť = jednosmerne v km od domu.",
            style = MaterialTheme.typography.bodyMedium,
        )
        config.czTowns.forEachIndexed { i, town ->
            TownRow(
                town = town,
                onChange = { updated ->
                    vm.updateConfig { c ->
                        val list = c.czTowns.toMutableList()
                        list[i] = updated
                        c.copy(czTowns = list)
                    }
                },
                onRemove = {
                    vm.updateConfig { c ->
                        c.copy(czTowns = c.czTowns.filterIndexed { idx, _ -> idx != i })
                    }
                },
            )
            HorizontalDivider()
        }
        FilledTonalButton(
            onClick = {
                vm.updateConfig { c ->
                    c.copy(czTowns = c.czTowns + CzTownConfig("Nove-Mesto", "Nové mesto", 30.0))
                }
            },
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("  Pridať mesto")
        }
    }
}

/**
 * Prehľad ručne prepísaných vzdialenosti — zadávajú sa ťuknutím na kilometre
 * priamo pri pumpe na hlavnej obrazovke, tu sa len dajú skontrolovať a zmazať.
 *
 * Sekcia existuje kvôli jednej pasci: ručný zápis prebije aj tabuľku, ktorá
 * sa medzitým opravila. Bez zoznamu by po rokoch nikto nevedel, prečo appka
 * pri jednej pumpe tvrdohlavo drží staré číslo.
 */
@Composable
private fun ManualKmSection(config: UserConfig, vm: MainViewModel) {
    if (config.manualKm.isEmpty()) return
    SettingsCard("Ručne zadané vzdialenosti") {
        Text(
            text = "Prebíjajú namerané kilometre. Nastavíš ich ťuknutím na " +
                "vzdialenosť pri pumpe na hlavnej obrazovke.",
            style = MaterialTheme.typography.bodyMedium,
        )
        config.manualKm.toSortedMap().forEach { (kluc, km) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = kluc.replace("|", " • "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${km.toPlainString()} km",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { vm.setManualKm(kluc, null) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Zmazať")
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * Aktualizácie appky z domáceho NAS-u cez SMB.
 * Heslo sa ukladá do DataStore v rámci privátneho úložiska appky.
 */
@Composable
private fun UpdateSection(config: UserConfig, vm: MainViewModel) {
    val context = LocalContext.current
    val updateState by vm.updateState.collectAsState()
    val u = config.update

    // Nadpis, popisky aj poradie polí sú rovnaké vo všetkých appkách
    // (docs/shared-standard.md 2.6). Verzia sa tu neopakuje — má jedno miesto, pätičku
    // na hlavnej obrazovke.
    SettingsCard("Aktualizácie") {
        Text(
            text = "Appka sa po spustení sama pozrie na NAS (len na domácej " +
                "sieti, nie cez mobilné dáta). Sťahuje sa až po tvojom potvrdení.",
            style = MaterialTheme.typography.bodyMedium,
        )

        TextSettingField(
            label = "Adresa NAS-u",
            value = u.host,
            onChange = { vm.updateConfig { c -> c.copy(update = c.update.copy(host = it)) } },
            supportingText = "IP alebo meno NAS-u v domácej sieti, napr. nas.local.",
        )
        TextSettingField(
            label = "Zdieľaný priečinok",
            value = u.shareName,
            onChange = { vm.updateConfig { c -> c.copy(update = c.update.copy(shareName = it)) } },
            supportingText = "Na Synology je /volume1/Android share „Android\".",
        )
        TextSettingField(
            label = "Podpriečinok appky",
            value = u.remotePath,
            onChange = { vm.updateConfig { c -> c.copy(update = c.update.copy(remotePath = it)) } },
            supportingText = "Napr. „Tankomat\" — každá appka svoj vlastný priečinok.",
        )
        TextSettingField(
            label = "Názov súboru",
            value = u.apkFileName,
            onChange = { vm.updateConfig { c -> c.copy(update = c.update.copy(apkFileName = it)) } },
            supportingText = "Vždy rovnaké meno — pri novej verzii ho na NAS-e prepíš. " +
                "Verziu appka číta z APK, nie z názvu.",
        )

        HorizontalDivider()

        TextSettingField(
            label = "SMB meno",
            value = u.username,
            onChange = { vm.updateConfig { c -> c.copy(update = c.update.copy(username = it)) } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            supportingText = "Meno SMB účtu, ktorý má na NAS-e prístup k tomu priečinku.",
        )
        PasswordField(
            value = u.password,
            onChange = { vm.updateConfig { c -> c.copy(update = c.update.copy(password = it)) } },
        )
        UpdateStatusRow(updateState, vm, context)
    }
}

@Composable
private fun UpdateStatusRow(state: UpdateState, vm: MainViewModel, context: android.content.Context) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = { vm.checkForUpdate() },
            enabled = state !is UpdateState.Working,
        ) {
            Text("Skontrolovať teraz")
        }
        if (state is UpdateState.ReadyToInstall) {
            FilledTonalButton(
                onClick = {
                    if (ApkInstaller.canInstall(context)) {
                        ApkInstaller.install(context, state.file)
                    } else {
                        // Bez tejto vety appka len ticho otvorí systémové
                        // nastavenia a kto sa z nich vráti, nevie, čo tam mal
                        // spraviť ani prečo sa nič nenainštalovalo. Rovnaké
                        // znenie majú `stocks`, `wake-nb`, `askener`, `meniny`.
                        Toast.makeText(context, R.string.up_need_perm,
                            Toast.LENGTH_LONG).show()
                        ApkInstaller.openInstallPermissionSettings(context)
                    }
                },
            ) { Text("Nainštalovať") }
        }
    }

    val status = when (state) {
        is UpdateState.Idle -> null
        is UpdateState.Working -> "Pozerám na NAS a sťahujem…"
        is UpdateState.UpToDate -> "Máš najnovšiu verziu. ✅"
        is UpdateState.Available -> "Na NAS-e je verzia ${state.remote} (máš ${state.current})."
        is UpdateState.ReadyToInstall -> "Stiahnutá verzia ${state.versionCode} — môžeš inštalovať."
        is UpdateState.Failed -> "Nepodarilo sa: ${state.message}"
    }
    if (status != null) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is UpdateState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) {
    // Lokálny buffer — bez neho DataStore round-trip na každý úder klávesy
    // spôsobuje, že pole "nereaguje" (kurzor sa nehýbe, znaky miznú).
    var text by remember(value) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text("Heslo") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
        ),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Skryť" else "Zobraziť")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Textové pole s lokálnym bufferom (ako [NumericField]) — `value` prichádza
 * z DataStore cez Flow s oneskorením rádovo desiatok ms na každú zmenu.
 * Bez lokálneho stavu by pole medzi úhozmi „skočilo" späť na starú hodnotu
 * z Flow, kým si appka zapíše a znovu prečíta config — navonok to vyzerá
 * ako rozbité písanie (kurzor sa nehýbe, znaky sa strácajú).
 */
@Composable
private fun TextSettingField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TownRow(
    town: CzTownConfig,
    onChange: (CzTownConfig) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextSettingField(
                label = "Názov",
                value = town.displayName,
                onChange = { onChange(town.copy(displayName = it)) },
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.padding(start = 4.dp))
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Odstrániť")
            }
        }
        TextSettingField(
            label = "URL slug (mbenzin.cz)",
            value = town.slug,
            onChange = { onChange(town.copy(slug = it)) },
        )
        NumericField(
            label = "Vzdialenosť od domu (km)",
            value = town.distanceFromHomeKm,
            onChange = { onChange(town.copy(distanceFromHomeKm = it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuelTypeDropdown(current: FuelType, onPick: (FuelType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current.labelSk,
            onValueChange = {},
            readOnly = true,
            label = { Text("Druh paliva") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FuelType.entries.forEach { fuel ->
                DropdownMenuItem(
                    text = { Text(fuel.labelSk) },
                    onClick = {
                        onPick(fuel)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NumericField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toPlainString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.replace(',', '.').toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NullableNumericField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.toPlainString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isBlank()) onChange(null)
            else it.replace(',', '.').toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Vzhlad: rezim a velkost pisma. Sekcia je vo vsetkych appkach rovnaka
 * (docs/shared-standard.md 2.8), tu je postavena v Compose namiesto `NastaveniaForm`.
 *
 * Po zmene sa aktivita stavia NANOVO. Rezim aj velkost pisma sedia
 * v konfiguracii kontextu, ktory sa vyrobil pri `attachBaseContext` — uz
 * vykreslena obrazovka o zmene nema ako vediet. Appka ma jedinu aktivitu,
 * takze `recreate()` prekresli aj tuto obrazovku.
 */
@Composable
private fun VzhladSection() {
    val ctx = LocalContext.current
    var rezim by remember { mutableStateOf(Vzhlad.rezim(ctx)) }
    var pismo by remember { mutableStateOf(Vzhlad.pismo(ctx)) }

    fun uloz(novyRezim: Int, novePismo: Int) {
        rezim = novyRezim
        pismo = novePismo
        Vzhlad.uloz(ctx, novyRezim, novePismo)
        (ctx as? Activity)?.recreate()
    }

    SettingsCard("Vzhľad") {
        Text(
            text = "Nočný režim maľuje pozadie načierno. Na OLED displeji (má ho " +
                "väčšina novších telefónov) sa čierny pixel vôbec nerozsvieti, takže " +
                "to naozaj šetrí batériu. Veľkosť písma sa pripočítava k tomu, čo máš " +
                "nastavené v systéme.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        listOf(
            Vzhlad.PODLA_SYSTEMU to "Podľa systému",
            Vzhlad.SVETLY to "Svetlý",
            Vzhlad.CIERNY to "Čierny (šetrí batériu)",
        ).forEach { (hodnota, popis) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = rezim == hodnota,
                    onClick = { uloz(hodnota, pismo) },
                )
                Text(popis, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text("Veľkosť písma", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Krok je zhruba jeden bod.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { uloz(rezim, (pismo - 1).coerceAtLeast(Vzhlad.PISMO_MIN)) },
                enabled = pismo > Vzhlad.PISMO_MIN,
            ) { Text("−") }
            Text(
                // Popis kroku pyta Vzhlad — rozsah sa 3. 9. 2026 rozsiril na
                // -6..+6 a natvrdo napisane "mensie o 1" by pri -4 klamalo.
                text = Vzhlad.nazovKroku(pismo),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(
                onClick = { uloz(rezim, (pismo + 1).coerceAtMost(Vzhlad.PISMO_MAX)) },
                enabled = pismo < Vzhlad.PISMO_MAX,
            ) { Text("+") }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun Double.toPlainString(): String =
    if (this == this.toLong().toDouble()) this.toLong().toString() else this.toString()
