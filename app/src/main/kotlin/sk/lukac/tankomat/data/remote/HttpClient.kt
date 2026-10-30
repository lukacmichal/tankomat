package sk.lukac.tankomat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Tenký HTTP klient nad [HttpURLConnection]. Schválne nepoužívam OkHttp —
 * Jsoup samotný má svoju connect() metódu, ale pre plain text odpovede
 * (ČNB) chcem niečo nezávislé. Žiadne externé dependencies navyše.
 *
 * Šetrenie mobilných dát (ANDROID-1)
 * ----------------------------------
 * Stránka dalioil.sk má **132 kB** HTML (24 kB po gzipe) a vytiahne sa z nej
 * pár cien; dáta mapy mbenzin.cz majú ~100 kB po gzipe.
 * Bez cache to appka stiahne znova pri
 * každom obnovení, hoci ceny pohonných hmôt sa menia nanajvýš párkrát denne.
 * Preto:
 *
 *  1. **Cache s TTL** — do [maxVekMs] sa odpoveď vráti z pamäte a na sieť sa
 *     nesiahne vôbec.
 *  2. **Podmienený dopyt** — po vypršaní TTL sa pošle `If-None-Match` /
 *     `If-Modified-Since`. Keď server odpovie 304, prenesú sa len hlavičky
 *     a telo sa vezme z cache. ČNB to podporuje (`Last-Modified`,
 *     `Cache-Control: max-age=86400`); dalioil.sk validátory neposiela, tam
 *     šetrí len TTL.
 *
 * **Accept-Encoding sa zámerne nenastavuje.** `HttpURLConnection` si hlavičku
 * pridá sám a odpoveď transparentne rozbalí; len čo ju nastavíme ručne, prestane
 * rozbaľovať a do Jsoup by šli binárne dáta. Overené hlavičkou `Vary:
 * Accept-Encoding` na dalioil.sk — gzip teda naozaj beží.
 */
object HttpClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Ceny PHM sa menia párkrát denne — desať minút je bezpečná čerstvosť. */
    const val TTL_CENY_MS = 10 * 60 * 1000L

    /**
     * mbenzin.cz: kľúč sa pri nadmernom používaní obmedzí, preto dlhšie než
     * ostatné ceny — opakované potiahnutie dole do pol hodiny nejde na sieť.
     */
    const val TTL_MBENZIN_MS = 30 * 60 * 1000L

    /** ČNB kurz vychádza raz za pracovný deň o 14:30. */
    const val TTL_KURZ_MS = 6 * 60 * 60 * 1000L

    private data class Zaznam(
        val telo: String,
        val etag: String?,
        val lastModified: String?,
        val stiahnute: Long,
    )

    private val cache = HashMap<String, Zaznam>()
    private val zamok = Mutex()

    /** Testy a „stiahnuť znova" v UI potrebujú čistý štart. */
    suspend fun vycistiCache() = zamok.withLock { cache.clear() }

    /**
     * @param hlavicky hlavičky navyše, napr. prístupový kľúč. Pri nich sa
     *   presmerovanie nenasleduje: `HttpURLConnection` by hlavičky poslal aj
     *   na cudzí server, kam by ho presmerovanie poslalo.
     */
    suspend fun fetchText(
        url: String,
        maxVekMs: Long = TTL_CENY_MS,
        hlavicky: Map<String, String> = emptyMap(),
        teraz: () -> Long = System::currentTimeMillis,
    ): String {
        val ulozene = zamok.withLock { cache[url] }
        if (ulozene != null && teraz() - ulozene.stiahnute < maxVekMs) {
            return ulozene.telo
        }

        val vysledok = stiahni(url, ulozene, hlavicky, teraz)
        zamok.withLock { cache[url] = vysledok }
        return vysledok.telo
    }

    private suspend fun stiahni(
        url: String,
        ulozene: Zaznam?,
        hlavicky: Map<String, String>,
        teraz: () -> Long,
    ): Zaznam = withContext(Dispatchers.IO) {
        dopyt(url, ulozene, hlavicky, teraz)
    }

    private fun dopyt(
        url: String,
        ulozene: Zaznam?,
        hlavicky: Map<String, String>,
        teraz: () -> Long,
    ): Zaznam {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = hlavicky.isEmpty()
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "sk,cs,en;q=0.7")
            hlavicky.forEach { (meno, hodnota) -> setRequestProperty(meno, hodnota) }
            ulozene?.etag?.let { setRequestProperty("If-None-Match", it) }
            ulozene?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && ulozene != null) {
                return ulozene.copy(stiahnute = teraz())
            }
            if (code !in 200..299) {
                error("HTTP $code pre $url")
            }
            val telo = connection.inputStream.use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
            return Zaznam(
                telo = telo,
                etag = connection.getHeaderField("ETag"),
                lastModified = connection.getHeaderField("Last-Modified"),
                stiahnute = teraz(),
            )
        } finally {
            connection.disconnect()
        }
    }
}
