package com.example.alexaesp32.data

data class Esp32Status(
    val conectado: Boolean = false,
    val btConectado: Boolean = false,
    val parlante: String = "",
    val musica: String = "",
    val volumen: Int = 60,
    val sdDisponible: Boolean = false,
    val cola: String = ""
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val texto: String,
    val esUsuario: Boolean,
    val fecha: String = ""
)

data class TrackRecomendado(
    val tituloCompleto: String,
    val artistaNombre: String,
    val artistaId: Long,
    val urlMp3: String
)

data class TrackDescargado(
    val tituloCompleto: String,
    val artistaNombre: String,
    val artistaId: Long,
    val bytesMp3: ByteArray,
    val recomendaciones: List<TrackRecomendado>
)
