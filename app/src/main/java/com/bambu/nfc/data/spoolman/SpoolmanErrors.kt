package com.bambu.nfc.data.spoolman

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

/** One-line explanation of a failed Spoolman call for the UI. */
fun spoolmanErrorMessage(e: Throwable): String = when (e) {
    is UnknownHostException ->
        "Can't resolve the Spoolman host. *.lab.apj.dev only resolves on the home Wi-Fi: are you connected to it?"
    is InterruptedIOException -> "Spoolman didn't answer in time."
    is ConnectException -> "Couldn't connect to Spoolman (${e.message})."
    is HttpException -> {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val msg = body?.let { b ->
            runCatching { Json.parseToJsonElement(b).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                ?: b.take(200)
        }
        "Spoolman returned HTTP ${e.code()}${msg?.let { ": $it" } ?: ""}"
    }
    else -> e.message ?: e.javaClass.simpleName
}
