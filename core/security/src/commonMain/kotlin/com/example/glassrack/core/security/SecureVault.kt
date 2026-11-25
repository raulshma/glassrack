package com.example.glassrack.core.security

import com.example.glassrack.core.model.EncryptedBackupBundle
import com.example.glassrack.core.model.SshKeyAlgorithm
import com.example.glassrack.core.model.SshKeyRecord

interface HashProvider {
    fun sha256Hex(input: String): String
}

expect class SecureVault : HashProvider {
    suspend fun putSecret(vaultId: String, secret: String)
    suspend fun getSecret(vaultId: String): String?
    suspend fun deleteSecret(vaultId: String)
    suspend fun containsSecret(vaultId: String): Boolean
    suspend fun encryptBackup(plainJson: String, passphrase: String): EncryptedBackupBundle
    suspend fun decryptBackup(bundle: EncryptedBackupBundle, passphrase: String): String
    override fun sha256Hex(input: String): String
}

data class GeneratedKeyPair(
    val record: SshKeyRecord,
    val privateKeyPem: String,
    val passphrase: String?,
)

expect object SshKeyGenerator {
    fun generateKeyPair(
        alias: String,
        algorithm: SshKeyAlgorithm,
        passphrase: String? = null,
    ): GeneratedKeyPair

    fun parseImportedPrivateKey(
        alias: String,
        privateKeyPem: String,
        passphrase: String? = null,
    ): GeneratedKeyPair
}

expect class BiometricGate {
    fun canAuthenticate(): Boolean
    suspend fun authenticate(title: String, subtitle: String): Boolean
}
