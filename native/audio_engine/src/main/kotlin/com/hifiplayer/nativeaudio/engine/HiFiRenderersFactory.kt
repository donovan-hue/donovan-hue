package com.hifiplayer.nativeaudio.engine

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain
import com.hifiplayer.dsp.pipeline.AudioProcessingPipeline

/**
 * Feeds ExoPlayer an [AudioSink] whose chain is: (nothing) → our DSP processor → sink.
 *
 * Two deliberate choices:
 *  - **Float output requested**, so the sink can hand the DAC the highest precision the device
 *    accepts instead of forcing 16-bit. If the route cannot take float the sink falls back, and the
 *    AudioInfoCard reports what really happened instead of what was requested.
 *  - The built-in silence-skipping and speed processors are kept, because gapless and playback
 *    parameters depend on them; skipping silence is disabled by settings, not by removing them.
 */
class HiFiRenderersFactory(
    context: Context,
    private val pipeline: AudioProcessingPipeline,
    private val configProvider: () -> DspRuntimeConfig,
    private val onDecodedFormat: (sampleRateHz: Int, channels: Int, encoding: Int) -> Unit,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val dsp = DspAudioProcessor(pipeline, configProvider, onDecodedFormat)
        val processors = arrayOf<AudioProcessor>(dsp)
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(DefaultAudioProcessorChain(*processors))
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
