package com.example.alexaesp32.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MusicManager {

    fun esComandoSiguiente(texto: String): Boolean {
        val s = texto.lowercase().trim()
        return s == "siguiente" ||
            s == "siguiente cancion" ||
            s == "siguiente canción" ||
            s == "otra cancion" ||
            s == "otra canción" ||
            s == "cambia de cancion" ||
            s == "cambia la cancion" ||
            s == "pasa la cancion"
    }

    fun extraerNombreCancion(texto: String): String? {
        val s = texto.lowercase().trim()

        // Excepciones de comandos de hardware básicos para el ESP32
        if (s == "pon musica" || s == "reproduce musica" || s == "para la musica" || s == "parar musica" || s == "musica") {
            return null
        }

        val prefijos = listOf(
            "reproduce musica de ",
            "reproducir musica de ",
            "pon musica de ",
            "pon la cancion ",
            "reproduce la cancion ",
            "reproducir la cancion ",
            "descarga y reproduce ",
            "descarga la cancion ",
            "reproduce a ",
            "pon a ",
            "reproduce ",
            "reproducir ",
            "pon ",
            "play ",
            "escuchar "
        )

        for (p in prefijos) {
            if (s.startsWith(p)) {
                val cancion = s.removePrefix(p).trim()
                if (cancion.isNotEmpty() && cancion != "musica" && cancion != "la musica") {
                    return cancion
                }
            }
        }

        if (s.contains("de ") && (s.startsWith("cancion") || s.startsWith("tema"))) {
            return s.substringAfter("de ").trim()
        }

        return null
    }

    suspend fun buscarYDescargarMp3(
        query: String,
        historialExcluido: Set<String> = emptySet()
    ): Result<TrackDescargado> = withContext(Dispatchers.IO) {
        try {
            var dataArr: org.json.JSONArray? = null
            val candidatasQuery = mutableListOf(query)
            if (query.contains(" de ", ignoreCase = true)) {
                candidatasQuery.add(query.replace(" de ", " ", ignoreCase = true))
            }
            if (query.contains("tamos", ignoreCase = true)) {
                candidatasQuery.add(query.replace("tamos", "estamos", ignoreCase = true))
            }

            for (q in candidatasQuery) {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val searchJson = hacerGetJson("https://api.deezer.com/search?q=$encoded&limit=15")
                val arr = searchJson?.optJSONArray("data")
                if (arr != null && arr.length() > 0) {
                    dataArr = arr
                    break
                }
            }

            if (dataArr == null || dataArr.length() == 0) {
                return@withContext Result.failure(Exception("No encontré la canción '$query'"))
            }

            var elegidoTitulo = ""
            var elegidoArtista = ""
            var elegidoArtistaId = 0L
            var elegidoPreview = ""

            val candidatosExtras = mutableListOf<TrackRecomendado>()

            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val title = limpiarTexto(item.optString("title_short", item.optString("title", "")))
                val preview = item.optString("preview", "")
                val artistObj = item.optJSONObject("artist")
                val artistName = limpiarTexto(artistObj?.optString("name", "") ?: "")
                val artistId = artistObj?.optLong("id", 0L) ?: 0L

                if (title.isEmpty() || preview.isEmpty()) continue
                val nombreCompleto = if (artistName.isNotEmpty()) "$title - $artistName" else title
                val clave = nombreCompleto.lowercase()

                if (elegidoPreview.isEmpty() && !historialExcluido.contains(clave)) {
                    elegidoTitulo = nombreCompleto
                    elegidoArtista = artistName
                    elegidoArtistaId = artistId
                    elegidoPreview = preview
                } else {
                    if (!historialExcluido.contains(clave) && nombreCompleto != elegidoTitulo) {
                        candidatosExtras.add(
                            TrackRecomendado(
                                tituloCompleto = nombreCompleto,
                                artistaNombre = artistName,
                                artistaId = artistId,
                                urlMp3 = preview
                            )
                        )
                    }
                }
            }

            // Si todas estaban en el historial, tomar la primera disponible
            if (elegidoPreview.isEmpty()) {
                val item = dataArr.optJSONObject(0)
                val title = limpiarTexto(item?.optString("title_short", item.optString("title", "")) ?: "")
                val preview = item?.optString("preview", "") ?: ""
                val artistObj = item?.optJSONObject("artist")
                val artistName = limpiarTexto(artistObj?.optString("name", "") ?: "")
                val artistId = artistObj?.optLong("id", 0L) ?: 0L
                if (preview.isEmpty()) {
                    return@withContext Result.failure(Exception("La canción '$query' no tiene audio MP3 disponible"))
                }
                elegidoTitulo = if (artistName.isNotEmpty()) "$title - $artistName" else title
                elegidoArtista = artistName
                elegidoArtistaId = artistId
                elegidoPreview = preview
            }

            // Descargar los bytes del MP3 seleccionado
            val bytesMp3 = descargarBytesDesdeUrl(elegidoPreview)
                ?: return@withContext Result.failure(Exception("Error al descargar el archivo MP3"))

            // Construir lista de recomendaciones estilo YouTube (mismo artista + artistas relacionados)
            val recomendaciones = construirRecomendaciones(
                artistaId = elegidoArtistaId,
                excluirActual = elegidoTitulo.lowercase(),
                historialExcluido = historialExcluido,
                extrasBusqueda = candidatosExtras
            )

            Result.success(
                TrackDescargado(
                    tituloCompleto = elegidoTitulo,
                    artistaNombre = elegidoArtista,
                    artistaId = elegidoArtistaId,
                    bytesMp3 = bytesMp3,
                    recomendaciones = recomendaciones
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun obtenerRecomendacionesPorBusqueda(
        query: String,
        historialExcluido: Set<String>
    ): List<TrackRecomendado> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchJson = hacerGetJson("https://api.deezer.com/search?q=$encoded&limit=10") ?: return@withContext emptyList()
            val dataArr = searchJson.optJSONArray("data") ?: return@withContext emptyList()
            if (dataArr.length() == 0) return@withContext emptyList()

            val first = dataArr.optJSONObject(0)
            val artistId = first?.optJSONObject("artist")?.optLong("id", 0L) ?: 0L
            val extras = mutableListOf<TrackRecomendado>()

            for (i in 1 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val title = limpiarTexto(item.optString("title_short", item.optString("title", "")))
                val preview = item.optString("preview", "")
                val artistObj = item.optJSONObject("artist")
                val artistName = limpiarTexto(artistObj?.optString("name", "") ?: "")
                val aId = artistObj?.optLong("id", 0L) ?: 0L
                if (title.isNotEmpty() && preview.isNotEmpty()) {
                    val completo = "$title - $artistName"
                    if (!historialExcluido.contains(completo.lowercase())) {
                        extras.add(TrackRecomendado(completo, artistName, aId, preview))
                    }
                }
            }

            construirRecomendaciones(artistId, query.lowercase(), historialExcluido, extras)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun descargarMp3DeRecomendacion(track: TrackRecomendado): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val bytes = descargarBytesDesdeUrl(track.urlMp3)
            if (bytes != null && bytes.isNotEmpty()) {
                Result.success(bytes)
            } else {
                // Si el link CDN expiró, re-buscar por título completo
                val rebusqueda = buscarYDescargarMp3(track.tituloCompleto)
                rebusqueda.map { it.bytesMp3 }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun construirRecomendaciones(
        artistaId: Long,
        excluirActual: String,
        historialExcluido: Set<String>,
        extrasBusqueda: List<TrackRecomendado>
    ): List<TrackRecomendado> {
        val resultado = mutableListOf<TrackRecomendado>()
        val vistos = mutableSetOf<String>()
        vistos.add(excluirActual)
        vistos.addAll(historialExcluido)

        // 1. Artistas similares/relacionados (algoritmo estilo YouTube Mix)
        if (artistaId > 0L) {
            val relJson = hacerGetJson("https://api.deezer.com/artist/$artistaId/related?limit=4")
            val relArr = relJson?.optJSONArray("data")
            if (relArr != null) {
                for (r in 0 until relArr.length()) {
                    val relArtist = relArr.optJSONObject(r) ?: continue
                    val relId = relArtist.optLong("id", 0L)
                    if (relId > 0L) {
                        val topRelJson = hacerGetJson("https://api.deezer.com/artist/$relId/top?limit=3")
                        agregarDeArray(topRelJson?.optJSONArray("data"), resultado, vistos)
                    }
                }
            }

            // 2. Top canciones del mismo artista
            val topJson = hacerGetJson("https://api.deezer.com/artist/$artistaId/top?limit=8")
            agregarDeArray(topJson?.optJSONArray("data"), resultado, vistos)
        }

        // 3. Resultados adicionales de la búsqueda
        for (extra in extrasBusqueda) {
            val k = extra.tituloCompleto.lowercase()
            if (!vistos.contains(k)) {
                vistos.add(k)
                resultado.add(extra)
            }
        }

        // Mezclar ligeramente para que se sienta como una radio dinámica
        return resultado.shuffled()
    }

    private fun agregarDeArray(
        arr: org.json.JSONArray?,
        destino: MutableList<TrackRecomendado>,
        vistos: MutableSet<String>
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = limpiarTexto(item.optString("title_short", item.optString("title", "")))
            val preview = item.optString("preview", "")
            val artistObj = item.optJSONObject("artist")
            val artistName = limpiarTexto(artistObj?.optString("name", "") ?: "")
            val artistId = artistObj?.optLong("id", 0L) ?: 0L
            if (title.isEmpty() || preview.isEmpty()) continue

            val completo = if (artistName.isNotEmpty()) "$title - $artistName" else title
            val clave = completo.lowercase()
            if (!vistos.contains(clave)) {
                vistos.add(clave)
                destino.add(
                    TrackRecomendado(
                        tituloCompleto = completo,
                        artistaNombre = artistName,
                        artistaId = artistId,
                        urlMp3 = preview
                    )
                )
            }
        }
    }

    private fun limpiarTexto(s: String): String {
        return s.replace("á", "a").replace("é", "e").replace("í", "i")
            .replace("ó", "o").replace("ú", "u").replace("ñ", "n")
            .replace("Á", "A").replace("É", "E").replace("Í", "I")
            .replace("Ó", "O").replace("Ú", "U").replace("Ñ", "N")
            .trim()
    }

    private fun hacerGetJson(urlStr: String): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) Mobile")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                JSONObject(text)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun descargarBytesDesdeUrl(urlStr: String): ByteArray? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            if (conn.responseCode in 200..299) {
                val out = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
