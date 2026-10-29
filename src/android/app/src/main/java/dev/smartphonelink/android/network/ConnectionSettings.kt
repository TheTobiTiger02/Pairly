package dev.smartphonelink.android.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

data class TrustedHost(
    val hostId: String,
    val displayName: String,
    val certificateFingerprint: String,
    val endpointHost: String,
    val port: Int,
    val protocolVersion: Int,
    val deviceId: String,
    val trustSecret: String,
    val useTls: Boolean
) {
    val endpoint: String
        get() = EndpointFormatter.sessionEndpoint(endpointHost, port, useTls)
}

class ConnectionSettings(context: Context) {
    var clipboardSyncEnabled: Boolean
        get() = preferences.getBoolean("clipboard_sync_enabled", true)
        set(value) { preferences.edit().putBoolean("clipboard_sync_enabled", value).apply() }
    var clipboardWirelessDebuggingOff: Boolean
        get() = preferences.getBoolean("clipboard_wireless_debugging_off", true)
        set(value) { preferences.edit().putBoolean("clipboard_wireless_debugging_off", value).apply() }
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("smartphone_link_connection", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = preferences.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) {
                return existing
            }

            val generated = "android-${UUID.randomUUID().toString().replace("-", "")}"
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    var host: String
        get() = preferences.getString(KEY_HOST, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_HOST, value.trim()).apply()
        }

    var port: Int
        get() = preferences.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in 1..65535 } ?: DEFAULT_PORT
        set(value) {
            preferences.edit().putInt(KEY_PORT, value.coerceIn(1, 65535)).apply()
        }

    val endpoint: String
        get() = EndpointFormatter.sessionEndpoint(host, port, useTls)

    var useTls: Boolean
        get() = preferences.getBoolean(KEY_USE_TLS, false)
        private set(value) {
            preferences.edit().putBoolean(KEY_USE_TLS, value).apply()
        }

    var hostId: String
        get() = preferences.getString(KEY_HOST_ID, "").orEmpty()
        private set(value) {
            preferences.edit().putString(KEY_HOST_ID, value.trim()).apply()
        }

    var pcDisplayName: String
        get() = preferences.getString(KEY_PC_DISPLAY_NAME, "").orEmpty()
        private set(value) {
            preferences.edit().putString(KEY_PC_DISPLAY_NAME, value.trim()).apply()
        }

    var certificateFingerprint: String
        get() = preferences.getString(KEY_CERTIFICATE_FINGERPRINT, "").orEmpty()
        private set(value) {
            preferences.edit().putString(KEY_CERTIFICATE_FINGERPRINT, value.trim()).apply()
        }

    var protocolVersion: Int
        get() = preferences.getInt(KEY_PROTOCOL_VERSION, 1).coerceAtLeast(1)
        private set(value) {
            preferences.edit().putInt(KEY_PROTOCOL_VERSION, value.coerceAtLeast(1)).apply()
        }

    var trustKey: String
        get() {
            val encrypted = preferences.getString(KEY_TRUST_SECRET_ENCRYPTED, "").orEmpty()
            val iv = preferences.getString(KEY_TRUST_SECRET_IV, "").orEmpty()
            if (encrypted.isNotBlank() && iv.isNotBlank()) {
                decryptSecret(encrypted, iv)?.let { return it }
            }

            // One-time v1 migration. Keep the legacy value only if Keystore is unavailable.
            val legacy = preferences.getString(KEY_TRUST_KEY, "").orEmpty()
            if (legacy.isNotBlank()) {
                storeTrustSecret(legacy)
            }
            return legacy
        }
        set(value) {
            if (value.isBlank()) {
                preferences.edit()
                    .remove(KEY_TRUST_KEY)
                    .remove(KEY_TRUST_SECRET_ENCRYPTED)
                    .remove(KEY_TRUST_SECRET_IV)
                    .apply()
            } else {
                check(storeTrustSecret(value)) { "Android Keystore could not protect the pairing secret." }
            }
        }

    var autoConnectEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_CONNECT, false)
        set(value) {
            preferences.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
        }

    var sharedStorageUri: String
        get() = preferences.getString(KEY_SHARED_STORAGE_URI, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_SHARED_STORAGE_URI, value).apply()
        }

    val savedPairingRejected: Boolean
        get() = preferences.getBoolean(KEY_SAVED_PAIRING_REJECTED, false)

    val rejectedPairingReason: String
        get() = preferences.getString(KEY_REJECTED_PAIRING_REASON, "").orEmpty()

    val trustedHost: TrustedHost?
        get() {
            val secret = trustKey
            if (secret.isBlank()) return null
            return TrustedHost(
                hostId = hostId,
                displayName = pcDisplayName,
                certificateFingerprint = certificateFingerprint,
                endpointHost = host,
                port = port,
                protocolVersion = protocolVersion,
                deviceId = deviceId,
                trustSecret = secret,
                useTls = useTls
            )
        }

    fun rememberTrustedConnection(
        host: String,
        deviceId: String,
        trustKey: String,
        hostId: String = this.hostId,
        displayName: String = pcDisplayName,
        port: Int = this.port,
        protocolVersion: Int = this.protocolVersion,
        certificateFingerprint: String = this.certificateFingerprint,
        securePort: Int? = null
    ): Boolean {
        val existingLegacySecret = preferences.getString(KEY_TRUST_KEY, "").orEmpty()
        val cleanHost = EndpointFormatter.hostOnly(host)
        val enableTls = securePort != null && certificateFingerprint.isNotBlank()
        val resolvedPort = securePort ?: EndpointFormatter.explicitPort(host) ?: port
        preferences.edit()
            .putString(KEY_HOST, cleanHost)
            .putInt(KEY_PORT, resolvedPort.coerceIn(1, 65535))
            .putString(KEY_HOST_ID, hostId.trim())
            .putString(KEY_PC_DISPLAY_NAME, displayName.trim())
            .putInt(KEY_PROTOCOL_VERSION, protocolVersion.coerceAtLeast(1))
            .putString(KEY_CERTIFICATE_FINGERPRINT, certificateFingerprint.trim())
            .putBoolean(KEY_USE_TLS, enableTls)
            .putString(KEY_DEVICE_ID, deviceId)
            .putBoolean(KEY_AUTO_CONNECT, true)
            .putBoolean(KEY_SAVED_PAIRING_REJECTED, false)
            .remove(KEY_REJECTED_DEVICE_ID)
            .remove(KEY_REJECTED_PAIRING_REASON)
            .apply()
        return storeTrustSecret(trustKey) || existingLegacySecret == trustKey
    }

    fun updateDiscoveredEndpoint(pc: DiscoveredPc): Boolean {
        val savedHostId = hostId
        if (savedHostId.isBlank() || pc.hostId.isBlank() || pc.hostId != savedHostId) {
            return false
        }
        val savedFingerprint = certificateFingerprint
        val pinnedTlsIdentity = useTls || savedFingerprint.isNotBlank()
        if (pinnedTlsIdentity) {
            if (pc.securePort == null ||
                savedFingerprint.isBlank() ||
                pc.certificateFingerprint.isBlank() ||
                !savedFingerprint.equals(pc.certificateFingerprint, ignoreCase = true)
            ) {
                return false
            }
        }
        preferences.edit()
            .putString(KEY_HOST, pc.ip)
            .putInt(KEY_PORT, if (pinnedTlsIdentity) pc.securePort!! else (pc.securePort ?: pc.port))
            .putString(KEY_PC_DISPLAY_NAME, pc.name)
            .putInt(KEY_PROTOCOL_VERSION, pc.protocolVersion.coerceAtLeast(1))
            .putBoolean(KEY_USE_TLS, pinnedTlsIdentity || (pc.securePort != null && pc.certificateFingerprint.isNotBlank()))
            .apply()
        return true
    }

    fun rememberPairingCandidate(pc: DiscoveredPc) {
        preferences.edit()
            .putString(KEY_HOST, pc.ip)
            .putInt(KEY_PORT, pc.securePort ?: pc.port)
            .putString(KEY_HOST_ID, pc.hostId)
            .putString(KEY_PC_DISPLAY_NAME, pc.name)
            .putInt(KEY_PROTOCOL_VERSION, pc.protocolVersion.coerceAtLeast(1))
            .putString(KEY_CERTIFICATE_FINGERPRINT, pc.certificateFingerprint)
            .putBoolean(KEY_USE_TLS, pc.securePort != null && pc.certificateFingerprint.isNotBlank())
            .apply()
    }

    fun rememberManualEndpoint(value: String) {
        val cleanHost = EndpointFormatter.hostOnly(value)
        val explicitPort = EndpointFormatter.explicitPort(value)
        preferences.edit()
            .putString(KEY_HOST, cleanHost)
            .putInt(KEY_PORT, explicitPort ?: DEFAULT_PORT)
            .remove(KEY_HOST_ID)
            .remove(KEY_PC_DISPLAY_NAME)
            .remove(KEY_CERTIFICATE_FINGERPRINT)
            .putInt(KEY_PROTOCOL_VERSION, 1)
            .putBoolean(KEY_USE_TLS, value.trim().startsWith("wss://", ignoreCase = true))
            .apply()
    }

    fun rememberRejectedSavedPairing(reason: String) {
        preferences.edit()
            .putBoolean(KEY_SAVED_PAIRING_REJECTED, true)
            .putBoolean(KEY_AUTO_CONNECT, false)
            .putString(KEY_REJECTED_DEVICE_ID, deviceId)
            .putString(KEY_REJECTED_PAIRING_REASON, reason)
            .apply()
    }

    fun clearRejectedSavedPairing() {
        preferences.edit()
            .putBoolean(KEY_SAVED_PAIRING_REJECTED, false)
            .remove(KEY_REJECTED_PAIRING_REASON)
            .apply()
    }

    fun resetDeviceId() {
        preferences.edit().remove(KEY_DEVICE_ID).apply()
    }

    fun forgetTrustedHost() {
        preferences.edit()
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .remove(KEY_HOST_ID)
            .remove(KEY_PC_DISPLAY_NAME)
            .remove(KEY_PROTOCOL_VERSION)
            .remove(KEY_CERTIFICATE_FINGERPRINT)
            .remove(KEY_USE_TLS)
            .remove(KEY_TRUST_KEY)
            .remove(KEY_TRUST_SECRET_ENCRYPTED)
            .remove(KEY_TRUST_SECRET_IV)
            .remove(KEY_AUTO_CONNECT)
            .remove(KEY_SAVED_PAIRING_REJECTED)
            .remove(KEY_REJECTED_DEVICE_ID)
            .remove(KEY_REJECTED_PAIRING_REASON)
            .apply()
    }

    private fun storeTrustSecret(value: String): Boolean {
        val encrypted = encryptSecret(value)
        if (encrypted == null) {
            return false
        }
        preferences.edit()
            .putString(KEY_TRUST_SECRET_ENCRYPTED, encrypted.first)
            .putString(KEY_TRUST_SECRET_IV, encrypted.second)
            .remove(KEY_TRUST_KEY)
            .apply()
        return true
    }

    private fun encryptSecret(value: String): Pair<String, String>? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }.getOrNull()

    private fun decryptSecret(encrypted: String, iv: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_HOST_ID = "host_id"
        private const val KEY_PC_DISPLAY_NAME = "pc_display_name"
        private const val KEY_PROTOCOL_VERSION = "protocol_version"
        private const val KEY_CERTIFICATE_FINGERPRINT = "certificate_fingerprint"
        private const val KEY_USE_TLS = "use_tls"
        private const val KEY_TRUST_KEY = "trust_key"
        private const val KEY_TRUST_SECRET_ENCRYPTED = "trust_secret_encrypted"
        private const val KEY_TRUST_SECRET_IV = "trust_secret_iv"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_SHARED_STORAGE_URI = "shared_storage_uri"
        private const val KEY_SAVED_PAIRING_REJECTED = "saved_pairing_rejected"
        private const val KEY_REJECTED_DEVICE_ID = "rejected_device_id"
        private const val KEY_REJECTED_PAIRING_REASON = "rejected_pairing_reason"
        private const val KEYSTORE_ALIAS = "smartphone_link_trusted_host_v2"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEFAULT_PORT = 7816
    }
}
