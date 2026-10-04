package com.hifiplayer

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.hifiplayer.app.logging.CrashReporter
import com.hifiplayer.ui.MainActivity
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
 * - cualquier excepción dentro del grafo de dependencias al construirse;
 * - y una llamada a ExoPlayer desde un hilo que no es el suyo, que es lo que cerraba la aplicación
 *   al abrirla en un teléfono real (media3 lanza `IllegalStateException` y el proceso muere).
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
        val app = ApplicationProvider.getApplicationContext<HiFiPlayerApp>()
        clearReports(app)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertThat(controller.get()).isNotNull()
        assertThat(controller.get().isFinishing).isFalse()

        controller.pause().stop().destroy()
    }

    /**
     * La prueba de la avería de verdad (0.17.1): en un teléfono, la aplicación abría y moría en
     * 505 ms con `IllegalStateException: Player is accessed on the wrong thread` en un hilo de fondo
     * — y una excepción no capturada en un hilo de fondo se lleva por delante el proceso entero.
     *
     * Aquí se arranca la aplicación completa, se deja trabajar a los hilos de fondo (los flujos
     * corren ahí, y por eso el fallo no se ve en una llamada directa) y se exige que no haya quedado
     * ningún informe de fallo. Los 200 ms son tiempo real: en el móvil el fallo tarda 505 ms.
     */
    @Test
    @Config(sdk = [34])
    fun `el arranque no deja ninguna excepción, tampoco en los hilos de fondo`() {
        val app = ApplicationProvider.getApplicationContext<HiFiPlayerApp>()
        clearReports(app)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(6) {
            Thread.sleep(200)
            shadowOf(Looper.getMainLooper()).idle()
        }

        val report = CrashReporter.consume(app)
        controller.pause().stop().destroy()

        assertThat(report).isNull()
    }

    // ------------------------------------------------------------------------------------------
    // El capturador de fallos. Un falso positivo aquí es peor que no tenerlo: el usuario vería un
    // aviso de fallo en cada arranque de una aplicación que funciona.
    // ------------------------------------------------------------------------------------------

    @Test
    @Config(sdk = [34])
    fun `un arranque limpio no deja ningún informe pendiente`() {
        val app = ApplicationProvider.getApplicationContext<HiFiPlayerApp>()
        clearReports(app)

        assertThat(CrashReporter.consume(app)).isNull()
        // El registro de etapas sí tiene que existir: es lo que delata un cierre sin excepción.
        assertThat(File(app.filesDir, "startup-stage.txt").exists()).isTrue()
    }

    @Test
    @Config(sdk = [34])
    fun `un arranque que se quedó a medias deja un informe que se enseña una sola vez`() {
        val app = ApplicationProvider.getApplicationContext<HiFiPlayerApp>()
        clearReports(app)
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

    /** Deja el almacenamiento de informes como recién instalado: cada prueba parte de cero. */
    private fun clearReports(app: HiFiPlayerApp) {
        File(app.filesDir, "last-crash.txt").delete()
        File(app.filesDir, "last-crash.txt.seen").delete()
    }
}
