package com.majk.extractor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.majk.extractor.data.db.AddressStore
import com.majk.extractor.importing.ImageOcrImporter
import com.majk.extractor.ui.DatabaseScreen
import com.majk.extractor.ui.MainScreen
import com.majk.extractor.ui.theme.ExtractorTheme
import com.majk.extractor.vpn.CaptureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AddressStore.init(this)
        CaptureRepository.init(this)
        try {
            enableEdgeToEdge()
        } catch (_: Exception) {
        }
        setContent {
            ExtractorTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxSize()) {
                        TabRow(
                            selectedTabIndex = tab,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                            indicator = { positions ->
                                if (tab < positions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        Modifier.tabIndicatorOffset(positions[tab]),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        ) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                text = {
                                    Text(
                                        "Manifest",
                                        color = if (tab == 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            )
                            Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                text = {
                                    Text(
                                        "Database",
                                        color = if (tab == 1) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            )
                        }
                        if (tab == 0) MainScreen() else DatabaseScreen()
                    }
                }
            }
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (!type.startsWith("image/")) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    ImageOcrImporter.importUri(this@MainActivity, uri)
                }
                if (found.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "OCR found no Opter stops in image",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    CaptureRepository.mergeEnrich(found)
                    Toast.makeText(
                        this@MainActivity,
                        "Imported ${found.size} stops from image (total ${CaptureRepository.lastParseCount})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "OCR failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
