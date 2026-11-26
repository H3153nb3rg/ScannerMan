package com.scannerman.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scannerman.data.Avail
import com.scannerman.data.DeviceType
import com.scannerman.data.HistoryStore
import com.scannerman.data.Host
import com.scannerman.data.HostStatus
import com.scannerman.data.KnownDevice
import com.scannerman.data.KnownDeviceStore
import com.scannerman.data.NetworkProfile
import com.scannerman.data.NetworkProfileStore
import com.scannerman.data.Resolution
import com.scannerman.data.Settings
import com.scannerman.data.SettingsStore
import com.scannerman.data.hostSortKey
import com.scannerman.net.LocalNetwork
import com.scannerman.net.Phase
import com.scannerman.net.Scanner
import com.scannerman.net.WifiDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val network: LocalNetwork? = null,
    /** Named network (subnet + SSIDs) whose history is shown. Null while undecided. */
    val profile: NetworkProfile? = null,
    val wifi: WifiDetails.Info? = null,
    /** New SSID on a known subnet: ask whether it's the same network. */
    val pendingMerge: Resolution.Ask? = null,
    val hosts: List<Host> = emptyList(),
    val lastScan: Long? = null,
    /** True while showing hosts loaded from history (not yet re-scanned). */
    val fromHistory: Boolean = false,
    val scanning: Boolean = false,
    val phase: Phase? = null,
    val progress: Float = 0f,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val history = HistoryStore(app)
    private val settingsStore = SettingsStore(app)
    private val knownStore = KnownDeviceStore(app)
    private val profileStore = NetworkProfileStore(app)
    private var job: Job? = null
    /** A scan was requested while the merge question was open; run it after the answer. */
    private var scanAfterResolve = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    val settings: StateFlow<Settings> = settingsStore.flow
    val known: StateFlow<List<KnownDevice>> = knownStore.flow
    val profiles: StateFlow<List<NetworkProfile>> = profileStore.flow

    init {
        refreshNetwork(autoScan = settingsStore.value.autoScanOnStart)
    }

    /** Detects the current network and shows the stored result of the last run. */
    fun refreshNetwork(autoScan: Boolean = false) {
        if (_state.value.scanning) return
        viewModelScope.launch {
            val (net, profile) = resolveNetwork() ?: run { scanAfterResolve = scanAfterResolve || autoScan; return@launch }
            val snap = withContext(Dispatchers.IO) { history.load(profile.id) }
            _state.update {
                it.copy(
                    network = net,
                    hosts = sort(snap.hosts),
                    lastScan = snap.lastScan,
                    fromHistory = snap.hosts.isNotEmpty(),
                    message = if (net.scanPrefix != net.prefix)
                        "Subnet /${net.prefix} is large – scanning ${net.cidr} only" else it.message,
                )
            }
            if (autoScan) startScan()
        }
    }

    fun startScan() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val settings = settingsStore.value
            val (net, profile) = resolveNetwork() ?: run { scanAfterResolve = true; return@launch }
            val previous = withContext(Dispatchers.IO) { history.load(profile.id) }.hosts
            _state.update {
                it.copy(network = net, scanning = true, progress = 0f, hosts = emptyList(), fromHistory = false, message = null)
            }
            try {
                val found = Scanner(settings, getApplication()).scan(
                    net,
                    previous,
                    onProgress = { phase, done, total ->
                        _state.update { it.copy(phase = phase, progress = if (total == 0) 1f else done / total.toFloat()) }
                    },
                    onHostFound = { h ->
                        _state.update { s -> s.copy(hosts = sort(s.hosts.filter { it.address != h.address } + h)) }
                    },
                )
                val now = System.currentTimeMillis()
                val merged = knownStore.combine(HistoryStore.merge(previous, knownStore.combine(found), now))
                withContext(Dispatchers.IO) {
                    history.save(profile.id, now, merged)
                    knownStore.refresh(merged)
                }
                val moved = merged.count { h -> h.lastIpChange?.at == now }
                val online = merged.count { it.isOnline }
                _state.update {
                    it.copy(
                        hosts = sort(merged), lastScan = now,
                        message = "$online online (v4 ${merged.count { h -> h.v4 == Avail.UP }}, " +
                            "v6 ${merged.count { h -> h.v6 == Avail.UP }}) · " +
                            "${merged.count { h -> h.status == HostStatus.NEW }} new · " +
                            "${merged.count { h -> h.status == HostStatus.OFFLINE }} offline" +
                            (if (moved > 0) " · $moved changed IP" else ""),
                    )
                }
            } catch (e: CancellationException) {
                // Partial results are discarded; show the last complete run again.
                _state.update { it.copy(scanning = false, phase = null, message = "Scan stopped") }
                refreshNetwork()
                throw e
            } finally {
                _state.update { it.copy(scanning = false, phase = null) }
            }
        }
    }

    fun stopScan() {
        job?.cancel()
    }

    /** Removes hosts that were not seen in the last scan from history. */
    fun forgetOffline() = rewriteHistory { hosts -> hosts.filter { it.status != HostStatus.OFFLINE } }

    fun clearHistory() {
        val profile = _state.value.profile ?: return
        viewModelScope.launch(Dispatchers.IO) { history.clear(profile.id) }
        _state.update { it.copy(hosts = emptyList(), lastScan = null, fromHistory = false, message = "History cleared") }
    }

    // ---- Settings ----------------------------------------------------------------------

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = settingsStore.value.maxSubnetPrefix
        settingsStore.update(transform)
        if (settingsStore.value.maxSubnetPrefix != before) refreshNetwork()
    }

    // ---- Known devices ------------------------------------------------------------------

    /** Create or update a known device from [host] (null device = create new). */
    fun saveKnown(host: Host?, device: KnownDevice?, name: String, notes: String, type: DeviceType?) {
        val base = device ?: KnownDevice(name = name)
        val d = base.copy(name = name.trim(), notes = notes.trim(), type = type)
            .let { if (host != null) it.learn(host) else it }
        knownStore.upsert(d)
        recombine()
    }

    /** Edit a known device's identifiers directly (from the known-devices screen). */
    fun updateKnown(d: KnownDevice) {
        knownStore.upsert(d)
        recombine()
    }

    fun deleteKnown(id: String) = knownStore.delete(id)

    /** After the known list changed, merge host records that now belong together. */
    private fun recombine() = rewriteHistory { knownStore.combine(it) }

    private fun rewriteHistory(transform: (List<Host>) -> List<Host>) {
        val s = _state.value
        if (s.scanning) return
        val profile = s.profile ?: return
        val updated = sort(transform(s.hosts))
        _state.update { it.copy(hosts = updated) }
        viewModelScope.launch(Dispatchers.IO) {
            history.save(profile.id, s.lastScan ?: System.currentTimeMillis(), updated)
        }
    }

    // ---- Network profiles (subnet + Wi-Fi names) ---------------------------------------

    /**
     * Detects subnet + Wi-Fi and maps them to a network profile. Returns null if not
     * connected, or if the user first has to decide about a new SSID on a known subnet.
     */
    private suspend fun resolveNetwork(): Pair<LocalNetwork, NetworkProfile>? {
        val net = LocalNetwork.detect(getApplication(), settingsStore.value.maxSubnetPrefix)
        if (net == null) {
            _state.update { it.copy(network = null, profile = null, hosts = emptyList(), message = "Not connected to a network") }
            return null
        }
        val wifi = WifiDetails.current(getApplication())
        return when (val r = profileStore.resolve(net.cidr, net.gateway, wifi.ssid, wifi.bssid, wifi.frequency)) {
            is Resolution.Ok -> {
                _state.update { it.copy(network = net, wifi = wifi, profile = r.profile, pendingMerge = null) }
                net to r.profile
            }
            is Resolution.Ask -> {
                _state.update {
                    it.copy(network = net, wifi = wifi, profile = null, pendingMerge = r, hosts = emptyList(), lastScan = null)
                }
                null
            }
        }
    }

    /** Answer to the merge question: [into] = same network as that profile, null = separate. */
    fun answerMerge(into: NetworkProfile?) {
        val ask = _state.value.pendingMerge ?: return
        val p = if (into != null) profileStore.merge(ask, into) else profileStore.separate(ask)
        _state.update {
            it.copy(
                pendingMerge = null,
                message = if (into != null) "“${ask.ssid}” is now part of “${p.name}”" else "“${ask.ssid}” saved as separate network",
            )
        }
        val scan = scanAfterResolve
        scanAfterResolve = false
        refreshNetwork(autoScan = scan)
    }

    fun renameNetwork(name: String) {
        val p = _state.value.profile ?: return
        profileStore.rename(p.id, name)?.let { np -> _state.update { it.copy(profile = np) } }
    }

    /** Removes an SSID from the current network; when used again the app asks anew. */
    fun detachSsid(ssid: String) {
        val p = _state.value.profile ?: return
        profileStore.detachSsid(p.id, ssid)
        refreshNetwork()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun sort(hosts: List<Host>) = hosts.sortedWith(
        compareBy<Host> { it.status == HostStatus.OFFLINE }.thenBy { hostSortKey(it) }.thenBy { it.address }
    )
}
