package com.raulshma.grack.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandSanitizerTest {
    @Test
    fun scrubsSecretsAndPrivateKeys() {
        val raw = "PASS=hunter2 TOKEN='abc123' curl -H 'Authorization: Bearer my-secret-token' --password=qwerty http://lab"
        val clean = CommandSanitizer.sanitize(raw)
        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("abc123"))
        assertFalse(clean.contains("my-secret-token"))
        assertFalse(clean.contains("qwerty"))
        assertTrue(clean.contains("[REDACTED]"))
    }

    @Test
    fun scrubsSudoStdinAndPemBlocks() {
        val raw = "echo supersecret | sudo -S systemctl restart docker\n-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
        val clean = CommandSanitizer.sanitize(raw)
        assertFalse(clean.contains("supersecret"))
        assertFalse(clean.contains("abc"))
        assertTrue(clean.contains("[REDACTED_PRIVATE_KEY]"))
    }

    @Test
    fun preservesSafeCommands() {
        val cmd = "docker compose -f /opt/stacks/infra/docker-compose.yml pull"
        assertEquals(cmd, CommandSanitizer.sanitize(cmd))
    }
}
