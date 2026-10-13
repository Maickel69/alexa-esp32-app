package com.example.alexaesp32.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
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
                    volumen = json.optInt("vol", 60),
                    sdDisponible = json.optBoolean("sd", false),
                    cola = json.optString("cola", "")
                )
                Result.success(status)
            } else {
                Result.failure(Exception("HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reproducirDesdeSD(ip: String, query: String, enCola: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$ip/sd_play")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 8000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }

            val colaVal = if (enCola) "1" else "0"
            val body = "q=" + URLEncoder.encode(query, "UTF-8") + "&cola=$colaVal"
            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(body)
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val resp = reader.readText()
                reader.close()
                Result.success(resp.trim())
            } else {
                Result.failure(Exception("HTTP_$code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun subirMp3ASD(
        ip: String,
        titulo: String,
        bytesMp3: ByteArray,
        enCola: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val colaParam = if (enCola) "1" else "0"
            val tituloEnc = URLEncoder.encode(titulo, "UTF-8")
            val boundary = "----AlexaEsp32Boundary" + System.currentTimeMillis()
            val url = URL("http://$ip/subir_mp3?titulo=$tituloEnc&cola=$colaParam")

            val headerPart = StringBuilder()
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"cancion.mp3\"\r\n")
                .append("Content-Type: audio/mpeg\r\n\r\n")
                .toString()
                .toByteArray(Charsets.UTF_8)

            val footerPart = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val totalLength = headerPart.size + bytesMp3.size + footerPart.size

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 60000
                doOutput = true
                doInput = true
                useCaches = false
                setFixedLengthStreamingMode(totalLength)
                setRequestProperty("Connection", "close")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                out.write(headerPart)
                var offset = 0
                val chunkSize = 4096
                while (offset < bytesMp3.size) {
                    val len = minOf(chunkSize, bytesMp3.size - offset)
                    out.write(bytesMp3, offset, len)
                    offset += len
                }
                out.write(footerPart)
                out.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val resp = reader.readText()
                reader.close()
                Result.success(resp.trim())
            } else {
                val errStream = conn.errorStream
                val errTxt = if (errStream != null) {
                    BufferedReader(InputStreamReader(errStream, "UTF-8")).readText()
                } else {
                    "HTTP $code"
                }
                Result.failure(Exception("Error SD ($code): $errTxt"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
