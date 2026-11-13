package com.example

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.example.psiphon.CdnProvider
import com.example.psiphon.FreeProxyWorldSource
import com.example.psiphon.ProxyScrapeSource
import com.example.psiphon.PsiphonCleanIps
import com.example.psiphon.PsiphonProbes
import com.example.psiphon.PsiphonRegions
import kotlinx.coroutines.runBlocking
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConfigAndPacketTest {

    @Test
    fun countryCodeToFlag_producesExpectedEmojiAndName() {
        assertEquals("🇺🇸", PsiphonRegions.countryCodeToFlag("US"))
        assertEquals("🇩🇪", PsiphonRegions.countryCodeToFlag("de"))
        assertEquals("🌐", PsiphonRegions.countryCodeToFlag(""))
        assertEquals("Germany", PsiphonRegions.resolveDisplayName("DE"))
        val persianGermany = PsiphonRegions.resolveDisplayName("DE", Locale.forLanguageTag("fa"))
        assertTrue(persianGermany.isNotBlank() && persianGermany != "DE")
    }

    @Test
    fun persianResources_resolveCorrectly() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val faConfig = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("fa"))
        }
        val faContext = baseContext.createConfigurationContext(faConfig)
        assertEquals("ipfounder psiphon", faContext.getString(R.string.app_name))
        assertEquals("اسکنر", faContext.getString(R.string.nav_scan))
        assertEquals("تنظیمات", faContext.getString(R.string.nav_settings))
        assertEquals("شروع اسکن IP تمیز", faContext.getString(R.string.btn_start_scan))
    }

    @Test
    fun freeProxyWorld_parsesTableRowsAndBuildsUrl() {
        assertEquals(
            "https://www.freeproxy.world/?type=&anonymity=&country=US&speed=&port=",
            FreeProxyWorldSource.buildUrl(countryCode = "US", port = "")
        )
        assertEquals(
            "https://www.freeproxy.world/?type=socks5&anonymity=&country=US&speed=&port=",
            FreeProxyWorldSource.buildUrl(countryCode = "US", port = "", proxyType = "socks5")
        )
        assertEquals(
            "https://www.freeproxy.world/?type=socks5&anonymity=&country=US&speed=&port=1080",
            FreeProxyWorldSource.buildUrl(countryCode = "US", port = "1080", proxyType = "socks5")
        )
        val sampleHtml = """
            <table><tbody>
            <tr>
                <td style="font-weight: 500;">104.16.1.212</td>
                <td><a href="/?port=80">80</a></td>
                <td><a href="/?country=US" title="United States of America"><span class="flag flag-us"></span></a></td>
                <td><a href="/?type=http">http</a></td>
            </tr>
            <tr>
                <td style="font-weight: 500;">172.67.183.122</td>
                <td><a href="/?port=1080">1080</a></td>
                <td><a href="/?country=DE" title="Germany"></a></td>
                <td><a href="/?type=socks5">socks5</a></td>
            </tr>
            </tbody></table>
        """.trimIndent()
        val parsedAll = FreeProxyWorldSource.parseHtml(sampleHtml)
        assertEquals(2, parsedAll.size)
        assertEquals("104.16.1.212", parsedAll[0].ip)
        assertEquals(80, parsedAll[0].port)
        assertEquals("US", parsedAll[0].countryCode)
        assertEquals("HTTP", parsedAll[0].proxyType)
        assertEquals("172.67.183.122", parsedAll[1].ip)
        assertEquals(1080, parsedAll[1].port)
        assertEquals("DE", parsedAll[1].countryCode)
        assertEquals("SOCKS5", parsedAll[1].proxyType)

        val socksOnly = FreeProxyWorldSource.parseHtml(sampleHtml, includeHttp = false, includeSocks5 = true)
        assertEquals(1, socksOnly.size)
        assertEquals("SOCKS5", socksOnly[0].proxyType)
        assertEquals(1080, socksOnly[0].port)

        val httpOnly = FreeProxyWorldSource.parseHtml(sampleHtml, includeHttp = true, includeSocks5 = false)
        assertEquals(1, httpOnly.size)
        assertEquals("HTTP", httpOnly[0].proxyType)
        assertEquals(80, httpOnly[0].port)

        val port1080Only = FreeProxyWorldSource.parseHtml(sampleHtml, portFilter = "1080")
        assertEquals(1, port1080Only.size)
        assertEquals(1080, port1080Only[0].port)
        assertEquals("172.67.183.122", port1080Only[0].ip)

        val port80Only = FreeProxyWorldSource.parseHtml(sampleHtml, portFilter = "80")
        assertEquals(1, port80Only.size)
        assertEquals(80, port80Only[0].port)
        assertEquals("104.16.1.212", port80Only[0].ip)
    }

    @Test
    fun proxyScrapeSource_parsesPlainTextAndJsonAndBuildsUrl() {
        assertEquals(
            "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=10000&country=US&ssl=all&anonymity=all",
            ProxyScrapeSource.buildV2Url(protocol = "socks5", countryCode = "US")
        )
        assertEquals(
            "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=10000&country=all&ssl=all&anonymity=all",
            ProxyScrapeSource.buildV2Url(protocol = "http", countryCode = "")
        )

        val textPayload = """
            198.51.100.10:8080
            socks5://203.0.113.20:1080
            [2606:4700::1]:443
        """.trimIndent()
        val parsedText = ProxyScrapeSource.parseResponse(textPayload, defaultCountry = "DE", defaultProxyType = "HTTP")
        assertEquals(3, parsedText.size)
        assertEquals("198.51.100.10", parsedText[0].ip)
        assertEquals(8080, parsedText[0].port)
        assertEquals("HTTP", parsedText[0].proxyType)
        assertEquals("DE", parsedText[0].countryCode)
        assertEquals("SOCKS5", parsedText[1].proxyType)
        assertEquals(1080, parsedText[1].port)
        assertEquals("2606:4700::1", parsedText[2].ip)

        val port1080 = ProxyScrapeSource.parseResponse(textPayload, portFilter = "1080")
        assertEquals(1, port1080.size)
        assertEquals("203.0.113.20", port1080[0].ip)

        val jsonPayload = """
            {
              "proxies": [
                {"ip": "45.8.144.10", "port": 80, "protocol": "http", "ip_data": {"countryCode": "NL"}},
                {"ip": "45.8.144.20", "port": 1080, "protocol": "socks5", "ip_data": {"countryCode": "US"}}
              ]
            }
        """.trimIndent()
        val socksFromJson = ProxyScrapeSource.parseResponse(jsonPayload, includeHttp = false, includeSocks5 = true)
        assertEquals(1, socksFromJson.size)
        assertEquals("45.8.144.20", socksFromJson[0].ip)
        assertEquals("US", socksFromJson[0].countryCode)
        assertEquals("SOCKS5", socksFromJson[0].proxyType)
    }

    @Test
    fun allRequestedCdns_collectValidIpv4AndIpv6Candidates() = runBlocking {
        val requiredCdns = listOf(
            CdnProvider.AKAMAI,
            CdnProvider.FASTLY,
            CdnProvider.CLOUDFLARE,
            CdnProvider.ARVANCLOUD,
            CdnProvider.GCORE,
            CdnProvider.CACHEFLY,
            CdnProvider.AMAZON_CLOUDFRONT
        )
        requiredCdns.forEach { cdn ->
            val v4 = PsiphonCleanIps.collectCandidates(
                provider = cdn,
                includeIpv4 = true,
                includeIpv6 = false,
                customInput = "",
                importedServerIps = emptyList(),
                maxCount = 12
            )
            assertTrue("Expected IPv4 candidates for ${cdn.displayName}", v4.isNotEmpty())
            assertTrue("Expected only IPv4 for ${cdn.displayName}", v4.all { !it.contains(":") })

            val v6 = PsiphonCleanIps.collectCandidates(
                provider = cdn,
                includeIpv4 = false,
                includeIpv6 = true,
                customInput = "",
                importedServerIps = emptyList(),
                maxCount = 12
            )
            assertTrue("Expected IPv6 candidates for ${cdn.displayName}", v6.isNotEmpty())
            assertTrue("Expected only IPv6 for ${cdn.displayName}", v6.all { it.contains(":") })
        }
    }

    @Test
    fun parseCustomTargets_supportsIpv4CidrAndIpv6() {
        val mixed = "23.32.0.0/29, 2a04:4e42::1, 151.101.1.69"
        val parsed = PsiphonCleanIps.parseCustomTargets(
            raw = mixed,
            includeIpv4 = true,
            includeIpv6 = true,
            limit = 20
        )
        assertTrue(parsed.contains("2a04:4e42::1"))
        assertTrue(parsed.contains("151.101.1.69"))
        assertTrue(parsed.any { it.startsWith("23.32.0.") })

        val v6Only = PsiphonCleanIps.parseCustomTargets(
            raw = mixed,
            includeIpv4 = false,
            includeIpv6 = true,
            limit = 20
        )
        assertEquals(listOf("2a04:4e42::1"), v6Only)
    }

    @Test
    fun parseServerEntries_extractsHexAndPlainIps() {
        val prober = PsiphonProbes()
        val hexLine = "7b22697041646472657373223a223139382e35312e3130302e3432227d" // {"ipAddress":"198.51.100.42"}
        val extracted = prober.parseServerEntries("203.0.113.10:443\n$hexLine")
        assertTrue(extracted.contains("203.0.113.10"))
        assertTrue(extracted.contains("198.51.100.42"))
    }

    @Test
    fun scannerViewModel_targetPortsAndMultiProvidersUpdateSynchronously() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.data.local.MlmDatabase.getInstance(context)
        val prefs = com.example.data.preferences.UserPreferencesRepository(context)
        val repo = com.example.data.repository.MlmRepository(db, prefs)
        val vm = com.example.ui.ScannerViewModel(context, repo)

        vm.updateSettings { it.copy(targetPortsInput = "") }
        assertEquals("", vm.settings.value.targetPortsInput)
        vm.updateSettings { it.copy(targetPortsInput = "8") }
        assertEquals("8", vm.settings.value.targetPortsInput)
        vm.updateSettings { it.copy(targetPortsInput = "80") }
        assertEquals("80", vm.settings.value.targetPortsInput)
        vm.updateSettings { it.copy(targetPortsInput = "808") }
        assertEquals("808", vm.settings.value.targetPortsInput)
        vm.updateSettings { it.copy(targetPortsInput = "8080") }
        assertEquals("8080", vm.settings.value.targetPortsInput)

        vm.setFreeProxyPortFilter("1080")
        assertEquals("1080", vm.settings.value.freeProxyPortFilter)
        vm.setFreeProxyPortFilter("abc8443xyz")
        assertEquals("8443", vm.settings.value.freeProxyPortFilter)
        vm.setFreeProxyPortFilter("")
        assertEquals("", vm.settings.value.freeProxyPortFilter)

        vm.toggleProvider(CdnProvider.CLOUDFLARE)
        assertTrue(CdnProvider.CLOUDFLARE in vm.settings.value.selectedProviders)
        vm.toggleProvider(CdnProvider.PROXYSCRAPE)
        assertTrue(CdnProvider.PROXYSCRAPE in vm.settings.value.selectedProviders)
        vm.toggleAllRealScanCdns()
        assertTrue(CdnProvider.realScanCdns.all { it in vm.settings.value.selectedProviders })
    }
}
