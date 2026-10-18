package com.bambu.nfc.ui.spool

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bambu.nfc.data.labelito.LabelitoClient
import com.bambu.nfc.data.labelito.SpoolLabel
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.SpoolmanRepository
import com.bambu.nfc.data.spoolman.spoolmanErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Actions on one Spoolman spool plus the label-print flow. Shared by the scan sheet and detail screen. */
@HiltViewModel
class SpoolViewModel @Inject constructor(
    private val spoolman: SpoolmanRepository,
    private val labelito: LabelitoClient,
) : ViewModel() {

    data class State(
        val spool: Spool? = null,
        val busy: Boolean = false,
        val message: String? = null,
        val error: String? = null,
    )

    sealed interface PrintState {
        data object Idle : PrintState
        data object LoadingPreview : PrintState
        data class Preview(val png: ByteArray, val fields: Map<String, String>) : PrintState
        data class Printing(val png: ByteArray) : PrintState
        data object Printed : PrintState
        data class Failed(val message: String, val png: ByteArray? = null) : PrintState
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _print = MutableStateFlow<PrintState>(PrintState.Idle)
    val print: StateFlow<PrintState> = _print

    /** Shows [spool] (called with each fresh lookup result, so a re-scan shows current data). */
    fun bind(spool: Spool) {
        if (_state.value.spool?.id != spool.id) _state.value = State(spool = spool)
        else _state.update { it.copy(spool = spool) }
    }

    fun refresh() = run("Refreshed") { spoolman.getSpool(it.id) ?: error("Spool #${it.id} no longer exists in Spoolman") }

    fun measure(grossWeightG: Double) = run("Weight recorded") { spoolman.measure(it.id, grossWeightG) }

    fun use(grams: Double) = run("Used ${grams.fmt()} g") { spoolman.use(it.id, grams) }

    fun setLocation(location: String) = run("Location set") { spoolman.setLocation(it.id, location) }

    fun setArchived(archived: Boolean) = run(if (archived) "Archived" else "Unarchived") { spoolman.setArchived(it.id, archived) }

    fun clearMessages() = _state.update { it.copy(message = null, error = null) }

    private fun run(success: String, block: suspend (Spool) -> Spool) {
        val spool = _state.value.spool ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                val updated = block(spool)
                _state.update { it.copy(spool = updated, busy = false, message = success) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = spoolmanErrorMessage(e)) }
            }
        }
    }

    // --- label printing: POST /preview → show PNG → POST /print ---

    fun startPrint() {
        val spool = _state.value.spool ?: return
        val fields = SpoolLabel.fields(spool)
        _print.value = PrintState.LoadingPreview
        viewModelScope.launch {
            _print.value = try {
                PrintState.Preview(labelito.preview(fields), fields)
            } catch (e: Exception) {
                PrintState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun confirmPrint() {
        val preview = _print.value as? PrintState.Preview ?: return
        _print.value = PrintState.Printing(preview.png)
        viewModelScope.launch {
            _print.value = try {
                labelito.print(preview.fields)
                PrintState.Printed
            } catch (e: Exception) {
                PrintState.Failed(e.message ?: e.javaClass.simpleName, preview.png)
            }
        }
    }

    fun dismissPrint() {
        _print.value = PrintState.Idle
    }
}

internal fun Double.fmt(): String = if (this % 1.0 == 0.0) "%.0f".format(this) else "%.1f".format(this)
