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
                            buildString {
                                append(stop.fixedSiteName ?: stop.recipient.ifBlank { "(no name)" })
                                append(" · ")
                                append(if (stop.type == ShipmentType.PICK) "pickup" else "delivery")
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            listOf(stop.address, stop.zip, stop.city).filter { it.isNotBlank() }.joinToString(", "),
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
    var site by remember { mutableStateOf(stop.fixedSiteName ?: "None") }
    val sites = FixedSiteStore.all()

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
                Text("Assign to fixed site", style = MaterialTheme.typography.bodySmall)
                sites.map { it.name }.distinct().plus("None").forEach { name ->
                    Row {
                        RadioButton(selected = site == name, onClick = { site = name })
                        Text(name, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = stop.copy(
                    recipient = recipient.trim(),
                    address = address.trim(),
                    zip = zip.trim(),
                    city = city.trim()
                )
                onSave(updated, site.takeIf { it != "None" })
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
