package com.example.alexaesp32.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

    private const val GITHUB_REPO = "Maickel69/alexa-esp32-app"

    suspend fun verificarActualizacion(context: Context): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
            val versionInstalada = prefs.getString("version_instalada", "v1.0.1") ?: "v1.0.1"

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

                // Si ya esta en esta version, no mostrar banner
                if (tagName.isEmpty() || tagName == versionInstalada) {
                    return@withContext UpdateInfo(disponible = false)
                }

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

                if (downloadUrl.isNotEmpty()) {
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

    suspend fun descargarEInstalar(context: Context, info: UpdateInfo, onProgreso: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Guardar que esta version ya se descargo
            val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
            prefs.edit().putString("version_instalada", info.version).apply()

            // 1. Verificar permiso de instalación de APKs en Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    withContext(Dispatchers.Main) {
                        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(settingsIntent)
                    }
                    return@withContext Result.failure(Exception("Activa el permiso 'Permitir desde esta fuente' para actualizar."))
                }
            }

            val url = URL(info.urlDescarga)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

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
