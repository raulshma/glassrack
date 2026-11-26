package com.raulshma.grack.core.database

import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditEntry
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.security.HashProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditChainHasherTest {
    private val testHasher = object : HashProvider {
        override fun sha256Hex(input: String): String =
            input.hashCode().toUInt().toString(16).padStart(64, '0')
    }

    @Test
    fun detectsTamperedAuditEntry() {
        val hasher = AuditChainHasher(testHasher)
        val firstDraft = AuditEntry(
            id = "aud-1",
            timestampEpochMs = 1000L,
            serverId = "demo-rpi-core",
            serverNameSnapshot = "rpi5-core.lan",
            category = AuditCategory.CONTAINER,
            actionTitle = "Restart pihole",
            commandSanitized = "docker restart pihole",
            triggerSource = TriggerSource.UI,
            outcome = AuditOutcome.SUCCESS,
            exitCode = 0,
            durationMs = 450L,
            prevHash = AuditChainHasher.GENESIS_HASH,
        )
        val first = firstDraft.copy(entryHash = hasher.computeEntryHash(firstDraft, AuditChainHasher.GENESIS_HASH))

        val secondDraft = firstDraft.copy(
            id = "aud-2",
            timestampEpochMs = 2000L,
            commandSanitized = "docker restart caddy",
            prevHash = first.entryHash,
        )
        val second = secondDraft.copy(entryHash = hasher.computeEntryHash(secondDraft, first.entryHash))

        val validResult = hasher.verifyChain(listOf(first, second))
        assertTrue(validResult.isValid)
        assertEquals(2, validResult.totalEntries)

        val tampered = second.copy(commandSanitized = "rm -rf /")
        val invalidResult = hasher.verifyChain(listOf(first, tampered))
        assertFalse(invalidResult.isValid)
        assertEquals("aud-2", invalidResult.brokenEntryId)
    }
}
