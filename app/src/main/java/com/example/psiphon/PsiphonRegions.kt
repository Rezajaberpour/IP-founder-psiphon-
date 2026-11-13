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
    val flag: String
)

object PsiphonRegions {
    private val cache = ConcurrentHashMap<String, GeoIpCountry>()

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

    fun unknownCountry(): GeoIpCountry = GeoIpCountry(
        code = "--",
        name = "Unknown",
        flag = "🌐"
    )

    suspend fun lookupCountry(ip: String, client: OkHttpClient): GeoIpCountry =
        withContext(Dispatchers.IO) {
            val cleanIp = ip.trim().trim('[', ']')
            cache[cleanIp]?.let { return@withContext it }

            try {
                val request = Request.Builder()
                    .url("https://api.country.is/$cleanIp")
                    .header("User-Agent", "ipfounder-psiphon/1.0")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext unknownCountry()
                    val body = resp.body?.string().orEmpty()
                    val obj = Json.parseToJsonElement(body).jsonObject
                    val code = obj["country"]?.jsonPrimitive?.content?.uppercase(Locale.US)
                        ?: return@withContext unknownCountry()
                    if (code.length != 2) return@withContext unknownCountry()

                    val resolved = GeoIpCountry(
                        code = code,
                        name = resolveDisplayName(code),
                        flag = countryCodeToFlag(code)
                    )
                    cache[cleanIp] = resolved
                    resolved
                }
            } catch (_: Exception) {
                unknownCountry()
            }
        }
}
