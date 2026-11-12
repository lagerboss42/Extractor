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
import com.majk.extractor.BuildConfig
import com.majk.extractor.data.cleaner.ManifestCleaner
import com.majk.extractor.data.db.AddressStore
import com.majk.extractor.data.excel.ExcelGenerator
import com.majk.extractor.data.geocode.Geocoder
import com.majk.extractor.data.prefs.AppPreferences
import com.majk.extractor.data.storage.UserDataStore
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
    var geocodeEnabled by remember { mutableStateOf(AppPreferences.geocodeEnabled) }
    var visibleSections by remember { mutableStateOf(AppPreferences.visibleSections()) }
    var activeFixedIds by remember { mutableStateOf(setOf<String>()) }

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
        // Fail closed in the UI too — service refuses, but don't claim VPN is running.
        val opterInstalled = try {
            context.packageManager.getPackageInfo("com.opter.driver", 0)
            true
        } catch (_: Exception) {
            false
        }
        if (!opterInstalled) {
            status = "Opter Driver not installed — VPN refused"
            return
        }
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

    val capturePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                // Preserve extension so .pcap is not parsed as text.
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
                val ext = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in listOf("pcap", "pcapng", "txt", "log") }
                    ?: "bin"
                val dest = File(context.cacheDir, "capture_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                val count = CaptureRepository.loadCaptureFile(dest)
                stopCount = count.coerceAtLeast(0)
                status = when {
                    count < 0 -> "Could not read capture"
                    count == 0 -> "No shipments in capture"
                    else -> "Loaded $count stops from capture"
                }
            } catch (e: Exception) {
                status = "Load failed: ${e.message}"
            }
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
        status = "Capture stopped – $stopCount stops captured"
    }

    fun openFile(file: File, mimeType: String) {
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
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            status = "Open failed: ${e.message}"
        }
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
            status = "Building manifest…"
            try {
                Geocoder.lantmaterietUser = AppPreferences.lantmaterietUser.ifBlank { null }
                Geocoder.lantmaterietPass = AppPreferences.lantmaterietPass.ifBlank { null }

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

                status = if (geocodeEnabled) "Geocoding (${combined.size} stops)…" else "Writing Excel…"
                val withCoords = if (geocodeEnabled) {
                    try {
                        Geocoder.geocodeAll(combined)
                    } catch (e: Exception) {
                        status = "Geocode skipped: ${e.message}"
                        combined
                    }
                } else {
                    combined
                }

                status = "Writing Excel…"
                val file = ExcelGenerator.generate(context, withCoords)
                val dest = UserDataStore.nextManifestFile()
                try {
                    file.copyTo(dest, overwrite = true)
                } catch (_: Exception) {}
                lastFile = dest.takeIf { it.exists() } ?: file
                try {
                    AddressStore.recordVisits(withPk)
                } catch (_: Exception) {
                }
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
            CaptureRepository.reparseFromLogFile(logFile)
            raw = CaptureRepository.getShipments()
            stopCount = CaptureRepository.lastParseCount
        }
        if (raw.isEmpty() && activeFixedIds.isEmpty()) {
            status = "No stops captured and no kkböj selected"
            return
        }
        generateExcel(raw, "Capture Excel")
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
            ) { Text("Start Capture") }
            Button(
                onClick = { stopVpn() },
                enabled = vpnRunning,
                modifier = Modifier.weight(1f)
            ) { Text("Stop Capture") }
        }

        Text(
            text = if (vpnRunning) "Capture: running · stops: $stopCount" else "Capture: stopped · last stops: $stopCount",
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            onClick = { capturePicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Load capture file")
        }

        Button(
            onClick = { generateFromCapture() },
            enabled = !geocoding,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Generate Excel from Capture")
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
                Text("Share Excel")
            }
            Button(
                onClick = {
                    openFile(
                        lastFile!!,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Excel")
            }
        }

        HorizontalDivider()

        Text("Kkböj (tap to include)", style = MaterialTheme.typography.titleMedium)
        Text(
            "${activeFixedIds.size} selected · Info = kkböj",
            style = MaterialTheme.typography.bodySmall
        )

        FixedStops.sections.filter { it in visibleSections }.forEach { section ->
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
            enabled = stopCount > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear captured stops")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
