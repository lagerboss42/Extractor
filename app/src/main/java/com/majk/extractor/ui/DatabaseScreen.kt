package com.majk.extractor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.majk.extractor.data.db.AddressStore
import com.majk.extractor.data.db.GeocodeEntry
import com.majk.extractor.data.db.GeocodeStore
import com.majk.extractor.data.db.PkEntry
import com.majk.extractor.data.db.VisitEntry
import com.majk.extractor.util.PlusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private sealed class DbTarget {
    data class Code(val entry: PkEntry) : DbTarget()
    data class Visit(val entry: VisitEntry) : DbTarget()
    data class Geo(val entry: GeocodeEntry) : DbTarget()
}

@Composable
fun DatabaseScreen() {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var codes by remember { mutableStateOf<List<PkEntry>>(emptyList()) }
    var visits by remember { mutableStateOf<List<VisitEntry>>(emptyList()) }
    var geocodes by remember { mutableStateOf<List<GeocodeEntry>>(emptyList()) }
    val revision = AddressStore.revision + GeocodeStore.revision

    var menuTarget by remember { mutableStateOf<DbTarget?>(null) }
    var showModify by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var showAddCode by remember { mutableStateOf(false) }
    var showClearCodes by remember { mutableStateOf(false) }
    var showClearVisits by remember { mutableStateOf(false) }
    var showClearGeocode by remember { mutableStateOf(false) }

    LaunchedEffect(query, tab, revision) {
        when (tab) {
            0 -> codes = AddressStore.searchCodes(query)
            1 -> visits = AddressStore.searchVisits(query)
            else -> geocodes = GeocodeStore.search(query)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Database", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search address, zip, city or code") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Entry codes") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Visits") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Geocode") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Fixed sites") })
        }
        if (tab == 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${codes.size} saved codes · shown as {code} when PK is missing",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showClearCodes = true },
                    enabled = codes.isNotEmpty()
                ) {
                    Text("Clear all", color = Color(0xFFFF6B6B))
                }
            }
            Button(
                onClick = { showAddCode = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Add entry code")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(codes, key = { it.id }) { row ->
                    Box {
                        PkCard(
                            row = row,
                            onLongPress = { menuTarget = DbTarget.Code(row) }
                        )
                        DropdownMenu(
                            expanded = !showModify && !showRemove && menuTarget is DbTarget.Code &&
                                (menuTarget as DbTarget.Code).entry == row,
                            onDismissRequest = {
                                if (!showModify && !showRemove) menuTarget = null
                            }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Modify") },
                                onClick = {
                                    menuTarget = DbTarget.Code(row)
                                    showModify = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove", color = Color(0xFFFF6B6B)) },
                                onClick = {
                                    menuTarget = DbTarget.Code(row)
                                    showRemove = true
                                }
                            )
                        }
                    }
                }
            }
        } else if (tab == 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${visits.size} rows · address, shipments, weight, date",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showClearVisits = true },
                    enabled = visits.isNotEmpty()
                ) {
                    Text("Clear all", color = Color(0xFFFF6B6B))
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visits, key = { "${it.address}|${it.zip}|${it.date}" }) { row ->
                    Box {
                        VisitCard(
                            row = row,
                            onLongPress = { menuTarget = DbTarget.Visit(row) }
                        )
                        DropdownMenu(
                            expanded = !showModify && !showRemove && menuTarget is DbTarget.Visit &&
                                (menuTarget as DbTarget.Visit).entry == row,
                            onDismissRequest = {
                                if (!showModify && !showRemove) menuTarget = null
                            }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Modify") },
                                onClick = {
                                    menuTarget = DbTarget.Visit(row)
                                    showModify = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove", color = Color(0xFFFF6B6B)) },
                                onClick = {
                                    menuTarget = DbTarget.Visit(row)
                                    showRemove = true
                                }
                            )
                        }
                    }
                }
            }
        } else if (tab == 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${geocodes.size} cached · address, plus code, source",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showClearGeocode = true },
                    enabled = geocodes.isNotEmpty()
                ) {
                    Text("Clear all", color = Color(0xFFFF6B6B))
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(geocodes, key = { "${it.address}|${it.zip}|${it.city}" }) { row ->
                    Box {
                        GeocodeCard(
                            row = row,
                            onLongPress = { menuTarget = DbTarget.Geo(row) }
                        )
                        DropdownMenu(
                            expanded = !showModify && !showRemove && menuTarget is DbTarget.Geo &&
                                (menuTarget as DbTarget.Geo).entry == row,
                            onDismissRequest = {
                                if (!showModify && !showRemove) menuTarget = null
                            }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Modify") },
                                onClick = {
                                    menuTarget = DbTarget.Geo(row)
                                    showModify = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove", color = Color(0xFFFF6B6B)) },
                                onClick = {
                                    menuTarget = DbTarget.Geo(row)
                                    showRemove = true
                                }
                            )
                        }
                    }
                }
            }
        } else {
            FixedSitesPane()
        }
    }

    if (showAddCode) {
        var code by remember { mutableStateOf("") }
        var recipient by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var zip by remember { mutableStateOf("") }
        var city by remember { mutableStateOf("") }
        val canSave = code.isNotBlank() && address.isNotBlank() && zip.isNotBlank()
        AlertDialog(
            onDismissRequest = { showAddCode = false },
            title = { Text("Add entry code") },
            text = {
                Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code (*# allowed)") }, singleLine = true)
                    OutlinedTextField(value = recipient, onValueChange = { recipient = it }, label = { Text("Recipient (optional)") }, singleLine = true)
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, singleLine = true)
                    OutlinedTextField(value = zip, onValueChange = { zip = it }, label = { Text("Zip") }, singleLine = true)
                    OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (AddressStore.insertManualCode(code, recipient.ifBlank { null }, address, zip, city)) {
                            showAddCode = false
                        }
                    },
                    enabled = canSave
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCode = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearCodes) {
        RemoveConfirmDialog(
            title = "Clear all entry codes?",
            summary = "This deletes every saved portkod. Visits are not touched.",
            onDismiss = { showClearCodes = false },
            onConfirmed = {
                showClearCodes = false
                AddressStore.clearCodes()
            }
        )
    }
    if (showClearVisits) {
        RemoveConfirmDialog(
            title = "Clear all visits?",
            summary = "This deletes every visit row. Entry codes are not touched.",
            onDismiss = { showClearVisits = false },
            onConfirmed = {
                showClearVisits = false
                AddressStore.clearVisits()
            }
        )
    }
    if (showClearGeocode) {
        RemoveConfirmDialog(
            title = "Clear geocode cache?",
            summary = "This deletes every cached lookup, hits and misses.",
            onDismiss = { showClearGeocode = false },
            onConfirmed = {
                showClearGeocode = false
                GeocodeStore.clear()
            }
        )
    }

    if (showModify) {
        when (val t = menuTarget) {
            is DbTarget.Code -> ModifyCodeDialog(
                entry = t.entry,
                onDismiss = {
                    showModify = false
                    menuTarget = null
                },
                onSave = { code, recipient, address, zip, city ->
                    val ok = AddressStore.updateCode(t.entry.id, code, recipient, address, zip, city)
                    if (ok) {
                        showModify = false
                        menuTarget = null
                    }
                    ok
                }
            )
            is DbTarget.Visit -> ModifyVisitDialog(
                entry = t.entry,
                onDismiss = {
                    showModify = false
                    menuTarget = null
                },
                onSave = { address, zip, city, shipments, weight, date ->
                    val ok = AddressStore.updateVisit(
                        t.entry.address,
                        t.entry.zip,
                        t.entry.date,
                        address,
                        zip,
                        city,
                        shipments,
                        weight,
                        date
                    )
                    if (ok) {
                        showModify = false
                        menuTarget = null
                    }
                    ok
                }
            )
            is DbTarget.Geo -> ModifyGeoDialog(
                entry = t.entry,
                onDismiss = {
                    showModify = false
                    menuTarget = null
                },
                onSave = { address, zip, city, plusCode, lat, lon ->
                    val old = t.entry
                    if (address != old.address || zip != old.zip || city != old.city) {
                        GeocodeStore.delete(old.address, old.zip, old.city)
                    }
                    val short = PlusCode.short(lat, lon, city)
                    GeocodeStore.save(
                        address = address,
                        zip = zip,
                        city = city,
                        lat = lat,
                        lon = lon,
                        plusCode = short.ifBlank { plusCode },
                        source = "Manual",
                        found = true
                    )
                    showModify = false
                    menuTarget = null
                    true
                }
            )
            null -> {}
        }
    }

    if (showRemove) {
        when (val t = menuTarget) {
            is DbTarget.Code -> RemoveConfirmDialog(
                title = "Remove entry code?",
                summary = "${t.entry.address}\n{${t.entry.code}}",
                onDismiss = {
                    showRemove = false
                    menuTarget = null
                },
                onConfirmed = {
                    AddressStore.deleteCode(t.entry.id)
                    showRemove = false
                    menuTarget = null
                }
            )
            is DbTarget.Visit -> RemoveConfirmDialog(
                title = "Remove visit?",
                summary = "${t.entry.address}\n${t.entry.shipments} · ${t.entry.totalWeight} kg · ${t.entry.date}",
                onDismiss = {
                    showRemove = false
                    menuTarget = null
                },
                onConfirmed = {
                    AddressStore.deleteVisit(t.entry.address, t.entry.zip, t.entry.date)
                    showRemove = false
                    menuTarget = null
                }
            )
            is DbTarget.Geo -> RemoveConfirmDialog(
                title = "Remove cached lookup?",
                summary = "${t.entry.address}\n${t.entry.plusCode.ifBlank { "no match" }}",
                onDismiss = {
                    showRemove = false
                    menuTarget = null
                },
                onConfirmed = {
                    GeocodeStore.delete(t.entry.address, t.entry.zip, t.entry.city)
                    showRemove = false
                    menuTarget = null
                }
            )
            null -> {}
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PkCard(row: PkEntry, onLongPress: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(row.recipient ?: row.address, style = MaterialTheme.typography.titleSmall)
            if (row.recipient != null) {
                Text(row.address, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                listOf(row.zip, row.city).filter { it.isNotBlank() }.joinToString(" "),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "{${row.code}}",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GeocodeCard(row: GeocodeEntry, onLongPress: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(row.address.ifBlank { "(no address)" }, style = MaterialTheme.typography.titleSmall)
            Text(
                listOf(row.zip, row.city).filter { it.isNotBlank() }.joinToString(" "),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (row.found) row.plusCode.ifBlank { "matched" } else "no match",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = if (row.found) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            val isManual = row.source.equals("Manual", ignoreCase = true)
            Text(
                "${if (isManual) "Manual" else row.source} · ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(row.lookedUp))}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isManual) Color(0xFF4DB6AC) else Color.Unspecified
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VisitCard(row: VisitEntry, onLongPress: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(row.recipient ?: row.address, style = MaterialTheme.typography.titleSmall)
            if (row.recipient != null) {
                Text(row.address, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                listOf(row.zip, row.city).filter { it.isNotBlank() }.joinToString(" "),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "${row.shipments} shipments · ${row.totalWeight} kg · ${row.date}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ModifyCodeDialog(
    entry: PkEntry,
    onDismiss: () -> Unit,
    onSave: (code: String, recipient: String?, address: String, zip: String, city: String) -> Boolean
) {
    var code by remember { mutableStateOf(entry.code) }
    var recipient by remember { mutableStateOf(entry.recipient.orEmpty()) }
    var address by remember { mutableStateOf(entry.address) }
    var zip by remember { mutableStateOf(entry.zip) }
    var city by remember { mutableStateOf(entry.city) }
    val canSave = code.isNotBlank() &&
        code.trim().matches(Regex("^[A-Za-z0-9*#]+$")) &&
        (recipient.isNotBlank() || (address.isNotBlank() && zip.isNotBlank()))
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modify entry code") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code (*# allowed)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = recipient, onValueChange = { recipient = it }, label = { Text("Recipient (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zip, onValueChange = { zip = it }, label = { Text("Zip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error!!, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ok = onSave(code.trim(), recipient.trim().ifBlank { null }, address.trim(), zip.trim(), city.trim())
                    if (!ok) error = "Could not save. A row with the same address, recipient and code may already exist."
                },
                enabled = canSave
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ModifyVisitDialog(
    entry: VisitEntry,
    onDismiss: () -> Unit,
    onSave: (address: String, zip: String, city: String, shipments: Int, weight: Int, date: String) -> Boolean
) {
    var address by remember { mutableStateOf(entry.address) }
    var zip by remember { mutableStateOf(entry.zip) }
    var city by remember { mutableStateOf(entry.city) }
    var shipments by remember { mutableStateOf(entry.shipments.toString()) }
    var weight by remember { mutableStateOf(entry.totalWeight.toString()) }
    var date by remember { mutableStateOf(entry.date) }
    val shipInt = shipments.toIntOrNull()
    val weightInt = weight.toIntOrNull()
    val valid = address.isNotBlank() &&
        shipInt != null && shipInt >= 0 &&
        weightInt != null && weightInt >= 0 &&
        date.trim().matches(Regex("""\d{4}-\d{2}-\d{2}"""))
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modify visit") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zip, onValueChange = { zip = it }, label = { Text("Zip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = shipments,
                    onValueChange = { shipments = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Times visited (shipments)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Kilos") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) Text(error!!, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ok = onSave(address.trim(), zip.trim(), city.trim(), shipInt!!, weightInt!!, date.trim())
                    if (!ok) error = "Could not save. That date may already exist for this visit."
                },
                enabled = valid
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RemoveConfirmDialog(
    title: String,
    summary: String,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(holding) {
        if (!holding) {
            progress = 0f
            return@LaunchedEffect
        }
        val start = System.nanoTime()
        val durationNs = 2_000_000_000L
        while (isActive && holding) {
            val elapsed = System.nanoTime() - start
            progress = (elapsed.toFloat() / durationNs).coerceIn(0f, 1f)
            if (progress >= 1f) {
                onConfirmed()
                break
            }
            delay(16)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Hold Confirm for 2 seconds to delete.",
                    style = MaterialTheme.typography.bodySmall
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFFFF6B6B),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (holding) Color(0xFFB71C1C) else Color(0xFFC62828)
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            holding = true
                            waitForUpOrCancellation()
                            holding = false
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (holding) "Hold…" else "Confirm",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ModifyGeoDialog(
    entry: GeocodeEntry,
    onDismiss: () -> Unit,
    onSave: (address: String, zip: String, city: String, plusCode: String, lat: Double, lon: Double) -> Boolean
) {
    var address by remember { mutableStateOf(entry.address) }
    var zip by remember { mutableStateOf(entry.zip) }
    var city by remember { mutableStateOf(entry.city) }
    var plusCode by remember { mutableStateOf(entry.plusCode) }
    var latText by remember { mutableStateOf(entry.lat?.toString().orEmpty()) }
    var lonText by remember { mutableStateOf(entry.lon?.toString().orEmpty()) }
    var lastEdited by remember { mutableStateOf("plus") }
    var error by remember { mutableStateOf<String?>(null) }

    fun currentLat() = latText.trim().toDoubleOrNull()
    fun currentLon() = lonText.trim().toDoubleOrNull()

    val lat = currentLat()
    val lon = currentLon()
    val canSave = address.trim().isNotBlank() &&
        lat != null && lat in -90.0..90.0 &&
        lon != null && lon in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modify geocode") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zip, onValueChange = { zip = it }, label = { Text("Zip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = plusCode,
                    onValueChange = {
                        plusCode = it
                        lastEdited = "plus"
                    },
                    label = { Text("Plus Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = latText,
                    onValueChange = {
                        latText = it
                        lastEdited = "coords"
                        val la = it.trim().toDoubleOrNull()
                        val lo = currentLon()
                        if (la != null && lo != null) plusCode = PlusCode.short(la, lo, city)
                    },
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = {
                        lonText = it
                        lastEdited = "coords"
                        val la = currentLat()
                        val lo = it.trim().toDoubleOrNull()
                        if (la != null && lo != null) plusCode = PlusCode.short(la, lo, city)
                    },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                LaunchedEffect(plusCode) {
                    if (lastEdited == "plus") {
                        val decoded = PlusCode.decode(plusCode, currentLat(), currentLon(), city)
                        if (decoded != null) {
                            latText = format7(decoded.first)
                            lonText = format7(decoded.second)
                        }
                    }
                }
                if (error != null) Text(error!!, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val la = currentLat()
                    val lo = currentLon()
                    if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
                        error = "Latitude must be -90..90 and longitude -180..180."
                        return@TextButton
                    }
                    if (lastEdited == "plus") {
                        val decoded = PlusCode.decode(plusCode, la, lo, city)
                        if (decoded == null) {
                            error = "Plus Code does not decode (bad character or missing +)."
                            return@TextButton
                        }
                        val ok = onSave(address.trim(), zip.trim(), city.trim(), plusCode.trim(), decoded.first, decoded.second)
                        if (!ok) error = "Could not save."
                    } else {
                        val ok = onSave(address.trim(), zip.trim(), city.trim(), plusCode.trim(), la, lo)
                        if (!ok) error = "Could not save."
                    }
                },
                enabled = canSave
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun format7(v: Double): String = String.format(java.util.Locale.US, "%.7f", v)
