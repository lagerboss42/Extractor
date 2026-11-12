package com.majk.extractor

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.majk.extractor.data.db.AddressStore
import com.majk.extractor.data.prefs.AppPreferences
import com.majk.extractor.data.storage.UserDataStore
import com.majk.extractor.vpn.CaptureRepository
import com.majk.extractor.ui.DatabaseScreen
import com.majk.extractor.ui.MainScreen
import com.majk.extractor.ui.SettingsScreen
import com.majk.extractor.ui.theme.ExtractorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AddressStore.init(this)
        AppPreferences.init(this)
        UserDataStore.init(this)
        CaptureRepository.init(this)
        requestStorageAccess()
        try {
            enableEdgeToEdge()
        } catch (_: Exception) {
        }
        setContent {
            ExtractorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    var showSettings by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        if (showSettings) {
                            SettingsScreen(onBack = { showSettings = false })
                        } else {
                            Box(Modifier.fillMaxWidth()) {
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
                                IconButton(
                                    onClick = { showSettings = true },
                                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                            if (tab == 0) MainScreen() else DatabaseScreen()
                        }
                    }
                }
            }
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
            }
        }
    }
}
