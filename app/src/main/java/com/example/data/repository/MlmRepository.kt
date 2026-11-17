package com.example.data.repository

import com.example.data.local.CleanIpEntity
import com.example.data.local.MlmDatabase
import com.example.data.preferences.ScannerSettings
import com.example.data.preferences.UserPreferencesRepository
import com.example.psiphon.ProbeTelemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MlmRepository(
    database: MlmDatabase,
    private val preferences: UserPreferencesRepository
) {
    private val cleanIpDao = database.cleanIpDao()

    val savedCleanIps: Flow<List<CleanIpEntity>> = cleanIpDao.observeAllCleanIps()
    val scannerSettings: Flow<ScannerSettings> = preferences.scannerSettings

    suspend fun updateSettings(transform: (ScannerSettings) -> ScannerSettings) =
        preferences.update(transform)

    suspend fun saveProbeResult(result: ProbeTelemetry) {
        cleanIpDao.upsertCleanIp(
            CleanIpEntity(
                idKey = "${result.ip}:${result.targetPort}:${result.proxyProtocol}",
                ip = result.ip,
                ipVersion = result.ipVersion,
                targetPort = result.targetPort,
                localSocksPort = result.localSocksPort,
                proxyProtocol = result.proxyProtocol,
                provider = result.provider.displayName,
                method = result.method.wireName,
                tcpPingMs = result.tcpPingMs,
                httpsPingMs = result.httpsPingMs,
                tlsVersion = result.tlsVersion,
                certIssuer = result.certIssuer,
                certValid = result.certValid,
                httpsStatusCode = result.httpsStatusCode,
                countryCode = result.countryCode,
                countryName = result.countryName,
                countryFlag = result.countryFlag,
                city = result.city,
                isp = result.isp,
                isClean = result.isClean,
                aiScore = result.aiScore,
                fuzzyMembership = result.fuzzyMembership,
                nnPrediction = result.nnPrediction,
                nashPayoff = result.nashPayoff,
                anonymityConfidenceScore = result.anonymityConfidenceScore,
                proxyHealthScore = result.proxyHealthScore,
                aiVerdict = result.aiVerdict
            )
        )
    }

    suspend fun getAllCleanIpsOnce(): List<CleanIpEntity> = cleanIpDao.observeAllCleanIps().first()

    suspend fun restoreBackup(
        settingsTransform: (ScannerSettings) -> ScannerSettings,
        entities: List<CleanIpEntity>,
        replaceExisting: Boolean
    ) {
        if (replaceExisting) {
            cleanIpDao.clearAll()
        }
        if (entities.isNotEmpty()) {
            cleanIpDao.upsertAllCleanIps(entities)
        }
        preferences.update(settingsTransform)
    }

    suspend fun deleteCleanIp(idKey: String) = cleanIpDao.deleteById(idKey)
    suspend fun deleteFailedCleanIps(): Int = cleanIpDao.deleteFailed()
    suspend fun clearAllCleanIps() = cleanIpDao.clearAll()
}
