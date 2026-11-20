package com.imou.youtubestreamer.engine

import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.Clock
import com.imou.youtubestreamer.data.SystemClockImpl
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class BridgeErrorType {
    NONE,
    DNS_UNREACHABLE,
    CONNECT_UNREACHABLE,
    TLS_HANDSHAKE,
    STALLED,
    IO_ERROR
}

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
 * This bridge exposes an IPv4 loopback endpoint (`rtmp://127.0.0.1:<port>/...`) for FFmpeg
 * and forwards every byte over Android's verified BoringSSL/Conscrypt TLS socket (`a.rtmps.youtube.com:443`).
 *
 * Hardened in v1.0.15:
 * - Single active tunnel limit (closes previous local/remote sockets if a new connection arrives)
 * - DNS vs TCP connect vs TLS handshake error classification
 * - Local 30s read timeout, SSL keepAlive, and 20s upstream progress stall watchdog
 */
class RtmpsTlsBridge private constructor(
    private val tag: String,
    private val remoteHost: String,
    private val remotePort: Int,
    private val serverSocket: ServerSocket,
    private val clock: Clock
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val bytesUp = AtomicLong(0L)
    private val bytesDown = AtomicLong(0L)
    private val connectionCount = AtomicLong(0L)
    private val _lastUpstreamProgressElapsedMs = AtomicLong(clock.elapsedMs())

    val lastUpstreamProgressElapsedMs: Long
        get() = _lastUpstreamProgressElapsedMs.get()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastErrorType: BridgeErrorType = BridgeErrorType.NONE
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

                // Enforce at most one active tunnel per bridge instance (Phase 6.7)
                closeQuietly(activeLocal)
                closeQuietly(activeRemote)

                local.tcpNoDelay = true
                local.keepAlive = true
                local.soTimeout = LOCAL_READ_TIMEOUT_MS
                activeLocal = local

                AppLogger.step(
                    tag,
                    "RtmpsTlsBridge.conn#$connId",
                    "FFmpeg connected from ${local.remoteSocketAddress}; opening TLS to $remoteHost:$remotePort..."
                )
                Thread({
                    handleConnection(connId, local)
                }, "RtmpsConn-$tag-$connId").apply {
                    isDaemon = true
                    start()
                }
            } catch (t: Throwable) {
                if (!closed.get() && !serverSocket.isClosed) {
                    lastError = "${t::class.java.simpleName}: ${t.message}"
                    if (lastErrorType == BridgeErrorType.NONE) {
                        lastErrorType = BridgeErrorType.IO_ERROR
                    }
                    AppLogger.exception(tag, "RtmpsTlsBridge.accept", t)
                }
            }
        }
        AppLogger.step(
            tag,
            "RtmpsTlsBridge.accept",
            "Listener exited (up=${bytesUp.get()}B, down=${bytesDown.get()}B)"
        )
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
        val tunnelAlive = AtomicBoolean(true)
        var watchdogThread: Thread? = null

        try {
            val connStartElapsed = clock.elapsedMs()
            _lastUpstreamProgressElapsedMs.set(connStartElapsed)

            // Resolve fresh DNS per connection so cached DNS never hides WAN outages
            try {
                InetAddress.getAllByName(remoteHost)
            } catch (uhe: UnknownHostException) {
                lastErrorType = BridgeErrorType.DNS_UNREACHABLE
                throw uhe
            }

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            ssl = (factory.createSocket() as SSLSocket).apply {
                tcpNoDelay = true
                keepAlive = true
                val params = sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                params.serverNames = listOf(SNIHostName(remoteHost))
                sslParameters = params
                soTimeout = 10_000
                try {
                    connect(InetSocketAddress(remoteHost, remotePort), 8_000)
                } catch (ce: IOException) {
                    lastErrorType = BridgeErrorType.CONNECT_UNREACHABLE
                    throw ce
                }
                try {
                    startHandshake()
                } catch (se: IOException) {
                    lastErrorType = if (se is SSLException || se.cause is CertificateException) {
                        BridgeErrorType.TLS_HANDSHAKE
                    } else {
                        BridgeErrorType.IO_ERROR
                    }
                    throw se
                }
                // After handshake, allow idle server read while local soTimeout & watchdog catch stalls
                soTimeout = 0
            }
            activeRemote = ssl

            val session = ssl.session
            AppLogger.i(
                tag,
                "RTMPS TLS established (#$connId -> $remoteHost:$remotePort): ${session.protocol}, ${session.cipherSuite}"
            )

            // Watchdog: close sockets if bytesUp stalls for 20s after 25s startup grace
            val activeSsl = ssl
            watchdogThread = Thread({
                try {
                    while (tunnelAlive.get() && !closed.get()) {
                        Thread.sleep(WATCHDOG_INTERVAL_MS)
                        if (!tunnelAlive.get() || closed.get()) break
                        val now = clock.elapsedMs()
                        if (now - connStartElapsed < STALL_GRACE_MS) continue
                        val idleMs = now - _lastUpstreamProgressElapsedMs.get()
                        if (idleMs >= UPSTREAM_STALL_TIMEOUT_MS) {
                            lastErrorType = BridgeErrorType.STALLED
                            lastError = "Upstream write stall (${idleMs / 1000}s with no data pushed to YouTube)"
                            AppLogger.unexpected(tag, "RtmpsTlsBridge.watchdog#$connId", lastError!!)
                            tunnelAlive.set(false)
                            closeQuietly(local)
                            closeQuietly(activeSsl)
                            break
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }, "RtmpsWatch-$tag-$connId").apply {
                isDaemon = true
                start()
            }

            val doneSignal = AtomicBoolean(false)
            val upThread = Thread({
                pumpStream("up#$connId", local.getInputStream(), ssl.getOutputStream(), bytesUp, isUpstream = true) {
                    if (doneSignal.compareAndSet(false, true)) {
                        tunnelAlive.set(false)
                        closeQuietly(local)
                        closeQuietly(ssl)
                    }
                }
            }, "RtmpsUp-$tag-$connId").apply { isDaemon = true }

            val downThread = Thread({
                pumpStream("down#$connId", ssl.getInputStream(), local.getOutputStream(), bytesDown, isUpstream = false) {
                    if (doneSignal.compareAndSet(false, true)) {
                        tunnelAlive.set(false)
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
                if (lastErrorType == BridgeErrorType.NONE) {
                    lastErrorType = when (t) {
                        is UnknownHostException -> BridgeErrorType.DNS_UNREACHABLE
                        is SSLException -> BridgeErrorType.TLS_HANDSHAKE
                        else -> BridgeErrorType.IO_ERROR
                    }
                }
                AppLogger.exception(tag, "RtmpsTlsBridge.conn#$connId", t, "upstream=$remoteHost:$remotePort, errType=$lastErrorType")
            }
        } finally {
            tunnelAlive.set(false)
            watchdogThread?.interrupt()
            closeQuietly(local)
            closeQuietly(ssl)
        }
    }

    private fun pumpStream(
        dir: String,
        input: InputStream,
        output: OutputStream,
        counter: AtomicLong,
        isUpstream: Boolean,
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
                if (isUpstream) {
                    _lastUpstreamProgressElapsedMs.set(clock.elapsedMs())
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) {
                if (lastErrorType == BridgeErrorType.NONE && isUpstream) {
                    lastErrorType = BridgeErrorType.IO_ERROR
                    lastError = lastError ?: "${t::class.java.simpleName}: ${t.message}"
                }
                AppLogger.step(tag, "RtmpsTlsBridge.$dir", "Stream ended: ${t::class.java.simpleName}: ${t.message}")
            }
        } finally {
            onFinished()
        }
    }

    fun getStatsSummary(): String =
        "conns=${connectionCount.get()}, up=${bytesUp.get()}B, down=${bytesDown.get()}B, errType=$lastErrorType, err=${lastError ?: "none"}"

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
        private const val LOCAL_READ_TIMEOUT_MS = 30_000
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val STALL_GRACE_MS = 25_000L
        private const val UPSTREAM_STALL_TIMEOUT_MS = 20_000L

        fun prepare(targetUrl: String, tag: String, clock: Clock = SystemClockImpl): PreparedRtmpTarget {
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
            val bridge = RtmpsTlsBridge(
                tag = tag,
                remoteHost = host,
                remotePort = port,
                serverSocket = serverSocket,
                clock = clock
            )
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
