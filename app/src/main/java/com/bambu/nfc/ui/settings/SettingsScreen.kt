package com.bambu.nfc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bambu.nfc.BuildConfig
import com.bambu.nfc.data.labelito.LabelitoClient
import com.bambu.nfc.data.settings.AppSettings
import com.bambu.nfc.data.settings.SettingsStore
import com.bambu.nfc.data.spoolman.SpoolmanRepository
import com.bambu.nfc.data.spoolman.spoolmanErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val spoolman: SpoolmanRepository,
    private val labelito: LabelitoClient,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings

    private val _spoolmanResult = MutableStateFlow<String?>(null)
    val spoolmanResult: StateFlow<String?> = _spoolmanResult
    private val _labelitoResult = MutableStateFlow<String?>(null)
    val labelitoResult: StateFlow<String?> = _labelitoResult

    fun save(settings: AppSettings) = store.save(settings)

    /** GET /api/v1/info, then make sure the app's extra fields exist on that server. */
    fun testSpoolman() {
        _spoolmanResult.value = "Testing…"
        viewModelScope.launch {
            _spoolmanResult.value = try {
                val version = spoolman.info()["version"]?.jsonPrimitive?.content ?: "?"
                val created = spoolman.ensureExtraFields()
                "OK: Spoolman $version" + if (created.isNotEmpty()) ". Created extra fields: ${created.joinToString()}" else ". Extra fields present."
            } catch (e: Exception) {
                "Failed: ${spoolmanErrorMessage(e)}"
            }
        }
    }

    fun testLabelito() {
        _labelitoResult.value = "Testing…"
        viewModelScope.launch {
            _labelitoResult.value = try {
                "OK: ${labelito.printerStatus().take(300)}"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
        }
    }
}

/** Whether label QR links (scanned with the stock camera) open here, and a way to approve them. */
@Composable
private fun SpoolmanLinksSection() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(AppLinkStatus.read(context)) }
    // Re-read after returning from the system "Open by default" page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = AppLinkStatus.read(context) }

    Text("Spoolman label links", style = MaterialTheme.typography.titleMedium)
    Text(
        "Lets the phone's camera app open a spool label's QR code straight in Spool Scanner.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    status.forEach { (host, state) ->
        Text("$host: ${state.label}", style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedButton(onClick = { AppLinkStatus.openSettings(context) }) { Text("Open Spoolman links in this app") }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val saved by viewModel.settings.collectAsState()
    val spoolmanResult by viewModel.spoolmanResult.collectAsState()
    val labelitoResult by viewModel.labelitoResult.collectAsState()

    var spoolmanUrl by remember(saved) { mutableStateOf(saved.spoolmanUrl) }
    var labelitoUrl by remember(saved) { mutableStateOf(saved.labelitoUrl) }
    var token by remember(saved) { mutableStateOf(saved.labelitoToken) }
    var showToken by remember { mutableStateOf(false) }
    val dirty = spoolmanUrl != saved.spoolmanUrl || labelitoUrl != saved.labelitoUrl || token != saved.labelitoToken

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Both services live on *.lab.apj.dev, which only resolves on the home Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        Text("Spoolman", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = spoolmanUrl, onValueChange = { spoolmanUrl = it }, label = { Text("Base URL") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = viewModel::testSpoolman, enabled = !dirty) { Text("Test connection") }
        spoolmanResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("labelito (Brother QL-800)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = labelitoUrl, onValueChange = { labelitoUrl = it }, label = { Text("Base URL") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, label = { Text("API token (stored encrypted)") }, singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show token")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = viewModel::testLabelito, enabled = !dirty) { Text("Test printer status") }
        labelitoResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        SpoolmanLinksSection()

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.save(AppSettings(spoolmanUrl, labelitoUrl, token)) }, enabled = dirty) { Text("Save") }
            OutlinedButton(
                onClick = {
                    spoolmanUrl = AppSettings.DEFAULT_SPOOLMAN_URL
                    labelitoUrl = AppSettings.DEFAULT_LABELITO_URL
                }
            ) { Text("Reset URLs") }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Spool Scanner ${BuildConfig.VERSION_NAME}. Fork of GraafG/bambu-spool-manager (MIT).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
