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
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.launch
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

    fun actuallyStartVpn() {
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = Intent(context, LocalVpnService::class.java).apply {
                    action = LocalVpnService.ACTION_START
                }
                context.startForegroundService(intent)
                vpnRunning = true
                status = "VPN started – open Opter Driver"
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
        status = "VPN stopped – $stopCount stops captured"
    }

    fun shareFile(file: File, mimeType: String, title: String) {
        if (!file.exists()) {
            status = "File not found"
            return
        }
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
    }

    fun activeFixedShipments(): List<Shipment> {
        return FixedStops.all
            .filter { it.id in activeFixedIds }
            .map { it.toShipment() }
    }

    fun generateExcel(raw: List<Shipment>, label: String) {
        scope.launch {
            geocoding = true
            status = "Building manifest…"
            try {
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

                status = "Geocoding (${combined.size} stops)…"
                val withCoords = try {
                    Geocoder.geocodeAll(combined)
                } catch (e: Exception) {
                    status = "Geocode skipped: ${e.message}"
                    combined
                }

                status = "Writing Excel…"
                val file = ExcelGenerator.generate(context, withCoords)
                try {
                    AddressStore.recordVisits(withPk)
                } catch (_: Exception) {
                }
                lastFile = file
                val geoCount = withCoords.count { it.lat != null }
                val kkb = kkbRows.size
                val remembered = withPk.count { it.portkodRemembered }
                status = "$label: ${withCoords.size} rows (${withPk.size} from Opter, $kkb kkböj, $remembered saved PK), $geoCount geocoded"
            } catch (e: Exception) {
                status = "Error: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                geocoding = false
            }
        }
    }

    fun generateFromCapture() {
        var raw = CaptureRepository.getShipments()
        if (raw.isEmpty() && logFile.exists()) {
            status = "Re-parsing traffic log…"
            CaptureRepository.loadFromLogText(logFile.readText())
            raw = CaptureRepository.getShipments()
            stopCount = CaptureRepository.lastParseCount
        }
        if (raw.isEmpty() && activeFixedIds.isEmpty()) {
            status = "No stops captured and no kkböj selected"
            return
        }
        generateExcel(raw, "Capture Excel")
    }

    fun generateTestExcel() {
        val raw = listOf(
            Shipment(
                type = ShipmentType.DEL,
                recipient = "KRISTIN HALLBERG",
                address = "VÄSTAN 10",
                zip = "59175",
                city = "BORENSBORG",
                colliCount = 2,
                weightKg = 19,
                portkod = "1234ok",
                comment = "Lämna hos grannen",
                carrier = "TNT Sverige AB"
            ),
            Shipment(
                type = ShipmentType.DEL,
                recipient = "Göran Karl Eskil Nilsson",
                address = "FÅRFIOLSVÄGEN 4",
                zip = "58666",
                city = "LINKÖPING",
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
            text = if (vpnRunning) "VPN: running · stops: $stopCount" else "VPN: stopped · last stops: $stopCount",
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

        Text("Kkböj (tap to include)", style = MaterialTheme.typography.titleMedium)
        Text(
            "${activeFixedIds.size} selected · Info = kkböj",
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
            Text(if (showLmSettings) "Hide Lantmäteriet settings" else "Lantmäteriet API (optional)")
        }

        if (showLmSettings) {
            OutlinedTextField(
                value = lmUser,
                onValueChange = { lmUser = it },
                label = { Text("Lantmäteriet user") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = lmPass,
                onValueChange = { lmPass = it },
                label = { Text("Lantmäteriet password") },
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
                "VPN on. Open Opter in the SAME profile as Extractor (personal, not work). Pull to refresh the stop list."
            else
                "IMPORTANT: Extractor VPN only sees apps in the same Android profile.\n" +
                "1. Start VPN\n2. Open Opter (personal profile) → refresh stops\n" +
                "3. Stop VPN → check traffic log for :81\n4. Generate Excel",
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
