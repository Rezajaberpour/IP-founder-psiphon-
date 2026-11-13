package com.example.psiphon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

data class ProxyScrapeCandidate(
    val ip: String,
    val port: Int,
    val countryCode: String,
    val proxyType: String // "HTTP" or "SOCKS5"
)

object ProxyScrapeSource {

    fun buildV2Url(
        protocol: String,
        countryCode: String = "",
        timeoutMs: Int = 10000
    ): String {
        val cleanProto = when (protocol.trim().lowercase(Locale.US)) {
            "socks5" -> "socks5"
            else -> "http"
        }
        val cleanCountry = countryCode.trim().uppercase(Locale.US).ifBlank { "all" }
        val safeTimeout = timeoutMs.coerceIn(1000, 15000)
        return "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=$cleanProto&timeout=$safeTimeout&country=$cleanCountry&ssl=all&anonymity=all"
    }

    suspend fun fetchCandidates(
        client: OkHttpClient,
        countryCode: String = "US",
        portFilter: String = "",
        includeIpv4: Boolean = true,
        includeIpv6: Boolean = true,
        includeHttp: Boolean = true,
        includeSocks5: Boolean = true,
        limit: Int = 24
    ): List<ProxyScrapeCandidate> = withContext(Dispatchers.IO) {
        if (!includeHttp && !includeSocks5) return@withContext emptyList()

        coroutineScope {
            val httpDeferred = if (includeHttp) {
                async {
                    fetchByProtocol(
                        client = client,
                        protocol = "http",
                        defaultNormalizedType = "HTTP",
                        countryCode = countryCode,
                        portFilter = portFilter,
                        includeIpv4 = includeIpv4,
                        includeIpv6 = includeIpv6,
                        includeHttp = true,
                        includeSocks5 = false,
                        limit = if (includeSocks5) (limit / 2).coerceAtLeast(1) else limit
                    )
                }
            } else null

            val socks5Deferred = if (includeSocks5) {
                async {
                    fetchByProtocol(
                        client = client,
                        protocol = "socks5",
                        defaultNormalizedType = "SOCKS5",
                        countryCode = countryCode,
                        portFilter = portFilter,
                        includeIpv4 = includeIpv4,
                        includeIpv6 = includeIpv6,
                        includeHttp = false,
                        includeSocks5 = true,
                        limit = if (includeHttp) (limit - (limit / 2)).coerceAtLeast(1) else limit
                    )
                }
            } else null

            val combined = linkedMapOf<String, ProxyScrapeCandidate>()
            httpDeferred?.await()?.forEach { candidate ->
                combined["${candidate.ip}:${candidate.port}:${candidate.proxyType}"] = candidate
            }
            socks5Deferred?.await()?.forEach { candidate ->
                combined["${candidate.ip}:${candidate.port}:${candidate.proxyType}"] = candidate
            }
            combined.values.take(limit)
        }
    }

    private fun fetchByProtocol(
        client: OkHttpClient,
        protocol: String,
        defaultNormalizedType: String,
        countryCode: String,
        portFilter: String,
        includeIpv4: Boolean,
        includeIpv6: Boolean,
        includeHttp: Boolean,
        includeSocks5: Boolean,
        limit: Int
    ): List<ProxyScrapeCandidate> {
        val url = buildV2Url(protocol = protocol, countryCode = countryCode)
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/plain,application/json,*/*")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                parseResponse(
                    raw = body,
                    defaultCountry = countryCode,
                    defaultProxyType = defaultNormalizedType,
                    portFilter = portFilter,
                    includeIpv4 = includeIpv4,
                    includeIpv6 = includeIpv6,
                    includeHttp = includeHttp,
                    includeSocks5 = includeSocks5,
                    limit = limit
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseResponse(
        raw: String,
        defaultCountry: String = "",
        defaultProxyType: String = "HTTP",
        portFilter: String = "",
        includeIpv4: Boolean = true,
        includeIpv6: Boolean = true,
        includeHttp: Boolean = true,
        includeSocks5: Boolean = true,
        limit: Int = 24
    ): List<ProxyScrapeCandidate> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        val requiredPort = portFilter.trim().filter(Char::isDigit).toIntOrNull()
        val fallbackCountry = defaultCountry.trim().uppercase(Locale.US).ifBlank { "--" }
        val ipv4Regex = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
        val out = linkedMapOf<String, ProxyScrapeCandidate>()

        fun tryAdd(
            rawIp: String,
            port: Int,
            country: String,
            proto: String
        ) {
            if (out.size >= limit) return
            val ip = rawIp.trim().trim('[', ']')
            val isIpv4 = ipv4Regex.matches(ip)
            val isIpv6 = !isIpv4 && ip.contains(":") && !ip.contains(" ")
            if (!isIpv4 && !isIpv6) return
            if (isIpv4 && !includeIpv4) return
            if (isIpv6 && !includeIpv6) return
            if (port !in 1..65535) return
            if (requiredPort != null && port != requiredPort) return

            val normalizedProto = when (proto.trim().lowercase(Locale.US)) {
                "socks5" -> "SOCKS5"
                "http", "https" -> "HTTP"
                else -> return
            }
            if (normalizedProto == "HTTP" && !includeHttp) return
            if (normalizedProto == "SOCKS5" && !includeSocks5) return

            val cleanCountry = country.trim().uppercase(Locale.US).take(2).ifBlank { fallbackCountry }
            val key = "$ip:$port:$normalizedProto"
            if (!out.containsKey(key)) {
                out[key] = ProxyScrapeCandidate(
                    ip = ip,
                    port = port,
                    countryCode = cleanCountry,
                    proxyType = normalizedProto
                )
            }
        }

        // Handle optional JSON v4 payload
        if (trimmed.startsWith("{")) {
            try {
                val root = JSONObject(trimmed)
                val proxies = root.optJSONArray("proxies")
                if (proxies != null) {
                    for (i in 0 until proxies.length()) {
                        if (out.size >= limit) break
                        val obj = proxies.optJSONObject(i) ?: continue
                        val ip = obj.optString("ip").trim()
                        val port = obj.optInt("port", -1)
                        val proto = obj.optString("protocol", defaultProxyType)
                        val country = obj.optJSONObject("ip_data")?.optString("countryCode").orEmpty()
                        tryAdd(ip, port, country, proto)
                    }
                    return out.values.toList()
                }
            } catch (_: Exception) {
            }
        }

        // Standard plain text v2 lines: ip:port, [ipv6]:port, or protocol://ip:port
        val bracketV6PortRegex = Regex("""^\[([^\]]+)\]:(\d{1,5})$""")
        trimmed.lines().forEach { line ->
            if (out.size >= limit) return@forEach
            val entry = line.trim()
            if (entry.isEmpty() || entry.startsWith("#")) return@forEach

            var lineProto = defaultProxyType
            val endpoint = if (entry.contains("://")) {
                lineProto = entry.substringBefore("://")
                entry.substringAfter("://")
            } else {
                entry
            }

            val v6Match = bracketV6PortRegex.matchEntire(endpoint)
            if (v6Match != null) {
                val ip = v6Match.groupValues[1]
                val port = v6Match.groupValues[2].toIntOrNull() ?: return@forEach
                tryAdd(ip, port, fallbackCountry, lineProto)
            } else {
                val lastColon = endpoint.lastIndexOf(':')
                if (lastColon <= 0) return@forEach
                val ip = endpoint.substring(0, lastColon)
                val port = endpoint.substring(lastColon + 1).toIntOrNull() ?: return@forEach
                tryAdd(ip, port, fallbackCountry, lineProto)
            }
        }

        return out.values.toList()
    }
}
