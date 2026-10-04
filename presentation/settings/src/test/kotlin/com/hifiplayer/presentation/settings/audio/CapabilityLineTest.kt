package com.hifiplayer.presentation.settings.audio

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.device.BitPerfectSupport
import com.hifiplayer.domain.model.device.DeviceCapabilities
import com.hifiplayer.domain.model.device.OutputDevice
import com.hifiplayer.domain.model.device.OutputRouteType
import org.junit.Test

/**
 * The one line that summarises what an output route can do (requirements 10 and 46).
 *
 * A Hi-Fi player that prints "hasta 192 kHz" for a device that never answered is lying with a
 * straight face. These tests fix the rule: unanswered probes are left out and the screen says so.
 */
class CapabilityLineTest {

    @Test
    fun `a route that never answered says so instead of guessing`() {
        val device = device(capabilities = null)

        assertThat(capabilityLine(device)).isEqualTo("sin datos de capacidad todavía")
    }

    @Test
    fun `only the answered probes are printed`() {
        val device = device(
            capabilities = DeviceCapabilities(
                routeId = "usb-1",
                routeName = "DAC USB",
                routeType = OutputRouteType.USB_DEVICE,
                supportedSampleRates = setOf(44_100, 96_000),
                supportedBitDepths = setOf(16, 24),
                bitPerfectSupport = BitPerfectSupport.NEEDS_WIRED_OR_USB_OUTPUT,
            ),
        )

        val line = capabilityLine(device)

        assertThat(line).contains("hasta 96.0 kHz")
        assertThat(line).contains("24-bit")
        assertThat(line).contains("USB")
        // No float support was reported, so float is not mentioned.
        assertThat(line).doesNotContain("float")
        // And it does not claim bit-perfect either.
        assertThat(line).doesNotContain("bit-perfect confirmado")
    }

    @Test
    fun `an empty capability set still produces an honest line`() {
        val device = device(
            capabilities = DeviceCapabilities(
                routeId = "speaker",
                routeName = "Altavoz",
                routeType = OutputRouteType.BUILTIN_SPEAKER,
            ),
        )

        val line = capabilityLine(device)

        assertThat(line).doesNotContain("kHz")
        assertThat(line).doesNotContain("bit")
        assertThat(line).contains(BitPerfectSupport.UNKNOWN.displayName)
    }

    @Test
    fun `a confirmed bit-perfect route says exactly that`() {
        val device = device(
            capabilities = DeviceCapabilities(
                routeId = "usb-2",
                routeName = "DAC USB",
                routeType = OutputRouteType.USB_DEVICE,
                supportedSampleRates = setOf(192_000),
                supportedBitDepths = setOf(32),
                bitPerfectSupport = BitPerfectSupport.ACTIVE,
                supportsBitPerfectMixer = true,
            ),
        )

        val line = capabilityLine(device)

        assertThat(line).contains("hasta 192.0 kHz")
        assertThat(line).contains("bit-perfect confirmado")
    }

    private fun device(capabilities: DeviceCapabilities?): OutputDevice = OutputDevice(
        id = "route-1",
        name = "Salida",
        type = OutputRouteType.USB_DEVICE,
        isActive = false,
        capabilities = capabilities,
    )
}
