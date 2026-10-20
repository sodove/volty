package ru.sodovaya.volty.data.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.sodovaya.volty.domain.radio.RadioAddress
import ru.sodovaya.volty.domain.radio.RadioDuplicateRule
import ru.sodovaya.volty.domain.radio.RadioKeyStore
import ru.sodovaya.volty.domain.radio.RadioLink
import ru.sodovaya.volty.domain.radio.RadioLinkResult

/**
 * Wi-Fi Direct implementation of [RadioLink]. The group's network name and passphrase come from
 * [RadioCredentialDerivation], so only members of the same Volty server can find or join it.
 *
 * Rules this class keeps:
 * - Below API 29 every method returns [RadioLinkResult.Unsupported] ([radioNearby] false).
 * - It never calls `bindProcessToNetwork` and never changes routes: mobile data keeps working. The
 *   UDP socket is bound to the P2P interface address only (see [awaitLocalAddress]).
 * - Every suspending call is cancellable: receivers are unregistered when the coroutine is
 *   cancelled or times out, so the controller can cancel an in-flight join on `leave()`.
 * - It does not request runtime permissions (the UI layer does); without them it returns
 *   [RadioLinkResult.Failed].
 *
 * Duplicate hubs: while hosting, a scan runs every [DUPLICATE_SCAN_MILLIS]. When another access
 * point with this group's network name has a smaller BSSID ([RadioDuplicateRule]), [onDuplicateHub]
 * is invoked with its BSSID. The link does not dissolve its own group: the controller owns the
 * state and must call leave() then join(), otherwise its state would disagree with the radio.
 *
 * Framework code: not unit-testable in this repo, verified only on a device. In particular
 * unverified until then: that `connect()` with only a network name and passphrase finds a group
 * on a given vendor's Wi-Fi stack, how a join to an absent group fails (callback error versus
 * silence), and that a P2P group's scan BSSID matches the owner's device address (the locally
 * administered bit is normalised away, see [normalizeMac]).
 */
class AndroidRadioLink(
    context: Context,
    private val keys: RadioKeyStore,
    private val actor: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val port: Int = RadioUdpTransport.DEFAULT_PORT,
) : RadioLink {
    private val app = context.applicationContext
    private val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val manager: WifiP2pManager? by lazy { app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }
    private val wifi: WifiManager? by lazy { app.getSystemService(Context.WIFI_SERVICE) as? WifiManager }
    private val channel: WifiP2pManager.Channel? by lazy { manager?.initialize(app, app.mainLooper, null) }

    /** Network names seen per community by the last [host] or [join]; [radioNearby] cannot suspend to derive them. */
    @Volatile
    private var knownNames: Map<String, Set<String>> = emptyMap()

    @Volatile
    private var ownInterface: String? = null

    private var duplicateJob: Job? = null

    /** Invoked (on a background thread) with the other hub's BSSID when this hub should yield. */
    @Volatile
    var onDuplicateHub: ((otherBssid: String) -> Unit)? = null

    override suspend fun host(community: String): RadioLinkResult {
        if (!supported) return RadioLinkResult.Unsupported
        val (m, ch) = ready() ?: return RadioLinkResult.Failed("Wi-Fi Direct unavailable")
        val creds = credentials(community) ?: return RadioLinkResult.Failed("no radio key")
        val today = creds.first()
        // Clear a stale group of ours first; "no group" is a failure we ignore.
        p2p { m.removeGroup(ch, it) }
        var failure = createGroup(m, ch, today, WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
        if (failure != null) failure = createGroup(m, ch, today, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
        if (failure != null) return failureResult(failure)
        val group = awaitGroup(m, ch, GROUP_FORMATION_MILLIS) ?: return RadioLinkResult.Failed("group not formed")
        ownInterface = group.`interface`
        startDuplicateWatch(today.networkName, group)
        return RadioLinkResult.Hosting(port)
    }

    override suspend fun join(community: String, timeoutMillis: Long): RadioLinkResult {
        if (!supported) return RadioLinkResult.Unsupported
        val (m, ch) = ready() ?: return RadioLinkResult.Failed("Wi-Fi Direct unavailable")
        val candidates = credentials(community) ?: return RadioLinkResult.Failed("no radio key")
        val deadline = clock() + timeoutMillis
        for ((index, c) in candidates.withIndex()) {
            val remaining = deadline - clock()
            if (remaining <= 0) break
            // Earlier candidates get an even share so yesterday's name is still tried.
            val budget = remaining / (candidates.size - index)
            val config = WifiP2pConfig.Builder().setNetworkName(c.networkName).setPassphrase(c.passphrase).build()
            val failure = p2p { m.connect(ch, config, it) }
            if (failure == WifiP2pManager.P2P_UNSUPPORTED) return RadioLinkResult.Unsupported
            if (failure != null) continue // the request itself was refused: nothing to wait for
            val info = try {
                awaitJoined(m, ch, budget)
            } catch (e: kotlinx.coroutines.CancellationException) {
                m.cancelConnect(ch, null) // do not leave a half-open attempt behind a cancelled join
                throw e
            }
            if (info != null) {
                ownInterface = awaitGroup(m, ch, GROUP_FORMATION_MILLIS)?.`interface`
                return RadioLinkResult.Joined(RadioAddress(info.groupOwnerAddress.hostAddress ?: continue, port))
            }
            m.cancelConnect(ch, null)
        }
        return RadioLinkResult.NotFound
    }

    override suspend fun leave() {
        duplicateJob?.cancel()
        duplicateJob = null
        ownInterface = null
        if (!supported) return
        val (m, ch) = ready() ?: return
        p2p { m.cancelConnect(ch, it) }
        p2p { m.removeGroup(ch, it) }
    }

    /**
     * True when the last scan saw an access point named like this community's radio. Needs a prior
     * [host] or [join] to know the names, and triggers a (system-throttled) scan for next time.
     */
    @SuppressLint("MissingPermission")
    override fun radioNearby(community: String): Boolean {
        if (!supported) return false
        val names = knownNames[community] ?: return false
        val w = wifi ?: return false
        if (!hasWifiPermission()) return false
        return try {
            @Suppress("DEPRECATION")
            w.startScan()
            w.scanResults.any { it.SSID in names }
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * The IPv4 address of this phone's P2P interface, for binding the UDP socket; null if it does
     * not appear within [timeoutMillis] (a joining client's DHCP lease can lag the connection).
     */
    suspend fun awaitLocalAddress(timeoutMillis: Long = LOCAL_ADDRESS_MILLIS): InetAddress? =
        withTimeoutOrNull(timeoutMillis) {
            var found: InetAddress? = null
            while (found == null) {
                found = localAddressNow()
                if (found == null) delay(POLL_MILLIS)
            }
            found
        }

    private fun localAddressNow(): InetAddress? {
        val name = ownInterface ?: return null
        return try {
            NetworkInterface.getByName(name)?.inetAddresses?.toList()?.firstOrNull { it is Inet4Address }
        } catch (_: Exception) {
            null
        }
    }

    private fun ready(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        if (!hasWifiPermission()) return null
        val m = manager ?: return null
        val ch = channel ?: return null
        return m to ch
    }

    private fun hasWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Today's credentials first, then yesterday's; null when no key is cached. */
    private suspend fun credentials(community: String): List<RadioCredentials>? {
        val owner = actor() ?: return null
        val stored = keys.get(owner, community) ?: return null
        val list = RadioCredentialDerivation.joinCandidates(stored.key, clock())
        knownNames = knownNames + (community to list.map { it.networkName }.toSet())
        return list
    }

    private fun failureResult(reason: Int): RadioLinkResult =
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) RadioLinkResult.Unsupported
        else RadioLinkResult.Failed("Wi-Fi Direct error $reason")

    @SuppressLint("MissingPermission")
    private suspend fun createGroup(
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        creds: RadioCredentials,
        band: Int,
    ): Int? {
        val config = WifiP2pConfig.Builder()
            .setNetworkName(creds.networkName)
            .setPassphrase(creds.passphrase)
            .setGroupOperatingBand(band)
            .build()
        return p2p { m.createGroup(ch, config, it) }
    }

    /** Runs a listener-style P2P call; null = success, otherwise the framework's failure code. */
    @SuppressLint("MissingPermission")
    private suspend fun p2p(call: (WifiP2pManager.ActionListener) -> Unit): Int? =
        suspendCancellableCoroutine { c ->
            try {
                call(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (c.isActive) c.resumeWith(Result.success(null))
                    }

                    override fun onFailure(reason: Int) {
                        if (c.isActive) c.resumeWith(Result.success(reason))
                    }
                })
            } catch (_: SecurityException) {
                if (c.isActive) c.resumeWith(Result.success(WifiP2pManager.ERROR))
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun requestGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { c ->
            try {
                m.requestGroupInfo(ch) { if (c.isActive) c.resumeWith(Result.success(it)) }
            } catch (_: SecurityException) {
                if (c.isActive) c.resumeWith(Result.success(null))
            }
        }

    private suspend fun awaitGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel, timeoutMillis: Long): WifiP2pGroup? =
        withTimeoutOrNull(timeoutMillis) {
            var group = requestGroup(m, ch)
            while (group == null) {
                delay(POLL_MILLIS)
                group = requestGroup(m, ch)
            }
            group
        }

    /**
     * Waits for the P2P connection to form with another phone as group owner. Cancellation and
     * timeout both unregister the receiver (the `finally`), which is what keeps join cancellable.
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitJoined(m: WifiP2pManager, ch: WifiP2pManager.Channel, timeoutMillis: Long): WifiP2pInfo? {
        var receiver: BroadcastReceiver? = null
        try {
            return withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { c ->
                    val check = {
                        try {
                            m.requestConnectionInfo(ch) { info ->
                                if (info != null && info.groupFormed && !info.isGroupOwner &&
                                    info.groupOwnerAddress != null && c.isActive
                                ) {
                                    c.resumeWith(Result.success(info))
                                }
                            }
                        } catch (_: SecurityException) {
                        }
                    }
                    val r = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) = check()
                    }
                    receiver = r
                    val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        app.registerReceiver(r, filter)
                    }
                    check() // already connected before the receiver existed
                }
            }
        } finally {
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
        }
    }

    private fun startDuplicateWatch(hostedName: String, group: WifiP2pGroup) {
        duplicateJob?.cancel()
        val own = group.owner?.deviceAddress?.let(::normalizeMac) ?: return
        duplicateJob = scope.launch {
            while (isActive) {
                delay(DUPLICATE_SCAN_MILLIS)
                val w = wifi ?: continue
                if (!hasWifiPermission()) continue
                val other = try {
                    @Suppress("DEPRECATION")
                    w.startScan()
                    @SuppressLint("MissingPermission")
                    val results = w.scanResults
                    results.filter { it.SSID == hostedName }
                        .mapNotNull { it.BSSID?.let(::normalizeMac) }
                        .filter { it != own }
                        .firstOrNull { RadioDuplicateRule.shouldYield(group.clientList?.size ?: 0, own, it) }
                } catch (_: SecurityException) {
                    null
                }
                if (other != null) {
                    onDuplicateHub?.invoke(other)
                    return@launch
                }
            }
        }
    }

    private companion object {
        const val GROUP_FORMATION_MILLIS = 5_000L
        const val LOCAL_ADDRESS_MILLIS = 5_000L
        const val POLL_MILLIS = 250L
        const val DUPLICATE_SCAN_MILLIS = 30_000L

        /**
         * A P2P group's interface address is the owner's device address with the locally
         * administered bit (0x02 of the first octet) set; clearing it on both sides makes the two
         * comparable and keeps [RadioDuplicateRule] deterministic.
         */
        fun normalizeMac(mac: String): String {
            val lower = mac.lowercase()
            val first = lower.substringBefore(':').toIntOrNull(16) ?: return lower
            return "%02x".format(first and 0x02.inv()) + lower.substring(lower.indexOf(':'))
        }
    }
}
