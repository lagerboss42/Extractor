package com.majk.extractor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.majk.extractor.data.db.FixedSiteStore
import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType

@Composable
fun ReviewScreen(
    stops: List<Shipment>,
    onChange: (List<Shipment>) -> Unit,
    onWrite: () -> Unit,
    onCancel: () -> Unit
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Review", style = MaterialTheme.typography.headlineMedium)
        Text("${stops.size} stops · edit or assign before writing", style = MaterialTheme.typography.bodySmall)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(stops, key = { i, s -> "$i|${s.fixedSiteName}|${s.address}|${s.recipient}" }) { index, stop ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            listOf(stop.address, stop.zip, stop.city).filter { it.isNotBlank() }.joinToString(", ")
                                .ifBlank { "(no address)" },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            buildString {
                                append(stop.fixedSiteName ?: stop.recipient.ifBlank { "(no name)" })
                                append(" · ")
                                append(if (stop.type == ShipmentType.PICK) "pickup" else "delivery")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${stop.colliCount} kli · ${stop.weightKg} kg",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { editing = index }) { Text("Edit") }
                    }
                }
            }
        }
        Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) { Text("Write Excel") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }

    val idx = editing
    if (idx != null && idx in stops.indices) {
        EditStopDialog(
            stop = stops[idx],
            onDismiss = { editing = null },
            onSave = { updated, siteName ->
                onChange(applyEdit(stops, idx, updated, siteName))
                editing = null
            }
        )
    }
}

private fun applyEdit(
    stops: List<Shipment>,
    index: Int,
    edited: Shipment,
    siteName: String?
): List<Shipment> {
    val site = siteName?.let { name -> FixedSiteStore.all().firstOrNull { it.name == name } }
    val row = if (site == null) {
        edited.copy(fixedSiteName = null)
    } else {
        edited.copy(
            recipient = site.name,
            address = site.address.ifBlank { edited.address },
            zip = site.zip.ifBlank { edited.zip },
            city = site.city.ifBlank { edited.city },
            fixedSiteName = site.name
        )
    }
    val next = stops.toMutableList()
    next.removeAt(index)
    if (site != null) {
        val existing = next.indexOfFirst { it.fixedSiteName == site.name && it.type == row.type }
        if (existing >= 0) {
            val base = next[existing]
            next[existing] = base.copy(
                colliCount = base.colliCount + row.colliCount,
                weightKg = base.weightKg + row.weightKg,
                portkod = base.portkod ?: row.portkod,
                comment = listOfNotNull(base.comment, row.comment)
                    .filter { it.isNotBlank() }.distinct().joinToString(" | ").ifBlank { null }
            )
            return next
        }
    }
    next.add(index.coerceAtMost(next.size), row)
    return next
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditStopDialog(
    stop: Shipment,
    onDismiss: () -> Unit,
    onSave: (Shipment, String?) -> Unit
) {
    var recipient by remember { mutableStateOf(stop.recipient) }
    var address by remember { mutableStateOf(stop.address) }
    var zip by remember { mutableStateOf(stop.zip) }
    var city by remember { mutableStateOf(stop.city) }
    var info by remember { mutableStateOf(stop.comment.orEmpty()) }
    var colli by remember { mutableStateOf(stop.colliCount.toString()) }
    var weight by remember { mutableStateOf(stop.weightKg.toString()) }
    var type by remember { mutableStateOf(stop.type) }
    var site by remember { mutableStateOf(stop.fixedSiteName ?: "None") }
    var siteOpen by remember { mutableStateOf(false) }
    val sites = listOf("None") + FixedSiteStore.all().map { it.name }.distinct()
    val colliInt = colli.toIntOrNull()
    val weightInt = weight.toIntOrNull()
    val canSave = colliInt != null && colliInt >= 0 && weightInt != null && weightInt >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit stop") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = recipient, onValueChange = { recipient = it }, label = { Text("Recipient") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zip, onValueChange = { zip = it }, label = { Text("Zip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = colli, onValueChange = { colli = it }, label = { Text("Colli") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text("Weight kg") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = info, onValueChange = { info = it }, label = { Text("Info") }, modifier = Modifier.fillMaxWidth())
                Text("Type", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == ShipmentType.DEL, onClick = { type = ShipmentType.DEL }, label = { Text("Delivery") })
                    FilterChip(selected = type == ShipmentType.PICK, onClick = { type = ShipmentType.PICK }, label = { Text("Pickup") })
                }
                ExposedDropdownMenuBox(expanded = siteOpen, onExpandedChange = { siteOpen = it }) {
                    OutlinedTextField(
                        value = site,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Assign to fixed site") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = siteOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = siteOpen, onDismissRequest = { siteOpen = false }) {
                        sites.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    site = name
                                    siteOpen = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = stop.copy(
                        type = type,
                        recipient = recipient.trim(),
                        address = address.trim(),
                        zip = zip.trim(),
                        city = city.trim(),
                        colliCount = colliInt ?: stop.colliCount,
                        weightKg = weightInt ?: stop.weightKg,
                        comment = info.trim().ifBlank { null }
                    )
                    onSave(updated, site.takeIf { it != "None" })
                },
                enabled = canSave
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
