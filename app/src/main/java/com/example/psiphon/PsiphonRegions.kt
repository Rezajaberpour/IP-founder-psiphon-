package com.example.psiphon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class GeoIpCountry(
    val code: String,
    val name: String,
    val flag: String,
    val city: String = "Unknown",
    val isp: String = "Unknown"
)

object PsiphonRegions {
    private val cache = ConcurrentHashMap<String, GeoIpCountry>()
    private val jsonParser = Json { ignoreUnknownKeys = true }

    val supportedRegions = listOf(
        "US", "DE", "NL", "GB", "CA", "FR", "CH", "SG", "JP",
        "SE", "FI", "NO", "AT", "BE", "ES", "IT", "PL", "AU",
        "IN", "BR", "AE", "TR", "KR", "HK", "TW", "IE"
    )

    fun countryCodeToFlag(countryCode: String): String {
        val clean = countryCode.trim().uppercase(Locale.US)
        if (clean.length != 2 || !clean.all { it in 'A'..'Z' }) return "🌐"
        val first = Character.codePointAt(clean, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(clean, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun resolveDisplayName(countryCode: String, locale: Locale = Locale.ENGLISH): String {
        val clean = countryCode.trim().uppercase(Locale.US)
        if (clean.length != 2) return "Unknown"
        return Locale("", clean).getDisplayCountry(locale).ifBlank { clean }
    }

    fun unknownCountry(
        city: String = "Unknown",
        isp: String = "Unknown"
    ): GeoIpCountry = GeoIpCountry(
        code = "--",
        name = "Unknown",
        flag = "🌐",
        city = city.ifBlank { "Unknown" },
        isp = isp.ifBlank { "Unknown" }
    )

    /**
     * Parses GeoIP JSON payloads from ipwho.is (country_code, city, connection.isp/org)
     * or api.country.is (country) into a normalized GeoIpCountry model.
     */
    fun parseGeoIpJson(body: String, fallbackIsp: String = "Unknown"): GeoIpCountry? {
        if (body.isBlank()) return null
        return try {
            val obj = jsonParser.parseToJsonElement(body).jsonObject
            val successProp = obj["success"]?.jsonPrimitive?.content
            if (successProp == "false") return null

            val rawCode = obj["country_code"]?.jsonPrimitive?.content
                ?: obj["countryCode"]?.jsonPrimitive?.content
                ?: obj["country"]?.jsonPrimitive?.content
                ?: return null
            val code = rawCode.trim().uppercase(Locale.US)
            if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null

            val rawCity = obj["city"]?.jsonPrimitive?.content?.trim().orEmpty()
            val connObj = obj["connection"]?.jsonObject
            val rawIsp = connObj?.get("isp")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: connObj?.get("org")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                ?: obj["isp"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                ?: obj["org"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                ?: fallbackIsp

            GeoIpCountry(
                code = code,
                name = resolveDisplayName(code),
                flag = countryCodeToFlag(code),
                city = rawCity.ifBlank { "Unknown" },
                isp = rawIsp.ifBlank { "Unknown" }
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun lookupCountry(
        ip: String,
        client: OkHttpClient,
        fallbackIsp: String = "Unknown"
    ): GeoIpCountry = withContext(Dispatchers.IO) {
        val cleanIp = ip.trim().trim('[', ']')
        cache[cleanIp]?.let { return@withContext it }

        // Primary rich GeoIP lookup (Country + City + ISP) via ipwho.is
        try {
            val richReq = Request.Builder()
                .url("https://ipwho.is/$cleanIp")
                .header("User-Agent", "ipfounder-psiphon/1.0")
                .build()
            client.newCall(richReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseGeoIpJson(body, fallbackIsp)
                    if (parsed != null) {
                        cache[cleanIp] = parsed
                        return@withContext parsed
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Secondary Country fallback via api.country.is
        try {
            val req = Request.Builder()
                .url("https://api.country.is/$cleanIp")
                .header("User-Agent", "ipfounder-psiphon/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseGeoIpJson(body, fallbackIsp)
                    if (parsed != null) {
                        cache[cleanIp] = parsed
                        return@withContext parsed
                    }
                }
            }
        } catch (_: Exception) {
        }

        unknownCountry(isp = fallbackIsp)
    }
}
