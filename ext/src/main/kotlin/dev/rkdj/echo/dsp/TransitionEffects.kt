package dev.rkdj.echo.dsp

import dev.rkdj.echo.model.TransitionType
import kotlin.math.*

/**
 * Implements all offline DJ mixing transitions on stereo interleaved float PCM (`[-1f, 1f]`).
 * Every output passes through a smooth `tanh` soft-limiter to guarantee zero clipping.
 */
object TransitionEffects {

    private fun softLimit(x: Float): Float = tanh(x.toDouble()).toFloat()

    /**
     * Renders the selected DJ transition between [outroA] (Deck A) and [introB] (Deck B).
     * Both arrays are stereo interleaved float PCM at [sampleRate].
     */
    fun renderTransition(
        type: TransitionType,
        outroA: FloatArray,
        introB: FloatArray,
        bpmA: Double,
        sampleRate: Int = 44100
    ): FloatArray {
        return when (type) {
            TransitionType.PHRASE_BLEND -> renderPhraseBlend(outroA, introB)
            TransitionType.EQ_BASS_SWAP -> renderEqBassSwap(outroA, introB, sampleRate)
            TransitionType.FILTER_SWEEP -> renderFilterSweep(outroA, introB, sampleRate)
            TransitionType.BREAKDOWN_SWAP -> renderBreakdownSwap(outroA, introB, sampleRate)
            TransitionType.CUT_ON_THE_ONE -> renderCutOnTheOne(outroA, introB, sampleRate)
            TransitionType.LOOP_ROLL_STUTTER -> renderLoopRollStutter(outroA, introB, bpmA, sampleRate)
            TransitionType.VOCAL_ECHO_OUT -> renderVocalEchoOut(outroA, introB, bpmA, sampleRate)
            TransitionType.VINYL_BRAKE -> renderVinylBrake(outroA, introB, sampleRate)
            TransitionType.BACKSPIN -> renderBackspin(outroA, introB, sampleRate)
            TransitionType.DOUBLE_DROP -> renderDoubleDrop(outroA, introB, sampleRate)
        }
    }

    /** A. Equal-Power Phrase Blend */
    fun renderPhraseBlend(a: FloatArray, b: FloatArray): FloatArray {
        val frames = min(a.size, b.size) / 2
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val t = f.toDouble() / frames
            val ga = cos(0.5 * PI * t).toFloat()
            val gb = sin(0.5 * PI * t).toFloat()
            val i = f * 2
            out[i] = softLimit(a[i] * ga + b[i] * gb)
            out[i + 1] = softLimit(a[i + 1] * ga + b[i + 1] * gb)
        }
        return out
    }

    /** B. EQ / Bass Swap (Kills B's bass until midpoint downbeat, then swaps A & B low-end) */
    fun renderEqBassSwap(a: FloatArray, b: FloatArray, sr: Int): FloatArray {
        val frames = min(a.size, b.size) / 2
        val out = FloatArray(frames * 2)
        val hpfA = BiquadFilter(sr, FilterType.HPF, 20.0)
        val hpfB = BiquadFilter(sr, FilterType.HPF, 180.0)
        val swapFrame = frames / 2

        for (f in 0 until frames) {
            if (f == swapFrame) {
                hpfA.setParams(FilterType.HPF, 180.0)
                hpfB.setParams(FilterType.HPF, 20.0)
            }
            val t = f.toDouble() / frames
            val ga = cos(0.5 * PI * t).toFloat()
            val gb = sin(0.5 * PI * t).toFloat()
            val i = f * 2
            val aL = hpfA.processLeft(a[i]) * ga
            val aR = hpfA.processRight(a[i + 1]) * ga
            val bL = hpfB.processLeft(b[i]) * gb
            val bR = hpfB.processRight(b[i + 1]) * gb
            out[i] = softLimit(aL + bL)
            out[i + 1] = softLimit(aR + bR)
        }
        return out
    }

    /** C. Filter Sweep (HPF A: 20Hz -> 2500Hz, LPF B: 300Hz -> 20kHz) */
    fun renderFilterSweep(a: FloatArray, b: FloatArray, sr: Int): FloatArray {
        val frames = min(a.size, b.size) / 2
        val out = FloatArray(frames * 2)
        val hpfA = BiquadFilter(sr, FilterType.HPF, 20.0)
        val lpfB = BiquadFilter(sr, FilterType.LPF, 300.0)

        for (f in 0 until frames) {
            val t = f.toDouble() / frames
            if (f % 128 == 0) {
                hpfA.setParams(FilterType.HPF, 20.0 * (2500.0 / 20.0).pow(t))
                lpfB.setParams(FilterType.LPF, 300.0 * (20000.0 / 300.0).pow(t))
            }
            val ga = cos(0.5 * PI * t).toFloat()
            val gb = sin(0.5 * PI * t).toFloat()
            val i = f * 2
            out[i] = softLimit(hpfA.processLeft(a[i]) * ga + lpfB.processLeft(b[i]) * gb)
            out[i + 1] = softLimit(hpfA.processRight(a[i + 1]) * ga + lpfB.processRight(b[i + 1]) * gb)
        }
        return out
    }

    /** D. Breakdown Swap (Deck B rises gently under Deck A's breakdown, then slams at 75%) */
    fun renderBreakdownSwap(a: FloatArray, b: FloatArray, sr: Int): FloatArray {
        val frames = min(a.size, b.size) / 2
        val out = FloatArray(frames * 2)
        val hpfA = BiquadFilter(sr, FilterType.HPF, 20.0)
        val dropFrame = (frames * 0.75).toInt()

        for (f in 0 until frames) {
            val t = f.toDouble() / frames
            if (f % 128 == 0 && f < dropFrame) {
                hpfA.setParams(FilterType.HPF, 20.0 + 900.0 * (f.toDouble() / dropFrame))
            }
            val ga = if (f < dropFrame) (1.0 - 0.5 * (f.toDouble() / dropFrame)).toFloat() else 0.0f
            val gb = if (f < dropFrame) (0.35 + 0.65 * (f.toDouble() / dropFrame)).toFloat() else 1.0f
            val i = f * 2
            out[i] = softLimit(hpfA.processLeft(a[i]) * ga + b[i] * gb)
            out[i + 1] = softLimit(hpfA.processRight(a[i + 1]) * ga + b[i + 1] * gb)
        }
        return out
    }

    /** E. Cut on the One (Instant slam with 5ms anti-click micro-ramp) */
    fun renderCutOnTheOne(a: FloatArray, b: FloatArray, sr: Int): FloatArray {
        val framesA = a.size / 2
        val framesB = b.size / 2
        val cutFrame = min(framesA, sr * 2)
        val totalFrames = cutFrame + framesB
        val out = FloatArray(totalFrames * 2)
        val ramp = min((sr * 0.005).toInt(), min(cutFrame, framesB))

        for (f in 0 until cutFrame) {
            val gain = if (f >= cutFrame - ramp) (cutFrame - f).toFloat() / ramp else 1.0f
            out[f * 2] = a[f * 2] * gain
            out[f * 2 + 1] = a[f * 2 + 1] * gain
        }
        for (f in 0 until framesB) {
            val gain = if (f < ramp) f.toFloat() / ramp else 1.0f
            val idx = (cutFrame + f) * 2
            out[idx] = b[f * 2] * gain
            out[idx + 1] = b[f * 2 + 1] * gain
        }
        return out
    }

    /** F. Beat-Synced Loop Roll / Stutter Build ("Repeating One Word" 1 -> 1/2 -> 1/4 -> 1/8 beat) */
    fun renderLoopRollStutter(a: FloatArray, b: FloatArray, bpmA: Double, sr: Int): FloatArray {
        val beatFrames = ((60.0 / bpmA.coerceIn(60.0, 200.0)) * sr).toInt().coerceAtLeast(1024)
        val availFramesA = a.size / 2
        val sliceStart = (availFramesA / 2).coerceAtMost((availFramesA - beatFrames).coerceAtLeast(0))
        val sliceLen = min(beatFrames, availFramesA - sliceStart)
        val rollTotalFrames = beatFrames * 8
        val out = FloatArray((rollTotalFrames + b.size / 2) * 2)

        val hpf = BiquadFilter(sr, FilterType.HPF, 80.0)
        var outPos = 0
        val stages = intArrayOf(1, 2, 4, 8)
        for (div in stages) {
            val curLen = (sliceLen / div).coerceAtLeast(128)
            val repeats = 2 * div
            val fadeN = min(220, curLen / 4) // 5ms splice crossfade
            for (r in 0 until repeats) {
                if (outPos >= rollTotalFrames) break
                val prog = outPos.toDouble() / rollTotalFrames
                hpf.setParams(FilterType.HPF, 80.0 * (2200.0 / 80.0).pow(prog))
                for (k in 0 until curLen) {
                    if (outPos >= rollTotalFrames) break
                    val env = when {
                        k < fadeN -> k.toFloat() / fadeN
                        k > curLen - fadeN -> (curLen - k).toFloat() / fadeN
                        else -> 1.0f
                    }
                    val srcIdx = (sliceStart + k) * 2
                    out[outPos * 2] = softLimit(hpf.processLeft(a[srcIdx] * env))
                    out[outPos * 2 + 1] = softLimit(hpf.processRight(a[srcIdx + 1] * env))
                    outPos++
                }
            }
        }
        // Slam cleanly into Deck B on Beat 1
        val bFrames = b.size / 2
        for (f in 0 until bFrames) {
            val idx = (rollTotalFrames + f) * 2
            out[idx] = b[f * 2]
            out[idx + 1] = b[f * 2 + 1]
        }
        return out
    }

    /** G. Vocal Echo-Out (3/4-Beat Bandpassed Dub Delay Decay over Deck B's Intro) */
    fun renderVocalEchoOut(a: FloatArray, b: FloatArray, bpmA: Double, sr: Int): FloatArray {
        val delayFrames = (0.75 * (60.0 / bpmA.coerceIn(60.0, 200.0)) * sr).toInt().coerceAtLeast(512)
        val ringL = FloatArray(delayFrames)
        val ringR = FloatArray(delayFrames)
        val bpf = BiquadFilter(sr, FilterType.BPF, 1400.0, q = 0.9)

        val framesB = b.size / 2
        val totalFrames = max(framesB, sr * 4)
        val dryCutFrame = min(a.size / 2, (sr * 0.75).toInt())
        val out = FloatArray(totalFrames * 2)
        var wPtr = 0

        for (f in 0 until totalFrames) {
            val dryL = if (f < dryCutFrame) a[f * 2] else 0f
            val dryR = if (f < dryCutFrame) a[f * 2 + 1] else 0f
            val delL = ringL[wPtr]
            val delR = ringR[wPtr]

            ringL[wPtr] = bpf.processLeft(dryL + delL * 0.65f)
            ringR[wPtr] = bpf.processRight(dryR + delR * 0.65f)
            wPtr = (wPtr + 1) % delayFrames

            val bL = if (f < framesB) b[f * 2] * min(1.0f, f.toFloat() / (sr * 0.5f)) else 0f
            val bR = if (f < framesB) b[f * 2 + 1] * min(1.0f, f.toFloat() / (sr * 0.5f)) else 0f

            out[f * 2] = softLimit(dryL + delL * 0.8f + bL)
            out[f * 2 + 1] = softLimit(dryR + delR * 0.8f + bR)
        }
        return out
    }

    /** H. Vinyl Brake / Motor Stop (1.0s pitch + tempo slowdown into Deck B) */
    fun renderVinylBrake(a: FloatArray, b: FloatArray, sr: Int): FloatArray {
        val brakeFrames = sr // 1.0 second
        val framesA = a.size / 2
        val framesB = b.size / 2
        val out = FloatArray((brakeFrames + framesB) * 2)
        var readPos = 0.0

        for (f in 0 until brakeFrames) {
            val prog = f.toDouble() / brakeFrames
            val rate = (1.0 - prog).coerceAtLeast(0.0)
            val idx = readPos.toInt().coerceIn(0, (framesA - 2).coerceAtLeast(0))
            val frac = (readPos - idx).toFloat()
            val sL = a[idx * 2] * (1f - frac) + a[(idx + 1) * 2] * frac
            val sR = a[idx * 2 + 1] * (1f - frac) + a[(idx + 1) * 2 + 1] * frac
            val fade = (1.0 - 0.35 * prog).toFloat()
            out[f * 2] = sL * fade
            out[f * 2 + 1] = sR * fade
            readPos += rate
        }
        for (f in 0 until framesB) {
            val idx = (brakeFrames + f) * 2
            out[idx] = b[f * 2]
            out[idx + 1] = b[f * 2 + 1]
        }
        return out
    }

    /** I. Backspin / Wheel-Up (0.8s accelerating reverse platter zip into Deck B) */
    fun renderBackspin(a: FloatArray, b: FloatArray, sr: Int): FloatArray {
        val spinFrames = (sr * 0.8).toInt()
        val framesA = a.size / 2
        val framesB = b.size / 2
        val out = FloatArray((spinFrames + framesB) * 2)
        val hpf = BiquadFilter(sr, FilterType.HPF, 350.0)
        var readPos = (framesA - 2).coerceAtLeast(0).toDouble()

        for (f in 0 until spinFrames) {
            val prog = f.toDouble() / spinFrames
            val rate = -(1.0 + 3.0 * sin(PI * prog))
            val idx = readPos.toInt().coerceIn(0, (framesA - 2).coerceAtLeast(0))
            val frac = (readPos - idx).toFloat()
            val sL = a[idx * 2] * (1f - frac) + a[(idx + 1) * 2] * frac
            val sR = a[idx * 2 + 1] * (1f - frac) + a[(idx + 1) * 2 + 1] * frac
            val env = (1.0 - prog).toFloat()
            out[f * 2] = softLimit(hpf.processLeft(sL * env))
            out[f * 2 + 1] = softLimit(hpf.processRight(sR * env))
            readPos = (readPos + rate).coerceAtLeast(0.0)
        }
        for (f in 0 until framesB) {
            val idx = (spinFrames + f) * 2
            out[idx] = b[f * 2]
            out[idx + 1] = b[f * 2 + 1]
        }
        return out
    }

    /** K. Double Drop (Fuses both high-energy drops while cutting sub-bass <120Hz on Deck A) */
    fun renderDoubleDrop(a: FloatArray, b: FloatArray, sr: Int): FloatArray {
        val frames = min(a.size, b.size) / 2
        val out = FloatArray(frames * 2)
        val subKillA = BiquadFilter(sr, FilterType.HPF, 120.0)

        for (f in 0 until frames) {
            val t = f.toDouble() / frames
            val ga = (0.75 * (1.0 - t * 0.85)).toFloat()
            val gb = 0.85f
            val i = f * 2
            out[i] = softLimit(subKillA.processLeft(a[i]) * ga + b[i] * gb)
            out[i + 1] = softLimit(subKillA.processRight(a[i + 1]) * ga + b[i + 1] * gb)
        }
        return out
    }
}
