package sk.lukac.tankomat.data.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.lukac.tankomat.data.model.UpdateConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update z domáceho NAS-u cez SMB2/3 (knižnica SMBJ).
 *
 * Každá appka má na NAS-e vlastný podpriečinok (`UpdateConfig.remotePath`,
 * napr. „Tankomat") s **jedným súborom pevného mena** (`UpdateConfig.apkFileName`,
 * napr. `tankomat.apk`) — pri novom vydaní sa jednoducho prepíše, appka teda
 * nepotrebuje verziu vyčítavať z názvu súboru. Skutočný `versionCode` sa zistí
 * až po stiahnutí, priamo z APK (`ApkInstaller.readVersionCode`).
 *
 * Krátky `soTimeout`/`timeout` (5 s) je zámerný: NAS je len na domácej sieti,
 * takže keď je appka mimo domáceho WiFi (napr. na ceste v ČR), tichá kontrola
 * pri štarte má rýchlo zlyhať namiesto dlhého čakania na nedostupnú IP.
 */
class SmbUpdater {

    /** Stiahne `apkFileName` z NAS-u do [destination]. */
    suspend fun download(cfg: UpdateConfig, destination: File): File = withContext(Dispatchers.IO) {
        val client = SMBClient(
            SmbConfig.builder()
                .withSoTimeout(5, TimeUnit.SECONDS)
                .withTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        client.connect(cfg.host).use { connection ->
            val auth = AuthenticationContext(
                cfg.username,
                cfg.password.toCharArray(),
                // SMBJ chce doménu tretim parametrom. Synology ju v pracovnej
                // skupine nepouživa, takže je tu natvrdo null — pole "Doména"
                // sa 27. 8. 2026 z nastaveni odstranilo, lebo bolo cely cas
                // prazdne a nikto nevedel, na čo je.
                null,
            )
            val session = connection.authenticate(auth)
            val share = session.connectShare(cfg.shareName) as? DiskShare
                ?: error("„${cfg.shareName}\" nie je súborový share.")
            share.use { s ->
                val remotePath = joinPath(cfg.remotePath, cfg.apkFileName)
                if (!s.fileExists(remotePath)) {
                    error(
                        "Na NAS-e som nenašiel „${cfg.apkFileName}\" v priečinku " +
                            "„${cfg.remotePath.ifBlank { "/" }}\".",
                    )
                }
                s.openFile(
                    remotePath,
                    setOf(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { file ->
                    destination.parentFile?.mkdirs()
                    file.inputStream.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        destination
    }

    /**
     * Prečíta malý textový súbor vedľa APK (používa sa na `output-metadata.json`).
     *
     * Existuje preto, aby tichá kontrola pri štarte nemusela ťahať celých ~21 MB
     * APK — metadáta majú pár stoviek bajtov. Celé APK sa sťahuje až vtedy, keď
     * používateľ v ponuke povie áno.
     */
    suspend fun readText(cfg: UpdateConfig, fileName: String): String = withContext(Dispatchers.IO) {
        val client = SMBClient(
            SmbConfig.builder()
                .withSoTimeout(5, TimeUnit.SECONDS)
                .withTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
        client.connect(cfg.host).use { connection ->
            val auth = AuthenticationContext(
                cfg.username,
                cfg.password.toCharArray(),
                // SMBJ chce doménu tretim parametrom. Synology ju v pracovnej
                // skupine nepouživa, takže je tu natvrdo null — pole "Doména"
                // sa 27. 8. 2026 z nastaveni odstranilo, lebo bolo cely cas
                // prazdne a nikto nevedel, na čo je.
                null,
            )
            val session = connection.authenticate(auth)
            val share = session.connectShare(cfg.shareName) as? DiskShare
                ?: error("„${cfg.shareName}\" nie je súborový share.")
            share.use { s ->
                val path = joinPath(cfg.remotePath, fileName)
                if (!s.fileExists(path)) error("Na NAS-e nie je „$fileName\".")
                s.openFile(
                    path,
                    setOf(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { it.inputStream.readBytes().decodeToString() }
            }
        }
    }

    internal companion object {
        /** SMB používa spätné lomítka; prázdna cesta = koreň share-u. */
        fun joinPath(dir: String, fileName: String): String {
            val clean = dir.trim().trim('/', '\\').replace('/', '\\')
            return if (clean.isEmpty()) fileName else "$clean\\$fileName"
        }
    }
}
