package sk.lukac.tankomat.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import sk.lukac.tankomat.TankomatApp
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.model.UiState
import sk.lukac.tankomat.data.model.UserConfig
import sk.lukac.tankomat.Prenos
import sk.lukac.tankomat.RannaObnova
import sk.lukac.tankomat.data.remote.SmbUpdater
import sk.lukac.tankomat.data.repository.FuelRepository
import sk.lukac.tankomat.domain.SavingsCalculator
import sk.lukac.tankomat.settings.UserSettings
import sk.lukac.tankomat.update.ApkInstaller
import java.io.File

/**
 * Stav kontroly / sťahovania novej verzie z NAS-u.
 *
 * Súbor na NAS-e má pevné meno (prepisuje sa pri každom vydaní), takže
 * „skontrolovať" a „stiahnuť" je jeden krok — skutočnú verziu vieme až
 * po stiahnutí, z manifestu APK.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Working : UpdateState
    data object UpToDate : UpdateState

    /**
     * Tichá kontrola pri štarte našla na NAS-e novšiu verziu, ale APK ešte
     * nestiahla — čaká sa na potvrdenie používateľom.
     */
    data class Available(val remote: Long, val current: Long) : UpdateState
    data class ReadyToInstall(val file: File, val versionCode: Long) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class MainViewModel(
    private val repository: FuelRepository,
    private val userSettings: UserSettings,
    private val updater: SmbUpdater,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /**
     * Verzia ležiaca na NAS-e vo tvare "1.9 (10)", alebo null keď sa na NAS
     * nedalo pozrieť. Plní ju tichá kontrola pri štarte a číta pätička.
     *
     * Drží sa oddelene od [updateState]: pätička má ukazovať, čo na NAS-e
     * naozaj leží, aj keď to nie je novšie — teda aj v stave `Idle`.
     */
    private val _remoteVersion = MutableStateFlow<String?>(null)
    val remoteVersion: StateFlow<String?> = _remoteVersion.asStateFlow()

    val configFlow = userSettings.config

    init {
        // Jednorazovo presuň heslo k NAS-u, ktoré staršia verzia nechala
        // v čitateľnom configu, do šifrovaného úložiska. Po prvom behu
        // nerobí nič.
        viewModelScope.launch { userSettings.migrateLegacyPassword() }

        // Pri otvorení appky ukáž posledný uložený sken (ak existuje) —
        // nič sa nesťahuje, nové dáta až po ručnom obnovení.
        viewModelScope.launch {
            val snapshot = repository.lastSnapshot() ?: return@launch
            if (_uiState.value is UiState.Idle) {
                _uiState.value = buildReady(snapshot, userSettings.config.first())
            }
        }

        checkForUpdateSilently()
    }

    /**
     * Tichá kontrola pri štarte — číta iba `output-metadata.json` (pár stoviek
     * bajtov), nikdy celé APK. Keď je NAS nedostupný (appka je mimo domácej
     * siete), mlčky sa nestane nič: stav ostane `Idle` a používateľa to
     * neobťažuje chybovou hláškou, o ktorú nežiadal.
     *
     * Verejná preto, že tým istým spustí kontrolu aj ťuknutie na pätičku.
     */
    fun checkForUpdateSilently() {
        viewModelScope.launch {
            val cfg = userSettings.config.first().update
            if (cfg.host.isBlank() || cfg.username.isBlank()) return@launch
            val remote = runCatching {
                val e = JSONObject(updater.readText(cfg, "output-metadata.json"))
                    .getJSONArray("elements")
                    .getJSONObject(0)
                e.getLong("versionCode") to e.optString("versionName").ifBlank { "?" }
            }.getOrNull() ?: return@launch

            val (code, name) = remote
            _remoteVersion.value = "$name ($code)"

            val current = ApkInstaller.currentVersionCode(appContext)
            if (code > current && _updateState.value is UpdateState.Idle) {
                _updateState.value = UpdateState.Available(code, current)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            val config = userSettings.config.first()
            try {
                val snapshot = repository.refresh(
                    towns = config.czTowns,
                    homePriceOverride = config.homePriceEurOverride,
                    fuelTypeForFallback = config.fuelType,
                )
                _uiState.value = buildReady(snapshot, config)
            } catch (t: Throwable) {
                _uiState.value = UiState.Error(t.message ?: "Neznáma chyba")
            }
        }
    }

    /**
     * Stiahne `apkFileName` z NAS-u a porovná jeho skutočný versionCode
     * s aktuálne nainštalovanou appkou. Kontrola aj sťahovanie sú jeden krok,
     * lebo verziu vieme zistiť až po stiahnutí (súbor na NAS-e nemá číslo
     * v názve — vždy sa prepisuje). Spúšťa sa výhradne ručne z Nastavení.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            val cfg = userSettings.config.first().update
            if (cfg.host.isBlank() || cfg.username.isBlank()) {
                _updateState.value =
                    UpdateState.Failed("Doplň adresu NAS-u a prihlasovacie údaje nižšie.")
                return@launch
            }
            _updateState.value = UpdateState.Working
            _updateState.value = try {
                val target = ApkInstaller.downloadTarget(appContext, cfg.apkFileName)
                val downloaded = updater.download(cfg, target)
                // Najvacsi jednorazovy prenos, aky appka spravi.
                Prenos.zapis(appContext, downloaded.length())
                val remoteVersion = ApkInstaller.readVersionCode(appContext, downloaded)
                val current = ApkInstaller.currentVersionCode(appContext)
                when {
                    remoteVersion == null -> {
                        downloaded.delete()
                        UpdateState.Failed("Stiahnutý súbor sa nedá prečítať ako APK.")
                    }
                    remoteVersion > current -> UpdateState.ReadyToInstall(downloaded, remoteVersion)
                    else -> {
                        downloaded.delete()
                        UpdateState.UpToDate
                    }
                }
            } catch (t: Throwable) {
                UpdateState.Failed(t.message ?: "Spojenie s NAS-om zlyhalo.")
            }
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }

    /**
     * Prepíše (alebo zmaže, keď je `km` null) ručne zadanú vzdialenosť k jednej
     * pumpe. Prepočet ide z posledného skenu, takže sa nič nesťahuje — zmena
     * je na obrazovke okamžite.
     */
    fun setManualKm(key: String, km: Double?) {
        updateConfig { cfg ->
            val nove = cfg.manualKm.toMutableMap()
            if (km == null) nove.remove(key) else nove[key] = km
            cfg.copy(manualKm = nove)
        }
    }

    fun updateConfig(transform: (UserConfig) -> UserConfig) {
        viewModelScope.launch {
            userSettings.update(transform)
            val updated = userSettings.config.first()
            // Zmena rannej hodiny má platiť hneď, nie až po reštarte appky.
            RannaObnova.naplanuj(appContext, updated.rannaObnova, updated.rannaHodina)
            // Po zmene konfigu prepočítaj z posledného skenu (bez siete).
            repository.lastSnapshot()?.let { snapshot ->
                _uiState.value = buildReady(snapshot, updated)
            }
        }
    }

    private fun buildReady(
        snapshot: FuelRepository.Snapshot,
        config: UserConfig,
    ): UiState {
        val skPrice = snapshot.skReference.pricesEur[config.fuelType]
            ?: return UiState.Error(
                "Chýba referenčná SK cena pre ${config.fuelType.labelSk}. " +
                    "Zadaj ju ručne v Nastaveniach.",
            )
        val rankedAll = SavingsCalculator.rank(
            stations = snapshot.stations,
            fuel = config.fuelType,
            skPriceEur = skPrice,
            czkToEurRate = snapshot.czkToEur,
            tankVolumeLiters = config.tankVolumeLiters,
            consumptionLPer100Km = config.consumptionLPer100Km,
            manualKm = config.manualKm,
        )
        // Dávno nenahlásené ceny sú defaultne vylúčené úplne — aj z verdiktu.
        // Verdikt postavený na cene spred mesiacov by bol klamstvo.
        val usable = if (config.ignoreStalePrices) {
            rankedAll.filter { it.priceFreshness != PriceFreshness.STALE }
        } else {
            rankedAll
        }
        val results = if (config.showLosingStations) {
            usable
        } else {
            usable.filter { !it.isLoss }
        }

        return UiState.Ready(
            results = results,
            // rank() radí zostupne, takže prvá je najvýhodnejšia zo všetkých.
            bestOverall = usable.firstOrNull(),
            skReference = snapshot.skReference,
            exchangeRate = snapshot.czkToEur,
            refreshedAt = snapshot.fetchedAt,
            neznameMesta = snapshot.neznameMesta,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) as TankomatApp
                MainViewModel(
                    repository = app.container.repository,
                    userSettings = app.container.userSettings,
                    updater = app.container.smbUpdater,
                    appContext = app.applicationContext,
                )
            }
        }
    }
}
