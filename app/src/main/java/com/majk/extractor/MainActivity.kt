package com.majk.extractor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.majk.extractor.data.db.AddressStore
import com.majk.extractor.vpn.CaptureRepository
import com.majk.extractor.ui.DatabaseScreen
import com.majk.extractor.ui.MainScreen
import com.majk.extractor.ui.theme.ExtractorTheme

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
                    modifier = Modifier.fillMaxSize(),
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
    }
}
