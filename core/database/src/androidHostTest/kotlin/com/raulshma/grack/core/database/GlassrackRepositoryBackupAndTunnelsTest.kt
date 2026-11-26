package com.raulshma.grack.core.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.raulshma.grack.core.model.PortForwardTunnel
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.SshKeyAlgorithm
import com.raulshma.grack.core.model.SshKeyRecord
import com.raulshma.grack.core.security.SecureVault
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repository-level tests over an in-memory Room database: tunnels cascade on server delete
 * and the backup payload round-trips (tunnels + ssh key metadata survive an export/import
 * into a fresh database). Hermetic: no sockets, no real vault secrets (servers carry no
 * credentialVaultId), backup crypto is passphrase-based JCE.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class GlassrackRepositoryBackupAndTunnelsTest {

    private fun repository(scope: TestScope): GlassrackRepository {
        val queryContext = scope.coroutineContext.minusKey(Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-repo-test-${UUID.randomUUID()}.preferences_pb")
            .absolutePath
            .toPath()
        return GlassrackRepository(
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                GlassrackDatabase::class.java,
            ).setQueryCoroutineContext(queryContext).build(),
            vault = SecureVault(ApplicationProvider.getApplicationContext()),
            preferencesStore = PreferencesStore(
                PreferenceDataStoreFactory.createWithPath(produceFile = { prefsPath }),
            ),
        )
    }

    private val server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")

    private fun tunnel(id: String, serverId: String = server.id) = PortForwardTunnel(
        id = id,
        serverId = serverId,
        label = "Forward $id",
        localPort = 8096,
        remoteHost = "127.0.0.1",
        remotePort = 8096,
    )

    private val keyRecord = SshKeyRecord(
        id = "key-1",
        alias = "lab-ed25519",
        algorithm = SshKeyAlgorithm.ED25519,
        publicKeyOpenSsh = "ssh-ed25519 AAAA",
        fingerprintSha256 = "SHA256:abc",
        privateKeyVaultRef = "key_lab",
        hasPassphrase = false,
        createdAtEpochMs = 1L,
    )

    @Test
    fun deleteServerAlsoRemovesTheNodesTunnels() = runTest {
        val repo = repository(this)
        repo.upsertServer(server)
        repo.upsertServer(Server(id = "srv-2", name = "node-b", primaryHost = "10.0.0.6"))
        repo.upsertTunnel(tunnel("tun-1"))
        repo.upsertTunnel(tunnel("tun-2"))
        repo.upsertTunnel(tunnel("tun-other", serverId = "srv-2"))

        assertEquals(3, repo.tunnels.first().size)

        repo.deleteServer(server.id)

        val remaining = repo.tunnels.first()
        assertEquals(listOf("tun-other"), remaining.map { it.id })
        assertEquals(listOf("srv-2"), remaining.map { it.serverId }.distinct())
    }

    @Test
    fun deleteServerWithoutTunnelsIsANoOpForTheTunnelTable() = runTest {
        val repo = repository(this)
        repo.upsertServer(server)
        repo.deleteServer(server.id)
        assertTrue(repo.tunnels.first().isEmpty())
    }

    @Test
    fun backupV2RoundTripCarriesTunnelsAndSshKeyMetadataIntoAFreshDatabase() = runTest {
        val source = repository(this)
        source.upsertServer(server)
        source.upsertTunnel(tunnel("tun-1"))
        source.upsertTunnel(tunnel("tun-2", serverId = server.id).copy(localPort = 8123))
        source.saveSshKey(
            record = keyRecord,
            privateKeyPem = "not-a-real-key", // stored in the device vault, never in the backup
            passphrase = null,
        )

        val bundleJson = source.exportEncryptedBackup(passphrase = "correct horse", nowEpochMs = 42L)

        // Restore into a completely fresh database.
        val target = repository(this)
        target.importEncryptedBackup(bundleJson, passphrase = "correct horse")

        val restoredTunnels = target.tunnels.first().sortedBy { it.id }
        assertEquals(2, restoredTunnels.size)
        assertTrue(restoredTunnels.all { it.serverId == server.id })
        assertEquals(8123, restoredTunnels.first { it.id == "tun-2" }.localPort)

        val restoredKeys = target.sshKeys.first()
        assertEquals(listOf(keyRecord.id), restoredKeys.map { it.id })
        assertEquals(keyRecord.fingerprintSha256, restoredKeys.single().fingerprintSha256)

        assertEquals(listOf(server.id), target.getServersOnce().map { it.id })
    }

    @Test
    fun wrongPassphraseFailsTheImportWithoutTouchingTheTargetDatabase() = runTest {
        val source = repository(this)
        source.upsertServer(server)
        source.upsertTunnel(tunnel("tun-1"))
        val bundleJson = source.exportEncryptedBackup(passphrase = "correct horse", nowEpochMs = 42L)

        val target = repository(this)
        val failure = runCatching {
            target.importEncryptedBackup(bundleJson, passphrase = "wrong")
        }
        assertTrue(failure.isFailure)
        assertTrue(target.getServersOnce().isEmpty())
        assertTrue(target.tunnels.first().isEmpty())
    }
}
