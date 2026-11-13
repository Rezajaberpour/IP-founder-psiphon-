package com.example.psiphon

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Isolated Probe Service mirroring PsiphonProbes.kt separation so runtime probing
 * remains decoupled from the UI lifecycle.
 */
class PsiphonProbeService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): PsiphonProbeService = this@PsiphonProbeService
    }

    private val binder = LocalBinder()
    val prober = PsiphonProbes()

    override fun onBind(intent: Intent?): IBinder = binder
}

class PsiphonProbes {

    val sharedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    suspend fun probeCandidate(
        ip: String,
        targetPort: Int,
        localSocksPort: Int,
        provider: CdnProvider,
        method: PsiphonMethod,
        sniHost: String,
        timeoutMs: Int,
        verifyTls: Boolean,
        verifyHttpsTunnel: Boolean,
        countryHint: String? = null,
        proxyProtocol: String = "HTTP"
    ): ProbeTelemetry = withContext(Dispatchers.IO) {
        val cleanIp = ip.trim().trim('[', ']')
        val ipVersion = if (cleanIp.contains(":")) "IPv6" else "IPv4"
        val effectiveSni = sniHost.trim().ifBlank { provider.defaultSni }
        val normalizedProtocol = if (proxyProtocol.equals("SOCKS5", ignoreCase = true)) "SOCKS5" else "HTTP"

        // Step 1: Real dual-stack TCP connect
        val socket = Socket()
        val tcpStartNs = System.nanoTime()
        val tcpPingMs: Long
        var tlsVersion = if (normalizedProtocol == "SOCKS5") "SOCKS5" else "TCP"
        var certIssuer = "None"
        var certValid = false

        try {
            val addr = InetAddress.getByName(cleanIp)
            socket.connect(InetSocketAddress(addr, targetPort), timeoutMs)
            tcpPingMs = ((System.nanoTime() - tcpStartNs) / 1_000_000L).coerceAtLeast(1L)

            if (normalizedProtocol == "SOCKS5") {
                try {
                    socket.soTimeout = timeoutMs
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    // SOCKS5 greeting: VER=5, NMETHODS=1, METHOD=0x00 (No Auth)
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val reply = ByteArray(2)
                    val read = inp.read(reply)
                    socket.close()
                    if (read < 2 || reply[0] != 0x05.toByte()) {
                        return@withContext ProbeTelemetry(
                            ip = cleanIp,
                            ipVersion = ipVersion,
                            targetPort = targetPort,
                            localSocksPort = localSocksPort,
                            proxyProtocol = "SOCKS5",
                            provider = provider,
                            method = method,
                            tcpPingMs = tcpPingMs,
                            httpsPingMs = null,
                            tlsVersion = "SOCKS5-Failed",
                            certIssuer = "InvalidGreeting",
                            certValid = false,
                            httpsStatusCode = null,
                            countryCode = "--",
                            countryName = "Unknown",
                            countryFlag = "🌐",
                            isClean = false,
                            failureReason = "Not SOCKS5"
                        )
                    }
                    if (reply[1] == 0xFF.toByte() || reply[1] != 0x00.toByte()) {
                        return@withContext ProbeTelemetry(
                            ip = cleanIp,
                            ipVersion = ipVersion,
                            targetPort = targetPort,
                            localSocksPort = localSocksPort,
                            proxyProtocol = "SOCKS5",
                            provider = provider,
                            method = method,
                            tcpPingMs = tcpPingMs,
                            httpsPingMs = null,
                            tlsVersion = "SOCKS5-Auth",
                            certIssuer = "AuthRequired",
                            certValid = false,
                            httpsStatusCode = null,
                            countryCode = "--",
                            countryName = "Unknown",
                            countryFlag = "🌐",
                            isClean = false,
                            failureReason = "SOCKS5 auth required"
                        )
                    }
                    tlsVersion = "SOCKS5"
                    certValid = true
                } catch (socksEx: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    return@withContext ProbeTelemetry(
                        ip = cleanIp,
                        ipVersion = ipVersion,
                        targetPort = targetPort,
                        localSocksPort = localSocksPort,
                        proxyProtocol = "SOCKS5",
                        provider = provider,
                        method = method,
                        tcpPingMs = tcpPingMs,
                        httpsPingMs = null,
                        tlsVersion = "SOCKS5-Failed",
                        certIssuer = socksEx.javaClass.simpleName,
                        certValid = false,
                        httpsStatusCode = null,
                        countryCode = "--",
                        countryName = "Unknown",
                        countryFlag = "🌐",
                        isClean = false,
                        failureReason = "SOCKS5: ${socksEx.localizedMessage ?: socksEx.javaClass.simpleName}"
                    )
                }
            } else {
                // Step 2: Real TLS handshake & X.509 certificate verification (HTTP/HTTPS endpoints)
                val shouldAttemptTls = verifyTls && (targetPort == 443 || targetPort == 8443 || targetPort == 2053 || targetPort == 2083)
                if (shouldAttemptTls) {
                    try {
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        val sslSocket = factory.createSocket(socket, effectiveSni, targetPort, true) as SSLSocket
                        sslSocket.soTimeout = timeoutMs
                        val params: SSLParameters = sslSocket.sslParameters
                        if (effectiveSni.isNotBlank() && !isIpLiteral(effectiveSni)) {
                            params.serverNames = listOf(SNIHostName(effectiveSni))
                        }
                        sslSocket.sslParameters = params
                        sslSocket.startHandshake()

                        tlsVersion = sslSocket.session.protocol ?: "TLS"
                        val certs = sslSocket.session.peerCertificates
                        val x509 = certs.firstOrNull() as? X509Certificate
                        if (x509 != null) {
                            x509.checkValidity()
                            certIssuer = extractOrgOrCn(x509.issuerX500Principal.name)
                            certValid = true
                        }
                        sslSocket.close()
                    } catch (tlsEx: Exception) {
                        try {
                            socket.close()
                        } catch (_: Exception) {
                        }
                        return@withContext ProbeTelemetry(
                            ip = cleanIp,
                            ipVersion = ipVersion,
                            targetPort = targetPort,
                            localSocksPort = localSocksPort,
                            proxyProtocol = "HTTP",
                            provider = provider,
                            method = method,
                            tcpPingMs = tcpPingMs,
                            httpsPingMs = null,
                            tlsVersion = "TLS-Failed",
                            certIssuer = tlsEx.javaClass.simpleName,
                            certValid = false,
                            httpsStatusCode = null,
                            countryCode = "--",
                            countryName = "Unknown",
                            countryFlag = "🌐",
                            isClean = false,
                            failureReason = "TLS: ${tlsEx.localizedMessage ?: tlsEx.javaClass.simpleName}"
                        )
                    }
                } else {
                    socket.close()
                }
            }
        } catch (_: SocketTimeoutException) {
            try { socket.close() } catch (_: Exception) {}
            return@withContext ProbeTelemetry(
                ip = cleanIp,
                ipVersion = ipVersion,
                targetPort = targetPort,
                localSocksPort = localSocksPort,
                proxyProtocol = normalizedProtocol,
                provider = provider,
                method = method,
                tcpPingMs = null,
                httpsPingMs = null,
                tlsVersion = "",
                certIssuer = "",
                certValid = false,
                httpsStatusCode = null,
                countryCode = "--",
                countryName = "Unknown",
                countryFlag = "🌐",
                isClean = false,
                failureReason = "Timeout (${timeoutMs}ms)"
            )
        } catch (ex: Exception) {
            try { socket.close() } catch (_: Exception) {}
            return@withContext ProbeTelemetry(
                ip = cleanIp,
                ipVersion = ipVersion,
                targetPort = targetPort,
                localSocksPort = localSocksPort,
                proxyProtocol = normalizedProtocol,
                provider = provider,
                method = method,
                tcpPingMs = null,
                httpsPingMs = null,
                tlsVersion = "",
                certIssuer = "",
                certValid = false,
                httpsStatusCode = null,
                countryCode = "--",
                countryName = "Unknown",
                countryFlag = "🌐",
                isClean = false,
                failureReason = ex.localizedMessage ?: "Unreachable"
            )
        }

        // Step 3: Pinned-IP HTTP/HTTPS verification (HTTP protocol only)
        var httpsPingMs: Long? = null
        var httpsStatusCode: Int? = null
        var httpsError: String? = null
        val supportsHttpProbe = normalizedProtocol == "HTTP" &&
            targetPort in setOf(443, 8443, 2053, 2083, 80, 8080, 8880)

        if (verifyHttpsTunnel && supportsHttpProbe) {
            val isPlainHttp = targetPort in setOf(80, 8080, 8880)
            val scheme = if (isPlainHttp) "http" else "https"
            val pinnedDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return listOf(InetAddress.getByName(cleanIp))
                }
            }
            val pinnedClient = sharedClient.newBuilder()
                .dns(pinnedDns)
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build()

            val probeUrl = "$scheme://$effectiveSni:$targetPort/"
            try {
                val reqStartNs = System.nanoTime()
                val req = Request.Builder()
                    .url(probeUrl)
                    .head()
                    .header("User-Agent", "ipfounder-psiphon/1.0")
                    .build()
                pinnedClient.newCall(req).execute().use { resp ->
                    httpsPingMs = ((System.nanoTime() - reqStartNs) / 1_000_000L).coerceAtLeast(1L)
                    httpsStatusCode = resp.code
                }
            } catch (ex: Exception) {
                httpsError = ex.localizedMessage ?: ex.javaClass.simpleName
            }
        }

        // Step 4: GeoIP Country lookup (with countryHint fallback for FreeProxy.world)
        val lookedUp = PsiphonRegions.lookupCountry(cleanIp, sharedClient)
        val country = if (lookedUp.code == "--" && !countryHint.isNullOrBlank() && countryHint.length == 2) {
            val code = countryHint.uppercase()
            GeoIpCountry(
                code = code,
                name = PsiphonRegions.resolveDisplayName(code),
                flag = PsiphonRegions.countryCodeToFlag(code)
            )
        } else {
            lookedUp
        }

        val passed = if (verifyHttpsTunnel && supportsHttpProbe && !provider.isProxyListSource) {
            httpsStatusCode != null
        } else {
            true
        }

        ProbeTelemetry(
            ip = cleanIp,
            ipVersion = ipVersion,
            targetPort = targetPort,
            localSocksPort = localSocksPort,
            proxyProtocol = normalizedProtocol,
            provider = provider,
            method = method,
            tcpPingMs = tcpPingMs,
            httpsPingMs = httpsPingMs ?: tcpPingMs,
            tlsVersion = tlsVersion,
            certIssuer = certIssuer,
            certValid = certValid,
            httpsStatusCode = httpsStatusCode,
            countryCode = country.code,
            countryName = country.name,
            countryFlag = country.flag,
            isClean = passed,
            failureReason = if (!passed) httpsError else null
        )
    }

    /**
     * Parses imported Psiphon server_entries.txt lines (plain host:port or hex-encoded entries)
     * into candidate IP/host targets.
     */
    fun parseServerEntries(rawContent: String): List<String> {
        val candidates = linkedSetOf<String>()
        val ipRegex = Regex("""(\d{1,3}(?:\.\d{1,3}){3})""")
        rawContent.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            // Extract IPv4 literals directly if present
            ipRegex.findAll(trimmed).forEach { match ->
                candidates.add(match.value)
            }
            // Attempt hex-decode of Psiphon server_entry lines if purely hex
            if (trimmed.length > 32 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                try {
                    val decoded = trimmed.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                        .toString(Charsets.UTF_8)
                    ipRegex.findAll(decoded).forEach { match ->
                        candidates.add(match.value)
                    }
                } catch (_: Exception) {
                }
            }
        }
        return candidates.toList()
    }

    private fun extractOrgOrCn(dn: String): String {
        val parts = dn.split(",").map { it.trim() }
        val org = parts.firstOrNull { it.startsWith("O=", ignoreCase = true) }?.substringAfter("=")
        val cn = parts.firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substringAfter("=")
        return (org ?: cn ?: "Verified").take(26)
    }

    private fun isIpLiteral(value: String): Boolean {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4.matches(value) || value.contains(":")
    }
}
