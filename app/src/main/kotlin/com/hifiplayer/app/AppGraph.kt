package com.hifiplayer.app

import android.content.Context
import com.hifiplayer.core.audio.AudioCapabilitiesManager
import com.hifiplayer.core.audio.AudioDeviceMonitor
import com.hifiplayer.core.audio.BitPerfectController
import com.hifiplayer.core.common.coroutines.DefaultDispatcherProvider
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.core.metadata.MetadataReader
import com.hifiplayer.core.storage.FileAccess
import com.hifiplayer.core.storage.SafFolderSource
import com.hifiplayer.core.usb.UsbAudioDeviceScanner
import com.hifiplayer.core.usb.UsbPermissionRequester
import com.hifiplayer.data.audio.PlaybackRepositoryImpl
import com.hifiplayer.data.audio.service.PlaybackServiceDependencies
import com.hifiplayer.data.local.settings.SettingsRepositoryImpl
import com.hifiplayer.data.metadata.ArtworkRepositoryImpl
import com.hifiplayer.data.metadata.MetadataRepositoryImpl
import com.hifiplayer.data.repository.devices.AudioDeviceRepositoryImpl
import com.hifiplayer.data.repository.library.FavoriteRepositoryImpl
import com.hifiplayer.data.repository.library.LibraryScanner
import com.hifiplayer.data.repository.library.MusicRepositoryImpl
import com.hifiplayer.data.repository.library.PlaylistRepositoryImpl
import com.hifiplayer.data.repository.library.QueueRepositoryImpl
import com.hifiplayer.data.repository.library.SearchRepositoryImpl
import com.hifiplayer.data.repository.local.DatabaseProvisioning
import com.hifiplayer.domain.repository.ArtworkRepository
import com.hifiplayer.domain.repository.AudioDeviceRepository
import com.hifiplayer.domain.repository.FavoriteRepository
import com.hifiplayer.domain.repository.MetadataRepository
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.repository.PlaybackRepository
import com.hifiplayer.domain.repository.PlaylistRepository
import com.hifiplayer.domain.repository.QueueRepository
import com.hifiplayer.domain.repository.SearchRepository
import com.hifiplayer.domain.repository.SettingsRepository
import com.hifiplayer.domain.usecase.favorites.GetFavoriteTracksUseCase
import com.hifiplayer.domain.usecase.favorites.ObserveFavoriteIdsUseCase
import com.hifiplayer.domain.usecase.favorites.ToggleFavoriteUseCase
import com.hifiplayer.domain.usecase.library.AddMusicFolderUseCase
import com.hifiplayer.domain.usecase.library.CancelScanUseCase
import com.hifiplayer.domain.usecase.library.GetAlbumsUseCase
import com.hifiplayer.domain.usecase.library.GetArtistsUseCase
import com.hifiplayer.domain.usecase.library.GetFoldersUseCase
import com.hifiplayer.domain.usecase.library.GetGenresUseCase
import com.hifiplayer.domain.usecase.library.GetLibraryStatsUseCase
import com.hifiplayer.domain.usecase.library.GetRecentlyAddedUseCase
import com.hifiplayer.domain.usecase.library.GetRecentlyPlayedUseCase
import com.hifiplayer.domain.usecase.library.GetTracksUseCase
import com.hifiplayer.domain.usecase.library.LoadArtworkBytesUseCase
import com.hifiplayer.domain.usecase.library.PruneMissingTracksUseCase
import com.hifiplayer.domain.usecase.audio.GetCapabilityReportUseCase
import com.hifiplayer.domain.usecase.audio.GetDecoderSupportUseCase
import com.hifiplayer.domain.usecase.audio.ObserveActiveOutputUseCase
import com.hifiplayer.domain.usecase.audio.ObserveAudioCapabilitiesUseCase
import com.hifiplayer.domain.usecase.audio.ObserveBitPerfectStateUseCase
import com.hifiplayer.domain.usecase.audio.ObserveOutputDevicesUseCase
import com.hifiplayer.domain.usecase.audio.RefreshOutputsUseCase
import com.hifiplayer.domain.usecase.audio.RequestUsbPermissionUseCase
import com.hifiplayer.domain.usecase.audio.SelectOutputDeviceUseCase
import com.hifiplayer.domain.usecase.audio.UseSystemDefaultOutputUseCase
import com.hifiplayer.domain.usecase.audio.VerifyOutputUseCase
import com.hifiplayer.domain.usecase.dsp.DeleteEqPresetUseCase
import com.hifiplayer.domain.usecase.dsp.SaveEqPresetUseCase
import com.hifiplayer.domain.usecase.dsp.SetAppGainUseCase
import com.hifiplayer.domain.usecase.dsp.SetBalanceUseCase
import com.hifiplayer.domain.usecase.dsp.SetCrossfeedUseCase
import com.hifiplayer.domain.usecase.dsp.SetEqEnabledUseCase
import com.hifiplayer.domain.usecase.dsp.SetEqPresetUseCase
import com.hifiplayer.domain.usecase.dsp.SetPreampUseCase
import com.hifiplayer.domain.usecase.dsp.SetReplayGainModeUseCase
import com.hifiplayer.domain.usecase.dsp.UpdateEqBandUseCase
import com.hifiplayer.domain.usecase.dsp.UpdateReplayGainSettingsUseCase
import com.hifiplayer.domain.usecase.library.RefreshLibraryUseCase
import com.hifiplayer.domain.usecase.settings.ClearCacheUseCase
import com.hifiplayer.domain.usecase.settings.GetCacheSizeUseCase
import com.hifiplayer.domain.usecase.settings.ResetSettingsUseCase
import com.hifiplayer.domain.usecase.settings.SetAnimationsEnabledUseCase
import com.hifiplayer.domain.usecase.settings.SetArtworkSizeUseCase
import com.hifiplayer.domain.usecase.settings.SetAutoPlayOnOpenUseCase
import com.hifiplayer.domain.usecase.settings.SetAutomaticScanningUseCase
import com.hifiplayer.domain.usecase.settings.SetBitPerfectUseCase
import com.hifiplayer.domain.usecase.settings.SetGaplessUseCase
import com.hifiplayer.domain.usecase.settings.SetPauseOnOutputDisconnectUseCase
import com.hifiplayer.domain.usecase.settings.SetRepeatSettingUseCase
import com.hifiplayer.domain.usecase.settings.SetResamplePolicyUseCase
import com.hifiplayer.domain.usecase.settings.SetResumeOnStartUseCase
import com.hifiplayer.domain.usecase.settings.SetShuffleSettingUseCase
import com.hifiplayer.domain.usecase.settings.SetScanOnStartupUseCase
import com.hifiplayer.domain.usecase.settings.SetShowTechnicalInfoInListsUseCase
import com.hifiplayer.domain.usecase.settings.SetThemeModeUseCase
import com.hifiplayer.domain.usecase.settings.SetUsbAutoRouteUseCase
import com.hifiplayer.domain.usecase.library.SearchLibraryUseCase
import com.hifiplayer.domain.usecase.playback.PlayTrackUseCase
import com.hifiplayer.domain.usecase.playback.PlayTracksUseCase
import com.hifiplayer.domain.usecase.playlists.AddTrackToPlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.CreatePlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.DeletePlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.GetPlaylistTracksUseCase
import com.hifiplayer.domain.usecase.playlists.GetPlaylistsUseCase
import com.hifiplayer.domain.usecase.playlists.MovePlaylistTrackUseCase
import com.hifiplayer.domain.usecase.playlists.PlayPlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.RemoveTrackFromPlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.RenamePlaylistUseCase
import com.hifiplayer.domain.usecase.queue.AddToQueueUseCase
import com.hifiplayer.domain.usecase.queue.AddTracksToQueueUseCase
import com.hifiplayer.domain.usecase.queue.ClearQueueUseCase
import com.hifiplayer.domain.usecase.queue.PlayNextUseCase
import com.hifiplayer.domain.usecase.queue.GetSavedQueueUseCase
import com.hifiplayer.domain.usecase.queue.MoveQueueItemUseCase
import com.hifiplayer.domain.usecase.queue.PlayQueueIndexUseCase
import com.hifiplayer.domain.usecase.queue.RemoveFromQueueUseCase
import com.hifiplayer.domain.usecase.library.ResolveTrackArtworkUseCase
import com.hifiplayer.domain.usecase.playback.NextTrackUseCase
import com.hifiplayer.domain.usecase.playback.ObservePlaybackStateUseCase
import com.hifiplayer.domain.usecase.playback.PreviousTrackUseCase
import com.hifiplayer.domain.usecase.playback.RestoreQueueUseCase
import com.hifiplayer.domain.usecase.playback.SaveQueueUseCase
import com.hifiplayer.domain.usecase.playback.SeekUseCase
import com.hifiplayer.domain.usecase.playback.SetRepeatModeUseCase
import com.hifiplayer.domain.usecase.playback.SetShuffleUseCase
import com.hifiplayer.domain.usecase.playback.TogglePlayPauseUseCase
import com.hifiplayer.domain.usecase.settings.ObserveSettingsUseCase
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.model.library.SortDirection
import com.hifiplayer.domain.model.library.TrackQuery
import com.hifiplayer.domain.model.library.TrackSort
import com.hifiplayer.nativeaudio.engine.AudioEngine
import com.hifiplayer.nativeaudio.engine.Media3AudioEngine
import com.hifiplayer.presentation.library.admin.LibraryAdminViewModel
import com.hifiplayer.presentation.library.artwork.ArtworkLoader
import com.hifiplayer.presentation.library.artwork.LocalArtworkLoaderImpl
import com.hifiplayer.presentation.library.browse.BrowseViewModel
import com.hifiplayer.presentation.library.collection.CollectionViewModel
import com.hifiplayer.presentation.library.home.HomeViewModel
import com.hifiplayer.presentation.library.playlists.PlaylistDetailViewModel
import com.hifiplayer.presentation.library.playlists.PlaylistPickerViewModel
import com.hifiplayer.presentation.library.playlists.PlaylistsViewModel
import com.hifiplayer.presentation.library.search.SearchViewModel
import com.hifiplayer.presentation.playback.audio.AudioInfoViewModel
import com.hifiplayer.presentation.settings.SettingsViewModel
import com.hifiplayer.presentation.settings.ThemeViewModel
import com.hifiplayer.presentation.settings.audio.AudioSettingsViewModel
import com.hifiplayer.presentation.settings.dsp.DspViewModel
import com.hifiplayer.presentation.navigation.CollectionArgs
import com.hifiplayer.presentation.navigation.CollectionKind
import com.hifiplayer.presentation.playback.NowPlayingViewModel
import com.hifiplayer.presentation.playback.QueueViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The single composition root (requirement 34 and Rule 48).
 *
 * It is written by hand instead of with a DI framework because the wiring is small, explicit and
 * has to happen in a defined order: the engine must be built on the main thread, and the playback
 * repository watches the settings and the audio devices, so both must already exist when it is
 * constructed.
 *
 * Nothing else in the app constructs a repository: the UI receives ViewModels, the service receives
 * the engine and the playback repository, and tests build the same pieces with fakes.
 */
class AppGraph(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Outlives any single screen: playback, scans and device monitoring share it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()

    val timeProvider: TimeProvider = TimeProvider.System

    val settings: SettingsRepository = SettingsRepositoryImpl(appContext, appScope, timeProvider)

    private val provisioning = DatabaseProvisioning(appContext)

    private val fileAccess = FileAccess(appContext)

    private val metadataReader = MetadataReader(fileAccess)

    private val safFolderSource = SafFolderSource(appContext)

    private val libraryScanner = LibraryScanner(
        context = appContext,
        trackDao = provisioning.tracks,
        structureDao = provisioning.structure,
        sourceDao = provisioning.sources,
        metadataReader = metadataReader,
        fileAccess = fileAccess,
        dispatchers = dispatchers,
        timeProvider = timeProvider,
    )

    val music: MusicRepository = MusicRepositoryImpl(
        context = appContext,
        provisioning = provisioning,
        scanner = libraryScanner,
        settingsRepository = settings,
        fileAccess = fileAccess,
        dispatchers = dispatchers,
        timeProvider = timeProvider,
    )

    val playlists: PlaylistRepository = PlaylistRepositoryImpl(provisioning, dispatchers, timeProvider)

    val favorites: FavoriteRepository = FavoriteRepositoryImpl(provisioning, dispatchers, timeProvider)

    val queue: QueueRepository = QueueRepositoryImpl(provisioning, dispatchers, timeProvider)

    val search: SearchRepository = SearchRepositoryImpl(provisioning, dispatchers, timeProvider)

    val metadata: MetadataRepository = MetadataRepositoryImpl(fileAccess, metadataReader, dispatchers)

    val artwork: ArtworkRepository = ArtworkRepositoryImpl(
        context = appContext,
        fileAccess = fileAccess,
        reader = metadataReader,
        safFolderSource = safFolderSource,
        dispatchers = dispatchers,
    )

    private val usbScanner = UsbAudioDeviceScanner(appContext)

    private val capabilitiesManager = AudioCapabilitiesManager(appContext, timeProvider)

    val audioDevices: AudioDeviceRepository = AudioDeviceRepositoryImpl(
        context = appContext,
        capabilitiesManager = capabilitiesManager,
        monitor = AudioDeviceMonitor(appContext, capabilitiesManager, usbScanner),
        bitPerfectController = BitPerfectController(appContext),
        usbScanner = usbScanner,
        usbPermissionRequester = UsbPermissionRequester(appContext),
        settingsRepository = settings,
        dispatchers = dispatchers,
        scope = appScope,
        timeProvider = timeProvider,
    )

    /**
     * Built here, on the main thread, because ExoPlayer requires a Looper it can pin itself to.
     * [AppGraph] is constructed from `Application.onCreate()`, which always runs on the main thread.
     */
    val engine: AudioEngine = Media3AudioEngine(
        context = appContext,
        scope = appScope,
        mainDispatcher = Dispatchers.Main.immediate,
    )

    val playback: PlaybackRepository = PlaybackRepositoryImpl(
        engine = engine,
        queueRepository = queue,
        musicRepository = music,
        settingsRepository = settings,
        audioDeviceRepository = audioDevices,
        scope = appScope,
        dispatchers = dispatchers,
        timeProvider = timeProvider,
    )

    /** What the playback service needs; it must not build repositories of its own. */
    val serviceDependencies: PlaybackServiceDependencies = object : PlaybackServiceDependencies {
        override val engine: AudioEngine get() = this@AppGraph.engine
        override val playbackRepository: PlaybackRepository get() = this@AppGraph.playback
    }

    /** ViewModels are created by the graph so screens never touch a repository (requirement 1). */
    fun nowPlayingViewModel(): NowPlayingViewModel = NowPlayingViewModel(
        observePlaybackState = ObservePlaybackStateUseCase(playback),
        observeSettings = ObserveSettingsUseCase(settings),
        togglePlayPause = TogglePlayPauseUseCase(playback),
        nextTrack = NextTrackUseCase(playback),
        previousTrack = PreviousTrackUseCase(playback),
        seek = SeekUseCase(playback),
        setShuffle = SetShuffleUseCase(playback),
        setRepeatMode = SetRepeatModeUseCase(playback),
        resolveArtwork = ResolveTrackArtworkUseCase(artwork),
        loadArtworkBytes = LoadArtworkBytesUseCase(artwork),
        dispatchers = dispatchers,
    )

    /** Queue / Up Next screen (phase 5): all actions end in real engine operations. */
    fun queueViewModel(): QueueViewModel = QueueViewModel(
        observePlaybackState = ObservePlaybackStateUseCase(playback),
        playQueueIndex = PlayQueueIndexUseCase(playback),
        removeFromQueue = RemoveFromQueueUseCase(playback),
        moveQueueItem = MoveQueueItemUseCase(playback),
        clearQueue = ClearQueueUseCase(playback),
        saveQueue = SaveQueueUseCase(playback),
        restoreQueue = RestoreQueueUseCase(playback),
        getSavedQueue = GetSavedQueueUseCase(queue),
        timeProvider = timeProvider,
        dispatchers = dispatchers,
    )

    // ---------------------------------------------------------------- biblioteca (fases 6 y 7)

    /** Home: the shelves the user lands on (requirement 24). */
    fun homeViewModel(): HomeViewModel = HomeViewModel(
        musicRepository = music,
        getRecentlyPlayed = GetRecentlyPlayedUseCase(music),
        getRecentlyAdded = GetRecentlyAddedUseCase(music),
        getFavorites = GetFavoriteTracksUseCase(favorites),
        getAlbums = GetAlbumsUseCase(music),
        getStats = GetLibraryStatsUseCase(music),
        observeFavoriteIds = ObserveFavoriteIdsUseCase(favorites),
        toggleFavorite = ToggleFavoriteUseCase(favorites),
        refreshLibrary = RefreshLibraryUseCase(music),
        playTracks = PlayTracksUseCase(playback),
        playNext = PlayNextUseCase(playback),
        addToQueue = AddToQueueUseCase(playback),
        addTracksToQueue = AddTracksToQueueUseCase(playback),
        dispatchers = dispatchers,
    )

    /** Library browsing: songs, albums, artists, genres and folders (requirement 25). */
    fun browseViewModel(): BrowseViewModel = BrowseViewModel(
        musicRepository = music,
        getTracks = GetTracksUseCase(music),
        getAlbums = GetAlbumsUseCase(music),
        getArtists = GetArtistsUseCase(music),
        getGenres = GetGenresUseCase(music),
        getFolders = GetFoldersUseCase(music),
        getStats = GetLibraryStatsUseCase(music),
        observeFavoriteIds = ObserveFavoriteIdsUseCase(favorites),
        toggleFavorite = ToggleFavoriteUseCase(favorites),
        refreshLibrary = RefreshLibraryUseCase(music),
        playTracks = PlayTracksUseCase(playback),
        playNext = PlayNextUseCase(playback),
        addToQueue = AddToQueueUseCase(playback),
    )

    /**
     * One collection screen for albums, artists, genres and folders.
     *
     * The query is built here from the navigation arguments because the repository is what knows how
     * to translate a filter into SQL, and the screens only know what to display.
     */
    fun collectionViewModel(args: CollectionArgs): CollectionViewModel {
        val query = when (args.kind) {
            CollectionKind.ALBUM -> TrackQuery(albumId = args.id)
            CollectionKind.ARTIST -> TrackQuery(artistId = args.id)
            CollectionKind.GENRE -> TrackQuery(genre = args.id)
            CollectionKind.FOLDER -> TrackQuery(folderPath = args.id)
        }
        val origin = when (args.kind) {
            CollectionKind.ALBUM -> QueueOrigin.ALBUM
            CollectionKind.ARTIST -> QueueOrigin.ARTIST
            CollectionKind.GENRE -> QueueOrigin.GENRE
            CollectionKind.FOLDER -> QueueOrigin.FOLDER
        }
        return CollectionViewModel(
            musicRepository = music,
            title = args.title,
            subtitle = args.subtitle,
            origin = origin,
            query = query,
            observeFavoriteIds = ObserveFavoriteIdsUseCase(favorites),
            toggleFavorite = ToggleFavoriteUseCase(favorites),
            playTracks = PlayTracksUseCase(playback),
            setShuffle = SetShuffleUseCase(playback),
            playNext = PlayNextUseCase(playback),
            addToQueue = AddToQueueUseCase(playback),
            addTracksToQueue = AddTracksToQueueUseCase(playback),
            dispatchers = dispatchers,
        )
    }

    /** Global search with the 300 ms debounce inside the ViewModel (requirement 26). */
    fun searchViewModel(): SearchViewModel = SearchViewModel(
        musicRepository = music,
        searchLibrary = SearchLibraryUseCase(search),
        playTrack = PlayTrackUseCase(playback),
        playTracks = PlayTracksUseCase(playback),
        playNext = PlayNextUseCase(playback),
        addToQueue = AddToQueueUseCase(playback),
        dispatchers = dispatchers,
    )

    /** Playlist list and management (phase 6). */
    fun playlistsViewModel(): PlaylistsViewModel = PlaylistsViewModel(
        getPlaylists = GetPlaylistsUseCase(playlists),
        createPlaylist = CreatePlaylistUseCase(playlists),
        renamePlaylist = RenamePlaylistUseCase(playlists),
        deletePlaylist = DeletePlaylistUseCase(playlists),
        playPlaylist = PlayPlaylistUseCase(playlists, playback),
        dispatchers = dispatchers,
    )

    /** One playlist's tracks, with real reordering (phase 6). */
    fun playlistDetailViewModel(playlistId: String): PlaylistDetailViewModel = PlaylistDetailViewModel(
        playlistId = playlistId,
        getPlaylists = GetPlaylistsUseCase(playlists),
        getTracks = GetPlaylistTracksUseCase(playlists),
        observeFavoriteIds = ObserveFavoriteIdsUseCase(favorites),
        toggleFavorite = ToggleFavoriteUseCase(favorites),
        removeTrack = RemoveTrackFromPlaylistUseCase(playlists),
        moveTrack = MovePlaylistTrackUseCase(playlists),
        playTracks = PlayTracksUseCase(playback),
        setShuffle = SetShuffleUseCase(playback),
        playNext = PlayNextUseCase(playback),
        addToQueue = AddToQueueUseCase(playback),
        addTracksToQueue = AddTracksToQueueUseCase(playback),
        dispatchers = dispatchers,
    )

    /** "Add to a playlist" picker, offered from every track list. */
    fun playlistPickerViewModel(): PlaylistPickerViewModel = PlaylistPickerViewModel(
        getPlaylists = GetPlaylistsUseCase(playlists),
        addTrack = AddTrackToPlaylistUseCase(playlists),
        createPlaylist = CreatePlaylistUseCase(playlists),
    )

    /** Folders and scanning: the actions behind "Añadir carpeta" and "Escanear". */
    fun libraryAdminViewModel(): LibraryAdminViewModel = LibraryAdminViewModel(
        musicRepository = music,
        addMusicFolder = AddMusicFolderUseCase(music),
        refreshLibrary = RefreshLibraryUseCase(music),
        cancelScan = CancelScanUseCase(music),
        pruneMissing = PruneMissingTracksUseCase(music),
    )

    /**
     * The artwork loader the UI uses.
     *
     * It is built here, over the artwork use cases, so no Composables ever hold a repository, and it
     * is a single instance because the decoded-bitmap cache is worth sharing across screens.
     */
    // ---------------------------------------------------------------- audio y ajustes (fases 8-13)

    /**
     * Audio Information: the whole path, measured.
     *
     * It reads the engine, the device repository and the platform's decoder list, so it can answer
     * "is this really 24/96 right now" with the numbers the system reported instead of the file name.
     */
    fun audioInfoViewModel(): AudioInfoViewModel = AudioInfoViewModel(
        observePlaybackState = ObservePlaybackStateUseCase(playback),
        observeSettings = ObserveSettingsUseCase(settings),
        observeActiveOutput = ObserveActiveOutputUseCase(audioDevices),
        observeCapabilities = ObserveAudioCapabilitiesUseCase(audioDevices),
        observeBitPerfectState = ObserveBitPerfectStateUseCase(audioDevices),
        observeOutputDevices = ObserveOutputDevicesUseCase(audioDevices),
        refreshOutputs = RefreshOutputsUseCase(audioDevices),
        getCapabilityReport = GetCapabilityReportUseCase(audioDevices),
        getDecoderSupport = GetDecoderSupportUseCase(audioDevices),
        verifyOutput = VerifyOutputUseCase(audioDevices),
    )

    /** Output devices and bit-perfect (phases 9 and 10). */
    fun audioSettingsViewModel(): AudioSettingsViewModel = AudioSettingsViewModel(
        observePlaybackState = ObservePlaybackStateUseCase(playback),
        observeSettings = ObserveSettingsUseCase(settings),
        observeOutputDevices = ObserveOutputDevicesUseCase(audioDevices),
        observeActiveOutput = ObserveActiveOutputUseCase(audioDevices),
        observeCapabilities = ObserveAudioCapabilitiesUseCase(audioDevices),
        observeBitPerfectState = ObserveBitPerfectStateUseCase(audioDevices),
        refreshOutputs = RefreshOutputsUseCase(audioDevices),
        selectOutput = SelectOutputDeviceUseCase(audioDevices),
        useSystemDefault = UseSystemDefaultOutputUseCase(audioDevices),
        requestUsbPermission = RequestUsbPermissionUseCase(audioDevices),
        setBitPerfect = SetBitPerfectUseCase(settings),
        setUsbAutoRoute = SetUsbAutoRouteUseCase(settings),
        setPauseOnDisconnect = SetPauseOnOutputDisconnectUseCase(settings),
    )

    /** Equalizer, ReplayGain and crossfeed (phases 11, 12 and 13). */
    fun dspViewModel(): DspViewModel = DspViewModel(
        observeSettings = ObserveSettingsUseCase(settings),
        observePlaybackState = ObservePlaybackStateUseCase(playback),
        setEqEnabled = SetEqEnabledUseCase(settings),
        updateEqBand = UpdateEqBandUseCase(settings),
        setEqPreset = SetEqPresetUseCase(settings),
        saveEqPreset = SaveEqPresetUseCase(settings),
        deleteEqPreset = DeleteEqPresetUseCase(settings),
        setPreamp = SetPreampUseCase(settings),
        setReplayGainMode = SetReplayGainModeUseCase(settings),
        updateReplayGain = UpdateReplayGainSettingsUseCase(settings),
        setCrossfeed = SetCrossfeedUseCase(settings),
        setBalance = SetBalanceUseCase(settings),
        setAppGain = SetAppGainUseCase(settings),
    )

    /** The settings screens (requirement 28). */
    fun settingsViewModel(): SettingsViewModel = SettingsViewModel(
        musicRepository = music,
        observeSettings = ObserveSettingsUseCase(settings),
        getStats = GetLibraryStatsUseCase(music),
        observeBitPerfectState = ObserveBitPerfectStateUseCase(audioDevices),
        getCacheSize = GetCacheSizeUseCase(artwork),
        clearCache = ClearCacheUseCase(artwork),
        resetSettings = ResetSettingsUseCase(settings),
        setGapless = SetGaplessUseCase(settings),
        setResamplePolicy = SetResamplePolicyUseCase(settings),
        setRepeatSetting = SetRepeatSettingUseCase(settings),
        setShuffleSetting = SetShuffleSettingUseCase(settings),
        setUsbAutoRoute = SetUsbAutoRouteUseCase(settings),
        setPauseOnOutputDisconnect = SetPauseOnOutputDisconnectUseCase(settings),
        setResumeOnStart = SetResumeOnStartUseCase(settings),
        setAutoPlayOnOpen = SetAutoPlayOnOpenUseCase(settings),
        setScanOnStartup = SetScanOnStartupUseCase(settings),
        setAutomaticScanning = SetAutomaticScanningUseCase(settings),
        setThemeMode = SetThemeModeUseCase(settings),
        setArtworkSize = SetArtworkSizeUseCase(settings),
        setAnimations = SetAnimationsEnabledUseCase(settings),
        setShowTechnicalInfo = SetShowTechnicalInfoInListsUseCase(settings),
    )

    /** Only what the app shell needs before it draws: the theme and the list detail level. */
    fun themeViewModel(): ThemeViewModel = ThemeViewModel(ObserveSettingsUseCase(settings))

    val artworkLoader: ArtworkLoader = LocalArtworkLoaderImpl(
        resolveArtwork = ResolveTrackArtworkUseCase(artwork),
        loadArtworkBytes = LoadArtworkBytesUseCase(artwork),
    )
}
