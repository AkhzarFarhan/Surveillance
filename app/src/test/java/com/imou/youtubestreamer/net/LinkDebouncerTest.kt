package com.imou.youtubestreamer.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDebouncerTest {

    @Test
    fun `requires 2 consecutive probes to declare UP or DOWN and resists AP flap storms`() {
        val debouncer = LinkDebouncer(downThreshold = 2, upThreshold = 2)

        // 1st good probe: still down (requires 2)
        val s1 = debouncer.onProbeSample(
            lanProbeOk = true, wanProbeOk = true,
            hasLocalNetwork = true, hasWifiOrEthernet = true,
            isCellularOnly = false, osValidated = true, osWanAllowed = true,
            networkHandle = 1L, elapsedMs = 1_000L
        )
        assertFalse(s1.lanUp)
        assertFalse(s1.wanUp)

        // 2nd good probe: transitions UP
        val s2 = debouncer.onProbeSample(
            lanProbeOk = true, wanProbeOk = true,
            hasLocalNetwork = true, hasWifiOrEthernet = true,
            isCellularOnly = false, osValidated = true, osWanAllowed = true,
            networkHandle = 1L, elapsedMs = 2_000L
        )
        assertTrue(s2.lanUp)
        assertTrue(s2.wanUp)

        // Single transient WAN drop (e.g. Wi-Fi up, 1 failed probe): stays UP
        val s3 = debouncer.onProbeSample(
            lanProbeOk = true, wanProbeOk = false,
            hasLocalNetwork = true, hasWifiOrEthernet = true,
            isCellularOnly = false, osValidated = true, osWanAllowed = true,
            networkHandle = 1L, elapsedMs = 17_000L
        )
        assertTrue(s3.lanUp)
        assertTrue(s3.wanUp)

        // 2nd consecutive WAN failure with LAN still healthy -> WAN DOWN, LAN UP
        val s4 = debouncer.onProbeSample(
            lanProbeOk = true, wanProbeOk = false,
            hasLocalNetwork = true, hasWifiOrEthernet = true,
            isCellularOnly = false, osValidated = true, osWanAllowed = true,
            networkHandle = 1L, elapsedMs = 32_000L
        )
        assertTrue(s4.lanUp)
        assertFalse(s4.wanUp)
    }

    @Test
    fun `blocks both WAN and LAN when cellular-only and allowCellular is false`() {
        val debouncer = LinkDebouncer()
        val s = debouncer.onProbeSample(
            lanProbeOk = true, wanProbeOk = true,
            hasLocalNetwork = true, hasWifiOrEthernet = false,
            isCellularOnly = true, osValidated = true, osWanAllowed = false,
            networkHandle = 99L, elapsedMs = 5_000L
        )
        assertFalse(s.lanUp)
        assertFalse(s.wanUp)
        assertTrue(s.isCellularOnly)
    }
}
