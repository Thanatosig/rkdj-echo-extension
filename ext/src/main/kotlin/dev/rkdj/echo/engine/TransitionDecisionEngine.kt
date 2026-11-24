package dev.rkdj.echo.engine

import dev.rkdj.echo.analysis.HarmonicPlaylistSorter
import dev.rkdj.echo.model.*
import kotlin.math.abs

/**
 * RKDJ Decision Engine: Maps Track A (outgoing) and Track B (incoming) musical features
 * to the exact DJ mixing technique from the RKDJ Mixing Playbook.
 */
object TransitionDecisionEngine {

    fun planTransition(
        from: TrackAnalysis,
        to: TrackAnalysis,
        settings: MixSettings = MixSettings()
    ): TransitionPlan {
        val directDiff = abs(to.bpm - from.bpm) / from.bpm
        val doubleDiff = abs(to.bpm * 2.0 - from.bpm) / from.bpm
        val halfDiff = abs(to.bpm * 0.5 - from.bpm) / from.bpm

        val (bestDiff, ratio) = when {
            directDiff <= doubleDiff && directDiff <= halfDiff -> directDiff to 1.0
            doubleDiff <= halfDiff -> doubleDiff to 2.0
            else -> halfDiff to 0.5
        }

        val keyDist = from.key.distanceTo(to.key)
        val vocalOverlap = maxOf(from.vocalOutroProbability, to.vocalIntroProbability)
        val defaultBeats = settings.defaultBlendBars * 4

        val (type, beats, reason) = when {
            // High-energy bass/electronic double drop
            from.bpm >= 138.0 && to.bpm >= 138.0 && bestDiff <= 0.06 && keyDist <= 1 &&
                from.energyOutro > 0.70 && to.energyIntro > 0.70 ->
                Triple(TransitionType.DOUBLE_DROP, 16, "High-energy BPM>=138 harmonic match -> Double Drop")

            // Breakdown swap when outgoing track enters drumless breakdown
            bestDiff <= 0.06 && from.hasBreakdownOutro ->
                Triple(TransitionType.BREAKDOWN_SWAP, 16, "Drumless outro breakdown detected -> Breakdown Swap")

            // Classic seamless club EQ Bass Swap
            bestDiff <= 0.06 && keyDist <= 1 && vocalOverlap < 0.45 ->
                Triple(TransitionType.EQ_BASS_SWAP, defaultBeats, "BPM within 6% & Camelot match (${from.key}->${to.key}) -> EQ Bass Swap")

            // Close BPM but clashing musical keys -> Filter Sweep
            bestDiff <= 0.06 && keyDist >= 2 ->
                Triple(TransitionType.FILTER_SWEEP, 16, "BPM match with key clash (${from.key}->${to.key}) -> Filter Sweep")

            // Close BPM with overlapping vocals -> Echo-Out avoids lyric clash
            bestDiff <= 0.06 && vocalOverlap >= 0.45 ->
                Triple(TransitionType.VOCAL_ECHO_OUT, 8, "Vocal overlap detected -> 3/4-Beat Vocal Echo-Out")

            // Large BPM jump + prominent vocal ending -> Repeating-word Loop Roll Stutter
            bestDiff > 0.08 && from.vocalOutroProbability >= 0.50 && settings.enableCreativeTricks ->
                Triple(TransitionType.LOOP_ROLL_STUTTER, 8, "Large BPM jump + vocal outro -> Beat-Synced Loop Roll Stutter")

            // Large BPM jump + high energy instrumental -> Vinyl Brake
            bestDiff > 0.08 && from.energyOutro > 0.65 && settings.enableCreativeTricks ->
                Triple(TransitionType.VINYL_BRAKE, 4, "Large BPM jump + high-energy outro -> Vinyl Brake")

            // Clean fallback for open-format jumps
            else ->
                Triple(TransitionType.CUT_ON_THE_ONE, 4, "Open-format phrase boundary -> Cut on the One")
        }

        return TransitionPlan(
            fromTrackId = from.trackId,
            toTrackId = to.trackId,
            type = type,
            transitionBeats = beats,
            targetBpmRatio = ratio,
            mixOutSec = from.outroStartSec,
            mixInSec = to.downbeatOffsetSec,
            reason = reason
        )
    }
}
