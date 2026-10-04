package com.hifiplayer

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.hifiplayer.app.logging.CrashReporter
import com.hifiplayer.ui.MainActivity
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Aquí se arranca la aplicación de verdad (requisito 46 y lección de la 0.17.0).
 *
 * Este test instancia el `Application` y la actividad con **el manifest y los recursos reales**, así
 * que ve los fallos que solo existen al ejecutar y que ningún compilador detecta:
 *
 * - un `android:name` que apunta a una clase que no existe (lo que rompió la 0.17.0: el APK se
 *   instalaba y se cerraba al abrir);
 * - un tema o un recurso que el manifest usa y que no está;
 * - una API del sistema que no existe en el nivel de Android del dispositivo;
 * - cualquier excepción dentro del grafo de dependencias al construirse.
 *
 * Se ejecuta en varios niveles de Android a propósito: escribir código que solo funciona en el
 * Android más nuevo y se cae en uno más viejo es un fallo real, y esta es la única forma de verlo
 * desde esta máquina.
 */
@RunWith(RobolectricTestRunner::class)
class AppStartupTest {

    @Test
    @Config(sdk = [26])
    fun `la aplicación arranca en Android 8`() {
        val app = ApplicationProvider.getApplicationContext<HiFiPlayerApp>()

        // El grafo es lo primero que se construye en onCreate: si algo del arranque falla, falla aquí.
        assertThat(app.graph).isNotNull()
        assertThat(app.graph.dispatchers).isNotNull()
        assertThat(app.graph.settings).isNotNull()
        assertThat(app.graph.playback).isNotNull()
    }

    @Test
    @Config(sdk = [34])
    fun `la aplicación arranca en Android 14`() {
        val app = ApplicationProvider.getApplicationContext<HiFiPlayerApp>()

        assertThat(app.graph).isNotNull()
        assertThat(app.graph.playback).isNotNull()
    }

    /**
     * La actividad de entrada, con el tema del manifest y el `setContent` de Compose. Es el camino
     * exacto que recorre el sistema cuando el usuario toca el icono.
     */
    @Test
    @Config(sdk = [34])
    fun `la pantalla de inicio se abre sin cerrarse`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertThat(controller.get()).isNotNull()
        assertThat(controller.get().isFinishing).isFalse()

        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------------------------------------
    // El capturador de fallos. Un falso positivo aquí es peor que no tenerlo: el usuario vería un
    // aviso de fallo en cada arranque de una aplicación que funciona.
    // ------------------------------------------------------------------------------------------

    @Test
    @Config(sdk = [34])
    fun `un arranque limpio no deja ningún informe pendiente`() {
        val app = ApplicationProvider.getApplicationContext<HiFiPlayerApp>()

        assertThat(CrashReporter.consume(app)).isNull()
        // El registro de etapas sí tiene que existir: es lo que delata un cierre sin excepción.
        assertThat(File(app.filesDir, "startup-stage.txt").exists()).isTrue()
    }

    @Test
    @Config(sdk = [34])
    fun `un arranque que se quedó a medias deja un informe que se enseña una sola vez`() {
        val app = ApplicationProvider.getApplicationContext<HiFiPlayerApp>()
        // Se reproduce lo que deja un cierre sin excepción: etapas escritas, pero ninguna que diga
        // que la interfaz llegó a dibujarse.
        File(app.filesDir, "startup-stage.txt")
            .writeText("2026-01-01 00:00:00 (+3ms) Application: capturador instalado\n")
        CrashReporter.install(app)

        val report = requireNotNull(CrashReporter.consume(app))
        assertThat(report).contains("sin excepción")
        assertThat(report).contains("capturador instalado")

        // Y no vuelve a aparecer: si no, sería un aviso eterno.
        assertThat(CrashReporter.consume(app)).isNull()
    }
}
