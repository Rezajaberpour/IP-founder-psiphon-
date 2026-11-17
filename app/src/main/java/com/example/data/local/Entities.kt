package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clean_ips")
data class CleanIpEntity(
    @PrimaryKey val idKey: String, // "$ip:$targetPort:$proxyProtocol"
    val ip: String,
    val ipVersion: String, // IPv4 / IPv6
    val targetPort: Int,
    val localSocksPort: Int,
    val proxyProtocol: String = "HTTP", // "HTTP" or "SOCKS5"
    val provider: String,
    val method: String,
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
    val aiScore: Int = 0,
    val fuzzyMembership: Float = 0f,
    val nnPrediction: Float = 0f,
    val nashPayoff: Float = 0f,
    val anonymityConfidenceScore: Int = 0,
    val proxyHealthScore: Int = 0,
    val aiVerdict: String = "MODERATE",
    val scannedAt: Long = System.currentTimeMillis()
)
