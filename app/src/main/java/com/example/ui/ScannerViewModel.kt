package com.example.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.local.CleanIpEntity
import com.example.data.preferences.AppLanguage
import com.example.data.preferences.ScannerSettings
import com.example.data.repository.MlmRepository
import com.example.psiphon.CdnProvider
import com.example.psiphon.FreeProxyWorldSource
import com.example.psiphon.ProbeTelemetry
import com.example.psiphon.ProxyScrapeSource
import com.example.psiphon.PsiphonCleanIps
import com.example.psiphon.PsiphonMethod
import com.example.psiphon.PsiphonProbeService
import com.example.psiphon.PsiphonProbes
import com.example.psiphon.ScanProgressState
import com.example.psiphon.ScanStage
import com.example.psiphon.ScanTarget
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ScannerViewModel(
    private val appContext: Context,
    private val repository: MlmRepository
) : ViewModel() {

    private val _settings = MutableStateFlow(ScannerSettings())
    val settings: StateFlow<ScannerSettings> = _settings.asStateFlow()
    @Volatile
    private var hasLocalSettingsEdit = false

    val savedCleanIps: StateFlow<List<CleanIpEntity>> = repository.savedCleanIps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanProgress = MutableStateFlow(ScanProgressState())
    val scanProgress: StateFlow<ScanProgressState> = _scanProgress.asStateFlow()

    private val _liveResults = MutableStateFlow<List<ProbeTelemetry>>(emptyList())
    val liveResults: StateFlow<List<ProbeTelemetry>> = _liveResults.asStateFlow()

    private val _importedServerEntries = MutableStateFlow<List<String>>(emptyList())
    val importedServerEntries: StateFlow<List<String>> = _importedServerEntries.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>()
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val fallbackProber = PsiphonProbes()
    private var boundService: PsiphonProbeService? = null
    private var scanJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PsiphonProbeService.LocalBinder
            boundService = binder?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    init {
        val intent = Intent(appContext, PsiphonProbeService::class.java)
        appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        viewModelScope.launch {
            val loaded = repository.scannerSettings.first()
            if (!hasLocalSettingsEdit) {
                _settings.value = loaded
            }
        }
    }

    private fun activeProber(): PsiphonProbes = boundService?.prober ?: fallbackProber

    fun setProvider(provider: CdnProvider) = updateSettings {
        it.copy(provider = provider, selectedProviders = setOf(provider))
    }

    fun toggleProvider(provider: CdnProvider) = updateSettings { current ->
        val currentSet = current.selectedProviders.ifEmpty { setOf(current.provider) }
        val nextSet = if (provider in currentSet) {
            if (currentSet.size > 1) currentSet - provider else currentSet
        } else {
            currentSet + provider
        }
        current.copy(
            provider = nextSet.firstOrNull() ?: provider,
            selectedProviders = nextSet
        )
    }

    fun toggleAllRealScanCdns() = updateSettings { current ->
        val cdns = CdnProvider.realScanCdns.toSet()
        val currentSet = current.selectedProviders.ifEmpty { setOf(current.provider) }
        val allCdnsSelected = cdns.all { it in currentSet }
        val nextSet = if (allCdnsSelected) {
            (currentSet - cdns).ifEmpty { setOf(CdnProvider.AKAMAI) }
        } else {
            currentSet + cdns
        }
        current.copy(
            provider = nextSet.first(),
            selectedProviders = nextSet
        )
    }

    fun setMethod(method: PsiphonMethod) = updateSettings { it.copy(method = method) }

    fun toggleIpv4() = updateSettings { current ->
        val next = !current.includeIpv4
        if (!next && !current.includeIpv6) current else current.copy(includeIpv4 = next)
    }

    fun toggleIpv6() = updateSettings { current ->
        val next = !current.includeIpv6
        if (!next && !current.includeIpv4) current else current.copy(includeIpv6 = next)
    }

    fun toggleHttp() = updateSettings { current ->
        val nextHttp = !current.includeHttp
        if (!nextHttp && !current.includeSocks5) {
            current
        } else {
            val trimmed = current.targetPortsInput.trim()
            val updatedPorts = when {
                nextHttp && !current.includeSocks5 && trimmed == "1080" -> "443"
                !nextHttp && current.includeSocks5 && trimmed in setOf("443", "80", "80, 443") -> "1080"
                else -> current.targetPortsInput
            }
            current.copy(includeHttp = nextHttp, targetPortsInput = updatedPorts)
        }
    }

    fun toggleSocks5() = updateSettings { current ->
        val nextSocks5 = !current.includeSocks5
        if (!nextSocks5 && !current.includeHttp) {
            current
        } else {
            val trimmed = current.targetPortsInput.trim()
            val updatedPorts = when {
                nextSocks5 && !current.includeHttp && trimmed in setOf("443", "80", "80, 443") -> "1080"
                !nextSocks5 && current.includeHttp && trimmed == "1080" -> "443"
                else -> current.targetPortsInput
            }
            current.copy(includeSocks5 = nextSocks5, targetPortsInput = updatedPorts)
        }
    }

    fun updateSettings(transform: (ScannerSettings) -> ScannerSettings) {
        hasLocalSettingsEdit = true
        _settings.update(transform)
        viewModelScope.launch {
            repository.updateSettings { _settings.value }
        }
    }

    fun setFreeProxyCountry(countryCode: String) =
        updateSettings { it.copy(freeProxyCountry = countryCode) }

    fun setFreeProxyPortFilter(port: String) =
        updateSettings { it.copy(freeProxyPortFilter = port.filter(Char::isDigit).take(5)) }

    fun resetSelectedIp() {
        updateSettings { it.copy(selectedIpKey = "") }
        emitString(R.string.msg_reset_auto)
    }

    fun selectCleanIp(idKey: String) {
        updateSettings { it.copy(selectedIpKey = idKey) }
        val displayEndpoint = idKey.removeSuffix(":HTTP").removeSuffix(":SOCKS5")
        emitString(R.string.msg_selected_ip, displayEndpoint)
    }

    fun startScan() {
        if (_scanProgress.value.isScanning) return
        val currentSettings = settings.value
        val ports = parseTargetPorts(currentSettings.targetPortsInput)

        scanJob = viewModelScope.launch {
            _liveResults.value = emptyList()
            _scanProgress.value = ScanProgressState(
                isScanning = true,
                stage = ScanStage.COLLECTING_IPS
            )
            try {
                val activeProviders = CdnProvider.entries.filter {
                    it in currentSettings.selectedProviders.ifEmpty { setOf(currentSettings.provider) }
                }
                val perProviderLimit = (currentSettings.maxCandidates / activeProviders.size.coerceAtLeast(1))
                    .coerceAtLeast(4)

                val perProviderBuckets: List<List<ScanTarget>> = coroutineScope {
                    activeProviders.map { selectedProvider ->
                        async {
                            when (selectedProvider) {
                                CdnProvider.FREEPROXY_WORLD -> {
                                    val fetched = FreeProxyWorldSource.fetchCandidates(
                                        client = activeProber().sharedClient,
                                        countryCode = currentSettings.freeProxyCountry,
                                        portFilter = currentSettings.freeProxyPortFilter,
                                        includeIpv4 = currentSettings.includeIpv4,
                                        includeIpv6 = currentSettings.includeIpv6,
                                        includeHttp = currentSettings.includeHttp,
                                        includeSocks5 = currentSettings.includeSocks5,
                                        limit = perProviderLimit
                                    )
                                    fetched.map {
                                        ScanTarget(
                                            ip = it.ip,
                                            port = it.port,
                                            countryHint = it.countryCode,
                                            proxyProtocol = it.proxyType,
                                            provider = CdnProvider.FREEPROXY_WORLD
                                        )
                                    }
                                }
                                CdnProvider.PROXYSCRAPE -> {
                                    val fetched = ProxyScrapeSource.fetchCandidates(
                                        client = activeProber().sharedClient,
                                        countryCode = currentSettings.freeProxyCountry,
                                        portFilter = currentSettings.freeProxyPortFilter,
                                        includeIpv4 = currentSettings.includeIpv4,
                                        includeIpv6 = currentSettings.includeIpv6,
                                        includeHttp = currentSettings.includeHttp,
                                        includeSocks5 = currentSettings.includeSocks5,
                                        limit = perProviderLimit
                                    )
                                    fetched.map {
                                        ScanTarget(
                                            ip = it.ip,
                                            port = it.port,
                                            countryHint = it.countryCode,
                                            proxyProtocol = it.proxyType,
                                            provider = CdnProvider.PROXYSCRAPE
                                        )
                                    }
                                }
                                else -> {
                                    val candidates = PsiphonCleanIps.collectCandidates(
                                        provider = selectedProvider,
                                        includeIpv4 = currentSettings.includeIpv4,
                                        includeIpv6 = currentSettings.includeIpv6,
                                        customInput = currentSettings.customTargetsInput,
                                        importedServerIps = _importedServerEntries.value,
                                        maxCount = perProviderLimit
                                    )
                                    candidates.flatMap { ip ->
                                        ports.map { port ->
                                            val protocol = when {
                                                currentSettings.includeHttp && !currentSettings.includeSocks5 -> "HTTP"
                                                !currentSettings.includeHttp && currentSettings.includeSocks5 -> "SOCKS5"
                                                port in setOf(1080, 9050, 10808) -> "SOCKS5"
                                                else -> "HTTP"
                                            }
                                            ScanTarget(
                                                ip = ip,
                                                port = port,
                                                countryHint = null,
                                                proxyProtocol = protocol,
                                                provider = selectedProvider
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                val liveProxyCount = perProviderBuckets.sumOf { bucket ->
                    bucket.count { it.provider.isProxyListSource }
                }
                if (liveProxyCount > 0) {
                    emitString(R.string.msg_freeproxy_fetched, liveProxyCount)
                }

                // Interleave targets round-robin across providers so live stream shows every selected source
                val targetPairs = interleaveBuckets(perProviderBuckets)
                    .distinctBy { "${it.ip}:${it.port}:${it.proxyProtocol}:${it.provider.name}" }

                if (targetPairs.isEmpty()) {
                    _scanProgress.value = ScanProgressState(
                        isScanning = false,
                        stage = ScanStage.STOPPED
                    )
                    emitString(R.string.msg_no_candidates)
                    return@launch
                }

                _scanProgress.value = ScanProgressState(
                    isScanning = true,
                    stage = ScanStage.PROBING,
                    testedCount = 0,
                    totalCount = targetPairs.size
                )

                val lock = Any()
                PsiphonCleanIps.runTargetPairsScan(
                    prober = activeProber(),
                    targets = targetPairs,
                    localSocksPort = currentSettings.localSocksPort,
                    provider = currentSettings.provider,
                    method = currentSettings.method,
                    sniHost = currentSettings.sniOverride,
                    timeoutMs = currentSettings.timeoutMs,
                    verifyTls = currentSettings.verifyTls,
                    verifyHttpsTunnel = currentSettings.verifyHttpsTunnel
                ) { result ->
                    if (result.isClean || result.tcpPingMs != null) {
                        repository.saveProbeResult(result)
                    }
                    synchronized(lock) {
                        _liveResults.update { current ->
                            (listOf(result) + current).sortedWith(
                                compareByDescending<ProbeTelemetry> { it.isClean }
                                    .thenBy { it.httpsPingMs ?: Long.MAX_VALUE }
                            )
                        }
                        _scanProgress.update { p ->
                            p.copy(
                                testedCount = p.testedCount + 1,
                                passedCount = p.passedCount + if (result.isClean) 1 else 0,
                                failedCount = p.failedCount + if (!result.isClean) 1 else 0,
                                currentTarget = "${result.ip}:${result.targetPort}"
                            )
                        }
                    }
                }

                val finalState = _scanProgress.value
                _scanProgress.value = finalState.copy(
                    isScanning = false,
                    stage = ScanStage.COMPLETED,
                    currentTarget = ""
                )
                emitString(R.string.msg_scan_complete, finalState.passedCount)
            } catch (_: CancellationException) {
                _scanProgress.update {
                    it.copy(
                        isScanning = false,
                        stage = ScanStage.STOPPED,
                        currentTarget = ""
                    )
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanProgress.update {
            it.copy(
                isScanning = false,
                stage = ScanStage.STOPPED,
                currentTarget = ""
            )
        }
        emitString(R.string.msg_scan_stopped)
    }

    fun rescanSingleEntity(entity: CleanIpEntity) {
        val currentSettings = settings.value
        viewModelScope.launch {
            emitString(R.string.msg_rescanning_single, "${entity.ip}:${entity.targetPort}")
            val result = activeProber().probeCandidate(
                ip = entity.ip,
                targetPort = entity.targetPort,
                localSocksPort = currentSettings.localSocksPort,
                provider = CdnProvider.entries.firstOrNull { it.displayName == entity.provider }
                    ?: currentSettings.provider,
                method = PsiphonMethod.entries.firstOrNull { it.wireName == entity.method }
                    ?: currentSettings.method,
                sniHost = currentSettings.sniOverride,
                timeoutMs = currentSettings.timeoutMs,
                verifyTls = currentSettings.verifyTls,
                verifyHttpsTunnel = currentSettings.verifyHttpsTunnel,
                proxyProtocol = entity.proxyProtocol
            )
            repository.saveProbeResult(result)
            val pingLabel = result.httpsPingMs?.let { "${it}ms" } ?: (result.failureReason ?: "Failed")
            emitMessage("${entity.countryFlag} ${entity.ip}:${entity.targetPort} -> $pingLabel")
        }
    }

    fun rescanAllSaved() {
        val list = savedCleanIps.value
        if (list.isEmpty() || _scanProgress.value.isScanning) return
        val currentSettings = settings.value
        scanJob = viewModelScope.launch {
            _scanProgress.value = ScanProgressState(
                isScanning = true,
                stage = ScanStage.PROBING,
                totalCount = list.size
            )
            try {
                list.forEach { entity ->
                    val result = activeProber().probeCandidate(
                        ip = entity.ip,
                        targetPort = entity.targetPort,
                        localSocksPort = currentSettings.localSocksPort,
                        provider = CdnProvider.entries.firstOrNull { it.displayName == entity.provider }
                            ?: currentSettings.provider,
                        method = PsiphonMethod.entries.firstOrNull { it.wireName == entity.method }
                            ?: currentSettings.method,
                        sniHost = currentSettings.sniOverride,
                        timeoutMs = currentSettings.timeoutMs,
                        verifyTls = currentSettings.verifyTls,
                        verifyHttpsTunnel = currentSettings.verifyHttpsTunnel,
                        proxyProtocol = entity.proxyProtocol
                    )
                    repository.saveProbeResult(result)
                    _scanProgress.update { p ->
                        p.copy(
                            testedCount = p.testedCount + 1,
                            passedCount = p.passedCount + if (result.isClean) 1 else 0,
                            failedCount = p.failedCount + if (!result.isClean) 1 else 0,
                            currentTarget = "${entity.ip}:${entity.targetPort}"
                        )
                    }
                }
                _scanProgress.update { it.copy(isScanning = false, stage = ScanStage.COMPLETED, currentTarget = "") }
                emitString(R.string.msg_rescan_all_complete, list.size)
            } catch (_: CancellationException) {
                _scanProgress.update { it.copy(isScanning = false, stage = ScanStage.STOPPED) }
            }
        }
    }

    fun deleteCleanIp(idKey: String) {
        if (settings.value.selectedIpKey == idKey) {
            updateSettings { it.copy(selectedIpKey = "") }
        }
        viewModelScope.launch {
            repository.deleteCleanIp(idKey)
        }
    }

    fun removeFailedIps() {
        viewModelScope.launch {
            val removed = repository.deleteFailedCleanIps()
            _liveResults.update { list -> list.filter { it.isClean } }
            emitString(R.string.msg_removed_failed, removed)
        }
    }

    fun clearAllSaved() {
        updateSettings { it.copy(selectedIpKey = "") }
        viewModelScope.launch {
            repository.clearAllCleanIps()
            emitString(R.string.msg_cleared_saved)
        }
    }

    fun importServerEntriesText(content: String) {
        val entries = activeProber().parseServerEntries(content)
        if (entries.isEmpty()) {
            emitString(R.string.msg_no_ips_in_file)
            return
        }
        _importedServerEntries.value = entries
        updateSettings {
            it.copy(
                provider = CdnProvider.CUSTOM,
                selectedProviders = it.selectedProviders + CdnProvider.CUSTOM,
                customTargetsInput = entries.joinToString(", ")
            )
        }
        emitString(R.string.msg_loaded_server_entries, entries.size)
    }

    private fun <T> interleaveBuckets(buckets: List<List<T>>): List<T> {
        if (buckets.isEmpty()) return emptyList()
        val maxLen = buckets.maxOfOrNull { it.size } ?: 0
        val out = ArrayList<T>(buckets.sumOf { it.size })
        for (i in 0 until maxLen) {
            for (bucket in buckets) {
                if (i < bucket.size) out.add(bucket[i])
            }
        }
        return out
    }

    private fun parseTargetPorts(input: String): List<Int> {
        val parsed = input.split(',', '،', ' ', ';')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .distinct()
        return parsed.ifEmpty { listOf(443) }
    }

    fun notifyMessage(message: String) = emitMessage(message)

    private fun localizedContext(): Context {
        val locale = when (settings.value.appLanguage) {
            AppLanguage.SYSTEM -> return appContext
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.PERSIAN -> Locale.forLanguageTag("fa")
        }
        val config = Configuration(appContext.resources.configuration)
        config.setLocale(locale)
        return appContext.createConfigurationContext(config)
    }

    private fun emitString(@StringRes resId: Int, vararg args: Any) {
        emitMessage(localizedContext().getString(resId, *args))
    }

    private fun emitMessage(msg: String) {
        viewModelScope.launch { _snackbarEvents.emit(msg) }
    }

    override fun onCleared() {
        scanJob?.cancel()
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
        }
        super.onCleared()
    }

    companion object {
        fun provideFactory(appContext: Context, repository: MlmRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ScannerViewModel(appContext.applicationContext, repository) as T
                }
            }
    }
}
