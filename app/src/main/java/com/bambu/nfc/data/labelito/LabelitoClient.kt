package com.bambu.nfc.data.labelito

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/**
 * Failures the UI explains differently. labelito (chiva/labelito) answers 401 for a bad bearer
 * token and 409 when the loaded roll doesn't match the template's media (or the printer reports a
 * fault). The Brother QL-800 hangs off the gaming PC by USB, so when that PC sleeps labelito is
 * unreachable: a timeout, a refused connection, or a 502/503/504 from the ingress in front of it.
 */
sealed class LabelitoException(message: String) : Exception(message) {
    class NotConfigured : LabelitoException("Set the labelito URL and API token in Settings.")
    class Unauthorized : LabelitoException("labelito rejected the API token (401). Check it in Settings.")
    class WrongRoll(val detail: String?) : LabelitoException(
        "Printer refused the job (409): ${detail ?: "wrong roll loaded"}. " +
            "The spool-label template needs the 62 mm DK-2205 continuous roll."
    )
    class HostUnavailable(val detail: String?) : LabelitoException(
        "labelito is unreachable${detail?.let { " ($it)" } ?: ""}. The gaming PC that hosts the " +
            "printer is probably asleep: wake it and try again."
    )
    class NotOnHomeNetwork(host: String) : LabelitoException(
        "Can't resolve $host. *.lab.apj.dev only resolves on the home Wi-Fi: are you connected to it?"
    )
    class Http(val code: Int, val detail: String?) : LabelitoException("labelito returned HTTP $code${detail?.let { ": $it" } ?: ""}")
}

class LabelitoClient(
    private val okHttp: OkHttpClient,
    private val config: () -> Config,
) {
    data class Config(val baseUrl: String, val token: String)

    companion object {
        const val TEMPLATE = "spool-label"
        private val JSON = "application/json".toMediaType()
    }

    /** Renders the label to PNG without printing. */
    suspend fun preview(fields: Map<String, String>): ByteArray = call("preview", fields) { it.body!!.bytes() }

    /** Prints one label. Returns labelito's JSON response body (job record). */
    suspend fun print(fields: Map<String, String>): String = call("print", fields) { it.body?.string().orEmpty() }

    /** GET /printer/status — loaded media, faults. Used by Settings' "Test" button. */
    suspend fun printerStatus(): String = execute(newRequest("printer/status").get().build()) { it.body?.string().orEmpty() }

    private suspend fun <T> call(path: String, fields: Map<String, String>, read: (Response) -> T): T {
        val body = buildJsonObject {
            put("template", TEMPLATE)
            put("fields", JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
        }.toString()
        return execute(newRequest(path).post(body.toRequestBody(JSON)).build(), read)
    }

    private fun newRequest(path: String): Request.Builder {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.token.isBlank()) throw LabelitoException.NotConfigured()
        val base = cfg.baseUrl.trim().trimEnd('/')
        return Request.Builder().url("$base/$path").header("Authorization", "Bearer ${cfg.token.trim()}")
    }

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        val response = try {
            okHttp.newCall(request).execute()
        } catch (e: UnknownHostException) {
            throw LabelitoException.NotOnHomeNetwork(request.url.host)
        } catch (e: InterruptedIOException) { // includes SocketTimeoutException
            throw LabelitoException.HostUnavailable("timed out")
        } catch (e: ConnectException) {
            throw LabelitoException.HostUnavailable("connection refused")
        } catch (e: NoRouteToHostException) {
            throw LabelitoException.HostUnavailable("no route to host")
        } catch (e: IOException) {
            throw LabelitoException.HostUnavailable(e.message)
        }
        response.use {
            if (it.isSuccessful) return@withContext read(it)
            val detail = errorDetail(it.body?.string())
            throw when (it.code) {
                401, 403 -> LabelitoException.Unauthorized()
                409 -> LabelitoException.WrongRoll(detail)
                502, 503, 504 -> LabelitoException.HostUnavailable(detail ?: "HTTP ${it.code}")
                else -> LabelitoException.Http(it.code, detail)
            }
        }
    }

    /** FastAPI errors are `{"detail": "..."}` (sometimes a nested object); plain text otherwise. */
    internal fun errorDetail(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val parsed = runCatching { Json.parseToJsonElement(body).jsonObject["detail"] }.getOrNull()
        return when {
            parsed is JsonPrimitive -> parsed.content
            parsed != null -> parsed.toString()
            else -> body.take(200)
        }
    }
}
