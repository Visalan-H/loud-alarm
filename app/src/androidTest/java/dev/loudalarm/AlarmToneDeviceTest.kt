package dev.loudalarm

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds the real tone on a real audio stack. Unit tests can't catch this: a wrong AudioTrack state
 * check once threw the tone away on every ring while all JVM tests passed.
 */
class AlarmToneDeviceTest {
    @Test
    fun toneBuildsAndPlays() {
        val tone = AlarmTone()
        try {
            tone.play()
            assertTrue("tone should be playing", tone.isPlaying)
        } finally {
            tone.release()
        }
    }
}
