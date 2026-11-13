package com.example.data.repository

import com.example.data.local.CleanIpEntity
import com.example.data.local.MlmDatabase
import com.example.data.preferences.ScannerSettings
import com.example.data.preferences.UserPreferencesRepository
import com.example.psiphon.ProbeTelemetry
import kotlinx.coroutines.flow.Flow

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
                isClean = result.isClean
            )
        )
    }

    suspend fun deleteCleanIp(idKey: String) = cleanIpDao.deleteById(idKey)
    suspend fun deleteFailedCleanIps(): Int = cleanIpDao.deleteFailed()
    suspend fun clearAllCleanIps() = cleanIpDao.clearAll()
}
