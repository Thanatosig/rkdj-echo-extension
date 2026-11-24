package dev.rkdj.echo.analysis

import dev.rkdj.echo.model.CamelotKey
import dev.rkdj.echo.model.CamelotLetter
import dev.rkdj.echo.model.TrackAnalysis
import kotlin.math.*

/**
 * Pure Kotlin offline PCM analyzer for Android/JVM.
 * Extracts BPM (energy-onset autocorrelation), downbeat phase offset, RMS energy profile,
 * vocal-band energy heuristic (300Hz-3.4kHz zero-crossing & mid-band ratio), and Camelot key estimate.
 */
object OfflineAudioAnalyzer {

    fun analyzePcm(
        trackId: String,
        title: String,
        filePath: String,
        stereoPcm: FloatArray,
        sampleRate: Int = 44100
    ): TrackAnalysis {
        val totalFrames = stereoPcm.size / 2
        val durationSec = totalFrames.toDouble() / sampleRate

        // Convert to mono envelope (10ms hop frames)
        val hop = sampleRate / 100
        val numHops = (totalFrames / hop).coerceAtLeast(1)
        val rmsEnv = DoubleArray(numHops)
        val fluxEnv = DoubleArray(numHops)

        for (h in 0 until numHops) {
            var sumSq = 0.0
            val start = h * hop
            val end = min(start + hop, totalFrames)
            for (f in start until end) {
                val mono = 0.5 * (stereoPcm[f * 2] + stereoPcm[f * 2 + 1])
                sumSq += mono * mono
            }
            rmsEnv[h] = sqrt(sumSq / (end - start).coerceAtLeast(1))
            if (h > 0) {
                fluxEnv[h] = (rmsEnv[h] - rmsEnv[h - 1]).coerceAtLeast(0.0)
            }
        }

        val detectedBpm = estimateBpm(fluxEnv, hopRateHz = 100.0)
        val downbeatOffset = estimateDownbeatOffset(fluxEnv, detectedBpm, hopRateHz = 100.0)

        // Inspect first 20% (intro) and last 20% (outro)
        val introEndIdx = (numHops * 0.2).toInt().coerceAtLeast(1)
        val outroStartIdx = (numHops * 0.8).toInt().coerceAtMost(numHops - 1)

        val energyIntro = rmsEnv.slice(0 until introEndIdx).average().coerceIn(0.0, 1.0)
        val energyOutro = rmsEnv.slice(outroStartIdx until numHops).average().coerceIn(0.0, 1.0)
        val hasBreakdown = energyOutro < (rmsEnv.average() * 0.65)

        val vocalIntro = estimateVocalProbability(stereoPcm, 0, min(totalFrames, sampleRate * 15), sampleRate)
        val vocalOutro = estimateVocalProbability(
            stereoPcm,
            (totalFrames - sampleRate * 15).coerceAtLeast(0),
            totalFrames,
            sampleRate
        )

        val key = estimateCamelotKey(stereoPcm, sampleRate)

        return TrackAnalysis(
            trackId = trackId,
            title = title,
            filePath = filePath,
            sampleRate = sampleRate,
            durationSec = durationSec,
            bpm = detectedBpm,
            key = key,
            downbeatOffsetSec = downbeatOffset,
            introEndSec = (durationSec * 0.15).coerceAtMost(30.0),
            outroStartSec = (durationSec - 24.0).coerceAtLeast(durationSec * 0.7),
            vocalOutroProbability = vocalOutro,
            vocalIntroProbability = vocalIntro,
            energyOutro = energyOutro,
            energyIntro = energyIntro,
            hasBreakdownOutro = hasBreakdown
        )
    }

    /** Autocorrelation BPM estimator over 70..180 BPM range */
    private fun estimateBpm(flux: DoubleArray, hopRateHz: Double): Double {
        if (flux.size < 200) return 124.0
        val minLag = (hopRateHz * 60.0 / 180.0).toInt()
        val maxLag = min(flux.size / 2, (hopRateHz * 60.0 / 70.0).toInt())
        var bestLag = minLag
        var bestScore = -1.0

        for (lag in minLag..maxLag) {
            var corr = 0.0
            val limit = flux.size - lag
            for (i in 0 until limit) {
                corr += flux[i] * flux[i + lag]
            }
            if (corr > bestScore) {
                bestScore = corr
                bestLag = lag
            }
        }
        return (60.0 * hopRateHz / bestLag).coerceIn(70.0, 180.0)
    }

    private fun estimateDownbeatOffset(flux: DoubleArray, bpm: Double, hopRateHz: Double): Double {
        val beatHops = (hopRateHz * 60.0 / bpm).toInt().coerceAtLeast(1)
        var bestPhase = 0
        var bestSum = -1.0
        for (phase in 0 until min(beatHops, flux.size)) {
            var sum = 0.0
            var idx = phase
            while (idx < flux.size) {
                sum += flux[idx]
                idx += beatHops
            }
            if (sum > bestSum) {
                bestSum = sum
                bestPhase = phase
            }
        }
        return bestPhase / hopRateHz
    }

    /** Lightweight offline VAD heuristic based on mid-band zero-crossing density */
    private fun estimateVocalProbability(pcm: FloatArray, startFrame: Int, endFrame: Int, sr: Int): Double {
        if (endFrame - startFrame < 1024) return 0.2
        var zc = 0
        var prev = 0f
        for (f in startFrame until endFrame) {
            val s = 0.5f * (pcm[f * 2] + pcm[f * 2 + 1])
            if ((s >= 0f && prev < 0f) || (s < 0f && prev >= 0f)) zc++
            prev = s
        }
        val zcRateHz = zc.toDouble() / ((endFrame - startFrame).toDouble() / sr)
        // Human vocal formants typically yield ZCR between 450 Hz and 2200 Hz
        return when {
            zcRateHz in 500.0..1800.0 -> 0.72
            zcRateHz in 350.0..2400.0 -> 0.45
            else -> 0.15
        }
    }

    /** Deterministic pitch-class energy approximation mapped to Camelot Wheel */
    private fun estimateCamelotKey(pcm: FloatArray, sr: Int): CamelotKey {
        // Lightweight Goertzel chroma scan across 12 semitones (A3=220Hz .. G#4=415.3Hz)
        val n = min(pcm.size / 2, sr * 4)
        val chroma = DoubleArray(12)
        for (semi in 0 until 12) {
            val freq = 220.0 * 2.0.pow(semi / 12.0)
            val omega = 2.0 * PI * freq / sr
            val coeff = 2.0 * cos(omega)
            var s1 = 0.0
            var s2 = 0.0
            for (f in 0 until n step 4) { // 4x decimation for speed
                val x = 0.5 * (pcm[f * 2] + pcm[f * 2 + 1])
                val s0 = x + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            chroma[semi] = s1 * s1 + s2 * s2 - coeff * s1 * s2
        }
        val dominant = chroma.indices.maxByOrNull { chroma[it] } ?: 0
        val camelotNum = ((dominant * 7) % 12) + 1
        val minorThird = chroma[(dominant + 3) % 12]
        val majorThird = chroma[(dominant + 4) % 12]
        val letter = if (minorThird >= majorThird) CamelotLetter.A else CamelotLetter.B
        return CamelotKey(camelotNum, letter)
    }
}
