package com.hifiplayer.presentation.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hifiplayer.presentation.playback.MiniPlayer
import com.hifiplayer.presentation.playback.NowPlayingScreen
import com.hifiplayer.presentation.playback.NowPlayingViewModel
import com.hifiplayer.presentation.playback.QueueScreen
import com.hifiplayer.presentation.playback.QueueViewModel

/**
 * Application shell: bottom tabs, the mini player that sits above them, and Now Playing opened over
 * everything (requirements 24 and 25).
 *
 * The single [NowPlayingViewModel] created here drives both the mini player and the full screen, so
 * the artwork is loaded once and both surfaces can never disagree about what is playing.
 */
@Composable
fun HiFiApp(
    createPlayerViewModel: () -> NowPlayingViewModel,
    createQueueViewModel: () -> QueueViewModel,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val playerViewModel: NowPlayingViewModel = viewModel(
        factory = viewModelFactory { initializer { createPlayerViewModel() } },
    )
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Column {
                val fullScreenRoutes = setOf(
                    HiFiDestination.NowPlaying.route,
                    HiFiDestination.Queue.route,
                )
                if (currentRoute !in fullScreenRoutes) {
                    MiniPlayer(
                        state = playerState,
                        onTogglePlayPause = playerViewModel::onTogglePlayPause,
                        onNext = playerViewModel::onNext,
                        onOpen = {
                            navController.navigate(HiFiDestination.NowPlaying.route) { launchSingleTop = true }
                        },
                    )
                }
                HiFiBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(HiFiDestination.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HiFiDestination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(HiFiDestination.Home.route) {
                PendingScreen(
                    title = "Inicio",
                    phase = "fase 7 · metadatos y artwork",
                    willContain = "Aquí vivirán Reproducido recientemente, Añadido recientemente, Favoritos y Álbumes, " +
                        "cada uno alimentado por la biblioteca real ya escaneada.",
                )
            }
            composable(HiFiDestination.Library.route) {
                PendingScreen(
                    title = "Biblioteca",
                    phase = "fase 5 · cola y biblioteca en pantalla",
                    willContain = "Canciones, Álbumes, Artistas, Géneros y Carpetas con orden y búsqueda " +
                        "sobre los datos que ya están en Room.",
                )
            }
            composable(HiFiDestination.Playlists.route) {
                PendingScreen(
                    title = "Listas de reproducción",
                    phase = "fase 6 · listas",
                    willContain = "Crear, renombrar, borrar listas, añadir y quitar pistas y reordenarlas " +
                        "arrastrando, con persistencia en Room.",
                )
            }
            composable(HiFiDestination.Settings.route) {
                PendingScreen(
                    title = "Ajustes",
                    phase = "fases 9-13 · audio, USB, bit-perfect, ReplayGain, EQ y crossfeed",
                    willContain = "Los grupos Audio, Reproducción, Biblioteca, Apariencia, Almacenamiento y Acerca de, " +
                        "conectados a la configuración real.",
                )
            }
            composable(HiFiDestination.NowPlaying.route) {
                NowPlayingScreen(
                    state = playerState,
                    onBack = { navController.popBackStack() },
                    onTogglePlayPause = playerViewModel::onTogglePlayPause,
                    onNext = playerViewModel::onNext,
                    onPrevious = playerViewModel::onPrevious,
                    onShuffleToggle = playerViewModel::onShuffleToggle,
                    onRepeatCycle = playerViewModel::onRepeatCycle,
                    onScrubStart = playerViewModel::onScrubStart,
                    onScrubChange = playerViewModel::onScrubChange,
                    onScrubFinish = playerViewModel::onScrubFinish,
                    onDismissMessage = playerViewModel::onDismissMessage,
                    onOpenQueue = {
                        navController.navigate(HiFiDestination.Queue.route) { launchSingleTop = true }
                    },
                )
            }
            composable(HiFiDestination.Queue.route) {
                // Scoped to this back stack entry: the queue screen stops observing when it leaves,
                // and the queue itself lives in the engine, not in this ViewModel.
                val queueViewModel: QueueViewModel = viewModel(
                    factory = viewModelFactory { initializer { createQueueViewModel() } },
                )
                val queueState by queueViewModel.state.collectAsStateWithLifecycle()
                QueueScreen(
                    state = queueState,
                    onBack = { navController.popBackStack() },
                    onPlayIndex = queueViewModel::onPlayIndex,
                    onRemove = queueViewModel::onRemove,
                    onMove = queueViewModel::onMove,
                    onClearRequested = queueViewModel::onClearRequested,
                    onClearCancelled = queueViewModel::onClearCancelled,
                    onClearConfirmed = queueViewModel::onClearConfirmed,
                    onSave = queueViewModel::onSave,
                    onRestore = queueViewModel::onRestore,
                    onDismissMessage = queueViewModel::onDismissMessage,
                )
            }
        }
    }
}
