package com.example.alexaesp32.data

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

object MusicManager {

    fun estaSpotifyInstalado(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.spotify.music", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun extraerNombreCancion(texto: String): String? {
        val s = texto.lowercase().trim()

        // Excepciones de comandos de hardware para el ESP32
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

    fun reproducirCancion(context: Context, cancion: String): String {
        val tieneSpotify = estaSpotifyInstalado(context)

        return if (tieneSpotify) {
            try {
                // Intent oficial de reproducción directa de Android para asistentes (inicia PLAY automáticamente)
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage("com.spotify.music")
                    putExtra(SearchManager.QUERY, cancion)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, cancion)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Reproduciendo $cancion en Spotify."
            } catch (e: Exception) {
                try {
                    // Alternativa con URI directa si el intent de búsqueda no está disponible
                    val uri = Uri.parse("spotify:search:" + Uri.encode(cancion))
                    val intentFallback = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.spotify.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intentFallback)
                    "Abriendo $cancion en Spotify."
                } catch (e2: Exception) {
                    abrirEnYouTube(context, cancion)
                }
            }
        } else {
            abrirEnYouTube(context, cancion)
        }
    }

    private fun abrirEnYouTube(context: Context, cancion: String): String {
        try {
            val webUri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(cancion))
            val intent = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "No tienes Spotify. Buscando $cancion en YouTube."
        } catch (e: Exception) {
            return "No pude abrir el reproductor de musica."
        }
    }
}
