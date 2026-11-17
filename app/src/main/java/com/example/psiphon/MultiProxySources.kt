package com.example.psiphon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

data class LiveProxyCandidate(
    val ip: String,
    val port: Int,
    val countryCode: String,
    val proxyType: String, // "HTTP" or "SOCKS5"
    val provider: CdnProvider
)

/**
 * Fetches & parses the remaining 4 live Proxy Scanner sources alongside FreeProxy.world
 * and ProxyScrape to provide 6 independent real-time proxy feeds:
 * 1. FreeProxy.world (FreeProxyWorldSource)
 * 2. ProxyScrape (ProxyScrapeSource)
 * 3. GeoNode (proxylist.geonode.com API)
 * 4. TheSpeedX (github raw PROXY-List)
 * 5. Monosans (github raw proxy-list)
 * 6. OpenProxyList (api.openproxylist.xyz)
 */
object MultiProxySources {

    fun buildGeoNodeUrl(
        countryCode: String = "",
        includeHttp: Boolean = true,
        includeSocks5: Boolean = true,
        limit: Int = 50
    ): String {
        val protocols = buildList {
            if (includeHttp) add("http")
            if (includeSocks5) add("socks5")
        }.ifEmpty { listOf("http", "socks5") }.joinToString("%2C")
        val safeLimit = limit.coerceIn(10, 100)
        val cleanCountry = countryCode.trim().uppercase(Locale.US)
        val countryQuery = if (cleanCountry.isNotBlank()) "&country=$cleanCountry" else ""
        return "https://proxylist.geonode.com/api/proxy-list?limit=$safeLimit&page=1&sort_by=lastChecked&sort_type=desc&protocols=$protocols$countryQuery"
    }

    suspend fun fetchCandidates(
        provider: CdnProvider,
        client: OkHttpClient,
        countryCode: String = "",
        portFilter: String = "",
        includeIpv4: Boolean = true,
        includeIpv6: Boolean = true,
        includeHttp: Boolean = true,
        includeSocks5: Boolean = true,
        limit: Int = 24
    ): List<LiveProxyCandidate> = withContext(Dispatchers.IO) {
        if (!includeHttp && !includeSocks5) return@withContext emptyList()

        when (provider) {
            CdnProvider.GEONODE -> {
                val url = buildGeoNodeUrl(
                    countryCode = countryCode,
                    includeHttp = includeHttp,
                    includeSocks5 = includeSocks5,
                    limit = (limit * 2).coerceIn(20, 100)
                )
                val body = executeGet(client, url)
                parseGeoNodeJson(
                    jsonRaw = body,
                    countryFilter = countryCode,
                    portFilter = portFilter,
                    includeIpv4 = includeIpv4,
                    includeIpv6 = includeIpv6,
                    includeHttp = includeHttp,
                    includeSocks5 = includeSocks5,
                    limit = limit
                )
            }
            CdnProvider.THESPEEDX -> fetchDualFeed(
                provider = CdnProvider.THESPEEDX,
                client = client,
                httpUrl = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
                socks5Url = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
                countryCode = countryCode,
                portFilter = portFilter,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                includeHttp = includeHttp,
                includeSocks5 = includeSocks5,
                limit = limit
            )
            CdnProvider.MONOSANS -> fetchDualFeed(
                provider = CdnProvider.MONOSANS,
                client = client,
                httpUrl = "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
                socks5Url = "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
                countryCode = countryCode,
                portFilter = portFilter,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                includeHttp = includeHttp,
                includeSocks5 = includeSocks5,
                limit = limit
            )
            CdnProvider.OPENPROXY -> fetchDualFeed(
                provider = CdnProvider.OPENPROXY,
                client = client,
                httpUrl = "https://api.openproxylist.xyz/http.txt",
                socks5Url = "https://api.openproxylist.xyz/socks5.txt",
                countryCode = countryCode,
                portFilter = portFilter,
                includeIpv4 = includeIpv4,
                includeIpv6 = includeIpv6,
                includeHttp = includeHttp,
                includeSocks5 = includeSocks5,
                limit = limit
            )
            else -> emptyList()
        }
    }

    private suspend fun fetchDualFeed(
        provider: CdnProvider,
        client: OkHttpClient,
        httpUrl: String,
        socks5Url: String,
        countryCode: String,
        portFilter: String,
        includeIpv4: Boolean,
        includeIpv6: Boolean,
        includeHttp: Boolean,
        includeSocks5: Boolean,
        limit: Int
    ): List<LiveProxyCandidate> = coroutineScope {
        val httpDeferred = if (includeHttp) {
            async {
                val raw = executeGet(client, httpUrl)
                parsePlainFeed(
                    raw = raw,
                    provider = provider,
                    defaultProxyType = "HTTP",
                    countryHint = countryCode,
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
                val raw = executeGet(client, socks5Url)
                parsePlainFeed(
                    raw = raw,
                    provider = provider,
                    defaultProxyType = "SOCKS5",
                    countryHint = countryCode,
                    portFilter = portFilter,
                    includeIpv4 = includeIpv4,
                    includeIpv6 = includeIpv6,
                    includeHttp = false,
                    includeSocks5 = true,
                    limit = if (includeHttp) (limit - (limit / 2)).coerceAtLeast(1) else limit
                )
            }
        } else null

        val combined = linkedMapOf<String, LiveProxyCandidate>()
        httpDeferred?.await()?.forEach { c ->
            combined["${c.ip}:${c.port}:${c.proxyType}"] = c
        }
        socks5Deferred?.await()?.forEach { c ->
            combined["${c.ip}:${c.port}:${c.proxyType}"] = c
        }
        combined.values.take(limit)
    }

    private fun executeGet(client: OkHttpClient, url: String): String {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun parseGeoNodeJson(
        jsonRaw: String,
        countryFilter: String = "",
        portFilter: String = "",
        includeIpv4: Boolean = true,
        includeIpv6: Boolean = true,
        includeHttp: Boolean = true,
        includeSocks5: Boolean = true,
        limit: Int = 24
    ): List<LiveProxyCandidate> {
        val trimmed = jsonRaw.trim()
        if (!trimmed.startsWith("{")) return emptyList()

        val requiredCountry = countryFilter.trim().uppercase(Locale.US)
        val requiredPort = portFilter.trim().filter(Char::isDigit).toIntOrNull()
        val ipv4Regex = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
        val out = linkedMapOf<String, LiveProxyCandidate>()

        try {
            val root = JSONObject(trimmed)
            val data = root.optJSONArray("data") ?: return emptyList()
            for (i in 0 until data.length()) {
                if (out.size >= limit) break
                val item = data.optJSONObject(i) ?: continue
                val ip = item.optString("ip").trim().trim('[', ']')
                val isIpv4 = ipv4Regex.matches(ip)
                val isIpv6 = !isIpv4 && ip.contains(":") && !ip.contains(" ")
                if (!isIpv4 && !isIpv6) continue
                if (isIpv4 && !includeIpv4) continue
                if (isIpv6 && !includeIpv6) continue

                val port = item.opt("port")?.toString()?.trim()?.toIntOrNull() ?: continue
                if (port !in 1..65535) continue
                if (requiredPort != null && port != requiredPort) continue

                val country = item.optString("country").trim().uppercase(Locale.US).ifBlank { "--" }
                if (requiredCountry.isNotBlank() && country != "--" && country != requiredCountry) continue

                val protocolsArr = item.optJSONArray("protocols")
                val protocolList = buildList {
                    if (protocolsArr != null) {
                        for (p in 0 until protocolsArr.length()) {
                            add(protocolsArr.optString(p).trim().lowercase(Locale.US))
                        }
                    }
                }
                val normalizedProto = when {
                    "socks5" in protocolList && includeSocks5 -> "SOCKS5"
                    ("http" in protocolList || "https" in protocolList) && includeHttp -> "HTTP"
                    else -> continue
                }

                val key = "$ip:$port:$normalizedProto"
                if (!out.containsKey(key)) {
                    out[key] = LiveProxyCandidate(
                        ip = ip,
                        port = port,
                        countryCode = country,
                        proxyType = normalizedProto,
                        provider = CdnProvider.GEONODE
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out.values.toList()
    }

    fun parsePlainFeed(
        raw: String,
        provider: CdnProvider,
        defaultProxyType: String = "HTTP",
        countryHint: String = "",
        portFilter: String = "",
        includeIpv4: Boolean = true,
        includeIpv6: Boolean = true,
        includeHttp: Boolean = true,
        includeSocks5: Boolean = true,
        limit: Int = 24
    ): List<LiveProxyCandidate> {
        if (raw.isBlank()) return emptyList()

        val requiredPort = portFilter.trim().filter(Char::isDigit).toIntOrNull()
        val cleanCountry = countryHint.trim().uppercase(Locale.US).take(2).ifBlank { "--" }
        val ipv4Regex = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
        val bracketV6PortRegex = Regex("""^\[([^\]]+)\]:(\d{1,5})$""")
        val out = linkedMapOf<String, LiveProxyCandidate>()

        raw.lineSequence().forEach { line ->
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

            val normalizedProto = when (lineProto.trim().lowercase(Locale.US)) {
                "socks5" -> "SOCKS5"
                "http", "https" -> "HTTP"
                else -> return@forEach
            }
            if (normalizedProto == "HTTP" && !includeHttp) return@forEach
            if (normalizedProto == "SOCKS5" && !includeSocks5) return@forEach

            val ip: String
            val port: Int
            val v6Match = bracketV6PortRegex.matchEntire(endpoint)
            if (v6Match != null) {
                ip = v6Match.groupValues[1]
                port = v6Match.groupValues[2].toIntOrNull() ?: return@forEach
            } else {
                val lastColon = endpoint.lastIndexOf(':')
                if (lastColon <= 0) return@forEach
                ip = endpoint.substring(0, lastColon).trim().trim('[', ']')
                port = endpoint.substring(lastColon + 1).trim().toIntOrNull() ?: return@forEach
            }

            if (port !in 1..65535) return@forEach
            if (requiredPort != null && port != requiredPort) return@forEach

            val isIpv4 = ipv4Regex.matches(ip)
            val isIpv6 = !isIpv4 && ip.contains(":") && !ip.contains(" ")
            if (!isIpv4 && !isIpv6) return@forEach
            if (isIpv4 && !includeIpv4) return@forEach
            if (isIpv6 && !includeIpv6) return@forEach

            val key = "$ip:$port:$normalizedProto"
            if (!out.containsKey(key)) {
                out[key] = LiveProxyCandidate(
                    ip = ip,
                    port = port,
                    countryCode = cleanCountry,
                    proxyType = normalizedProto,
                    provider = provider
                )
            }
        }
        return out.values.toList()
    }
}
