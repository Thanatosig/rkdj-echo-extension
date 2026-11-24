package dev.rkdj.echo.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min

@Serializable
enum class CamelotLetter { A, B }

@Serializable
data class CamelotKey(
    val number: Int, // 1..12
    val letter: CamelotLetter
) {
    init {
        require(number in 1..12) { "Camelot number must be between 1 and 12" }
    }

    /**
     * Computes harmonic distance on the Camelot Wheel:
     * 0 = identical key or relative major/minor (e.g., 8A <-> 8B)
     * 1 = adjacent harmonic step (e.g., 8A <-> 9A or 7A)
     * >=2 = harmonic clash
     */
    fun distanceTo(other: CamelotKey): Int {
        val ringDist = min(abs(number - other.number), 12 - abs(number - other.number))
        return if (letter == other.letter) {
            ringDist
        } else {
            if (ringDist == 0) 0 else ringDist + 1
        }
    }

    override fun toString(): String = "$number$letter"
}

@Serializable
enum class TransitionType {
    PHRASE_BLEND,
    EQ_BASS_SWAP,
    FILTER_SWEEP,
    BREAKDOWN_SWAP,
    CUT_ON_THE_ONE,
    LOOP_ROLL_STUTTER,
    VOCAL_ECHO_OUT,
    VINYL_BRAKE,
    BACKSPIN,
    DOUBLE_DROP
}

@Serializable
data class TrackAnalysis(
    val trackId: String,
    val title: String,
    val filePath: String,
    val sampleRate: Int = 44100,
    val durationSec: Double,
    val bpm: Double,
    val key: CamelotKey,
    val downbeatOffsetSec: Double,
    val introEndSec: Double,
    val outroStartSec: Double,
    val vocalOutroProbability: Double, // 0.0..1.0
    val vocalIntroProbability: Double, // 0.0..1.0
    val energyOutro: Double,           // 0.0..1.0
    val energyIntro: Double,           // 0.0..1.0
    val hasBreakdownOutro: Boolean = false
)

@Serializable
data class TransitionPlan(
    val fromTrackId: String,
    val toTrackId: String,
    val type: TransitionType,
    val transitionBeats: Int,
    val targetBpmRatio: Double, // 1.0 = direct, 2.0 = double-time, 0.5 = half-time
    val mixOutSec: Double,
    val mixInSec: Double,
    val reason: String
)

@Serializable
data class MixSettings(
    val harmonicSortEnabled: Boolean = true,
    val enableCreativeTricks: Boolean = true, // Loop Roll, Echo-Out, Vinyl Brake, Backspin
    val defaultBlendBars: Int = 4,            // 4 bars = 16 beats
    val targetSampleRate: Int = 44100
)
