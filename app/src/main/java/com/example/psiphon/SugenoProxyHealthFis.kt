package com.example.psiphon

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

data class TunableGaussianMf(
    var center: Double,
    var sigma: Double
) {
    fun evaluate(x: Double): Double {
        val safeSigma = sigma.coerceAtLeast(0.08)
        val diff = x - center
        return exp(-(diff * diff) / (2.0 * safeSigma * safeSigma)).coerceIn(1e-4, 1.0)
    }
}

data class SugenoHealthEvaluation(
    val proxyHealth: Double,            // 0.0..1.0 Sugeno weighted-average proxy health
    val proxyHealthScore: Int,          // 0..100
    val anonymityConfidence: Double,    // 0.0..1.0 Anonymity Confidence from ANN-tuned MFs
    val anonymityConfidenceScore: Int,  // 0..100 (%)
    val activeRuleWeights: DoubleArray, // Normalized rule firing strengths (w_bar)
    val tuningEpochs: Int
)

/**
 * First-Order Sugeno-Type Fuzzy Inference System (TSK) with ANN-Based Membership Function Tuning.
 *
 * Evaluates proxy health across 4 crisp network & stealth inputs:
 *   x[0] = latencyHealth       (responsiveness vs timeout)
 *   x[1] = tlsStealth          (TLS 1.3 + valid X.509 / SNI fronting)
 *   x[2] = protocolAnonymity   (obfuscated MEEK/QUIC/OSSH/SOCKS5 + non-standard port stealth)
 *   x[3] = transportStability  (low HTTPS overhead + reachable clean status)
 *
 * Online ANN Tuner (4 -> 6 -> 8 MLP + analytical Gaussian MF gradients):
 *   Backpropagates Sugeno inference error through firing strengths and updates both
 *   Gaussian Membership Function centers (c) & widths (sigma) and Sugeno consequent weights.
 */
class SugenoProxyHealthFis {

    private val lock = Any()

    // Tunable Low & High Gaussian Membership Functions for the 4 inputs
    val lowMfs = Array(4) { TunableGaussianMf(center = 0.22, sigma = 0.24) }
    val highMfs = Array(4) {
        when (it) {
            1 -> TunableGaussianMf(center = 0.90, sigma = 0.23) // TLS stealth
            2 -> TunableGaussianMf(center = 0.88, sigma = 0.24) // Protocol anonymity
            else -> TunableGaussianMf(center = 0.86, sigma = 0.24)
        }
    }

    // 5 First-Order Sugeno Consequent Polynomials: f_k(x) = p0 + p1*x0 + p2*x1 + p3*x2 + p4*x3
    // Rule 0: Elite Anonymity & Health (all High)
    // Rule 1: Resilient Stealth (High TLS + High Anonymity + High Stability)
    // Rule 2: Fast Functional Proxy (High Latency + High Stability + Low TLS)
    // Rule 3: Throttled Stealth (Low Latency + High TLS + High Anonymity)
    // Rule 4: Degraded / Compromised (Low TLS + Low Stability)
    private val consequents = arrayOf(
        doubleArrayOf(0.56, 0.11, 0.14, 0.13, 0.10),
        doubleArrayOf(0.46, 0.06, 0.16, 0.15, 0.12),
        doubleArrayOf(0.32, 0.14, 0.05, 0.10, 0.14),
        doubleArrayOf(0.28, 0.05, 0.13, 0.13, 0.08),
        doubleArrayOf(0.03, 0.04, 0.05, 0.05, 0.06)
    )

    // Lightweight 2-Layer ANN Tuner (4 -> 6 -> 8) predicting adaptive MF shift/scale modulation
    private val annHiddenDim = 6
    private val annOutDim = 8 // [dc_high[0..3], dsigma_high[0..3]]
    private val annW1 = Array(annHiddenDim) { i ->
        DoubleArray(4) { j -> if ((i + j) % 2 == 0) 0.28 else -0.16 }
    }
    private val annB1 = DoubleArray(annHiddenDim) { 0.05 }
    private val annW2 = Array(annOutDim) { i ->
        DoubleArray(annHiddenDim) { j -> if ((i + j) % 3 == 0) 0.18 else 0.10 }
    }
    private val annB2 = DoubleArray(annOutDim) { 0.0 }

    var tuningEpochs: Int = 0
        private set
    var cumulativeMfShift: Double = 0.0
        private set

    fun evaluate(telemetry: ProbeTelemetry, timeoutMs: Int = 3000): SugenoHealthEvaluation =
        synchronized(lock) {
            val x = extractHealthFeatures(telemetry, timeoutMs)
            evalInternal(x, telemetry.isClean)
        }

    fun evaluateAndTune(telemetry: ProbeTelemetry, timeoutMs: Int = 3000): SugenoHealthEvaluation =
        synchronized(lock) {
            val x = extractHealthFeatures(telemetry, timeoutMs)
            val initialEval = evalInternal(x, telemetry.isClean)

            val targetHealth = computeTargetHealth(telemetry, x)
            val targetAnon = computeTargetAnonymity(telemetry, x)

            tuneWithAnnAndBackprop(
                x = x,
                predictedHealth = initialEval.proxyHealth,
                predictedAnon = initialEval.anonymityConfidence,
                targetHealth = targetHealth,
                targetAnon = targetAnon,
                learningRate = 0.045
            )

            // Re-evaluate with freshly tuned membership functions
            evalInternal(x, telemetry.isClean)
        }

    fun getMembershipSnapshot(): List<Pair<Double, Double>> = synchronized(lock) {
        highMfs.map { it.center to it.sigma }
    }

    private fun evalInternal(x: DoubleArray, isClean: Boolean): SugenoHealthEvaluation {
        val muLow = DoubleArray(4) { j -> lowMfs[j].evaluate(x[j]) }
        val muHigh = DoubleArray(4) { j -> highMfs[j].evaluate(x[j]) }

        // Sugeno Rule Base (Product T-Norm)
        val firing = doubleArrayOf(
            muHigh[0] * muHigh[1] * muHigh[2] * muHigh[3], // R0: Elite Health & Anonymity
            muHigh[1] * muHigh[2] * muHigh[3],             // R1: Resilient Stealth Tunnel
            muHigh[0] * muHigh[3] * muLow[1],              // R2: Fast Plain/SOCKS Proxy
            muLow[0] * muHigh[1] * muHigh[2],              // R3: High-Latency Stealth
            muLow[1] * muLow[3]                            // R4: Weak / Compromised
        )

        val sumW = firing.sum().coerceAtLeast(1e-6)
        val wBar = DoubleArray(firing.size) { k -> firing[k] / sumW }

        var rawHealth = 0.0
        for (k in firing.indices) {
            val fk = clamp01(
                consequents[k][0] +
                    consequents[k][1] * x[0] +
                    consequents[k][2] * x[1] +
                    consequents[k][3] * x[2] +
                    consequents[k][4] * x[3]
            )
            rawHealth += wBar[k] * fk
        }
        val proxyHealth = if (isClean) clamp01(rawHealth) else clamp01(rawHealth * 0.36)

        // Anonymity Confidence Score is derived directly from ANN-tuned TLS & Protocol Stealth MFs
        // combined with the normalized Sugeno stealth rule firing weights (R0 + R1 + 0.65*R3)
        val stealthRuleSupport = clamp01(wBar[0] + wBar[1] + 0.65 * wBar[3] + 0.30 * wBar[2])
        val mfStealthFusion = clamp01(
            0.38 * muHigh[1] + // TLS & Cert membership degree
                0.37 * muHigh[2] + // Protocol & Port Obfuscation membership degree
                0.25 * muHigh[3]   // Transport Stability membership degree
        )
        val rawAnon = clamp01(0.55 * mfStealthFusion + 0.30 * stealthRuleSupport + 0.15 * proxyHealth)
        val anonymityConfidence = if (isClean) rawAnon else clamp01(rawAnon * 0.34)

        return SugenoHealthEvaluation(
            proxyHealth = proxyHealth,
            proxyHealthScore = (proxyHealth * 100.0).toInt().coerceIn(0, 100),
            anonymityConfidence = anonymityConfidence,
            anonymityConfidenceScore = (anonymityConfidence * 100.0).toInt().coerceIn(0, 100),
            activeRuleWeights = wBar,
            tuningEpochs = tuningEpochs
        )
    }

    /**
     * Combines exact analytical gradients of Gaussian MFs with a 2-layer ANN Tuner MLP
     * to tune membership function centers (c) and widths (sigma) online.
     */
    private fun tuneWithAnnAndBackprop(
        x: DoubleArray,
        predictedHealth: Double,
        predictedAnon: Double,
        targetHealth: Double,
        targetAnon: Double,
        learningRate: Double
    ) {
        val errorHealth = predictedHealth - targetHealth
        val errorAnon = predictedAnon - targetAnon
        val combinedErr = 0.45 * errorHealth + 0.55 * errorAnon

        // 1. Forward pass of ANN MF Tuner (4 -> 6 -> 8)
        val h = DoubleArray(annHiddenDim)
        for (i in 0 until annHiddenDim) {
            var s = annB1[i]
            for (j in 0 until 4) s += annW1[i][j] * x[j]
            h[i] = tanh(s.coerceIn(-6.0, 6.0))
        }
        val annDeltaRaw = DoubleArray(annOutDim)
        for (k in 0 until annOutDim) {
            var s = annB2[k]
            for (i in 0 until annHiddenDim) s += annW2[k][i] * h[i]
            annDeltaRaw[k] = tanh(s.coerceIn(-4.0, 4.0))
        }

        // 2. Analytical Gaussian MF gradients + ANN-modulated updates
        val muLow = DoubleArray(4) { j -> lowMfs[j].evaluate(x[j]) }
        val muHigh = DoubleArray(4) { j -> highMfs[j].evaluate(x[j]) }

        val firing = doubleArrayOf(
            muHigh[0] * muHigh[1] * muHigh[2] * muHigh[3],
            muHigh[1] * muHigh[2] * muHigh[3],
            muHigh[0] * muHigh[3] * muLow[1],
            muLow[0] * muHigh[1] * muHigh[2],
            muLow[1] * muLow[3]
        )
        val sumW = firing.sum().coerceAtLeast(1e-6)
        val wBar = DoubleArray(firing.size) { k -> firing[k] / sumW }

        val ruleOutputs = DoubleArray(firing.size) { k ->
            clamp01(
                consequents[k][0] +
                    consequents[k][1] * x[0] +
                    consequents[k][2] * x[1] +
                    consequents[k][3] * x[2] +
                    consequents[k][4] * x[3]
            )
        }

        var stepShift = 0.0
        for (j in 0 until 4) {
            // dL/d(muHigh[j]) from Sugeno normalized rule weights
            val dHighFromRules = when (j) {
                0 -> (firing[0] / muHigh[0].coerceAtLeast(1e-4)) * (ruleOutputs[0] - predictedHealth) / sumW
                1 -> ((firing[0] + firing[1] + firing[3]) / muHigh[1].coerceAtLeast(1e-4)) *
                    (0.5 * (ruleOutputs[0] + ruleOutputs[1]) - predictedHealth) / sumW
                2 -> ((firing[0] + firing[1] + firing[3]) / muHigh[2].coerceAtLeast(1e-4)) *
                    (0.5 * (ruleOutputs[0] + ruleOutputs[1]) - predictedHealth) / sumW
                else -> ((firing[0] + firing[1] + firing[2]) / muHigh[3].coerceAtLeast(1e-4)) *
                    (0.5 * (ruleOutputs[0] + ruleOutputs[1]) - predictedHealth) / sumW
            }

            // Plus direct anonymity sensitivity gradient on High MFs
            val anonWeight = when (j) {
                1 -> 0.38
                2 -> 0.37
                3 -> 0.25
                else -> 0.12
            }
            val gradMuHigh = combinedErr * (dHighFromRules + anonWeight)

            val mfH = highMfs[j]
            val sigH = mfH.sigma.coerceAtLeast(0.10)
            val dMuDcH = muHigh[j] * (x[j] - mfH.center) / (sigH * sigH)
            val dMuDsH = muHigh[j] * ((x[j] - mfH.center) * (x[j] - mfH.center)) / (sigH * sigH * sigH)

            // ANN modulation scale keeps MF adaptation bounded & feature-aware
            val annModC = 1.0 + 0.35 * annDeltaRaw[j]
            val annModS = 1.0 + 0.35 * annDeltaRaw[4 + j]

            val deltaCH = (learningRate * gradMuHigh * dMuDcH * annModC).coerceIn(-0.025, 0.025)
            val deltaSH = (learningRate * gradMuHigh * dMuDsH * annModS).coerceIn(-0.020, 0.020)

            val oldC = mfH.center
            val oldS = mfH.sigma
            mfH.center = (mfH.center - deltaCH).coerceIn(0.65, 0.98)
            mfH.sigma = (mfH.sigma - deltaSH).coerceIn(0.14, 0.42)
            stepShift += abs(mfH.center - oldC) + abs(mfH.sigma - oldS)

            // Also gently tune Low MF centers & widths
            val mfL = lowMfs[j]
            val sigL = mfL.sigma.coerceAtLeast(0.10)
            val dMuDcL = muLow[j] * (x[j] - mfL.center) / (sigL * sigL)
            val dMuDsL = muLow[j] * ((x[j] - mfL.center) * (x[j] - mfL.center)) / (sigL * sigL * sigL)
            val gradMuLow = -combinedErr * 0.22
            val deltaCL = (learningRate * gradMuLow * dMuDcL).coerceIn(-0.02, 0.02)
            val deltaSL = (learningRate * gradMuLow * dMuDsL).coerceIn(-0.015, 0.015)
            mfL.center = (mfL.center - deltaCL).coerceIn(0.05, 0.40)
            mfL.sigma = (mfL.sigma - deltaSL).coerceIn(0.14, 0.42)
        }

        // 3. Update Sugeno Consequent Polynomial Parameters
        for (k in consequents.indices) {
            val lrCon = learningRate * 0.6 * wBar[k]
            consequents[k][0] = clamp01(consequents[k][0] - lrCon * errorHealth)
            for (j in 0 until 4) {
                consequents[k][j + 1] = (consequents[k][j + 1] - lrCon * errorHealth * x[j]).coerceIn(0.01, 0.45)
            }
        }

        // 4. Backpropagate MSE gradient into the 2-Layer ANN Tuner weights
        val dh = DoubleArray(annHiddenDim)
        for (k in 0 until annOutDim) {
            val gradOut = (combinedErr * (1.0 - annDeltaRaw[k] * annDeltaRaw[k])).coerceIn(-0.5, 0.5)
            for (i in 0 until annHiddenDim) {
                dh[i] += gradOut * annW2[k][i]
                annW2[k][i] = (annW2[k][i] - learningRate * 0.5 * gradOut * h[i]).coerceIn(-2.0, 2.0)
            }
            annB2[k] = (annB2[k] - learningRate * 0.5 * gradOut).coerceIn(-2.0, 2.0)
        }
        for (i in 0 until annHiddenDim) {
            val gradH = (dh[i] * (1.0 - h[i] * h[i])).coerceIn(-0.5, 0.5)
            for (j in 0 until 4) {
                annW1[i][j] = (annW1[i][j] - learningRate * 0.5 * gradH * x[j]).coerceIn(-2.0, 2.0)
            }
            annB1[i] = (annB1[i] - learningRate * 0.5 * gradH).coerceIn(-2.0, 2.0)
        }

        tuningEpochs += 1
        cumulativeMfShift += stepShift
    }

    fun extractHealthFeatures(telemetry: ProbeTelemetry, timeoutMs: Int): DoubleArray {
        val safeTimeout = timeoutMs.coerceAtLeast(500).toDouble()
        val ping = (telemetry.httpsPingMs ?: telemetry.tcpPingMs)?.toDouble() ?: safeTimeout
        val latencyHealth = if (telemetry.tcpPingMs == null && telemetry.httpsPingMs == null) {
            0.0
        } else {
            val linear = clamp01(1.0 - (ping / safeTimeout))
            val logScore = clamp01(
                ln((safeTimeout / ping.coerceAtLeast(20.0)).coerceAtLeast(1.0)) /
                    ln((safeTimeout / 20.0).coerceAtLeast(2.0))
            )
            clamp01(0.50 * linear + 0.50 * logScore)
        }

        val tlsStealth = when {
            telemetry.certValid && telemetry.tlsVersion.contains("1.3", ignoreCase = true) -> 1.0
            telemetry.certValid && telemetry.tlsVersion.contains("1.2", ignoreCase = true) -> 0.88
            telemetry.certValid -> 0.82
            telemetry.proxyProtocol.equals("SOCKS5", ignoreCase = true) && telemetry.isClean -> 0.78
            telemetry.tlsVersion == "TCP" && telemetry.isClean -> 0.55
            else -> 0.10
        }

        val methodStealth = when (telemetry.method) {
            PsiphonMethod.FRONTED_MEEK_OSSH -> 0.94
            PsiphonMethod.QUIC_OSSH -> 0.88
            PsiphonMethod.OSSH -> 0.82
            PsiphonMethod.UNFRONTED_MEEK_OSSH -> 0.74
            PsiphonMethod.SSH -> 0.64
        }
        val portStealth = when (telemetry.targetPort) {
            8443, 2053, 2083, 2087, 2096 -> 0.94
            443 -> 0.86
            1080 -> 0.82
            8080, 3128 -> 0.65
            80 -> 0.42
            else -> 0.70
        }
        val protoBoost = if (telemetry.proxyProtocol.equals("SOCKS5", ignoreCase = true)) 0.08 else 0.0
        val protocolAnonymity = clamp01(0.58 * methodStealth + 0.42 * portStealth + protoBoost)

        val tcp = telemetry.tcpPingMs?.toDouble() ?: safeTimeout
        val https = telemetry.httpsPingMs?.toDouble() ?: tcp
        val overheadRatio = if (tcp <= 0.0) 1.0 else (https / tcp).coerceAtLeast(1.0)
        val jitterHealth = if (telemetry.tcpPingMs == null) 0.0 else clamp01(1.0 - ((overheadRatio - 1.0) / 4.0))
        val statusHealth = when {
            telemetry.isClean && telemetry.httpsStatusCode in 200..399 -> 1.0
            telemetry.isClean && telemetry.httpsStatusCode != null -> 0.88
            telemetry.isClean -> 0.80
            telemetry.tcpPingMs != null -> 0.28
            else -> 0.0
        }
        val transportStability = clamp01(0.45 * jitterHealth + 0.55 * statusHealth)

        return doubleArrayOf(latencyHealth, tlsStealth, protocolAnonymity, transportStability)
    }

    private fun computeTargetHealth(telemetry: ProbeTelemetry, x: DoubleArray): Double {
        if (!telemetry.isClean) return if (telemetry.tcpPingMs != null) 0.20 else 0.03
        return clamp01(0.32 * x[0] + 0.28 * x[1] + 0.15 * x[2] + 0.25 * x[3])
    }

    private fun computeTargetAnonymity(telemetry: ProbeTelemetry, x: DoubleArray): Double {
        if (!telemetry.isClean) return if (telemetry.tcpPingMs != null) 0.18 else 0.02
        return clamp01(0.10 * x[0] + 0.40 * x[1] + 0.35 * x[2] + 0.15 * x[3])
    }

    private fun clamp01(v: Double): Double = min(1.0, max(0.0, if (v.isNaN()) 0.0 else v))
}
