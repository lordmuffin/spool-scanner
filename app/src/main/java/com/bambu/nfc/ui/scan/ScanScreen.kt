package com.bambu.nfc.ui.scan

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bambu.nfc.data.nfc.TagRead
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.ui.spool.SpoolPanel
import com.bambu.nfc.ui.spool.fmt

@Composable
fun ScanScreen(viewModel: ScanViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val warning by viewModel.setupWarning.collectAsState()
    val nfcStatus by viewModel.nfcStatus.collectAsState()
    val centered = uiState is ScanViewModel.UiState.Ready || uiState is ScanViewModel.UiState.Busy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top
    ) {
        nfcStatus.banner()?.let { NfcBannerCard(it) }
        when (val state = uiState) {
            ScanViewModel.UiState.Ready -> {
                if (centered) Spacer(Modifier.height(96.dp))
                ScanPrompt()
                Spacer(Modifier.height(24.dp))
                ScanQrButton(viewModel::openQrScanner)
                warning?.let {
                    Spacer(Modifier.height(24.dp))
                    ErrorCard(it)
                    TextButton(onClick = viewModel::ensureExtraFields) { Text("Retry") }
                }
            }
            is ScanViewModel.UiState.Busy -> {
                Spacer(Modifier.height(96.dp))
                CircularProgressIndicator(Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text(state.message, style = MaterialTheme.typography.titleLarge)
            }
            is ScanViewModel.UiState.Known -> {
                SpoolPanel(
                    spool = state.spool,
                    justCreated = state.justCreated,
                    offerSticker = state.offerSticker,
                    onWriteSticker = viewModel::armWrite,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = viewModel::dismiss) { Text("Done") }
            }
            is ScanViewModel.UiState.Create -> CreateSpoolForm(
                initial = state.spec,
                prefilledFrom = state.prefilledFrom,
                onCreate = viewModel::createSpool,
                onCancel = viewModel::dismiss,
            )
            is ScanViewModel.UiState.Unlinked -> Unlinked(state, viewModel)
            is ScanViewModel.UiState.PickSpool -> SpoolPicker(
                spools = state.spools,
                onPick = { viewModel.link(state.read, it) },
                onCancel = viewModel::dismiss,
            )
            is ScanViewModel.UiState.NotFound -> {
                Spacer(Modifier.height(48.dp))
                ScanPrompt()
                Spacer(Modifier.height(24.dp))
                ErrorCard("Spool #${state.spoolId} not found in Spoolman")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.openSpool(state.spoolId) }) { Text("Retry") }
                    TextButton(onClick = viewModel::dismiss) { Text("OK") }
                }
                ScanQrButton(viewModel::openQrScanner)
            }
            is ScanViewModel.UiState.NotSpoolmanCode -> {
                Spacer(Modifier.height(48.dp))
                NotSpoolmanCode(state.raw)
                TextButton(onClick = viewModel::dismiss) { Text("OK") }
            }
            is ScanViewModel.UiState.Error -> {
                Spacer(Modifier.height(48.dp))
                ScanPrompt()
                Spacer(Modifier.height(24.dp))
                ErrorCard(state.message)
                TextButton(onClick = viewModel::dismiss) { Text("OK") }
                ScanQrButton(viewModel::openQrScanner)
            }
        }
    }
}

@Composable
private fun Unlinked(state: ScanViewModel.UiState.Unlinked, viewModel: ScanViewModel) {
    val read = state.read
    Icon(Icons.Default.Nfc, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text(
        when {
            state.missingSpoolId != null -> "Sticker points at spool #${state.missingSpoolId}, which isn't in Spoolman"
            read is TagRead.Unreadable -> "Bambu tag this phone can't decode"
            else -> "Sticker isn't linked to a spool"
        },
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (read is TagRead.Unreadable) read.reason
        else "Create a new spool for it, or link it to a spool that's already in Spoolman.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text("Tag UID ${read.uid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Button(onClick = { viewModel.startCreate(read) }, modifier = Modifier.fillMaxWidth()) { Text("New spool…") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { viewModel.startLink(read) }, modifier = Modifier.fillMaxWidth()) { Text("Link to existing spool…") }
    TextButton(onClick = viewModel::dismiss) { Text("Cancel") }
}

@Composable
fun SpoolPicker(spools: List<Spool>, onPick: (Spool) -> Unit, onCancel: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Text("Pick the spool", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    val shown = spools.filter { it.matches(query) }
    if (shown.isEmpty()) Text("No spools match.", modifier = Modifier.padding(16.dp))
    shown.forEach { spool ->
        ListItem(
            headlineContent = { Text("#${spool.id}  ${spool.filament.name ?: spool.filament.material ?: ""}") },
            supportingContent = {
                Text(listOfNotNull(spool.filament.vendor?.name, spool.filament.colorHex?.let { "#$it" },
                    spool.remainingWeight?.let { "${it.fmt()} g" }, spool.location).joinToString("  |  "))
            },
            modifier = Modifier.clickable { onPick(spool) }
        )
        HorizontalDivider()
    }
    TextButton(onClick = onCancel) { Text("Cancel") }
}

fun Spool.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val haystack = listOfNotNull(
        "#$id", filament.name, filament.material, filament.vendor?.name, filament.colorHex, location
    ).joinToString(" ")
    return query.trim().split(Regex("\\s+")).all { haystack.contains(it, ignoreCase = true) }
}

@Composable
private fun ScanQrButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Default.QrCodeScanner, null, Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text("Scan QR label")
    }
}

/** A QR code or link that isn't a Spoolman code: show it raw, with Copy. No lookup happens. */
@Composable
fun NotSpoolmanCode(raw: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Not a Spoolman code", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(raw, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 6)
            TextButton(onClick = { clipboard.setText(AnnotatedString(raw)) }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Copy")
            }
        }
    }
}

@Composable
private fun NfcBannerCard(banner: NfcBanner) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(banner.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(banner.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (banner.showSettingsButton) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openNfcSettings(context) }) { Text("Open NFC settings") }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** NFC settings screen, or the wireless settings screen on devices without a dedicated one. */
private fun openNfcSettings(context: Context) {
    val nfc = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(nfc)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ScanPrompt() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "pulse"
    )
    Icon(
        imageVector = Icons.Default.Nfc,
        contentDescription = "NFC",
        modifier = Modifier.size(120.dp).alpha(alpha),
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(24.dp))
    Text("Hold phone to spool", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "Tap a Bambu Lab spool's RFID tag or a spool's NFC sticker",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
