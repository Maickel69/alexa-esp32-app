package com.example.alexaesp32.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val disponible: Boolean = false,
    val version: String = "",
    val urlDescarga: String = ""
)

object UpdateManager {

    // Version local base
    const val VERSION_ACTUAL = "v1.0.0"
    private const val GITHUB_REPO = "Maickel69/alexa-esp32-app"

    suspend fun verificarActualizacion(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "AlexaESP32-App")
            }

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                val tagName = json.optString("tag_name", "")
                
                var downloadUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                if (tagName.isNotEmpty() && tagName != VERSION_ACTUAL && downloadUrl.isNotEmpty()) {
                    return@withContext UpdateInfo(
                        disponible = true,
                        version = tagName,
                        urlDescarga = downloadUrl
                    )
                }
            }
        } catch (_: Exception) {}
        UpdateInfo(disponible = false)
    }

    suspend fun descargarEInstalar(context: Context, urlDescarga: String, onProgreso: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlDescarga)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

            // Manejar redirecciones de GitHub Release S3 / Assets
            var targetConn = conn
            if (conn.responseCode in listOf(301, 302, 303, 307)) {
                val redirectUrl = conn.getHeaderField("Location")
                targetConn = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                }
            }

            val totalBytes = targetConn.contentLength
            val apkFile = File(context.cacheDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            targetConn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var descargados = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        descargados += bytesRead
                        if (totalBytes > 0) {
                            val progreso = ((descargados.toDouble() / totalBytes) * 100).toInt()
                            onProgreso(progreso)
                        }
                    }
                    output.flush()
                }
            }

            // Lanzar instalador nativo de Android
            withContext(Dispatchers.Main) {
                val contentUri: Uri = FileProvider.getUriForFile(
                    context,
                    "com.alexa.esp32.fileprovider",
                    apkFile
                )

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
