package dev.rkdj.echo.dsp

import kotlin.math.*

enum class FilterType { LPF, HPF, BPF, LOW_SHELF }

/**
 * Stereo 2nd-order RBJ Biquad Filter using Direct Form II Transposed.
 * Numerically stable under continuous cutoff sweeps (HPF/LPF DJ sweeps & EQ swaps).
 */
class BiquadFilter(
    private val sampleRate: Int = 44100,
    type: FilterType = FilterType.LPF,
    cutoffHz: Double = 1000.0,
    q: Double = 0.707,
    gainDb: Double = 0.0
) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // State per channel (Left = 0, Right = 1)
    private var z1L = 0.0
    private var z2L = 0.0
    private var z1R = 0.0
    private var z2R = 0.0

    init {
        setParams(type, cutoffHz, q, gainDb)
    }

    fun reset() {
        z1L = 0.0; z2L = 0.0
        z1R = 0.0; z2R = 0.0
    }

    fun setParams(
        type: FilterType,
        cutoffHz: Double,
        q: Double = 0.707,
        gainDb: Double = 0.0
    ) {
        val clampedCutoff = cutoffHz.coerceIn(20.0, sampleRate * 0.45)
        val w0 = 2.0 * PI * clampedCutoff / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q.coerceAtLeast(0.1))
        val amp = 10.0.pow(gainDb / 40.0)

        val rawB0: Double
        val rawB1: Double
        val rawB2: Double
        val rawA0: Double
        val rawA1: Double
        val rawA2: Double

        when (type) {
            FilterType.LPF -> {
                rawB0 = (1.0 - cosW0) / 2.0
                rawB1 = 1.0 - cosW0
                rawB2 = (1.0 - cosW0) / 2.0
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW0
                rawA2 = 1.0 - alpha
            }
            FilterType.HPF -> {
                rawB0 = (1.0 + cosW0) / 2.0
                rawB1 = -(1.0 + cosW0)
                rawB2 = (1.0 + cosW0) / 2.0
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW0
                rawA2 = 1.0 - alpha
            }
            FilterType.BPF -> {
                rawB0 = alpha
                rawB1 = 0.0
                rawB2 = -alpha
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW0
                rawA2 = 1.0 - alpha
            }
            FilterType.LOW_SHELF -> {
                val sq = 2.0 * sqrt(amp) * alpha
                rawB0 = amp * ((amp + 1.0) - (amp - 1.0) * cosW0 + sq)
                rawB1 = 2.0 * amp * ((amp - 1.0) - (amp + 1.0) * cosW0)
                rawB2 = amp * ((amp + 1.0) - (amp - 1.0) * cosW0 - sq)
                rawA0 = (amp + 1.0) + (amp - 1.0) * cosW0 + sq
                rawA1 = -2.0 * ((amp - 1.0) + (amp + 1.0) * cosW0)
                rawA2 = (amp + 1.0) + (amp - 1.0) * cosW0 - sq
            }
        }

        b0 = rawB0 / rawA0
        b1 = rawB1 / rawA0
        b2 = rawB2 / rawA0
        a1 = rawA1 / rawA0
        a2 = rawA2 / rawA0
    }

    fun processLeft(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1L
        z1L = b1 * xd - a1 * y + z2L
        z2L = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun processRight(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1R
        z1R = b1 * xd - a1 * y + z2R
        z2R = b2 * xd - a2 * y
        return y.toFloat()
    }
}
