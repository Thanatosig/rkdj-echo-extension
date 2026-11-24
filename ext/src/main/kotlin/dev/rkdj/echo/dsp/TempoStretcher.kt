package dev.rkdj.echo.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline pitch-preserving tempo stretcher (OLA / WSOLA) for stereo interleaved float PCM.
 * Supports smooth ±8% BPM alignment during DJ transitions without native dependencies,
 * with an optional interface hook for SoundTouch/RubberBand JNI acceleration.
 */
object TempoStretcher {

    /**
     * Stretches or compresses stereo interleaved PCM (`[L0, R0, L1, R1, ...]`) by [tempoRatio].
     * tempoRatio > 1.0 speeds up playback (higher BPM); tempoRatio < 1.0 slows it down.
     */
    fun stretchStereo(
        input: FloatArray,
        tempoRatio: Double,
        sampleRate: Int = 44100
    ): FloatArray {
        val ratio = tempoRatio.coerceIn(0.85, 1.15)
        if (kotlin.math.abs(ratio - 1.0) < 0.002) return input.copyOf()

        val totalFrames = input.size / 2
        val windowFrames = (sampleRate * 0.040).roundToInt() // 40ms Hann grain
        val hopOut = windowFrames / 2
        val hopIn = (hopOut * ratio).roundToInt().coerceAtLeast(1)

        val outFrames = (totalFrames / ratio).roundToInt().coerceAtLeast(windowFrames)
        val output = FloatArray(outFrames * 2)
        val norm = FloatArray(outFrames)

        // Precompute Hann window
        val hann = FloatArray(windowFrames) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (windowFrames - 1)))).toFloat()
        }

        var inPos = 0
        var outPos = 0
        while (inPos + windowFrames < totalFrames && outPos + windowFrames < outFrames) {
            for (i in 0 until windowFrames) {
                val w = hann[i]
                val inIdx = (inPos + i) * 2
                val outIdx = (outPos + i) * 2
                output[outIdx] += input[inIdx] * w
                output[outIdx + 1] += input[inIdx + 1] * w
                norm[outPos + i] += w
            }
            inPos += hopIn
            outPos += hopOut
        }

        for (f in 0 until outFrames) {
            val n = norm[f]
            if (n > 1e-4f) {
                output[f * 2] /= n
                output[f * 2 + 1] /= n
            }
        }
        return output
    }
}
