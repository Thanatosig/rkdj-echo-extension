package dev.rkdj.echo.analysis

import dev.rkdj.echo.model.TrackAnalysis
import kotlin.math.abs

/**
 * Reorders an offline playlist using greedy nearest-neighbor traversal over
 * BPM compatibility (including 2:1 half-time / double-time bridges) and Camelot Wheel distance.
 */
object HarmonicPlaylistSorter {

    fun sortPlaylist(tracks: List<TrackAnalysis>): List<TrackAnalysis> {
        if (tracks.size <= 2) return tracks
        val remaining = tracks.toMutableList()
        val ordered = mutableListOf<TrackAnalysis>()

        // Start with the first track in the user's playlist
        ordered.add(remaining.removeAt(0))

        while (remaining.isNotEmpty()) {
            val current = ordered.last()
            val bestNext = remaining.minByOrNull { candidate -> transitionCost(current, candidate) }!!
            ordered.add(bestNext)
            remaining.remove(bestNext)
        }
        return ordered
    }

    fun bestBpmDiffPercent(bpmA: Double, bpmB: Double): Double {
        val direct = abs(bpmB - bpmA) / bpmA
        val doubleTime = abs(bpmB * 2.0 - bpmA) / bpmA
        val halfTime = abs(bpmB * 0.5 - bpmA) / bpmA
        return minOf(direct, doubleTime, halfTime)
    }

    private fun transitionCost(a: TrackAnalysis, b: TrackAnalysis): Double {
        val bpmCost = bestBpmDiffPercent(a.bpm, b.bpm) * 100.0
        val keyCost = a.key.distanceTo(b.key) * 3.5
        val energyJump = abs(a.energyOutro - b.energyIntro) * 5.0
        return bpmCost + keyCost + energyJump
    }
}
