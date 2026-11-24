package dev.rkdj.echo.engine

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-dependency localhost HTTP WAV/PCM streaming bridge (127.0.0.1).
 * Lets Echo Music's built-in ExoPlayer play RKDJ's real-time offline Auto-DJ mix
 * as a standard audio stream without modifying Echo's core APK.
 */
class LocalMixStreamServer(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var streamProvider: ((playlistId: String, out: OutputStream) -> Unit)? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start(provider: (playlistId: String, out: OutputStream) -> Unit): Int {
        streamProvider = provider
        if (running.compareAndSet(false, true)) {
            serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            executor.submit { acceptLoop() }
        }
        return port
    }

    fun getMixStreamUrl(playlistId: String): String =
        "http://127.0.0.1:$port/rkdj/mix/$playlistId.wav"

    fun stop() {
        running.set(false)
        serverSocket?.close()
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                executor.submit {
                    client.use { sock ->
                        val requestLine = sock.getInputStream().bufferedReader().readLine() ?: return@use
                        val playlistId = requestLine.substringAfter("/rkdj/mix/", "default")
                            .substringBefore(".wav")
                            .substringBefore(" ")
                        val out = sock.getOutputStream()
                        val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: audio/wav\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(header.toByteArray())
                        out.write(buildStreamingWavHeader(sampleRate, channels))
                        out.flush()
                        streamProvider?.invoke(playlistId, out)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    /** Generates a standard 44-byte streaming WAV header (unknown/infinite length) */
    private fun buildStreamingWavHeader(sr: Int, ch: Int): ByteArray {
        val byteRate = sr * ch * 2
        val blockAlign = (ch * 2).toShort()
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(0x7FFFFFFF) // Streaming length
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(ch.toShort())
        buf.putInt(sr)
        buf.putInt(byteRate)
        buf.putShort(blockAlign)
        buf.putShort(16) // 16-bit
        buf.put("data".toByteArray())
        buf.putInt(0x7FFFFFFF)
        return buf.array()
    }
}
