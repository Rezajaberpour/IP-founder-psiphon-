package com.example.psiphon

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.example.data.local.CleanIpEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class AdaptiveRouteReason {
    OPTIMAL_CF_SYNERGY,
    CONGESTION_FAILOVER,
    ANONYMITY_PRIORITY,
    HOLD_HYSTERESIS,
    NO_CLEAN_ROUTES
}

data class AdaptiveRouteDecision(
    val selectedRoute: CleanIpEntity? = null,
    val selectedIdKey: String = "",
    val compositeScore: Int = 0,
    val anonymityConfidence: Int = 0,
    val proxyRttMs: Long? = null,
    val cfReferenceIp: String = "1.1.1.1",
    val cfReferenceRttMs: Long? = null,
    val cfReferenceColo: String = "--",
    val cfJitterMs: Long? = null,
    val cfLossPercent: Int = 0,
    val reasonCode: AdaptiveRouteReason = AdaptiveRouteReason.NO_CLEAN_ROUTES,
    val evaluatedCount: Int = 0,
    val updatedAtMs: Long = 0L
)

object AdaptiveRoutingEngine {

    private const val HYSTERESIS_MARGIN = 3.0

    fun evaluateBestRoute(
        candidates: List<CleanIpEntity>,
        cfDiagState: CloudflareDiagnosticState,
        currentSelectedKey: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): AdaptiveRouteDecision {
        // Select best-performing healthy Cloudflare edge reference from live diagnostics
        val activeCfStats = cfDiagState.nodeStats.values
            .filter { it.samples.isNotEmpty() }
            .sortedWith(
                compareBy<CloudflareNodeStats> { it.packetLossPercent }
                    .thenBy { it.avgRttMs ?: it.latestRttMs ?: Long.MAX_VALUE }
            )
        val bestCf = activeCfStats.firstOrNull()
        val cfRefIp = bestCf?.node?.ip ?: "1.1.1.1"
        val cfRefRtt = bestCf?.avgRttMs ?: bestCf?.latestRttMs
        val cfRefColo = bestCf?.coloCode ?: "--"
        val cfJitter = bestCf?.jitterMs
        val cfLoss = bestCf?.packetLossPercent ?: 0

        val cleanCandidates = candidates.filter { it.isClean }
        if (cleanCandidates.isEmpty()) {
            return AdaptiveRouteDecision(
                selectedRoute = null,
                selectedIdKey = "",
                cfReferenceIp = cfRefIp,
                cfReferenceRttMs = cfRefRtt,
                cfReferenceColo = cfRefColo,
                cfJitterMs = cfJitter,
                cfLossPercent = cfLoss,
                reasonCode = AdaptiveRouteReason.NO_CLEAN_ROUTES,
                evaluatedCount = candidates.size,
                updatedAtMs = nowMs
            )
        }

        val isCfCongested = (cfRefRtt != null && cfRefRtt > 220L) ||
            (cfJitter != null && cfJitter > 45L) ||
            cfLoss >= 10

        val isCfHealthyFast = cfRefRtt != null && cfRefRtt <= 140L && cfLoss < 5

        val scored = cleanCandidates.map { entity ->
            val rawScore = scoreCandidate(
                entity = entity,
                cfRefRtt = cfRefRtt,
                isCfHealthyFast = isCfHealthyFast,
                isCfCongested = isCfCongested
            )
            entity to rawScore
        }.sortedByDescending { it.second }

        val topPair = scored.first()
        val currentPair = if (currentSelectedKey.isNotBlank()) {
            scored.firstOrNull { it.first.idKey == currentSelectedKey }
        } else null

        val chosenPair: Pair<CleanIpEntity, Double>
        val reason: AdaptiveRouteReason

        if (currentPair != null &&
            currentPair.first.idKey != topPair.first.idKey &&
            (topPair.second - currentPair.second) < HYSTERESIS_MARGIN
        ) {
            chosenPair = currentPair
            reason = AdaptiveRouteReason.HOLD_HYSTERESIS
        } else {
            chosenPair = topPair
            val entity = chosenPair.first
            reason = when {
                isCfCongested && (entity.proxyProtocol.equals("SOCKS5", ignoreCase = true) ||
                    entity.targetPort in setOf(8443, 2053, 2083)) -> AdaptiveRouteReason.CONGESTION_FAILOVER
                isCfHealthyFast && entity.provider.equals("Cloudflare", ignoreCase = true) ->
                    AdaptiveRouteReason.OPTIMAL_CF_SYNERGY
                else -> AdaptiveRouteReason.ANONYMITY_PRIORITY
            }
        }

        val chosen = chosenPair.first
        return AdaptiveRouteDecision(
            selectedRoute = chosen,
            selectedIdKey = chosen.idKey,
            compositeScore = chosenPair.second.roundToInt().coerceIn(0, 100),
            anonymityConfidence = chosen.anonymityConfidenceScore,
            proxyRttMs = chosen.httpsPingMs ?: chosen.tcpPingMs,
            cfReferenceIp = cfRefIp,
            cfReferenceRttMs = cfRefRtt,
            cfReferenceColo = cfRefColo,
            cfJitterMs = cfJitter,
            cfLossPercent = cfLoss,
            reasonCode = reason,
            evaluatedCount = cleanCandidates.size,
            updatedAtMs = nowMs
        )
    }

    internal fun scoreCandidate(
        entity: CleanIpEntity,
        cfRefRtt: Long?,
        isCfHealthyFast: Boolean,
        isCfCongested: Boolean
    ): Double {
        val anonNorm = (entity.anonymityConfidenceScore.coerceIn(0, 100) / 100.0)
        val proxyPing = (entity.httpsPingMs ?: entity.tcpPingMs ?: 1200L).toDouble()
        val latencyNorm = (1.0 - (proxyPing / 2000.0)).coerceIn(0.05, 1.0)
        val healthNorm = (entity.proxyHealthScore.coerceIn(0, 100) / 100.0)

        // Under Cloudflare congestion, boost Anonymity + SOCKS5/Obfuscation resilience;
        // under healthy fast Cloudflare RTT, boost CDN synergy + low latency.
        val baseScore = if (isCfCongested) {
            100.0 * (0.56 * anonNorm + 0.24 * latencyNorm + 0.20 * healthNorm)
        } else {
            100.0 * (0.48 * anonNorm + 0.34 * latencyNorm + 0.18 * healthNorm)
        }

        val isCloudflareRoute = entity.provider.equals("Cloudflare", ignoreCase = true)
        val isSocksOrAltPort = entity.proxyProtocol.equals("SOCKS5", ignoreCase = true) ||
            entity.targetPort in setOf(8443, 2053, 2083)

        var cfDelta = 0.0
        if (isCfHealthyFast && isCloudflareRoute) {
            // Fast & clean Cloudflare edge diagnostics boost matching Cloudflare endpoints
            val rttClosenessBonus = if (cfRefRtt != null && proxyPing <= (cfRefRtt * 2.5 + 80.0)) 6.0 else 3.0
            cfDelta += rttClosenessBonus
        } else if (isCfCongested) {
            if (isCloudflareRoute && entity.targetPort == 80) {
                cfDelta -= 8.0 // Penalize fragile plain Cloudflare HTTP when edge shows loss/high RTT
            }
            if (isSocksOrAltPort) {
                cfDelta += 6.5 // Favor resilient SOCKS5 or alt TLS ports during congestion
            }
        }

        val certBonus = if (entity.certValid && entity.tlsVersion.contains("1.3", ignoreCase = true)) 2.5 else 0.0
        return (baseScore + cfDelta + certBonus).coerceIn(0.0, 100.0)
    }
}

/**
 * Bound Android Service hosting reactive Adaptive Routing evaluation across
 * Sugeno/ANN Anonymity Confidence Scores and live Cloudflare CDN RTT telemetry.
 */
class AdaptiveRoutingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): AdaptiveRoutingService = this@AdaptiveRoutingService
    }

    private val binder = LocalBinder()
    private val _latestDecision = MutableStateFlow(AdaptiveRouteDecision())
    val latestDecision: StateFlow<AdaptiveRouteDecision> = _latestDecision.asStateFlow()

    override fun onBind(intent: Intent?): IBinder = binder

    fun evaluateRoute(
        candidates: List<CleanIpEntity>,
        cfDiagState: CloudflareDiagnosticState,
        currentSelectedKey: String
    ): AdaptiveRouteDecision {
        val decision = AdaptiveRoutingEngine.evaluateBestRoute(
            candidates = candidates,
            cfDiagState = cfDiagState,
            currentSelectedKey = currentSelectedKey
        )
        _latestDecision.value = decision
        return decision
    }
}
