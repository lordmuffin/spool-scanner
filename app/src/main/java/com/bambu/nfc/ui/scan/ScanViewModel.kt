package com.bambu.nfc.ui.scan

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bambu.nfc.data.nfc.SpoolUri
import com.bambu.nfc.data.nfc.SpoolmanCode
import com.bambu.nfc.data.nfc.TagRead
import com.bambu.nfc.data.nfc.TagScanner
import com.bambu.nfc.data.qr.QrScanner
import com.bambu.nfc.data.settings.SpoolLinks
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.SpoolmanRepository
import com.bambu.nfc.data.spoolman.spoolmanErrorMessage
import com.bambu.nfc.domain.NewSpoolSpec
import com.bambu.nfc.domain.SpoolMatcher
import com.bambu.nfc.util.toHex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity-scoped. Three ways in, one lookup: an NFC tap ([onTagDiscovered]), a QR code from the
 * in-app scanner ([QrScanner]) and an App Link / ACTION_VIEW intent ([onLink]). Spool codes from
 * all three resolve through [resolveSpool].
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanner: TagScanner,
    private val spoolman: SpoolmanRepository,
    private val links: SpoolLinks,
    qrScanner: QrScanner,
) : ViewModel() {

    sealed interface UiState {
        data object Ready : UiState
        data class Busy(val message: String) : UiState
        /**
         * The code belongs to a spool in Spoolman. [offerSticker]: opened from a QR code or link and
         * the spool has no NFC tag recorded yet, so offer to write a sticker.
         */
        data class Known(val spool: Spool, val justCreated: Boolean = false, val offerSticker: Boolean = false) : UiState
        /** Nothing in Spoolman yet: an editable form. [prefilledFrom] says where the values came from. */
        data class Create(val spec: NewSpoolSpec, val prefilledFrom: String? = null) : UiState
        /** A sticker (or undecodable Bambu tag) not linked to any spool: create or link. */
        data class Unlinked(val read: TagRead, val missingSpoolId: Long? = null) : UiState
        data class PickSpool(val read: TagRead, val spools: List<Spool>) : UiState
        /** A QR code or link named a spool id that Spoolman doesn't have. */
        data class NotFound(val spoolId: Long) : UiState
        /** A link that isn't a Spoolman spool/filament code; shown raw so it can be copied. */
        data class NotSpoolmanCode(val raw: String) : UiState
        data class Error(val message: String) : UiState
    }

    sealed interface WriteState {
        val spool: Spool
        data class Armed(override val spool: Spool) : WriteState
        data class Writing(override val spool: Spool) : WriteState
        data class Written(override val spool: Spool, val uri: String) : WriteState
        data class Failed(override val spool: Spool, val message: String) : WriteState
    }

    /** The in-app QR camera is open. [notice]: the last code seen that isn't a Spoolman code. */
    data class QrCamera(val notice: String? = null)

    private val _uiState = MutableStateFlow<UiState>(UiState.Ready)
    val uiState: StateFlow<UiState> = _uiState

    private val _writeState = MutableStateFlow<WriteState?>(null)
    val writeState: StateFlow<WriteState?> = _writeState

    private val _qrCamera = MutableStateFlow<QrCamera?>(null)
    val qrCamera: StateFlow<QrCamera?> = _qrCamera

    /** Non-blocking startup problem (e.g. Spoolman unreachable while ensuring extra fields). */
    private val _setupWarning = MutableStateFlow<String?>(null)
    val setupWarning: StateFlow<String?> = _setupWarning

    private val _nfcStatus = MutableStateFlow(NfcStatus.Enabled)
    val nfcStatus: StateFlow<NfcStatus> = _nfcStatus

    /** Fires once per tap that ends in a tag read/write failure (MainActivity vibrates). */
    private val _tagErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val tagErrors: SharedFlow<Unit> = _tagErrors

    /** Fires when the QR camera accepts a code (MainActivity vibrates, like an NFC tap). */
    private val _qrAccepted = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val qrAccepted: SharedFlow<Unit> = _qrAccepted

    /** Fires when a tag, QR code or link starts a lookup, so the UI can switch to the Scan tab. */
    private val _showScan = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val showScan: SharedFlow<Unit> = _showScan

    init {
        ensureExtraFields()
        viewModelScope.launch { qrScanner.codes.collect(::onQrDecoded) }
    }

    fun onNfcStatus(status: NfcStatus) {
        _nfcStatus.value = status
    }

    fun ensureExtraFields() {
        viewModelScope.launch {
            _setupWarning.value = try {
                spoolman.ensureExtraFields()
                null
            } catch (e: Exception) {
                "Spoolman setup check failed: ${spoolmanErrorMessage(e)}"
            }
        }
    }

    // --- NFC ---

    fun onTagDiscovered(tag: Tag) {
        _qrCamera.value = null // a tag tap while the camera is open wins: close it, handle the tag
        when (val w = _writeState.value) {
            is WriteState.Armed, is WriteState.Failed -> { writeSticker(tag, w.spool); return }
            is WriteState.Writing -> return
            else -> Unit
        }
        _showScan.tryEmit(Unit)
        viewModelScope.launch {
            _uiState.value = UiState.Busy("Reading tag…")
            val read = try {
                scanner.read(tag)
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Couldn't read the tag: ${e.message}")
                _tagErrors.tryEmit(Unit)
                return@launch
            }
            if (read is TagRead.Unreadable) _tagErrors.tryEmit(Unit)
            _uiState.value = UiState.Busy("Looking it up in Spoolman…")
            _uiState.value = try {
                lookup(read)
            } catch (e: Exception) {
                UiState.Error(spoolmanErrorMessage(e))
            }
        }
    }

    private suspend fun lookup(read: TagRead): UiState = when (read) {
        is TagRead.Bambu -> spoolman.findBambuSpool(read.trayUuid, read.uid)?.let { UiState.Known(it) }
            ?: UiState.Create(NewSpoolSpec.fromBambuTag(read.filament, read.trayUuid), prefilledFrom = "the Bambu tag")

        // A sticker whose spool was deleted can still be re-linked, so fall back to Unlinked, not NotFound.
        is TagRead.Sticker -> read.spoolId?.let { resolveSpool(it, tagUid = read.uid) }?.let { UiState.Known(it) }
            ?: spoolman.findByNfcUid(read.uid)?.let { UiState.Known(it) }
            ?: UiState.Unlinked(read, missingSpoolId = read.spoolId)

        is TagRead.Unreadable ->
            if (!read.mifareClassic) UiState.Error(read.reason)
            else spoolman.findByNfcUid(read.uid)?.let { UiState.Known(it) } ?: UiState.Unlinked(read)
    }

    /**
     * The one "spool by id" lookup behind stickers, QR codes and links. [tagUid] (NFC only) is
     * recorded on the spool so the sticker is found even if its NDEF record is lost.
     * Returns null when Spoolman has no such spool; throws when Spoolman can't be reached.
     */
    private suspend fun resolveSpool(id: Long, tagUid: String? = null): Spool? {
        val spool = spoolman.getSpool(id) ?: return null
        return if (tagUid != null) spoolman.learnNfcUid(spool, tagUid) else spool
    }

    // --- QR codes and links ---

    /** Opens the spool, shows NotFound, or Spoolman's error. Also the Retry for NotFound. */
    fun openSpool(id: Long) {
        viewModelScope.launch {
            _uiState.value = UiState.Busy("Opening spool #$id…")
            _uiState.value = try {
                resolveSpool(id)?.let { UiState.Known(it, offerSticker = !SpoolMatcher.hasAnyTag(it)) }
                    ?: UiState.NotFound(id)
            } catch (e: Exception) {
                UiState.Error(spoolmanErrorMessage(e))
            }
        }
    }

    /** A filament code: the create form for a new spool of that filament. */
    fun openFilament(id: Long) {
        viewModelScope.launch {
            _uiState.value = UiState.Busy("Loading filament #$id…")
            _uiState.value = try {
                spoolman.getFilament(id)
                    ?.let { UiState.Create(NewSpoolSpec.fromFilament(it), prefilledFrom = "Spoolman filament #$id") }
                    ?: UiState.Error("Filament #$id not found in Spoolman")
            } catch (e: Exception) {
                UiState.Error(spoolmanErrorMessage(e))
            }
        }
    }

    /** An ACTION_VIEW link (stock camera App Link, browser, …). */
    fun onLink(raw: String) {
        _qrCamera.value = null
        _showScan.tryEmit(Unit)
        when (val code = SpoolUri.parseCode(raw)) {
            null -> _uiState.value = UiState.NotSpoolmanCode(raw)
            else -> open(code)
        }
    }

    fun openQrScanner() {
        _qrCamera.value = QrCamera()
    }

    fun closeQrScanner() {
        _qrCamera.value = null
    }

    /**
     * Every decoded frame lands here. The first Spoolman code closes the camera and is handled;
     * frames that arrive after that (the same label is decoded many times a second) are dropped
     * because the camera is no longer open. Other QR codes leave the camera open with a notice.
     */
    internal fun onQrDecoded(raw: String) {
        val camera = _qrCamera.value ?: return
        val code = SpoolUri.parseCode(raw)
        if (code == null) {
            if (camera.notice != raw) _qrCamera.value = camera.copy(notice = raw)
            return
        }
        _qrCamera.value = null
        _qrAccepted.tryEmit(Unit)
        _showScan.tryEmit(Unit)
        open(code)
    }

    private fun open(code: SpoolmanCode) = when (code) {
        is SpoolmanCode.Spool -> openSpool(code.id)
        is SpoolmanCode.Filament -> openFilament(code.id)
    }

    // --- create / link ---

    /** Opens the create form for an unlinked tag (blank for a sticker; Bambu vendor for a MIFARE tag). */
    fun startCreate(read: TagRead) {
        val bambu = read is TagRead.Unreadable && read.mifareClassic
        _uiState.value = UiState.Create(
            NewSpoolSpec(
                vendorName = if (bambu) NewSpoolSpec.BAMBU_VENDOR else "",
                filamentName = "",
                material = "PLA",
                colorHex = "",
                nfcUid = read.uid,
            ),
        )
    }

    fun createSpool(spec: NewSpoolSpec) {
        viewModelScope.launch {
            _uiState.value = UiState.Busy("Creating spool in Spoolman…")
            _uiState.value = try {
                val spool = spoolman.createSpool(spec)
                // Created from a filament QR code: it has no tag yet, so offer a sticker too.
                UiState.Known(spool, justCreated = true, offerSticker = spec.nfcUid == null && spec.bambuTrayUuid == null)
            } catch (e: Exception) {
                UiState.Error("Couldn't create the spool: ${spoolmanErrorMessage(e)}")
            }
        }
    }

    fun startLink(read: TagRead) {
        viewModelScope.launch {
            _uiState.value = UiState.Busy("Loading spools…")
            _uiState.value = try {
                UiState.PickSpool(read, spoolman.listSpools(includeArchived = false))
            } catch (e: Exception) {
                UiState.Error(spoolmanErrorMessage(e))
            }
        }
    }

    /** Records the tag's UID on [spool] so the next tap finds it. */
    fun link(read: TagRead, spool: Spool) {
        viewModelScope.launch {
            _uiState.value = UiState.Busy("Linking tag to spool #${spool.id}…")
            _uiState.value = try {
                UiState.Known(spoolman.linkNfcUid(spool.id, read.uid))
            } catch (e: Exception) {
                UiState.Error(spoolmanErrorMessage(e))
            }
        }
    }

    // --- NTAG215 sticker writing: arm, then the next tap writes the spool's Spoolman link ---

    fun armWrite(spool: Spool) {
        _writeState.value = WriteState.Armed(spool)
    }

    fun cancelWrite() {
        _writeState.value = null
    }

    private fun writeSticker(tag: Tag, spool: Spool) {
        viewModelScope.launch {
            _writeState.value = WriteState.Writing(spool)
            val uri = links.forSpool(spool.id)
            val result = scanner.writeSpoolUri(tag, uri)
            _writeState.value = result.fold(
                onSuccess = {
                    // Also record the sticker's UID, so lookup still works if the NDEF gets erased.
                    runCatching { spoolman.linkNfcUid(spool.id, tag.id.toHex()) }
                    WriteState.Written(spool, uri)
                },
                onFailure = {
                    _tagErrors.tryEmit(Unit)
                    WriteState.Failed(spool, it.message ?: "Write failed")
                },
            )
        }
    }

    fun dismiss() {
        _uiState.value = UiState.Ready
    }
}
