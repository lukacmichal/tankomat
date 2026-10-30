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
import sk.lukac.tankomat.data.model.CzCenaRiadok
import sk.lukac.tankomat.data.model.PriceFreshness
import sk.lukac.tankomat.data.model.StationDistances
import sk.lukac.tankomat.data.model.UiState
import sk.lukac.tankomat.data.model.UserConfig
import sk.lukac.tankomat.RannaObnova
import sk.lukac.tankomat.data.remote.SmbUpdater
import sk.lukac.tankomat.data.repository.FuelRepository
import sk.lukac.tankomat.data.repository.NoDataException
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
                    fuel = config.fuelType,
                    towns = config.czTowns,
                    searchRadiusKm = config.searchRadiusKm,
                    homePriceOverride = config.homePriceEurOverride,
                )
                _uiState.value = buildReady(snapshot, config)
            } catch (t: Throwable) {
                // Posledné ceny pre to isté palivo ostávajú na obrazovke aj
                // s dôvodom, prečo nie sú čerstvé — prázdna chyba by v aute
                // bez signálu zobrala jediné čísla, ktoré človek má.
                val posledny = repository.lastSnapshot()
                    ?.takeIf { it.mbenzinFuel == config.fuelType }
                val dovod = (t as? NoDataException)?.dovod ?: t.message ?: "neznáma chyba"
                _uiState.value = if (posledny != null) {
                    buildReady(posledny, config, chybaObnovenia = dovod)
                } else {
                    UiState.Error(t.message ?: "Neznáma chyba")
                }
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
            val snapshot = repository.lastSnapshot() ?: return@launch
            // benzin.cz posiela ceny po palivách. Iné palivo v skene nie je,
            // takže prepočet by ukázal prázdny zoznam — treba jeden dopyt.
            if (snapshot.mbenzinFuel != updated.fuelType) {
                refresh()
                return@launch
            }
            // Inak prepočítaj z posledného skenu (bez siete).
            _uiState.value = buildReady(snapshot, updated)
        }
    }

    private fun buildReady(
        snapshot: FuelRepository.Snapshot,
        config: UserConfig,
        chybaObnovenia: String? = null,
    ): UiState {
        val skPrice = snapshot.skReference.pricesEur[config.fuelType]
            ?: return UiState.Error(
                "Chýba referenčná SK cena pre ${config.fuelType.labelSk}. " +
                    "Zadaj ju ručne v Nastaveniach.",
            )
        // Okruh platí po ceste (od 1.25). Sken z mapy je predvybraný vzdušnou
        // čiarou, ktorá je len dolná hranica — tu sa dorezáva podľa skutočnej
        // cesty. Pumpa, ku ktorej cestu nepoznáme, ostáva: nezaráta sa, ale
        // obrazovka povie, že je.
        val vzdialenosti = snapshot.stations.associateWith { StationDistances.resolve(it, config.manualKm) }
        val vOkruhu = snapshot.stations.filter { st ->
            vzdialenosti[st]?.let { it.oneWayKm <= config.searchRadiusKm } ?: true
        }
        val rankedAll = SavingsCalculator.rank(
            stations = vOkruhu,
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

        val czCeny = vOkruhu.mapNotNull { st ->
            val cena = st.prices[config.fuelType] ?: return@mapNotNull null
            val d = vzdialenosti[st]
            CzCenaRiadok(st, d?.oneWayKm, d?.source, cena)
        }.sortedWith(compareBy(nullsLast()) { it.oneWayKm })

        return UiState.Ready(
            results = results,
            bestOverall = usable.firstOrNull(),
            czCeny = czCeny,
            fuel = config.fuelType,
            skReference = snapshot.skReference,
            exchangeRate = snapshot.czkToEur,
            refreshedAt = snapshot.fetchedAt,
            chybaObnovenia = chybaObnovenia,
            bezVzdialenosti = czCeny.count { it.oneWayKm == null },
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
