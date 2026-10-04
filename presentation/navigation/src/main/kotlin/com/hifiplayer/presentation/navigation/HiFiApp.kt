package com.hifiplayer.presentation.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hifiplayer.domain.model.library.SearchHit
import com.hifiplayer.domain.model.library.SearchHitType
import com.hifiplayer.presentation.library.admin.LibraryAdminViewModel
import com.hifiplayer.presentation.library.artwork.ArtworkLoader
import com.hifiplayer.presentation.library.artwork.LocalArtworkLoader
import com.hifiplayer.presentation.library.browse.BrowseScreen
import com.hifiplayer.presentation.library.browse.BrowseViewModel
import com.hifiplayer.presentation.library.collection.CollectionScreen
import com.hifiplayer.presentation.library.collection.CollectionViewModel
import com.hifiplayer.presentation.library.common.PlaylistPickerActions
import com.hifiplayer.presentation.library.common.ScreenBanner
import com.hifiplayer.presentation.library.common.TrackInteractionState
import com.hifiplayer.presentation.library.common.rememberTrackInteractionState
import com.hifiplayer.presentation.library.home.HomeScreen
import com.hifiplayer.presentation.library.home.HomeViewModel
import com.hifiplayer.presentation.library.playlists.PlaylistDetailScreen
import com.hifiplayer.presentation.library.playlists.PlaylistDetailViewModel
import com.hifiplayer.presentation.library.playlists.PlaylistPickerViewModel
import com.hifiplayer.presentation.library.playlists.PlaylistsScreen
import com.hifiplayer.presentation.library.playlists.PlaylistsViewModel
import com.hifiplayer.presentation.library.search.SearchScreen
import com.hifiplayer.presentation.library.search.SearchViewModel
import com.hifiplayer.presentation.playback.MiniPlayer
import com.hifiplayer.presentation.playback.audio.AudioInfoScreen
import com.hifiplayer.presentation.playback.audio.AudioInfoViewModel
import com.hifiplayer.presentation.settings.AboutInfo
import com.hifiplayer.presentation.settings.SettingsScreen
import com.hifiplayer.presentation.settings.SettingsViewModel
import com.hifiplayer.presentation.settings.audio.AudioSettingsViewModel
import com.hifiplayer.presentation.settings.audio.BitPerfectScreen
import com.hifiplayer.presentation.settings.audio.OutputDevicesScreen
import com.hifiplayer.presentation.settings.dsp.CrossfeedScreen
import com.hifiplayer.presentation.settings.dsp.DspViewModel
import com.hifiplayer.presentation.settings.dsp.EqScreen
import com.hifiplayer.presentation.settings.dsp.ReplayGainScreen
import com.hifiplayer.presentation.playback.NowPlayingScreen
import com.hifiplayer.presentation.playback.NowPlayingViewModel
import com.hifiplayer.presentation.playback.QueueScreen
import com.hifiplayer.presentation.playback.QueueViewModel

/**
 * Application shell: bottom tabs, the mini player that sits above them, and the detail screens pushed
 * over them (requirements 24 to 26).
 *
 * ViewModels of the four tabs live here, so switching tabs keeps their state; the detail screens
 * create theirs inside their own back stack entry, so closing them really closes them. One
 * [NowPlayingViewModel] drives the mini player and the full screen: the artwork is loaded once and
 * both surfaces can never disagree about what is playing.
 */
@Composable
fun HiFiApp(
    createPlayerViewModel: () -> NowPlayingViewModel,
    createQueueViewModel: () -> QueueViewModel,
    createHomeViewModel: () -> HomeViewModel,
    createBrowseViewModel: () -> BrowseViewModel,
    createSearchViewModel: () -> SearchViewModel,
    createPlaylistsViewModel: () -> PlaylistsViewModel,
    createPlaylistDetailViewModel: (String) -> PlaylistDetailViewModel,
    createCollectionViewModel: (CollectionArgs) -> CollectionViewModel,
    createAdminViewModel: () -> LibraryAdminViewModel,
    createPickerViewModel: () -> PlaylistPickerViewModel,
    createSettingsViewModel: () -> SettingsViewModel,
    createAudioSettingsViewModel: () -> AudioSettingsViewModel,
    createDspViewModel: () -> DspViewModel,
    createAudioInfoViewModel: () -> AudioInfoViewModel,
    aboutInfo: AboutInfo,
    artworkLoader: ArtworkLoader?,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val playerViewModel: NowPlayingViewModel = viewModel(
        factory = viewModelFactory { initializer { createPlayerViewModel() } },
    )
    // `visuals` deliberately excludes the moving playhead: the shell, the mini player and every
    // screen below only redraw when the track or a control changes, not twice per second.
    val playerState by playerViewModel.visuals.collectAsStateWithLifecycle()
    // Collected (not read): the value is touched inside the two controls that display it, so a new
    // playhead position only redraws those two and never the shell.
    val playheadProgress = playerViewModel.progress.collectAsStateWithLifecycle()
    val playheadPositionMs = playerViewModel.positionMs.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // App-level actions: adding a music folder is a platform dialog, so the launcher lives here and
    // its result lands in the admin ViewModel, which reports success or failure on the active screen.
    val adminViewModel: LibraryAdminViewModel = viewModel(
        factory = viewModelFactory { initializer { createAdminViewModel() } },
    )
    val adminState by adminViewModel.state.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { adminViewModel.onFolderPicked(it.toString()) }
    }

    // The playlist picker is offered from every screen that lists tracks, so it is fed once.
    val pickerViewModel: PlaylistPickerViewModel = viewModel(
        factory = viewModelFactory { initializer { createPickerViewModel() } },
    )
    val pickerState by pickerViewModel.state.collectAsStateWithLifecycle()
    val pickerActions = remember(pickerState.playlists) {
        PlaylistPickerActions(
            playlists = pickerState.playlists,
            onAdd = pickerViewModel::onAdd,
            onCreate = pickerViewModel::onCreateAndAdd,
        )
    }

    val banner = ScreenBanner(
        message = adminState.message ?: pickerState.message,
        isError = if (adminState.message != null) adminState.messageIsError else pickerState.messageIsError,
        onDismiss = {
            adminViewModel.onDismissMessage()
            pickerViewModel.onDismissMessage()
        },
    )

    val openCollection: (CollectionArgs) -> Unit = { args ->
        navController.navigate(CollectionRoute.route(args)) { launchSingleTop = true }
    }
    val openPlaylist: (String) -> Unit = { playlistId ->
        navController.navigate("playlist/$playlistId") { launchSingleTop = true }
    }
    val openAudioInfo: () -> Unit = { navController.navigate(HiFiDestination.AudioInfo.route) { launchSingleTop = true } }
    val openOutputs: () -> Unit = { navController.navigate(HiFiDestination.OutputDevices.route) { launchSingleTop = true } }
    val openBitPerfect: () -> Unit = { navController.navigate(HiFiDestination.BitPerfect.route) { launchSingleTop = true } }
    val openEq: () -> Unit = { navController.navigate(HiFiDestination.Eq.route) { launchSingleTop = true } }
    val openReplayGain: () -> Unit = { navController.navigate(HiFiDestination.ReplayGain.route) { launchSingleTop = true } }
    val openCrossfeed: () -> Unit = { navController.navigate(HiFiDestination.Crossfeed.route) { launchSingleTop = true } }

    val openSearch: () -> Unit = {
        navController.navigate(HiFiDestination.Search.route) { launchSingleTop = true }
    }

    /**
     * Where a search result takes the user.
     *
     * A track hit never arrives here: the search ViewModel plays it instead, because playing is what
     * tapping a track means. The rest open the screen that owns the data.
     */
    val openHitDestination: (SearchHit) -> Unit = { hit ->
        when (hit.type) {
            SearchHitType.TRACK -> Unit
            SearchHitType.PLAYLIST -> openPlaylist(hit.id)
            SearchHitType.ALBUM -> openCollection(
                CollectionArgs(CollectionKind.ALBUM, hit.id, hit.title, hit.subtitle),
            )
            SearchHitType.ARTIST -> openCollection(
                CollectionArgs(CollectionKind.ARTIST, hit.id, hit.title, hit.subtitle),
            )
            SearchHitType.GENRE -> openCollection(
                CollectionArgs(CollectionKind.GENRE, hit.id, hit.title, hit.subtitle),
            )
            SearchHitType.FOLDER -> openCollection(
                CollectionArgs(CollectionKind.FOLDER, hit.id, hit.title, hit.subtitle),
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Column {
                if (currentRoute !in HiFiDestination.fullScreen) {
                    MiniPlayer(
                        state = playerState,
                        progress = { playheadProgress.value },
                        onTogglePlayPause = playerViewModel::onTogglePlayPause,
                        onNext = playerViewModel::onNext,
                        onOpen = {
                            navController.navigate(HiFiDestination.NowPlaying.route) { launchSingleTop = true }
                        },
                    )
                }
                if (currentRoute in HiFiDestination.tabRoutes) {
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
            }
        },
    ) { innerPadding ->
        CompositionLocalProvider(LocalArtworkLoader provides artworkLoader) {
            NavHost(
                navController = navController,
                startDestination = HiFiDestination.Home.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(HiFiDestination.Home.route) {
                    val viewModel: HomeViewModel = viewModel(
                        factory = viewModelFactory { initializer { createHomeViewModel() } },
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val interaction = rememberTrackInteractionState()
                    HomeScreen(
                        state = state,
                        interaction = interaction,
                        pickerActions = pickerActions,
                        banner = banner,
                        onPlaySection = viewModel::onPlayFromSection,
                        onPlayNext = viewModel::onPlayNext,
                        onAddToQueue = viewModel::onAddToQueue,
                        onToggleFavorite = viewModel::onToggleFavorite,
                        onRefresh = viewModel::onRefresh,
                        onOpenSearch = openSearch,
                        onOpenAlbum = { album ->
                            openCollection(
                                CollectionArgs(
                                    kind = CollectionKind.ALBUM,
                                    id = album.id,
                                    title = album.title,
                                    subtitle = albumSubtitle(album),
                                ),
                            )
                        },
                        onOpenFavorites = {
                            navController.navigate(HiFiDestination.Library.route) { launchSingleTop = true }
                        },
                        onAddFolder = { pickFolder.launch(null) },
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(HiFiDestination.Library.route) {
                    val viewModel: BrowseViewModel = viewModel(
                        factory = viewModelFactory { initializer { createBrowseViewModel() } },
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val interaction = rememberTrackInteractionState()
                    BrowseScreen(
                        state = state,
                        interaction = interaction,
                        pickerActions = pickerActions,
                        banner = banner,
                        onSelectTab = viewModel::onSelectTab,
                        onSelectSort = viewModel::onSelectSort,
                        onToggleFavoritesFilter = viewModel::onToggleFavoritesFilter,
                        onToggleLosslessFilter = viewModel::onToggleLosslessFilter,
                        onRefresh = viewModel::onRefresh,
                        onPlaySong = { track, visible -> viewModel.onPlaySong(track, visible) },
                        onPlayAll = { viewModel.onPlayAll(it) },
                        onPlayNext = viewModel::onPlayNext,
                        onAddToQueue = viewModel::onAddToQueue,
                        onToggleFavorite = viewModel::onToggleFavorite,
                        onOpenAlbum = { album ->
                            openCollection(
                                CollectionArgs(
                                    kind = CollectionKind.ALBUM,
                                    id = album.id,
                                    title = album.title,
                                    subtitle = albumSubtitle(album),
                                ),
                            )
                        },
                        onOpenArtist = { artist ->
                            openCollection(
                                CollectionArgs(
                                    kind = CollectionKind.ARTIST,
                                    id = artist.id,
                                    title = artist.name,
                                    subtitle = "${artist.trackCount} pistas · ${artist.albumCount} álbumes",
                                ),
                            )
                        },
                        onOpenGenre = { genre ->
                            openCollection(
                                CollectionArgs(
                                    kind = CollectionKind.GENRE,
                                    id = genre.name,
                                    title = genre.name,
                                    subtitle = if (genre.trackCount == 1) "1 pista" else "${genre.trackCount} pistas",
                                ),
                            )
                        },
                        onOpenFolder = { folder ->
                            openCollection(
                                CollectionArgs(
                                    kind = CollectionKind.FOLDER,
                                    id = folder.path,
                                    title = folder.displayName,
                                    subtitle = "${folder.trackCount} pistas",
                                ),
                            )
                        },
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(HiFiDestination.Playlists.route) {
                    val viewModel: PlaylistsViewModel = viewModel(
                        factory = viewModelFactory { initializer { createPlaylistsViewModel() } },
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    PlaylistsScreen(
                        state = state,
                        banner = banner,
                        onNewNameChange = viewModel::onNewNameChange,
                        onCreate = viewModel::onCreate,
                        onOpen = { openPlaylist(it.id) },
                        onPlay = viewModel::onPlay,
                        onStartRename = viewModel::onStartRename,
                        onRenameValueChange = viewModel::onRenameValueChange,
                        onConfirmRename = viewModel::onConfirmRename,
                        onCancelRename = viewModel::onCancelRename,
                        onRequestDelete = viewModel::onRequestDelete,
                        onCancelDelete = viewModel::onCancelDelete,
                        onConfirmDelete = viewModel::onConfirmDelete,
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(
                    route = HiFiDestination.PlaylistDetail.route,
                    arguments = listOf(navArgument(HiFiDestination.PlaylistDetail.PLAYLIST_ID) { type = NavType.StringType }),
                ) { entry ->
                    val playlistId = entry.arguments?.getString(HiFiDestination.PlaylistDetail.PLAYLIST_ID).orEmpty()
                    val viewModel: PlaylistDetailViewModel = viewModel(
                        key = playlistId,
                        factory = viewModelFactory { initializer { createPlaylistDetailViewModel(playlistId) } },
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val interaction = rememberTrackInteractionState()
                    PlaylistDetailScreen(
                        state = state,
                        interaction = interaction,
                        pickerActions = pickerActions,
                        banner = banner,
                        onBack = { navController.popBackStack() },
                        onPlayTrack = viewModel::onPlayTrack,
                        onPlayAll = viewModel::onPlayAll,
                        onShuffleAll = viewModel::onShuffleAll,
                        onAddAllToQueue = viewModel::onAddAllToQueue,
                        onPlayNext = viewModel::onPlayNext,
                        onAddToQueue = viewModel::onAddToQueue,
                        onToggleFavorite = viewModel::onToggleFavorite,
                        onMove = viewModel::onMove,
                        onRemoveRequested = viewModel::onRemoveRequested,
                        onRemoveCancelled = viewModel::onRemoveCancelled,
                        onRemoveConfirmed = viewModel::onRemoveConfirmed,
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(
                    route = HiFiDestination.Collection.route,
                    arguments = listOf(
                        navArgument(HiFiDestination.Collection.KIND) { type = NavType.StringType },
                        navArgument(HiFiDestination.Collection.ID) { type = NavType.StringType },
                        navArgument(HiFiDestination.Collection.TITLE) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument(HiFiDestination.Collection.SUBTITLE) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    val args = CollectionRoute.parse(entry)
                    val viewModel: CollectionViewModel = viewModel(
                        key = "${args.kind.key}:${args.id}",
                        factory = viewModelFactory { initializer { createCollectionViewModel(args) } },
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val interaction = rememberTrackInteractionState()
                    CollectionScreen(
                        state = state,
                        interaction = interaction,
                        pickerActions = pickerActions,
                        banner = banner,
                        onBack = { navController.popBackStack() },
                        onPlayTrack = viewModel::onPlayTrack,
                        onPlayAll = viewModel::onPlayAll,
                        onShuffleAll = viewModel::onShuffleAll,
                        onAddAllToQueue = viewModel::onAddAllToQueue,
                        onPlayNext = viewModel::onPlayNext,
                        onAddToQueue = viewModel::onAddToQueue,
                        onToggleFavorite = viewModel::onToggleFavorite,
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(HiFiDestination.Search.route) {
                    val viewModel: SearchViewModel = viewModel(
                        factory = viewModelFactory { initializer { createSearchViewModel() } },
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    SearchScreen(
                        state = state,
                        onTermChange = viewModel::onTermChange,
                        onSelectType = viewModel::onSelectType,
                        onOpenHit = { hit -> viewModel.onOpenHit(hit, openHitDestination) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(HiFiDestination.Settings.route) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = viewModelFactory { initializer { createSettingsViewModel() } },
                    )
                    val settingsState by viewModel.state.collectAsStateWithLifecycle()
                    SettingsScreen(
                        state = settingsState,
                        about = aboutInfo,
                        onOpenAudioInfo = openAudioInfo,
                        onOpenBitPerfect = openBitPerfect,
                        onOpenOutputDevices = openOutputs,
                        onOpenEq = openEq,
                        onOpenReplayGain = openReplayGain,
                        onOpenCrossfeed = openCrossfeed,
                        onAddFolder = { pickFolder.launch(null) },
                        onScanNow = adminViewModel::onRefresh,
                        onPruneMissing = adminViewModel::onPruneMissing,
                        onClearCache = viewModel::onClearCache,
                        onResetSettings = viewModel::onResetSettings,
                        onResamplePolicyChange = viewModel::onResamplePolicyChange,
                        onGaplessChange = viewModel::onGaplessChange,
                        onUsbAutoRouteChange = viewModel::onUsbAutoRouteChange,
                        onPauseOnDisconnectChange = viewModel::onPauseOnDisconnectChange,
                        onResumeOnStartChange = viewModel::onResumeOnStartChange,
                        onAutoPlayOnOpenChange = viewModel::onAutoPlayOnOpenChange,
                        onRepeatSettingChange = viewModel::onRepeatSettingChange,
                        onShuffleSettingChange = viewModel::onShuffleSettingChange,
                        onScanOnStartupChange = viewModel::onScanOnStartupChange,
                        onAutomaticScanningChange = viewModel::onAutomaticScanningChange,
                        onThemeModeChange = viewModel::onThemeModeChange,
                        onArtworkSizeChange = viewModel::onArtworkSizeChange,
                        onAnimationsChange = viewModel::onAnimationsChange,
                        onShowTechnicalInfoChange = viewModel::onShowTechnicalInfoChange,
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(HiFiDestination.AudioInfo.route) {
                    val viewModel: AudioInfoViewModel = viewModel(
                        factory = viewModelFactory { initializer { createAudioInfoViewModel() } },
                    )
                    val audioState by viewModel.state.collectAsStateWithLifecycle()
                    AudioInfoScreen(
                        state = audioState,
                        onBack = { navController.popBackStack() },
                        onRefresh = viewModel::onRefresh,
                        onVerifyCurrentTrack = viewModel::onVerifyCurrentTrack,
                        onOpenOutputDevices = openOutputs,
                        onOpenBitPerfect = openBitPerfect,
                        onOpenEq = openEq,
                        onOpenReplayGain = openReplayGain,
                        onOpenCrossfeed = openCrossfeed,
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(HiFiDestination.OutputDevices.route) {
                    val viewModel: AudioSettingsViewModel = viewModel(
                        factory = viewModelFactory { initializer { createAudioSettingsViewModel() } },
                    )
                    val deviceState by viewModel.state.collectAsStateWithLifecycle()
                    OutputDevicesScreen(
                        state = deviceState,
                        onBack = { navController.popBackStack() },
                        onRefresh = viewModel::onRefresh,
                        onSelectDevice = viewModel::onSelectDevice,
                        onUseSystemDefault = viewModel::onUseSystemDefault,
                        onRequestPermission = viewModel::onRequestUsbPermission,
                        onUsbAutoRouteChange = viewModel::onUsbAutoRouteChange,
                        onPauseOnDisconnectChange = viewModel::onPauseOnDisconnectChange,
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(HiFiDestination.BitPerfect.route) {
                    val viewModel: AudioSettingsViewModel = viewModel(
                        factory = viewModelFactory { initializer { createAudioSettingsViewModel() } },
                    )
                    val deviceState by viewModel.state.collectAsStateWithLifecycle()
                    BitPerfectScreen(
                        state = deviceState,
                        onBack = { navController.popBackStack() },
                        onBitPerfectChange = viewModel::onBitPerfectChange,
                        onOpenOutputs = openOutputs,
                        onDismissMessage = viewModel::onDismissMessage,
                    )
                }

                composable(HiFiDestination.Eq.route) {
                    val viewModel: DspViewModel = viewModel(
                        factory = viewModelFactory { initializer { createDspViewModel() } },
                    )
                    val dspState by viewModel.state.collectAsStateWithLifecycle()
                    EqScreen(
                        state = dspState,
                        onBack = { navController.popBackStack() },
                        onEnabledChange = viewModel::onEqEnabledChange,
                        onBandDrag = viewModel::onBandDrag,
                        onBandCommit = viewModel::onBandCommit,
                        onSelectPreset = viewModel::onSelectPreset,
                        onSavePreset = viewModel::onSavePreset,
                        onDeletePreset = viewModel::onDeletePreset,
                        onPreampChange = viewModel::onPreampChange,
                        onDismissMessage = viewModel::onDismissMessage,
                        onBitPerfectChange = viewModel::onBitPerfectChange,
                    )
                }

                composable(HiFiDestination.ReplayGain.route) {
                    val viewModel: DspViewModel = viewModel(
                        factory = viewModelFactory { initializer { createDspViewModel() } },
                    )
                    val dspState by viewModel.state.collectAsStateWithLifecycle()
                    ReplayGainScreen(
                        state = dspState,
                        onBack = { navController.popBackStack() },
                        onModeChange = viewModel::onReplayGainModeChange,
                        onPreampChange = viewModel::onReplayGainPreampChange,
                        onPreventClippingChange = viewModel::onPreventClippingChange,
                        onAlbumGainPreferenceChange = viewModel::onAlbumGainPreferenceChange,
                        onDismissMessage = viewModel::onDismissMessage,
                        onBitPerfectChange = viewModel::onBitPerfectChange,
                    )
                }

                composable(HiFiDestination.Crossfeed.route) {
                    val viewModel: DspViewModel = viewModel(
                        factory = viewModelFactory { initializer { createDspViewModel() } },
                    )
                    val dspState by viewModel.state.collectAsStateWithLifecycle()
                    CrossfeedScreen(
                        state = dspState,
                        onBack = { navController.popBackStack() },
                        onModeChange = viewModel::onCrossfeedChange,
                        onBalanceChange = viewModel::onBalanceChange,
                        onAppGainChange = viewModel::onAppGainChange,
                        onDismissMessage = viewModel::onDismissMessage,
                        onBitPerfectChange = viewModel::onBitPerfectChange,
                    )
                }

                composable(HiFiDestination.NowPlaying.route) {
                    NowPlayingScreen(
                        state = playerState,
                        progress = { playheadProgress.value },
                        positionMs = { playheadPositionMs.value },
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
                        onOpenAudioInfo = openAudioInfo,
                        onOpenEq = openEq,
                        onOpenReplayGain = openReplayGain,
                        onOpenCrossfeed = openCrossfeed,
                        onOpenOutputs = openOutputs,
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
}

/** Subtitle shown on an album's collection screen; the same wording as the album row. */
private fun albumSubtitle(album: com.hifiplayer.domain.model.library.Album): String = listOfNotNull(
    album.displayArtist,
    album.year?.toString(),
    "${album.trackCount} ${if (album.trackCount == 1) "pista" else "pistas"}",
    album.formatSummary.takeIf { it.isNotBlank() },
).joinToString(" · ")
