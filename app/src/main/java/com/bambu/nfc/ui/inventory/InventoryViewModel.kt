package com.bambu.nfc.ui.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bambu.nfc.data.local.SpoolCache
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.SpoolmanRepository
import com.bambu.nfc.data.spoolman.spoolmanErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val spoolman: SpoolmanRepository,
    private val cache: SpoolCache,
) : ViewModel() {

    data class State(
        val spools: List<Spool> = emptyList(),
        val loading: Boolean = false,
        /** Set when showing the offline cache because Spoolman couldn't be reached. */
        val offlineSince: Long? = null,
        val error: String? = null,
        val query: String = "",
        val showArchived: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val spools = spoolman.listSpools(includeArchived = true)
                cache.save(spools)
                _state.update { it.copy(spools = spools, loading = false, offlineSince = null) }
            } catch (e: Exception) {
                val (cached, fetchedAt) = runCatching { cache.load() }.getOrDefault(emptyList<Spool>() to null)
                _state.update {
                    it.copy(spools = cached, loading = false, offlineSince = fetchedAt, error = spoolmanErrorMessage(e))
                }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setShowArchived(show: Boolean) = _state.update { it.copy(showArchived = show) }
}
