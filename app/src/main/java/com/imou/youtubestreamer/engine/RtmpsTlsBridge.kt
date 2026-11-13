package com.imou.youtubestreamer.engine

import com.imou.youtubestreamer.data.AppLogger
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class PreparedRtmpTarget(
    val ffmpegUrl: String,
    val tcUrl: String?,
    val bridge: RtmpsTlsBridge?
)

/**
 * Localhost TCP -> TLSv1.3 SSLSocket bridge for RTMPS ingestion.
 *
 * `dev.ffmpegkit-maintained:ffmpeg-kit-full` builds FFmpeg's libavformat without OpenSSL/GnuTLS,
 * so passing `rtmps://` directly fails with "Protocol not found".
 * This bridge exposes an ephemeral loopback endpoint (`rtmp://127.0.0.1:<port>/...`) for FFmpeg
 * and forwards every byte over Android's verified BoringSSL/Conscrypt TLS socket (`a.rtmps.youtube.com:443`).
 */
class RtmpsTlsBridge private constructor(
    private val tag: String,
    private val remoteHost: String,
    private val remotePort: Int,
    private val serverSocket: ServerSocket
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val bytesUp = AtomicLong(0L)
    private val bytesDown = AtomicLong(0L)
    private val connectionCount = AtomicLong(0L)

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var activeLocal: Socket? = null

    @Volatile
    private var activeRemote: SSLSocket? = null

    private val readyLatch = CountDownLatch(1)

    private val acceptThread: Thread = Thread({
        AppLogger.step(
            tag,
            "RtmpsTlsBridge.accept",
            "Loopback listener bound to ${serverSocket.inetAddress?.hostAddress}:${serverSocket.localPort} -> $remoteHost:$remotePort"
        )
        readyLatch.countDown()
        while (!closed.get() && !serverSocket.isClosed) {
            try {
                val local = serverSocket.accept()
                val connId = connectionCount.incrementAndGet()
                local.tcpNoDelay = true
                local.keepAlive = true
                activeLocal = local
                AppLogger.step(tag, "RtmpsTlsBridge.conn#$connId", "FFmpeg connected from ${local.remoteSocketAddress}; opening TLS to $remoteHost:$remotePort...")
                Thread({
                    handleConnection(connId, local)
                }, "RtmpsConn-$tag-$connId").apply {
                    isDaemon = true
                    start()
                }
            } catch (t: Throwable) {
                if (!closed.get() && !serverSocket.isClosed) {
                    lastError = "${t::class.java.simpleName}: ${t.message}"
                    AppLogger.exception(tag, "RtmpsTlsBridge.accept", t)
                }
            }
        }
        AppLogger.step(tag, "RtmpsTlsBridge.accept", "Listener exited (up=${bytesUp.get()}B, down=${bytesDown.get()}B)")
    }, "RtmpsBridge-$tag").apply {
        isDaemon = true
        start()
    }

    fun awaitReady() {
        try {
            readyLatch.await(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun handleConnection(connId: Long, local: Socket) {
        var ssl: SSLSocket? = null
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            ssl = (factory.createSocket() as SSLSocket).apply {
                tcpNoDelay = true
                val params = sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                params.serverNames = listOf(SNIHostName(remoteHost))
                sslParameters = params
                soTimeout = 10_000
                connect(InetSocketAddress(remoteHost, remotePort), 8_000)
                startHandshake()
                // After handshake, disable read timeout so idle upstream RTMP periods are never killed
                soTimeout = 0
            }
            activeRemote = ssl

            val session = ssl.session
            AppLogger.i(
                tag,
                "RTMPS TLS established (#$connId -> $remoteHost:$remotePort): ${session.protocol}, ${session.cipherSuite}"
            )

            val doneSignal = AtomicBoolean(false)
            val upThread = Thread({
                pumpStream("up#$connId", local.getInputStream(), ssl.getOutputStream(), bytesUp) {
                    if (doneSignal.compareAndSet(false, true)) {
                        closeQuietly(local)
                        closeQuietly(ssl)
                    }
                }
            }, "RtmpsUp-$tag-$connId").apply { isDaemon = true }

            val downThread = Thread({
                pumpStream("down#$connId", ssl.getInputStream(), local.getOutputStream(), bytesDown) {
                    if (doneSignal.compareAndSet(false, true)) {
                        closeQuietly(local)
                        closeQuietly(ssl)
                    }
                }
            }, "RtmpsDown-$tag-$connId").apply { isDaemon = true }

            upThread.start()
            downThread.start()
            upThread.join()
            downThread.join()

            AppLogger.step(
                tag,
                "RtmpsTlsBridge.conn#$connId",
                "Tunnel closed cleanly (bytesUp=${bytesUp.get()}, bytesDown=${bytesDown.get()})"
            )
        } catch (t: Throwable) {
            if (!closed.get()) {
                lastError = "${t::class.java.simpleName}: ${t.localizedMessage ?: t.message}"
                AppLogger.exception(tag, "RtmpsTlsBridge.conn#$connId", t, "upstream=$remoteHost:$remotePort")
            }
        } finally {
            closeQuietly(local)
            closeQuietly(ssl)
        }
    }

    private fun pumpStream(
        dir: String,
        input: InputStream,
        output: OutputStream,
        counter: AtomicLong,
        onFinished: () -> Unit
    ) {
        val buf = ByteArray(32 * 1024)
        try {
            while (!closed.get()) {
                val read = input.read(buf)
                if (read <= 0) break
                output.write(buf, 0, read)
                output.flush()
                counter.addAndGet(read.toLong())
            }
        } catch (t: Throwable) {
            if (!closed.get()) {
                AppLogger.step(tag, "RtmpsTlsBridge.$dir", "Stream ended: ${t::class.java.simpleName}: ${t.message}")
            }
        } finally {
            onFinished()
        }
    }

    fun getStatsSummary(): String =
        "conns=${connectionCount.get()}, up=${bytesUp.get()}B, down=${bytesDown.get()}B, err=${lastError ?: "none"}"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        AppLogger.step(tag, "RtmpsTlsBridge.close", "Closing bridge (${getStatsSummary()})")
        closeQuietly(activeLocal)
        closeQuietly(activeRemote)
        closeQuietly(serverSocket)
        acceptThread.interrupt()
    }

    private fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (_: Throwable) {
        }
    }

    private fun closeQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (_: Throwable) {
        }
    }

    private fun closeQuietly(s: ServerSocket?) {
        try {
            s?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun prepare(targetUrl: String, tag: String): PreparedRtmpTarget {
            val trimmed = targetUrl.trim()
            AppLogger.enter(tag, "RtmpsTlsBridge.prepare", "scheme=${trimmed.substringBefore("://")}")

            if (!trimmed.startsWith("rtmps://", ignoreCase = true)) {
                AppLogger.exit(tag, "RtmpsTlsBridge.prepare", "Direct plain RTMP")
                return PreparedRtmpTarget(ffmpegUrl = trimmed, tcUrl = null, bridge = null)
            }

            val parsed = URI(trimmed)
            val rawHost = parsed.host ?: throw IllegalArgumentException("Invalid RTMPS URL: missing host")
            val host = if (rawHost.equals("a.rtmp.youtube.com", ignoreCase = true)) {
                "a.rtmps.youtube.com"
            } else {
                rawHost
            }
            val port = if (parsed.port > 0) parsed.port else 443
            val rawPath = parsed.rawPath.orEmpty().ifEmpty { "/live2" }
            val querySuffix = parsed.rawQuery?.let { "?$it" }.orEmpty()
            val fullPath = "$rawPath$querySuffix"

            val lastSlash = rawPath.lastIndexOf('/')
            val appPath = if (lastSlash > 0) rawPath.substring(0, lastSlash) else rawPath
            val portPart = if (port != 443) ":$port" else ""
            val tcUrl = "rtmps://$host$portPart$appPath"

            // CRITICAL: On Android (Inet6AddressImpl), InetAddress.getLoopbackAddress() returns IPv6 ::1!
            // Binding a ServerSocket to ::1 causes IPv4 connections to 127.0.0.1:<port> to fail with
            // ECONNREFUSED ("Connection refused"). Always bind explicitly to IPv4 127.0.0.1.
            val ipv4Loopback = InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1))
            val serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ipv4Loopback, 0), 32)
            }
            val bridge = RtmpsTlsBridge(tag = tag, remoteHost = host, remotePort = port, serverSocket = serverSocket)
            bridge.awaitReady()
            val localUrl = "rtmp://127.0.0.1:${serverSocket.localPort}$fullPath"

            AppLogger.step(
                tag,
                "RtmpsTlsBridge.prepare",
                "Bridge listening on ${serverSocket.inetAddress.hostAddress}:${serverSocket.localPort} -> $host:$port, tcUrl='$tcUrl'"
            )
            AppLogger.exit(tag, "RtmpsTlsBridge.prepare", "Bridged via IPv4 loopback port ${serverSocket.localPort}")
            return PreparedRtmpTarget(ffmpegUrl = localUrl, tcUrl = tcUrl, bridge = bridge)
        }
    }
}
