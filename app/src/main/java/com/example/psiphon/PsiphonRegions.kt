package com.example.psiphon

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class GeoIpDetails(
    val code: String,
    val name: String,
    val flag: String,
    val city: String = "Unknown",
    val isp: String = "Unknown"
)

object PsiphonRegions {

    private val geoCache = ConcurrentHashMap<String, GeoIpDetails>()

    fun countryCodeToFlag(countryCode: String): String {
        val clean = countryCode.trim().uppercase(Locale.US)
        if (clean.length != 2 || !clean.all { it in 'A'..'Z' }) return "🌐"
        val first = 0x1F1E6 + (clean[0].code - 'A'.code)
        val second = 0x1F1E6 + (clean[1].code - 'A'.code)
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun resolveDisplayName(countryCode: String, locale: Locale = Locale.ENGLISH): String {
        val clean = countryCode.trim().uppercase(Locale.US)
        if (clean.length != 2 || !clean.all { it in 'A'..'Z' }) return "Unknown"
        return try {
            val resolved = Locale("", clean).getDisplayCountry(locale)
            if (resolved.isNullOrBlank()) clean else resolved
        } catch (_: Exception) {
            clean
        }
    }

    fun parseGeoIpJson(rawJson: String): GeoIpDetails? {
        if (rawJson.isBlank()) return null
        return try {
            val obj = JSONObject(rawJson)
            if (obj.has("success") && !obj.optBoolean("success", true)) return null
            val code = (obj.optString("country_code").ifBlank { obj.optString("country") })
                .trim()
                .uppercase(Locale.US)
            if (code.length != 2) return null
            val city = obj.optString("city").trim().ifBlank { "Unknown" }
            val conn = obj.optJSONObject("connection")
            val isp = (conn?.optString("isp").orEmpty()
                .ifBlank { conn?.optString("org").orEmpty() }
                .ifBlank { obj.optString("isp") }
                .ifBlank { obj.optString("org") })
                .trim()
                .ifBlank { "Unknown" }
            GeoIpDetails(
                code = code,
                name = resolveDisplayName(code, Locale.ENGLISH),
                flag = countryCodeToFlag(code),
                city = city,
                isp = isp
            )
        } catch (_: Exception) {
            null
        }
    }

    fun lookupGeoIp(
        ip: String,
        client: OkHttpClient,
        countryHint: String? = null,
        provider: CdnProvider? = null
    ): GeoIpDetails {
        val cleanIp = ip.trim().trim('[', ']')
        geoCache[cleanIp]?.let { return it }

        // 1. Rich lookup via ipwho.is (Country + City + ISP)
        try {
            val req = Request.Builder()
                .url("https://ipwho.is/$cleanIp")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    parseGeoIpJson(body)?.let { details ->
                        geoCache[cleanIp] = details
                        return details
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback via api.country.is
        try {
            val req = Request.Builder()
                .url("https://api.country.is/$cleanIp")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    parseGeoIpJson(body)?.let { details ->
                        geoCache[cleanIp] = details
                        return details
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 3. Deterministic fallback from hint / provider
        val hintCode = countryHint?.trim()?.uppercase(Locale.US)?.takeIf { it.length == 2 && it != "--" }
        val fallbackIsp = provider?.displayName ?: "Anycast Edge"
        return if (hintCode != null) {
            GeoIpDetails(
                code = hintCode,
                name = resolveDisplayName(hintCode),
                flag = countryCodeToFlag(hintCode),
                city = "Unknown",
                isp = fallbackIsp
            )
        } else {
            GeoIpDetails(
                code = "--",
                name = "Unknown",
                flag = "🌐",
                city = "Unknown",
                isp = fallbackIsp
            )
        }
    }
}
