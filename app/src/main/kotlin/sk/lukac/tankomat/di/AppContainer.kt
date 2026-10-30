package sk.lukac.tankomat.di

import android.content.Context
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
class AppContainer(appContext: Context) {
    val userSettings: UserSettings = UserSettings(appContext)
    private val mbenzin: MbenzinScraper = MbenzinScraper()
    private val dalioil: DalioilScraper = DalioilScraper()
    private val snapshotStore = SnapshotStore(File(appContext.filesDir, "last_snapshot.json"))
    private val trasy = TrasyKPumpam(OsrmRouter(), RouteStore(File(appContext.filesDir, "trasy.json")))
    val repository: FuelRepository = FuelRepository(mbenzin, dalioil, snapshotStore, trasy)
    val smbUpdater: SmbUpdater = SmbUpdater()
}
