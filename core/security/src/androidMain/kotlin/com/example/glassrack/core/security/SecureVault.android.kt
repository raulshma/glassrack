package com.example.glassrack.core.security

import android.content.Context
import android.util.Base64
import co.touchlab.kermit.Logger
import com.example.glassrack.core.model.EncryptedBackupBundle
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual class SecureVault(private val context: Context) : HashProvider {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val vaultFile: File get() = File(context.filesDir, "glassrack_vault.enc.json")

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "glassrack_tink_keyset", "glassrack_tink_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://glassrack_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    actual suspend fun putSecret(vaultId: String, secret: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val map = loadMapLocked().toMutableMap()
                val encrypted = aead.encrypt(
                    secret.encodeToByteArray(),
                    vaultId.encodeToByteArray(),
                )
                map[vaultId] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
                saveMapLocked(map)
            }
        }
    }

    actual suspend fun getSecret(vaultId: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val encoded = loadMapLocked()[vaultId] ?: return@withLock null
            runCatching {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                aead.decrypt(bytes, vaultId.encodeToByteArray()).decodeToString()
            }.onFailure {
                Logger.e(it) { "Failed to decrypt vault secret $vaultId" }
            }.getOrNull()
        }
    }

    actual suspend fun deleteSecret(vaultId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val map = loadMapLocked().toMutableMap()
                if (map.remove(vaultId) != null) {
                    saveMapLocked(map)
                }
            }
        }
    }

    actual suspend fun containsSecret(vaultId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { loadMapLocked().containsKey(vaultId) }
    }

    actual suspend fun encryptBackup(plainJson: String, passphrase: String): EncryptedBackupBundle =
        withContext(Dispatchers.Default) {
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val key = deriveAesKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plainJson.encodeToByteArray())
            EncryptedBackupBundle(
                formatVersion = 1,
                saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
                ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
                ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            )
        }

    actual suspend fun decryptBackup(bundle: EncryptedBackupBundle, passphrase: String): String =
        withContext(Dispatchers.Default) {
            val salt = Base64.decode(bundle.saltBase64, Base64.NO_WRAP)
            val iv = Base64.decode(bundle.ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(bundle.ciphertextBase64, Base64.NO_WRAP)
            val key = deriveAesKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).decodeToString()
        }

    actual override fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun deriveAesKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        val encoded = factory.generateSecret(spec).encoded
        return SecretKeySpec(encoded, "AES")
    }

    private fun loadMapLocked(): Map<String, String> {
        if (!vaultFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(vaultFile.readText())
        }.getOrDefault(emptyMap())
    }

    private fun saveMapLocked(map: Map<String, String>) {
        vaultFile.writeText(json.encodeToString(map))
    }
}
