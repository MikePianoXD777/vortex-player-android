package com.vortexine.player.motor

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** Un solo cliente HTTP para todo: búsquedas, descargas, portadas y NewPipeExtractor. */
object Red {
    const val AGENTE = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val cliente: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POST de JSON a YouTube Music (su API web, la misma que usa su página; sin llave ni cuenta). */
    fun pedirJson(url: String, cuerpo: String): String {
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", AGENTE)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .post(cuerpo.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        cliente.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("YouTube Music contestó ${r.code}")
            return r.body.string()
        }
    }

    fun bajarTexto(url: String): String {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", AGENTE)
            .header("Accept-Language", "es-MX,es;q=0.9").build()
        cliente.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("La página contestó ${r.code}")
            return r.body.string()
        }
    }
}

/** El «descargador» que pide NewPipeExtractor: le presta nuestro cliente. */
class DescargadorNewPipe : Downloader() {
    override fun execute(request: Request): Response {
        val b = okhttp3.Request.Builder().url(request.url()).header("User-Agent", Red.AGENTE)
        val cuerpo = request.dataToSend()?.toRequestBody()
        b.method(request.httpMethod(), cuerpo ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null)
        for ((k, vs) in request.headers()) {
            b.removeHeader(k)
            for (v in vs) b.addHeader(k, v)
        }
        Red.cliente.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube pidió verificar que no eres un robot", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
        }
    }
}
