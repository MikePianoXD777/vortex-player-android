package com.vortexine.player.motor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.content.Context
import android.net.Uri

/**
 * La calidad real de cada canción, como en Vortex Player de la PC: sin pérdida (FLAC, ALAC, WAV…) con más
 * de 16 bits o más de 48 kHz es «Hi-Res»; a 16 bits y 44,1/48 kHz es «CD». Lo demás dice su códec y sus kbps.
 */
object Calidad {
    private val SIN_PERDIDA = setOf("flac", "alac", "wav", "pcm", "aiff", "wavpack", "ape")
    private val NOMBRES = mapOf("opus" to "Opus", "aac" to "AAC", "mp3" to "MP3", "vorbis" to "Vorbis",
        "flac" to "FLAC", "alac" to "ALAC", "pcm" to "WAV", "wav" to "WAV")

    fun esSinPerdida(codec: String) = codec in SIN_PERDIDA

    private fun khz(hz: Int): String {
        val k = hz / 1000.0
        return if (k == Math.floor(k)) k.toInt().toString() else ("%.1f".format(java.util.Locale.US, k)).trimEnd('0').replace('.', ',')
    }

    /** «Hi-Res 24/96», «CD 16/44,1», «Opus 160 kbps»… */
    fun etiqueta(codec: String, bits: Int, hz: Int, kbps: Int): String {
        if (esSinPerdida(codec)) {
            if (bits > 16 || hz > 48000) return if (bits > 0) "Hi-Res $bits/${khz(hz)}" else "Hi-Res ${khz(hz)} kHz"
            return "CD ${if (bits > 0) bits else 16}/${khz(hz)}"
        }
        val nombre = NOMBRES[codec] ?: codec.uppercase().ifEmpty { "?" }
        return if (kbps > 0) "$nombre $kbps kbps" else nombre
    }

    /** «hires», «cd» o «comprimida»: para el color de la etiqueta. */
    fun nivel(codec: String, bits: Int, hz: Int): String = when {
        !esSinPerdida(codec) -> "comprimida"
        bits > 16 || hz > 48000 -> "hires"
        else -> "cd"
    }

    /** El códec corto a partir del tipo MIME de Android. */
    fun codecDeMime(mime: String): String = when (mime.lowercase()) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/alac", "audio/x-alac" -> "alac"
        "audio/raw", "audio/wav", "audio/x-wav" -> "wav"
        "audio/opus" -> "opus"
        "audio/mp4a-latm", "audio/aac", "audio/mp4" -> "aac"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/vorbis", "audio/ogg" -> "vorbis"
        else -> mime.substringAfter('/').removePrefix("x-")
    }

    data class Datos(val codec: String, val bits: Int, val hz: Int, val kbps: Int)

    /** Lee el códec, los bits y la frecuencia del archivo (sin decodificarlo). */
    fun leer(ctx: Context, uri: Uri, bytes: Long, duracionMs: Long): Datos? = try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var datos: Datos? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val codec = codecDeMime(mime)
                val hz = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                var bits = 0
                for (k in listOf("bits-per-sample", "bits-per-raw-sample")) {
                    if (bits == 0 && f.containsKey(k)) bits = runCatching { f.getInteger(k) }.getOrDefault(0)
                }
                if (bits == 0 && android.os.Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    bits = when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                        android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                        else -> 0
                    }
                }
                if (!esSinPerdida(codec)) bits = 0
                var kbps = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 else 0
                if (kbps == 0 && duracionMs > 0 && bytes > 0) kbps = (bytes * 8 / duracionMs).toInt()
                datos = Datos(codec, bits, hz, kbps)
                break
            }
            datos
        } finally {
            ex.release()
        }
    } catch (e: Exception) {
        null
    }
}
