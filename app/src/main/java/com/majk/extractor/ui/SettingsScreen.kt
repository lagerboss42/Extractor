package com.majk.extractor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.majk.extractor.data.model.FixedStops
import com.majk.extractor.data.prefs.AppPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var quiet by remember { mutableIntStateOf(AppPreferences.quietMinutes) }
    var geocode by remember { mutableStateOf(AppPreferences.geocodeEnabled) }
    var lmUser by remember { mutableStateOf(AppPreferences.lantmaterietUser) }
    var lmPass by remember { mutableStateOf(AppPreferences.lantmaterietPass) }
    var prune by remember { mutableIntStateOf(AppPreferences.pruneDays) }
    var visible by remember { mutableStateOf(AppPreferences.visibleSections()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Capture", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Quiet period")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        quiet = (quiet - 1).coerceAtLeast(1)
                        AppPreferences.quietMinutes = quiet
                    }) { Text("−") }
                    Text("$quiet min", modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = {
                        quiet = (quiet + 1).coerceAtMost(60)
                        AppPreferences.quietMinutes = quiet
                    }) { Text("+") }
                }
            }
            Text(
                "Auto-save capture after this idle time or on Stop Capture.",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()

            Text("Geocoding", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Geocode addresses")
                Switch(
                    checked = geocode,
                    onCheckedChange = {
                        geocode = it
                        AppPreferences.geocodeEnabled = it
                    }
                )
            }
            OutlinedTextField(
                value = lmUser,
                onValueChange = {
                    lmUser = it
                    AppPreferences.lantmaterietUser = it
                },
                label = { Text("Lantmäteriet username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = lmPass,
                onValueChange = {
                    lmPass = it
                    AppPreferences.lantmaterietPass = it
                },
                label = { Text("Lantmäteriet password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()

            Text("Storage", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Prune older than")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        prune = (prune - 1).coerceAtLeast(1)
                        AppPreferences.pruneDays = prune
                    }) { Text("−") }
                    Text("$prune days", modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = {
                        prune = (prune + 1).coerceAtMost(365)
                        AppPreferences.pruneDays = prune
                    }) { Text("+") }
                }
            }

            HorizontalDivider()

            Text("Kkböj regions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Choose which fixed-stop groups appear on the main screen.",
                style = MaterialTheme.typography.bodySmall
            )
            FixedStops.sections.forEach { section ->
                val on = section in visible
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(section)
                    Switch(
                        checked = on,
                        onCheckedChange = { checked ->
                            visible = if (checked) visible + section else visible - section
                            AppPreferences.setVisibleSections(visible)
                        }
                    )
                }
            }
        }
    }
}
