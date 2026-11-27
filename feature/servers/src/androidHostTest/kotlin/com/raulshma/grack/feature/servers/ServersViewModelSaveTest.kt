package com.raulshma.grack.feature.servers

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.database.GlassrackDatabase
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.database.PreferencesStore
import com.raulshma.grack.core.model.AuthType
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.sudoSecretVaultId
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.HomelabController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Saving a node: the stored capabilities describe the SSH login user, so they are dropped when
 * that user changes and kept when it does not — whatever else the same save does to the
 * credentials. Hermetic: the controller never reaches a node (stub transport), in-memory Room,
 * everything on the test scheduler.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ServersViewModelSaveTest {

    private class Harness(val repository: GlassrackRepository, val vault: SecureVault, val viewModel: ServersViewModel)

    private suspend fun TestScope.withHarness(block: suspend (Harness) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryContext = coroutineContext.minusKey(Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-save-test-${UUID.randomUUID()}.preferences_pb")
            .absolutePath
            .toPath()
        val vault = SecureVault(context)
        val db = Room.inMemoryDatabaseBuilder(context, GlassrackDatabase::class.java)
            .setQueryCoroutineContext(queryContext)
            .build()
        val dataStoreScope = CoroutineScope(queryContext + SupervisorJob())
        val repository = GlassrackRepository(
            db = db,
            vault = vault,
            preferencesStore = PreferencesStore(
                PreferenceDataStoreFactory.createWithPath(scope = dataStoreScope, produceFile = { prefsPath }),
            ),
        )
        val controller = HomelabController(repository, StubSshClient(), coroutineContext + SupervisorJob())
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ServersViewModel(
                        repository = repository,
                        controller = controller,
                        vault = vault,
                        biometricGate = BiometricGate(context),
                        transferStreams = null,
                        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                    ) as T
            },
        )[ServersViewModel::class.java]
        try {
            block(Harness(repository, vault, viewModel))
        } finally {
            controller.stop()
            store.clear()
            dataStoreScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /** As a finished capability probe leaves a node whose login user is root. */
    private val probedAsRoot = OsCapabilities(
        distroId = "debian",
        distro = "Debian GNU/Linux 12 (bookworm)",
        isRoot = true,
        hasDocker = true,
        hasDockerCompose = true,
        probeVersion = 1,
    )

    private val storedAsRoot = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        username = "root",
        capabilities = probedAsRoot,
    )

    /** Waits for work that hops to a real thread (the device vault) before it comes back to the scheduler. */
    private suspend fun TestScope.awaitUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            testScheduler.runCurrent()
            Thread.sleep(10)
        }
        testScheduler.runCurrent()
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }

    @Test
    fun changingTheSshUserDropsTheCapabilitiesOfTheOldOne() = runTest {
        withHarness { h ->
            h.repository.upsertServer(storedAsRoot)
            testScheduler.runCurrent()

            // Root is replaced by a normal user who logs in with a password — in the same save.
            h.viewModel.saveServer(storedAsRoot.copy(username = "pi-user", authType = AuthType.PASSWORD), ServerSecretEdits(loginPassword = "login-pw"))

            awaitUntil { h.repository.getServerById("srv-1")?.username == "pi-user" }
            val stored = h.repository.getServerById("srv-1")!!
            assertEquals("pi-user", stored.username)
            // Otherwise every privileged action would still run bare, as if the login were root.
            assertFalse(stored.capabilities.isRoot)
            assertEquals(OsCapabilities(), stored.capabilities)
            // The rest of the save is intact.
            assertEquals("srv_pw_srv-1", stored.credentialVaultId)
            assertEquals("login-pw", h.vault.getSecret("srv_pw_srv-1"))
        }
    }

    @Test
    fun clearingTheLoginPasswordInTheSameSaveStillDropsThem() = runTest {
        withHarness { h ->
            val withPassword = storedAsRoot.copy(username = "pi-user", authType = AuthType.PASSWORD, credentialVaultId = "srv_pw_srv-1")
                .let { it.copy(capabilities = probedAsRoot) }
            h.repository.upsertServer(withPassword)
            testScheduler.runCurrent()

            h.viewModel.saveServer(withPassword.copy(username = "pi"), ServerSecretEdits(clearLoginPassword = true))

            awaitUntil { h.repository.getServerById("srv-1")?.username == "pi" }
            val stored = h.repository.getServerById("srv-1")!!
            assertEquals(OsCapabilities(), stored.capabilities)
            assertNull(stored.credentialVaultId)
        }
    }

    @Test
    fun keepingTheSshUserKeepsTheProbedCapabilities() = runTest {
        withHarness { h ->
            h.repository.upsertServer(storedAsRoot)
            testScheduler.runCurrent()

            h.viewModel.saveServer(storedAsRoot.copy(name = "node-b", sudoAuth = SudoAuth.SEPARATE_PASSWORD), ServerSecretEdits(sudoPassword = "sudo-pw"))

            awaitUntil { h.repository.getServerById("srv-1")?.name == "node-b" }
            val stored = h.repository.getServerById("srv-1")!!
            assertEquals(probedAsRoot, stored.capabilities)
            assertEquals("sudo-pw", h.vault.getSecret(sudoSecretVaultId("srv-1")))
        }
    }
}
