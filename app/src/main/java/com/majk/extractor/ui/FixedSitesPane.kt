package com.majk.extractor.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.majk.extractor.data.db.FixedSiteRule
import com.majk.extractor.data.db.FixedSiteStore
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

private val DisabledGrey = Color(0xFF9E9E9E)

@Composable
fun FixedSitesPane() {
    val revision = FixedSiteStore.revision
    var sites by remember { mutableStateOf(FixedSiteStore.all()) }
    var editing by remember { mutableStateOf<FixedSiteRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        sites = FixedSiteStore.all()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${sites.size} sites · tap to edit · hold and drag to reorder",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add fixed site")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sites, key = { it.id }) { site ->
                val muted = !site.enabled
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(site.id) {
                            val timeout = viewConfiguration.longPressTimeoutMillis
                            val threshold = 64.dp.toPx()
                            awaitEachGesture {
                                awaitFirstDown()
                                val up = withTimeoutOrNull(timeout) { waitForUpOrCancellation() }
                                if (up != null) {
                                    editing = FixedSiteStore.all().firstOrNull { it.id == site.id } ?: site
                                    return@awaitEachGesture
                                }
                                dragging = site.id
                                var accum = 0f
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) break
                                        accum += change.positionChange().y
                                        change.consume()
                                        while (accum > threshold) {
                                            FixedSiteStore.move(site.id, false)
                                            accum -= threshold
                                        }
                                        while (accum < -threshold) {
                                            FixedSiteStore.move(site.id, true)
                                            accum += threshold
                                        }
                                    }
                                } finally {
                                    dragging = null
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    site.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (muted) DisabledGrey else Color.Unspecified,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (muted) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Disabled",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DisabledGrey
                                    )
                                }
                            }
                            Text(
                                listOf(site.zip, site.city).filter { it.isNotBlank() }.joinToString(" ")
                                    .ifBlank { site.address },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (muted) DisabledGrey else Color.Unspecified
                            )
                        }
                        Text(
                            "⋮⋮",
                            color = if (dragging == site.id) MaterialTheme.colorScheme.primary else DisabledGrey
                        )
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
            },
            onDelete = {
                editing?.let { FixedSiteStore.delete(it.id) }
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
    onSave: (FixedSiteRule) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var zip by remember { mutableStateOf(existing?.zip.orEmpty()) }
    var city by remember { mutableStateOf(existing?.city.orEmpty()) }
    var recipient by remember { mutableStateOf(existing?.recipientKeywords.orEmpty()) }
    var addressKeys by remember { mutableStateOf(existing?.addressKeywords.orEmpty()) }
    var exclude by remember { mutableStateOf(existing?.excludeKeywords.orEmpty()) }
    var either by remember { mutableStateOf(existing?.matchEither ?: false) }
    var split by remember { mutableStateOf(existing?.splitByType ?: true) }
    var typeFilter by remember { mutableStateOf(existing?.typeFilter ?: "any") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    val canSave = name.isNotBlank() && (recipient.isNotBlank() || addressKeys.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (existing == null) "Add fixed site" else "Modify ${existing.name}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("Enabled", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Canonical address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zip, onValueChange = { zip = it }, label = { Text("Zip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = recipient, onValueChange = { recipient = it }, label = { Text("Recipient keywords") }, placeholder = { Text("toyota, tmh, \"Port K\"") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = addressKeys, onValueChange = { addressKeys = it }, label = { Text("Address keywords") }, placeholder = { Text("kyrkogatan, magasinsgatan") }, supportingText = { Text("Use quotes to match a phrase as one string, e.g. \"Port K\".") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = exclude, onValueChange = { exclude = it }, label = { Text("Exclude keywords") }, placeholder = { Text("port f, gate f") }, modifier = Modifier.fillMaxWidth())
                Text("* use asterisk as wildcard.", style = MaterialTheme.typography.bodySmall)
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
                if (existing != null) {
                    Spacer(Modifier.height(8.dp))
                    HoldToRemove(onRemove = onDelete)
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
                            requireZip = "",
                            requireCity = "",
                            recipientKeywords = recipient.trim(),
                            addressKeywords = addressKeys.trim(),
                            excludeKeywords = exclude.trim(),
                            matchEither = either,
                            splitByType = split,
                            typeFilter = typeFilter,
                            order = existing?.order ?: 0,
                            enabled = enabled
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
