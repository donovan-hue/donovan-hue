package com.hifiplayer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hifiplayer.app.BuildConfig
import com.hifiplayer.HiFiPlayerApp
import com.hifiplayer.core.designsystem.theme.HiFiTheme
import com.hifiplayer.domain.model.settings.ThemeMode
import com.hifiplayer.presentation.navigation.HiFiApp
import com.hifiplayer.presentation.settings.AboutInfo
import com.hifiplayer.presentation.settings.ThemeViewModel

/**
 * How much of the specification is implemented, shown in About.
 *
 * These two numbers are written by hand on purpose: they are a statement about the project, not a
 * runtime fact, and the About screen says so. Getting them from the code would be guessing.
 */
private const val PHASES_DONE = 15
private const val PHASES_TOTAL = 17

/** Automated tests across the whole project; CI prints the exact number on every run. */
private const val TEST_COUNT = 83

/**
 * Single activity (requirement: the UI is Compose + one host).
 *
 * The activity owns no state of its own: after a rotation or a process restart the ViewModels
 * re-read playback from the engine and the settings from DataStore, so nothing is lost and nothing
 * is duplicated here. Even the theme is read from the settings through its own ViewModel, so this
 * class never touches a repository.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as HiFiPlayerApp).graph
        val about = AboutInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            phasesDone = PHASES_DONE,
            phasesTotal = PHASES_TOTAL,
            testCount = TEST_COUNT,
            buildType = BuildConfig.BUILD_TYPE,
        )
        setContent {
            // The appearance setting drives the theme; the product default is dark.
            val themeViewModel: ThemeViewModel = viewModel(
                factory = viewModelFactory { initializer { graph.themeViewModel() } },
            )
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                else -> true
            }
            HiFiTheme(darkTheme = darkTheme) {
                HiFiApp(
                    createPlayerViewModel = graph::nowPlayingViewModel,
                    createQueueViewModel = graph::queueViewModel,
                    createHomeViewModel = graph::homeViewModel,
                    createBrowseViewModel = graph::browseViewModel,
                    createSearchViewModel = graph::searchViewModel,
                    createPlaylistsViewModel = graph::playlistsViewModel,
                    createPlaylistDetailViewModel = graph::playlistDetailViewModel,
                    createCollectionViewModel = graph::collectionViewModel,
                    createAdminViewModel = graph::libraryAdminViewModel,
                    createPickerViewModel = graph::playlistPickerViewModel,
                    createSettingsViewModel = graph::settingsViewModel,
                    createAudioSettingsViewModel = graph::audioSettingsViewModel,
                    createDspViewModel = graph::dspViewModel,
                    createAudioInfoViewModel = graph::audioInfoViewModel,
                    aboutInfo = about,
                    artworkLoader = graph.artworkLoader,
                )
            }
        }
    }
}
