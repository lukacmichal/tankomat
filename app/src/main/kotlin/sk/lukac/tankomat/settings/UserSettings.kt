package sk.lukac.tankomat.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import sk.lukac.tankomat.BuildConfig
import sk.lukac.tankomat.data.model.UserConfig

private const val TAG = "UserSettings"
private const val SECRET_FILE = "tankomat_secret"
private const val K_NAS_PASSWORD = "nas_password"

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tankomat_settings")

/**
 * Užívateľské nastavenia perzistované do DataStore Preferences ako jeden JSON
 * blob. Výrazne jednoduchšie ako mať key-per-field, a JSON nám stačí
 * (config je drobný, čítame ho zriedka).
 *
 * **Heslo k NAS-u v tom blobe nie je.** Leží v `EncryptedSharedPreferences`,
 * kľúč drží Android Keystore. Predtým bolo v JSON-e v čitateľnej podobe a so
 * zapnutou zálohou odchádzalo do Google cloudu — teraz je záloha vypnutá aj
 * heslo šifrované. Do `UserConfig` sa dopĺňa až pri čítaní, takže zvyšok appky
 * o tom delení nevie a pracuje s configom ako predtým.
 */
class UserSettings(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val key = stringPreferencesKey("config_json_v1")

    /** Šifrované úložisko; pri nefunkčnom keystore padni na obyčajné, nech appka žije. */
    private val secret: SharedPreferences by lazy {
        val app = context.applicationContext
        try {
            val master = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app, SECRET_FILE, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "šifrované prefs nedostupné, používam obyčajné", e)
            app.getSharedPreferences(SECRET_FILE, Context.MODE_PRIVATE)
        }
    }

    val config: Flow<UserConfig> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs -> withPassword(parse(prefs[key])) }

    private fun parse(raw: String?): UserConfig =
        raw?.let { runCatching { json.decodeFromString(UserConfig.serializer(), it) }.getOrNull() }
            ?: UserConfig()

    private fun stripPassword(c: UserConfig): UserConfig =
        c.copy(update = c.update.copy(password = ""))

    /**
     * Doplní heslo zo šifrovaného úložiska.
     *
     * Ak blob ešte nesie heslo po starej verzii, má prednosť a hneď sa
     * prekopíruje — inak by používateľ po aktualizácii appky o uložené heslo
     * prišiel. Z blobu ho odstráni až [migrateLegacyPassword] alebo najbližší
     * zápis, lebo tu (v čítacej vetve) sa do DataStore zapisovať nedá.
     */
    private fun withPassword(cfg: UserConfig): UserConfig {
        val legacy = cfg.update.password
        if (legacy.isNotEmpty()) {
            secret.edit().putString(K_NAS_PASSWORD, legacy).apply()
            return cfg
        }
        // Poradie je podstatné: ručne zadané má prednosť pred tým, čo do APK
        // vložil build. Inak by sa prepis v Nastaveniach po reštarte stratil.
        val password = secret.getString(K_NAS_PASSWORD, "").orEmpty()
            .ifEmpty { BuildConfig.NAS_PASS }
        val username = cfg.update.username.ifEmpty { BuildConfig.NAS_USER }
        if (password.isEmpty() && username == cfg.update.username) return cfg
        return cfg.copy(update = cfg.update.copy(username = username, password = password))
    }

    suspend fun update(transform: (UserConfig) -> UserConfig) {
        context.dataStore.edit { prefs ->
            val current = withPassword(parse(prefs[key]))
            val next = transform(current)
            // Heslo zhodné s tým, čo vložil build, sa NEukladá: uložená kópia by
            // po zmene v nas-credentials.local a prebuildovaní prebila novú
            // hodnotu a appka by sa hlásila starým heslom.
            val password = next.update.password
            secret.edit().apply {
                if (password.isEmpty() || password == BuildConfig.NAS_PASS) {
                    remove(K_NAS_PASSWORD)
                } else {
                    putString(K_NAS_PASSWORD, password)
                }
            }.apply()
            prefs[key] = json.encodeToString(UserConfig.serializer(), stripPassword(next))
        }
    }

    /**
     * Vymaže heslo, ktoré do blobu zapísala staršia verzia appky.
     *
     * Volá sa raz pri štarte. Bez toho by čitateľné heslo ostalo ležať v
     * `tankomat_settings` až do najbližšej zmeny nastavení.
     */
    suspend fun migrateLegacyPassword() {
        context.dataStore.edit { prefs ->
            val cfg = parse(prefs[key])
            if (cfg.update.password.isEmpty()) return@edit
            secret.edit().putString(K_NAS_PASSWORD, cfg.update.password).apply()
            prefs[key] = json.encodeToString(UserConfig.serializer(), stripPassword(cfg))
            Log.i(TAG, "heslo k NAS-u presunuté do šifrovaného úložiska")
        }
    }
}
