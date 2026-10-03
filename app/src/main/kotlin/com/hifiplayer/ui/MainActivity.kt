package com.hifiplayer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hifiplayer.HiFiPlayerApp
import com.hifiplayer.core.designsystem.theme.HiFiTheme
import com.hifiplayer.presentation.navigation.HiFiApp

/**
 * Single activity (requirement: the UI is Compose + one host).
 *
 * The activity owns no state of its own: after a rotation or a process restart the ViewModels
 * re-read playback from the engine and the settings from DataStore, so nothing is lost and nothing
 * is duplicated here.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as HiFiPlayerApp).graph
        setContent {
            // Appearance settings will drive this in the settings phase; the product default is dark.
            HiFiTheme(darkTheme = true) {
                HiFiApp(createPlayerViewModel = graph::nowPlayingViewModel)
            }
        }
    }
}
