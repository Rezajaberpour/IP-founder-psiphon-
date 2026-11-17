package com.example.psiphon

import androidx.annotation.StringRes
import com.example.R

enum class CdnProvider(
    val displayName: String,
    @StringRes val labelRes: Int,
    val defaultSni: String,
    val description: String,
    val isCdn: Boolean = false,
    val isProxyListSource: Boolean = false
) {
    AKAMAI(
        displayName = "Akamai",
        labelRes = R.string.provider_akamai,
        defaultSni = "www.akamai.com",
        description = "Akamai Edge IPv4 & IPv6 anycast/CDN subnets",
        isCdn = true
    ),
    FASTLY(
        displayName = "Fastly",
        labelRes = R.string.provider_fastly,
        defaultSni = "www.fastly.com",
        description = "Fastly global anycast IPv4 & IPv6 edge ranges",
        isCdn = true
    ),
    CLOUDFLARE(
        displayName = "Cloudflare",
        labelRes = R.string.provider_cloudflare,
        defaultSni = "cloudflare-dns.com",
        description = "Cloudflare global anycast IPv4 & IPv6 edge subnets",
        isCdn = true
    ),
    ARVANCLOUD(
        displayName = "ArvanCloud",
        labelRes = R.string.provider_arvancloud,
        defaultSni = "www.arvancloud.ir",
        description = "ArvanCloud CDN edge IPv4 & IPv6 ranges",
        isCdn = true
    ),
    GCORE(
        displayName = "Gcore",
        labelRes = R.string.provider_gcore,
        defaultSni = "gcore.com",
        description = "Gcore global CDN edge IPv4 & IPv6 subnets",
        isCdn = true
    ),
    CACHEFLY(
        displayName = "CacheFly",
        labelRes = R.string.provider_cachefly,
        defaultSni = "www.cachefly.com",
        description = "CacheFly global anycast CDN IPv4 & IPv6 blocks",
        isCdn = true
    ),
    AMAZON_CLOUDFRONT(
        displayName = "Amazon CloudFront",
        labelRes = R.string.provider_cloudfront,
        defaultSni = "d1.awsstatic.com",
        description = "Amazon CloudFront edge IPv4 & IPv6 subnets",
        isCdn = true
    ),
    FREEPROXY_WORLD(
        displayName = "FreeProxy.world",
        labelRes = R.string.provider_freeproxy,
        defaultSni = "cloudflare-dns.com",
        description = "Live endpoints fetched from freeproxy.world",
        isProxyListSource = true
    ),
    PROXYSCRAPE(
        displayName = "ProxyScrape",
        labelRes = R.string.provider_proxyscrape,
        defaultSni = "cloudflare-dns.com",
        description = "Live HTTP & SOCKS5 endpoints fetched from api.proxyscrape.com",
        isProxyListSource = true
    ),
    GEONODE(
        displayName = "GeoNode",
        labelRes = R.string.provider_geonode,
        defaultSni = "cloudflare-dns.com",
        description = "Geo-tagged HTTP & SOCKS5 endpoints from proxylist.geonode.com API",
        isProxyListSource = true
    ),
    THESPEEDX(
        displayName = "TheSpeedX",
        labelRes = R.string.provider_thespeedx,
        defaultSni = "cloudflare-dns.com",
        description = "Public HTTP & SOCKS5 feeds from TheSpeedX PROXY-List",
        isProxyListSource = true
    ),
    MONOSANS(
        displayName = "Monosans",
        labelRes = R.string.provider_monosans,
        defaultSni = "cloudflare-dns.com",
        description = "Public verified HTTP & SOCKS5 proxy feeds from Monosans",
        isProxyListSource = true
    ),
    OPENPROXY(
        displayName = "OpenProxyList",
        labelRes = R.string.provider_openproxy,
        defaultSni = "cloudflare-dns.com",
        description = "Live HTTP & SOCKS5 endpoints from OpenProxyList API",
        isProxyListSource = true
    ),
    DNS_ANYCAST(
        displayName = "DNS & Edge",
        labelRes = R.string.provider_dns_anycast,
        defaultSni = "cloudflare-dns.com",
        description = "Live DNS A/AAAA resolved CDN & resolver edges"
    ),
    CUSTOM(
        displayName = "Custom",
        labelRes = R.string.provider_custom,
        defaultSni = "cloudflare-dns.com",
        description = "User-supplied IPv4, IPv6, or CIDR ranges"
    );

    companion object {
        val realScanCdns: List<CdnProvider>
            get() = entries.filter { it.isCdn }

        val proxyListSources: List<CdnProvider>
            get() = entries.filter { it.isProxyListSource }

        fun fromName(value: String): CdnProvider =
            entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) ||
                    it.displayName.equals(value, ignoreCase = true)
            } ?: AKAMAI

        fun parseSet(serialized: String, fallback: CdnProvider = AKAMAI): Set<CdnProvider> {
            val parsed = serialized.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { token ->
                    entries.firstOrNull {
                        it.name.equals(token, ignoreCase = true) ||
                            it.displayName.equals(token, ignoreCase = true)
                    }
                }
                .toSet()
            return parsed.ifEmpty { setOf(fallback) }
        }
    }
}

enum class PsiphonMethod(
    val wireName: String,
    val badge: String,
    val description: String
) {
    FRONTED_MEEK_OSSH(
        wireName = "FRONTED-MEEK-OSSH",
        badge = "F-MEEK",
        description = "Domain-fronted TLS over CDN edge + Obfuscated SSH"
    ),
    UNFRONTED_MEEK_OSSH(
        wireName = "UNFRONTED-MEEK-OSSH",
        badge = "MEEK",
        description = "Direct HTTPS Meek encapsulation on target port"
    ),
    OSSH(
        wireName = "OSSH",
        badge = "OSSH",
        description = "Obfuscated SSH handshake"
    ),
    QUIC_OSSH(
        wireName = "QUIC-OSSH",
        badge = "QUIC",
        description = "QUIC-encapsulated OSSH transport"
    ),
    SSH(
        wireName = "SSH",
        badge = "SSH",
        description = "Direct SSH protocol"
    );

    companion object {
        fun fromName(value: String): PsiphonMethod =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FRONTED_MEEK_OSSH
    }
}

enum class IpVersionFilter {
    IPV4,
    IPV6
}

enum class ScanStage(val label: String, @StringRes val labelRes: Int) {
    IDLE("Ready to Scan", R.string.stage_idle),
    COLLECTING_IPS("Collecting IPv4/IPv6 Candidates…", R.string.stage_collecting_ips),
    PROBING("Testing TCP, TLS Cert & HTTPS Tunnel…", R.string.stage_probing),
    COMPLETED("Scan Completed", R.string.stage_completed),
    STOPPED("Scan Stopped", R.string.stage_stopped)
}

data class ScanProgressState(
    val isScanning: Boolean = false,
    val stage: ScanStage = ScanStage.IDLE,
    val testedCount: Int = 0,
    val totalCount: Int = 0,
    val passedCount: Int = 0,
    val failedCount: Int = 0,
    val currentTarget: String = ""
)

data class ProbeTelemetry(
    val ip: String,
    val ipVersion: String, // "IPv4" or "IPv6"
    val targetPort: Int,
    val localSocksPort: Int,
    val proxyProtocol: String = "HTTP", // "HTTP" or "SOCKS5"
    val provider: CdnProvider,
    val method: PsiphonMethod,
    val tcpPingMs: Long?,
    val httpsPingMs: Long?,
    val tlsVersion: String,
    val certIssuer: String,
    val certValid: Boolean,
    val httpsStatusCode: Int?,
    val countryCode: String,
    val countryName: String,
    val countryFlag: String,
    val city: String = "Unknown",
    val isp: String = "Unknown",
    val isClean: Boolean,
    val failureReason: String? = null,
    val aiScore: Int = 0, // 0..100 Hybrid Neuro-Fuzzy + ANN + Game Theory Choquet Fusion
    val fuzzyMembership: Float = 0f, // ANFIS Layer-5 membership score 0f..1f
    val nnPrediction: Float = 0f, // Online-adapted MLP ANN probability 0f..1f
    val nashPayoff: Float = 0f, // Fictitious Play Mixed Nash Equilibrium strategic payoff 0f..1f
    val anonymityConfidenceScore: Int = 0, // 0..100% Sugeno FIS + ANN-tuned MF Anonymity Confidence
    val proxyHealthScore: Int = 0, // 0..100 Sugeno FIS Proxy Health Score
    val aiVerdict: String = "MODERATE" // OPTIMAL, RESILIENT, MODERATE, RISKY
)
