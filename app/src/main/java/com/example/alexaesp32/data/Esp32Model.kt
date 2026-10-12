package com.example.alexaesp32.data

data class Esp32Status(
    val conectado: Boolean = false,
    val btConectado: Boolean = false,
    val parlante: String = "",
    val musica: String = "",
    val volumen: Int = 60
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val texto: String,
    val esUsuario: Boolean,
    val fecha: String = ""
)
