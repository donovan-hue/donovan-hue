package com.hifiplayer.app.logging

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hifiplayer.core.common.logging.LogRedaction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda el último fallo para que el usuario pueda enviarlo (requisito 31).
 *
 * Por qué existe: la aplicación se cerraba al abrir en un móvil real y desde la máquina de
 * construcción no hay forma de ver el error. Un fallo que el usuario no puede contar es un fallo que
 * no se puede arreglar, así que la app se lo queda y lo deja donde pueda encontrarlo.
 *
 * Hay tres caminos, a propósito, porque el fallo puede impedir que la app llegue a dibujar algo:
 *
 * 1. Un diálogo al siguiente arranque (lo más cómodo, pero solo sirve si la app arranca).
 * 2. Una copia en la carpeta **Descargas** del teléfono, que se ve incluso si la app nunca llega a
 *    abrir: con un fallo que se repite en cada arranque, el diálogo no se vería jamás.
 * 3. Las etapas del arranque, para el caso en que el proceso muera **sin excepción** (el sistema lo
 *    mata, o falla código nativo): ahí no hay rastro que guardar, pero sí se sabe hasta dónde llegó.
 *
 * Escribe en el almacenamiento interno de la app (sin permisos) y en `Descargas` a través de
 * `MediaStore` (tampoco necesita permisos desde Android 10). **Nunca** incluye rutas de archivos ni
 * datos del usuario: pasa el texto por [LogRedaction] igual que el registro normal.
 */
object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"
    private const val STAGE_FILE = "startup-stage.txt"
    private const val STAGE_PREVIOUS_FILE = "startup-stage-previous.txt"

    /** Trozo del rastro que se conserva: suficiente para localizar el fallo y fácil de pegar. */
    private const val MAX_STACK_LINES = 40

    /** Etapas del arranque anterior que se enseñan cuando no llegó a dibujar la interfaz. */
    private const val MAX_STAGE_LINES = 10

    /** Marca de un arranque que sí terminó de dibujar la pantalla. */
    private const val UI_READY_PREFIX = "Interfaz: dibujada"

    private var appContext: Context? = null

    /** Etapas del arranque anterior si se quedó a medias. Null cuando el arranque anterior terminó. */
    private var unfinishedStartup: String? = null

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Momento de la instalación, para medir cuánto tarda cada etapa del arranque. */
    private val startedAt = System.nanoTime()

    /**
     * Instala el capturador y abre el registro de etapas. Tiene que llamarse **lo primero** en
     * `Application.onCreate`: si el fallo está en el grafo de dependencias o en el propio registro,
     * ya tiene que estar puesto.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        unfinishedStartup = rotateStageFile(app)
        stage("Application: capturador instalado")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Nunca dejamos que un fallo al escribir el informe tape el fallo original.
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Anota que el arranque ha llegado hasta aquí. Es un archivo de texto diminuto: se escribe al
     * lado del arranque, no durante la reproducción, y es la única forma de saber algo cuando el
     * proceso muere sin dejar excepción.
     */
    fun stage(name: String) {
        val app = appContext ?: return
        val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        runCatching {
            File(app.filesDir, STAGE_FILE).appendText("${stampFormat.format(Date())} (+${elapsed}ms) $name\n")
        }
    }

    /**
     * Devuelve el informe pendiente y lo marca como entregado, para no enseñarlo en cada arranque.
     * Devuelve null cuando no hubo ningún fallo.
     */
    fun consume(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            // Ya se ha entregado: si vuelve a fallar, se escribirá de nuevo.
            runCatching { file.renameTo(File(context.filesDir, "$FILE_NAME.seen")) }
            return text?.takeIf { it.isNotBlank() }
        }
        // No hubo excepción, pero el arranque anterior no llegó a dibujar la pantalla: el sistema
        // cerró el proceso o falló algo nativo. Eso también hay que contarlo.
        val unfinished = unfinishedStartup ?: return null
        unfinishedStartup = null
        return buildString {
            appendLine("HiFi Player · informe de cierre sin excepción")
            appendLine("Fecha: ${stampFormat.format(Date())}")
            appendLine("Versión: ${versionName(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("La aplicación se cerró sin registrar una excepción de Java/Kotlin: el sistema")
            appendLine("terminó el proceso o falló código nativo. Estas son las etapas del arranque que")
            appendLine("sí llegaron a completarse:")
            appendLine()
            append(unfinished.trimEnd())
        }
    }

    /** Lee las etapas del arranque anterior y abre un registro nuevo para este arranque. */
    private fun rotateStageFile(context: Context): String? {
        val current = File(context.filesDir, STAGE_FILE)
        if (!current.exists()) return null
        val text = runCatching { current.readText() }.getOrNull().orEmpty()
        runCatching { current.renameTo(File(context.filesDir, STAGE_PREVIOUS_FILE)) }
        if (text.isBlank()) return null
        // Si el arranque anterior llegó a dibujar, no hay nada que contar.
        if (text.trimEnd().lineSequence().any { it.contains(UI_READY_PREFIX) }) return null
        return text.lines().takeLast(MAX_STAGE_LINES).joinToString("\n")
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val timestamp = stampFormat.format(Date())
        val stack = error.stackTraceToString().lines().take(MAX_STACK_LINES).joinToString("\n")
        val report = buildString {
            appendLine("HiFi Player · informe de fallo")
            appendLine("Fecha: $timestamp")
            appendLine("Versión: ${versionName(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Hilo: ${thread.name}")
            appendLine()
            appendLine("--- error ---")
            appendLine("${error.javaClass.name}: ${error.message}")
            appendLine()
            appendLine("--- rastro ---")
            appendLine(stack)
            val stages = runCatching { File(context.filesDir, STAGE_FILE).readText() }.getOrNull()
            if (!stages.isNullOrBlank()) {
                appendLine()
                appendLine("--- etapas del arranque ---")
                append(stages.lines().takeLast(MAX_STAGE_LINES).joinToString("\n"))
            }
        }
        // La redacción quita rutas y URI del texto, igual que en los registros normales.
        val safe = runCatching { LogRedaction.redact(report) }.getOrDefault(report)
        runCatching { File(context.filesDir, FILE_NAME).writeText(safe) }
            .onFailure {
                // Último recurso: el archivo sin redactar, que no se ve desde fuera del almacenamiento
                // interno de la app.
                runCatching { File(context.filesDir, FILE_NAME).writeText(report) }
            }
        // Copia visible para el usuario, por si la app se cierra en cada arranque y el diálogo no se
        // llega a ver. Aquí sí o sí hay que enterarse de por qué.
        exportToDownloads(context, safe, timestamp)
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /**
     * Deja el informe en la carpeta Descargas del teléfono. Desde Android 10 no hace falta ningún
     * permiso para escribir los propios archivos en Descargas; en versiones anteriores se usa la
     * carpeta privada externa de la app, que se ve por USB.
     */
    private fun exportToDownloads(context: Context, report: String, timestamp: String) {
        val name = "hifi-player-fallo-${timestamp.replace(':', '-').replace(' ', '_')}.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
                }
            }
            return
        }
        runCatching {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) File(dir, name).writeText(report)
        }
    }
}
