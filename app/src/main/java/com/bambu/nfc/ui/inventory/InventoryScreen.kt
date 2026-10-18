package com.bambu.nfc.ui.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.ui.components.ColorSwatch
import com.bambu.nfc.ui.scan.matches
import com.bambu.nfc.ui.spool.fmt
import java.text.DateFormat
import java.util.Date

@Composable
fun InventoryScreen(viewModel: InventoryViewModel, onSpoolClick: (Long) -> Unit) {
    val state by viewModel.state.collectAsState()
    // Refresh whenever the tab is shown, so changes made on the detail screen appear.
    LaunchedEffect(Unit) { viewModel.refresh() }
    val shown = state.spools.filter { (state.showArchived || !it.archived) && it.matches(state.query) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::setQuery, label = { Text("Search spools") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.showArchived,
                onClick = { viewModel.setShowArchived(!state.showArchived) },
                label = { Text("Show archived") }
            )
            Text(
                "  ${shown.size} spools, ${shown.sumOf { it.remainingWeight ?: 0.0 }.div(1000).let { "%.1f".format(it) }} kg",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text(
                    err + (state.offlineSince?.let {
                        "\nShowing the offline copy from ${DateFormat.getDateTimeInstance().format(Date(it))}."
                    } ?: ""),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { spool ->
                SpoolRow(spool, enabled = state.offlineSince == null && state.error == null) { onSpoolClick(spool.id) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SpoolRow(spool: Spool, enabled: Boolean, onClick: () -> Unit) {
    val f = spool.filament
    ListItem(
        leadingContent = {
            f.colorHex?.let { hex ->
                runCatching { (0xFF000000 or hex.take(6).toLong(16)).toInt() }.getOrNull()?.let { ColorSwatch(it, size = 40.dp) }
            }
        },
        headlineContent = { Text("#${spool.id}  ${f.name ?: f.material ?: "Filament"}" + if (spool.archived) " (archived)" else "") },
        supportingContent = { Text(listOfNotNull(f.vendor?.name, f.material, spool.location).joinToString("  |  ")) },
        trailingContent = { Text(spool.remainingWeight?.let { "${it.fmt()} g" } ?: "?", style = MaterialTheme.typography.titleMedium) },
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    )
}
