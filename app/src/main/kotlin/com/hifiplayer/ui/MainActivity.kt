package com.hifiplayer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.hifiplayer.app.logging.CrashReporter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hifiplayer.app.BuildConfig
import com.hifiplayer.HiFiPlayerApp
import com.hifiplayer.core.designsystem.theme.HiFiTheme
import com.hifiplayer.presentation.navigation.HiFiApp
import com.hifiplayer.presentation.settings.AboutInfo
import com.hifiplayer.presentation.settings.toThemeAppearance
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
private const val TEST_COUNT = 103

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
        CrashReporter.stage("Actividad: creada")
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
            // La decisión se toma en un solo sitio y se prueba ahí; el tema ya no la revisa por su
            // cuenta (antes "Claro" se imponía como oscuro si el teléfono estaba en modo oscuro).
            val appearance = themeMode.toThemeAppearance(isSystemInDarkTheme())
            HiFiTheme(darkTheme = appearance.darkTheme, pureBlack = appearance.pureBlack) {
                CrashReportDialog()
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
                // Va después de HiFiApp a propósito: si la composición de la interfaz lanza una
                // excepción, esto no llega a ejecutarse y el arranque queda marcado como incompleto.
                StartupStageWatcher()
            }
        }
    }
}

/**
 * Deja constancia de que el arranque llegó a dibujar la pantalla.
 *
 * No es decorativo: cuando no hay excepción que guardar (el sistema mata el proceso, o falla código
 * nativo), la única prueba que queda es hasta dónde llegó el arranque. Y si esto no se ejecuta, el
 * siguiente arranque sabe que el anterior se quedó a medias.
 */
@Composable
private fun StartupStageWatcher() {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        CrashReporter.stage("Interfaz: compuesta")
        // El primer fotograma se pinta en el siguiente recorrido del árbol de vistas.
        view.post { CrashReporter.stage("Interfaz: dibujada") }
    }
}

/**
 * Si la aplicación falló la última vez, enseña el informe y lo deja copiado (requisito 31).
 *
 * Esto no es una función decorativa: la 0.17.1 se cerraba al abrir en un móvil real y desde la
 * máquina de construcción no había forma de ver el error. Ahora el fallo viaja con el usuario en
 * lugar de perderse en un registro que nadie puede leer sin un ordenador.
 */
@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // `consume` marca el informe como entregado: se enseña una vez, no en cada arranque.
    var report by remember { mutableStateOf(CrashReporter.consume(context)) }

    val current = report ?: return

    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("La aplicación falló la última vez") },
        text = {
            Column(modifier = Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = "Copia el informe y envíalo: dice exactamente dónde falló.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = current,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(current))
                    Toast.makeText(context, "Informe copiado al portapapeles", Toast.LENGTH_LONG).show()
                    report = null
                },
            ) { Text("Copiar informe") }
        },
        dismissButton = {
            TextButton(onClick = { report = null }) { Text("Cerrar") }
        },
    )
}
