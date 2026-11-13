package com.example.psiphon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class ScanTarget(
    val ip: String,
    val port: Int,
    val countryHint: String? = null,
    val proxyProtocol: String = "HTTP",
    val provider: CdnProvider = CdnProvider.AKAMAI
)

object PsiphonCleanIps {

    private val akamaiV4Cidrs = listOf(
        "23.32.0.0/24",
        "23.64.0.0/24",
        "104.64.0.0/24",
        "184.24.0.0/24"
    )

    private val akamaiV6Prefixes = listOf(
        "2600:1400:a::",
        "2600:1406:bc00::"
    )

    private val fastlyV4Cidrs = listOf(
        "151.101.0.0/24",
        "151.101.64.0/24",
        "151.101.128.0/24",
        "151.101.192.0/24",
        "199.232.0.0/24"
    )

    private val fastlyV6Prefixes = listOf(
        "2a04:4e42::",
        "2a04:4e42:200::",
        "2a04:4e42:400::",
        "2a04:4e42:600::"
    )

    private val cloudflareV4Cidrs = listOf(
        "104.16.0.0/24",
        "104.17.0.0/24",
        "104.18.0.0/24",
        "104.24.0.0/24",
        "172.64.0.0/24",
        "172.67.0.0/24",
        "162.159.0.0/24",
        "188.114.96.0/24"
    )

    private val cloudflareV6Prefixes = listOf(
        "2606:4700::",
        "2606:4700:4700::",
        "2606:4700:3030::",
        "2606:4700:10::"
    )

    private val arvanCloudV4Cidrs = listOf(
        "185.143.232.0/24",
        "185.143.233.0/24",
        "185.143.234.0/24",
        "185.143.235.0/24",
        "188.229.116.0/24",
        "94.101.182.0/24",
        "185.215.232.0/24",
        "194.36.174.0/24"
    )

    private val arvanCloudV6Prefixes = listOf(
        "2a0d:d080::",
        "2a0d:d080:1::"
    )

    private val gcoreV4Cidrs = listOf(
        "92.223.80.0/24",
        "92.223.84.0/24",
        "92.223.88.0/24",
        "92.223.92.0/24",
        "5.188.184.0/24",
        "185.209.160.0/24"
    )

    private val gcoreV6Prefixes = listOf(
        "2a03:90c0::",
        "2a03:90c0:100::",
        "2a03:90c0:200::"
    )

    private val cacheFlyV4Cidrs = listOf(
        "205.234.175.0/24",
        "140.99.93.0/24",
        "204.93.144.0/24"
    )

    private val cacheFlyV6Prefixes = listOf(
        "2606:6d00::",
        "2606:6d00:1::"
    )

    private val cloudFrontV4Cidrs = listOf(
        "13.32.0.0/24",
        "13.33.0.0/24",
        "13.35.0.0/24",
        "13.224.0.0/24",
        "18.64.0.0/24",
        "18.154.0.0/24",
        "52.84.0.0/24",
        "54.192.0.0/24",
        "99.84.0.0/24",
        "108.156.0.0/24",
        "143.204.0.0/24"
    )

    private val cloudFrontV6Prefixes = listOf(
        "2600:9000:2000::",
        "2600:9000:2100::",
        "2600:9000:2200::",
        "2600:9000:2400::"
    )

    private val dnsHostnames = listOf(
        "www.fastly.com",
        "www.akamai.com",
        "cloudflare-dns.com",
        "dns.google",
        "dns.quad9.net"
    )

    private val dnsFallbackV4 = listOf(
        "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9",
        "104.16.132.229", "104.16.133.229", "172.64.155.1", "162.159.192.1"
    )

    private val dnsFallbackV6 = listOf(
        "2606:4700:4700::1111",
        "2606:4700:4700::1001",
        "2001:4860:4860::8888",
        "2620:fe::fe"
    )

    suspend fun collectCandidates(
        provider: CdnProvider,
        includeIpv4: Boolean,
        includeIpv6: Boolean,
        customInput: String,
        importedServerIps: List<String>,
        maxCount: Int
    ): List<String> = withContext(Dispatchers.IO) {
        val pool = linkedSetOf<String>()

        if (importedServerIps.isNotEmpty() && provider == CdnProvider.CUSTOM) {
            importedServerIps.forEach { ip ->
                val isV6 = ip.contains(":")
                if ((isV6 && includeIpv6) || (!isV6 && includeIpv4)) {
                    pool.add(ip)
                }
            }
        }

        when (provider) {
            CdnProvider.AKAMAI -> collectCdnPool(
                pool = pool,
                dnsHosts = listOf("www.akamai.com", "a248.e.akamai.net"),
                v4Cidrs = akamaiV4Cidrs,
                v6Prefixes = akamaiV6Prefixes,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                maxCount = maxCount
            )
            CdnProvider.FASTLY -> collectCdnPool(
                pool = pool,
                dnsHosts = listOf("www.fastly.com", "global.ssl.fastly.net"),
                v4Cidrs = fastlyV4Cidrs,
                v6Prefixes = fastlyV6Prefixes,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                maxCount = maxCount
            )
            CdnProvider.CLOUDFLARE -> collectCdnPool(
                pool = pool,
                dnsHosts = listOf("cloudflare-dns.com", "www.cloudflare.com", "speed.cloudflare.com"),
                v4Cidrs = cloudflareV4Cidrs,
                v6Prefixes = cloudflareV6Prefixes,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                maxCount = maxCount
            )
            CdnProvider.ARVANCLOUD -> collectCdnPool(
                pool = pool,
                dnsHosts = listOf("www.arvancloud.ir", "napi.arvancloud.ir"),
                v4Cidrs = arvanCloudV4Cidrs,
                v6Prefixes = arvanCloudV6Prefixes,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                maxCount = maxCount
            )
            CdnProvider.GCORE -> collectCdnPool(
                pool = pool,
                dnsHosts = listOf("gcore.com", "cdn.gcore.com"),
                v4Cidrs = gcoreV4Cidrs,
                v6Prefixes = gcoreV6Prefixes,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                maxCount = maxCount
            )
            CdnProvider.CACHEFLY -> collectCdnPool(
                pool = pool,
                dnsHosts = listOf("www.cachefly.com", "cachefly.cachefly.net"),
                v4Cidrs = cacheFlyV4Cidrs,
                v6Prefixes = cacheFlyV6Prefixes,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                maxCount = maxCount
            )
            CdnProvider.AMAZON_CLOUDFRONT -> collectCdnPool(
                pool = pool,
                dnsHosts = listOf("d1.awsstatic.com", "aws.amazon.com"),
                v4Cidrs = cloudFrontV4Cidrs,
                v6Prefixes = cloudFrontV6Prefixes,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                maxCount = maxCount
            )
            CdnProvider.DNS_ANYCAST -> {
                resolveDnsForHosts(dnsHostnames, wantV4 = includeIpv4, wantV6 = includeIpv6)
                    .forEach { pool.add(it) }
                if (includeIpv4) dnsFallbackV4.forEach { pool.add(it) }
                if (includeIpv6) dnsFallbackV6.forEach { pool.add(it) }
            }
            CdnProvider.FREEPROXY_WORLD,
            CdnProvider.PROXYSCRAPE -> {
                // Fetched asynchronously with port/country/type metadata via dedicated sources
            }
            CdnProvider.CUSTOM -> {
                parseCustomTargets(customInput, includeIpv4, includeIpv6, maxCount)
                    .forEach { pool.add(it) }
            }
        }

        pool.take(maxCount)
    }

    private fun collectCdnPool(
        pool: MutableSet<String>,
        dnsHosts: List<String>,
        v4Cidrs: List<String>,
        v6Prefixes: List<String>,
        includeIpv4: Boolean,
        includeIpv6: Boolean,
        maxCount: Int
    ) {
        if (includeIpv4) {
            resolveDnsForHosts(dnsHosts, wantV4 = true, wantV6 = false).forEach { pool.add(it) }
            val perCidrLimit = (maxCount / v4Cidrs.size.coerceAtLeast(1)).coerceAtLeast(2)
            v4Cidrs.forEach { cidr ->
                expandIpv4Cidr(cidr, perCidrLimit).forEach { pool.add(it) }
            }
        }
        if (includeIpv6) {
            resolveDnsForHosts(dnsHosts, wantV4 = false, wantV6 = true).forEach { pool.add(it) }
            v6Prefixes.forEach { prefix ->
                for (i in 1..4) pool.add("$prefix$i")
            }
        }
    }

    suspend fun runScan(
        prober: PsiphonProbes,
        candidates: List<String>,
        targetPorts: List<Int>,
        localSocksPort: Int,
        provider: CdnProvider,
        method: PsiphonMethod,
        sniHost: String,
        timeoutMs: Int,
        verifyTls: Boolean,
        verifyHttpsTunnel: Boolean,
        concurrency: Int = 6,
        onTargetCompleted: suspend (ProbeTelemetry) -> Unit
    ) {
        val targets = candidates.flatMap { ip ->
            targetPorts.map { port -> ScanTarget(ip = ip, port = port, provider = provider) }
        }
        runTargetPairsScan(
            prober = prober,
            targets = targets,
            localSocksPort = localSocksPort,
            provider = provider,
            method = method,
            sniHost = sniHost,
            timeoutMs = timeoutMs,
            verifyTls = verifyTls,
            verifyHttpsTunnel = verifyHttpsTunnel,
            concurrency = concurrency,
            onTargetCompleted = onTargetCompleted
        )
    }

    suspend fun runTargetPairsScan(
        prober: PsiphonProbes,
        targets: List<ScanTarget>,
        localSocksPort: Int,
        provider: CdnProvider = CdnProvider.AKAMAI,
        method: PsiphonMethod,
        sniHost: String,
        timeoutMs: Int,
        verifyTls: Boolean,
        verifyHttpsTunnel: Boolean,
        concurrency: Int = 6,
        onTargetCompleted: suspend (ProbeTelemetry) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, 12))

        targets.map { target ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val result = prober.probeCandidate(
                        ip = target.ip,
                        targetPort = target.port,
                        localSocksPort = localSocksPort,
                        provider = target.provider,
                        method = method,
                        sniHost = sniHost,
                        timeoutMs = timeoutMs,
                        verifyTls = verifyTls,
                        verifyHttpsTunnel = verifyHttpsTunnel,
                        countryHint = target.countryHint,
                        proxyProtocol = target.proxyProtocol
                    )
                    onTargetCompleted(result)
                }
            }
        }.awaitAll()
    }

    private fun resolveDnsForHosts(
        hosts: List<String>,
        wantV4: Boolean,
        wantV6: Boolean
    ): List<String> {
        val out = linkedSetOf<String>()
        for (host in hosts) {
            try {
                val addresses = InetAddress.getAllByName(host)
                for (addr in addresses) {
                    val hostAddr = addr.hostAddress?.substringBefore("%") ?: continue
                    when {
                        addr is Inet4Address && wantV4 -> out.add(hostAddr)
                        addr is Inet6Address && wantV6 -> out.add(hostAddr)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out.toList()
    }

    fun parseCustomTargets(
        raw: String,
        includeIpv4: Boolean,
        includeIpv6: Boolean,
        limit: Int
    ): List<String> {
        val tokens = raw.split('\n', ',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val out = linkedSetOf<String>()
        for (token in tokens) {
            if (out.size >= limit) break
            if (token.contains("/") && !token.contains(":")) {
                if (includeIpv4) {
                    expandIpv4Cidr(token, limit).forEach { out.add(it) }
                }
            } else if (token.contains(":")) {
                if (includeIpv6) {
                    if (token.contains("/")) {
                        val base = token.substringBefore("/").trimEnd(':')
                        for (i in 1..6) out.add("$base::$i")
                    } else {
                        out.add(token.trim('[', ']'))
                    }
                }
            } else {
                if (includeIpv4) out.add(token)
            }
        }
        return out.take(limit)
    }

    private fun expandIpv4Cidr(cidr: String, maxPerCidr: Int): List<String> {
        val parts = cidr.split("/")
        val baseIp = parts.getOrNull(0) ?: return emptyList()
        val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        val octets = baseIp.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || prefix !in 16..32) return emptyList()

        val hostBits = 32 - prefix
        val totalHosts = if (hostBits >= 16) 254 else (1 shl hostBits).coerceAtMost(254)
        val baseInt = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
        val mask = if (prefix == 0) 0 else (-1 shl hostBits)
        val networkBase = baseInt and mask

        val step = (totalHosts / maxPerCidr.coerceAtLeast(1)).coerceAtLeast(1)
        val result = mutableListOf<String>()
        var offset = 1
        while (offset <= totalHosts && result.size < maxPerCidr) {
            val ipInt = networkBase + offset
            result.add(
                "${(ipInt ushr 24) and 0xFF}.${(ipInt ushr 16) and 0xFF}.${(ipInt ushr 8) and 0xFF}.${ipInt and 0xFF}"
            )
            offset += step
        }
        return result
    }
}
