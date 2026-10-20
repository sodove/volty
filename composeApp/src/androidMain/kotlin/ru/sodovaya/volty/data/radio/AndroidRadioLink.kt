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
import android.os.SystemClock
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.sodovaya.volty.domain.radio.RadioAddress
import ru.sodovaya.volty.domain.radio.RadioDuplicateDecision
import ru.sodovaya.volty.domain.radio.RadioKeyStore
import ru.sodovaya.volty.domain.radio.RadioLink
import ru.sodovaya.volty.domain.radio.RadioLinkResult
import ru.sodovaya.volty.domain.radio.RadioScanFilter
import ru.sodovaya.volty.domain.radio.RadioScanResult
import ru.sodovaya.volty.domain.radio.radioJoinBudgets
import ru.sodovaya.volty.domain.radio.reusableRadioAttachment

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
 * Duplicate hubs (ruling R14): while hosting, a scan runs every [DUPLICATE_SCAN_MILLIS]. On seeing
 * another access point with this group's network name the link waits a random 0..30 s, scans
 * again, and only if it is still there and [RadioDuplicateDecision] says so (smaller BSSID, or
 * our own address is unknown/redacted) invokes [onDuplicateHub]. The link does not dissolve its
 * own group: the controller owns the state and re-runs raise semantics.
 *
 * Scanning needs ACCESS_FINE_LOCATION on every API level ([scanPermissionGranted]); without it
 * radioNearby* returns null/false and duplicate detection stays quiet.
 *
 * Framework code: not unit-testable in this repo, verified only on a device. In particular
 * unverified until then: that `connect()` with only a network name and passphrase finds a group
 * on a given vendor's Wi-Fi stack, how a join to an absent group fails (callback error versus
 * silence), and that a P2P group's scan BSSID matches the owner's device address (the locally
 * administered bit is normalised away, see [RadioScanFilter.normalizeMac]).
 *
 * Final-review fixes (device-only, compile-verified):
 * - I2: [join] first asks whether this phone is still a client of one of the community's groups and
 *   reuses that attachment; otherwise it clears any leftover attachment before connecting.
 * - I3: today's network name gets the whole join budget except in the first 6 h after UTC midnight
 *   ([radioJoinBudgets]).
 * - I4: every scan decision uses only results of the scan it started ([RadioScanFilter.fresh]),
 *   skips the round when the scan request is refused, and never counts this phone's own group.
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

    /** Day-keyed network names; filled by [credentials] and, on a miss, by [warmNames]. */
    private val names = RadioNameCache()
    private val warming = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var ownInterface: String? = null

    /** This phone's P2P device address while it hosts (a scan must never see it as another hub). */
    @Volatile
    private var ownHubAddress: String? = null

    @Volatile
    private var duplicateJob: Job? = null

    @Volatile
    override var onDuplicateHub: ((community: String) -> Unit)? = null

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
        val group = awaitGroup(m, ch, GROUP_FORMATION_MILLIS)
        if (group == null) {
            // createGroup was accepted but nothing formed: do not leave a half-made group behind.
            withContext(NonCancellable) { p2p { m.removeGroup(ch, it) } }
            return RadioLinkResult.Failed("group not formed")
        }
        ownInterface = group.`interface`
        ownHubAddress = group.owner?.deviceAddress
        startDuplicateWatch(community, today.networkName, group)
        return RadioLinkResult.Hosting(port)
    }

    override suspend fun join(community: String, timeoutMillis: Long): RadioLinkResult {
        if (!supported) return RadioLinkResult.Unsupported
        val (m, ch) = ready() ?: return RadioLinkResult.Failed("Wi-Fi Direct unavailable")
        val candidates = credentials(community) ?: return RadioLinkResult.Failed("no radio key")
        // I2: still a client of one of our groups (the hub came back by itself during Lost): keep it.
        reuseAttachment(m, ch, candidates.map { it.networkName })?.let { return it }
        // Otherwise a leftover attachment or half-open attempt would make connect() fail or join the
        // wrong group: clear both first ("nothing to clear" is a failure we ignore).
        p2p { m.cancelConnect(ch, it) }
        p2p { m.removeGroup(ch, it) }
        ownInterface = null
        val start = clock()
        val deadline = start + timeoutMillis
        val budgets = radioJoinBudgets(start, timeoutMillis)
        for ((index, c) in candidates.take(budgets.size).withIndex()) {
            val remaining = deadline - clock()
            if (remaining <= 0) break
            // The last name tried may use what an earlier one left (a refused connect returns at once).
            val budget = if (index == budgets.lastIndex) remaining else minOf(budgets[index], remaining)
            if (budget <= 0) continue
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
                val hubHost = info.groupOwnerAddress?.hostAddress
                ownInterface = awaitGroup(m, ch, GROUP_FORMATION_MILLIS)?.`interface`
                if (hubHost == null || awaitLocalAddress() == null) {
                    // Connected, but there is no interface to bind the socket to: not a usable link.
                    withContext(NonCancellable) {
                        p2p { m.cancelConnect(ch, it) }
                        p2p { m.removeGroup(ch, it) }
                    }
                    ownInterface = null
                    return RadioLinkResult.Failed("no local P2P address")
                }
                return RadioLinkResult.Joined(RadioAddress(hubHost, port))
            }
            m.cancelConnect(ch, null)
        }
        return RadioLinkResult.NotFound
    }

    override suspend fun leave() {
        duplicateJob?.cancel()
        duplicateJob = null
        ownInterface = null
        ownHubAddress = null
        if (!supported) return
        val (m, ch) = ready() ?: return
        p2p { m.cancelConnect(ch, it) }
        p2p { m.removeGroup(ch, it) }
    }

    /** True when a scan sees this community's radio; a thin wrapper over [radioNearbyAny]. */
    override suspend fun radioNearby(community: String): Boolean = radioNearbyAny(listOf(community)) != null

    /**
     * Does ONE scan, waits for its results, and returns the first of [communities] whose network
     * name the scan saw, or null (also when scanning is not permitted or was refused, or names are
     * not known yet). Only names [join] would actually try count: yesterday's only in the first
     * hours after UTC midnight, or auto-join would chase a hub it never tries to connect to.
     * Names are derived from the key store on a miss or a new UTC day: that is asynchronous, so the
     * first call after a cold start returns null and a later poll hits.
     */
    suspend fun radioNearbyAny(communities: List<String>): String? {
        if (!supported || communities.isEmpty() || !scanPermissionGranted()) return null
        val now = clock()
        val day = now / DAY_MILLIS
        // How many day-keyed names join tries right now (budgets of any total have that many parts).
        val tried = radioJoinBudgets(now, 1).size
        // The cached set keeps candidate order (today, yesterday): it is built with toSet().
        val known = communities.mapNotNull { c -> names.get(c, day)?.let { c to it.take(tried) } }
        communities.filter { c -> known.none { it.first == c } }.forEach(::warmNames)
        if (known.isEmpty()) return null
        val seen = freshScan(ownBssids())?.mapNotNullTo(HashSet()) { it.ssid } ?: return null
        return known.firstOrNull { (_, n) -> n.any { it in seen } }?.first
    }

    /**
     * Starts one scan and returns only what that scan saw (I4), minus this phone's own group
     * interface; null when the scan request is refused (throttled: Android allows a few scans per
     * two minutes) or scanning is not permitted, in which case the caller decides nothing this round.
     * Waits for the system's scan-results broadcast, up to [SCAN_WAIT_MILLIS].
     */
    @SuppressLint("MissingPermission")
    private suspend fun freshScan(own: Set<String>): List<RadioScanResult>? {
        val w = wifi ?: return null
        if (!scanPermissionGranted()) return null
        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                done.complete(Unit)
            }
        }
        register(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        try {
            val startedAt = SystemClock.elapsedRealtime()
            @Suppress("DEPRECATION")
            if (!w.startScan()) return null
            withTimeoutOrNull(SCAN_WAIT_MILLIS) { done.await() }
            val results = w.scanResults.map { RadioScanResult(it.SSID, it.BSSID, it.timestamp) }
            return RadioScanFilter.fresh(results, startedAt, own)
        } catch (_: SecurityException) {
            return null
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    /** This phone's own addresses as a scan could show them: the hub's device address and the group interface's MAC. */
    private fun ownBssids(): Set<String> = buildSet {
        ownHubAddress?.takeUnless(RadioDuplicateDecision::isAddressUnknown)?.let(::add)
        val name = ownInterface ?: return@buildSet
        val mac = runCatching { NetworkInterface.getByName(name)?.hardwareAddress }.getOrNull() ?: return@buildSet
        mac.joinToString(":") { "%02x".format(it) }.takeUnless(RadioDuplicateDecision::isAddressUnknown)?.let(::add)
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    /**
     * I2: when this phone is already a client of a formed group with one of [candidateNames] and
     * its P2P interface has an address, that attachment is the join result; no connect() needed.
     */
    private suspend fun reuseAttachment(
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        candidateNames: List<String>,
    ): RadioLinkResult? {
        val info = withTimeoutOrNull(INFO_TIMEOUT_MILLIS) { requestConnection(m, ch) } ?: return null
        val group = withTimeoutOrNull(INFO_TIMEOUT_MILLIS) { requestGroup(m, ch) }
        val host = reusableRadioAttachment(
            info.groupFormed, info.isGroupOwner, info.groupOwnerAddress?.hostAddress, group?.networkName, candidateNames,
        ) ?: return null
        ownInterface = group?.`interface`
        if (awaitLocalAddress(INFO_TIMEOUT_MILLIS) == null) {
            ownInterface = null
            return null
        }
        return RadioLinkResult.Joined(RadioAddress(host, port))
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestConnection(m: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pInfo? =
        suspendCancellableCoroutine { c ->
            try {
                m.requestConnectionInfo(ch) { if (c.isActive) c.resumeWith(Result.success(it)) }
            } catch (_: SecurityException) {
                if (c.isActive) c.resumeWith(Result.success(null))
            }
        }

    /** Whether radioNearby* and duplicate detection can scan; the UI asks for ACCESS_FINE_LOCATION when false. */
    fun scanPermissionGranted(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun warmNames(community: String) {
        if (!warming.add(community)) return
        scope.launch {
            try {
                credentials(community)
            } finally {
                warming.remove(community)
            }
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
        names.put(community, clock() / DAY_MILLIS, list.map { it.networkName }.toSet())
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
                    register(r, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
                    check() // already connected before the receiver existed
                }
            }
        } finally {
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
        }
    }

    private fun startDuplicateWatch(community: String, hostedName: String, group: WifiP2pGroup) {
        duplicateJob?.cancel()
        val rawOwn = group.owner?.deviceAddress
        val own = if (RadioDuplicateDecision.isAddressUnknown(rawOwn)) rawOwn else RadioScanFilter.normalizeMac(rawOwn!!)
        val members = group.clientList?.size ?: 0
        lateinit var self: Job
        self = scope.launch {
            while (isActive) {
                delay(DUPLICATE_SCAN_MILLIS)
                if (foreignHub(hostedName, own) == null) continue
                delay(Random.nextLong(DUPLICATE_BACKOFF_MILLIS + 1)) // desynchronise two hubs seeing each other
                val other = foreignHub(hostedName, own) ?: continue
                if (!RadioDuplicateDecision.shouldReport(own, other, members)) continue
                // Re-check right before acting: leave() may have cancelled this watch while we waited.
                if (!isActive || duplicateJob !== self) return@launch
                onDuplicateHub?.invoke(community)
                return@launch
            }
        }
        duplicateJob = self
    }

    /**
     * BSSID (normalised) of another access point with our group's name seen by a fresh scan, or
     * null; also null when scanning is unavailable or the scan was refused (no decision this round).
     */
    private suspend fun foreignHub(hostedName: String, own: String?): String? =
        freshScan(ownBssids())?.filter { it.ssid == hostedName }
            ?.mapNotNull { it.bssid?.let(RadioScanFilter::normalizeMac) }
            ?.firstOrNull { it != own }

    private companion object {
        const val GROUP_FORMATION_MILLIS = 5_000L
        const val LOCAL_ADDRESS_MILLIS = 5_000L
        const val POLL_MILLIS = 250L
        const val DUPLICATE_SCAN_MILLIS = 30_000L
        const val DUPLICATE_BACKOFF_MILLIS = 30_000L
        const val DAY_MILLIS = 86_400_000L
        const val SCAN_WAIT_MILLIS = 10_000L
        const val INFO_TIMEOUT_MILLIS = 2_000L
    }
}
