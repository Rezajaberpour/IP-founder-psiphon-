package com.example.psiphon

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

class PsiphonProbes {

    val sharedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .writeTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .build()

    fun parseServerEntries(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val found = linkedSetOf<String>()
        val ipv4Regex = Regex("""\b(?:25[0-5]|2[0-4]\d|[01]?\d\d?)(?:\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)){3}\b""")
        val hexCandidateRegex = Regex("""\b[0-9a-fA-F]{16,}\b""")

        ipv4Regex.findAll(raw).forEach { found.add(it.value) }

        hexCandidateRegex.findAll(raw).forEach { match ->
            val hex = match.value
            if (hex.length % 2 == 0) {
                try {
                    val decoded = hex.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                        .toString(Charsets.UTF_8)
                    ipv4Regex.findAll(decoded).forEach { found.add(it.value) }
                } catch (_: Exception) {
                }
            }
        }
        return found.toList()
    }

    fun probeCandidate(
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
    ): ProbeTelemetry {
        val cleanIp = ip.trim().trim('[', ']')
        val ipVersion = if (cleanIp.contains(":")) "IPv6" else "IPv4"
        val effectiveTimeout = timeoutMs.coerceIn(800, 10_000)
        val effectiveSni = sniHost.trim().ifBlank { provider.defaultSni }

        // 1. TCP Connect Probe
        val tcpStart = System.nanoTime()
        val tcpOk = try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(InetAddress.getByName(cleanIp), targetPort), effectiveTimeout)
                true
            }
        } catch (_: Exception) {
            false
        }
        val tcpPingMs = if (tcpOk) ((System.nanoTime() - tcpStart) / 1_000_000L).coerceAtLeast(1L) else null

        if (!tcpOk) {
            val geo = PsiphonRegions.lookupGeoIp(cleanIp, sharedClient, countryHint, provider)
            return ProbeTelemetry(
                ip = cleanIp,
                ipVersion = ipVersion,
                targetPort = targetPort,
                localSocksPort = localSocksPort,
                proxyProtocol = proxyProtocol,
                provider = provider,
                method = method,
                tcpPingMs = null,
                httpsPingMs = null,
                tlsVersion = "",
                certIssuer = "",
                certValid = false,
                httpsStatusCode = null,
                countryCode = geo.code,
                countryName = geo.name,
                countryFlag = geo.flag,
                city = geo.city,
                isp = geo.isp,
                isClean = false,
                failureReason = "TCP timeout"
            )
        }

        // 2. Branch for SOCKS5 Proxy Handshake vs TLS/HTTPS CDN Edge Verification
        val isSocks5 = proxyProtocol.equals("SOCKS5", ignoreCase = true)
        var tlsVersion = if (isSocks5) "SOCKS5" else "TCP"
        var certIssuer = if (isSocks5) "SOCKS5 Proxy" else "None"
        var certValid = !verifyTls || isSocks5
        var httpsStatusCode: Int? = null
        var httpsPingMs: Long? = tcpPingMs
        var isClean = true
        var failureReason: String? = null

        if (isSocks5) {
            val socksStart = System.nanoTime()
            val socksResult = verifySocks5Greeting(cleanIp, targetPort, effectiveTimeout)
            httpsPingMs = ((System.nanoTime() - socksStart) / 1_000_000L).coerceAtLeast(1L)
            if (!socksResult.first) {
                isClean = false
                failureReason = socksResult.second ?: "SOCKS5 handshake rejected"
            }
        } else {
            val expectsTls = verifyTls && targetPort !in setOf(80, 8080, 3128)
            if (expectsTls) {
                val tlsInfo = performTlsHandshake(cleanIp, targetPort, effectiveSni, effectiveTimeout)
                if (tlsInfo.success) {
                    tlsVersion = tlsInfo.protocol.ifBlank { "TLSv1.3" }
                    certIssuer = tlsInfo.issuer
                    certValid = tlsInfo.certValid
                    httpsPingMs = tlsInfo.rttMs
                } else {
                    isClean = false
                    certValid = false
                    tlsVersion = "TLS-ERR"
                    failureReason = tlsInfo.error ?: "TLS handshake failed"
                }
            }

            if (isClean && verifyHttpsTunnel) {
                val scheme = if (targetPort in setOf(80, 8080, 3128)) "http" else "https"
                val httpRes = performPinnedHeadRequest(cleanIp, targetPort, effectiveSni, scheme, effectiveTimeout)
                if (httpRes.first != null) {
                    httpsStatusCode = httpRes.first
                    httpsPingMs = httpRes.second
                    if (!expectsTls && scheme == "http") tlsVersion = "HTTP"
                } else {
                    isClean = false
                    failureReason = httpRes.third ?: "Pinned tunnel probe failed"
                }
            }
        }

        val geo = PsiphonRegions.lookupGeoIp(cleanIp, sharedClient, countryHint, provider)
        return ProbeTelemetry(
            ip = cleanIp,
            ipVersion = ipVersion,
            targetPort = targetPort,
            localSocksPort = localSocksPort,
            proxyProtocol = proxyProtocol,
            provider = provider,
            method = method,
            tcpPingMs = tcpPingMs,
            httpsPingMs = httpsPingMs,
            tlsVersion = tlsVersion,
            certIssuer = certIssuer,
            certValid = certValid,
            httpsStatusCode = httpsStatusCode,
            countryCode = geo.code,
            countryName = geo.name,
            countryFlag = geo.flag,
            city = geo.city,
            isp = geo.isp,
            isClean = isClean,
            failureReason = failureReason
        )
    }

    private fun verifySocks5Greeting(ip: String, port: Int, timeoutMs: Int): Pair<Boolean, String?> {
        return try {
            Socket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
                val out = DataOutputStream(sock.getOutputStream())
                val inp = DataInputStream(sock.getInputStream())
                // SOCKS5 version=5, 1 auth method, 0x00 (No Authentication)
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val reply = ByteArray(2)
                inp.readFully(reply)
                if (reply[0] == 0x05.toByte() && reply[1] != 0xFF.toByte()) {
                    true to null
                } else {
                    false to "SOCKS5 auth rejected"
                }
            }
        } catch (e: Exception) {
            false to (e.message?.take(42) ?: "SOCKS5 timeout")
        }
    }

    private data class TlsProbeResult(
        val success: Boolean,
        val protocol: String = "",
        val issuer: String = "",
        val certValid: Boolean = false,
        val rttMs: Long = 0L,
        val error: String? = null
    )

    private fun performTlsHandshake(ip: String, port: Int, sniHost: String, timeoutMs: Int): TlsProbeResult {
        val startNs = System.nanoTime()
        return try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, null)

            Socket().use { rawSocket ->
                rawSocket.soTimeout = timeoutMs
                rawSocket.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
                val sslSocket = sslContext.socketFactory.createSocket(rawSocket, sniHost, port, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs
                if (sniHost.isNotBlank()) {
                    val params: SSLParameters = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    sslSocket.sslParameters = params
                }
                sslSocket.startHandshake()
                val session = sslSocket.session
                val certs = session.peerCertificates
                val firstX509 = certs.firstOrNull() as? X509Certificate
                val validDates = try {
                    firstX509?.checkValidity()
                    firstX509 != null
                } catch (_: Exception) {
                    false
                }
                val issuerCn = firstX509?.issuerX500Principal?.name
                    ?.substringAfter("O=", "")
                    ?.substringBefore(",")
                    ?.ifBlank { firstX509.issuerX500Principal.name.take(28) }
                    ?: "Verified"
                val rtt = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                TlsProbeResult(
                    success = true,
                    protocol = session.protocol ?: "TLSv1.3",
                    issuer = issuerCn,
                    certValid = validDates,
                    rttMs = rtt
                )
            }
        } catch (e: Exception) {
            TlsProbeResult(success = false, error = e.message?.take(48) ?: "TLS error")
        }
    }

    private fun performPinnedHeadRequest(
        ip: String,
        port: Int,
        sniHost: String,
        scheme: String,
        timeoutMs: Int
    ): Triple<Int?, Long?, String?> {
        val targetAddress = InetAddress.getByName(ip)
        val pinnedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(targetAddress)
        }
        val client = sharedClient.newBuilder()
            .dns(pinnedDns)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val url = "$scheme://$sniHost:$port/"
        val req = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "PsiphonCleanScanner/1.0")
            .build()
        val startNs = System.nanoTime()
        return try {
            client.newCall(req).execute().use { resp ->
                val rtt = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                Triple(resp.code, rtt, null)
            }
        } catch (e: Exception) {
            Triple(null, null, e.message?.take(48) ?: "HTTP tunnel timeout")
        }
    }
}

class PsiphonProbeService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): PsiphonProbeService = this@PsiphonProbeService
    }

    private val binder = LocalBinder()
    val prober: PsiphonProbes by lazy { PsiphonProbes() }

    override fun onBind(intent: Intent?): IBinder = binder
}
