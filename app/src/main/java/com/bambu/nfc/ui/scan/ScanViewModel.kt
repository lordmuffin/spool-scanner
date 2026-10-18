package com.bambu.nfc.ui.scan

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bambu.nfc.data.nfc.TagRead
import com.bambu.nfc.data.nfc.TagScanner
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.SpoolmanRepository
import com.bambu.nfc.data.spoolman.spoolmanErrorMessage
import com.bambu.nfc.domain.NewSpoolSpec
import com.bambu.nfc.util.toHex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Activity-scoped: MainActivity hands every tapped tag to [onTagDiscovered]. */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanner: TagScanner,
    private val spoolman: SpoolmanRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Ready : UiState
        data class Busy(val message: String) : UiState
        /** The tag belongs to a spool in Spoolman. */
        data class Known(val spool: Spool, val justCreated: Boolean = false) : UiState
        /** Nothing in Spoolman yet: an editable form, prefilled from the Bambu tag when there is one. */
        data class Create(val spec: NewSpoolSpec, val fromBambuTag: Boolean) : UiState
        /** A sticker (or undecodable Bambu tag) not linked to any spool: create or link. */
        data class Unlinked(val read: TagRead, val missingSpoolId: Long? = null) : UiState
        data class PickSpool(val read: TagRead, val spools: List<Spool>) : UiState
        data class Error(val message: String) : UiState
    }

    sealed interface WriteState {
        val spool: Spool
        data class Armed(override val spool: Spool) : WriteState
        data class Writing(override val spool: Spool) : WriteState
        data class Written(override val spool: Spool) : WriteState
        data class Failed(override val spool: Spool, val message: String) : WriteState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Ready)
    val uiState: StateFlow<UiState> = _uiState

    private val _writeState = MutableStateFlow<WriteState?>(null)
    val writeState: StateFlow<WriteState?> = _writeState

    /** Non-blocking startup problem (e.g. Spoolman unreachable while ensuring extra fields). */
    private val _setupWarning = MutableStateFlow<String?>(null)
    val setupWarning: StateFlow<String?> = _setupWarning

    init {
        ensureExtraFields()
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

    fun onTagDiscovered(tag: Tag) {
        when (val w = _writeState.value) {
            is WriteState.Armed, is WriteState.Failed -> { writeSticker(tag, w.spool); return }
            is WriteState.Writing -> return
            else -> Unit
        }
        viewModelScope.launch {
            _uiState.value = UiState.Busy("Reading tag…")
            val read = try {
                scanner.read(tag)
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Couldn't read the tag: ${e.message}")
                return@launch
            }
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
            ?: UiState.Create(NewSpoolSpec.fromBambuTag(read.filament, read.trayUuid), fromBambuTag = true)

        is TagRead.Sticker -> {
            val byUri = read.spoolId?.let { spoolman.getSpool(it) }
            if (byUri != null) {
                UiState.Known(spoolman.learnNfcUid(byUri, read.uid))
            } else {
                spoolman.findByNfcUid(read.uid)?.let { UiState.Known(it) }
                    ?: UiState.Unlinked(read, missingSpoolId = read.spoolId)
            }
        }

        is TagRead.Unreadable ->
            if (!read.mifareClassic) UiState.Error(read.reason)
            else spoolman.findByNfcUid(read.uid)?.let { UiState.Known(it) } ?: UiState.Unlinked(read)
    }

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
            fromBambuTag = false,
        )
    }

    fun createSpool(spec: NewSpoolSpec) {
        viewModelScope.launch {
            _uiState.value = UiState.Busy("Creating spool in Spoolman…")
            _uiState.value = try {
                UiState.Known(spoolman.createSpool(spec), justCreated = true)
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

    // --- NTAG215 sticker writing: arm, then the next tap writes WEB+SPOOLMAN:S-<id> ---

    fun armWrite(spool: Spool) {
        _writeState.value = WriteState.Armed(spool)
    }

    fun cancelWrite() {
        _writeState.value = null
    }

    private fun writeSticker(tag: Tag, spool: Spool) {
        viewModelScope.launch {
            _writeState.value = WriteState.Writing(spool)
            val result = scanner.writeSpoolUri(tag, spool.id)
            _writeState.value = result.fold(
                onSuccess = {
                    // Also record the sticker's UID, so lookup still works if the NDEF gets erased.
                    runCatching { spoolman.linkNfcUid(spool.id, tag.id.toHex()) }
                    WriteState.Written(spool)
                },
                onFailure = { WriteState.Failed(spool, it.message ?: "Write failed") },
            )
        }
    }

    fun dismiss() {
        _uiState.value = UiState.Ready
    }
}
