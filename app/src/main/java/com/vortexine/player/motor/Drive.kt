package com.vortexine.player.motor

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Tu Google Drive, solo en la carpeta oculta de Vortex Player (`appDataFolder`): ahí vive
 * `vortex-player.json`. La app no ve ni toca nada más de tu Drive.
 */
class Drive(private val token: String, private val archivo: String = ARCHIVO) {   // 1.5: también «sonando.json»
    class SinPermiso : IOException("Google ya no reconoce la sesión")

    companion object {
        const val ARCHIVO = "vortex-player.json"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val SUBIR = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private fun pedir(r: Request.Builder): String {
        Red.cliente.newCall(r.header("Authorization", "Bearer $token").build()).execute().use { res ->
            val cuerpo = res.body.string()
            if (res.code == 401) throw SinPermiso()
            if (!res.isSuccessful) throw IOException("Google Drive contestó ${res.code}")
            return cuerpo
        }
    }

    /** El id del archivo en Drive, o null si todavía no hay. */
    fun buscar(): String? {
        val q = java.net.URLEncoder.encode("name = '$archivo'", "UTF-8")
        val r = JSONObject(pedir(Request.Builder().url("$API/files?spaces=appDataFolder&q=$q&fields=files(id)&orderBy=modifiedTime+desc")))
        return r.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifEmpty { null }
    }

    fun leer(id: String): String = pedir(Request.Builder().url("$API/files/$id?alt=media"))

    fun crear(texto: String): String {
        val datos = JSONObject().put("name", archivo).put("parents", org.json.JSONArray().put("appDataFolder"))
        val cuerpo = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(datos.toString().toRequestBody(JSON))
            .addPart(texto.toRequestBody(JSON))
            .build()
        return JSONObject(pedir(Request.Builder().url("$SUBIR/files?uploadType=multipart&fields=id").post(cuerpo))).getString("id")
    }

    fun escribir(id: String, texto: String) {
        pedir(Request.Builder().url("$SUBIR/files/$id?uploadType=media").patch(texto.toRequestBody(JSON)))
    }

    /** (nombre, correo) de la cuenta. */
    fun usuario(): Pair<String, String> {
        val u = JSONObject(pedir(Request.Builder().url("$API/about?fields=user(displayName,emailAddress)"))).getJSONObject("user")
        return u.optString("displayName") to u.optString("emailAddress")
    }
}
