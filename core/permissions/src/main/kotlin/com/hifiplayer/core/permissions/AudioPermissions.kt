package com.hifiplayer.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Requirement 33: ask for the minimum, explain why.
 *
 * - Android 13+ : `READ_MEDIA_AUDIO` (granular media permission).
 * - Android 12- : `READ_EXTERNAL_STORAGE` (the only option MediaStore offered then).
 * - `POST_NOTIFICATIONS` (13+) is needed for the playback notification, but playback itself
 *   keeps working without it, so it is requested separately and its absence is not fatal.
 * - Folders added through SAF need **no** runtime permission at all: that is why the app offers
 *   SAF first (requirement 33) and never asks for full storage access.
 */
object AudioPermissions {

    fun libraryPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun notificationPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun allRequestablePermissions(): List<String> = libraryPermissions() + notificationPermissions()

    /** Human explanation shown before the system dialog (requirement 33: explain each one). */
    fun rationale(permission: String): String = when (permission) {
        Manifest.permission.READ_MEDIA_AUDIO ->
            "Para leer tu música desde el almacenamiento. NiHiFi Player solo lee los archivos de audio que " +
                "selecciones; nunca los modifica."
        Manifest.permission.READ_EXTERNAL_STORAGE ->
            "Para leer tu música desde el almacenamiento. También puedes elegir una carpeta con el selector " +
                "del sistema y no conceder este permiso."
        Manifest.permission.POST_NOTIFICATIONS ->
            "Para mostrar los controles de reproducción en la barra de notificaciones. El audio funciona igual " +
                "si lo deniegas."
        else -> "Permiso necesario para el funcionamiento de la app."
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun libraryAccessState(context: Context): PermissionState {
        val permissions = libraryPermissions()
        val granted = permissions.filter { isGranted(context, it) }
        val missing = permissions - granted.toSet()
        return if (missing.isEmpty()) PermissionState.AllGranted else PermissionState.Missing(missing)
    }

    fun notificationState(context: Context): PermissionState {
        val permissions = notificationPermissions()
        if (permissions.isEmpty()) return PermissionState.NotApplicable
        val missing = permissions.filterNot { isGranted(context, it) }
        return if (missing.isEmpty()) PermissionState.AllGranted else PermissionState.Missing(missing)
    }

    /**
     * True when the app can see the library without any runtime permission: either it already
     * has it, or the user granted at least one SAF folder (which needs no permission).
     */
    fun canReadLibraryWithoutPermission(grantedSafTrees: Int): Boolean = grantedSafTrees > 0
}

sealed interface PermissionState {
    data object AllGranted : PermissionState
    data object NotApplicable : PermissionState
    data class Missing(val permissions: List<String>) : PermissionState {
        val first: String get() = permissions.first()
    }

    val isGranted: Boolean get() = this is AllGranted || this is NotApplicable
}
