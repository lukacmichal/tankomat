package sk.lukac.tankomat

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sk.lukac.tankomat.ui.AppNavigation
import sk.lukac.tankomat.ui.theme.TankomatTheme

class MainActivity : ComponentActivity() {

    /** Nastavenie vzhladu, s ktorym bola obrazovka postavena. */
    private var vzhladPodpis = 0

    /**
     * Rezim a velkost pisma sa nasadzuju TU, do kontextu aktivity.
     *
     * Appka bezi na Compose a nema `AppCompatDelegate`, ktory by nocny rezim
     * drzal za nu — [Vzhlad.obal] preto prepisuje `uiMode` aj `fontScale`
     * priamo v konfiguracii. `isSystemInDarkTheme()` v `TankomatTheme` cita
     * prave tuto konfiguraciu, takze o nastaveni appky nemusi nic vediet.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Vzhlad.obal(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        vzhladPodpis = Vzhlad.podpis(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TankomatTheme {
                AppNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Poistka pre pocitadlo dat: scrapery cien su bez Androidu a bajty
        // len nazbieraju ([Prenos.nazbieraj]). Tu je prvy bod, kde je po nich
        // Context — bez tohto by prenos v pocitadle chybal.
        Prenos.odovzdaj(this)
        // Zmena v Nastaveniach: kontext uz je vyrobeny starou konfiguraciou,
        // takze jedina cesta je postavit obrazovku nanovo. Appka ma jedinu
        // aktivitu, takze sa tym prekresli aj obrazovka Nastaveni.
        if (Vzhlad.podpis(this) != vzhladPodpis) recreate()
    }
}
