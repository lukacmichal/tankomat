package sk.lukac.tankomat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Ranne stahovanie cien je od 1.28 ZRUSENE (TANKOMAT-11, 11. 11. 2026).
 *
 * Pouzivatel: „ja potrebujem, nech sa cena aktualizuje len vtedy, ked ja tu
 * cenu obnovim". Do 1.27 si appka o 6:00 stiahla ceny sama, a ked v tu hodinu
 * Wi-Fi nebola, cakala na najblizsiu — preto sa ceny menili „samy od seba,
 * staci byt na Wi-Fi". Teraz sa nestahuje nic bez ↻ alebo potiahnutia dole.
 *
 * Tento subor ostava len kvoli telefonom, na ktorych bezala starsia verzia:
 * ich rozvrh lezi vo WorkManageri a aktualizaciou appky nezmizne.
 *
 * - [zrus] ho pri kazdom starte appky zahodi (oba nazvy z minulosti) a zmaze
 *   prefs, v ktorych si rozvrh pamatal podpis.
 * - [Praca] musi zostat pod tym istym menom triedy: WorkManager si v databaze
 *   pamata `sk.lukac.tankomat.RannaObnova$Praca`. Keby rozvrh stihol nabehnut
 *   skor, nez sa zrusi, spusti sa tato prazdna praca — nic nestiahne a nic
 *   dalsie nenaplanuje.
 */
object RannaObnova {
    private val MENA = listOf("tankomat_rano_1x", "tankomat_rano")
    private const val FILE = "tankomat_rozvrh"

    fun zrus(ctx: Context) {
        val app = ctx.applicationContext
        val wm = WorkManager.getInstance(app)
        MENA.forEach { wm.cancelUniqueWork(it) }
        app.deleteSharedPreferences(FILE)
    }

    class Praca(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            zrus(applicationContext)
            return Result.success()
        }
    }
}
