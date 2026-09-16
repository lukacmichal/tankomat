package sk.lukac.tankomat.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Testy šetrenia dát (ANDROID-1).
 *
 * Server je napísaný priamo nad [ServerSocket] — Android unit testy sa
 * kompilujú proti `android.jar`, takže `com.sun.net.httpserver` k dispozícii
 * nie je a pridávať kvôli tomu mock knižnicu je zbytočné.
 *
 * Overuje sa to, čo reálne šetrí prenos: že sa do TTL nesiahne na sieť vôbec
 * a že po TTL server dostane validátory a smie odpovedať 304 bez tela.
 */
class HttpClientCacheTest {

    private lateinit var socket: ServerSocket
    @Volatile private var bezi = true

    @Volatile private var poziadaviek = 0
    @Volatile private var poslednyIfNoneMatch: String? = null
    @Volatile private var poslednyIfModifiedSince: String? = null
    @Volatile private var telo = "prve telo"
    @Volatile private var vratit304 = false

    private val url: String get() = "http://127.0.0.1:${socket.localPort}/ceny"

    @Before
    fun start() {
        socket = ServerSocket(0)
        thread(isDaemon = true) {
            while (bezi) {
                val klient = try { socket.accept() } catch (e: Exception) { break }
                klient.use { obsluz(it) }
            }
        }
        runBlocking { HttpClient.vycistiCache() }
    }

    private fun obsluz(klient: Socket) {
        val citac = BufferedReader(InputStreamReader(klient.getInputStream()))
        var ifNoneMatch: String? = null
        var ifModifiedSince: String? = null
        while (true) {
            val riadok = citac.readLine() ?: return
            if (riadok.isEmpty()) break
            val d = riadok.indexOf(':')
            if (d > 0) {
                val meno = riadok.substring(0, d).trim().lowercase()
                val hodnota = riadok.substring(d + 1).trim()
                if (meno == "if-none-match") ifNoneMatch = hodnota
                if (meno == "if-modified-since") ifModifiedSince = hodnota
            }
        }
        poziadaviek++
        poslednyIfNoneMatch = ifNoneMatch
        poslednyIfModifiedSince = ifModifiedSince

        val hlavicky = "ETag: \"v1\"\r\n" +
            "Last-Modified: Tue, 18 Aug 2026 17:00:00 GMT\r\n"
        val odpoved = if (vratit304) {
            "HTTP/1.1 304 Not Modified\r\n$hlavicky" + "Content-Length: 0\r\nConnection: close\r\n\r\n"
        } else {
            val bajty = telo.toByteArray()
            "HTTP/1.1 200 OK\r\n$hlavicky" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bajty.size}\r\nConnection: close\r\n\r\n$telo"
        }
        klient.getOutputStream().apply { write(odpoved.toByteArray()); flush() }
    }

    @After
    fun stop() {
        bezi = false
        socket.close()
        runBlocking { HttpClient.vycistiCache() }
    }

    @Test
    fun druhyDopytDoTtlNejdeNaSiet() = runBlocking {
        assertEquals("prve telo", HttpClient.fetchText(url))
        assertEquals("prve telo", HttpClient.fetchText(url))
        assertEquals("do TTL sa smie ist na siet len raz", 1, poziadaviek)
    }

    @Test
    fun poVyprsaniTtlSaPosleValidator() = runBlocking {
        // teraz() je injektovany, takze sa nemusi nic cakat.
        var cas = 1_000_000L
        HttpClient.fetchText(url, HttpClient.TTL_CENY_MS) { cas }
        assertEquals(1, poziadaviek)

        cas += HttpClient.TTL_CENY_MS + 1
        vratit304 = true
        val telo2 = HttpClient.fetchText(url, HttpClient.TTL_CENY_MS) { cas }

        assertEquals("server dostal druhy dopyt", 2, poziadaviek)
        assertEquals("\"v1\"", poslednyIfNoneMatch)
        assertTrue("If-Modified-Since musi ist tiez", poslednyIfModifiedSince != null)
        assertEquals("pri 304 sa telo berie z cache", "prve telo", telo2)
    }

    @Test
    fun poTristoStyriSaTtlPosunie() = runBlocking {
        var cas = 1_000_000L
        HttpClient.fetchText(url, HttpClient.TTL_CENY_MS) { cas }

        cas += HttpClient.TTL_CENY_MS + 1
        vratit304 = true
        HttpClient.fetchText(url, HttpClient.TTL_CENY_MS) { cas }
        assertEquals(2, poziadaviek)

        // Hned po 304 ma platit nove TTL — inak by kazde dalsie obnovenie
        // znova chodilo na siet a revalidacia by nic neusetrila.
        HttpClient.fetchText(url, HttpClient.TTL_CENY_MS) { cas }
        assertEquals("po 304 sa TTL musi posunut", 2, poziadaviek)
    }

    @Test
    fun zmenaObsahuSaPrejaviPoTtl() = runBlocking {
        var cas = 1_000_000L
        assertEquals("prve telo", HttpClient.fetchText(url, HttpClient.TTL_CENY_MS) { cas })

        telo = "nove ceny"
        cas += HttpClient.TTL_CENY_MS + 1
        assertEquals("nove ceny", HttpClient.fetchText(url, HttpClient.TTL_CENY_MS) { cas })
    }

    @Test
    fun kurzMaDlhsieTtlNezCeny() {
        assertTrue(
            "kurz vychadza raz denne, ceny castejsie",
            HttpClient.TTL_KURZ_MS > HttpClient.TTL_CENY_MS,
        )
    }
}
