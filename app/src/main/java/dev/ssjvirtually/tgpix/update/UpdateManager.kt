package dev.ssjvirtually.tgpix.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.ssjvirtually.tgpix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val forceUpdate: Boolean,
    val releaseNotes: String
)

object UpdateManager {
    // We will query a version.json located in the main branch of the repository.
    // When the user pushes updates, they update this file.
    private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/ssjvirtually/TGPix/main/version.json"

    /**
     * Checks if a new version is available by comparing local VERSION_CODE with the one in version.json.
     */
    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(UPDATE_JSON_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                
                val latestVersionCode = json.getInt("versionCode")
                val latestVersionName = json.getString("versionName")
                val apkUrl = json.getString("apkUrl")
                val forceUpdate = json.optBoolean("forceUpdate", false)
                val releaseNotes = json.optString("releaseNotes", "")
                
                val currentVersionCode = BuildConfig.VERSION_CODE
                
                if (latestVersionCode > currentVersionCode) {
                    return@withContext UpdateInfo(
                        versionCode = latestVersionCode,
                        versionName = latestVersionName,
                        apkUrl = apkUrl,
                        forceUpdate = forceUpdate,
                        releaseNotes = releaseNotes
                    )
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Downloads the APK to the application cache directory, exposing download progress.
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL(apkUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            
            val fileLength = connection.contentLength
            val apkFile = File(context.cacheDir, "TGPix-update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }
            
            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    var totalBytesWritten = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead
                        if (fileLength > 0) {
                            val progress = totalBytesWritten.toFloat() / fileLength.toFloat()
                            onProgress(progress.coerceIn(0f, 1f))
                        }
                    }
                }
            }
            apkFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Checks if the app currently has permission to install package archives (Oreo+ requirement).
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Redirects the user to the "Install unknown apps" settings panel for this package.
     */
    fun launchUnknownAppSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Starts the Android Package Installer for the downloaded APK file.
     */
    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
