package sk.lukac.tankomat

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import sk.lukac.tankomat.settings.UserSettings
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
 * Styri veci, na ktorych to stoji:
 *
 * 1. **Len Wi-Fi.** `NetworkType.UNMETERED`: ked v tu hodinu Wi-Fi nie je,
 *    WorkManager pracu NEZAHODI ani ju nepusti cez simku — pocka na
 *    najblizsiu Wi-Fi. A v samotnej praci este [Prenos.naMobilnychDatach]:
 *    meranost nie je to iste co simka (docs/shared-standard.md 2.8b), operator moze
 *    neobmedzene data hlasit ako nemerane (ANDROID-5).
 * 2. **Jednorazova praca, ktora si naplanuje dalsiu — NIE periodicka**
 *    (ANDROID-5, 13. 10. 2026). Periodicka praca rata dalsi beh od skutocneho
 *    konca predosleho: rano bez Wi-Fi a Wi-Fi az o 17:00 by z rozvrhu urobilo
 *    „17:00" natrvalo. Takto sa kazdy den mieri znova na `rannaHodina`.
 *    Dalsi beh sa pripaja za beziaci (`APPEND_OR_REPLACE`) a WorkManager mu
 *    odklad zacne ratat az po jeho skonceni. Rovnako `PondelnaObnova` v Stocks.
 * 3. **Posledny sken lezi na disku** (`SnapshotStore`) a plati dalej. Cakanie
 *    na Wi-Fi teda nikoho neobera o cisla; appka ukazuje posledne zname ceny
 *    aj v aute bez signalu.
 * 4. **`KEEP`, kym sa hodina nezmenila.** Appka planuje pri kazdom starte a
 *    `REPLACE` s novym odkladom by cakajucu pracu zahodil — o samotny cas
 *    by sa neprislo, ale beziaca praca by sa zrusila.
 *
 * Dvojca v appke `pocasie` (`Obnova.kt`) robi to iste a rovnako; sedia aj
 * nazvy, aby sa dali citat vedla seba.
 */
object RannaObnova {
    private const val TAG = "RannaObnova"

    /**
     * Novy nazov od ANDROID-5. Pod starym visi periodicka praca a jednorazova
     * sa k periodickej pripojit neda — stary sa preto len rusi.
     */
    private const val MENO = "tankomat_rano_1x"
    private const val MENO_STARE = "tankomat_rano"

    /**
     * Kolko pokusov ma jeden ranny beh, kym to vzda do zajtra. Bez stropu by
     * nedostupna stranka drzala beh v `retry` donekonecna a zajtrajsok by sa
     * nenaplanoval nikdy.
     */
    private const val POKUSOV = 5

    /**
     * Podpis uz naplanovaneho rozvrhu vo vlastnom subore prefs.
     *
     * Zamerne nie v `UserConfig`: je to vnutorna vec rozvrhu, nie nastavenie,
     * a v DataStore blobe by sa miesala s tym, co si clovek naozaj nastavil.
     */
    private const val FILE = "tankomat_rozvrh"
    private const val K_PODPIS = "podpis"
    private const val K_SIESTA = "siesta_nastavena"

    fun naplanuj(ctx: Context, zapnuta: Boolean, hodina: Int) {
        val app = ctx.applicationContext
        val wm = WorkManager.getInstance(app)
        val sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        wm.cancelUniqueWork(MENO_STARE)
        if (!zapnuta) {
            wm.cancelUniqueWork(MENO)
            sp.edit().putString(K_PODPIS, "").apply()
            return
        }
        val hod = hodina.coerceIn(0, 23)
        val podpis = "1x:rano=$hod"
        val politika = if (sp.getString(K_PODPIS, "") == podpis) {
            ExistingWorkPolicy.KEEP
        } else {
            ExistingWorkPolicy.REPLACE
        }
        wm.enqueueUniqueWork(MENO, politika, poziadavka(hod, System.currentTimeMillis()))
        sp.edit().putString(K_PODPIS, podpis).apply()
    }

    /**
     * Raz po aktualizacii nastavi rannu obnovu na 6:00 a zapne ju (ANDROID-5,
     * „nech sa to spusta vsade o 6:00"). Nova predvolba by na telefone
     * s ulozenou inou hodinou nezmenila nic; potom uz plati, co si clovek
     * nastavi v Nastaveniach.
     */
    suspend fun nastavSiestuRaz(ctx: Context, nastavenia: UserSettings) {
        val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (sp.getBoolean(K_SIESTA, false)) return
        nastavenia.update { it.copy(rannaObnova = true, rannaHodina = 6) }
        sp.edit().putBoolean(K_SIESTA, true).apply()
    }

    /**
     * Zajtrajsi beh, volany z beziacej prace. `APPEND_OR_REPLACE`, nie
     * `REPLACE`: ten by zrusil prave beziacu pracu, teda samu seba.
     */
    private fun naplanujDalsiu(ctx: Context, hodina: Int) {
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
            MENO, ExistingWorkPolicy.APPEND_OR_REPLACE,
            poziadavka(hodina.coerceIn(0, 23), System.currentTimeMillis()))
    }

    private fun poziadavka(hodina: Int, teraz: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<Praca>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setInitialDelay(odkladDoHodiny(hodina, teraz), TimeUnit.MILLISECONDS)
            // 5, 10, 20, 40 min: vypadok stranky o 6:00 sa do odchodu doziha,
            // ale beh nestrieli pokusy kazdych 30 s.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

    /**
     * Kolko milisekund zostava do najblizsej [hodina]:00 miestneho casu.
     *
     * Pocita sa cez `LocalDate.atTime`, nie pripocitanim 24 hodin: pri zmene
     * casu ma "6:00" ostat sestou hodinou rano, nie sa posunut na piatu.
     * Ked je prave teraz presne ta hodina, mieri sa na zajtrajsok — inak by
     * odklad vysiel nula a dalsi beh by sa spustil hned po tomto.
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
     * Neuspech je `retry` (najviac [POKUSOV]), potom sa beh vzda a naplanuje
     * zajtrajsok. Posledny dobry sken sa pri neuspechu neprepisuje — o to sa
     * stara `FuelRepository`, ktory prazdny vysledok vobec neulozi.
     */
    class Praca(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as? TankomatApp ?: return Result.success()
            val config = app.container.userSettings.config.first()
            // Vypnute medzitym v Nastaveniach — rozvrh sa zrusi pri najblizsom
            // starte appky, dovtedy nech beh aspon nic nestahuje.
            if (!config.rannaObnova) return Result.success()
            val ok = if (Prenos.naMobilnychDatach(app)) {
                // Nemerana siet, ale simka: nestahovat, pockat na Wi-Fi.
                false
            } else {
                try {
                    app.container.repository.refresh(
                        fuel = config.fuelType,
                        towns = config.czTowns,
                        searchRadiusKm = config.searchRadiusKm,
                        homePriceOverride = config.homePriceEurOverride,
                    )
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "ranne stiahnutie cien zlyhalo", t)
                    false
                }
            }
            // Zajtrajsok sa pripaja az po poslednom pokuse: kazde opakovanie
            // spusti doWork znova a pripojilo by dalsieho.
            if (!ok && runAttemptCount < POKUSOV - 1) return Result.retry()
            naplanujDalsiu(app, config.rannaHodina)
            return Result.success()
        }
    }
}
