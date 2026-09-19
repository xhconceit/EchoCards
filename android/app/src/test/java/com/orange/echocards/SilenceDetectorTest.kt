package com.orange.echocards

import com.orange.echocards.speech.cloud.SilenceDetector
import com.orange.echocards.speech.cloud.VadState
import org.junit.Assert.assertEquals
import org.junit.Test

class SilenceDetectorTest {

    private fun detector() = SilenceDetector(
        speechStartRms = 500,
        speechEndRms = 300,
        trailingSilenceMs = 800,
    )

    @Test
    fun idle_untilSpeechAboveThreshold() {
        val d = detector()
        assertEquals(VadState.IDLE, d.accept(100, 0))
        assertEquals(VadState.IDLE, d.accept(400, 100))
        assertEquals(VadState.SPEAKING, d.accept(600, 200))
    }

    @Test
    fun trailingSilence_reachesEnded() {
        val d = detector()
        d.accept(600, 0)
        assertEquals(VadState.SPEAKING, d.accept(200, 100))
        assertEquals(VadState.SPEAKING, d.accept(200, 500))
        assertEquals(VadState.ENDED, d.accept(200, 900))
    }

    @Test
    fun reSpeaking_resetsSilenceTimer() {
        val d = detector()
        d.accept(600, 0)
        d.accept(200, 100)
        d.accept(200, 400)
        d.accept(600, 500)
        assertEquals(VadState.SPEAKING, d.accept(200, 600))
        assertEquals(VadState.SPEAKING, d.accept(200, 1000))
        assertEquals(VadState.ENDED, d.accept(200, 1400))
    }

    @Test
    fun ended_isSticky() {
        val d = detector()
        d.accept(600, 0)
        d.accept(200, 0)
        d.accept(200, 800)
        assertEquals(VadState.ENDED, d.accept(600, 900))
    }
}
