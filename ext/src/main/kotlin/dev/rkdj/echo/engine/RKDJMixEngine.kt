package dev.rkdj.echo.engine

import dev.rkdj.echo.analysis.HarmonicPlaylistSorter
import dev.rkdj.echo.dsp.TempoStretcher
import dev.rkdj.echo.dsp.TransitionEffects
import dev.rkdj.echo.model.MixSettings
import dev.rkdj.echo.model.TrackAnalysis
import dev.rkdj.echo.model.TransitionPlan
import kotlin.math.min

/**
 * Orchestrates offline dual-deck mixing for a full playlist.
 * Streams 16-bit stereo PCM chunks directly to [LocalMixStreamServer] so Echo Music's
 * Media3/ExoPlayer plays the entire Auto-DJ set seamlessly offline.
 */
class RKDJMixEngine(
    private val settings: MixSettings = MixSettings()
) {
    data class MixBlueprint(
        val orderedTracks: List<TrackAnalysis>,
        val transitions: List<TransitionPlan>
    )

    fun buildPlaylistBlueprint(tracks: List<TrackAnalysis>): MixBlueprint {
        val ordered = if (settings.harmonicSortEnabled) {
            HarmonicPlaylistSorter.sortPlaylist(tracks)
        } else {
            tracks
        }
        val plans = mutableListOf<TransitionPlan>()
        for (i in 0 until ordered.size - 1) {
            plans.add(TransitionDecisionEngine.planTransition(ordered[i], ordered[i + 1], settings))
        }
        return MixBlueprint(ordered, plans)
    }

    /**
     * Mixes two decoded stereo PCM buffers using the planned transition and returns
     * the stitched stereo float PCM transition bridge.
     */
    fun renderPairTransition(
        trackA: TrackAnalysis,
        pcmA: FloatArray,
        trackB: TrackAnalysis,
        pcmB: FloatArray,
        plan: TransitionPlan
    ): FloatArray {
        val sr = settings.targetSampleRate
        val transitionSec = (plan.transitionBeats * 60.0 / trackA.bpm).coerceIn(1.5, 16.0)
        val windowSamples = (transitionSec * sr).toInt() * 2

        val outroStartIdx = ((plan.mixOutSec * sr).toInt() * 2).coerceIn(0, (pcmA.size - windowSamples).coerceAtLeast(0))
        val outroSliceA = pcmA.copyOfRange(outroStartIdx, min(pcmA.size, outroStartIdx + windowSamples))

        val introStartIdx = ((plan.mixInSec * sr).toInt() * 2).coerceIn(0, (pcmB.size - windowSamples).coerceAtLeast(0))
        var introSliceB = pcmB.copyOfRange(introStartIdx, min(pcmB.size, introStartIdx + windowSamples))

        // Tempo-stretch Track B's intro to lock with Track A's BPM if within direct/ratio range
        val effectiveBpmB = trackB.bpm * plan.targetBpmRatio
        val tempoRatio = trackA.bpm / effectiveBpmB
        if (tempoRatio in 0.92..1.08) {
            introSliceB = TempoStretcher.stretchStereo(introSliceB, tempoRatio, sr)
        }

        return TransitionEffects.renderTransition(
            type = plan.type,
            outroA = outroSliceA,
            introB = introSliceB,
            bpmA = trackA.bpm,
            sampleRate = sr
        )
    }

    /** Converts normalized stereo float PCM `[-1f, 1f]` into little-endian 16-bit PCM bytes */
    fun floatToPcm16Bytes(pcm: FloatArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        return out
    }
}
