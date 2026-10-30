package com.majk.extractor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.majk.extractor.data.db.AddressStore
import com.majk.extractor.data.db.PkEntry
import com.majk.extractor.data.db.VisitEntry

@Composable
fun DatabaseScreen() {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var codes by remember { mutableStateOf<List<PkEntry>>(emptyList()) }
    var visits by remember { mutableStateOf<List<VisitEntry>>(emptyList()) }
    val revision = AddressStore.revision

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
                "${codes.size} saved codes \u00b7 shown as {code} when PK is missing",
                style = MaterialTheme.typography.bodySmall
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(codes, key = { "${it.address}|${it.zip}|${it.code}" }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.address, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOf(row.zip, row.city).filter { it.isNotBlank() }.joinToString(" "),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "{${row.code}}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                "${visits.size} rows \u00b7 address, shipments, weight, date",
                style = MaterialTheme.typography.bodySmall
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visits, key = { "${it.address}|${it.zip}|${it.date}" }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.address, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOf(row.zip, row.city).filter { it.isNotBlank() }.joinToString(" "),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "${row.shipments} shipments \u00b7 ${row.totalWeight} kg \u00b7 ${row.date}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
