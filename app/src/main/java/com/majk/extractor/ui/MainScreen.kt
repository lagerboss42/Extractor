package com.majk.extractor.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.majk.extractor.data.cleaner.ManifestCleaner
import com.majk.extractor.data.db.AddressStore
import com.majk.extractor.data.excel.ExcelGenerator
import com.majk.extractor.data.geocode.Geocoder
import com.majk.extractor.data.model.FixedStop
import com.majk.extractor.data.model.FixedStops
import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import com.majk.extractor.vpn.CaptureRepository
import com.majk.extractor.vpn.LocalVpnService
import com.majk.extractor.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Ready") }
    var lastFile by remember { mutableStateOf<File?>(null) }
    var vpnRunning by remember { mutableStateOf(VpnStatus.isRunning) }
    var stopCount by remember { mutableStateOf(CaptureRepository.lastParseCount) }
    var geocoding by remember { mutableStateOf(false) }

    var activeFixedIds by remember { mutableStateOf(setOf<String>()) }

    var lmUser by remember { mutableStateOf(Geocoder.lantmaterietUser.orEmpty()) }
    var lmPass by remember { mutableStateOf(Geocoder.lantmaterietPass.orEmpty()) }
    var showLmSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CaptureRepository.init(context)
        stopCount = CaptureRepository.lastParseCount
        if (stopCount > 0) {
            status = "Restored $stopCount captured stops from disk"
        }
    }

    val logFile = remember {
        File(context.getExternalFilesDir(null), "opter_traffic_log.txt")
    }

    LaunchedEffect(vpnRunning) {
        while (vpnRunning) {
            stopCount = CaptureRepository.lastParseCount
            kotlinx.coroutines.delay(1000)
        }
        stopCount = CaptureRepository.lastParseCount
    }

    // Refresh count while parsing may be filling stops
    LaunchedEffect(Unit) {
        while (true) {
            stopCount = CaptureRepository.lastParseCount
            kotlinx.coroutines.delay(1500)
        }
    }

    fun actuallyStartVpn() {
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = Intent(context, LocalVpnService::class.java).apply {
                    action = LocalVpnService.ACTION_START
                }
                context.startForegroundService(intent)
                vpnRunning = true
                status = "VPN started \u2013 open Opter Driver"
            } catch (e: Exception) {
                status = "VPN start failed: ${e.message}"
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            actuallyStartVpn()
        } else {
            status = "VPN permission denied"
        }
    }

    fun startVpn() {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            actuallyStartVpn()
        }
    }

    fun stopVpn() {
        val intent = Intent(context, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_STOP
        }
        context.startService(intent)
        vpnRunning = false
        stopCount = CaptureRepository.lastParseCount
        status = "VPN stopped \u2013 $stopCount stops captured"
    }

    fun shareFile(file: File, mimeType: String, title: String) {
        if (!file.exists()) {
            status = "File not found"
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title))
        } catch (e: Exception) {
            status = "Share failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun activeFixedShipments(): List<Shipment> {
        return FixedStops.all
            .filter { it.id in activeFixedIds }
            .map { it.toShipment() }
    }

    fun generateExcel(raw: List<Shipment>, label: String) {
        scope.launch {
            geocoding = true
            status = "Building manifest\u2026"
            try {
                data class GenResult(val file: File, val message: String)
                val result = withContext(Dispatchers.IO) {
                    Geocoder.lantmaterietUser = lmUser.ifBlank { null }
                    Geocoder.lantmaterietPass = lmPass.ifBlank { null }

                    val cleaned = ManifestCleaner.cleanAndConsolidate(raw)
                    val withPk = AddressStore.applyPortkods(cleaned)
                    val opterKeys = withPk.map {
                        "${it.address.lowercase().replace(" ", "")}|${it.zip.filter { c -> c.isDigit() }}"
                    }.toSet()
                    val kkbRows = activeFixedShipments().filter { s ->
                        val k = "${s.address.lowercase().replace(" ", "")}|${s.zip.filter { c -> c.isDigit() }}"
                        k.isBlank() || k !in opterKeys
                    }
                    val combined = withPk + kkbRows

                    val withCoords = try {
                        Geocoder.geocodeAll(combined)
                    } catch (_: Exception) {
                        combined
                    }

                    val file = ExcelGenerator.generate(context, withCoords)
                    try {
                        AddressStore.recordVisits(withPk)
                    } catch (_: Exception) {
                    }
                    val geoCount = withCoords.count { it.lat != null }
                    val kkb = kkbRows.size
                    val remembered = withPk.count { it.portkodRemembered }
                    GenResult(
                        file,
                        "$label: ${withCoords.size} rows (${withPk.size} from Opter, $kkb kkb\u00f6j, $remembered saved PK), $geoCount geocoded"
                    )
                }
                lastFile = result.file
                status = result.message
            } catch (e: Exception) {
                status = "Error: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                geocoding = false
            }
        }
    }

    fun generateFromCapture() {
        scope.launch {
            geocoding = true
            try {
                var raw = CaptureRepository.getShipments()
                if (raw.isEmpty() && logFile.exists()) {
                    status = "Re-parsing traffic log\u2026"
                    withContext(Dispatchers.IO) {
                        CaptureRepository.reparseFromLogFile(logFile)
                    }
                    raw = CaptureRepository.getShipments()
                    stopCount = CaptureRepository.lastParseCount
                }
                if (raw.isEmpty() && activeFixedIds.isEmpty()) {
                    status = "No stops captured and no kkb\u00f6j selected"
                    return@launch
                }
                geocoding = false
                generateExcel(raw, "Capture Excel")
            } catch (e: Exception) {
                status = "Error: ${e.javaClass.simpleName}: ${e.message}"
                geocoding = false
            }
        }
    }

    fun generateTestExcel() {
        val raw = listOf(
            Shipment(
                type = ShipmentType.DEL,
                recipient = "KRISTIN HALLBERG",
                address = "V\u00c4STAN 10",
                zip = "59175",
                city = "BORENSBORG",
                colliCount = 2,
                weightKg = 19,
                portkod = "1234ok",
                comment = "L\u00e4mna hos grannen",
                carrier = "TNT Sverige AB"
            ),
            Shipment(
                type = ShipmentType.DEL,
                recipient = "G\u00f6ran Karl Eskil Nilsson",
                address = "F\u00c5RFIOLSV\u00c4GEN 4",
                zip = "58666",
                city = "LINK\u00d6PING",
                colliCount = 5,
                weightKg = 22,
                portkod = null,
                comment = null,
                carrier = "Region"
            )
        )
        generateExcel(raw, "Test Excel")
    }

    fun toggleFixed(id: String) {
        activeFixedIds = if (id in activeFixedIds) {
            activeFixedIds - id
        } else {
            activeFixedIds + id
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Extractor", style = MaterialTheme.typography.headlineMedium)
        Text(status, style = MaterialTheme.typography.bodyMedium)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { startVpn() },
                enabled = !vpnRunning,
                modifier = Modifier.weight(1f)
            ) { Text("Start VPN") }
            Button(
                onClick = { stopVpn() },
                enabled = vpnRunning,
                modifier = Modifier.weight(1f)
            ) { Text("Stop VPN") }
        }

        Text(
            text = if (vpnRunning) "VPN: running \u00b7 stops: $stopCount" else "VPN: stopped \u00b7 last stops: $stopCount",
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            onClick = { generateFromCapture() },
            enabled = !geocoding,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Generate Excel from Capture")
        }

        Button(
            onClick = {
                shareFile(logFile, "text/plain", "Share Traffic Log")
            },
            enabled = logFile.exists(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share / Open Traffic Log")
        }

        Button(
            onClick = {
                scope.launch {
                    status = "Re-parsing traffic log\u2026"
                    try {
                        val n = withContext(Dispatchers.IO) {
                            CaptureRepository.reparseFromLogFile(logFile)
                        }
                        stopCount = n
                        status = when {
                            n > 0 -> "Re-parsed log \u2192 $n stops"
                            !logFile.exists() || logFile.length() == 0L -> "No traffic log to re-parse"
                            else -> "Re-parsed log \u2192 0 stops (no DEL/list rows the parser accepts)"
                        }
                    } catch (e: Exception) {
                        status = "Re-parse failed: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            },
            enabled = logFile.exists(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Re-parse traffic log")
        }

        if (lastFile != null) {
            Button(
                onClick = {
                    shareFile(
                        lastFile!!,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Share Excel"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share / Open Excel")
            }
        }

        HorizontalDivider()

        Text("Kkb\u00f6j (tap to include)", style = MaterialTheme.typography.titleMedium)
        Text(
            "${activeFixedIds.size} selected \u00b7 Info = kkb\u00f6j",
            style = MaterialTheme.typography.bodySmall
        )

        FixedStops.sections.forEach { section ->
            Text(section, style = MaterialTheme.typography.titleSmall)
            val stops = FixedStops.all.filter { it.section == section }
            FlowRowFixed(stops = stops, activeIds = activeFixedIds, onToggle = { toggleFixed(it) })
            Spacer(modifier = Modifier.height(4.dp))
        }

        HorizontalDivider()

        Button(
            onClick = {
                CaptureRepository.clear()
                stopCount = 0
                status = "Captured stops cleared"
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Text("Clear captured stops")
        }

        Button(
            onClick = { generateTestExcel() },
            enabled = !geocoding,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Generate Test Excel")
        }

        TextButton(onClick = { showLmSettings = !showLmSettings }) {
            Text(if (showLmSettings) "Hide Lantm\u00e4teriet settings" else "Lantm\u00e4teriet API (optional)")
        }

        if (showLmSettings) {
            OutlinedTextField(
                value = lmUser,
                onValueChange = { lmUser = it },
                label = { Text("Lantm\u00e4teriet user") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = lmPass,
                onValueChange = { lmPass = it },
                label = { Text("Lantm\u00e4teriet password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                text = "Leave empty to use only Nominatim (free).",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (vpnRunning)
                "VPN on. Open Opter in the SAME profile. Restart Opter to force list-load."
            else
                "Sources: VPN traffic. Share a screenshot as fallback if needed.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun FlowRowFixed(
    stops: List<FixedStop>,
    activeIds: Set<String>,
    onToggle: (String) -> Unit
) {
    val chunked = stops.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chunked.forEach { rowStops ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowStops.forEach { stop ->
                    val active = stop.id in activeIds
                    FilterChip(
                        selected = active,
                        onClick = { onToggle(stop.id) },
                        label = {
                            Text(
                                stop.recipient,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                color = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowStops.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
