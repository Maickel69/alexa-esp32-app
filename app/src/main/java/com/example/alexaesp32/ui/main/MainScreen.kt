package com.example.alexaesp32.ui.main

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.alexaesp32.data.ChatMessage
import com.example.alexaesp32.data.Esp32Status

// Colores temáticos de Alexa
val FondoOscuro = Color(0xFF0F1117)
val TarjetaOscura = Color(0xFF1A202C)
val BordeGris = Color(0xFF2D3748)
val AzulAlexa = Color(0xFF00CAFF)
val MoradoAlexa = Color(0xFF7928CA)
val VerdeOnline = Color(0xFF48BB78)
val RojoAlerta = Color(0xFFE53E3E)

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val ip by viewModel.ip.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val mensajes by viewModel.mensajes.collectAsStateWithLifecycle()
    val cargando by viewModel.cargando.collectAsStateWithLifecycle()
    val estadoDescargaMusica by viewModel.estadoDescargaMusica.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val progresoDescarga by viewModel.progresoDescarga.collectAsStateWithLifecycle()

    var textoInput by remember { mutableStateOf("") }
    var mostrarDialogoIp by remember { mutableStateOf(false) }
    var ipTemp by remember { mutableStateOf(ip) }

    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.inicializar(context)
    }

    LaunchedEffect(mensajes.size) {
        if (mensajes.isNotEmpty()) {
            listState.animateScrollToItem(mensajes.size - 1)
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                viewModel.enviarMensaje(context, spokenText)
            }
        }
    }

    fun iniciarEscuchaVoz() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora con tu Alexa...")
            }
            speechLauncher.launch(intent)
        } catch (_: Exception) {}
    }

    if (mostrarDialogoIp) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoIp = false },
            title = { Text("Configurar IP del ESP32", color = Color.White) },
            text = {
                Column {
                    Text("Ingresa la dirección IP que aparece en la pantalla OLED de tu ESP32:", color = Color.LightGray, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ipTemp,
                        onValueChange = { ipTemp = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AzulAlexa,
                            unfocusedBorderColor = BordeGris
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.cambiarIp(ipTemp)
                        mostrarDialogoIp = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AzulAlexa)
                ) {
                    Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogoIp = false }) {
                    Text("Cancelar", color = Color.Gray)
                }
            },
            containerColor = TarjetaOscura
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FondoOscuro)
            .padding(14.dp)
    ) {
        // 1. Barra Superior con Estado del ESP32, MicroSD y Auto-DJ
        HeaderBar(
            ip = ip,
            status = status,
            onSettingsClick = {
                ipTemp = ip
                mostrarDialogoIp = true
            }
        )

        Spacer(Modifier.height(10.dp))

        // Banner de Actualización Automática OTA de GitHub
        AnimatedVisibility(visible = updateInfo.disponible) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                colors = CardDefaults.cardColors(containerColor = MoradoAlexa),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.descargarEInstalarActualizacion(context) }
                    ) {
                        Text(
                            text = "🚀 Nueva versión disponible (${updateInfo.version})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (progresoDescarga >= 0) "Descargando: $progresoDescarga%" else "Toca aquí para actualizar en 1 toque",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    if (progresoDescarga >= 0) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.descargarEInstalarActualizacion(context) }) {
                                Text("Actualizar", color = AzulAlexa, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            IconButton(onClick = { viewModel.descartarActualizacion(context) }, modifier = Modifier.size(24.dp)) {
                                Text("✕", color = Color.LightGray, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // Banner de descarga MP3 en segundo plano hacia la MicroSD
        AnimatedVisibility(visible = estadoDescargaMusica.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AzulAlexa, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = estadoDescargaMusica,
                        color = AzulAlexa,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 2. Chips de acciones rápidas
        ActionChipsRow(
            onChipClick = { orden ->
                viewModel.enviarMensaje(context, orden)
            }
        )

        Spacer(Modifier.height(10.dp))

        // 3. Lista de Mensajes del Chat
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(TarjetaOscura)
                .border(1.dp, BordeGris, RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(mensajes, key = { it.id }) { msg ->
                ChatBubble(msg)
            }
        }

        Spacer(Modifier.height(12.dp))

        AnimatedVisibility(cargando && estadoDescargaMusica.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AzulAlexa, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Alexa está procesando...", color = AzulAlexa, fontSize = 12.sp)
            }
        }

        // 4. Barra Inferior con Entrada de Texto y Gran Botón de Micrófono
        BottomInputBar(
            texto = textoInput,
            onTextoChange = { textoInput = it },
            onEnviar = {
                if (textoInput.isNotBlank()) {
                    viewModel.enviarMensaje(context, textoInput)
                    textoInput = ""
                }
            },
            onMicClick = { iniciarEscuchaVoz() },
            cargando = cargando
        )
    }
}

@Composable
fun HeaderBar(
    ip: String,
    status: Esp32Status,
    onSettingsClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TarjetaOscura),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(BordeGris, BordeGris)))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (status.conectado) VerdeOnline else RojoAlerta)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Alexa ESP32",
                        color = AzulAlexa,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (status.sdDisponible) "SD: OK" else "SD: --",
                        color = if (status.sdDisponible) Color(0xFF9AE6B4) else Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(FondoOscuro)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = ip,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(FondoOscuro)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickable { onSettingsClick() }
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onSettingsClick, modifier = Modifier.size(28.dp)) {
                        Text("⚙", fontSize = 16.sp, color = Color.LightGray)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Fila de telemetría de hardware
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val parlanteTexto = if (status.btConectado) "BT: ${status.parlante}" else if (status.parlante.isNotEmpty()) "Buscando: ${status.parlante}" else "BT: sin parlante"
                Text(
                    text = parlanteTexto,
                    color = if (status.btConectado) Color(0xFF9AE6B4) else Color.LightGray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Vol: ${status.volumen}%",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            // Canción actual y siguiente en cola Auto-DJ
            Text(
                text = "🎵 Sonando: " + (if (status.musica.isNotEmpty()) status.musica else "nada"),
                color = if (status.musica.isNotEmpty() && status.musica != "no") AzulAlexa else Color.Gray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (status.cola.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "⏭ Siguiente en SD: ${status.cola}",
                    color = Color(0xFFD6BCFA),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ActionChipsRow(onChipClick: (String) -> Unit) {
    val chips = listOf(
        "Siguiente",
        "Para la musica",
        "Pon musica",
        "Buscar parlantes",
        "Conectar al 1",
        "Mi parlante",
        "Desconectar"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.forEach { chip ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(TarjetaOscura)
                    .border(1.dp, BordeGris, RoundedCornerShape(16.dp))
                    .clickable { onChipClick(chip) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(text = chip, color = Color(0xFFE2E8F0), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    val alineacion = if (msg.esUsuario) Alignment.End else Alignment.Start
    val fondo = if (msg.esUsuario) Color(0xFF2B6CB0) else Color(0xFF2D3748)
    val colorTexto = Color(0xFFE2E8F0)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alineacion
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(fondo)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(text = msg.texto, color = colorTexto, fontSize = 14.sp)
                if (msg.fecha.isNotEmpty()) {
                    Text(
                        text = msg.fecha,
                        color = Color.LightGray.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun BottomInputBar(
    texto: String,
    onTextoChange: (String) -> Unit,
    onEnviar: () -> Unit,
    onMicClick: () -> Unit,
    cargando: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = texto,
            onValueChange = onTextoChange,
            placeholder = { Text("Ej: Reproduce Bad Bunny...", color = Color.Gray, fontSize = 14.sp) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = TarjetaOscura,
                unfocusedContainerColor = TarjetaOscura,
                focusedBorderColor = AzulAlexa,
                unfocusedBorderColor = BordeGris
            )
        )

        IconButton(
            onClick = onEnviar,
            enabled = !cargando && texto.isNotBlank(),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (texto.isNotBlank()) Color(0xFF3182CE) else TarjetaOscura)
        ) {
            Text("➤", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }

        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(AzulAlexa, MoradoAlexa)))
                .clickable { onMicClick() },
            contentAlignment = Alignment.Center
        ) {
            Text("🎙", fontSize = 24.sp)
        }
    }
}
