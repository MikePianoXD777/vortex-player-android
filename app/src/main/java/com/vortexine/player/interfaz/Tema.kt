package com.vortexine.player.interfaz

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 1.2: la paleta de Obsidian (su tema oscuro de serie, el que Mike usa en la PC), acomodada como Spotify:
 * fondo casi negro, tarjetas gris oscuro y el morado de Obsidian donde Spotify pone su verde.
 * Solo en el cel: Vortex Player de la PC sigue tomando los colores del fondo de pantalla.
 */
object C {
    val fondo = Color(0xFF1C1C1C)        // --color-base-00
    val barra = Color(0xFF161616)        // la barra de abajo, un poco más oscura (como Spotify)
    val tarjeta = Color(0xFF282828)      // --color-base-20
    val campo = Color(0xFF2E2E2E)        // --color-base-25
    val borde = Color(0xFF3F3F3F)        // --color-base-35
    val texto = Color(0xFFDADADA)        // --color-base-100
    val blanco = Color(0xFFF5F5F5)
    val suave = Color(0xFFB3B3B3)        // --color-base-70
    val tenue = Color(0xFF999999)        // --color-base-60
    val acento = Color(0xFFA68AF9)       // --interactive-accent (texto e iconos)
    val morado = Color(0xFF8A5CF5)       // --color-accent (el botón grande de tocar)
    val lila = Color(0xFFA882FF)         // --color-purple
    val cian = Color(0xFF53DFDD)         // --color-cyan
    val peligro = Color(0xFFFB464C)      // --color-red
    val exito = Color(0xFF44CF6E)        // --color-green
    val realce = Color(0xFF2A2A2A)
    val realceFuerte = Color(0xFF363636) // --color-base-30

    fun deNivel(nivel: String) = when (nivel) {
        "hires" -> lila
        "cd" -> cian
        else -> tenue
    }
}

@Composable
fun TemaVortex(contenido: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.acento, onPrimary = C.fondo, secondary = C.morado, onSecondary = C.blanco,
            background = C.fondo, onBackground = C.texto, surface = C.fondo, onSurface = C.texto,
            surfaceVariant = C.tarjeta, onSurfaceVariant = C.suave, outline = C.borde, error = C.peligro,
            surfaceContainer = C.tarjeta, surfaceContainerHigh = C.tarjeta, surfaceContainerHighest = C.campo,
            surfaceContainerLow = C.fondo, secondaryContainer = C.realceFuerte, onSecondaryContainer = C.texto,
        ),
        content = contenido,
    )
}
