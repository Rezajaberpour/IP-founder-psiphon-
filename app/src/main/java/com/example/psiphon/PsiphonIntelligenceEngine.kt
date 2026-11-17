package com.example.psiphon

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

data class IntelligenceSummary(
    val samplesLearned: Int = 0,
    val avgAiScore: Int = 0,
    val avgFuzzyMembership: Float = 0f,
    val avgAnonymityConfidence: Int = 0,
    val avgProxyHealth: Int = 0,
    val mfTuningEpochs: Int = 0,
    val nashBestProvider: String = "Akamai",
    val nashEquilibriumWeight: Float = 0f
)

data class HybridEvaluation(
    val aiScore: Int,
    val fuzzyMembership: Float,
    val nnPrediction: Float,
    val nashPayoff: Float,
    val anonymityConfidenceScore: Int,
    val proxyHealthScore: Int,
    val aiVerdict: String
)

/**
 * Hybrid Neuro-Fuzzy + Artificial Neural Network (ANN) + Game Theory Intelligence Engine:
 *
 * 1. ANFIS (5-Layer Adaptive Neuro-Fuzzy Inference System):
 *    - Fuzzifies latency, handshake integrity, overhead jitter, and subnet/provider priors
 *      via Gaussian membership functions and Sugeno consequent polynomials.
 * 2. Online-Adaptive MLP ANN (6 -> 8 -> 4 -> 1):
 *    - Hidden Layer 1: LeakyReLU, Hidden Layer 2: Tanh, Output: Sigmoid clean probability.
 *    - Updates weights live via online gradient backpropagation after every real probe.
 * 3. Game Theory Solver (Attacker-Defender Matrix + Double Oracle + Fictitious Play Nash Equilibrium):
 *    - Models strategic interaction between Scanner route selection and Censor threats
 *      (SNI/TLS Inspection, Packet Throttling, Port Blackholing, Deep Probe).
 *    - Solves mixed Nash Equilibrium via iterative Fictitious Play and eliminates strictly
 *      dominated strategies (Double Oracle pruning).
 * 4. Choquet Fuzzy Integral Fusion:
 *    - Non-linearly fuses (ANFIS, ANN, Nash Strategic Payoff) under a lambda-fuzzy measure.
 */
class PsiphonIntelligenceEngine {

    private val lock = Any()

    // --- Layer A: Online MLP ANN (6 -> 8 -> 4 -> 1) ---
    private val inDim = 6
    private val h1Dim = 8
    private val h2Dim = 4

    // Pre-calibrated domain weights (Latency, Overhead, TLS Integrity, Reachability, Provider Prior, Subnet Prior)
    private val w1 = Array(h1Dim) { i ->
        DoubleArray(inDim) { j ->
            val base = when (j) {
                0 -> if (i % 2 == 0) 0.95 else 0.55 // Low latency reward
                1 -> if (i < 4) 0.72 else 0.40      // Low HTTPS overhead
                2 -> 1.15                           // TLS & X.509 integrity
                3 -> 1.25                           // HTTP/SOCKS reachability
                4 -> 0.50                           // Provider empirical prior
                else -> 0.60                        // /24 Subnet reputation
            }
            val signMod = if ((i + j) % 5 == 0) -0.18 else 1.0
            base * signMod * 0.45
        }
    }
    private val b1 = DoubleArray(h1Dim) { -0.25 }

    private val w2 = Array(h2Dim) { i ->
        DoubleArray(h1Dim) { j ->
            val parity = if ((i + j) % 4 == 0) -0.25 else 0.55
            0.35 * parity
        }
    }
    private val b2 = DoubleArray(h2Dim) { -0.15 }

    private val w3 = DoubleArray(h2Dim) { i -> 0.58 + (i * 0.08) }
    private var b3 = -0.85

    // --- Layer B: ANFIS Sugeno Consequent Bias Terms (6 fuzzy rules) ---
    private val anfisRuleWeights = doubleArrayOf(1.10, 0.92, 0.74, 0.52, 0.35, 0.14)
    private val anfisRuleBias = doubleArrayOf(0.94, 0.80, 0.64, 0.46, 0.28, 0.10)

    // --- Layer C: Empirical Subnet & Provider Reputation + Game Theory History ---
    private data class StatCounter(
        var total: Int = 0,
        var clean: Int = 0,
        var avgLatencyMs: Double = 450.0,
        var avgAiScore: Double = 55.0
    ) {
        val empiricalRate: Double
            get() = if (total == 0) 0.55 else (clean + 1.0) / (total + 2.0) // Laplace smoothed
    }

    val sugenoHealthFis = SugenoProxyHealthFis()

    private val providerStats = mutableMapOf<CdnProvider, StatCounter>()
    private val subnetStats = mutableMapOf<String, StatCounter>()
    private var learnedSamples = 0
    private var cumulativeAiScore = 0L
    private var cumulativeFuzzy = 0.0
    private var cumulativeAnonConfidence = 0L
    private var cumulativeProxyHealth = 0L
    private var lastNashBestProvider = CdnProvider.AKAMAI
    private var lastNashWeight = 0.5f

    /**
     * Evaluates a completed probe using ANFIS + ANN + Game Theory Mixed Nash Equilibrium
     * and Choquet Fuzzy Integral Fusion, then performs online backpropagation adaptation.
     */
    fun evaluateAndLearn(telemetry: ProbeTelemetry, timeoutMs: Int = 3000): ProbeTelemetry {
        val eval = synchronized(lock) {
            val subnetKey = extractSubnetKey(telemetry.ip)
            val provStat = providerStats.getOrPut(telemetry.provider) { StatCounter() }
            val subStat = subnetStats.getOrPut(subnetKey) { StatCounter() }

            val features = extractFeatureVector(
                telemetry = telemetry,
                timeoutMs = timeoutMs,
                providerPrior = provStat.empiricalRate,
                subnetPrior = subStat.empiricalRate
            )

            // 1. Sugeno FIS Proxy Health + ANN-tuned MF Anonymity Confidence Score
            val sugenoEval = sugenoHealthFis.evaluateAndTune(telemetry, timeoutMs)

            // 2. ANFIS 5-layer Sugeno Neuro-Fuzzy inference
            val anfisScore = computeAnfisScore(features)
            val fuzzyScore = clamp01(0.55 * anfisScore + 0.45 * sugenoEval.proxyHealth)

            // 3. ANN Forward pass (6 -> 8 -> 4 -> 1)
            val forward = forwardAnn(features)
            val nnProb = forward.output

            // 4. Game Theory Payoff Matrix + Double Oracle + Fictitious Play Nash Equilibrium
            val nashPayoff = computeStrategicNashPayoff(
                telemetry = telemetry,
                features = features,
                provStat = provStat
            )

            // 5. Choquet Fuzzy Integral Non-linear Fusion (lambda = -0.20)
            val fused = choquetFuzzyIntegral(
                fuzzy = fuzzyScore,
                ann = nnProb,
                nash = nashPayoff,
                isClean = telemetry.isClean
            )
            val finalScore = (fused * 100.0).toInt().coerceIn(0, 100)
            val verdict = when {
                !telemetry.isClean -> "RISKY"
                finalScore >= 82 -> "OPTIMAL"
                finalScore >= 64 -> "RESILIENT"
                finalScore >= 42 -> "MODERATE"
                else -> "RISKY"
            }

            // 6. Online Learning: Update ANN (Backprop) + ANFIS Consequent + Empirical Stats
            val targetLabel = computeTargetSignal(telemetry, timeoutMs)
            backpropagateAnn(features, forward, targetLabel, learningRate = 0.04)
            adaptAnfisRules(features, targetLabel, learningRate = 0.02)

            provStat.total += 1
            if (telemetry.isClean) provStat.clean += 1
            val effectiveLatency = (telemetry.httpsPingMs ?: telemetry.tcpPingMs ?: timeoutMs.toLong()).toDouble()
            provStat.avgLatencyMs = 0.8 * provStat.avgLatencyMs + 0.2 * effectiveLatency
            provStat.avgAiScore = 0.8 * provStat.avgAiScore + 0.2 * finalScore

            subStat.total += 1
            if (telemetry.isClean) subStat.clean += 1
            subStat.avgLatencyMs = 0.75 * subStat.avgLatencyMs + 0.25 * effectiveLatency
            subStat.avgAiScore = 0.75 * subStat.avgAiScore + 0.25 * finalScore

            learnedSamples += 1
            cumulativeAiScore += finalScore
            cumulativeFuzzy += fuzzyScore
            cumulativeAnonConfidence += sugenoEval.anonymityConfidenceScore
            cumulativeProxyHealth += sugenoEval.proxyHealthScore
            recomputeGlobalNashBestProvider()

            HybridEvaluation(
                aiScore = finalScore,
                fuzzyMembership = fuzzyScore.toFloat().coerceIn(0f, 1f),
                nnPrediction = nnProb.toFloat().coerceIn(0f, 1f),
                nashPayoff = nashPayoff.toFloat().coerceIn(0f, 1f),
                anonymityConfidenceScore = sugenoEval.anonymityConfidenceScore,
                proxyHealthScore = sugenoEval.proxyHealthScore,
                aiVerdict = verdict
            )
        }

        return telemetry.copy(
            aiScore = eval.aiScore,
            fuzzyMembership = eval.fuzzyMembership,
            nnPrediction = eval.nnPrediction,
            nashPayoff = eval.nashPayoff,
            anonymityConfidenceScore = eval.anonymityConfidenceScore,
            proxyHealthScore = eval.proxyHealthScore,
            aiVerdict = eval.aiVerdict
        )
    }

    /**
     * Uses the current Game Theory Mixed Nash Equilibrium strategy + ANN prior + subnet reputation
     * to intelligently reorder candidate ScanTargets BEFORE probing starts, placing optimal routes first
     * while preserving bounded exploration across all selected providers.
     */
    fun prioritizeTargets(targets: List<ScanTarget>): List<ScanTarget> {
        if (targets.size <= 1) return targets
        return synchronized(lock) {
            val activeProviders = targets.map { it.provider }.distinct()
            val nashStrategy = solveMixedNashStrategy(activeProviders)

            targets.mapIndexed { index, target ->
                val provWeight = nashStrategy[target.provider] ?: (1.0 / activeProviders.size.coerceAtLeast(1))
                val subStat = subnetStats[extractSubnetKey(target.ip)]
                val provStat = providerStats[target.provider]

                val priorFeatures = doubleArrayOf(
                    clamp01(1.0 - ((provStat?.avgLatencyMs ?: 350.0) / 3000.0)),
                    0.75,
                    if (target.port in setOf(443, 8443, 2053, 2083)) 0.90 else 0.60,
                    if (target.proxyProtocol == "SOCKS5") 0.75 else 0.85,
                    provStat?.empiricalRate ?: 0.58,
                    subStat?.empiricalRate ?: 0.56
                )
                val nnPrior = forwardAnn(priorFeatures).output
                val fuzzyPrior = computeAnfisScore(priorFeatures)

                // Epsilon-Nash strategic exploration bonus keeps diverse providers early in schedule
                val diversityDecay = 1.0 / (1.0 + 0.04 * (index % 7))
                val priority = 0.40 * nnPrior + 0.30 * fuzzyPrior + 0.25 * provWeight + 0.05 * diversityDecay
                target to priority
            }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }

    fun snapshotSummary(): IntelligenceSummary = synchronized(lock) {
        IntelligenceSummary(
            samplesLearned = learnedSamples,
            avgAiScore = if (learnedSamples == 0) 0 else (cumulativeAiScore / learnedSamples).toInt(),
            avgFuzzyMembership = if (learnedSamples == 0) 0f else (cumulativeFuzzy / learnedSamples).toFloat().coerceIn(0f, 1f),
            avgAnonymityConfidence = if (learnedSamples == 0) 0 else (cumulativeAnonConfidence / learnedSamples).toInt(),
            avgProxyHealth = if (learnedSamples == 0) 0 else (cumulativeProxyHealth / learnedSamples).toInt(),
            mfTuningEpochs = sugenoHealthFis.tuningEpochs,
            nashBestProvider = lastNashBestProvider.displayName,
            nashEquilibriumWeight = lastNashWeight
        )
    }

    fun resetSessionStats() {
        synchronized(lock) {
            learnedSamples = 0
            cumulativeAiScore = 0L
            cumulativeFuzzy = 0.0
            cumulativeAnonConfidence = 0L
            cumulativeProxyHealth = 0L
        }
    }

    // =========================================================================
    // 1. ANFIS (5-Layer Sugeno Neuro-Fuzzy Inference System)
    // =========================================================================
    private fun gaussianMf(x: Double, center: Double, sigma: Double): Double {
        val diff = x - center
        return exp(-(diff * diff) / (2.0 * sigma * sigma)).coerceIn(1e-4, 1.0)
    }

    private fun computeAnfisScore(features: DoubleArray): Double {
        val lat = features[0]      // 1.0 = ultra fast, 0.0 = slow/timeout
        val overhead = features[1] // 1.0 = minimal HTTPS overhead
        val tls = features[2]      // 1.0 = valid TLS 1.3 + X.509
        val reach = features[3]    // 1.0 = clean tunnel / SOCKS5 ok
        val prior = features[4]    // provider prior

        // Layer 1: Fuzzification
        val muFast = gaussianMf(lat, 0.92, 0.24)
        val muMedLat = gaussianMf(lat, 0.55, 0.25)
        val muSlow = gaussianMf(lat, 0.15, 0.22)

        val muStrongTls = gaussianMf(tls, 0.95, 0.24)
        val muWeakTls = gaussianMf(tls, 0.20, 0.28)

        val muStable = gaussianMf(overhead, 0.88, 0.28)
        val muReach = gaussianMf(reach, 0.95, 0.22)
        val muTrusted = gaussianMf(prior, 0.80, 0.30)

        // Layer 2: T-Norm Rule Firing Strengths
        val firing = doubleArrayOf(
            muFast * muStrongTls * muReach * muStable, // Rule 1: Fast + Strong TLS + Reachable + Stable
            muMedLat * muStrongTls * muReach,          // Rule 2: Medium Latency + Strong TLS + Reachable
            muFast * muReach * muTrusted,              // Rule 3: Fast + Reachable (e.g. SOCKS5 / HTTP) + Trusted
            muMedLat * muStable * muTrusted,           // Rule 4: Balanced Medium
            muSlow * muReach,                          // Rule 5: Slow but reachable
            muWeakTls * (1.0 - muReach * 0.5)          // Rule 6: Weak TLS / Blocked
        )

        // Layer 3 & 4: Normalize weights and compute Sugeno consequent
        val sumFiring = firing.sum().coerceAtLeast(1e-6)
        var out = 0.0
        for (k in firing.indices) {
            val wBar = firing[k] / sumFiring
            val consequent = clamp01(
                0.45 * anfisRuleBias[k] +
                    0.30 * anfisRuleWeights[k] * (0.4 * lat + 0.35 * reach + 0.25 * tls) +
                    0.25 * overhead
            )
            out += wBar * consequent
        }
        return clamp01(out)
    }

    private fun adaptAnfisRules(features: DoubleArray, target: Double, learningRate: Double) {
        val current = computeAnfisScore(features)
        val error = target - current
        // Gradient adjustment on Sugeno consequent biases scaled by input quality
        val scale = 0.5 * features[0] + 0.5 * features[3]
        for (k in anfisRuleBias.indices) {
            val ruleSensitivity = (anfisRuleWeights.size - k) / anfisRuleWeights.size.toDouble()
            anfisRuleBias[k] = clamp01(anfisRuleBias[k] + learningRate * error * ruleSensitivity * (0.4 + 0.6 * scale))
        }
    }

    // =========================================================================
    // 2. MLP Artificial Neural Network (6 -> 8 -> 4 -> 1) + Backpropagation
    // =========================================================================
    private data class ForwardCache(
        val h1Pre: DoubleArray,
        val h1: DoubleArray,
        val h2Pre: DoubleArray,
        val h2: DoubleArray,
        val output: Double
    )

    private fun leakyRelu(z: Double): Double = if (z >= 0.0) z else 0.05 * z
    private fun leakyReluDeriv(z: Double): Double = if (z >= 0.0) 1.0 else 0.05
    private fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z.coerceIn(-12.0, 12.0)))

    private fun forwardAnn(x: DoubleArray): ForwardCache {
        val h1Pre = DoubleArray(h1Dim)
        val h1 = DoubleArray(h1Dim)
        for (i in 0 until h1Dim) {
            var sum = b1[i]
            for (j in 0 until inDim) sum += w1[i][j] * x[j]
            h1Pre[i] = sum
            h1[i] = leakyRelu(sum)
        }

        val h2Pre = DoubleArray(h2Dim)
        val h2 = DoubleArray(h2Dim)
        for (i in 0 until h2Dim) {
            var sum = b2[i]
            for (j in 0 until h1Dim) sum += w2[i][j] * h1[j]
            h2Pre[i] = sum
            h2[i] = tanh(sum.coerceIn(-8.0, 8.0))
        }

        var zOut = b3
        for (i in 0 until h2Dim) zOut += w3[i] * h2[i]
        val yHat = sigmoid(zOut)

        return ForwardCache(h1Pre, h1, h2Pre, h2, yHat)
    }

    private fun backpropagateAnn(
        x: DoubleArray,
        cache: ForwardCache,
        target: Double,
        learningRate: Double
    ) {
        // Binary Cross Entropy gradient wrt output logit: dL/dzOut = yHat - target
        val dOut = (cache.output - target).coerceIn(-1.0, 1.0)

        val dh2 = DoubleArray(h2Dim)
        for (i in 0 until h2Dim) {
            dh2[i] = dOut * w3[i]
            w3[i] = (w3[i] - learningRate * dOut * cache.h2[i]).coerceIn(-4.0, 4.0)
        }
        b3 = (b3 - learningRate * dOut).coerceIn(-4.0, 4.0)

        val dh1 = DoubleArray(h1Dim)
        for (i in 0 until h2Dim) {
            val dTanh = 1.0 - cache.h2[i] * cache.h2[i]
            val delta2 = (dh2[i] * dTanh).coerceIn(-1.0, 1.0)
            for (j in 0 until h1Dim) {
                dh1[j] += delta2 * w2[i][j]
                w2[i][j] = (w2[i][j] - learningRate * delta2 * cache.h1[j]).coerceIn(-4.0, 4.0)
            }
            b2[i] = (b2[i] - learningRate * delta2).coerceIn(-4.0, 4.0)
        }

        for (i in 0 until h1Dim) {
            val delta1 = (dh1[i] * leakyReluDeriv(cache.h1Pre[i])).coerceIn(-1.0, 1.0)
            for (j in 0 until inDim) {
                w1[i][j] = (w1[i][j] - learningRate * delta1 * x[j]).coerceIn(-4.0, 4.0)
            }
            b1[i] = (b1[i] - learningRate * delta1).coerceIn(-4.0, 4.0)
        }
    }

    // =========================================================================
    // 3. Game Theory: Fuzzy Payoff Matrix + Double Oracle + Fictitious Play Nash
    // =========================================================================
    private fun computeStrategicNashPayoff(
        telemetry: ProbeTelemetry,
        features: DoubleArray,
        provStat: StatCounter
    ): Double {
        // Defender utility against 4 Fuzzy Censor Strategies:
        // [0] SNI/TLS Deep Inspection, [1] Latency/Bandwidth Throttling,
        // [2] IP/Port Blackholing, [3] Active Tunnel Replay/Probing
        val tlsDefense = features[2]
        val speedDefense = features[0]
        val portDiversity = when (telemetry.targetPort) {
            443 -> 0.72
            8443, 2053, 2083 -> 0.88 // Alternate HTTPS ports evade simple 443 saturation
            1080 -> 0.78
            else -> 0.62
        }
        val methodResilience = when (telemetry.method) {
            PsiphonMethod.FRONTED_MEEK_OSSH -> 0.92
            PsiphonMethod.QUIC_OSSH -> 0.86
            PsiphonMethod.OSSH -> 0.80
            PsiphonMethod.UNFRONTED_MEEK_OSSH -> 0.74
            PsiphonMethod.SSH -> 0.65
        }
        val protocolBonus = if (telemetry.proxyProtocol == "SOCKS5") 0.84 else 0.80

        val censorStrategies = doubleArrayOf(
            // Against SNI/TLS inspection: domain fronting & valid TLS win
            clamp01(0.50 * tlsDefense + 0.35 * methodResilience + 0.15 * features[3]),
            // Against Throttling: fast TCP/HTTPS latency wins
            clamp01(0.65 * speedDefense + 0.25 * features[1] + 0.10 * provStat.empiricalRate),
            // Against Port/IP blackholing: clean subnet prior + non-standard port diversity win
            clamp01(0.45 * features[5] + 0.35 * portDiversity + 0.20 * features[3]),
            // Against Active probing: obfuscated method + valid HTTPS response win
            clamp01(0.45 * methodResilience + 0.35 * protocolBonus + 0.20 * features[3])
        )

        // Minimax / Hurwicz fuzzy equilibrium payoff against worst-case adversary mix (alpha = 0.65)
        val worstCase = censorStrategies.minOrNull() ?: 0.0
        val avgPayoff = censorStrategies.average()
        val hurwiczAlpha = 0.35 // Pessimistic/defensive weighting toward Nash security level
        return clamp01(hurwiczAlpha * avgPayoff + (1.0 - hurwiczAlpha) * worstCase)
    }

    /**
     * Runs Double Oracle dominance pruning + iterative Fictitious Play on a provider vs censor
     * payoff matrix to compute the optimal mixed Nash Equilibrium selection probabilities.
     */
    fun solveMixedNashStrategy(providers: List<CdnProvider>): Map<CdnProvider, Double> {
        if (providers.isEmpty()) return emptyMap()
        if (providers.size == 1) return mapOf(providers.first() to 1.0)

        val n = providers.size
        val m = 4 // 4 censor attack vectors
        val payoffMatrix = Array(n) { r ->
            val p = providers[r]
            val stat = providerStats[p] ?: StatCounter()
            val cleanRate = stat.empiricalRate
            val speedScore = clamp01(1.0 - (stat.avgLatencyMs / 2500.0))
            doubleArrayOf(
                if (p.isCdn) clamp01(0.55 * cleanRate + 0.45 * 0.88) else clamp01(0.6 * cleanRate + 0.25),
                clamp01(0.5 * speedScore + 0.5 * cleanRate),
                if (p.isProxyListSource) clamp01(0.55 * cleanRate + 0.40) else clamp01(0.5 * cleanRate + 0.35),
                clamp01(0.65 * cleanRate + 0.35 * speedScore)
            )
        }

        // Double Oracle Pruning: remove strategies strictly dominated by another provider across all attack vectors
        val activeIndices = (0 until n).filter { i ->
            val rowI = payoffMatrix[i]
            val dominated = (0 until n).any { j ->
                i != j && (0 until m).all { col -> payoffMatrix[j][col] > rowI[col] + 0.06 }
            }
            !dominated
        }.ifEmpty { (0 until n).toList() }

        // Fictitious Play Equilibrium Convergence (40 iterations)
        val rowCounts = DoubleArray(n) { 0.0 }
        val colCounts = DoubleArray(m) { 1.0 }
        val iterations = 40
        for (t in 1..iterations) {
            // Adversary mixed strategy
            val advSum = colCounts.sum().coerceAtLeast(1.0)
            val advMix = DoubleArray(m) { c -> colCounts[c] / advSum }

            // Defender Best Response among non-dominated Double Oracle candidates
            var bestRow = activeIndices.first()
            var bestVal = Double.NEGATIVE_INFINITY
            for (r in activeIndices) {
                var expectedPayoff = 0.0
                for (c in 0 until m) expectedPayoff += payoffMatrix[r][c] * advMix[c]
                if (expectedPayoff > bestVal) {
                    bestVal = expectedPayoff
                    bestRow = r
                }
            }
            rowCounts[bestRow] += 1.0

            // Adversary Best Response (minimizing Defender expected payoff)
            val defSum = rowCounts.sum().coerceAtLeast(1.0)
            val defMix = DoubleArray(n) { r -> rowCounts[r] / defSum }
            var bestCol = 0
            var minPayoff = Double.POSITIVE_INFINITY
            for (c in 0 until m) {
                var colPayoff = 0.0
                for (r in activeIndices) colPayoff += defMix[r] * payoffMatrix[r][c]
                if (colPayoff < minPayoff) {
                    minPayoff = colPayoff
                    bestCol = c
                }
            }
            colCounts[bestCol] += 1.0
        }

        // Smooth with small Dirichlet floor (5%) to avoid starving untested sources
        val rawSum = rowCounts.sum().coerceAtLeast(1.0)
        val result = linkedMapOf<CdnProvider, Double>()
        var normSum = 0.0
        for (r in 0 until n) {
            val mixed = 0.95 * (rowCounts[r] / rawSum) + 0.05 * (1.0 / n)
            result[providers[r]] = mixed
            normSum += mixed
        }
        return result.mapValues { it.value / normSum }
    }

    private fun recomputeGlobalNashBestProvider() {
        val observed = providerStats.keys.toList()
        if (observed.isEmpty()) return
        val strategy = solveMixedNashStrategy(observed)
        val best = strategy.maxByOrNull { it.value } ?: return
        lastNashBestProvider = best.key
        lastNashWeight = best.value.toFloat().coerceIn(0f, 1f)
    }

    // =========================================================================
    // 4. Choquet Fuzzy Integral Fusion (Non-linear Multi-Expert Aggregation)
    // =========================================================================
    private fun choquetFuzzyIntegral(
        fuzzy: Double,
        ann: Double,
        nash: Double,
        isClean: Boolean
    ): Double {
        if (!isClean) {
            // Failed probes retain diagnostic partial score capped strictly below 35
            return clamp01(0.32 * (0.4 * fuzzy + 0.4 * ann + 0.2 * nash))
        }
        // Individual fuzzy densities g(ANFIS), g(ANN), g(Nash)
        val experts = listOf(
            fuzzy to 0.36,
            ann to 0.38,
            nash to 0.34
        ).sortedBy { it.first } // ascending order h_(1) <= h_(2) <= h_(3)

        // Sugeno lambda-fuzzy measure aggregation (lambda = -0.20 sub-additive synergy damping)
        val lambda = -0.20
        var measure = 0.0
        val setMeasures = DoubleArray(experts.size)
        for (idx in experts.indices.reversed()) {
            val g = experts[idx].second
            measure = measure + g + lambda * measure * g
            setMeasures[idx] = clamp01(measure)
        }
        // Normalize top set measure to 1.0
        val topNorm = setMeasures[0].coerceAtLeast(1e-6)
        var integral = 0.0
        var prevH = 0.0
        for (idx in experts.indices) {
            val h = experts[idx].first
            val muA = (setMeasures[idx] / topNorm).coerceIn(0.0, 1.0)
            integral += (h - prevH) * muA
            prevH = h
        }
        return clamp01(integral)
    }

    // =========================================================================
    // Feature Extraction & Helpers
    // =========================================================================
    private fun extractFeatureVector(
        telemetry: ProbeTelemetry,
        timeoutMs: Int,
        providerPrior: Double,
        subnetPrior: Double
    ): DoubleArray {
        val safeTimeout = timeoutMs.coerceAtLeast(500).toDouble()
        val effectivePing = (telemetry.httpsPingMs ?: telemetry.tcpPingMs)?.toDouble() ?: safeTimeout
        // Blended linear + anchored log normalization so fast pings (20..250ms) score cleanly in 0.72..0.96
        val latencyNorm = if (telemetry.tcpPingMs == null && telemetry.httpsPingMs == null) {
            0.0
        } else {
            val linearPart = clamp01(1.0 - (effectivePing / safeTimeout))
            val logPart = clamp01(
                ln((safeTimeout / effectivePing.coerceAtLeast(20.0)).coerceAtLeast(1.0)) /
                    ln((safeTimeout / 20.0).coerceAtLeast(2.0))
            )
            clamp01(0.50 * linearPart + 0.50 * logPart)
        }

        val tcp = telemetry.tcpPingMs?.toDouble() ?: safeTimeout
        val https = telemetry.httpsPingMs?.toDouble() ?: tcp
        val overheadRatio = if (tcp <= 0.0) 1.0 else (https / tcp).coerceAtLeast(1.0)
        val overheadNorm = if (telemetry.tcpPingMs == null) 0.0 else clamp01(1.0 - ((overheadRatio - 1.0) / 4.0))

        val tlsBonus = when {
            telemetry.certValid && telemetry.tlsVersion.contains("1.3", ignoreCase = true) -> 1.0
            telemetry.certValid -> 0.88
            telemetry.proxyProtocol == "SOCKS5" && telemetry.isClean -> 0.82
            telemetry.tlsVersion == "TCP" && telemetry.isClean -> 0.68
            else -> 0.12
        }

        val reachScore = when {
            telemetry.isClean && telemetry.httpsStatusCode in 200..399 -> 1.0
            telemetry.isClean && telemetry.httpsStatusCode != null -> 0.88
            telemetry.isClean -> 0.82
            telemetry.tcpPingMs != null -> 0.30
            else -> 0.0
        }

        return doubleArrayOf(
            latencyNorm,
            overheadNorm,
            tlsBonus,
            reachScore,
            clamp01(providerPrior),
            clamp01(subnetPrior)
        )
    }

    private fun computeTargetSignal(telemetry: ProbeTelemetry, timeoutMs: Int): Double {
        if (!telemetry.isClean) {
            return if (telemetry.tcpPingMs != null) 0.18 else 0.02
        }
        val ping = (telemetry.httpsPingMs ?: telemetry.tcpPingMs ?: 300L).toDouble()
        val speedFactor = clamp01(1.0 - (ping / timeoutMs.coerceAtLeast(1000).toDouble()))
        val tlsFactor = if (telemetry.certValid) 0.15 else 0.05
        return clamp01(0.68 + 0.22 * speedFactor + tlsFactor)
    }

    private fun extractSubnetKey(ip: String): String {
        val clean = ip.trim().trim('[', ']')
        return if (clean.contains(":")) {
            clean.split(":").take(3).joinToString(":")
        } else {
            clean.split(".").take(3).joinToString(".")
        }
    }

    private fun clamp01(v: Double): Double = min(1.0, max(0.0, if (v.isNaN()) 0.0 else v))
}
