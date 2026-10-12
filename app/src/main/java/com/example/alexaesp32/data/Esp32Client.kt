package com.example.alexaesp32.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Esp32Client {

    suspend fun enviarPrompt(ip: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$ip/preguntar")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 18000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }

            val data = "prompt=" + URLEncoder.encode(prompt, "UTF-8")
            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(data)
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val resp = reader.readText()
                reader.close()
                Result.success(resp.trim())
            } else {
                Result.failure(Exception("HTTP Error $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun obtenerEstado(ip: String): Result<Esp32Status> = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$ip/estado")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val text = reader.readText()
                reader.close()

                val json = JSONObject(text)
                val status = Esp32Status(
                    conectado = true,
                    btConectado = json.optBoolean("bt", false),
                    parlante = json.optString("parlante", ""),
                    musica = json.optString("musica", ""),
                    volumen = json.optInt("vol", 60)
                )
                Result.success(status)
            } else {
                Result.failure(Exception("HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
