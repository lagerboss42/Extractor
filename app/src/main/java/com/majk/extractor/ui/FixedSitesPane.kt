package com.majk.extractor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.majk.extractor.data.db.FixedSiteRule
import com.majk.extractor.data.db.FixedSiteStore
import java.util.UUID

@Composable
fun FixedSitesPane() {
    val revision = FixedSiteStore.revision
    var sites by remember { mutableStateOf(FixedSiteStore.all()) }
    var editing by remember { mutableStateOf<FixedSiteRule?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        sites = FixedSiteStore.all()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${sites.size} sites · first match wins · comma-separated keywords",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add fixed site")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sites, key = { it.id }) { site ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(site.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOf(site.zip, site.city).filter { it.isNotBlank() }.joinToString(" ")
                                .ifBlank { site.address },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (site.recipientKeywords.isNotBlank()) {
                            Text(site.recipientKeywords, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { FixedSiteStore.move(site.id, true) }) { Text("Up") }
                            TextButton(onClick = { FixedSiteStore.move(site.id, false) }) { Text("Down") }
                            TextButton(onClick = { editing = site }) { Text("Modify") }
                            TextButton(onClick = { FixedSiteStore.delete(site.id) }) {
                                Text("Remove", color = Color(0xFFFF6B6B))
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        FixedSiteDialog(
            existing = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { rule ->
                FixedSiteStore.upsert(rule)
                adding = false
                editing = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixedSiteDialog(
    existing: FixedSiteRule?,
    onDismiss: () -> Unit,
    onSave: (FixedSiteRule) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var zip by remember { mutableStateOf(existing?.zip.orEmpty()) }
    var city by remember { mutableStateOf(existing?.city.orEmpty()) }
    var recipient by remember { mutableStateOf(existing?.recipientKeywords.orEmpty()) }
    var addressKeys by remember { mutableStateOf(existing?.addressKeywords.orEmpty()) }
    var exclude by remember { mutableStateOf(existing?.excludeKeywords.orEmpty()) }
    var requireZip by remember { mutableStateOf(existing?.requireZip.orEmpty()) }
    var requireCity by remember { mutableStateOf(existing?.requireCity.orEmpty()) }
    var either by remember { mutableStateOf(existing?.matchEither ?: false) }
    var split by remember { mutableStateOf(existing?.splitByType ?: true) }
    var typeFilter by remember { mutableStateOf(existing?.typeFilter ?: "any") }
    val canSave = name.isNotBlank() && (recipient.isNotBlank() || addressKeys.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add fixed site" else "Modify fixed site") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Canonical address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zip, onValueChange = { zip = it }, label = { Text("Zip (required if set)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City (required if set)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = recipient, onValueChange = { recipient = it }, label = { Text("Recipient keywords") }, placeholder = { Text("toyota, tmh, \"Port K\"") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = addressKeys, onValueChange = { addressKeys = it }, label = { Text("Address keywords") }, placeholder = { Text("kyrkogatan, magasinsgatan") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = exclude, onValueChange = { exclude = it }, label = { Text("Exclude keywords") }, placeholder = { Text("port f, gate f") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = requireZip, onValueChange = { requireZip = it }, label = { Text("Require zip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = requireCity, onValueChange = { requireCity = it }, label = { Text("Require city") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("*GE* matches anywhere. GE is a whole word. GE* starts with, *GE ends with.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = either, onCheckedChange = { either = it })
                    Text("Match recipient or address")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = split, onCheckedChange = { split = it })
                    Text("Split pickups and deliveries")
                }
                Text("Type", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("any" to "Any", "pick" to "Pickup", "del" to "Delivery").forEach { (id, label) ->
                        FilterChip(
                            selected = typeFilter == id,
                            onClick = { typeFilter = id },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        FixedSiteRule(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            address = address.trim(),
                            zip = zip.trim(),
                            city = city.trim(),
                            requireZip = requireZip.trim(),
                            requireCity = requireCity.trim(),
                            recipientKeywords = recipient.trim(),
                            addressKeywords = addressKeys.trim(),
                            excludeKeywords = exclude.trim(),
                            matchEither = either,
                            splitByType = split,
                            typeFilter = typeFilter,
                            order = existing?.order ?: 0
                        )
                    )
                },
                enabled = canSave
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
