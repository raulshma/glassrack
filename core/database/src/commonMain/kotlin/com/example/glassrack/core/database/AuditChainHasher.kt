package com.example.glassrack.core.database

import com.example.glassrack.core.model.AuditChainVerification
import com.example.glassrack.core.model.AuditEntry
import com.example.glassrack.core.security.HashProvider

class AuditChainHasher(private val hasher: HashProvider) {
    fun computeEntryHash(entry: AuditEntry, prevHash: String): String {
        val canonical = buildString {
            append(prevHash)
            append('|')
            append(entry.id)
            append('|')
            append(entry.timestampEpochMs)
            append('|')
            append(entry.serverId.orEmpty())
            append('|')
            append(entry.category.name)
            append('|')
            append(entry.actionTitle)
            append('|')
            append(entry.commandSanitized)
            append('|')
            append(entry.triggerSource.name)
            append('|')
            append(entry.outcome.name)
            append('|')
            append(entry.exitCode?.toString().orEmpty())
            append('|')
            append(entry.durationMs)
        }
        return hasher.sha256Hex(canonical)
    }

    fun verifyChain(chronologicalEntries: List<AuditEntry>): AuditChainVerification {
        var expectedPrev = GENESIS_HASH
        for (entry in chronologicalEntries) {
            if (entry.prevHash != expectedPrev) {
                return AuditChainVerification(
                    totalEntries = chronologicalEntries.size,
                    isValid = false,
                    brokenEntryId = entry.id,
                )
            }
            val expectedHash = computeEntryHash(entry, expectedPrev)
            if (entry.entryHash != expectedHash) {
                return AuditChainVerification(
                    totalEntries = chronologicalEntries.size,
                    isValid = false,
                    brokenEntryId = entry.id,
                )
            }
            expectedPrev = entry.entryHash
        }
        return AuditChainVerification(
            totalEntries = chronologicalEntries.size,
            isValid = true,
            brokenEntryId = null,
        )
    }

    companion object {
        const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
