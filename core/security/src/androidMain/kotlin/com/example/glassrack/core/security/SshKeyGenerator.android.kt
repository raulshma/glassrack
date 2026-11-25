package com.example.glassrack.core.security

import com.example.glassrack.core.model.SshKeyAlgorithm
import com.example.glassrack.core.model.SshKeyRecord
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.util.UUID

actual object SshKeyGenerator {
    actual fun generateKeyPair(
        alias: String,
        algorithm: SshKeyAlgorithm,
        passphrase: String?,
    ): GeneratedKeyPair {
        val jsch = JSch()
        val keyType = when (algorithm) {
            SshKeyAlgorithm.ED25519 -> KeyPair.ED25519
            SshKeyAlgorithm.ECDSA -> KeyPair.ECDSA
            SshKeyAlgorithm.RSA -> KeyPair.RSA
        }
        val keySize = when (algorithm) {
            SshKeyAlgorithm.ED25519 -> 256
            SshKeyAlgorithm.ECDSA -> 256
            SshKeyAlgorithm.RSA -> 4096
        }
        val pair = KeyPair.genKeyPair(jsch, keyType, keySize)
        return try {
            val privOut = ByteArrayOutputStream()
            val pubOut = ByteArrayOutputStream()
            val passBytes = passphrase?.takeIf { it.isNotEmpty() }?.encodeToByteArray()
            pair.writePrivateKey(privOut, passBytes)
            pair.writePublicKey(pubOut, alias)
            val id = UUID.randomUUID().toString()
            val vaultRef = "ssh_key_$id"
            val fingerprint = "SHA256:${pair.fingerPrint}"
            val publicKeyStr = pubOut.toByteArray().decodeToString().trim()
            val privatePem = privOut.toByteArray().decodeToString()
            GeneratedKeyPair(
                record = SshKeyRecord(
                    id = id,
                    alias = alias,
                    algorithm = algorithm,
                    publicKeyOpenSsh = publicKeyStr,
                    fingerprintSha256 = fingerprint,
                    privateKeyVaultRef = vaultRef,
                    hasPassphrase = !passphrase.isNullOrEmpty(),
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
                privateKeyPem = privatePem,
                passphrase = passphrase,
            )
        } finally {
            pair.dispose()
        }
    }

    actual fun parseImportedPrivateKey(
        alias: String,
        privateKeyPem: String,
        passphrase: String?,
    ): GeneratedKeyPair {
        val jsch = JSch()
        val pair = KeyPair.load(jsch, privateKeyPem.encodeToByteArray(), null)
        return try {
            if (pair.isEncrypted && passphrase.isNullOrEmpty()) {
                throw IllegalArgumentException("Private key is passphrase-protected")
            }
            if (pair.isEncrypted && !pair.decrypt(passphrase)) {
                throw IllegalArgumentException("Incorrect passphrase for imported key")
            }
            val pubOut = ByteArrayOutputStream()
            pair.writePublicKey(pubOut, alias)
            val alg = when (pair.keyType) {
                KeyPair.ED25519 -> SshKeyAlgorithm.ED25519
                KeyPair.ECDSA -> SshKeyAlgorithm.ECDSA
                else -> SshKeyAlgorithm.RSA
            }
            val id = UUID.randomUUID().toString()
            GeneratedKeyPair(
                record = SshKeyRecord(
                    id = id,
                    alias = alias,
                    algorithm = alg,
                    publicKeyOpenSsh = pubOut.toByteArray().decodeToString().trim(),
                    fingerprintSha256 = "SHA256:${pair.fingerPrint}",
                    privateKeyVaultRef = "ssh_key_$id",
                    hasPassphrase = !passphrase.isNullOrEmpty(),
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
                privateKeyPem = privateKeyPem,
                passphrase = passphrase,
            )
        } finally {
            pair.dispose()
        }
    }
}
