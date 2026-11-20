package com.imou.youtubestreamer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.data.AppSettings
import com.imou.youtubestreamer.data.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class LinkState(
    val lanUp: Boolean = false,
    val wanUp: Boolean = false,
    val networkHandle: Long = 0L,
    val isWifi: Boolean = false,
    val isCellularOnly: Boolean = false,
    val changedAtElapsedMs: Long = 0L
)

/**
 * Pure hysteresis state machine preventing flap storms while detecting silent WAN drops.
 *
 * - Declares UP only after [upThreshold] (2) consecutive successful probes AND OS validation/policy allow.
 * - Declares DOWN after [downThreshold] (2) consecutive failed probes, OR immediately on OS network loss
 *   (or policy block / unvalidated OS state once [downThreshold] is reached or OS loses default network).
 */
class LinkDebouncer(
    private val downThreshold: Int = 2,
    private val upThreshold: Int = 2
) {
    private var state = LinkState()
    private var lanOkStreak = 0
    private var lanFailStreak = 0
    private var wanOkStreak = 0
    private var wanFailStreak = 0

    @Synchronized
    fun currentState(): LinkState = state

    @Synchronized
    fun onOsLost(elapsedMs: Long): LinkState {
        lanOkStreak = 0
        lanFailStreak = downThreshold
        wanOkStreak = 0
        wanFailStreak = downThreshold
        return updateState(
            lanUp = false,
            wanUp = false,
            networkHandle = 0L,
            isWifi = false,
            isCellularOnly = false,
            elapsedMs = elapsedMs
        )
    }

    @Synchronized
    fun onOsCapabilities(
        networkHandle: Long,
        hasWifiOrEthernet: Boolean,
        isCellularOnly: Boolean,
        osValidated: Boolean,
        osWanAllowed: Boolean,
        elapsedMs: Long
    ): LinkState {
        var newLanUp = state.lanUp
        var newWanUp = state.wanUp

        if (isCellularOnly && !osWanAllowed) {
            lanOkStreak = 0
            wanOkStreak = 0
            newLanUp = false
            newWanUp = false
        } else {
            if (!osWanAllowed || !osValidated) {
                wanOkStreak = 0
                if (!osWanAllowed) {
                    newWanUp = false
                }
            }
            if (!hasWifiOrEthernet && isCellularOnly) {
                lanOkStreak = 0
                newLanUp = false
            }
        }

        return updateState(
            lanUp = newLanUp,
            wanUp = newWanUp,
            networkHandle = networkHandle,
            isWifi = hasWifiOrEthernet,
            isCellularOnly = isCellularOnly,
            elapsedMs = elapsedMs
        )
    }

    @Synchronized
    fun onProbeSample(
        lanProbeOk: Boolean,
        wanProbeOk: Boolean,
        hasLocalNetwork: Boolean,
        hasWifiOrEthernet: Boolean,
        isCellularOnly: Boolean,
        osValidated: Boolean,
        osWanAllowed: Boolean,
        networkHandle: Long,
        elapsedMs: Long
    ): LinkState {
        val effectiveLanOk = lanProbeOk && hasLocalNetwork && (hasWifiOrEthernet || !isCellularOnly)
        val effectiveWanOk = wanProbeOk && osValidated && osWanAllowed

        if (effectiveLanOk) {
            lanFailStreak = 0
            lanOkStreak++
        } else {
            lanOkStreak = 0
            lanFailStreak++
        }

        if (effectiveWanOk) {
            wanFailStreak = 0
            wanOkStreak++
        } else {
            wanOkStreak = 0
            wanFailStreak++
        }

        val nextLanUp = when {
            state.lanUp && lanFailStreak >= downThreshold -> false
            !state.lanUp && lanOkStreak >= upThreshold -> true
            else -> state.lanUp
        }

        val nextWanUp = when {
            !osWanAllowed -> false // Policy must take effect immediately (e.g. cellular blocked)
            state.wanUp && wanFailStreak >= downThreshold -> false
            !state.wanUp && wanOkStreak >= upThreshold -> true
            else -> state.wanUp
        }

        return updateState(
            lanUp = nextLanUp,
            wanUp = nextWanUp,
            networkHandle = networkHandle,
            isWifi = hasWifiOrEthernet,
            isCellularOnly = isCellularOnly,
            elapsedMs = elapsedMs
        )
    }

    private fun updateState(
        lanUp: Boolean,
        wanUp: Boolean,
        networkHandle: Long,
        isWifi: Boolean,
        isCellularOnly: Boolean,
        elapsedMs: Long
    ): LinkState {
        val statusChanged = lanUp != state.lanUp || wanUp != state.wanUp
        val changedAt = if (statusChanged || state.changedAtElapsedMs == 0L) elapsedMs else state.changedAtElapsedMs
        state = LinkState(
            lanUp = lanUp,
            wanUp = wanUp,
            networkHandle = networkHandle,
            isWifi = isWifi,
            isCellularOnly = isCellularOnly,
            changedAtElapsedMs = changedAt
        )
        return state
    }
}

/**
 * Monitors both OS network state (default network callback) AND real active TCP probes
 * to the CCTV camera (LAN) and YouTube Live ingest (WAN) every 15 seconds.
 */
class ConnectivityMonitor(
    private val context: Context,
    private val settings: AppSettings,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val onLinkChanged: (newState: LinkState, networkHandleChanged: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "ConnectivityMon"
        private const val PROBE_INTERVAL_MS = 15_000L
        private const val LAN_PROBE_TIMEOUT_MS = 3_000
        private const val WAN_PROBE_TIMEOUT_MS = 4_000
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val debouncer = LinkDebouncer()
    private val _linkState = MutableStateFlow(LinkState())
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val started = AtomicBoolean(false)
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var probeJob: Job? = null

    private val activeWifiNetworks = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var currentDefaultHandle: Long = 0L

    @Volatile
    private var lastReportedOnlineHandle: Long = 0L

    @Volatile
    private var osValidated: Boolean = false

    @Volatile
    private var osHasInternet: Boolean = false

    @Volatile
    private var osIsWifiOrEth: Boolean = false

    @Volatile
    private var osIsCellular: Boolean = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        AppLogger.enter(TAG, "start")

        registerCallbacks()
        snapshotInitialNetworkState()

        probeJob = scope.launch(Dispatchers.IO) {
            // Two fast initial probes 1.2 s apart so cold startup establishes link quickly while still honoring 2-sample confirmation
            runSingleProbeAndPublish(allowEmitWithoutChange = false)
            delay(1_200L)
            if (isActive) {
                runSingleProbeAndPublish(allowEmitWithoutChange = true)
            }

            while (isActive) {
                delay(PROBE_INTERVAL_MS)
                runSingleProbeAndPublish(allowEmitWithoutChange = false)
            }
        }
        AppLogger.exit(TAG, "start", "Callbacks & 15s probe loop active")
    }

    fun triggerImmediateProbe() {
        if (!started.get()) return
        scope.launch(Dispatchers.IO) {
            runSingleProbeAndPublish(allowEmitWithoutChange = false)
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        AppLogger.enter(TAG, "stop")
        probeJob?.cancel()
        probeJob = null
        try {
            defaultCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "stop:defaultCallback", t)
        }
        try {
            wifiCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (t: Throwable) {
            AppLogger.exception(TAG, "stop:wifiCallback", t)
        }
        defaultCallback = null
        wifiCallback = null
        activeWifiNetworks.clear()
        AppLogger.exit(TAG, "stop", "Stopped")
    }

    private fun registerCallbacks() {
        val cm = connectivityManager ?: run {
            AppLogger.unexpected(TAG, "registerCallbacks", "ConnectivityManager is null!")
            return
        }

        // Passive tracker for Wi-Fi association (useful when router WAN is down and Android fails default route to Cellular)
        val wifiReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val wCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeWifiNetworks.add(network.networkHandle)
            }

            override fun onLost(network: Network) {
                activeWifiNetworks.remove(network.networkHandle)
            }
        }
        wifiCallback = wCb
        cm.registerNetworkCallback(wifiReq, wCb)

        // Primary OS signal: default network callback
        val dCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                val prevHandle = currentDefaultHandle
                currentDefaultHandle = handle
                AppLogger.step(TAG, "defaultCallback.onAvailable", "handle=$handle, prev=$prevHandle")
                cm.getNetworkCapabilities(network)?.let { updateFromCapabilities(network, it) }
                triggerImmediateProbe()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                currentDefaultHandle = network.networkHandle
                updateFromCapabilities(network, caps)
            }

            override fun onLost(network: Network) {
                val handle = network.networkHandle
                AppLogger.step(TAG, "defaultCallback.onLost", "lostHandle=$handle, currentDefault=$currentDefaultHandle")
                if (handle == currentDefaultHandle) {
                    currentDefaultHandle = 0L
                    osValidated = false
                    osHasInternet = false
                    osIsWifiOrEth = false
                    osIsCellular = false
                    val newState = debouncer.onOsLost(clock.elapsedMs())
                    publishIfChanged(newState, networkHandleChanged = false, forceEmit = true)
                }
            }
        }
        defaultCallback = dCb
        cm.registerDefaultNetworkCallback(dCb)
    }

    private fun snapshotInitialNetworkState() {
        val cm = connectivityManager ?: return
        val net = cm.activeNetwork ?: return
        currentDefaultHandle = net.networkHandle
        cm.getNetworkCapabilities(net)?.let { caps ->
            updateFromCapabilities(net, caps)
        }
    }

    private fun updateFromCapabilities(network: Network, caps: NetworkCapabilities) {
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isWifiOrEth = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            activeWifiNetworks.isNotEmpty()
        val isCellularOnly = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !isWifiOrEth

        osHasInternet = internet
        osValidated = validated
        osIsWifiOrEth = isWifiOrEth
        osIsCellular = isCellularOnly

        val wanAllowed = internet && (isWifiOrEth || settings.allowCellular)
        val newState = debouncer.onOsCapabilities(
            networkHandle = network.networkHandle,
            hasWifiOrEthernet = isWifiOrEth,
            isCellularOnly = isCellularOnly,
            osValidated = validated,
            osWanAllowed = wanAllowed,
            elapsedMs = clock.elapsedMs()
        )
        publishIfChanged(newState, networkHandleChanged = false)
    }

    private suspend fun runSingleProbeAndPublish(allowEmitWithoutChange: Boolean) {
        val hasLocalNet = osIsWifiOrEth || activeWifiNetworks.isNotEmpty() ||
            (settings.allowCellular && osHasInternet)
        val wanAllowedByPolicy = osHasInternet && (osIsWifiOrEth || settings.allowCellular)

        val lanOk = if (hasLocalNet) {
            probeTcp(settings.cameraIp, settings.rtspPort, LAN_PROBE_TIMEOUT_MS, forceFreshDns = false)
        } else {
            false
        }

        val wanOk = if (wanAllowedByPolicy && osValidated) {
            probeTcp("a.rtmps.youtube.com", 443, WAN_PROBE_TIMEOUT_MS, forceFreshDns = true) ||
                probeTcp("a.rtmp.youtube.com", 1935, WAN_PROBE_TIMEOUT_MS, forceFreshDns = true)
        } else {
            false
        }

        val handle = currentDefaultHandle
        val newState = debouncer.onProbeSample(
            lanProbeOk = lanOk,
            wanProbeOk = wanOk,
            hasLocalNetwork = hasLocalNet,
            hasWifiOrEthernet = osIsWifiOrEth || activeWifiNetworks.isNotEmpty(),
            isCellularOnly = osIsCellular && activeWifiNetworks.isEmpty(),
            osValidated = osValidated,
            osWanAllowed = wanAllowedByPolicy,
            networkHandle = handle,
            elapsedMs = clock.elapsedMs()
        )

        val onlineHandleChanged = newState.lanUp && newState.wanUp &&
            handle != 0L && lastReportedOnlineHandle != 0L && handle != lastReportedOnlineHandle

        if (newState.lanUp && newState.wanUp && handle != 0L) {
            lastReportedOnlineHandle = handle
        }

        publishIfChanged(
            newState,
            networkHandleChanged = onlineHandleChanged,
            forceEmit = allowEmitWithoutChange || onlineHandleChanged
        )
    }

    private suspend fun probeTcp(
        host: String,
        port: Int,
        timeoutMs: Int,
        forceFreshDns: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            val targetAddr = if (forceFreshDns) {
                val addrs = InetAddress.getAllByName(host)
                addrs.firstOrNull() ?: return@withContext false
            } else {
                InetAddress.getByName(host)
            }
            socket = Socket()
            socket.connect(InetSocketAddress(targetAddr, port), timeoutMs)
            true
        } catch (_: Throwable) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun publishIfChanged(
        newState: LinkState,
        networkHandleChanged: Boolean,
        forceEmit: Boolean = false
    ) {
        val prev = _linkState.value
        val changed = prev.lanUp != newState.lanUp ||
            prev.wanUp != newState.wanUp ||
            prev.isWifi != newState.isWifi ||
            prev.isCellularOnly != newState.isCellularOnly ||
            networkHandleChanged

        _linkState.value = newState
        if (changed || forceEmit) {
            AppLogger.step(
                TAG,
                "publishLinkState",
                "lanUp=${newState.lanUp}, wanUp=${newState.wanUp}, wifi=${newState.isWifi}, cellularOnly=${newState.isCellularOnly}, netChanged=$networkHandleChanged"
            )
            onLinkChanged(newState, networkHandleChanged)
        }
    }
}
