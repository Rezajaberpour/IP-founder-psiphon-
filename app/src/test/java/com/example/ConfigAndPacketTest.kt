package com.example

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.example.psiphon.CdnProvider
import com.example.psiphon.FreeProxyWorldSource
import com.example.psiphon.MultiProxySources
import com.example.psiphon.ProbeTelemetry
import com.example.psiphon.ProxyScrapeSource
import com.example.psiphon.PsiphonCleanIps
import com.example.psiphon.PsiphonIntelligenceEngine
import com.example.psiphon.PsiphonMethod
import com.example.psiphon.PsiphonProbes
import com.example.psiphon.PsiphonRegions
import com.example.psiphon.ScanTarget
import kotlinx.coroutines.runBlocking
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        vm.toggleAllProxySources()
        assertTrue(CdnProvider.proxyListSources.all { it in vm.settings.value.selectedProviders })
    }

    @Test
    fun sourceCounts_matchSevenCdnsAndSixProxySources() {
        assertEquals(7, CdnProvider.realScanCdns.size)
        assertEquals(6, CdnProvider.proxyListSources.size)
    }

    @Test
    fun multiProxySources_parseGeoNodeJsonAndPlainFeeds() {
        val url = MultiProxySources.buildGeoNodeUrl(countryCode = "DE", includeHttp = true, includeSocks5 = true)
        assertTrue(url.contains("proxylist.geonode.com/api/proxy-list"))
        assertTrue(url.contains("country=DE"))

        val geoJson = """
            {
              "data": [
                {"ip": "198.51.100.1", "port": "8080", "country": "US", "protocols": ["http"]},
                {"ip": "203.0.113.2", "port": "1080", "country": "DE", "protocols": ["socks5"]}
              ]
            }
        """.trimIndent()
        val parsedGeo = MultiProxySources.parseGeoNodeJson(geoJson, countryFilter = "DE", portFilter = "1080")
        assertEquals(1, parsedGeo.size)
        assertEquals("203.0.113.2", parsedGeo[0].ip)
        assertEquals(1080, parsedGeo[0].port)
        assertEquals("SOCKS5", parsedGeo[0].proxyType)
        assertEquals(CdnProvider.GEONODE, parsedGeo[0].provider)

        val plainRaw = """
            # comment
            198.51.100.77:3128
            socks5://203.0.113.88:1080
            [2a04:4e42::1]:443
        """.trimIndent()
        val speedxSocks = MultiProxySources.parsePlainFeed(
            raw = plainRaw,
            provider = CdnProvider.THESPEEDX,
            defaultProxyType = "HTTP",
            portFilter = "1080"
        )
        assertEquals(1, speedxSocks.size)
        assertEquals("203.0.113.88", speedxSocks[0].ip)
        assertEquals("SOCKS5", speedxSocks[0].proxyType)
        assertEquals(CdnProvider.THESPEEDX, speedxSocks[0].provider)
    }

    @Test
    fun hybridIntelligenceEngine_evaluatesLearnsAndComputesNashEquilibrium() {
        val engine = PsiphonIntelligenceEngine()

        val optimalProbe = ProbeTelemetry(
            ip = "104.17.1.15",
            ipVersion = "IPv4",
            targetPort = 8443,
            localSocksPort = 1080,
            proxyProtocol = "HTTP",
            provider = CdnProvider.CLOUDFLARE,
            method = PsiphonMethod.FRONTED_MEEK_OSSH,
            tcpPingMs = 65L,
            httpsPingMs = 110L,
            tlsVersion = "TLSv1.3",
            certIssuer = "Cloudflare Inc",
            certValid = true,
            httpsStatusCode = 200,
            countryCode = "US",
            countryName = "United States",
            countryFlag = "🇺🇸",
            isClean = true
        )

        val failedProbe = ProbeTelemetry(
            ip = "198.51.100.99",
            ipVersion = "IPv4",
            targetPort = 80,
            localSocksPort = 1080,
            proxyProtocol = "HTTP",
            provider = CdnProvider.OPENPROXY,
            method = PsiphonMethod.SSH,
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
            failureReason = "Timeout"
        )

        val evalOptimal = engine.evaluateAndLearn(optimalProbe, timeoutMs = 3000)
        val evalFailed = engine.evaluateAndLearn(failedProbe, timeoutMs = 3000)

        assertTrue("Clean TLSv1.3 fast IP should score high (${evalOptimal.aiScore})", evalOptimal.aiScore >= 75)
        assertTrue("Optimal IP must outscore failed IP", evalOptimal.aiScore > evalFailed.aiScore)
        assertTrue("Failed IP must stay low", evalFailed.aiScore < 35)
        assertTrue(evalOptimal.fuzzyMembership > evalFailed.fuzzyMembership)
        assertTrue(evalOptimal.nnPrediction > evalFailed.nnPrediction)
        assertTrue(evalOptimal.nashPayoff > evalFailed.nashPayoff)

        // Repeatedly train online ANN & empirical stats on Cloudflare vs OpenProxy
        repeat(4) {
            engine.evaluateAndLearn(optimalProbe, timeoutMs = 3000)
            engine.evaluateAndLearn(failedProbe, timeoutMs = 3000)
        }
        val summary = engine.snapshotSummary()
        assertEquals(10, summary.samplesLearned)

        val nashMix = engine.solveMixedNashStrategy(listOf(CdnProvider.CLOUDFLARE, CdnProvider.OPENPROXY))
        val probSum = nashMix.values.sum()
        assertEquals(1.0, probSum, 0.001)
        assertTrue(
            "Nash equilibrium should favor reliable CLOUDFLARE over failing OPENPROXY",
            (nashMix[CdnProvider.CLOUDFLARE] ?: 0.0) > (nashMix[CdnProvider.OPENPROXY] ?: 0.0)
        )

        val prioritized = engine.prioritizeTargets(
            listOf(
                ScanTarget(ip = "198.51.100.99", port = 80, provider = CdnProvider.OPENPROXY),
                ScanTarget(ip = "104.17.1.20", port = 8443, provider = CdnProvider.CLOUDFLARE)
            )
        )
        assertEquals(CdnProvider.CLOUDFLARE, prioritized.first().provider)
    }

    @Test
    fun sugenoProxyHealthFis_tunesGaussianMembershipFunctionsWithAnnAndComputesAnonymityScore() {
        val fis = com.example.psiphon.SugenoProxyHealthFis()
        val initialSnapshot = fis.getMembershipSnapshot()

        val highAnonProxy = ProbeTelemetry(
            ip = "104.18.2.44",
            ipVersion = "IPv4",
            targetPort = 8443,
            localSocksPort = 1080,
            proxyProtocol = "SOCKS5",
            provider = CdnProvider.CLOUDFLARE,
            method = PsiphonMethod.FRONTED_MEEK_OSSH,
            tcpPingMs = 55L,
            httpsPingMs = 95L,
            tlsVersion = "TLSv1.3",
            certIssuer = "Cloudflare",
            certValid = true,
            httpsStatusCode = 200,
            countryCode = "US",
            countryName = "United States",
            countryFlag = "🇺🇸",
            isClean = true
        )

        val weakPlainProxy = ProbeTelemetry(
            ip = "203.0.113.55",
            ipVersion = "IPv4",
            targetPort = 80,
            localSocksPort = 1080,
            proxyProtocol = "HTTP",
            provider = CdnProvider.OPENPROXY,
            method = PsiphonMethod.SSH,
            tcpPingMs = 1400L,
            httpsPingMs = 2600L,
            tlsVersion = "TCP",
            certIssuer = "",
            certValid = false,
            httpsStatusCode = 403,
            countryCode = "--",
            countryName = "Unknown",
            countryFlag = "🌐",
            isClean = false,
            failureReason = "Blocked"
        )

        val evalHigh = fis.evaluateAndTune(highAnonProxy, timeoutMs = 3000)
        val evalWeak = fis.evaluateAndTune(weakPlainProxy, timeoutMs = 3000)

        assertTrue(
            "High-anonymity TLSv1.3 SOCKS5 proxy should score high (${evalHigh.anonymityConfidenceScore})",
            evalHigh.anonymityConfidenceScore >= 75
        )
        assertTrue(
            "Sugeno proxy health should score high (${evalHigh.proxyHealthScore})",
            evalHigh.proxyHealthScore >= 75
        )
        assertTrue(evalHigh.anonymityConfidenceScore > evalWeak.anonymityConfidenceScore)
        assertTrue(evalHigh.proxyHealthScore > evalWeak.proxyHealthScore)
        assertTrue(evalWeak.anonymityConfidenceScore < 40)

        // Repeatedly tune Gaussian MF parameters via ANN backpropagation
        repeat(6) {
            fis.evaluateAndTune(highAnonProxy, timeoutMs = 3000)
            fis.evaluateAndTune(weakPlainProxy, timeoutMs = 3000)
        }
        val tunedSnapshot = fis.getMembershipSnapshot()
        assertEquals(14, fis.tuningEpochs)
        assertTrue("ANN tuner must shift Gaussian MF centers/widths", fis.cumulativeMfShift > 0.0)
        assertTrue(
            "Membership function snapshot should reflect tuned parameters",
            initialSnapshot != tunedSnapshot
        )

        // End-to-end check through PsiphonIntelligenceEngine
        val engine = PsiphonIntelligenceEngine()
        val enriched = engine.evaluateAndLearn(highAnonProxy, timeoutMs = 3000)
        assertTrue(enriched.anonymityConfidenceScore >= 75)
        assertTrue(enriched.proxyHealthScore >= 75)
        val summary = engine.snapshotSummary()
        assertEquals(1, summary.mfTuningEpochs)
        assertTrue(summary.avgAnonymityConfidence >= 75)
    }

    @Test
    fun geoIpTable_parsesLocationDetailsSearchesByCountryCityIspAndSortsByAnonymity() {
        val sampleJson = """
            {
              "success": true,
              "ip": "104.16.132.229",
              "country_code": "US",
              "city": "San Jose",
              "connection": {
                "isp": "Cloudflare, Inc.",
                "org": "Cloudflare, Inc."
              }
            }
        """.trimIndent()
        val parsed = com.example.psiphon.PsiphonRegions.parseGeoIpJson(sampleJson)
        assertNotNull(parsed)
        assertEquals("US", parsed!!.code)
        assertEquals("San Jose", parsed.city)
        assertEquals("Cloudflare, Inc.", parsed.isp)

        val live = listOf(
            ProbeTelemetry(
                ip = "104.16.132.229",
                ipVersion = "IPv4",
                targetPort = 443,
                localSocksPort = 1080,
                proxyProtocol = "HTTP",
                provider = CdnProvider.CLOUDFLARE,
                method = PsiphonMethod.FRONTED_MEEK_OSSH,
                tcpPingMs = 45L,
                httpsPingMs = 90L,
                tlsVersion = "TLSv1.3",
                certIssuer = "Cloudflare",
                certValid = true,
                httpsStatusCode = 200,
                countryCode = "US",
                countryName = "United States",
                countryFlag = "🇺🇸",
                city = "San Jose",
                isp = "Cloudflare, Inc.",
                isClean = true,
                anonymityConfidenceScore = 91
            ),
            ProbeTelemetry(
                ip = "185.143.232.10",
                ipVersion = "IPv4",
                targetPort = 1080,
                localSocksPort = 1080,
                proxyProtocol = "SOCKS5",
                provider = CdnProvider.GEONODE,
                method = PsiphonMethod.OSSH,
                tcpPingMs = 130L,
                httpsPingMs = 130L,
                tlsVersion = "SOCKS5",
                certIssuer = "None",
                certValid = true,
                httpsStatusCode = null,
                countryCode = "DE",
                countryName = "Germany",
                countryFlag = "🇩🇪",
                city = "Frankfurt",
                isp = "Hetzner Online GmbH",
                isClean = true,
                anonymityConfidenceScore = 84
            )
        )

        val rows = com.example.ui.screens.buildGeoIpTableRows(liveResults = live, savedIps = emptyList())
        assertEquals(2, rows.size)

        val hetznerMatches = com.example.ui.screens.filterAndSortGeoIpRows(
            rows = rows,
            query = "Hetzner",
            filterMode = "ALL",
            sortColumn = com.example.ui.screens.GeoIpSortColumn.ANONYMITY,
            sortDescending = true
        )
        assertEquals(1, hetznerMatches.size)
        assertEquals("Frankfurt", hetznerMatches[0].city)

        val cityMatches = com.example.ui.screens.filterAndSortGeoIpRows(
            rows = rows,
            query = "San Jose",
            filterMode = "ALL",
            sortColumn = com.example.ui.screens.GeoIpSortColumn.ANONYMITY,
            sortDescending = true
        )
        assertEquals(1, cityMatches.size)
        assertEquals(91, cityMatches[0].anonymityConfidenceScore)

        val sortedByAnon = com.example.ui.screens.filterAndSortGeoIpRows(
            rows = rows,
            query = "",
            filterMode = "ALL",
            sortColumn = com.example.ui.screens.GeoIpSortColumn.ANONYMITY,
            sortDescending = true
        )
        assertEquals(91, sortedByAnon.first().anonymityConfidenceScore)
    }

    @Test
    fun cloudflareDiagnosticEngine_computesRttJitterColoAndManagesNodes() {
        val traceBody = """
            fl=21f412
            h=speed.cloudflare.com
            ip=203.0.113.10
            ts=1731790000.123
            colo=FRA
            loc=DE
        """.trimIndent()
        assertEquals("FRA", com.example.psiphon.CloudflareDiagnosticEngine.parseCloudflareTraceColo(traceBody))
        assertTrue(com.example.psiphon.CloudflareDiagnosticEngine.isValidEdgeIp("104.18.12.34"))
        assertTrue(!com.example.psiphon.CloudflareDiagnosticEngine.isValidEdgeIp("not-an-ip"))

        val node = com.example.psiphon.CloudflareDiagnosticEngine.presetNodes.first()
        val samples = listOf(
            com.example.psiphon.RttSample(1, node.id, node.ip, node.label, 35L, 80L, "FRA", 1000L, true),
            com.example.psiphon.RttSample(2, node.id, node.ip, node.label, 42L, 100L, "FRA", 2000L, true),
            com.example.psiphon.RttSample(3, node.id, node.ip, node.label, 38L, 90L, "FRA", 3000L, true),
            com.example.psiphon.RttSample(4, node.id, node.ip, node.label, null, null, "--", 4000L, false, "Timeout")
        )
        val stats = com.example.psiphon.CloudflareDiagnosticEngine.computeNodeStats(node, samples)
        assertEquals(80L, stats.minRttMs)
        assertEquals(90L, stats.avgRttMs)
        assertEquals(100L, stats.maxRttMs)
        assertEquals(15L, stats.jitterMs) // (|100-80| + |90-100|) / 2 = 15
        assertEquals(25, stats.packetLossPercent)
        assertEquals("FRA", stats.coloCode)

        val app = ApplicationProvider.getApplicationContext<MlmVpnApplication>()
        val vm = com.example.ui.ScannerViewModel(app, app.repository)
        assertTrue(vm.addCustomCfEdgeNode("104.18.55.1"))
        assertTrue(vm.cfDiagState.value.nodes.any { it.ip == "104.18.55.1" })
        vm.setCfDiagInterval(500L)
        assertEquals(500L, vm.cfDiagState.value.intervalMs)
    }

    @Test
    fun adaptiveRoutingEngine_selectsBestProxyUsingAnonymityAndCloudflareRtt() {
        val cfNode = com.example.psiphon.CloudflareDiagnosticEngine.presetNodes.first()

        val highAnonCf = com.example.data.local.CleanIpEntity(
            idKey = "104.16.132.229:443:HTTP",
            ip = "104.16.132.229",
            ipVersion = "IPv4",
            targetPort = 443,
            localSocksPort = 1080,
            proxyProtocol = "HTTP",
            provider = "Cloudflare",
            method = "FRONTED-MEEK-OSSH",
            tcpPingMs = 45L,
            httpsPingMs = 95L,
            tlsVersion = "TLSv1.3",
            certIssuer = "Cloudflare",
            certValid = true,
            httpsStatusCode = 200,
            countryCode = "US",
            countryName = "United States",
            countryFlag = "🇺🇸",
            isClean = true,
            anonymityConfidenceScore = 92,
            proxyHealthScore = 90
        )

        val stealthSocks = com.example.data.local.CleanIpEntity(
            idKey = "185.143.232.10:8443:SOCKS5",
            ip = "185.143.232.10",
            ipVersion = "IPv4",
            targetPort = 8443,
            localSocksPort = 1080,
            proxyProtocol = "SOCKS5",
            provider = "GeoNode",
            method = "OSSH",
            tcpPingMs = 85L,
            httpsPingMs = 110L,
            tlsVersion = "SOCKS5",
            certIssuer = "None",
            certValid = true,
            httpsStatusCode = null,
            countryCode = "DE",
            countryName = "Germany",
            countryFlag = "🇩🇪",
            isClean = true,
            anonymityConfidenceScore = 91,
            proxyHealthScore = 88
        )

        val weakHttp80 = com.example.data.local.CleanIpEntity(
            idKey = "104.17.1.10:80:HTTP",
            ip = "104.17.1.10",
            ipVersion = "IPv4",
            targetPort = 80,
            localSocksPort = 1080,
            proxyProtocol = "HTTP",
            provider = "Cloudflare",
            method = "SSH",
            tcpPingMs = 40L,
            httpsPingMs = 80L,
            tlsVersion = "TCP",
            certIssuer = "None",
            certValid = false,
            httpsStatusCode = 200,
            countryCode = "US",
            countryName = "United States",
            countryFlag = "🇺🇸",
            isClean = true,
            anonymityConfidenceScore = 48,
            proxyHealthScore = 52
        )

        // Scenario 1: Healthy, fast Cloudflare RTT -> Synergy favors high-anonymity Cloudflare TLSv1.3 route
        val fastCfStats = com.example.psiphon.CloudflareDiagnosticEngine.computeNodeStats(
            cfNode,
            listOf(
                com.example.psiphon.RttSample(1, cfNode.id, cfNode.ip, cfNode.label, 25L, 55L, "SJC", 1000L, true),
                com.example.psiphon.RttSample(2, cfNode.id, cfNode.ip, cfNode.label, 28L, 60L, "SJC", 2000L, true)
            )
        )
        val fastCfState = com.example.psiphon.CloudflareDiagnosticState(
            nodeStats = mapOf(cfNode.id to fastCfStats)
        )
        val fastDecision = com.example.psiphon.AdaptiveRoutingEngine.evaluateBestRoute(
            candidates = listOf(weakHttp80, stealthSocks, highAnonCf),
            cfDiagState = fastCfState,
            currentSelectedKey = ""
        )
        assertEquals(highAnonCf.idKey, fastDecision.selectedIdKey)
        assertEquals(com.example.psiphon.AdaptiveRouteReason.OPTIMAL_CF_SYNERGY, fastDecision.reasonCode)

        // Scenario 2: Congested/lossy Cloudflare edge -> Failover shifts to resilient SOCKS5 alt-port route
        val congestedCfStats = com.example.psiphon.CloudflareDiagnosticEngine.computeNodeStats(
            cfNode,
            listOf(
                com.example.psiphon.RttSample(1, cfNode.id, cfNode.ip, cfNode.label, 220L, 410L, "SJC", 1000L, true),
                com.example.psiphon.RttSample(2, cfNode.id, cfNode.ip, cfNode.label, null, null, "--", 2000L, false, "Timeout")
            )
        )
        val congestedCfState = com.example.psiphon.CloudflareDiagnosticState(
            nodeStats = mapOf(cfNode.id to congestedCfStats)
        )
        val failoverDecision = com.example.psiphon.AdaptiveRoutingEngine.evaluateBestRoute(
            candidates = listOf(weakHttp80, highAnonCf, stealthSocks),
            cfDiagState = congestedCfState,
            currentSelectedKey = weakHttp80.idKey
        )
        assertEquals(stealthSocks.idKey, failoverDecision.selectedIdKey)
        assertEquals(com.example.psiphon.AdaptiveRouteReason.CONGESTION_FAILOVER, failoverDecision.reasonCode)
    }

    @Test
    fun encryptedProxyBackup_roundTripsSettingsAndProxiesAndRejectsWrongPassphrase() = runBlocking {
        val sampleSettings = com.example.data.preferences.ScannerSettings(
            provider = CdnProvider.CLOUDFLARE,
            selectedProviders = setOf(CdnProvider.CLOUDFLARE, CdnProvider.FASTLY),
            method = PsiphonMethod.FRONTED_MEEK_OSSH,
            includeIpv4 = true,
            includeIpv6 = false,
            includeHttp = true,
            includeSocks5 = true,
            targetPortsInput = "443, 8443",
            localSocksPort = 1089,
            sniOverride = "cdn.example.net",
            freeProxyCountry = "DE",
            autoRoutingEnabled = true
        )
        val sampleEntities = listOf(
            com.example.data.local.CleanIpEntity(
                idKey = "104.18.22.10:8443:SOCKS5",
                ip = "104.18.22.10",
                ipVersion = "IPv4",
                targetPort = 8443,
                localSocksPort = 1089,
                proxyProtocol = "SOCKS5",
                provider = "Cloudflare",
                method = "FRONTED-MEEK-OSSH",
                tcpPingMs = 44L,
                httpsPingMs = 92L,
                tlsVersion = "TLSv1.3",
                certIssuer = "Cloudflare",
                certValid = true,
                httpsStatusCode = 200,
                countryCode = "DE",
                countryName = "Germany",
                countryFlag = "🇩🇪",
                city = "Frankfurt",
                isp = "Cloudflare, Inc.",
                isClean = true,
                aiScore = 91,
                anonymityConfidenceScore = 93,
                proxyHealthScore = 89,
                aiVerdict = "OPTIMAL"
            )
        )

        val passphrase = "s3cure-psiphon-backup!"
        val encryptedBytes = com.example.psiphon.EncryptedProxyBackup.exportToEncryptedBytes(
            settings = sampleSettings,
            proxies = sampleEntities,
            passphrase = passphrase,
            exportedAtEpochMs = 1731790000000L
        )
        val encryptedText = encryptedBytes.toString(Charsets.UTF_8)
        assertTrue(com.example.psiphon.EncryptedProxyBackup.isValidBackupEnvelope(encryptedBytes))
        assertTrue(encryptedText.contains("IPFOUNDER_AES_GCM_V1"))
        // Ciphertext must not expose raw proxy IPs or SNI in plaintext
        assertTrue(!encryptedText.contains("104.18.22.10"))
        assertTrue(!encryptedText.contains("cdn.example.net"))

        val restored = com.example.psiphon.EncryptedProxyBackup.importFromEncryptedBytes(
            rawBytes = encryptedBytes,
            passphrase = passphrase
        )
        assertEquals(1, restored.restoredEntities.size)
        val restoredProxy = restored.restoredEntities.first()
        assertEquals("104.18.22.10", restoredProxy.ip)
        assertEquals(8443, restoredProxy.targetPort)
        assertEquals("SOCKS5", restoredProxy.proxyProtocol)
        assertEquals("Frankfurt", restoredProxy.city)
        assertEquals(93, restoredProxy.anonymityConfidenceScore)

        val restoredSettings = restored.settingsRestorer(com.example.data.preferences.ScannerSettings())
        assertEquals(1089, restoredSettings.localSocksPort)
        assertEquals("cdn.example.net", restoredSettings.sniOverride)
        assertEquals("DE", restoredSettings.freeProxyCountry)
        assertTrue(CdnProvider.FASTLY in restoredSettings.selectedProviders)

        // Wrong passphrase must fail GCM authentication tag verification
        var wrongPassRejected = false
        try {
            com.example.psiphon.EncryptedProxyBackup.importFromEncryptedBytes(encryptedBytes, "wrong-pass")
        } catch (_: com.example.psiphon.BackupDecryptionException) {
            wrongPassRejected = true
        }
        assertTrue("Wrong passphrase must throw BackupDecryptionException", wrongPassRejected)

        // Non-backup file must fail envelope validation
        assertTrue(!com.example.psiphon.EncryptedProxyBackup.isValidBackupEnvelope("not-a-backup".toByteArray()))

        // ViewModel round-trip persistence check
        val app = ApplicationProvider.getApplicationContext<MlmVpnApplication>()
        val vm = com.example.ui.ScannerViewModel(app, app.repository)
        app.repository.clearAllCleanIps()
        val restoredCount = vm.restoreEncryptedBackupBytes(
            rawBytes = encryptedBytes,
            passphrase = passphrase,
            replaceExisting = true
        )
        assertEquals(1, restoredCount)
        assertEquals(1089, vm.settings.value.localSocksPort)
        assertEquals("104.18.22.10", app.repository.getAllCleanIpsOnce().first().ip)
    }
}
