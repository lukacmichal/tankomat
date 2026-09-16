package sk.lukac.tankomat

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Ranne stiahnutie cien — raz denne o pevnej hodine a VYHRADNE po Wi-Fi.
 *
 * Dovtedy appka nestahovala na pozadi nic: ceny sa obnovili az ked ich clovek
 * vypytal tlacidlom. To je setrne k datam, ale znamena, ze rano pri odchode
 * ukazuje vcerajsie cisla — a prave rano sa clovek rozhoduje, ci pojde tankovat
 * do Ciech.
 *
 * Tri veci, na ktorych to stoji:
 *
 * 1. **`NetworkType.UNMETERED`.** Nie je to setrenie, je to cely zmysel tohto
 *    rozvrhu. Ked v tu hodinu Wi-Fi nie je, WorkManager pracu NEZAHODI ani ju
 *    nepusti cez simku — pocka na najblizsiu Wi-Fi. Cez mobilne data sa teda
 *    na pozadi nestiahne nikdy nic.
 * 2. **Posledny sken lezi na disku** (`SnapshotStore`) a plati dalej. Cakanie
 *    na Wi-Fi teda nikoho neobera o cisla; appka ukazuje posledne zname ceny
 *    aj v aute bez signalu.
 * 3. **`KEEP`, kym sa hodina nezmenila.** Appka planuje pri kazdom starte a
 *    `UPDATE` s novym odkladom by rozvrh posuval donekonecna — kto appku
 *    otvara kazdy vecer, tomu by rano nestiahla nikdy.
 *
 * Dvojca v appke `pocasie` (`Obnova.kt`) robi to iste a rovnako; sedia aj
 * nazvy, aby sa dali citat vedla seba.
 */
object RannaObnova {
    private const val TAG = "RannaObnova"
    private const val MENO = "tankomat_rano"

    /**
     * Podpis uz naplanovaneho rozvrhu vo vlastnom subore prefs.
     *
     * Zamerne nie v `UserConfig`: je to vnutorna vec rozvrhu, nie nastavenie,
     * a v DataStore blobe by sa miesala s tym, co si clovek naozaj nastavil.
     */
    private const val FILE = "tankomat_rozvrh"
    private const val K_PODPIS = "podpis"

    fun naplanuj(ctx: Context, zapnuta: Boolean, hodina: Int) {
        val app = ctx.applicationContext
        val wm = WorkManager.getInstance(app)
        val sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!zapnuta) {
            wm.cancelUniqueWork(MENO)
            sp.edit().putString(K_PODPIS, "").apply()
            return
        }
        val hod = hodina.coerceIn(0, 23)
        val podpis = "rano=$hod"
        val politika = if (sp.getString(K_PODPIS, "") == podpis) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            ExistingPeriodicWorkPolicy.UPDATE
        }
        val poziadavka = PeriodicWorkRequestBuilder<Praca>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setInitialDelay(
                odkladDoHodiny(hod, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(MENO, politika, poziadavka)
        sp.edit().putString(K_PODPIS, podpis).apply()
    }

    /**
     * Kolko milisekund zostava do najblizsej [hodina]:00 miestneho casu.
     *
     * Pocita sa cez `LocalDate.atTime`, nie pripocitanim 24 hodin: pri zmene
     * casu ma "6:00" ostat sestou hodinou rano, nie sa posunut na piatu.
     * Ked je prave teraz presne ta hodina, mieri sa na zajtrajsok — inak by
     * odklad vysiel nula a praca by sa spustila hned pri kazdom starte appky.
     */
    internal fun odkladDoHodiny(
        hodina: Int,
        teraz: Long,
        zona: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val ted = Instant.ofEpochMilli(teraz).atZone(zona)
        var ciel = ted.toLocalDate().atTime(hodina.coerceIn(0, 23), 0).atZone(zona)
        if (!ciel.isAfter(ted)) ciel = ciel.plusDays(1)
        return Duration.between(ted, ciel).toMillis()
    }

    /**
     * Stiahne ceny a ulozi ich ako novy sken.
     *
     * Neuspech je `retry`, nie `failure`: WorkManager to skusi s odstupom a
     * nezrusi rozvrh. Posledny dobry sken sa pri neuspechu neprepisuje —
     * o to sa stara `FuelRepository`, ktory prazdny vysledok vobec neulozi.
     */
    class Praca(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as? TankomatApp ?: return Result.success()
            val config = app.container.userSettings.config.first()
            // Vypnute medzitym v Nastaveniach — rozvrh sa zrusi pri najblizsom
            // starte appky, dovtedy nech beh aspon nic nestahuje.
            if (!config.rannaObnova) return Result.success()
            return try {
                app.container.repository.refresh(
                    towns = config.czTowns,
                    homePriceOverride = config.homePriceEurOverride,
                    fuelTypeForFallback = config.fuelType,
                )
                Result.success()
            } catch (t: Throwable) {
                Log.w(TAG, "ranne stiahnutie cien zlyhalo", t)
                Result.retry()
            } finally {
                // Scrapery su bez Androidu a bajty len nazbieraju; tu je prvy
                // bod, kde je po nich Context. Bez tohto by prenos, ktory sa
                // stal na pozadi, v pocitadle chybal — aj ked zlyhal.
                Prenos.odovzdaj(applicationContext)
            }
        }
    }
}
