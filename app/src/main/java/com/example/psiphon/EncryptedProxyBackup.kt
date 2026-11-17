package com.example.psiphon

import android.util.Base64
import com.example.data.local.CleanIpEntity
import com.example.data.preferences.ScannerSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class EncryptedBackupEnvelope(
    val format: String = FORMAT_ID,
    val kdf: String = KDF_ALGORITHM,
    val iterations: Int = KDF_ITERATIONS,
    val cipher: String = CIPHER_ALGORITHM,
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String
) {
    companion object {
        const val FORMAT_ID = "IPFOUNDER_AES_GCM_V1"
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val KDF_ITERATIONS = 120_000
        const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    }
}

@Serializable
data class BackupSettingsDto(
    val provider: String = CdnProvider.AKAMAI.name,
    val selectedProviders: List<String> = listOf(CdnProvider.AKAMAI.name),
    val method: String = PsiphonMethod.FRONTED_MEEK_OSSH.name,
    val includeIpv4: Boolean = true,
    val includeIpv6: Boolean = true,
    val includeHttp: Boolean = true,
    val includeSocks5: Boolean = true,
    val targetPortsInput: String = "443",
    val localSocksPort: Int = 1080,
    val customTargetsInput: String = "",
    val sniOverride: String = "",
    val timeoutMs: Int = 3000,
    val maxCandidates: Int = 24,
    val verifyTls: Boolean = true,
    val verifyHttpsTunnel: Boolean = true,
    val selectedIpKey: String = "",
    val freeProxyCountry: String = "US",
    val freeProxyPortFilter: String = "",
    val autoRoutingEnabled: Boolean = true
)

@Serializable
data class BackupProxyDto(
    val idKey: String,
    val ip: String,
    val ipVersion: String,
    val targetPort: Int,
    val localSocksPort: Int,
    val proxyProtocol: String = "HTTP",
    val provider: String,
    val method: String,
    val tcpPingMs: Long? = null,
    val httpsPingMs: Long? = null,
    val tlsVersion: String = "",
    val certIssuer: String = "",
    val certValid: Boolean = false,
    val httpsStatusCode: Int? = null,
    val countryCode: String = "--",
    val countryName: String = "Unknown",
    val countryFlag: String = "🌐",
    val city: String = "Unknown",
    val isp: String = "Unknown",
    val isClean: Boolean = false,
    val aiScore: Int = 0,
    val fuzzyMembership: Float = 0f,
    val nnPrediction: Float = 0f,
    val nashPayoff: Float = 0f,
    val anonymityConfidenceScore: Int = 0,
    val proxyHealthScore: Int = 0,
    val aiVerdict: String = "MODERATE",
    val scannedAt: Long = 0L
)

@Serializable
data class ProxyBackupPayload(
    val schemaVersion: Int = 1,
    val appName: String = "ipfounder psiphon",
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val scannerSettings: BackupSettingsDto,
    val proxyConfigurations: List<BackupProxyDto>
)

sealed class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InvalidPassphraseException(message: String = "Passphrase must be at least 4 characters") : BackupCryptoException(message)
class InvalidBackupFormatException(message: String = "Selected file is not a valid encrypted proxy backup") : BackupCryptoException(message)
class BackupDecryptionException(message: String = "Incorrect passphrase or corrupted backup file", cause: Throwable? = null) : BackupCryptoException(message, cause)

data class RestoredProxyBackup(
    val payload: ProxyBackupPayload,
    val restoredEntities: List<CleanIpEntity>,
    val settingsRestorer: (ScannerSettings) -> ScannerSettings
)

object EncryptedProxyBackup {

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val AES_KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val secureRandom = SecureRandom()

    fun exportToEncryptedBytes(
        settings: ScannerSettings,
        proxies: List<CleanIpEntity>,
        passphrase: String,
        exportedAtEpochMs: Long = System.currentTimeMillis()
    ): ByteArray {
        if (passphrase.length < 4) {
            throw InvalidPassphraseException()
        }

        val payload = ProxyBackupPayload(
            schemaVersion = 1,
            exportedAtEpochMs = exportedAtEpochMs,
            scannerSettings = settings.toBackupDto(),
            proxyConfigurations = proxies.map { it.toBackupDto() }
        )
        val plaintextBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }

        val key = deriveAesKey(passphrase.toCharArray(), salt, EncryptedBackupEnvelope.KDF_ITERATIONS)
        val cipher = Cipher.getInstance(EncryptedBackupEnvelope.CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintextBytes)

        val envelope = EncryptedBackupEnvelope(
            saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
        return json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
    }

    fun isValidBackupEnvelope(rawBytes: ByteArray): Boolean {
        return try {
            parseEnvelope(rawBytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun importFromEncryptedBytes(
        rawBytes: ByteArray,
        passphrase: String
    ): RestoredProxyBackup {
        if (passphrase.isEmpty()) {
            throw InvalidPassphraseException("Passphrase cannot be empty")
        }

        val envelope = parseEnvelope(rawBytes)
        val salt = decodeBase64Strict(envelope.saltBase64)
        val iv = decodeBase64Strict(envelope.ivBase64)
        val ciphertext = decodeBase64Strict(envelope.ciphertextBase64)

        if (salt.size < 8 || iv.size != IV_BYTES || ciphertext.size < 16) {
            throw InvalidBackupFormatException("Malformed cryptographic parameters in backup file")
        }

        val plaintextBytes = try {
            val key = deriveAesKey(passphrase.toCharArray(), salt, envelope.iterations)
            val cipher = Cipher.getInstance(EncryptedBackupEnvelope.CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw BackupDecryptionException("Incorrect passphrase or tampered backup file", e)
        } catch (e: GeneralSecurityException) {
            throw BackupDecryptionException("Unable to decrypt backup file", e)
        }

        val payload = try {
            json.decodeFromString<ProxyBackupPayload>(plaintextBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw InvalidBackupFormatException("Decrypted payload is corrupted")
        }

        val entities = payload.proxyConfigurations.map { it.toEntity() }
        val settingsDto = payload.scannerSettings
        val restorer: (ScannerSettings) -> ScannerSettings = { current ->
            val fallbackProvider = CdnProvider.fromName(settingsDto.provider)
            val restoredProviders = settingsDto.selectedProviders
                .map { CdnProvider.fromName(it) }
                .toSet()
                .ifEmpty { setOf(fallbackProvider) }
            current.copy(
                provider = restoredProviders.firstOrNull() ?: fallbackProvider,
                selectedProviders = restoredProviders,
                method = PsiphonMethod.fromName(settingsDto.method),
                includeIpv4 = settingsDto.includeIpv4,
                includeIpv6 = settingsDto.includeIpv6,
                includeHttp = settingsDto.includeHttp,
                includeSocks5 = settingsDto.includeSocks5,
                targetPortsInput = settingsDto.targetPortsInput.ifBlank { "443" },
                localSocksPort = settingsDto.localSocksPort.coerceIn(1024, 65535),
                customTargetsInput = settingsDto.customTargetsInput,
                sniOverride = settingsDto.sniOverride,
                timeoutMs = settingsDto.timeoutMs.coerceIn(1000, 10000),
                maxCandidates = settingsDto.maxCandidates.coerceIn(4, 128),
                verifyTls = settingsDto.verifyTls,
                verifyHttpsTunnel = settingsDto.verifyHttpsTunnel,
                selectedIpKey = settingsDto.selectedIpKey,
                freeProxyCountry = settingsDto.freeProxyCountry,
                freeProxyPortFilter = settingsDto.freeProxyPortFilter,
                autoRoutingEnabled = settingsDto.autoRoutingEnabled
            )
        }

        return RestoredProxyBackup(
            payload = payload,
            restoredEntities = entities,
            settingsRestorer = restorer
        )
    }

    private fun parseEnvelope(rawBytes: ByteArray): EncryptedBackupEnvelope {
        if (rawBytes.isEmpty()) throw InvalidBackupFormatException("Backup file is empty")
        val envelope = try {
            json.decodeFromString<EncryptedBackupEnvelope>(rawBytes.toString(Charsets.UTF_8).trim())
        } catch (e: Exception) {
            throw InvalidBackupFormatException("Unsupported or corrupted backup file")
        }
        if (envelope.format != EncryptedBackupEnvelope.FORMAT_ID ||
            envelope.cipher != EncryptedBackupEnvelope.CIPHER_ALGORITHM ||
            envelope.kdf != EncryptedBackupEnvelope.KDF_ALGORITHM ||
            envelope.iterations !in 10_000..1_000_000
        ) {
            throw InvalidBackupFormatException("Incompatible backup encryption format")
        }
        return envelope
    }

    private fun decodeBase64Strict(value: String): ByteArray {
        return try {
            Base64.decode(value, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupFormatException("Invalid Base64 data in backup file")
        }
    }

    private fun deriveAesKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(EncryptedBackupEnvelope.KDF_ALGORITHM)
        val spec = PBEKeySpec(password, salt, iterations, AES_KEY_BITS)
        return try {
            val secretBytes = factory.generateSecret(spec).encoded
            SecretKeySpec(secretBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun ScannerSettings.toBackupDto(): BackupSettingsDto = BackupSettingsDto(
        provider = provider.name,
        selectedProviders = selectedProviders.ifEmpty { setOf(provider) }.map { it.name },
        method = method.name,
        includeIpv4 = includeIpv4,
        includeIpv6 = includeIpv6,
        includeHttp = includeHttp,
        includeSocks5 = includeSocks5,
        targetPortsInput = targetPortsInput,
        localSocksPort = localSocksPort,
        customTargetsInput = customTargetsInput,
        sniOverride = sniOverride,
        timeoutMs = timeoutMs,
        maxCandidates = maxCandidates,
        verifyTls = verifyTls,
        verifyHttpsTunnel = verifyHttpsTunnel,
        selectedIpKey = selectedIpKey,
        freeProxyCountry = freeProxyCountry,
        freeProxyPortFilter = freeProxyPortFilter,
        autoRoutingEnabled = autoRoutingEnabled
    )

    private fun CleanIpEntity.toBackupDto(): BackupProxyDto = BackupProxyDto(
        idKey = idKey,
        ip = ip,
        ipVersion = ipVersion,
        targetPort = targetPort,
        localSocksPort = localSocksPort,
        proxyProtocol = proxyProtocol,
        provider = provider,
        method = method,
        tcpPingMs = tcpPingMs,
        httpsPingMs = httpsPingMs,
        tlsVersion = tlsVersion,
        certIssuer = certIssuer,
        certValid = certValid,
        httpsStatusCode = httpsStatusCode,
        countryCode = countryCode,
        countryName = countryName,
        countryFlag = countryFlag,
        city = city,
        isp = isp,
        isClean = isClean,
        aiScore = aiScore,
        fuzzyMembership = fuzzyMembership,
        nnPrediction = nnPrediction,
        nashPayoff = nashPayoff,
        anonymityConfidenceScore = anonymityConfidenceScore,
        proxyHealthScore = proxyHealthScore,
        aiVerdict = aiVerdict,
        scannedAt = scannedAt
    )

    private fun BackupProxyDto.toEntity(): CleanIpEntity = CleanIpEntity(
        idKey = idKey.ifBlank { "$ip:$targetPort:$proxyProtocol" },
        ip = ip,
        ipVersion = ipVersion,
        targetPort = targetPort,
        localSocksPort = localSocksPort,
        proxyProtocol = proxyProtocol,
        provider = provider,
        method = method,
        tcpPingMs = tcpPingMs,
        httpsPingMs = httpsPingMs,
        tlsVersion = tlsVersion,
        certIssuer = certIssuer,
        certValid = certValid,
        httpsStatusCode = httpsStatusCode,
        countryCode = countryCode,
        countryName = countryName,
        countryFlag = countryFlag,
        city = city,
        isp = isp,
        isClean = isClean,
        aiScore = aiScore,
        fuzzyMembership = fuzzyMembership,
        nnPrediction = nnPrediction,
        nashPayoff = nashPayoff,
        anonymityConfidenceScore = anonymityConfidenceScore,
        proxyHealthScore = proxyHealthScore,
        aiVerdict = aiVerdict,
        scannedAt = scannedAt
    )
}
