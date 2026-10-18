package com.bambu.nfc.ui.spool

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.domain.NewSpoolSpec
import com.bambu.nfc.ui.components.ColorSwatch

/**
 * Everything about one spool: remaining grams, location and the Spoolman actions, plus
 * "Print label" (labelito) and "Write NFC sticker" for non-Bambu spools.
 */
@Composable
fun SpoolPanel(
    spool: Spool,
    onWriteSticker: (Spool) -> Unit,
    modifier: Modifier = Modifier,
    justCreated: Boolean = false,
    viewModel: SpoolViewModel = hiltViewModel(key = "spool-${spool.id}"),
) {
    LaunchedEffect(spool) { viewModel.bind(spool) }
    val state by viewModel.state.collectAsState()
    val current = state.spool?.takeIf { it.id == spool.id } ?: spool
    val printState by viewModel.print.collectAsState()

    var dialog by remember { mutableStateOf<ActionDialog?>(null) }
    val isBambu = current.filament.vendor?.name.equals(NewSpoolSpec.BAMBU_VENDOR, ignoreCase = true)

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SpoolSummary(current)

        if (state.busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.error?.let { StatusText(it, isError = true) }
        state.message?.let { StatusText(it, isError = false) }

        if (justCreated) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Created spool #${current.id} in Spoolman", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isBambu) "Print a label for it now?" else "Print a label and write its NFC sticker?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { viewModel.startPrint() }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
            Icon(Icons.Default.Print, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Print label")
        }
        if (!isBambu) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { onWriteSticker(current) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Nfc, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Write NFC sticker")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { dialog = ActionDialog.Measure }, modifier = Modifier.weight(1f), enabled = !state.busy) {
                Icon(Icons.Default.Scale, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Weigh")
            }
            OutlinedButton(onClick = { dialog = ActionDialog.Use }, modifier = Modifier.weight(1f), enabled = !state.busy) {
                Icon(Icons.Default.Remove, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Use")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { dialog = ActionDialog.Location }, modifier = Modifier.weight(1f), enabled = !state.busy) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Location")
            }
            OutlinedButton(
                onClick = { if (current.archived) viewModel.setArchived(false) else dialog = ActionDialog.Archive },
                modifier = Modifier.weight(1f), enabled = !state.busy
            ) {
                Icon(if (current.archived) Icons.Default.Unarchive else Icons.Default.Archive, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(if (current.archived) "Unarchive" else "Archive")
            }
        }
    }

    when (dialog) {
        ActionDialog.Measure -> NumberDialog(
            title = "Weigh spool",
            label = "Gross weight (spool + filament), g",
            help = current.effectiveSpoolWeight?.let { "Spoolman subtracts the empty-spool weight of ${it.fmt()} g." }
                ?: "Spoolman has no empty-spool weight for this spool or filament, so the measurement will be off. Set one in Spoolman, or use \"Use\" instead.",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.measure(it); dialog = null },
        )
        ActionDialog.Use -> NumberDialog(
            title = "Use filament",
            label = "Grams used",
            onDismiss = { dialog = null },
            onConfirm = { viewModel.use(it); dialog = null },
        )
        ActionDialog.Location -> TextDialog(
            title = "Location",
            label = "e.g. Dry box 1, AMS A2, Shelf",
            initial = current.location.orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { viewModel.setLocation(it); dialog = null },
        )
        ActionDialog.Archive -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Archive spool #${current.id}?") },
            text = { Text("Use this when the spool is empty. It stays in Spoolman's history.") },
            confirmButton = { TextButton(onClick = { viewModel.setArchived(true); dialog = null }) { Text("Archive") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }

    PrintLabelDialog(
        state = printState,
        onPrint = viewModel::confirmPrint,
        onRetry = viewModel::startPrint,
        onDismiss = viewModel::dismissPrint,
    )
}

private enum class ActionDialog { Measure, Use, Location, Archive }

@Composable
fun SpoolSummary(spool: Spool) {
    val f = spool.filament
    val color = f.colorHex?.let { runCatching { (0xFF000000 or it.take(6).toLong(16)).toInt() }.getOrNull() }
    if (color != null) ColorSwatch(colorRgba = color, size = 64.dp)
    Spacer(Modifier.height(8.dp))
    Text(
        "#${spool.id}  ${f.name ?: f.material ?: "Filament"}" + if (spool.archived) "  (archived)" else "",
        style = MaterialTheme.typography.headlineSmall
    )
    Text(
        listOfNotNull(f.vendor?.name, f.material, f.colorHex?.let { "#$it" }, "${f.diameter} mm").joinToString("  |  "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Text(
        spool.remainingWeight?.let { "${it.fmt()} g left" } ?: "Remaining weight unknown",
        style = MaterialTheme.typography.displaySmall
    )
    spool.initialWeight?.let { initial ->
        if (initial > 0) {
            LinearProgressIndicator(
                progress = { ((spool.remainingWeight ?: 0.0) / initial).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            Text("of ${initial.fmt()} g", style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Location: ${spool.location?.ifBlank { null } ?: "not set"}",
        style = MaterialTheme.typography.bodyLarge
    )
    val temps = listOfNotNull(
        f.settingsExtruderTemp?.takeIf { it > 0 }?.let { "Nozzle $it°C" },
        f.settingsBedTemp?.takeIf { it > 0 }?.let { "Bed $it°C" },
    )
    if (temps.isNotEmpty()) {
        Text(temps.joinToString("  |  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusText(text: String, isError: Boolean) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
fun NumberDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
    help: String? = null,
) {
    var text by remember { mutableStateOf("") }
    val value = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                help?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null && value > 0) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PrintLabelDialog(
    state: SpoolViewModel.PrintState,
    onPrint: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is SpoolViewModel.PrintState.Idle) return
    val png = when (state) {
        is SpoolViewModel.PrintState.Preview -> state.png
        is SpoolViewModel.PrintState.Printing -> state.png
        is SpoolViewModel.PrintState.Failed -> state.png
        else -> null
    }
    val bitmap = remember(png) { png?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }

    AlertDialog(
        onDismissRequest = { if (state !is SpoolViewModel.PrintState.Printing) onDismiss() },
        title = { Text(if (state is SpoolViewModel.PrintState.Printed) "Label printed" else "Print label") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Label preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Color.White)
                    )
                    Spacer(Modifier.height(12.dp))
                }
                when (state) {
                    SpoolViewModel.PrintState.LoadingPreview -> { CircularProgressIndicator(); Text("Rendering preview…") }
                    is SpoolViewModel.PrintState.Printing -> { CircularProgressIndicator(); Text("Printing…") }
                    SpoolViewModel.PrintState.Printed -> Text("Sent to the Brother QL-800.")
                    is SpoolViewModel.PrintState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is SpoolViewModel.PrintState.Preview -> TextButton(onClick = onPrint) { Text("Print") }
                is SpoolViewModel.PrintState.Failed -> TextButton(onClick = onRetry) { Text("Retry") }
                SpoolViewModel.PrintState.Printed -> TextButton(onClick = onDismiss) { Text("Done") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is SpoolViewModel.PrintState.Printed && state !is SpoolViewModel.PrintState.Printing) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
