package com.hifiplayer.nativeaudio.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.hifiplayer.dsp.pipeline.AudioProcessingPipeline
import com.hifiplayer.nativeaudio.engine.internal.PcmCodec
import java.nio.ByteBuffer

/**
 * Bridges the app's DSP chain into Media3's audio pipeline (requirement 11).
 *
 * The processor is deliberately dumb about policy and strict about formats:
 *
 *  - it accepts only PCM 16-bit and PCM float, and **asks for the same format it received**, so it
 *    never introduces a hidden conversion;
 *  - in bit-perfect mode the bytes are copied verbatim: no float round-trip, no gain, no filter,
 *    not even a channel reorder. That is what makes the claim honest;
 *  - in DSP mode the frames go through [AudioProcessingPipeline], which already knows how to
 *    bypass itself stage by stage.
 *
 * It also measures the decoded format Media3 is really feeding the sink, which is what the
 * AudioInfoCard shows as the "decoded" step.
 */
class DspAudioProcessor(
    private val pipeline: AudioProcessingPipeline,
    private val configProvider: () -> DspRuntimeConfig,
    private val onDecodedFormat: (sampleRateHz: Int, channels: Int, encoding: Int) -> Unit,
) : BaseAudioProcessor() {

    private var bytesPerSample: Int = 2
    private var floatInput: Boolean = false
    private var scratch: FloatArray = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        floatInput = encoding == C.ENCODING_PCM_FLOAT
        bytesPerSample = if (floatInput) 4 else 2

        val config = configProvider()
        pipeline.configure(
            settings = config.settings,
            replayGainInfo = config.replayGain,
            sampleRateHz = inputAudioFormat.sampleRate,
            channels = inputAudioFormat.channelCount,
            bitPerfectRequested = config.bitPerfect,
        )
        onDecodedFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, encoding)
        // Same format out: the conversion happens inside our own buffer handling, never as a
        // silent resample or requantisation on the way out.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        // Bit-perfect: byte-for-byte passthrough.
        if (configProvider().bitPerfect) {
            val output = replaceOutputBuffer(remaining)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val channels = inputAudioFormat.channelCount
        val frames = remaining / (bytesPerSample * channels)
        if (frames <= 0) {
            // Incomplete frame: forward it untouched rather than dropping audio.
            val output = replaceOutputBuffer(remaining)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val samples = frames * channels
        if (scratch.size < samples) scratch = FloatArray(samples)
        PcmCodec.decode(inputBuffer, scratch, samples, floatInput)

        pipeline.process(scratch, frames, channels)

        val output = replaceOutputBuffer(samples * bytesPerSample)
        PcmCodec.encode(scratch, samples, output, floatInput)
        output.flip()
    }

    override fun onReset() {
        scratch = FloatArray(0)
    }
}
