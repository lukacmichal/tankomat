package sk.lukac.tankomat.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Spustenie systémového inštalátora nad stiahnutým APK.
 *
 * Android 8+ vyžaduje, aby mala appka povolenie „Inštalovať neznáme aplikácie".
 * Povolenie `REQUEST_INSTALL_PACKAGES` v manifeste len umožní o to požiadať —
 * samotné zapnutie robí užívateľ v systémových nastaveniach, preto
 * [canInstall] / [openInstallPermissionSettings].
 */
object ApkInstaller {

    /** Aktuálny versionCode tejto appky (na porovnanie s tým na NAS-e). */
    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /**
     * Prečíta `versionCode` z APK súboru bez jeho inštalácie — appka na NAS-e
     * má vždy rovnaké meno (prepisuje sa), takže verziu nezisťujeme z názvu,
     * ale priamo z manifestu stiahnutého balíčka. Null = súbor sa nedá
     * naparsovať ako APK (poškodený download a pod.).
     */
    fun readVersionCode(context: Context, apk: File): Long? {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /** Má appka povolené inštalovať APK z neznámych zdrojov? */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Otvorí systémovú obrazovku, kde sa povolenie zapína. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Odovzdá APK systémovému inštalátoru cez FileProvider URI. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Kam ukladáme stiahnuté APK (zdieľané cez FileProvider — viď `file_paths.xml`). */
    fun downloadTarget(context: Context, fileName: String): File =
        File(File(context.cacheDir, "updates"), fileName)
}
