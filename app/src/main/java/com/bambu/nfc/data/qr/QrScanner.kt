package com.bambu.nfc.data.qr

import android.content.Intent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw QR payloads decoded by whatever camera pipeline is running. ScanViewModel only sees this
 * flow, so it can be tested with a fake that emits strings, no camera involved.
 * The same label keeps arriving frame after frame; the ViewModel debounces.
 */
interface QrScanner {
    val codes: Flow<String>
}

/** Fed by the camera screen's ML Kit analyzer ([QrAnalyzer]). */
@Singleton
class CameraQrScanner @Inject constructor() : QrScanner {
    private val _codes = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val codes: Flow<String> = _codes.asSharedFlow()

    fun submit(raw: String) {
        _codes.tryEmit(raw)
    }
}

/** The link an ACTION_VIEW intent (App Link from the stock camera, a browser, …) carries, if any. */
object LinkIntents {
    fun linkFrom(action: String?, data: String?): String? =
        data?.takeIf { action == Intent.ACTION_VIEW && it.isNotBlank() }
}
