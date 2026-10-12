package com.example.alexaesp32.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.alexaesp32.data.ChatMessage
import com.example.alexaesp32.data.Esp32Client
import com.example.alexaesp32.data.Esp32Status
import com.example.alexaesp32.data.MusicManager
import com.example.alexaesp32.data.TtsManager
import com.example.alexaesp32.data.UpdateInfo
import com.example.alexaesp32.data.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainScreenViewModel : ViewModel() {

    private val _ip = MutableStateFlow("192.168.1.11")
    val ip: StateFlow<String> = _ip.asStateFlow()

    private val _status = MutableStateFlow(Esp32Status())
    val status: StateFlow<Esp32Status> = _status.asStateFlow()

    private val _mensajes = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                texto = "Hola, soy tu Alexa. Pídeme lo que quieras o pulsa el micrófono.",
                esUsuario = false,
                fecha = horaActual()
            )
        )
    )
    val mensajes: StateFlow<List<ChatMessage>> = _mensajes.asStateFlow()

    private val _cargando = MutableStateFlow(false)
    val cargando: StateFlow<Boolean> = _cargando.asStateFlow()

    // Sistema de Auto-Actualización OTA
    private val _updateInfo = MutableStateFlow(UpdateInfo())
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    private val _progresoDescarga = MutableStateFlow(-1)
    val progresoDescarga: StateFlow<Int> = _progresoDescarga.asStateFlow()

    private var tts: TtsManager? = null

    init {
        iniciarSondeo()
        verificarActualizaciones()
    }

    fun inicializarTts(context: Context) {
        if (tts == null) {
            tts = TtsManager(context)
        }
    }

    fun cambiarIp(nuevaIp: String) {
        val limpia = nuevaIp.trim().removePrefix("http://").removeSuffix("/")
        if (limpia.isNotEmpty()) {
            _ip.value = limpia
            actualizarEstado()
        }
    }

    fun verificarActualizaciones() {
        viewModelScope.launch {
            val info = UpdateManager.verificarActualizacion()
            _updateInfo.value = info
        }
    }

    fun descargarEInstalarActualizacion(context: Context) {
        val info = _updateInfo.value
        if (!info.disponible || info.urlDescarga.isEmpty()) return

        viewModelScope.launch {
            _progresoDescarga.value = 0
            val res = UpdateManager.descargarEInstalar(context, info.urlDescarga) { p ->
                _progresoDescarga.value = p
            }
            res.onFailure {
                _progresoDescarga.value = -1
                agregarMensaje("Error al descargar actualización: ${it.localizedMessage}", esUsuario = false)
            }
        }
    }

    fun enviarMensaje(context: Context, texto: String) {
        val t = texto.trim()
        if (t.isEmpty() || _cargando.value) return

        agregarMensaje(t, esUsuario = true)
        _cargando.value = true

        viewModelScope.launch {
            // 1. Detectar si el usuario pide música
            val cancion = MusicManager.extraerNombreCancion(t)
            if (cancion != null) {
                val respuestaMusica = MusicManager.reproducirCancion(context, cancion)
                agregarMensaje(respuestaMusica, esUsuario = false)
                tts?.hablar(respuestaMusica)

                Esp32Client.enviarPrompt(_ip.value, "Musica: $cancion")
                actualizarEstado()
                _cargando.value = false
                return@launch
            }

            // 2. Comandos de hardware o IA en el ESP32
            val resultado = Esp32Client.enviarPrompt(_ip.value, t)
            resultado.onSuccess { respuesta ->
                agregarMensaje(respuesta, esUsuario = false)
                tts?.hablar(respuesta)
                actualizarEstado()
            }.onFailure {
                val ipEncontrada = autoDescubrirIp()
                if (ipEncontrada != null) {
                    val reintento = Esp32Client.enviarPrompt(ipEncontrada, t)
                    reintento.onSuccess { resp ->
                        agregarMensaje(resp, esUsuario = false)
                        tts?.hablar(resp)
                        actualizarEstado()
                    }.onFailure {
                        mostrarErrorConexion()
                    }
                } else {
                    mostrarErrorConexion()
                }
            }

            _cargando.value = false
        }
    }

    private fun mostrarErrorConexion() {
        val errorMsg = "No se pudo conectar con el ESP32 en ${_ip.value}. Revisa la pantalla OLED para ver su IP actual."
        agregarMensaje(errorMsg, esUsuario = false)
    }

    fun actualizarEstado() {
        viewModelScope.launch {
            val res = Esp32Client.obtenerEstado(_ip.value)
            res.onSuccess { st ->
                _status.value = st
            }.onFailure {
                _status.value = _status.value.copy(conectado = false)
            }
        }
    }

    private suspend fun autoDescubrirIp(): String? = withContext(Dispatchers.IO) {
        val ipsComunes = listOf(
            "192.168.1.11",
            "192.168.1.9",
            "192.168.1.15",
            "192.168.1.20",
            "10.40.20.155"
        )
        for (candidata in ipsComunes) {
            val res = Esp32Client.obtenerEstado(candidata)
            if (res.isSuccess) {
                _ip.value = candidata
                return@withContext candidata
            }
        }
        null
    }

    private fun iniciarSondeo() {
        viewModelScope.launch {
            while (isActive) {
                actualizarEstado()
                if (!_status.value.conectado) {
                    autoDescubrirIp()
                }
                delay(3500)
            }
        }
    }

    private fun agregarMensaje(txt: String, esUsuario: Boolean) {
        val lista = _mensajes.value.toMutableList()
        lista.add(ChatMessage(texto = txt, esUsuario = esUsuario, fecha = horaActual()))
        _mensajes.value = lista
    }

    private fun horaActual(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    override fun onCleared() {
        super.onCleared()
        tts?.liberar()
        tts = null
    }
}
