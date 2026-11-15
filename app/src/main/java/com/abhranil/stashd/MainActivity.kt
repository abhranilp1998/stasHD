package com.abhranil.stashd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.abhranil.stashd.ui.nav.StashdNavHost
import com.abhranil.stashd.ui.theme.StashdTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Dark is the primary look; a light/system option comes with settings (DataStore) later.
            StashdTheme(darkTheme = true) {
                StashdNavHost()
            }
        }
    }
}
