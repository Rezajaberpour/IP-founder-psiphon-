package com.example.psiphon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

data class FreeProxyCandidate(
    val ip: String,
    val port: Int,
    val countryCode: String,
    val proxyType: String // "HTTP" or "SOCKS5"
)

object FreeProxyWorldSource {

    val supportedCountries = listOf(
        "US", "", "DE", "GB", "NL", "CA", "SG", "FR"
    )

    val supportedPorts = listOf(
        "", "80", "443", "1080", "8080", "3128", "8443"
    )

    fun buildUrl(
        countryCode: String = "US",
        port: String = "",
        proxyType: String = ""
    ): String {
        val cleanCountry = countryCode.trim().uppercase(Locale.US)
        val cleanPort = port.trim().filter(Char::isDigit)
        val cleanType = proxyType.trim().lowercase(Locale.US)
        return "https://www.freeproxy.world/?type=$cleanType&anonymity=&country=$cleanCountry&speed=&port=$cleanPort"
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
    ): List<FreeProxyCandidate> = withContext(Dispatchers.IO) {
        if (!includeHttp && !includeSocks5) return@withContext emptyList()

        coroutineScope {
            val httpDeferred = if (includeHttp) {
                async {
                    fetchByType(
                        client = client,
                        countryCode = countryCode,
                        portFilter = portFilter,
                        typeParam = "http",
                        defaultNormalizedType = "HTTP",
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
                    fetchByType(
                        client = client,
                        countryCode = countryCode,
                        portFilter = portFilter,
                        typeParam = "socks5",
                        defaultNormalizedType = "SOCKS5",
                        includeIpv4 = includeIpv4,
                        includeIpv6 = includeIpv6,
                        includeHttp = false,
                        includeSocks5 = true,
                        limit = if (includeHttp) (limit - (limit / 2)).coerceAtLeast(1) else limit
                    )
                }
            } else null

            val combined = linkedMapOf<String, FreeProxyCandidate>()
            httpDeferred?.await()?.forEach { candidate ->
                combined["${candidate.ip}:${candidate.port}:${candidate.proxyType}"] = candidate
            }
            socks5Deferred?.await()?.forEach { candidate ->
                combined["${candidate.ip}:${candidate.port}:${candidate.proxyType}"] = candidate
            }
            combined.values.take(limit)
        }
    }

    private fun fetchByType(
        client: OkHttpClient,
        countryCode: String,
        portFilter: String,
        typeParam: String,
        defaultNormalizedType: String,
        includeIpv4: Boolean,
        includeIpv6: Boolean,
        includeHttp: Boolean,
        includeSocks5: Boolean,
        limit: Int
    ): List<FreeProxyCandidate> {
        val url = buildUrl(countryCode = countryCode, port = portFilter, proxyType = typeParam)
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val html = response.body?.string().orEmpty()
                parseHtml(
                    html = html,
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

    fun parseHtml(
        html: String,
        defaultCountry: String = "US",
        defaultProxyType: String = "HTTP",
        portFilter: String = "",
        includeIpv4: Boolean = true,
        includeIpv6: Boolean = true,
        includeHttp: Boolean = true,
        includeSocks5: Boolean = true,
        limit: Int = 24
    ): List<FreeProxyCandidate> {
        if (html.isBlank()) return emptyList()

        val requiredPort = portFilter.trim().filter(Char::isDigit).toIntOrNull()
        val rowRegex = Regex("<tr>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tdRegex = Regex("<td[^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tagStripRegex = Regex("<[^>]+>")
        val ipv4Regex = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
        val countryHrefRegex = Regex("""/\?country=([A-Za-z]{2})""")
        val typeHrefRegex = Regex("""/\?type=([A-Za-z0-9]+)""")

        val out = linkedMapOf<String, FreeProxyCandidate>()
        for (rowMatch in rowRegex.findAll(html)) {
            if (out.size >= limit) break
            val rowHtml = rowMatch.groupValues[1]
            val cells = tdRegex.findAll(rowHtml).map { it.groupValues[1] }.toList()
            if (cells.size < 2) continue

            val ipText = cells[0].replace(tagStripRegex, "").trim()
            val isIpv4 = ipv4Regex.matches(ipText)
            val isIpv6 = !isIpv4 && ipText.contains(":") && !ipText.contains(" ")
            if (!isIpv4 && !isIpv6) continue
            if (isIpv4 && !includeIpv4) continue
            if (isIpv6 && !includeIpv6) continue

            val portText = cells[1].replace(tagStripRegex, "").trim().filter(Char::isDigit)
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
            if (requiredPort != null && port != requiredPort) continue

            val country = countryHrefRegex.find(rowHtml)?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
                ?: defaultCountry.trim().uppercase(Locale.US).ifBlank { "--" }
            val rawType = typeHrefRegex.find(rowHtml)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
            val normalizedType = when (rawType) {
                "socks5" -> "SOCKS5"
                "http", "https" -> "HTTP"
                null -> defaultProxyType.uppercase(Locale.US)
                else -> continue // skip socks4 or unknown protocols
            }

            if (normalizedType == "HTTP" && !includeHttp) continue
            if (normalizedType == "SOCKS5" && !includeSocks5) continue

            val key = "$ipText:$port:$normalizedType"
            if (!out.containsKey(key)) {
                out[key] = FreeProxyCandidate(
                    ip = ipText,
                    port = port,
                    countryCode = country,
                    proxyType = normalizedType
                )
            }
        }
        return out.values.toList()
    }
}
