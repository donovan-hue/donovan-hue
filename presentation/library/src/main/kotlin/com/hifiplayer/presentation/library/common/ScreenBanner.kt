package com.hifiplayer.presentation.library.common

/**
 * Message produced outside the screen's own ViewModel (adding a folder, a scan cancelled, adding a
 * track to a playlist).
 *
 * Screens render it in the same place and with the same wording as their own messages, so there is a
 * single message area per screen instead of two competing ones.
 */
data class ScreenBanner(
    val message: String? = null,
    val isError: Boolean = false,
    val onDismiss: () -> Unit = {},
) {
    val visible: Boolean get() = !message.isNullOrBlank()
}
