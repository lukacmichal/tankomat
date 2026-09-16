package sk.lukac.tankomat

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sk.lukac.tankomat.di.AppContainer

class TankomatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Ranny rozvrh sa nastavuje pri kazdom starte: system ho po
        // aktualizacii appky alebo po vycisteni dat moze zahodit a bez tohto
        // by uz nikdy nenabehol. Samotne planovanie je lacne a ked sa hodina
        // nezmenila, `KEEP` necha bezat ten stary (viz [RannaObnova]).
        //
        // Nastavenie lezi v DataStore, cize sa cita zo suspend funkcie —
        // preto vlastny scope, nie priamy hovor v onCreate.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val cfg = container.userSettings.config.first()
            RannaObnova.naplanuj(this@TankomatApp, cfg.rannaObnova, cfg.rannaHodina)
        }
    }
}
