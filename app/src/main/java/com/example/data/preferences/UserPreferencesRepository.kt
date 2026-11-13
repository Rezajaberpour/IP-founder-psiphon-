package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.psiphon.CdnProvider
import com.example.psiphon.PsiphonMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ipfounder_psiphon_prefs")

enum class AppLanguage {
    SYSTEM,
    ENGLISH,
    PERSIAN;

    companion object {
        fun fromName(value: String): AppLanguage =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

data class ScannerSettings(
    val provider: CdnProvider = CdnProvider.AKAMAI,
    val selectedProviders: Set<CdnProvider> = setOf(CdnProvider.AKAMAI),
    val method: PsiphonMethod = PsiphonMethod.FRONTED_MEEK_OSSH,
    val includeIpv4: Boolean = true,
    val includeIpv6: Boolean = true,
    val includeHttp: Boolean = true,
    val includeSocks5: Boolean = true,
    val targetPortsInput: String = "443",
    val localSocksPort: Int = 1080,
    val customTargetsInput: String = "",
    val sniOverride: String = "",
    val timeoutMs: Int = 3000,
    val maxCandidates: Int = 24,
    val verifyTls: Boolean = true,
    val verifyHttpsTunnel: Boolean = true,
    val selectedIpKey: String = "",
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val freeProxyCountry: String = "US",
    val freeProxyPortFilter: String = ""
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val SELECTED_PROVIDERS = stringPreferencesKey("selected_providers")
        val METHOD = stringPreferencesKey("method")
        val INCLUDE_IPV4 = booleanPreferencesKey("include_ipv4")
        val INCLUDE_IPV6 = booleanPreferencesKey("include_ipv6")
        val INCLUDE_HTTP = booleanPreferencesKey("include_http")
        val INCLUDE_SOCKS5 = booleanPreferencesKey("include_socks5")
        val TARGET_PORTS = stringPreferencesKey("target_ports")
        val LOCAL_SOCKS_PORT = intPreferencesKey("local_socks_port")
        val CUSTOM_TARGETS = stringPreferencesKey("custom_targets")
        val SNI_OVERRIDE = stringPreferencesKey("sni_override")
        val TIMEOUT_MS = intPreferencesKey("timeout_ms")
        val MAX_CANDIDATES = intPreferencesKey("max_candidates")
        val VERIFY_TLS = booleanPreferencesKey("verify_tls")
        val VERIFY_HTTPS = booleanPreferencesKey("verify_https")
        val SELECTED_IP_KEY = stringPreferencesKey("selected_ip_key")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val FREEPROXY_COUNTRY = stringPreferencesKey("freeproxy_country")
        val FREEPROXY_PORT_FILTER = stringPreferencesKey("freeproxy_port_filter")
    }

    private fun readSettings(prefs: Preferences): ScannerSettings {
        val legacyProvider = CdnProvider.fromName(prefs[Keys.PROVIDER] ?: CdnProvider.AKAMAI.name)
        val rawSelected = prefs[Keys.SELECTED_PROVIDERS]
        val selectedProviders = if (rawSelected.isNullOrBlank()) {
            setOf(legacyProvider)
        } else {
            CdnProvider.parseSet(rawSelected, fallback = legacyProvider)
        }
        return ScannerSettings(
            provider = selectedProviders.firstOrNull() ?: legacyProvider,
            selectedProviders = selectedProviders,
            method = PsiphonMethod.fromName(prefs[Keys.METHOD] ?: PsiphonMethod.FRONTED_MEEK_OSSH.name),
            includeIpv4 = prefs[Keys.INCLUDE_IPV4] ?: true,
            includeIpv6 = prefs[Keys.INCLUDE_IPV6] ?: true,
            includeHttp = prefs[Keys.INCLUDE_HTTP] ?: true,
            includeSocks5 = prefs[Keys.INCLUDE_SOCKS5] ?: true,
            targetPortsInput = prefs[Keys.TARGET_PORTS] ?: "443",
            localSocksPort = prefs[Keys.LOCAL_SOCKS_PORT] ?: 1080,
            customTargetsInput = prefs[Keys.CUSTOM_TARGETS] ?: "",
            sniOverride = prefs[Keys.SNI_OVERRIDE] ?: "",
            timeoutMs = prefs[Keys.TIMEOUT_MS] ?: 3000,
            maxCandidates = prefs[Keys.MAX_CANDIDATES] ?: 24,
            verifyTls = prefs[Keys.VERIFY_TLS] ?: true,
            verifyHttpsTunnel = prefs[Keys.VERIFY_HTTPS] ?: true,
            selectedIpKey = prefs[Keys.SELECTED_IP_KEY] ?: "",
            appLanguage = AppLanguage.fromName(prefs[Keys.APP_LANGUAGE] ?: AppLanguage.SYSTEM.name),
            freeProxyCountry = prefs[Keys.FREEPROXY_COUNTRY] ?: "US",
            freeProxyPortFilter = prefs[Keys.FREEPROXY_PORT_FILTER] ?: ""
        )
    }

    val scannerSettings: Flow<ScannerSettings> = context.dataStore.data.map(::readSettings)

    suspend fun update(transform: (ScannerSettings) -> ScannerSettings) {
        context.dataStore.edit { prefs ->
            val current = readSettings(prefs)
            val updated = transform(current)
            val safeSet = updated.selectedProviders.ifEmpty { setOf(updated.provider) }
            prefs[Keys.PROVIDER] = (safeSet.firstOrNull() ?: updated.provider).name
            prefs[Keys.SELECTED_PROVIDERS] = safeSet.joinToString(",") { it.name }
            prefs[Keys.METHOD] = updated.method.name
            prefs[Keys.INCLUDE_IPV4] = updated.includeIpv4
            prefs[Keys.INCLUDE_IPV6] = updated.includeIpv6
            prefs[Keys.INCLUDE_HTTP] = updated.includeHttp
            prefs[Keys.INCLUDE_SOCKS5] = updated.includeSocks5
            prefs[Keys.TARGET_PORTS] = updated.targetPortsInput
            prefs[Keys.LOCAL_SOCKS_PORT] = updated.localSocksPort
            prefs[Keys.CUSTOM_TARGETS] = updated.customTargetsInput
            prefs[Keys.SNI_OVERRIDE] = updated.sniOverride
            prefs[Keys.TIMEOUT_MS] = updated.timeoutMs
            prefs[Keys.MAX_CANDIDATES] = updated.maxCandidates
            prefs[Keys.VERIFY_TLS] = updated.verifyTls
            prefs[Keys.VERIFY_HTTPS] = updated.verifyHttpsTunnel
            prefs[Keys.SELECTED_IP_KEY] = updated.selectedIpKey
            prefs[Keys.APP_LANGUAGE] = updated.appLanguage.name
            prefs[Keys.FREEPROXY_COUNTRY] = updated.freeProxyCountry
            prefs[Keys.FREEPROXY_PORT_FILTER] = updated.freeProxyPortFilter
        }
    }
}
