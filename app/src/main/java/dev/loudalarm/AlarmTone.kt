package dev.loudalarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A synthesized alarm tone on the alarm stream. Generating it in code means no sound file can go
 * missing, and the alarm stream plays even when the phone is on silent.
 */
class AlarmTone {
    private val samples = alarmPattern()

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(ATTRIBUTES)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(samples.size * 2)
        .build()
        .apply {
            // A static track reports STATE_NO_STATIC_DATA until written, so check after the write.
            val written = write(samples, 0, samples.size)
            if (written != samples.size || state != AudioTrack.STATE_INITIALIZED) {
                release()
                error("AudioTrack failed to initialize (wrote $written, state $state)")
            }
            setLoopPoints(0, samples.size, -1)
        }

    val isPlaying: Boolean get() = track.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Starts the loop, or restarts it if something stopped it. */
    fun play() {
        when (track.playState) {
            AudioTrack.PLAYSTATE_PLAYING -> return
            AudioTrack.PLAYSTATE_STOPPED -> {
                track.reloadStaticData()
                track.setLoopPoints(0, samples.size, -1)
            }
        }
        track.play()
    }

    fun release() {
        runCatching { track.stop() }
        track.release()
    }

    companion object {
        const val SAMPLE_RATE = 44_100

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

/**
 * One second of audio: four 100 ms square-wave beeps alternating 2.9 and 3.4 kHz, then silence.
 * Square waves near 3 kHz sit where human hearing is most sensitive.
 */
fun alarmPattern(rate: Int = AlarmTone.SAMPLE_RATE): ShortArray {
    val out = ShortArray(rate)
    val beep = rate / 10
    val gap = rate / 20
    val fade = rate / 400
    for (i in 0 until 4) {
        val start = i * (beep + gap)
        val freq = if (i % 2 == 0) 2900.0 else 3400.0
        for (n in 0 until beep) {
            val wave = if (sin(2 * PI * freq * n / rate) >= 0) 1.0 else -1.0
            val envelope = min(1.0, min(n, beep - 1 - n) / fade.toDouble())
            out[start + n] = (wave * envelope * 0.95 * Short.MAX_VALUE).toInt().toShort()
        }
    }
    return out
}
