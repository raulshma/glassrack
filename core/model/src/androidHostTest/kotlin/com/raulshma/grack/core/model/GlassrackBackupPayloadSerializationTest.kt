package com.raulshma.grack.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassrackBackupPayloadSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val payload = GlassrackBackupPayload(
        exportedAtEpochMs = 1_700_000_000_000L,
        servers = listOf(Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")),
        dashboards = listOf(DashboardProfile(id = "dash-main", title = "Main Rack", sortOrder = 0)),
        tiles = emptyList(),
        snippets = emptyList(),
        alertRules = emptyList(),
        preferences = UserPreferences(),
        sshKeyMetadata = listOf(
            SshKeyRecord(
                id = "key-1",
                alias = "lab-ed25519",
                algorithm = SshKeyAlgorithm.ED25519,
                publicKeyOpenSsh = "ssh-ed25519 AAAA",
                fingerprintSha256 = "SHA256:abc",
                privateKeyVaultRef = "key_lab",
                hasPassphrase = false,
                createdAtEpochMs = 1L,
            ),
        ),
        tunnels = listOf(
            PortForwardTunnel(
                id = "tun-1",
                serverId = "srv-1",
                label = "Jellyfin",
                localPort = 8096,
                remoteHost = "127.0.0.1",
                remotePort = 8096,
            ),
        ),
    )

    @Test
    fun v2PayloadRoundTripsIncludingTunnels() {
        val encoded = json.encodeToString(payload)
        val decoded = json.decodeFromString<GlassrackBackupPayload>(encoded)

        assertEquals(GlassrackBackupPayload.BACKUP_FORMAT_VERSION, decoded.version)
        assertEquals(2, decoded.version)
        assertEquals(payload.tunnels, decoded.tunnels)
        assertEquals(payload.sshKeyMetadata, decoded.sshKeyMetadata)
        assertEquals(payload.servers, decoded.servers)
    }

    @Test
    fun encodedV2PayloadCarriesTheVersionFieldAndTunnels() {
        val encoded = json.encodeToString(payload)
        assertTrue("\"version\": 2" in encoded || "\"version\":2" in encoded)
        assertTrue("tun-1" in encoded)
    }

    @Test
    fun v1PayloadWithoutTunnelsFieldIsStillAccepted() {
        val v1Json = """
            {
              "version": 1,
              "exportedAtEpochMs": 1700000000000,
              "servers": [
                {
                  "id": "srv-1", "name": "node-a", "groupTag": "Homelab", "tags": [],
                  "accentColorHex": 2130706431, "primaryHost": "10.0.0.5", "fallbackHost": "",
                  "port": 22, "username": "root", "authType": "KEY", "macAddress": "",
                  "pollIntervalSec": 5, "preferredWifiSsids": [], "notes": "", "sortOrder": 0,
                  "capabilities": {}, "customProbes": []
                }
              ],
              "dashboards": [], "tiles": [], "snippets": [], "alertRules": [],
              "preferences": {},
              "sshKeyMetadata": []
            }
        """.trimIndent()

        val decoded = json.decodeFromString<GlassrackBackupPayload>(v1Json)

        assertEquals(1, decoded.version)
        assertTrue(decoded.tunnels.isEmpty())
        assertEquals("srv-1", decoded.servers.single().id)
        assertTrue(decoded.sshKeyMetadata.isEmpty())
    }
}
