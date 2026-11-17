package com.example.psiphon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class CloudflareEdgeNode(
    val id: String,
    val ip: String,
    val label: String,
    val regionHint: String,
    val port: Int = 443
)

data class RttSample(
    val sampleIndex: Int,
    val nodeId: String,
    val ip: String,
    val label: String,
    val tcpRttMs: Long?,
    val totalRttMs: Long?,
    val coloCode: String,
    val timestampMs: Long,
    val success: Boolean,
    val error: String? = null
)

data class CloudflareNodeStats(
    val node: CloudflareEdgeNode,
    val latestRttMs: Long? = null,
    val minRttMs: Long? = null,
    val avgRttMs: Long? = null,
    val maxRttMs: Long? = null,
    val jitterMs: Long? = null,
    val packetLossPercent: Int = 0,
    val coloCode: String = "--",
    val samples: List<RttSample> = emptyList()
)

data class CloudflareDiagnosticState(
    val nodes: List<CloudflareEdgeNode> = CloudflareDiagnosticEngine.presetNodes,
    val selectedNodeIds: Set<String> = setOf("cf_dns_1", "cf_edge_104_16", "cf_edge_172_64"),
    val isRunning: Boolean = false,
    val intervalMs: Long = 1000L,
    val totalSweeps: Int = 0,
    val nodeStats: Map<String, CloudflareNodeStats> = emptyMap()
)

object CloudflareDiagnosticEngine {

    const val MAX_HISTORY_SAMPLES = 30

    val presetNodes: List<CloudflareEdgeNode> = listOf(
        CloudflareEdgeNode(
            id = "cf_dns_1",
            ip = "1.1.1.1",
            label = "1.1.1.1",
            regionHint = "Primary Anycast"
        ),
        CloudflareEdgeNode(
            id = "cf_dns_2",
            ip = "1.0.0.1",
            label = "1.0.0.1",
            regionHint = "Secondary Anycast"
        ),
        CloudflareEdgeNode(
            id = "cf_edge_104_16",
            ip = "104.16.132.229",
            label = "104.16.132.229",
            regionHint = "Core CDN Edge A"
        ),
        CloudflareEdgeNode(
            id = "cf_edge_104_17",
            ip = "104.17.24.14",
            label = "104.17.24.14",
            regionHint = "Core CDN Edge B"
        ),
        CloudflareEdgeNode(
            id = "cf_edge_172_64",
            ip = "172.64.155.1",
            label = "172.64.155.1",
            regionHint = "Workers / Edge"
        ),
        CloudflareEdgeNode(
            id = "cf_edge_162_159",
            ip = "162.159.192.1",
            label = "162.159.192.1",
            regionHint = "WARP Anycast"
        ),
        CloudflareEdgeNode(
            id = "cf_edge_188_114",
            ip = "188.114.96.1",
            label = "188.114.96.1",
            regionHint = "EMEA Edge"
        )
    )

    fun isValidEdgeIp(rawIp: String): Boolean {
        val clean = rawIp.trim().trim('[', ']')
        if (clean.isBlank()) return false
        val ipv4 = Regex("""^(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)$""")
        if (ipv4.matches(clean)) return true
        return clean.contains(":") && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }

    fun parseCloudflareTraceColo(body: String): String {
        if (body.isBlank()) return "--"
        val line = body.lineSequence().firstOrNull { it.startsWith("colo=") } ?: return "--"
        return line.substringAfter("colo=").trim().take(6).ifBlank { "--" }
    }

    fun computeNodeStats(
        node: CloudflareEdgeNode,
        samples: List<RttSample>
    ): CloudflareNodeStats {
        if (samples.isEmpty()) return CloudflareNodeStats(node = node)
        val trimmed = samples.takeLast(MAX_HISTORY_SAMPLES)
        val rtts = trimmed.mapNotNull { it.totalRttMs ?: it.tcpRttMs }
        val successCount = trimmed.count { it.success }
        val lossPercent = (((trimmed.size - successCount) * 100.0) / trimmed.size).toInt().coerceIn(0, 100)

        var jitterSum = 0L
        var jitterPairs = 0
        for (i in 1 until rtts.size) {
            jitterSum += abs(rtts[i] - rtts[i - 1])
            jitterPairs += 1
        }
        val jitter = if (jitterPairs > 0) jitterSum / jitterPairs else 0L
        val latestColo = trimmed.lastOrNull { it.coloCode != "--" }?.coloCode ?: "--"

        return CloudflareNodeStats(
            node = node,
            latestRttMs = trimmed.lastOrNull()?.let { it.totalRttMs ?: it.tcpRttMs },
            minRttMs = rtts.minOrNull(),
            avgRttMs = if (rtts.isEmpty()) null else rtts.sum() / rtts.size,
            maxRttMs = rtts.maxOrNull(),
            jitterMs = if (rtts.isEmpty()) null else jitter,
            packetLossPercent = lossPercent,
            coloCode = latestColo,
            samples = trimmed
        )
    }

    suspend fun measureNodesSweep(
        nodes: List<CloudflareEdgeNode>,
        baseClient: OkHttpClient,
        sampleIndex: Int,
        timeoutMs: Int = 2500
    ): List<RttSample> = coroutineScope {
        nodes.map { node ->
            async(Dispatchers.IO) {
                measureNodeRtt(
                    node = node,
                    baseClient = baseClient,
                    sampleIndex = sampleIndex,
                    timeoutMs = timeoutMs
                )
            }
        }.awaitAll()
    }

    suspend fun measureNodeRtt(
        node: CloudflareEdgeNode,
        baseClient: OkHttpClient,
        sampleIndex: Int,
        timeoutMs: Int = 2500
    ): RttSample = withContext(Dispatchers.IO) {
        val cleanIp = node.ip.trim().trim('[', ']')
        val now = System.currentTimeMillis()
        var tcpRttMs: Long? = null

        val socket = Socket()
        try {
            val addr = InetAddress.getByName(cleanIp)
            val startTcp = System.nanoTime()
            socket.connect(InetSocketAddress(addr, node.port), timeoutMs)
            tcpRttMs = ((System.nanoTime() - startTcp) / 1_000_000L).coerceAtLeast(1L)
            try { socket.close() } catch (_: Exception) {}
        } catch (ex: Exception) {
            try { socket.close() } catch (_: Exception) {}
            return@withContext RttSample(
                sampleIndex = sampleIndex,
                nodeId = node.id,
                ip = cleanIp,
                label = node.label,
                tcpRttMs = null,
                totalRttMs = null,
                coloCode = "--",
                timestampMs = now,
                success = false,
                error = ex.localizedMessage ?: "Timeout"
            )
        }

        // Measure pinned HTTPS edge trace RTT + extract Cloudflare PoP colo IATA code
        var totalRttMs: Long? = tcpRttMs
        var coloCode = "--"
        try {
            val pinnedDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName(cleanIp))
            }
            val pinnedClient = baseClient.newBuilder()
                .dns(pinnedDns)
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build()
            val reqStart = System.nanoTime()
            val req = Request.Builder()
                .url("https://speed.cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", "ipfounder-psiphon/1.0")
                .build()
            pinnedClient.newCall(req).execute().use { resp ->
                totalRttMs = ((System.nanoTime() - reqStart) / 1_000_000L).coerceAtLeast(1L)
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    coloCode = parseCloudflareTraceColo(body)
                }
            }
        } catch (_: Exception) {
            // Keep real TCP connect RTT when TLS/trace endpoint is blocked on restricted network
        }

        RttSample(
            sampleIndex = sampleIndex,
            nodeId = node.id,
            ip = cleanIp,
            label = node.label,
            tcpRttMs = tcpRttMs,
            totalRttMs = totalRttMs ?: tcpRttMs,
            coloCode = coloCode,
            timestampMs = now,
            success = true
        )
    }
}
