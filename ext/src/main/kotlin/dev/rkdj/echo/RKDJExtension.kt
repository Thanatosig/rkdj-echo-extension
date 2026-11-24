package dev.rkdj.echo

import dev.rkdj.echo.analysis.OfflineAudioAnalyzer
import dev.rkdj.echo.engine.LocalMixStreamServer
import dev.rkdj.echo.engine.RKDJMixEngine
import dev.rkdj.echo.model.MixSettings
import dev.rkdj.echo.model.TrackAnalysis
import java.io.File

/**
 * RKDJ — Offline Auto-DJ Extension for Echo Music (brahmkshatriya/echo).
 *
 * Architecture:
 * 1. Scans offline/cached audio playlists & caches BPM/Key/Phrase metadata locally.
 * 2. Orders tracks harmonically via Camelot Wheel + BPM ratio matching.
 * 3. Selects context-aware DJ transitions (Bass Swap, Filter Sweep, Loop Roll Stutter,
 *    Vocal Echo-Out, Vinyl Brake, Backspin, Double Drop, Cut on the One).
 * 4. Serves the live-mixed continuous WAV/PCM stream over localhost (`127.0.0.1`)
 *    so Echo's native Media3 player plays it 100% offline.
 */
class RKDJExtension(
    private val settings: MixSettings = MixSettings()
) {
    val id: String = "rkdj"
    val name: String = "RKDJ — Offline Auto-DJ"
    val version: String = "1.0.0"

    private val mixEngine = RKDJMixEngine(settings)
    private val streamServer = LocalMixStreamServer(sampleRate = settings.targetSampleRate)
    private val analysisCache = mutableMapOf<String, TrackAnalysis>()

    /**
     * Initializes the offline localhost audio bridge.
     * Call during Echo extension `onExtensionSelected()` / startup lifecycle.
     */
    fun startOfflineBridge(pcmLoader: (TrackAnalysis) -> FloatArray): Int {
        return streamServer.start { playlistId, out ->
            val tracks = analysisCache.values.toList()
            if (tracks.isEmpty()) return@start
            val blueprint = mixEngine.buildPlaylistBlueprint(tracks)

            for (i in blueprint.orderedTracks.indices) {
                val curTrack = blueprint.orderedTracks[i]
                val curPcm = pcmLoader(curTrack)
                val sr = settings.targetSampleRate

                if (i < blueprint.transitions.size) {
                    val plan = blueprint.transitions[i]
                    val nextTrack = blueprint.orderedTracks[i + 1]
                    val nextPcm = pcmLoader(nextTrack)

                    // 1. Stream main body of current track up to mixOutSec
                    val mainEndIdx = ((plan.mixOutSec * sr).toInt() * 2).coerceIn(0, curPcm.size)
                    out.write(mixEngine.floatToPcm16Bytes(curPcm.copyOfRange(0, mainEndIdx)))

                    // 2. Render & stream the Auto-DJ transition bridge
                    val bridge = mixEngine.renderPairTransition(curTrack, curPcm, nextTrack, nextPcm, plan)
                    out.write(mixEngine.floatToPcm16Bytes(bridge))
                } else {
                    // Final track plays to completion
                    out.write(mixEngine.floatToPcm16Bytes(curPcm))
                }
            }
        }
    }

    /** Registers & analyzes an offline audio buffer into RKDJ's local cache */
    fun registerOfflineTrack(
        trackId: String,
        title: String,
        file: File,
        stereoPcm: FloatArray
    ): TrackAnalysis {
        val analysis = OfflineAudioAnalyzer.analyzePcm(
            trackId = trackId,
            title = title,
            filePath = file.absolutePath,
            stereoPcm = stereoPcm,
            sampleRate = settings.targetSampleRate
        )
        analysisCache[trackId] = analysis
        return analysis
    }

    /** Returns the Echo-playable Streamable URL for the continuous Auto-DJ mix */
    fun getAutoDjMixUrl(playlistId: String): String = streamServer.getMixStreamUrl(playlistId)

    /** Returns the full ordered tracklist + planned DJ transition types for Echo UI display */
    fun previewMixBlueprint(): RKDJMixEngine.MixBlueprint =
        mixEngine.buildPlaylistBlueprint(analysisCache.values.toList())

    fun shutdown() {
        streamServer.stop()
    }
}
