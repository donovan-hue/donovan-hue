package com.hifiplayer.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.common.ext.formatBytes
import com.hifiplayer.core.common.ext.formatDuration
import com.hifiplayer.core.designsystem.component.HiFiChip
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.settings.ArtworkSize
import com.hifiplayer.domain.model.settings.RepeatModeSetting
import com.hifiplayer.domain.model.settings.ResamplePolicy
import com.hifiplayer.domain.model.settings.ThemeMode
import com.hifiplayer.presentation.settings.controls.SettingsChoiceRow
import com.hifiplayer.presentation.settings.controls.SettingsNavRow
import com.hifiplayer.presentation.settings.controls.SettingsNote
import com.hifiplayer.presentation.settings.controls.SettingsSectionHeader
import com.hifiplayer.presentation.settings.controls.SettingsSliderRow
import com.hifiplayer.presentation.settings.controls.SettingsSwitchRow
import com.hifiplayer.presentation.settings.controls.SettingsValueRow

/** What the About group shows; the version comes from the installed package, not from a string in code. */
data class AboutInfo(
    val versionName: String,
    val versionCode: Int,
    val phasesDone: Int,
    val phasesTotal: Int,
    val testCount: Int,
    val buildType: String,
)

/**
 * Settings (requirement 28), grouped exactly as the specification asks: Audio, Playback, Library,
 * Appearance, Storage and About.
 *
 * Every value on this screen is the value the app is using right now, and every row that changes
 * something writes it through a use case. Rows that lead to another screen are the only way to reach
 * those screens, so nothing here is decorative (requirement 46).
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    about: AboutInfo,
    onOpenAudioInfo: () -> Unit,
    onOpenBitPerfect: () -> Unit,
    onOpenOutputDevices: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenReplayGain: () -> Unit,
    onOpenCrossfeed: () -> Unit,
    onAddFolder: () -> Unit,
    onScanNow: () -> Unit,
    onPruneMissing: () -> Unit,
    onClearCache: () -> Unit,
    onResetSettings: () -> Unit,
    onResamplePolicyChange: (ResamplePolicy) -> Unit,
    onGaplessChange: (Boolean) -> Unit,
    onUsbAutoRouteChange: (Boolean) -> Unit,
    onPauseOnDisconnectChange: (Boolean) -> Unit,
    onResumeOnStartChange: (Boolean) -> Unit,
    onAutoPlayOnOpenChange: (Boolean) -> Unit,
    onRepeatSettingChange: (RepeatModeSetting) -> Unit,
    onShuffleSettingChange: (Boolean) -> Unit,
    onScanOnStartupChange: (Boolean) -> Unit,
    onAutomaticScanningChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onArtworkSizeChange: (ArtworkSize) -> Unit,
    onAnimationsChange: (Boolean) -> Unit,
    onShowTechnicalInfoChange: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current
    val settings = state.settings
    val appearance = settings.appearance
    val playback = settings.playback
    val dsp = settings.dsp

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Ajustes",
            subtitle = "versión ${about.versionName}",
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- Audio
            item(key = "audio-header") {
                SettingsSectionHeader(
                    title = "Audio",
                    subtitle = "Qué sale del dispositivo y con qué procesamiento.",
                )
            }
            item(key = "audio-info") {
                SettingsNavRow(
                    title = "Información de audio",
                    subtitle = "SOURCE y OUTPUT medidos, decodificadores y comprobación del formato actual.",
                    value = state.bitPerfect.bannerTitle,
                    onClick = onOpenAudioInfo,
                )
            }
            item(key = "audio-bitperfect") {
                SettingsNavRow(
                    title = "Bit-perfect",
                    subtitle = "Un interruptor que apaga todo el procesamiento. Se muestra lo que el sistema confirma.",
                    value = if (state.bitPerfect.isActive) "activo" else if (playback.bitPerfectEnabled) "solicitado" else "apagado",
                    onClick = onOpenBitPerfect,
                )
            }
            item(key = "audio-outputs") {
                SettingsNavRow(
                    title = "Dispositivo de salida",
                    subtitle = "Altavoz, auriculares, Bluetooth y DAC USB, con lo que cada ruta admite de verdad.",
                    value = playback.preferredOutputDeviceId?.let { "fijado" } ?: "automático",
                    onClick = onOpenOutputDevices,
                )
            }
            item(key = "audio-eq") {
                SettingsNavRow(
                    title = "Ecualizador paramétrico",
                    subtitle = "Bandas por frecuencia, ganancia, Q y tipo, con presets.",
                    value = if (settings.eq.enabled) "${settings.eq.activeBandCount} bandas" else "apagado",
                    onClick = onOpenEq,
                )
            }
            item(key = "audio-rg") {
                SettingsNavRow(
                    title = "ReplayGain",
                    subtitle = "Ajusta el volumen entre pistas sin tocar los archivos. Solo al reproducir.",
                    value = settings.replayGain.mode.displayName,
                    onClick = onOpenReplayGain,
                )
            }
            item(key = "audio-crossfeed") {
                SettingsNavRow(
                    title = "Crossfeed",
                    subtitle = "Mezcla un poco de cada canal en el otro. Altera la señal a propósito.",
                    value = dsp.crossfeed.shortLabel,
                    onClick = onOpenCrossfeed,
                )
            }
            item(key = "audio-resample") {
                SettingsChoiceRow(
                    title = "Si el formato no es nativo",
                    subtitle = settings.playback.resamplePolicy.description,
                    options = ResamplePolicy.entries,
                    selected = playback.resamplePolicy,
                    label = { it.displayName },
                    onSelect = onResamplePolicyChange,
                )
            }
            item(key = "audio-gapless") {
                SettingsSwitchRow(
                    title = "Reproducción sin pausas",
                    subtitle = "Las pistas que van juntas (un directo, una suite) suenan seguidas.",
                    checked = playback.gaplessEnabled,
                    onCheckedChange = onGaplessChange,
                )
            }
            item(key = "audio-usb") {
                SettingsSwitchRow(
                    title = "Enviar el audio al DAC USB al conectarlo",
                    subtitle = "Si un DAC USB aparece, pasa a ser la salida activa sin preguntar.",
                    checked = playback.usbAutoRoute,
                    onCheckedChange = onUsbAutoRouteChange,
                )
            }
            item(key = "audio-pause-disconnect") {
                SettingsSwitchRow(
                    title = "Pausar si se desconecta la salida",
                    subtitle = "Al desenchufar auriculares o DAC se pausa, en vez de seguir sonando por el altavoz.",
                    checked = playback.pauseOnOutputDisconnect,
                    onCheckedChange = onPauseOnDisconnectChange,
                )
            }

            // ---------------------------------------------------------------- Reproducción
            item(key = "playback-header") {
                SettingsSectionHeader(title = "Reproducción")
            }
            item(key = "playback-repeat") {
                SettingsChoiceRow(
                    title = "Repetición",
                    options = RepeatModeSetting.entries,
                    selected = playback.repeatMode,
                    label = { it.displayName },
                    onSelect = onRepeatSettingChange,
                )
            }
            item(key = "playback-shuffle") {
                SettingsSwitchRow(
                    title = "Orden aleatorio al iniciar",
                    subtitle = "Empieza a sonar mezclado la próxima vez que abras una lista.",
                    checked = playback.shuffleEnabled,
                    onCheckedChange = onShuffleSettingChange,
                )
            }
            item(key = "playback-resume") {
                SettingsSwitchRow(
                    title = "Reanudar al abrir la app",
                    subtitle = "Vuelve a la cola y a la posición donde lo dejaste.",
                    checked = playback.resumePlaybackOnStart,
                    onCheckedChange = onResumeOnStartChange,
                )
            }
            item(key = "playback-autoplay") {
                SettingsSwitchRow(
                    title = "Empezar a sonar al elegir una pista",
                    subtitle = "Al tocar una pista concreta suena ya; si está apagado, solo se carga en la cola.",
                    checked = playback.autoPlayOnOpen,
                    onCheckedChange = onAutoPlayOnOpenChange,
                )
            }

            // ---------------------------------------------------------------- Biblioteca
            item(key = "library-header") {
                SettingsSectionHeader(
                    title = "Biblioteca",
                    subtitle = "De dónde sale la música y cuándo se analiza.",
                )
            }
            item(key = "library-folders") {
                Row(
                    modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${settings.library.documentTreeUris.size} carpetas autorizadas",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    HiFiChip(label = "Añadir carpeta", selected = false, onClick = onAddFolder)
                    HiFiChip(label = "Escanear ahora", selected = false, onClick = onScanNow)
                }
            }
            if (state.scan.isRunning) {
                item(key = "library-scan") {
                    SettingsValueRow(
                        title = "Escaneando",
                        value = if (state.scan.total > 0) "${state.scan.processed}/${state.scan.total}" else "${state.scan.processed}",
                        subtitle = "${state.scan.phase.displayName} · ${state.scan.percent} %",
                    )
                }
            }
            item(key = "library-scan-startup") {
                SettingsSwitchRow(
                    title = "Escanear al abrir",
                    subtitle = "Busca archivos nuevos o movidos cada vez que arranca.",
                    checked = settings.library.scanOnStartup,
                    onCheckedChange = onScanOnStartupChange,
                )
            }
            item(key = "library-automatic") {
                SettingsSwitchRow(
                    title = "Vigilar cambios en las carpetas",
                    subtitle = "Detecta música añadida o quitada mientras la app está abierta.",
                    checked = settings.library.automaticScanning,
                    onCheckedChange = onAutomaticScanningChange,
                )
            }
            item(key = "library-prune") {
                SettingsNavRow(
                    title = "Quitar de la biblioteca lo que ya no existe",
                    subtitle = "Compara con el almacenamiento real y limpia entradas huérfanas.",
                    onClick = onPruneMissing,
                )
            }
            if (state.stats != null) {
                val stats = state.stats!!
                item(key = "library-stats") {
                    SettingsValueRow(
                        title = "Contenido actual",
                        value = "${stats.trackCount} pistas",
                        subtitle = "${stats.albumCount} álbumes · ${formatDuration(stats.totalDurationMs)} · " +
                            "${formatBytes(stats.totalSizeBytes)} · ${stats.losslessCount} sin pérdida · " +
                            "${stats.highResolutionCount} en alta resolución",
                    )
                }
                if (stats.unknownFormatCount > 0) {
                    item(key = "library-unknown") {
                        SettingsNote(
                            text = "${stats.unknownFormatCount} archivos no tienen formato reconocido: en las " +
                                "listas aparecen como «formato no detectado» en vez de con una cifra inventada.",
                            isWarning = false,
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- Apariencia
            item(key = "appearance-header") {
                SettingsSectionHeader(title = "Apariencia")
            }
            item(key = "appearance-theme") {
                SettingsChoiceRow(
                    title = "Tema",
                    options = ThemeMode.entries,
                    selected = appearance.themeMode,
                    label = { it.displayName },
                    onSelect = onThemeModeChange,
                )
            }
            item(key = "appearance-artwork") {
                SettingsChoiceRow(
                    title = "Tamaño de las portadas en las listas",
                    subtitle = "El tamaño con el que se piden las imágenes; las grandes se reservan para el reproductor.",
                    options = ArtworkSize.entries,
                    selected = appearance.artworkSize,
                    label = { it.displayName },
                    onSelect = onArtworkSizeChange,
                )
            }
            item(key = "appearance-technical") {
                SettingsSwitchRow(
                    title = "Mostrar datos técnicos en las listas",
                    subtitle = "Formato, bitrate y frecuencia junto a cada pista. Es información medida, no estimada.",
                    checked = appearance.showTechnicalInfoInLists,
                    onCheckedChange = onShowTechnicalInfoChange,
                )
            }
            item(key = "appearance-animations") {
                SettingsSwitchRow(
                    title = "Animaciones",
                    subtitle = "Transiciones suaves. Apagarlas hace la interfaz más inmediata.",
                    checked = appearance.animationsEnabled,
                    onCheckedChange = onAnimationsChange,
                )
            }

            // ---------------------------------------------------------------- Almacenamiento
            item(key = "storage-header") {
                SettingsSectionHeader(
                    title = "Almacenamiento",
                    subtitle = "Los archivos de música nunca se tocan: la app solo lee.",
                )
            }
            item(key = "storage-cache") {
                SettingsValueRow(
                    title = "Caché de portadas",
                    value = if (state.cacheSizeBytes > 0) formatBytes(state.cacheSizeBytes) else "vacía",
                    subtitle = "Imágenes ya escaladas que se reutilizan al desplazar. Se puede borrar sin perder nada.",
                )
            }
            item(key = "storage-clear") {
                SettingsNavRow(
                    title = "Borrar la caché de portadas",
                    subtitle = "Se volverán a generar la próxima vez que se muestren.",
                    onClick = onClearCache,
                )
            }

            // ---------------------------------------------------------------- Acerca de
            item(key = "about-header") { SettingsSectionHeader(title = "Acerca de") }
            item(key = "about-version") {
                SettingsValueRow(
                    title = "HiFi Player",
                    value = "${about.versionName} (${about.versionCode})",
                    subtitle = "${about.buildType} · pliego completo: ${about.phasesDone} de ${about.phasesTotal} fases · " +
                        "${about.testCount} pruebas automáticas",
                )
            }
            item(key = "about-claims") {
                SettingsNote(
                    text = "La app informa de lo que mide: si un dato no se pudo leer, aparece como no detectado, y " +
                        "bit-perfect solo se muestra cuando el sistema lo confirma en el dispositivo.",
                    isWarning = false,
                )
            }
            item(key = "about-reset") {
                SettingsNavRow(
                    title = "Devolver los ajustes a sus valores por defecto",
                    subtitle = "No borra la biblioteca, las listas ni los favoritos.",
                    onClick = onResetSettings,
                )
            }

            if (state.message != null) {
                item(key = "message") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.messageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissMessage) { Text("Entendido") }
                    }
                }
            }

            item(key = "footer") {
                Text(
                    text = "El motor de audio, la base de datos y el procesamiento son piezas separadas: " +
                        "por eso esta app puede decir exactamente qué está haciendo con la señal.",
                    style = TechLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 12.dp),
                )
            }
            item(key = "bottom") { Box(modifier = Modifier.height(dimens.sectionSpacing)) }
        }
    }
}
