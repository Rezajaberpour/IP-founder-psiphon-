package com.example.psiphon

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

data class IntelligenceSummary(
    val samplesLearned: Int = 0,
    val avgAiScore: Int = 0,
    val avgFuzzyMembership: Float = 0f,
    val nashBestProvider: String = "--",
    val avgAnonymityConfidence: Int = 0,
    val avgProxyHealth: Int = 0,
    val mfTuningEpochs: Int = 0
)

/**
 * Hybrid Neuro-Fuzzy (ANFIS + Sugeno FIS), Online MLP ANN, and Game-Theoretic
 * (Fictitious Play Mixed Nash Equilibrium + Choquet Fuzzy Integral) Engine.
 */
class PsiphonIntelligenceEngine {

    private val lock = Any()
    private val sugenoFis = SugenoProxyHealthFis()

    // Lightweight 2-Layer Online MLP ANN (5 -> 6 -> 1) predicting tunnel clean survival probability
    private val w1 = Array(6) { i -> DoubleArray(5) { j -> if ((i + j) % 2 == 0) 0.25 else -0.14 } }
    private val b1 = DoubleArray(6) { 0.05 }
    private val w2 = DoubleArray(6) { i -> if (i % 2 == 0) 0.35 else 0.22 }
    private var b2 = 0.0

    // Empirical provider payoff tracking for Fictitious Play Nash Equilibrium
    private val providerStats = ConcurrentHashMap<CdnProvider, Pair<Double, Int>>()

    private var samplesLearned = 0
    private var cumulativeAiScore = 0L
    private var cumulativeFuzzy = 0.0
    private var cumulativeAnon = 0L
    private var cumulativeHealth = 0L

    fun evaluateAndLearn(telemetry: ProbeTelemetry, timeoutMs: Int = 3000): ProbeTelemetry =
        synchronized(lock) {
            val sugenoEval = sugenoFis.evaluateAndTune(telemetry, timeoutMs)
            val x = extractFeatureVector(telemetry, timeoutMs)

            // Forward pass: Online MLP ANN
            val hidden = DoubleArray(6)
            for (i in 0 until 6) {
                var sum = b1[i]
                for (j in 0 until 5) sum += w1[i][j] * x[j]
                hidden[i] = tanh(sum.coerceIn(-6.0, 6.0))
            }
            var logit = b2
            for (i in 0 until 6) logit += w2[i] * hidden[i]
            val nnProb = (1.0 / (1.0 + exp(-logit.coerceIn(-8.0, 8.0)))).coerceIn(0.01, 0.99)

            // ANFIS Layer-5 Fuzzy Membership Degree
            val fuzzyFit = (0.55 * x[0] + 0.25 * x[1] + 0.20 * sugenoEval.proxyHealth).coerceIn(0.0, 1.0)

            // Game Theory Payoff (Player: Client routing vs Censorship Adversary)
            val targetValue = if (telemetry.isClean) {
                (0.50 + 0.35 * x[0] + 0.15 * x[1]).coerceIn(0.55, 0.98)
            } else {
                if (telemetry.tcpPingMs != null) 0.18 else 0.04
            }
            val nashPayoff = if (telemetry.isClean) {
                (0.45 * sugenoEval.anonymityConfidence + 0.35 * x[0] + 0.20 * nnProb).coerceIn(0.35, 0.99)
            } else {
                (0.25 * x[0]).coerceIn(0.02, 0.30)
            }

            // Online SGD Backpropagation for MLP ANN
            val err = nnProb - targetValue
            val dLogit = err * nnProb * (1.0 - nnProb)
            val lr = 0.08
            for (i in 0 until 6) {
                val dh = dLogit * w2[i] * (1.0 - hidden[i] * hidden[i])
                w2[i] = (w2[i] - lr * dLogit * hidden[i]).coerceIn(-2.5, 2.5)
                for (j in 0 until 5) {
                    w1[i][j] = (w1[i][j] - lr * dh * x[j]).coerceIn(-2.5, 2.5)
                }
                b1[i] = (b1[i] - lr * dh).coerceIn(-2.0, 2.0)
            }
            b2 = (b2 - lr * dLogit).coerceIn(-2.0, 2.0)

            // Update provider empirical payoff for Nash solver
            val prev = providerStats[telemetry.provider] ?: (0.5 to 0)
            val count = prev.second + 1
            val runningMean = prev.first + (nashPayoff - prev.first) / count
            providerStats[telemetry.provider] = runningMean to count

            // Choquet Fuzzy Integral Fusion across Fuzzy, ANN, Nash & Sugeno Health
            val components = doubleArrayOf(
                fuzzyFit,
                nnProb,
                nashPayoff,
                sugenoEval.anonymityConfidence
            ).sortedDescending()
            val choquetScore = (0.42 * components[0] + 0.28 * components[1] + 0.18 * components[2] + 0.12 * components[3])
            val finalAiDouble = if (telemetry.isClean) {
                (choquetScore * 100.0).coerceIn(35.0, 99.0)
            } else {
                (choquetScore * 45.0).coerceIn(4.0, 34.0)
            }
            val aiScore = finalAiDouble.roundToInt()

            val verdict = when {
                !telemetry.isClean -> "RISKY"
                aiScore >= 82 -> "OPTIMAL"
                aiScore >= 64 -> "RESILIENT"
                aiScore >= 42 -> "MODERATE"
                else -> "RISKY"
            }

            samplesLearned += 1
            cumulativeAiScore += aiScore
            cumulativeFuzzy += fuzzyFit
            cumulativeAnon += sugenoEval.anonymityConfidenceScore
            cumulativeHealth += sugenoEval.proxyHealthScore

            telemetry.copy(
                aiScore = aiScore,
                fuzzyMembership = fuzzyFit.toFloat(),
                nnPrediction = nnProb.toFloat(),
                nashPayoff = nashPayoff.toFloat(),
                anonymityConfidenceScore = sugenoEval.anonymityConfidenceScore,
                proxyHealthScore = sugenoEval.proxyHealthScore,
                aiVerdict = verdict
            )
        }

    fun solveMixedNashStrategy(providers: List<CdnProvider>): Map<CdnProvider, Double> = synchronized(lock) {
        if (providers.isEmpty()) return emptyMap()
        if (providers.size == 1) return mapOf(providers.first() to 1.0)

        // Fictitious Play over empirical payoffs with softmax regularization
        val weights = providers.associateWith { p ->
            val stat = providerStats[p]
            val meanPayoff = stat?.first ?: if (p.isCdn) 0.58 else 0.45
            exp(meanPayoff * 4.5)
        }
        val total = weights.values.sum().coerceAtLeast(1e-9)
        return weights.mapValues { (_, w) -> w / total }
    }

    fun prioritizeTargets(targets: List<ScanTarget>): List<ScanTarget> = synchronized(lock) {
        if (targets.size <= 1) return targets
        val distinctProviders = targets.map { it.provider }.distinct()
        val nashStrategy = solveMixedNashStrategy(distinctProviders)
        return targets.sortedWith(
            compareByDescending<ScanTarget> { target ->
                val stratWeight = nashStrategy[target.provider] ?: 0.2
                val portBoost = when (target.port) {
                    443, 8443 -> 0.12
                    2053, 2083, 1080 -> 0.09
                    else -> 0.02
                }
                val protoBoost = if (target.proxyProtocol.equals("SOCKS5", ignoreCase = true)) 0.05 else 0.03
                stratWeight + portBoost + protoBoost
            }
        )
    }

    fun snapshotSummary(): IntelligenceSummary = synchronized(lock) {
        if (samplesLearned == 0) return IntelligenceSummary()
        val bestProvider = providerStats.maxByOrNull { it.value.first }?.key?.displayName ?: "--"
        IntelligenceSummary(
            samplesLearned = samplesLearned,
            avgAiScore = (cumulativeAiScore / samplesLearned).toInt(),
            avgFuzzyMembership = (cumulativeFuzzy / samplesLearned).toFloat(),
            nashBestProvider = bestProvider,
            avgAnonymityConfidence = (cumulativeAnon / samplesLearned).toInt(),
            avgProxyHealth = (cumulativeHealth / samplesLearned).toInt(),
            mfTuningEpochs = sugenoFis.tuningEpochs
        )
    }

    private fun extractFeatureVector(telemetry: ProbeTelemetry, timeoutMs: Int): DoubleArray {
        val safeTimeout = timeoutMs.coerceAtLeast(500).toDouble()
        val ping = (telemetry.httpsPingMs ?: telemetry.tcpPingMs)?.toDouble() ?: safeTimeout
        val latencyNorm = if (telemetry.tcpPingMs == null && telemetry.httpsPingMs == null) {
            0.0
        } else {
            val linear = (1.0 - (ping / safeTimeout)).coerceIn(0.0, 1.0)
            val logScore = (ln((safeTimeout / ping.coerceAtLeast(20.0)).coerceAtLeast(1.0)) /
                ln((safeTimeout / 20.0).coerceAtLeast(2.0))).coerceIn(0.0, 1.0)
            (0.50 * linear + 0.50 * logScore).coerceIn(0.0, 1.0)
        }
        val tlsNorm = when {
            telemetry.certValid && telemetry.tlsVersion.contains("1.3", ignoreCase = true) -> 1.0
            telemetry.certValid -> 0.85
            telemetry.proxyProtocol.equals("SOCKS5", ignoreCase = true) && telemetry.isClean -> 0.78
            else -> 0.15
        }
        val protoNorm = if (telemetry.proxyProtocol.equals("SOCKS5", ignoreCase = true)) 0.90 else 0.75
        val cdnPrior = if (telemetry.provider.isCdn) 0.85 else 0.65
        val cleanFlag = if (telemetry.isClean) 1.0 else 0.0
        return doubleArrayOf(latencyNorm, tlsNorm, protoNorm, cdnPrior, cleanFlag)
    }
}
