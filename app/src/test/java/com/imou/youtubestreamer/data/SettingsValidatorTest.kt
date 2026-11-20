package com.imou.youtubestreamer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {

    @Test
    fun `rejects duplicate fixed and rotatable stream keys or backfill collisions`() {
        val dupLive = SettingsValidator.validateStreamKeys(
            fixedLive = "abcd-1234-efgh-5678",
            rotatableLive = "rtmps://a.rtmp.youtube.com/live2/abcd-1234-efgh-5678"
        )
        assertFalse(dupLive.isValid)

        val backfillCollides = SettingsValidator.validateStreamKeys(
            fixedLive = "live-key-1111",
            rotatableLive = "live-key-2222",
            fixedBackfill = "live-key-1111"
        )
        assertFalse(backfillCollides.isValid)

        val validAllDistinct = SettingsValidator.validateStreamKeys(
            fixedLive = "live-key-1111",
            rotatableLive = "live-key-2222",
            fixedBackfill = "bf-key-3333",
            rotatableBackfill = "bf-key-4444"
        )
        assertTrue(validAllDistinct.isValid)
        assertEquals(
            "rtmps://a.rtmps.youtube.com/live2/live-key-1111",
            SettingsValidator.normalizeRtmpTarget("rtmps://a.rtmp.youtube.com/live2/live-key-1111")
        )
    }

    @Test
    fun `rejects invalid camera IP, port range, and channel collision`() {
        assertFalse(SettingsValidator.validateCameraSettings("999.0.0.1", 554, 80, 2, 1).isValid)
        assertFalse(SettingsValidator.validateCameraSettings("192.168.1.2", 0, 80, 2, 1).isValid)
        assertFalse(SettingsValidator.validateCameraSettings("192.168.1.2", 554, 80, 1, 1).isValid)
        assertTrue(SettingsValidator.validateCameraSettings("192.168.1.2", 554, 80, 2, 1).isValid)
    }
}
