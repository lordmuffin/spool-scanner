package com.bambu.nfc.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bambu.nfc.domain.MaterialDefaults
import com.bambu.nfc.domain.NewSpoolSpec
import com.bambu.nfc.ui.components.ColorSwatch

private val HEX = Regex("^#?[0-9a-fA-F]{6}$")

/** Editable, prefilled spec → "Create in Spoolman". Nothing is created until the user confirms. */
@Composable
fun CreateSpoolForm(
    initial: NewSpoolSpec,
    prefilledFrom: String?,
    onCreate: (NewSpoolSpec) -> Unit,
    onCancel: () -> Unit,
) {
    var vendor by remember(initial) { mutableStateOf(initial.vendorName) }
    var name by remember(initial) { mutableStateOf(initial.filamentName) }
    var material by remember(initial) { mutableStateOf(initial.material) }
    var color by remember(initial) { mutableStateOf(initial.colorHex) }
    var colorName by remember(initial) { mutableStateOf(initial.colorName.orEmpty()) }
    var diameter by remember(initial) { mutableStateOf(initial.diameterMm.toString()) }
    var density by remember(initial) { mutableStateOf(initial.densityGcm3.toString()) }
    var weight by remember(initial) { mutableStateOf(initial.netWeightG.toPlain()) }
    var spoolWeight by remember(initial) { mutableStateOf(initial.spoolWeightG?.toPlain().orEmpty()) }
    var nozzle by remember(initial) { mutableStateOf(initial.extruderTempC?.toString().orEmpty()) }
    var bed by remember(initial) { mutableStateOf(initial.bedTempC?.toString().orEmpty()) }
    var location by remember(initial) { mutableStateOf(initial.location.orEmpty()) }

    val diameterV = diameter.num()
    val densityV = density.num()
    val weightV = weight.num()
    val valid = vendor.isNotBlank() && name.isNotBlank() && material.isNotBlank() && HEX.matches(color) &&
        (diameterV ?: 0.0) > 0 && (densityV ?: 0.0) > 0 && (weightV ?: 0.0) > 0

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("New spool", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (prefilledFrom != null) "Not in Spoolman yet. Filled in from $prefilledFrom. Check it, then create."
            else "Not in Spoolman yet. Describe the spool, then create.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Field("Vendor", vendor) { vendor = it }
        Field("Filament name (e.g. PLA Matte)", name) { name = it }
        Field("Material", material) {
            material = it
            density = MaterialDefaults.densityFor(it).toString()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = color, onValueChange = { color = it.removePrefix("#").take(6) },
                label = { Text("Colour hex (RRGGBB)") }, singleLine = true, isError = color.isNotEmpty() && !HEX.matches(color),
                modifier = Modifier.weight(1f)
            )
            if (HEX.matches(color)) ColorSwatch(colorRgba = (0xFF000000 or color.toLong(16)).toInt(), size = 40.dp)
        }
        Field("Colour name (for the label)", colorName) { colorName = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Diameter mm", diameter, Modifier.weight(1f), number = true) { diameter = it }
            Field("Density g/cm³", density, Modifier.weight(1f), number = true) { density = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Net weight g", weight, Modifier.weight(1f), number = true) { weight = it }
            Field("Empty spool g", spoolWeight, Modifier.weight(1f), number = true) { spoolWeight = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Nozzle °C", nozzle, Modifier.weight(1f), number = true) { nozzle = it }
            Field("Bed °C", bed, Modifier.weight(1f), number = true) { bed = it }
        }
        Field("Location", location) { location = it }

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onCreate(
                    initial.copy(
                        vendorName = vendor.trim(),
                        filamentName = name.trim(),
                        material = material.trim(),
                        colorHex = color.removePrefix("#").uppercase(),
                        colorName = colorName.trim().ifEmpty { null },
                        diameterMm = diameterV!!,
                        densityGcm3 = densityV!!,
                        netWeightG = weightV!!,
                        spoolWeightG = spoolWeight.num(),
                        extruderTempC = nozzle.toIntOrNull(),
                        bedTempC = bed.toIntOrNull(),
                        location = location.trim().ifEmpty { null },
                    )
                )
            }
        ) { Text("Create in Spoolman") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default
    )
}

private fun String.num(): Double? = trim().replace(',', '.').toDoubleOrNull()
private fun Double.toPlain(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()
