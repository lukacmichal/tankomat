package sk.lukac.tankomat

import android.app.Application
import sk.lukac.tankomat.di.AppContainer

class TankomatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Appka na pozadi nestahuje nic (TANKOMAT-11) — ceny len po ↻.
        // Rozvrh ranneho stahovania zo starsich verzii sa zrusi pri kazdom
        // starte; po prvom je to len lacne „nic na zrusenie" (viz [RannaObnova]).
        RannaObnova.zrus(this)
    }
}
