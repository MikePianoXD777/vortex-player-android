package com.vortexine.player.interfaz

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Los mismos colores de Vortex Player en la PC (colores.py). */
object C {
    val fondo = Color(0xFF0B0D1A)
    val barra = Color(0xFF11142A)
    val tarjeta = Color(0xFF141833)
    val campo = Color(0xFF161A36)
    val borde = Color(0xFF232850)
    val texto = Color(0xFFE8EBFF)
    val suave = Color(0xFFC3C8EA)
    val tenue = Color(0xFFA3AAD6)
    val azul = Color(0xFF9EC5FF)
    val violeta = Color(0xFFC6A8FF)
    val peligro = Color(0xFFFF9DB0)
    val exito = Color(0xFF8FE3B4)
    val realce = Color(0xFF1A1F45)
    val realceFuerte = Color(0xFF232A5C)

    fun deNivel(nivel: String) = when (nivel) {
        "hires" -> violeta
        "cd" -> azul
        else -> tenue
    }
}

@Composable
fun TemaVortex(contenido: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.azul, onPrimary = C.fondo, secondary = C.violeta, onSecondary = C.fondo,
            background = C.fondo, onBackground = C.texto, surface = C.barra, onSurface = C.texto,
            surfaceVariant = C.tarjeta, onSurfaceVariant = C.suave, outline = C.borde, error = C.peligro,
            surfaceContainer = C.barra, surfaceContainerHigh = C.tarjeta, surfaceContainerHighest = C.realce,
            secondaryContainer = C.realceFuerte, onSecondaryContainer = C.texto,
        ),
        content = contenido,
    )
}
