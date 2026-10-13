package com.example.alexaesp32.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.alexaesp32.data.ChatMessage
import com.example.alexaesp32.data.Esp32Client
import com.example.alexaesp32.data.Esp32Status
import com.example.alexaesp32.data.MusicManager
import com.example.alexaesp32.data.TrackRecomendado
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
                texto = "Hola, soy tu Alexa. Pídeme cualquier canción (ej: 'Reproduce Bad Bunny') y la descargaré en segundo plano a tu MicroSD con Auto-DJ estilo YouTube.",
                esUsuario = false,
                fecha = horaActual()
            )
        )
    )
    val mensajes: StateFlow<List<ChatMessage>> = _mensajes.asStateFlow()

    private val _cargando = MutableStateFlow(false)
    val cargando: StateFlow<Boolean> = _cargando.asStateFlow()

    private val _estadoDescargaMusica = MutableStateFlow("")
    val estadoDescargaMusica: StateFlow<String> = _estadoDescargaMusica.asStateFlow()

    // Sistema de Auto-Actualización OTA
    private val _updateInfo = MutableStateFlow(UpdateInfo())
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    private val _progresoDescarga = MutableStateFlow(-1)
    val progresoDescarga: StateFlow<Int> = _progresoDescarga.asStateFlow()

    private var tts: TtsManager? = null

    // Estado del motor Auto-DJ de Recomendaciones
    private val historialCanciones = mutableSetOf<String>()
    private val colaRecomendaciones = ArrayDeque<TrackRecomendado>()
    private var preparandoColaAutoDj = false
    private var autoDjActivo = false

    init {
        iniciarSondeo()
    }

    fun inicializar(context: Context) {
        if (tts == null) {
            tts = TtsManager(context)
        }
        verificarActualizaciones(context)
    }

    fun cambiarIp(nuevaIp: String) {
        val limpia = nuevaIp.trim().removePrefix("http://").removeSuffix("/")
        if (limpia.isNotEmpty()) {
            _ip.value = limpia
            actualizarEstado()
        }
    }

    fun verificarActualizaciones(context: Context) {
        viewModelScope.launch {
            val info = UpdateManager.verificarActualizacion(context)
            _updateInfo.value = info
        }
    }

    fun descartarActualizacion(context: Context) {
        val info = _updateInfo.value
        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        prefs.edit().putString("version_instalada", info.version).apply()
        _updateInfo.value = UpdateInfo(disponible = false)
    }

    fun descargarEInstalarActualizacion(context: Context) {
        val info = _updateInfo.value
        if (!info.disponible || info.urlDescarga.isEmpty()) return

        _updateInfo.value = UpdateInfo(disponible = false)

        viewModelScope.launch {
            _progresoDescarga.value = 0
            val res = UpdateManager.descargarEInstalar(context, info) { p ->
                _progresoDescarga.value = p
            }
            res.onFailure {
                _progresoDescarga.value = -1
                agregarMensaje("Actualización: ${it.localizedMessage}", esUsuario = false)
            }
        }
    }

    fun enviarMensaje(context: Context, texto: String) {
        val t = texto.trim()
        if (t.isEmpty() || _cargando.value) return

        agregarMensaje(t, esUsuario = true)

        // Si el usuario pide parar la música, desactivar el Auto-DJ
        val tl = t.lowercase()
        if (tl.contains("para la musica") || tl.contains("parar musica") || tl == "silencio" || tl == "callate") {
            autoDjActivo = false
            colaRecomendaciones.clear()
        }

        // Si el usuario pide "Siguiente canción"
        if (MusicManager.esComandoSiguiente(t)) {
            reproducirSiguienteRecomendacion()
            return
        }

        // Detectar si el usuario pide reproducir una canción específica
        val cancion = MusicManager.extraerNombreCancion(t)
        if (cancion != null) {
            reproducirODescargarCancion(cancion)
            return
        }

        _cargando.value = true
        viewModelScope.launch {
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

    private fun reproducirODescargarCancion(cancionQuery: String) {
        _cargando.value = true
        autoDjActivo = true

        viewModelScope.launch {
            // 1. Verificar primero si ya existe en la MicroSD del ESP32
            _estadoDescargaMusica.value = "🔎 Buscando '$cancionQuery' en la MicroSD..."
            val enSd = Esp32Client.reproducirDesdeSD(_ip.value, cancionQuery, enCola = false)

            if (enSd.isSuccess) {
                val resp = enSd.getOrNull() ?: "Reproduciendo $cancionQuery desde MicroSD."
                _estadoDescargaMusica.value = ""
                _cargando.value = false
                historialCanciones.add(cancionQuery.lowercase())
                agregarMensaje("🎵 $resp", esUsuario = false)
                tts?.hablar(resp)
                actualizarEstado()

                // Generar recomendaciones estilo YouTube en segundo plano y pre-cargar la siguiente
                val nuevasRecs = MusicManager.obtenerRecomendacionesPorBusqueda(cancionQuery, historialCanciones)
                if (nuevasRecs.isNotEmpty()) {
                    colaRecomendaciones.clear()
                    colaRecomendaciones.addAll(nuevasRecs)
                    prepararSiguienteEnColaSD()
                }
                return@launch
            }

            // 2. No está en la MicroSD -> Descargar el MP3 en segundo plano y subirlo a la MicroSD del ESP32
            _estadoDescargaMusica.value = "⬇ Descargando '$cancionQuery' en segundo plano..."
            val descargaRes = MusicManager.buscarYDescargarMp3(cancionQuery, historialCanciones)

            descargaRes.onSuccess { track ->
                historialCanciones.add(track.tituloCompleto.lowercase())
                colaRecomendaciones.clear()
                colaRecomendaciones.addAll(track.recomendaciones)

                val kb = track.bytesMp3.size / 1024
                _estadoDescargaMusica.value = "💾 Guardando '${track.tituloCompleto}' (${kb} KB) en MicroSD..."

                val subida = Esp32Client.subirMp3ASD(
                    ip = _ip.value,
                    titulo = track.tituloCompleto,
                    bytesMp3 = track.bytesMp3,
                    enCola = false
                )

                _estadoDescargaMusica.value = ""
                _cargando.value = false

                subida.onSuccess { msgEsp ->
                    val textoExito = "🎵 Reproduciendo en tu parlante desde MicroSD: ${track.tituloCompleto}"
                    agregarMensaje(textoExito, esUsuario = false)
                    actualizarEstado()
                    // Mientras suena esta canción, ir descargando la siguiente recomendación a la MicroSD
                    prepararSiguienteEnColaSD()
                }.onFailure { err ->
                    agregarMensaje(
                        "Descargué '${track.tituloCompleto}', pero el ESP32 no pudo guardarlo en la MicroSD (${err.localizedMessage}). Verifica haber subido el nuevo código al ESP32 y que la MicroSD esté bien insertada (MISO=23, MOSI=5, CS=19, SCK=18).",
                        esUsuario = false
                    )
                }
            }.onFailure { err ->
                _estadoDescargaMusica.value = ""
                _cargando.value = false
                agregarMensaje("No pude descargar '$cancionQuery': ${err.localizedMessage}", esUsuario = false)
            }
        }
    }

    private fun reproducirSiguienteRecomendacion() {
        if (colaRecomendaciones.isEmpty()) {
            // Si no hay cola en la app, pedirle al ESP32 "siguiente" de la MicroSD
            viewModelScope.launch {
                val res = Esp32Client.enviarPrompt(_ip.value, "siguiente cancion")
                res.onSuccess {
                    agregarMensaje("⏭ $it", esUsuario = false)
                    actualizarEstado()
                }
            }
            return
        }

        val siguiente = colaRecomendaciones.removeFirst()
        reproducirODescargarCancion(siguiente.tituloCompleto)
    }

    private fun prepararSiguienteEnColaSD() {
        if (!autoDjActivo || preparandoColaAutoDj || colaRecomendaciones.isEmpty()) return
        preparandoColaAutoDj = true

        viewModelScope.launch {
            try {
                // Pequeña pausa para que el inicio de reproducción por Bluetooth A2DP sea 100% fluido
                delay(2500)
                if (!autoDjActivo || colaRecomendaciones.isEmpty()) {
                    preparandoColaAutoDj = false
                    return@launch
                }

                val sigTrack = colaRecomendaciones.removeFirst()
                historialCanciones.add(sigTrack.tituloCompleto.lowercase())

                // 1. ¿Ya está en la MicroSD del ESP32?
                val yaEnSd = Esp32Client.reproducirDesdeSD(_ip.value, sigTrack.tituloCompleto, enCola = true)
                if (yaEnSd.isSuccess) {
                    actualizarEstado()
                    preparandoColaAutoDj = false
                    return@launch
                }

                // 2. Descargar la recomendación en segundo plano y dejarla lista en la MicroSD (cola=1)
                _estadoDescargaMusica.value = "🎧 Auto-DJ pre-descargando: ${sigTrack.tituloCompleto}..."
                val bytesRes = MusicManager.descargarMp3DeRecomendacion(sigTrack)

                bytesRes.onSuccess { bytes ->
                    _estadoDescargaMusica.value = "🎧 Auto-DJ guardando en SD: ${sigTrack.tituloCompleto}..."
                    Esp32Client.subirMp3ASD(
                        ip = _ip.value,
                        titulo = sigTrack.tituloCompleto,
                        bytesMp3 = bytes,
                        enCola = true
                    )
                    actualizarEstado()
                }

                // Si quedan pocas recomendaciones en la lista, buscar más automáticamente (radio infinita)
                if (colaRecomendaciones.size <= 2) {
                    val masRecs = MusicManager.obtenerRecomendacionesPorBusqueda(
                        sigTrack.artistaNombre.ifEmpty { sigTrack.tituloCompleto },
                        historialCanciones
                    )
                    for (r in masRecs) {
                        if (colaRecomendaciones.none { it.tituloCompleto.equals(r.tituloCompleto, ignoreCase = true) }) {
                            colaRecomendaciones.addLast(r)
                        }
                    }
                }
            } finally {
                _estadoDescargaMusica.value = ""
                preparandoColaAutoDj = false
            }
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
                // Si el ESP32 está reproduciendo música, el Auto-DJ está activo y la cola del ESP32 quedó vacía
                // (porque ya pasó a la siguiente canción), pre-descargar automáticamente la próxima recomendación:
                if (autoDjActivo &&
                    st.musica.isNotEmpty() &&
                    st.musica != "no" &&
                    st.cola.isEmpty() &&
                    !preparandoColaAutoDj &&
                    colaRecomendaciones.isNotEmpty()
                ) {
                    prepararSiguienteEnColaSD()
                }
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
