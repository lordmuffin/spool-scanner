package com.bambu.nfc.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.SpoolmanRepository
import com.bambu.nfc.data.spoolman.spoolmanErrorMessage
import com.bambu.nfc.ui.spool.SpoolPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpoolDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val spoolman: SpoolmanRepository,
) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Loaded(val spool: Spool) : State
        data class Failed(val message: String) : State
    }

    private val spoolId: Long = checkNotNull(savedStateHandle["spoolId"])
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            _state.value = try {
                spoolman.getSpool(spoolId)?.let { State.Loaded(it) } ?: State.Failed("Spool #$spoolId not found in Spoolman.")
            } catch (e: Exception) {
                State.Failed(spoolmanErrorMessage(e))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpoolDetailScreen(viewModel: SpoolDetailViewModel, onBack: () -> Unit, onWriteSticker: (Spool) -> Unit) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spool") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val s = state) {
                SpoolDetailViewModel.State.Loading -> { Spacer(Modifier.height(48.dp)); CircularProgressIndicator() }
                is SpoolDetailViewModel.State.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is SpoolDetailViewModel.State.Loaded -> SpoolPanel(spool = s.spool, onWriteSticker = onWriteSticker)
            }
        }
    }
}
