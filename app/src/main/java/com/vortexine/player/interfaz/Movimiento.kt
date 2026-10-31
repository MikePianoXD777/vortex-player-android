package com.vortexine.player.interfaz

import android.graphics.Color.RGBToHSV
import android.graphics.Color.HSVToColor
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- Movimiento ---------------------------------------------------------------------------------------
// Todo se mueve con resortes y curvas suaves; lo que entra o sale se desenfoca mientras se mueve (desenfoque
// de movimiento). El desenfoque solo existe en Android 12+ y solo mientras dura la animación: en reposo no
// cuesta nada, y en teléfonos más viejos las animaciones salen igual, sin desenfoque.

val SUAVE: FiniteAnimationSpec<Float> = tween(320, easing = FastOutSlowInEasing)
val DESENFOQUE = 18.dp

/** Desenfoca mientras el contenido entra o sale (para AnimatedContent / AnimatedVisibility). */
@Composable
fun AnimatedVisibilityScope.conDesenfoque(): Modifier {
    val radio by transition.animateDp(transitionSpec = { tween(300, easing = FastOutSlowInEasing) }, label = "desenfoque") {
        if (it == EnterExitState.Visible) 0.dp else DESENFOQUE
    }
    return if (radio > 0.5.dp) Modifier.blur(radio, BlurredEdgeTreatment.Unbounded) else Modifier
}

/** Cambiar de pestaña: aparece un poco más grande y se asienta. */
fun entrePestanas(): ContentTransform =
    (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320, easing = FastOutSlowInEasing), 0.96f))
        .togetherWith(fadeOut(tween(140)))

/** Entrar a una playlist (y volver): se desliza de lado. */
fun deLado(adelante: Boolean): ContentTransform {
    val d = if (adelante) 1 else -1
    return (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { d * it / 3 } + fadeIn(tween(260)))
        .togetherWith(slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -d * it / 5 } + fadeOut(tween(200)))
}

/** Cambio de canción en la barra de abajo y en el reproductor: la que se va sale hacia un lado. */
fun cambioDeCancion(): ContentTransform =
    (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) { it / 4 } + fadeIn(tween(220)))
        .togetherWith(slideOutHorizontally(tween(200)) { -it / 4 } + fadeOut(tween(160)))

/** Al tocarlo se hunde un poco y regresa con resorte. */
@Composable
fun Modifier.alPresionar(fuente: MutableInteractionSource): Modifier {
    val presionado by fuente.collectIsPressedAsState()
    val escala by animateFloatAsState(if (presionado) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "presion")
    return graphicsLayer { scaleX = escala; scaleY = escala }
}

/** El botón redondo de tocar, como el verde de Spotify (aquí morado de Obsidian). */
@Composable
fun BotonTocar(tocando: Boolean, tam: Dp = 56.dp, habilitado: Boolean = true, alTocar: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    FilledIconButton(alTocar, Modifier.size(tam).alPresionar(fuente), enabled = habilitado, shape = CircleShape,
        interactionSource = fuente,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = C.morado, contentColor = C.blanco)) {
        AnimatedContent(tocando, transitionSpec = { (scaleIn(spring(0.6f, 500f)) + fadeIn()).togetherWith(scaleOut() + fadeOut()) }, label = "icono") {
            Icon(if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Tocar o pausar", Modifier.size(tam * 0.55f))
        }
    }
}

// --- Portadas -----------------------------------------------------------------------------------------

/** La portada de una playlist: con 4 canciones o más, un mosaico de las primeras 4 (como Spotify). */
@Composable
fun Mosaico(portadas: List<String>, tam: Dp, radio: Dp = 4.dp) {
    if (portadas.size < 4) {
        if (portadas.isEmpty()) Box(Modifier.size(tam).clip(RoundedCornerShape(radio)).background(C.realceFuerte), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = C.tenue, modifier = Modifier.size(tam * 0.45f))
        } else Portada(portadas[0], tam, radio)
        return
    }
    Column(Modifier.size(tam).clip(RoundedCornerShape(radio))) {
        for (fila in 0..1) Row { for (col in 0..1) Portada(portadas[fila * 2 + col], tam / 2, 0.dp) }
    }
}

private val colores = HashMap<String, Color>()

/**
 * El color de una portada (para el degradado de la playlist y del reproductor, como Spotify): el promedio
 * de sus pixeles con más color, oscurecido para que el texto blanco se lea. Se calcula una vez por portada.
 */
@Composable
fun colorDe(url: String, respaldo: Color = C.realceFuerte): State<Color> {
    val ctx = LocalContext.current
    val estado = remember(url) { mutableStateOf(colores[url] ?: respaldo) }
    LaunchedEffect(url) {
        if (url.isEmpty() || url in colores) return@LaunchedEffect
        val c = withContext(Dispatchers.Default) {
            runCatching {
                val r = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url).size(24).allowHardware(false).build())
                (r as? SuccessResult)?.image?.toBitmap()?.let(::dominante)
            }.getOrNull()
        } ?: return@LaunchedEffect
        colores[url] = c
        estado.value = c
    }
    return estado
}

private fun dominante(b: android.graphics.Bitmap): Color {
    var r = 0.0; var g = 0.0; var bl = 0.0; var peso = 0.0
    val hsv = FloatArray(3)
    for (x in 0 until b.width) for (y in 0 until b.height) {
        val p = b.getPixel(x, y)
        val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
        RGBToHSV(pr, pg, pb, hsv)
        val w = 0.05 + hsv[1] * hsv[1] * hsv[2]   // los pixeles con color pesan más que los grises
        r += pr * w; g += pg * w; bl += pb * w; peso += w
    }
    RGBToHSV((r / peso).toInt(), (g / peso).toInt(), (bl / peso).toInt(), hsv)
    hsv[1] = (hsv[1] * 1.2f).coerceAtMost(0.75f)
    hsv[2] = hsv[2].coerceIn(0.28f, 0.45f)
    return Color(HSVToColor(hsv))
}

/** El color de fondo que cambia suave entre canciones. */
@Composable
fun colorAnimado(url: String): Color {
    val c by colorDe(url)
    val animado by animateColorAsState(c, tween(700), label = "color")
    return animado
}

/**
 * La barra de tiempo delgada de Spotify: 4 dp, se engrosa y muestra la bolita al arrastrarla.
 * `alMover` mientras arrastras (0..1) y `alSoltar` al soltar.
 */
@Composable
fun BarraTiempo(fraccion: Float, alMover: (Float) -> Unit, alSoltar: () -> Unit, modifier: Modifier = Modifier) {
    var activa by remember { mutableStateOf(false) }
    val grosor by animateDpAsState(if (activa) 6.dp else 4.dp, label = "grosor")
    val bolita by animateDpAsState(if (activa) 16.dp else 12.dp, label = "bolita")
    var ancho by remember { mutableFloatStateOf(1f) }
    Box(modifier.fillMaxWidth().height(28.dp)
        .onSizeChanged { ancho = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(Unit) {
            detectTapGestures(onPress = { o -> activa = true; alMover((o.x / ancho).coerceIn(0f, 1f)); tryAwaitRelease(); activa = false; alSoltar() })
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { activa = true }, onDragEnd = { activa = false; alSoltar() },
                onDragCancel = { activa = false; alSoltar() }) { cambio, _ -> alMover((cambio.position.x / ancho).coerceIn(0f, 1f)) }
        },
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(grosor).clip(RoundedCornerShape(50)).background(C.blanco.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxWidth(fraccion.coerceIn(0f, 1f)).height(grosor).clip(RoundedCornerShape(50)).background(C.blanco))
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((fraccion.coerceIn(0f, 1f) * ancho - bolita.toPx() / 2).toInt(), 0) }
            .size(bolita).clip(CircleShape).background(C.blanco))
    }
}
