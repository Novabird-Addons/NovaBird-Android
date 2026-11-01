package org.novabird.feature.ai.speech

import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A WAV file of the Helper's voice (PCM 16 bit or float 32 bit), ready for an [AudioTrack]. */
internal class Wav(val sampleRate: Int, val channels: Int, val encoding: Int, private val data: ByteArray) {
    private val frameBytes = channels * SHORT_BYTES

    /** Writes all samples (blocks while the track plays); returns the number of frames. */
    fun writeTo(track: AudioTrack): Long {
        var off = 0
        while (off < data.size) {
            val n = track.write(data, off, data.size - off)
            if (n <= 0) break
            off += n
        }
        return (data.size / frameBytes).toLong()
    }

    /** Writes [seconds] of silence; returns the number of frames. */
    fun writeSilence(track: AudioTrack, seconds: Float): Long {
        val frames = (sampleRate * seconds).toInt()
        val silence = ByteArray(frames * frameBytes)
        track.write(silence, 0, silence.size)
        return frames.toLong()
    }

    companion object {
        private const val SHORT_BYTES = 2
        private const val FLOAT_BYTES = 4
        private const val FORMAT_PCM = 1
        private const val FORMAT_FLOAT = 3
        private const val HEADER = 12
        private const val CHUNK_HEADER = 8

        @Suppress("MagicNumber")
        fun parse(bytes: ByteArray): Wav {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > HEADER && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "wav" }
            var pos = HEADER
            var format = 0
            var channels = 1
            var rate = 0
            var bits = 0
            while (pos + CHUNK_HEADER <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = b.getInt(pos + 4)
                val body = pos + CHUNK_HEADER
                when (id) {
                    "fmt " -> {
                        format = b.getShort(body).toInt() and 0xFFFF
                        channels = b.getShort(body + 2).toInt()
                        rate = b.getInt(body + 4)
                        bits = b.getShort(body + 14).toInt()
                        // WAVE_FORMAT_EXTENSIBLE: the real format is in the sub format
                        if (format == 0xFFFE && size >= 26) format = b.getShort(body + 24).toInt() and 0xFFFF
                    }

                    "data" -> {
                        val end = if (size < 0 || body + size > bytes.size) bytes.size else body + size
                        val pcm = when {
                            format == FORMAT_PCM && bits == 16 -> bytes.copyOfRange(body, end)
                            format == FORMAT_FLOAT && bits == 32 -> floatToPcm16(b, body, end)
                            else -> throw IllegalArgumentException("wav format $format/$bits")
                        }
                        return Wav(rate, channels, AudioFormat.ENCODING_PCM_16BIT, pcm)
                    }
                }
                pos = body + size + (size and 1)
            }
            throw IllegalArgumentException("wav")
        }

        private fun floatToPcm16(b: ByteBuffer, from: Int, to: Int): ByteArray {
            val out = ByteBuffer.allocate((to - from) / FLOAT_BYTES * SHORT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var p = from
            while (p + FLOAT_BYTES <= to) {
                out.putShort((b.getFloat(p).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                p += FLOAT_BYTES
            }
            return out.array()
        }
    }
}
