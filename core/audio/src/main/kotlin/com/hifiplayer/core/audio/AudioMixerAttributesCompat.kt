package com.hifiplayer.core.audio

import android.media.AudioFormat
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Keeps every reference to the Android 14 `AudioMixerAttributes` API in one place, so the rest of
 * the app stays compilable and honest on older versions (the controller reports
 * `API_NOT_SUPPORTED` instead of pretending).
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object AudioMixerAttributesCompat {

    private const val MIXER_BEHAVIOR_BIT_PERFECT = 1

    fun build(format: BitPerfectController.MixerFormat): AudioMixerAttributes {
        val encoding = when (format.bitDepth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> 21 // AudioFormat.ENCODING_PCM_24BIT_PACKED (API 31+)
            32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        val channelMask = if (format.channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(format.sampleRateHz)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()
        return AudioMixerAttributes.Builder(audioFormat)
            .setMixerBehavior(MIXER_BEHAVIOR_BIT_PERFECT)
            .build()
    }
}
