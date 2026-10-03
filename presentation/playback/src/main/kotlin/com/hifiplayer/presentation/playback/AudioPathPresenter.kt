package com.hifiplayer.presentation.playback

import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.playback.AudioOutputInfo
import com.hifiplayer.domain.model.settings.AppSettings

/**
 * Turns what the engine measured into what the screens show.
 *
 * It lives outside the ViewModels because both Now Playing and the Audio Information screen describe
 * the same signal path, and two descriptions of the same path that can drift apart are exactly how an
 * app ends up claiming something its engine did not do (requirements 12 and 30).
 */
internal fun buildAudioPath(info: AudioOutputInfo, settings: AppSettings): AudioPathUi = AudioPathUi(
    sourceFormatLabel = info.sourceFormat?.label,
    sourceCodecName = info.sourceFormat?.codec?.displayName,
    sourceChannels = info.sourceFormat?.channelLayoutName,
    sourceBitrate = info.sourceFormat?.bitrateKbps?.let { "$it kbps" },
    sourceIsLossless = info.sourceFormat?.isLossless,
    decodedFormatLabel = info.decodedFormat?.label,
    outputFormatLabel = info.outputFormat?.label,
    outputDeviceName = info.outputDeviceName,
    outputDeviceType = info.outputDeviceType,
    outputStatusLabel = info.outputStatusLabel,
    decoderName = info.decoderName,
    resampled = info.resampled,
    downmixed = info.downmixed,
    bitPerfect = BitPerfectBanner(
        requested = info.bitPerfect.requested,
        isActive = info.bitPerfect.isActive,
        title = info.bitPerfect.bannerTitle,
        detail = info.bitPerfect.bannerDetail,
    ),
    dspChainDescription = describeChain(info, settings),
    replayGainLabel = replayGainLabel(info),
    eqActive = info.eqActive,
    crossfeedLabel = info.crossfeedLabel,
    appliedGainLabel = if (info.appliedGainDb != 0.0) "%+.2f dB".format(info.appliedGainDb) else null,
    clippingPrevented = info.clippingPrevented,
    note = info.message,
)

/**
 * The signal path is written from the settings that are really on, and it says "bypass" when every
 * stage is off — which is the honest description of the bit-perfect path (requirement 6:
 * Source → Decode → Output).
 */
internal fun describeChain(info: AudioOutputInfo, settings: AppSettings): String {
    if (info.bitPerfect.isActive) return "Source → Decode → Output (sin procesamiento)"
    val stages = mutableListOf("Source", "Decode")
    if (settings.replayGain.mode != ReplayGainMode.OFF) stages += "ReplayGain"
    if (settings.eq.enabled && settings.eq.hasAnyGain) stages += "EQ"
    if (settings.dsp.crossfeed.isEnabled) stages += "Crossfeed"
    if (settings.dsp.appGainDb != 0.0 || settings.dsp.preampDb != 0.0 || settings.dsp.balance != 0.0) stages += "Gain"
    stages += "Output"
    return stages.joinToString(" → ")
}

internal fun replayGainLabel(info: AudioOutputInfo): String? {
    val mode = info.replayGainMode ?: return null
    return "$mode · %+.2f dB".format(info.replayGainDb)
}
