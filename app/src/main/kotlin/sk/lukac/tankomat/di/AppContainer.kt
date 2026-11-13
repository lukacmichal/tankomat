package sk.lukac.tankomat.di

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sk.lukac.tankomat.Kluce
import sk.lukac.tankomat.data.local.RouteStore
import sk.lukac.tankomat.data.local.SnapshotStore
import sk.lukac.tankomat.data.remote.DalioilScraper
import sk.lukac.tankomat.data.remote.MbenzinScraper
import sk.lukac.tankomat.data.remote.OsrmRouter
import sk.lukac.tankomat.data.remote.SmbUpdater
import sk.lukac.tankomat.data.repository.FuelRepository
import sk.lukac.tankomat.data.repository.TrasyKPumpam
import sk.lukac.tankomat.settings.UserSettings
import java.io.File

/**
 * Manuálny DI container — zámerne bez Hilt/Koin.
 * Aplikácia má pár závislostí, framework by tu bol overhead.
 */
class AppContainer(private val appContext: Context) {
    val userSettings: UserSettings = UserSettings(appContext)
    val smbUpdater: SmbUpdater = SmbUpdater()

    // Kľúč k mbenzin.cz nie je v APK (od 1.29, Kluce.kt). Keď ho appka ešte
    // nemá (prvé ↻ po aktualizácii), stiahne si ho z NAS-u práve vtedy — na
    // pozadí sa kvôli tomu nič nespúšťa (TANKOMAT-11).
    private val mbenzin: MbenzinScraper = MbenzinScraper {
        Kluce.hodnota(appContext, KLUC_MBENZIN).ifBlank {
            stiahniKluce()
            Kluce.hodnota(appContext, KLUC_MBENZIN)
        }
    }
    private val dalioil: DalioilScraper = DalioilScraper()
    private val snapshotStore = SnapshotStore(File(appContext.filesDir, "last_snapshot.json"))
    private val trasy = TrasyKPumpam(OsrmRouter(), RouteStore(File(appContext.filesDir, "trasy.json")))
    val repository: FuelRepository = FuelRepository(mbenzin, dalioil, snapshotStore, trasy)

    /**
     * Stiahne kľúče z NAS-u (`kluce.txt` vedľa APK). Nedostupný NAS = false
     * a ticho — mimo domu appka o NAS-e nenadáva (docs/shared-standard.md 2.4/5).
     */
    suspend fun stiahniKluce(): Boolean = withContext(Dispatchers.IO) {
        val cfg = userSettings.config.first().update
        if (cfg.host.isBlank() || cfg.username.isBlank()) return@withContext false
        val text = runCatching { smbUpdater.readText(cfg, Kluce.SUBOR) }.getOrNull()
            ?: return@withContext false
        Kluce.uloz(appContext, text)
        true
    }

    private companion object {
        /** Meno v kluce.txt na NAS-e — rovnaké ako v nas-credentials.local. */
        const val KLUC_MBENZIN = "MBENZIN_KLIC"
    }
}
