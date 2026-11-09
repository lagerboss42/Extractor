package com.majk.extractor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.majk.extractor.data.db.PkEntry
import com.majk.extractor.data.db.VisitEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private sealed class DbTarget {
    data class Code(val entry: PkEntry) : DbTarget()
    data class Visit(val entry: VisitEntry) : DbTarget()
}

@Composable
fun DatabaseScreen() {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var codes by remember { mutableStateOf<List<PkEntry>>(emptyList()) }
    var visits by remember { mutableStateOf<List<VisitEntry>>(emptyList()) }
    val revision = AddressStore.revision

    var menuTarget by remember { mutableStateOf<DbTarget?>(null) }
    var showModify by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }

    LaunchedEffect(query, tab, revision) {
        if (tab == 0) codes = AddressStore.searchCodes(query)
        else visits = AddressStore.searchVisits(query)
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
        }
        if (tab == 0) {
            Text(
                "${codes.size} saved codes · shown as {code} when PK is missing",
                style = MaterialTheme.typography.bodySmall
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(codes, key = { "${it.address}|${it.zip}|${it.code}" }) { row ->
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
        } else {
            Text(
                "${visits.size} rows · address, shipments, weight, date",
                style = MaterialTheme.typography.bodySmall
            )
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
        }
    }

    if (showModify) {
        when (val t = menuTarget) {
            is DbTarget.Code -> ModifyCodeDialog(
                entry = t.entry,
                onDismiss = {
                    showModify = false
                    menuTarget = null
                },
                onSave = { newCode ->
                    AddressStore.updateCode(t.entry.address, t.entry.zip, newCode)
                    showModify = false
                    menuTarget = null
                }
            )
            is DbTarget.Visit -> ModifyVisitDialog(
                entry = t.entry,
                onDismiss = {
                    showModify = false
                    menuTarget = null
                },
                onSave = { shipments, weight, date ->
                    AddressStore.updateVisit(
                        t.entry.address,
                        t.entry.zip,
                        t.entry.date,
                        shipments,
                        weight,
                        date
                    )
                    showModify = false
                    menuTarget = null
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
                    AddressStore.deleteCode(t.entry.address, t.entry.zip)
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
            Text(row.address, style = MaterialTheme.typography.titleSmall)
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
            Text(row.address, style = MaterialTheme.typography.titleSmall)
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
    onSave: (String) -> Unit
) {
    var code by remember { mutableStateOf(entry.code) }
    val valid = code.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modify entry code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.address, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("PK") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(code.trim()) },
                enabled = valid
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
    onSave: (shipments: Int, weight: Int, date: String) -> Unit
) {
    var shipments by remember { mutableStateOf(entry.shipments.toString()) }
    var weight by remember { mutableStateOf(entry.totalWeight.toString()) }
    var date by remember { mutableStateOf(entry.date) }
    val shipInt = shipments.toIntOrNull()
    val weightInt = weight.toIntOrNull()
    val valid = shipInt != null && shipInt >= 0 &&
        weightInt != null && weightInt >= 0 &&
        date.trim().matches(Regex("""\d{4}-\d{2}-\d{2}"""))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modify visit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.address, style = MaterialTheme.typography.bodySmall)
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(shipInt!!, weightInt!!, date.trim()) },
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
