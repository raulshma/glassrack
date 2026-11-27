package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.ContainerUpdateStatus
import com.raulshma.grack.core.model.NodeUpdateReport
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.OsScanStatus
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3B item 2: store-aware container update scanning. Covers the verdict matrix against
 * real registry answers — buildx index-digest match/mismatch, the no-buildx classic-store
 * fallback via `manifest inspect -v` config digests, a refused lookup -> UNKNOWN, Podman ->
 * UNKNOWN — the batched probe scripts and their parsers, and [ContainerInfo.updateAvailable]
 * set from the result.
 *
 * FIXTURE PROVENANCE — REAL. The `docker ps` / `docker images --no-trunc` rows, the batched
 * `docker buildx imagetools inspect` output and the batched `docker manifest inspect -v`
 * output below were captured on 2026-11-27 on a real Docker host (Engine 29.10, classic
 * `overlay2` image store) by running the scripts this code generates, with the references it
 * generates, under bash, dash and sh — identical records modulo completion order. Two things are not verbatim: compose
 * working-dir and config-file paths are replaced by neutral ones, and [buildxMissing] marks
 * buildx as absent with a wording composed for the occasion (docker's real exit code for an
 * unknown command, 1, was confirmed; its plugin wording was not captured). Every other value
 * — digests, image ids, exit codes, error texts including Docker Hub's `429` and gcr.io's
 * token failure — is as the host printed it.
 */
/** REAL: Docker Hub's answer once the anonymous pull budget of an IP is spent. */
private val dockerHubRateLimited = "ERROR: unexpected status from HEAD request to https://registry-1.docker.io/v2/library/debian/manifests/bookworm: 429 Too Many Requests"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ContainerUpdateScanTest {

    // ------------------------------------------------------------------
    // REAL host captures (see class KDoc)
    // ------------------------------------------------------------------

    /** `docker ps -a --no-trunc --format …`: five containers of public images; compose paths anonymized. */
    private val psFixture = """
        ===PS===
        92c0bf5320c2457bda847bcf254229c4a72f9f278e9949b4a000f15c8f9070e7|grack-repro-pi|debian:bookworm|running|Up 29 minutes|||||127.0.0.1:2222->22/tcp
        c4afdb54dbe4906b049dc84ead5a2d9f7a16944d25714d1f3ff53eba081aa313|radarr|linuxserver/radarr|running|Up 2 days|media_stack|radarr|/opt/media-stack|/opt/media-stack/compose.yaml|0.0.0.0:7878->7878/tcp, [::]:7878->7878/tcp
        230f4e347d4c076a992cb639638793677843e3af183eb98f577e0d898d4c1527|lidarr|lscr.io/linuxserver/lidarr:latest|running|Up 2 days|media_stack|lidarr|/opt/media-stack|/opt/media-stack/compose.yaml|0.0.0.0:8686->8686/tcp, [::]:8686->8686/tcp
        42d39629daa3276129d663ec0986f51c8e5e97b080522bda4a938bf561b5dfe9|jellyfin|jellyfin/jellyfin|running|Up 2 days (healthy)|media_stack|jellyfin|/opt/media-stack|/opt/media-stack/compose.yaml|0.0.0.0:8096->8096/tcp, [::]:8096->8096/tcp
        6fa6d09001b03ebfe11ad6dab82700b2de084a2a3b96bf4e1042a1931df499a3|karakeep-chrome-1|gcr.io/zenika-hub/alpine-chrome:123|running|Up 2 days|karakeep|chrome|/opt/media-stack|/opt/media-stack/compose.yaml|
    """.trimIndent()

    /** `docker images --no-trunc --format …` on the same host: full ids (`sha256:<64 hex>`) and RepoDigests. */
    private val imagesFixture = """
        ===IMAGES===
        sha256:a36824f1b4c6892ddfeb448726ace7f338ee14b4113288ecc813e266370d5c90|debian|bookworm|sha256:1d04200c2ece1d4d75a2a6f8e11ebce7d86a2ae28b34bb2c9cbb3ee9f4953e75|117MB|4 days ago
        sha256:e402b8025e438e9a5414d1a5973ad57792524b9d6dc1997dce4251b6b866bb76|lscr.io/linuxserver/lidarr|latest|sha256:8fb40ada166dd1880cb1b166df5bcbb081107523f20ab60df05c313e2052a467|294MB|9 days ago
        sha256:20b7b45d4644d936f35d0bcad0c3affd2cb88bb28dda51264abef227d452413c|linuxserver/radarr|latest|sha256:044788f5508b7f67a3e0368cdd142c77c4d4cd5aec2efaba53e1a68b6b6231e7|227MB|11 days ago
        sha256:995797bf87597b4247190e0034827f7887b34e75a4f603374591eab4f44172cb|jellyfin/jellyfin|latest|sha256:fea863d0cd9ec445985d9c75fc6ed64e38a1d2766cf0c250a33d553f4fc1b771|1.72GB|4 weeks ago
        sha256:bdc5d762e8c4219ec0e1693b2293e415b89e799c3974d0b8998dc6f971af7c92|gcr.io/zenika-hub/alpine-chrome|123|sha256:e38563d4475a3d791e986500a2e4125c9afd13798067138881cf770b1f6f3980|673MB|2 years ago
    """.trimIndent()

    /** One buildx batch over the five references: three answered, Docker Hub rate-limited one, gcr.io refused one. */
    private val buildxBatch = """
        ===BUILDX===
        __BUILDX__=ok
        __PROBE__|1||gcr.io/zenika-hub/alpine-chrome:123|ERROR: failed to authorize: failed to fetch anonymous token: unexpected status from GET request to https://gcr.io/v2/token?scope=repository%3Azenika-hub%2Falpin
        __PROBE__|1||debian:bookworm|ERROR: unexpected status from HEAD request to https://registry-1.docker.io/v2/library/debian/manifests/bookworm: 429 Too Many Requests
        __PROBE__|0|sha256:fea863d0cd9ec445985d9c75fc6ed64e38a1d2766cf0c250a33d553f4fc1b771|jellyfin/jellyfin:latest|
        __PROBE__|0|sha256:044788f5508b7f67a3e0368cdd142c77c4d4cd5aec2efaba53e1a68b6b6231e7|linuxserver/radarr:latest|
        __PROBE__|0|sha256:c4285344c994b34e1ae7db8d57c1702dcfe88b8e10c98a0cdf0f74aba8b58b9c|lscr.io/linuxserver/lidarr:latest|
        ===DRIVER===
        overlay2
    """.trimIndent()

    /** One manifest batch over the same five: the lscr.io / Docker Hub ones answered, the rate-limited and the refused ones exited 1. */
    private val manifestBatch = """
        ===MANIFESTV===
        __MANIFEST_BEGIN__|linuxserver/radarr:latest
        "digest": "sha256:ff1b1f21a200e19f52017bd1544031143715b1ab262b823a9dcb916a71da0956"
        "digest": "sha256:20b7b45d4644d936f35d0bcad0c3affd2cb88bb28dda51264abef227d452413c"
        "digest": "sha256:7970ef2aa55e0c5c0d8e6de65fc43820ec7ad65d6d950afc0d003b9f3105bd60"
        "digest": "sha256:f6a4c3e338ed76d3e90c1ce7a15013a45c9b55febc952bc905eed245ce0bb8d2"
        "digest": "sha256:23b6a4730181bf0e1562b48132561e7e2d7a6f31514f67b319748fee75e49b7c"
        "digest": "sha256:01dcaa168b622d0b2cf02b1b020eca554ef18a97385b1d14eda3e5c68d765471"
        "digest": "sha256:efc1a479174cc8fbce9f13bfbc982c63d605d4dff5cc3bfe037ba0b8294df000"
        "digest": "sha256:d3ae61b552ec610566103aa606e7ce4b4a9258e8b68197f00c0579c7e2547381"
        "digest": "sha256:e2a4e0d6c6f82772da017d808e4bc180d19e21d790064ec7d841f52d5871e07a"
        "digest": "sha256:7b15768d8948666c284a88700b94b12dcd46ec5b8c887e97472b56a3006d8cbd"
        "digest": "sha256:8e0888568a229456a1e2d8b78abdf908357883114993ebdd2818419e2f786c1d"
        "digest": "sha256:f80647895f31a0cb031d0061890f18fd6de4683adc73eaab589b8969dad08756"
        "digest": "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        "digest": "sha256:85051221a9b275329b39a01e46267fda84716dbbaed13a654d2f18dfce97336d"
        "digest": "sha256:98c26d9ab7e0b4817341607cdf132f018fdddbe8859023b137477af6385d376c"
        "digest": "sha256:ff1b1f21a200e19f52017bd1544031143715b1ab262b823a9dcb916a71da0956"
        "digest": "sha256:fb3d051248c0d3d3669986800b67f8642537717260414829276e27aed3a175dc"
        "digest": "sha256:7f16a30b2d9f31b6bc1d0abab2686b7a668fbf9d1b5f46d5d4d41853f7a796c8"
        "digest": "sha256:72effc04381d741b6adf6e78a73604b00a15ec5ce52d031f35e6c3212bacc7c3"
        "digest": "sha256:f6a4c3e338ed76d3e90c1ce7a15013a45c9b55febc952bc905eed245ce0bb8d2"
        "digest": "sha256:2910db0e655db3ef958415c06658f34d999e42d24be8f5e417541ec4242eb1b0"
        "digest": "sha256:70ee18a67940ca01cecfa70db175e34dd557850b5d4757f44a5f1214a686d5b8"
        "digest": "sha256:60b48546f9fa00cad1b56430b2688d704fa2bd41e6444a1700cc177601d75e77"
        "digest": "sha256:c4ae34a52c1c610b54460dddefde232bbe0dfad4c06bc89d051f5edb8420a24f"
        "digest": "sha256:e8641db2eef98a10ef904e066ec643e191587827242e5d51ce3ffa3c489485b0"
        "digest": "sha256:ca838472dd9de38510035fc66f1c9208777693b915af5daef5a5a88d2f6df8d7"
        "digest": "sha256:734080e43c65a19d7d05ae821a76245c74a436625644d6ecd3c59105ba7c9eee"
        "digest": "sha256:8c87c95e13edc308659c37b80434fce49318e90b3ebe7c227af526b6e999d1ee"
        "digest": "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        "digest": "sha256:13216ad120959d2dd043413893cfb18119f31ae979edc848336edbe236cf5b7b"
        "digest": "sha256:631fea0f94e4f4542534d51cbe486607b044e2a5175a3e7e0e5067d74407e5fe"
        "digest": "sha256:fb3d051248c0d3d3669986800b67f8642537717260414829276e27aed3a175dc"
        __MANIFEST_EXIT__=0
        __MANIFEST_END__|linuxserver/radarr:latest
        __MANIFEST_BEGIN__|lscr.io/linuxserver/lidarr:latest
        "digest": "sha256:019985f4289fcd04d830febac4120c4849be7787e8539ec5f1a38d101dbe5434"
        "digest": "sha256:4d45a5a7a6cef67ee1ffd779074a715a621782762d0b858f24fac616582e603d"
        "digest": "sha256:a1289cb1b2e1de8c50a27734ef8c33707f815455159090f89e6a4155aeb1cff0"
        "digest": "sha256:f6a4c3e338ed76d3e90c1ce7a15013a45c9b55febc952bc905eed245ce0bb8d2"
        "digest": "sha256:70f812ec3f0570c4af7815348886aa5e030e3869a73aee7087bcefdcbb56e56b"
        "digest": "sha256:6dd2c621127f2f6535f5a1178785030514ff6732f7504dd9708b9b413654ab86"
        "digest": "sha256:734d7249cdb1f283b6bbaff379c1741ec0ac42562a2cfa3427d01f80831448a3"
        "digest": "sha256:9d54ebc61246dca43c72ab509186bf2b8ef5a956f1f68153c8c29ffb150ecb67"
        "digest": "sha256:90c34420306bfd0596f7a68532da3379ba8ca88f76ae7527ecc7a0b9d1ad61ee"
        "digest": "sha256:a8b73db4aacb41a9b49850dac2435cfac3ab7242a6cba5f8fe57ed78aeb26c57"
        "digest": "sha256:b768b8c3bb496088cfb0cd0932939560504dd77b16827754d2233dc963df54b9"
        "digest": "sha256:c30853b7a1bb390be0854024fb4db3897898e006a80bad6eec6d5a88641f4fea"
        "digest": "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        "digest": "sha256:f6ef2ee392b04f7a5d144298a432eb836f69e5145d0d9016edf7855886a43487"
        "digest": "sha256:843c21cdcfa9950fb3ba3decfe424e845315920ab9bf9c639ac4a4903685645e"
        "digest": "sha256:019985f4289fcd04d830febac4120c4849be7787e8539ec5f1a38d101dbe5434"
        "digest": "sha256:0175046179b7627568b791591dfdc49b80caab7ff8384d2ba96cd7c27d14e176"
        "digest": "sha256:25b1e6e82315e947f23157183c5147f2fc514c2b0b7920ecc02a09ffdf571ad0"
        "digest": "sha256:3a577e8919dd655c6e553ff3beb5e44c3d110b67c0bb24853e2b22e889997b1e"
        "digest": "sha256:f6a4c3e338ed76d3e90c1ce7a15013a45c9b55febc952bc905eed245ce0bb8d2"
        "digest": "sha256:63d5122e41c79e616fb54d7b58895cdf67ba04f001f9313d3fc47dea52061314"
        "digest": "sha256:a4969be238be163f202ee19dc07a98cb08d996b34bbd8c2b500d72e921d98b0c"
        "digest": "sha256:fb4690ef6eacb2a50c9208fc7da0e8fad951e6db532d1e5701f04d4e0f294fd3"
        "digest": "sha256:28432009d5d844f028570e39bcfff35035e3b6d63b89b68ef92adbcde6f66c00"
        "digest": "sha256:89910489a59d10ab08479577e204070d703357fc711e872fcf9153daaf5c1d98"
        "digest": "sha256:af5f8098e3e6fdc8b3c21e98c6c57fe0140ce5382b501682a9b9b6c88771a44f"
        "digest": "sha256:e7b087a13f7363345c54328d04e920a495638bc7328f8b500e9396a37a790ee4"
        "digest": "sha256:853068f27aa2510cd8c033ab577cc6437daf39a414be1be9c2fc8f91c8faa4ba"
        "digest": "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        "digest": "sha256:f2e9579df138c1f600254cb8ff27604f0dfeac353e9b6b752d9bad5b6fc768ca"
        "digest": "sha256:c3302b16233be3865add030e23e0fdeef754699f2358ecb96dc80beb421ead6e"
        "digest": "sha256:0175046179b7627568b791591dfdc49b80caab7ff8384d2ba96cd7c27d14e176"
        __MANIFEST_EXIT__=0
        __MANIFEST_END__|lscr.io/linuxserver/lidarr:latest
        __MANIFEST_BEGIN__|jellyfin/jellyfin:latest
        "digest": "sha256:7da5184caa38c553f219f6772476540081f392a68049e2b68c923f6daf877f8e"
        "digest": "sha256:995797bf87597b4247190e0034827f7887b34e75a4f603374591eab4f44172cb"
        "digest": "sha256:f2be67225471500c788ce88c597eb5ea8336e912ad1ca4815650e0281afe2034"
        "digest": "sha256:0a41bce6ef14b2e6d675a3210eb1f7f768e5f7e3578f09c458b8c57b78a0901b"
        "digest": "sha256:526fffcbc249175d60463668711b5334da75fec86a43b7d5e1ce1a92e3e837d4"
        "digest": "sha256:2ad4c3afb22d86b7332e5fdaea07d58c89f17f77496bde606870ee5458f0d764"
        "digest": "sha256:4f4fb700ef54461cfa02571ae0db9a0dc1e0cdb5577484a6d75e68dc38e8acc1"
        "digest": "sha256:5ec2bdefc613e5b962dd435506edc8accdb424669c0d6d5300dd3b009785c8fa"
        "digest": "sha256:00d1feae788fc6984839781f24629f5c1222d5e5db0c1a4867db8f904e19e587"
        "digest": "sha256:eb04fc9266f4c68b461c3f25fb53b6d4815548cc03724124b651c24dc89e5482"
        "digest": "sha256:da8ad6b417985357bc88c0d3d9c127bf2ad48beff5f69dbbfdc804392fe8ce99"
        "digest": "sha256:e4f64296b96e1164c9495e41caa3c3abdfcb5a4dedc06bb57f6fd3c7c809e774"
        "digest": "sha256:c9f6c6d162f8eedc7344a7c66fb4a2f1fe5c871322794ba477a38ca04add9405"
        "digest": "sha256:7455036b369e17a568c7768619051af15b2c8204cc4042b109c1fe3ad7035a44"
        "digest": "sha256:ddc0c1e36a6793411b6c8d48e1ddad2987405d377da92bcf532ed3181c2e4d62"
        "digest": "sha256:b07e3c78fcc289d5f71179be4d723916dcdc1e47ff6d2ef36032bdec4d12102c"
        "digest": "sha256:a616ce4f70da30830c438c3abef3c546a626e55a413d968b1e0ab6353d87a573"
        "digest": "sha256:4f4fb700ef54461cfa02571ae0db9a0dc1e0cdb5577484a6d75e68dc38e8acc1"
        "digest": "sha256:b0d2da0aee584b027044990baa8519eb1cec00b2de13e8f9eee807f0ea6da3de"
        "digest": "sha256:af18fb08887bafccd15182942758397bc46fcb0d55e63f7609522a8b3af426a9"
        "digest": "sha256:b126f00c7ca93101cbbe9851278522e98ef80407af5d013dacd59dbaccdd3941"
        "digest": "sha256:c8843b1dfa45b4713be1a0612e17a9010d5c5bd4ec5e82507f74bc28518720be"
        "digest": "sha256:255cb25200441a781db63c6ff0e120b7cd677901e1b13a69971dd0158ff74f5c"
        "digest": "sha256:3ac0c13e8c56feea726461bed122e110ae7f4ea65f392e5d202db6af581b58ab"
        __MANIFEST_EXIT__=0
        __MANIFEST_END__|jellyfin/jellyfin:latest
        __MANIFEST_BEGIN__|debian:bookworm
        __MANIFEST_EXIT__=1
        __MANIFEST_END__|debian:bookworm
        __MANIFEST_BEGIN__|gcr.io/zenika-hub/alpine-chrome:123
        __MANIFEST_EXIT__=1
        __MANIFEST_END__|gcr.io/zenika-hub/alpine-chrome:123
    """.trimIndent()

    /** SYNTHETIC derivative of [buildxBatch]: the node has no buildx plugin, so every probe fails at once. */
    private val buildxMissing = """
        ===BUILDX===
        __BUILDX__=missing
        __PROBE__|1||gcr.io/zenika-hub/alpine-chrome:123|ERROR: failed to authorize: failed to fetch anonymous token: unexpected status from GET request to https://gcr.io/v2/token?scope=repository%3Azenika-hub%2Falpin
        __PROBE__|1||debian:bookworm|ERROR: unexpected status from HEAD request to https://registry-1.docker.io/v2/library/debian/manifests/bookworm: 429 Too Many Requests
        __PROBE__|1||jellyfin/jellyfin:latest|docker: 'buildx' is not a docker command. See 'docker --help'
        __PROBE__|1||linuxserver/radarr:latest|docker: 'buildx' is not a docker command. See 'docker --help'
        __PROBE__|1||lscr.io/linuxserver/lidarr:latest|docker: 'buildx' is not a docker command. See 'docker --help'
        ===DRIVER===
        overlay2
    """.trimIndent()

    /** SYNTHETIC derivative of [buildxBatch]: Docker Hub's real `429` answer (captured on debian:bookworm) on every probe. */
    private val buildxRateLimited = buildxBatch.lineSequence().joinToString("\n") { line ->
        if (line.startsWith("__PROBE__|")) "__PROBE__|1||" + line.split('|')[3] + "|" + dockerHubRateLimited else line
    }

    /** SYNTHETIC derivative of [manifestBatch]: every lookup refused the way gcr.io's was. */
    private val manifestAllRefused = manifestBatch.lineSequence()
        .filterNot { it.startsWith("\"digest\"") }
        .joinToString("\n") { if (it.startsWith("__MANIFEST_EXIT__=0")) "__MANIFEST_EXIT__=1" else it }

    private val dockerServer = Server(
        id = "srv-docker",
        name = "docker-node",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(hasDocker = true, hasPodman = false),
    )

    private val podmanOnlyServer = Server(
        id = "srv-podman",
        name = "podman-node",
        primaryHost = "10.0.0.7",
        capabilities = OsCapabilities(hasDocker = false, hasPodman = true),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    private suspend fun TestScope.withController(block: suspend (ControllerHarness) -> Unit) {
        val h = harness(this)
        try {
            block(h)
        } finally {
            h.controller.stop()
        }
    }

    /** Serves the real host's probes; individual tests override [buildx], [manifest] or [images]. */
    private fun ControllerHarness.serveRealHost(
        buildx: String = buildxBatch,
        manifest: String = manifestBatch,
        images: String = imagesFixture,
        ps: String = psFixture,
    ) {
        fake.executeHandler = { request ->
            when {
                request.command.contains("===PS===") -> SshExecResult(0, ps, "", 1L)
                request.command.contains("===IMAGES===") -> SshExecResult(0, images, "", 1L)
                request.command.contains("===BUILDX===") -> SshExecResult(0, buildx, "", 1L)
                request.command.contains("===MANIFESTV===") -> SshExecResult(0, manifest, "", 1L)
                else -> throw AssertionError("unexpected command: ${request.command}")
            }
        }
    }

    private fun ControllerHarness.verdicts(result: ContainerUpdateScanResult) =
        result.entries.associate { it.containerName to it.status }

    // ------------------------------------------------------------------
    // Verdicts against real registry answers
    // ------------------------------------------------------------------

    @Test
    fun realHostVerdictsMatchWhatTheRegistryAnswered() = runTest {
        withController { h ->
            h.serveRealHost()

            val result = h.controller.scanContainerUpdates(dockerServer)

            // buildx: radarr and jellyfin match their index digests, lidarr's differs.
            // debian:bookworm was rate-limited on buildx and again by the fallback;
            // gcr.io refused both ways. Neither is guessed.
            assertEquals(
                mapOf(
                    "radarr" to ContainerUpdateStatus.UP_TO_DATE,
                    "lidarr" to ContainerUpdateStatus.UPDATE_AVAILABLE,
                    "jellyfin" to ContainerUpdateStatus.UP_TO_DATE,
                    "grack-repro-pi" to ContainerUpdateStatus.UNKNOWN,
                    "karakeep-chrome-1" to ContainerUpdateStatus.UNKNOWN,
                ),
                h.verdicts(result),
            )
            val lidarr = result.entries.single { it.containerName == "lidarr" }
            assertEquals("sha256:8fb40ada166dd1880cb1b166df5bcbb081107523f20ab60df05c313e2052a467", lidarr.localRepoDigest)
            assertEquals("sha256:c4285344c994b34e1ae7db8d57c1702dcfe88b8e10c98a0cdf0f74aba8b58b9c", lidarr.remoteDigest)
            // Stack coordinates survive for the Update-stack action.
            assertEquals("media_stack", lidarr.composeProject)
            assertEquals("/opt/media-stack", lidarr.composeWorkingDir)
            assertEquals("/opt/media-stack/compose.yaml", lidarr.composeConfigFiles)
            assertEquals("sha256:044788f5508b7f67a3e0368cdd142c77c4d4cd5aec2efaba53e1a68b6b6231e7", result.entries.single { it.containerName == "radarr" }.remoteDigest)
            assertTrue(result.containers.none { it.name == "grack-repro-pi" && it.updateAvailable })

            assertTrue(result.containers.single { it.name == "lidarr" }.updateAvailable)
            assertFalse(result.containers.single { it.name == "radarr" }.updateAvailable)
            assertFalse(result.containers.single { it.name == "karakeep-chrome-1" }.updateAvailable)

            assertEquals(5, result.probedRefCount)
            assertEquals(2, result.fallbackRefCount)
            assertFalse(result.timedOut)
            assertEquals(
                "2 of 5 registry lookups failed (1 rate-limited): " +
                    "ERROR: unexpected status from HEAD request to " +
                    "https://registry-1.docker.io/v2/library/debian/manifests/bookworm: 429 Too Many Requests | " +
                    "ERROR: failed to authorize: failed to fetch anonymous token: unexpected status from GET request " +
                    "to https://gcr.io/v2/token?scope=repository%3Azenika-hub%2Falpin",
                result.detail,
            )
        }
    }

    private val realHostDetail =
        "2 of 5 registry lookups failed (1 rate-limited): " +
            "ERROR: unexpected status from HEAD request to " +
            "https://registry-1.docker.io/v2/library/debian/manifests/bookworm: 429 Too Many Requests | " +
            "ERROR: failed to authorize: failed to fetch anonymous token: unexpected status from GET request " +
            "to https://gcr.io/v2/token?scope=repository%3Azenika-hub%2Falpin"

    @Test
    fun theRegistryIsAskedOncePerWaveNotOncePerImage() = runTest {
        withController { h ->
            h.serveRealHost()

            h.controller.scanContainerUpdates(dockerServer)

            val commands = h.fake.executeRequests.map { it.command }
            // Listing, image listing, one buildx batch, one manifest batch: four round trips for five images.
            assertEquals(4, commands.size, commands.toString())
            assertTrue(commands[1].contains("images --no-trunc"), commands[1])
            val buildx = commands.single { it.contains("===BUILDX===") }
            for (ref in refs) {
                assertEquals(1, Regex("'${Regex.escape(ref)}'").findAll(buildx).count(), buildx)
            }
            // Only the references buildx could not answer go to the fallback, and only on the classic store.
            val manifest = commands.single { it.contains("===MANIFESTV===") }
            assertTrue(manifest.contains("'debian:bookworm'"), manifest)
            assertTrue(manifest.contains("'gcr.io/zenika-hub/alpine-chrome:123'"), manifest)
            assertFalse(manifest.contains("radarr"), manifest)
            assertFalse(manifest.contains("jellyfin"), manifest)
            // The batch budget is one probe timeout per wave of six plus headroom.
            val budgets = h.fake.executeRequests.filter { it.command.contains("===BUILDX===") || it.command.contains("===MANIFESTV===") }
            assertEquals(listOf(45_000L, 45_000L), budgets.map { it.timeoutMs })
        }
    }

    @Test
    fun withoutBuildxTheClassicStoreDecidesThroughManifestInspect() = runTest {
        withController { h ->
            h.serveRealHost(buildx = buildxMissing)

            val result = h.controller.scanContainerUpdates(dockerServer)

            // The fallback agrees with buildx on every image it could answer: the current
            // images' full ids appear as remote config digests, lidarr's does not.
            assertEquals(
                mapOf(
                    "radarr" to ContainerUpdateStatus.UP_TO_DATE,
                    "lidarr" to ContainerUpdateStatus.UPDATE_AVAILABLE,
                    "jellyfin" to ContainerUpdateStatus.UP_TO_DATE,
                    "grack-repro-pi" to ContainerUpdateStatus.UNKNOWN,
                    "karakeep-chrome-1" to ContainerUpdateStatus.UNKNOWN,
                ),
                h.verdicts(result),
            )
            assertEquals(5, result.fallbackRefCount)
            assertEquals("buildx is not installed on this node (2 of 5 images undetermined)", result.detail)
        }
    }

    @Test
    fun theContainerdStoreNeverGuessesThroughTheManifestFallback() = runTest {
        withController { h ->
            h.serveRealHost(
                buildx = buildxMissing.replace("===DRIVER===\noverlay2", "===DRIVER===\noverlayfs"),
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            // On the containerd store an image id is not a config digest: nothing to compare.
            assertEquals(5, result.entries.count { it.status == ContainerUpdateStatus.UNKNOWN })
            assertTrue(h.fake.executeRequests.none { it.command.contains("===MANIFESTV===") })
            assertEquals("buildx is not installed on this node (5 of 5 images undetermined)", result.detail)
        }
    }

    @Test
    fun aRateLimitedRegistryIsNamedAsSuch() = runTest {
        withController { h ->
            h.serveRealHost(buildx = buildxRateLimited, manifest = manifestAllRefused)

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(5, result.entries.count { it.status == ContainerUpdateStatus.UNKNOWN })
            assertTrue(
                result.detail!!.startsWith("5 of 5 registry lookups failed — rate-limited by the registry: $dockerHubRateLimited"),
                result.detail,
            )
        }
    }


    @Test
    fun aTruncatedIdIsNeverComparedAgainstAFullDigest() {
        // The default `docker images` listing prints 12-character ids; only a full one can equal a config digest.
        val probe = ContainerDigestProbe(
            manifestDigests = listOf("sha256:20b7b45d4644d936f35d0bcad0c3affd2cb88bb28dda51264abef227d452413c"),
            manifestExit = 0,
            driver = "overlay2",
        )
        assertEquals(ContainerUpdateStatus.UNKNOWN, UpdateParsers.decideContainerUpdate("", "20b7b45d4644", probe).status)
        assertEquals(
            ContainerUpdateStatus.UP_TO_DATE,
            UpdateParsers.decideContainerUpdate("", "sha256:20b7b45d4644d936f35d0bcad0c3affd2cb88bb28dda51264abef227d452413c", probe).status,
        )
        assertEquals(
            ContainerUpdateStatus.UPDATE_AVAILABLE,
            UpdateParsers.decideContainerUpdate("", "sha256:e402b8025e438e9a5414d1a5973ad57792524b9d6dc1997dce4251b6b866bb76", probe).status,
        )
    }

    @Test
    fun podmanNodesAreUndeterminedWithTheReason() = runTest {
        withController { h ->
            h.fake.executeHandler = { request ->
                when {
                    request.command.contains("===PS===") -> SshExecResult(0, psFixture, "", 1L)
                    request.command.contains("buildx imagetools inspect") ->
                        throw AssertionError("podman must not be digest-probed")
                    else -> throw AssertionError("unexpected command: ${request.command}")
                }
            }

            val result = h.controller.scanContainerUpdates(podmanOnlyServer)

            assertEquals(5, result.entries.count { it.status == ContainerUpdateStatus.UNKNOWN })
            assertEquals(HomelabController.PODMAN_UNSUPPORTED_DETAIL, result.detail)
            assertTrue(result.containers.none { it.updateAvailable })
        }
    }

    @Test
    fun digestPinnedReferencesAreNeverProbed() = runTest {
        withController { h ->
            // One added row: a container on a digest-pinned reference. (Synthetic; the real host has none.)
            val pinnedRef = "registry.example.com/custom/app@sha256:pinneddigest5555"
            h.serveRealHost(ps = psFixture + "\n0123456789abcdef|pinned|$pinnedRef|running|Up 1 hour|||||")

            val result = h.controller.scanContainerUpdates(dockerServer)

            val pinned = result.entries.single { it.containerName == "pinned" }
            assertEquals(ContainerUpdateStatus.PINNED, pinned.status)
            assertEquals("sha256:pinneddigest5555", pinned.localRepoDigest)
            assertTrue(h.fake.executeRequests.none { it.command.contains("registry.example.com") })
        }
    }

    @Test
    fun theSameImageBehindTwoReferencesIsAskedOnce() = runTest {
        withController { h ->
            // One added row (synthetic): the same image as radarr, spelled with its explicit tag.
            h.serveRealHost(ps = psFixture + "\n0123456789abcdef|radarr-again|linuxserver/radarr:latest|running|Up 1 hour|||||")

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(ContainerUpdateStatus.UP_TO_DATE, result.entries.single { it.containerName == "radarr-again" }.status)
            assertEquals(5, result.probedRefCount)
        }
    }

    @Test
    fun imagesMissingFromTheLocalStoreAreUndetermined() = runTest {
        withController { h ->
            h.serveRealHost(images = "===IMAGES===\n")

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(5, result.entries.count { it.status == ContainerUpdateStatus.UNKNOWN })
            assertEquals(0, result.probedRefCount)
            assertNull(result.detail)
        }
    }

    @Test
    fun anEmptyContainerListShortCircuitsTheScan() = runTest {
        withController { h ->
            h.serveRealHost(ps = "===PS===\n")

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertTrue(result.entries.isEmpty())
            assertTrue(result.containers.isEmpty())
            assertTrue(h.fake.executeRequests.none { it.command.contains("===IMAGES===") })
        }
    }

    // ------------------------------------------------------------------
    // Registry trouble
    // ------------------------------------------------------------------

    @Test
    fun aRegistryBatchThatOutlivesItsBudgetLeavesVerdictsUnknownAndTheScanIsTimeout() = runTest {
        withController { h ->
            h.serveRealHost()
            val serve = h.fake.executeHandler
            h.fake.executeHandler = { request ->
                if (request.command.contains("===BUILDX===")) withTimeout(1_000) { delay(2_000); SshExecResult(0, "", "", 1L) } else serve(request)
            }

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(5, result.entries.count { it.status == ContainerUpdateStatus.UNKNOWN })
            assertTrue(result.detail!!.startsWith("registry probes timed out after 45s"), result.detail)
            assertTrue(result.timedOut)
            // No driver was learned, so the fallback's interpretation would be a guess.
            assertTrue(h.fake.executeRequests.none { it.command.contains("===MANIFESTV===") })
        }
    }

    @Test
    fun aLostRegistryBatchLeavesVerdictsUnknownWithTheTransportError() = runTest {
        withController { h ->
            h.serveRealHost()
            val serve = h.fake.executeHandler
            h.fake.executeHandler = { request ->
                if (request.command.contains("===BUILDX===")) throw IOException("channel is closed") else serve(request)
            }

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(5, result.entries.count { it.status == ContainerUpdateStatus.UNKNOWN })
            assertEquals("channel is closed", result.detail)
            assertFalse(result.timedOut)
        }
    }

    @Test
    fun aRecordLostWithTheBatchStaysWithoutAVerdict() = runTest {
        withController { h ->
            // The batch came back without jellyfin's record and so did the fallback batch;
            // the gcr.io refusal of the real capture is still there alongside it.
            val withoutJellyfin = { line: String -> line.startsWith("__PROBE__") && line.split('|')[3] == "jellyfin/jellyfin:latest" }
            h.serveRealHost(
                buildx = buildxBatch.lineSequence().filterNot { withoutJellyfin(it) }.joinToString("\n"),
                manifest = manifestBatch.lineSequence()
                    .takeWhile { it != "__MANIFEST_BEGIN__|jellyfin/jellyfin:latest" }
                    .joinToString("\n"),
            )

            val result = h.controller.scanContainerUpdates(dockerServer)

            assertEquals(ContainerUpdateStatus.UP_TO_DATE, result.entries.single { it.containerName == "radarr" }.status)
            assertEquals(ContainerUpdateStatus.UNKNOWN, result.entries.single { it.containerName == "jellyfin" }.status)
            assertTrue(result.detail!!.startsWith("3 of 5 registry lookups failed (1 rate-limited, 1 without a result): "), result.detail)
        }
    }

    // ------------------------------------------------------------------
    // Whole scan: early publication, failure of the container half, ledger
    // ------------------------------------------------------------------

    private val aptListing = "===OSUPD===\nListing...\n__APT_LIST_EXIT__=0"
    private val registryMarkers = listOf("===PS===", "===IMAGES===", "===BUILDX===", "===MANIFESTV===")

    private fun ControllerHarness.serveWholeScan(containerListing: suspend () -> SshExecResult = { SshExecResult(0, psFixture, "", 1L) }) {
        val registry = fake.executeHandler
        fake.executeHandler = { request ->
            when {
                request.command == HomelabController.SUDO_PROBE_COMMAND -> SshExecResult(0, "", "", 1L)
                request.command.contains("update -qq") -> SshExecResult(0, "", "", 1L)
                request.command.contains("===OSUPD===") -> SshExecResult(0, aptListing, "", 1L)
                request.command.contains("===PS===") -> containerListing()
                else -> registry(request)
            }
        }
    }

    @Test
    fun theOsHalfIsPersistedBeforeTheRegistryPassStarts() = runTest {
        withController { h ->
            val dockerDebian = dockerServer.copy(capabilities = OsCapabilities(distroId = "debian", hasDocker = true))
            h.serveRealHost()
            var registryStarted = false
            val realHost = h.fake.executeHandler
            h.fake.executeHandler = { request ->
                if (request.command.contains("===BUILDX===")) registryStarted = true
                // The controller's own poller may ask for telemetry while the row exists; that is not this test's subject.
                if (registryMarkers.any { request.command.contains(it) }) realHost(request) else SshExecResult(0, "", "", 1L)
            }
            h.serveWholeScan()
            h.repository.upsertServer(dockerDebian)
            val published = mutableListOf<Pair<NodeUpdateReport, NodeUpdateReport?>>()

            val report = h.controller.scanNodeUpdates(dockerDebian, h.vault) { partial ->
                // Published, and already on disk, before the slow half begins.
                assertFalse(registryStarted, "the registry pass started before the OS half was published")
                published += partial to h.repository.getServerById(dockerDebian.id)?.updateReportJson?.let(NodeUpdateReport::fromJson)
            }

            val (partial, persisted) = published.single()
            assertEquals(OsScanStatus.OK, partial.os?.status)
            assertEquals(0, partial.containerUpdateCount)
            assertEquals(partial, persisted)
            assertEquals(1, report.containerUpdateCount)
            assertEquals(realHostDetail, report.containerScanDetail)
            // The finished report replaces the partial one, and the scan still writes one entry.
            assertEquals(report, NodeUpdateReport.fromJson(h.repository.getServerById(dockerDebian.id)?.updateReportJson))
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.also { assertEquals(1, it.size) }.single()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals(
                "apt: 0 pending (up to date); index refreshed via passwordless sudo. " +
                    "containers: 1 with updates, 2 up to date, 2 undetermined of 5 — $realHostDetail.",
                audit.outputSnippet,
            )
            assertTrue(audit.commandSanitized.endsWith("docker buildx imagetools inspect ×5 images; docker manifest inspect -v ×2 images"), audit.commandSanitized)
        }
    }

    @Test
    fun aFailedContainerPassKeepsThePreviousVerdictsAndSaysSo() = runTest {
        withController { h ->
            val dockerDebian = dockerServer.copy(capabilities = OsCapabilities(distroId = "debian", hasDocker = true))
            h.serveRealHost()
            h.serveWholeScan()
            h.repository.upsertServer(dockerDebian)
            val first = h.controller.scanNodeUpdates(dockerDebian, h.vault)
            assertEquals(1, first.containerUpdateCount)
            assertEquals(realHostDetail, first.containerScanDetail)

            // The next scan finds the container engine gone. It is handed the node as stored,
            // which is where the previous verdicts live.
            h.serveWholeScan(containerListing = { throw IOException("docker daemon is not responding") })
            val second = h.controller.scanNodeUpdates(h.repository.getServerById(dockerDebian.id)!!, h.vault)

            assertEquals(OsScanStatus.OK, second.os?.status)
            assertEquals(first.containers, second.containers)
            assertEquals(
                "the container check failed (docker daemon is not responding) — showing the previous scan's verdicts",
                second.containerScanDetail,
            )
            // The OS half succeeded, so the scan itself is SUCCESS — but its entry says the container half did not run.
            val audits = h.repository.auditEntries.first { it.size == 2 }
            assertTrue(audits.all { it.outcome == AuditOutcome.SUCCESS })
            val entries = audits.map { it.outputSnippet }
            assertEquals(1, entries.count { it.contains("of 5 — the container check failed (docker daemon is not responding)") }, entries.toString())
            assertEquals(1, entries.count { it.contains("2 undetermined of 5 — $realHostDetail") }, entries.toString())
        }
    }

    @Test
    fun aContainerPassThatTimesOutMakesTheScanTimeout() = runTest {
        withController { h ->
            val dockerDebian = dockerServer.copy(capabilities = OsCapabilities(distroId = "debian", hasDocker = true))
            h.serveWholeScan(containerListing = { withTimeout(1_000) { delay(2_000); SshExecResult(0, "", "", 1L) } })

            val report = h.controller.scanNodeUpdates(dockerDebian, h.vault)

            assertEquals(OsScanStatus.OK, report.os?.status)
            assertEquals("the container check timed out", report.containerScanDetail)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.single()
            assertEquals(AuditOutcome.TIMEOUT, audit.outcome)
        }
    }

    @Test
    fun aNodeWithoutDockerChecksContainersNotAtAll() = runTest {
        withController { h ->
            h.serveWholeScan()
            val plain = dockerServer.copy(capabilities = OsCapabilities(distroId = "debian", hasDocker = false))

            val report = h.controller.scanNodeUpdates(plain, h.vault)

            assertTrue(report.containers.isEmpty())
            assertEquals("", report.containerScanDetail)
            assertTrue(h.fake.executeRequests.none { it.command.contains("===PS===") })
            assertTrue(h.repository.auditEntries.first { it.isNotEmpty() }.single().outputSnippet.endsWith("containers: none (no docker on this node)."))
        }
    }

    // ------------------------------------------------------------------
    // Parsers over the REAL batches
    // ------------------------------------------------------------------

    private val refs = listOf(
        "linuxserver/radarr:latest",
        "lscr.io/linuxserver/lidarr:latest",
        "jellyfin/jellyfin:latest",
        "debian:bookworm",
        "gcr.io/zenika-hub/alpine-chrome:123",
    )

    @Test
    fun buildxBatchParserReadsRealRecordsWithTheirExitCodesAndReasons() {
        val probes = UpdateParsers.parseContainerDigestBatch(buildxBatch, refs, "overlay2")

        assertEquals("overlay2", UpdateParsers.parseStoreDriver(buildxBatch))
        assertEquals(true, UpdateParsers.parseBuildxAvailable(buildxBatch))
        assertEquals(false, UpdateParsers.parseBuildxAvailable(buildxMissing))
        assertNull(UpdateParsers.parseBuildxAvailable("===BUILDX===\n===DRIVER===\noverlay2"))
        assertEquals(refs.toSet(), probes.keys)

        val radarr = probes.getValue("linuxserver/radarr:latest")
        assertEquals("sha256:044788f5508b7f67a3e0368cdd142c77c4d4cd5aec2efaba53e1a68b6b6231e7", radarr.remoteIndexDigest)
        assertEquals(0, radarr.buildxExit)
        assertTrue(radarr.answered)
        // Docker Hub's rate limit, in its own words, is part of the record.
        val rateLimited = probes.getValue("debian:bookworm")
        assertEquals(1, rateLimited.buildxExit)
        assertTrue(rateLimited.reason.contains("429 Too Many Requests"), rateLimited.reason)
        val refused = probes.getValue("gcr.io/zenika-hub/alpine-chrome:123")
        assertEquals(1, refused.buildxExit)
        assertEquals("", refused.remoteIndexDigest)
        assertEquals(
            "ERROR: failed to authorize: failed to fetch anonymous token: unexpected status from GET request to " +
                "https://gcr.io/v2/token?scope=repository%3Azenika-hub%2Falpin",
            refused.reason,
        )
        assertFalse(refused.answered)
    }

    @Test
    fun buildxBatchParserFlagsEveryRefWithoutARecord() {
        val probes = UpdateParsers.parseContainerDigestBatch(
            "===BUILDX===\n__BUILDX__=ok\n__PROBE__|0|sha256:abc|jellyfin/jellyfin:latest|\n===DRIVER===\noverlay2",
            refs,
            "overlay2",
        )

        assertTrue(probes.getValue("jellyfin/jellyfin:latest").buildxAnswered)
        for (ref in refs - "jellyfin/jellyfin:latest") {
            val probe = probes.getValue(ref)
            assertTrue(probe.errored, ref)
            assertNull(probe.buildxExit)
            assertFalse(probe.answered)
        }
        // A lost batch is a map of errored probes, never an empty map a caller could read as "nothing to probe".
        val lost = UpdateParsers.parseContainerDigestBatch(null, refs, "")
        assertEquals(refs.toSet(), lost.keys)
        assertTrue(lost.values.all { it.errored })
        assertTrue(UpdateParsers.parseContainerDigestBatch("", refs, "").values.all { it.errored })
    }

    @Test
    fun manifestBatchParserReadsRealRecordsAndRefusedLookups() {
        val probes = UpdateParsers.parseContainerManifestBatch(manifestBatch, refs, "overlay2")

        // 32 digest lines in the real record, 28 distinct ones: the document repeats some.
        val radarr = probes.getValue("linuxserver/radarr:latest")
        assertEquals(28, radarr.manifestDigests.size)
        assertEquals(0, radarr.manifestExit)
        // The local image's id is among the remote config digests.
        assertTrue(radarr.manifestDigests.contains("sha256:20b7b45d4644d936f35d0bcad0c3affd2cb88bb28dda51264abef227d452413c"))
        assertFalse(
            probes.getValue("lscr.io/linuxserver/lidarr:latest").manifestDigests
                .contains("sha256:e402b8025e438e9a5414d1a5973ad57792524b9d6dc1997dce4251b6b866bb76"),
        )
        assertTrue(radarr.answered)
        // gcr.io refused: exit 1, no digests, and not marked errored — the lookup did answer.
        val refused = probes.getValue("gcr.io/zenika-hub/alpine-chrome:123")
        assertEquals(1, refused.manifestExit)
        assertTrue(refused.manifestDigests.isEmpty())
        assertFalse(refused.errored)
        assertFalse(refused.answered)
    }

    @Test
    fun manifestBatchParserRejectsRecordsCutOffMidWay() {
        val cut = manifestBatch.substringBefore("__MANIFEST_EXIT__=0")
        val probes = UpdateParsers.parseContainerManifestBatch(cut, refs, "overlay2")

        // The open record and every ref after it are errored probes, never partial digests.
        assertTrue(probes.getValue("linuxserver/radarr:latest").errored)
        assertFalse(probes.getValue("linuxserver/radarr:latest").answered)
        assertTrue(probes.getValue("lscr.io/linuxserver/lidarr:latest").errored)
        assertTrue(UpdateParsers.parseContainerManifestBatch(null, refs, "overlay2").values.all { it.errored })
    }

    @Test
    fun failedLookupsAreSummarisedFromExitCodesAndReasons() {
        val answered = ContainerDigestProbe(remoteIndexDigest = "sha256:" + "a".repeat(64), buildxExit = 0)
        val boom = ContainerDigestProbe(buildxExit = 1, reason = "ERROR: boom")
        val limited = ContainerDigestProbe(buildxExit = 1, reason = "ERROR: 429 Too Many Requests")
        val lost = ContainerDigestProbe(errored = true)

        fun summary(vararg probes: ContainerDigestProbe) = UpdateParsers.describeFailedProbes(probes.toList())

        assertNull(summary(answered))
        assertEquals("1 of 3 registry lookups failed: ERROR: boom", summary(answered, answered, boom))
        // The same complaint from several probes is quoted once.
        assertEquals("2 of 3 registry lookups failed: ERROR: boom", summary(answered, boom, boom))
        assertEquals("2 of 3 registry lookups failed — timed out", summary(answered, ContainerDigestProbe(buildxExit = 124), ContainerDigestProbe(buildxExit = 125)))
        assertEquals("2 of 3 registry lookups failed — no result came back", summary(answered, lost, lost))
        assertEquals("1 of 2 registry lookups failed — rate-limited by the registry: ERROR: 429 Too Many Requests", summary(answered, limited))
        assertEquals(
            "2 of 3 registry lookups failed (1 timed out, 1 rate-limited): ERROR: 429 Too Many Requests",
            summary(answered, ContainerDigestProbe(buildxExit = 124), limited),
        )
        // A probe that printed nothing still counts.
        assertEquals("1 of 2 registry lookups failed", summary(answered, ContainerDigestProbe(buildxExit = 1)))
    }

    @Test
    fun digestComparisonNormalizesCaseAndPrefix() {
        assertEquals("abc", UpdateParsers.normalizeDigest("sha256:ABC"))
        val decision = UpdateParsers.decideContainerUpdate(
            localRepoDigest = "sha256:SameDigest",
            localImageId = "",
            probe = ContainerDigestProbe(remoteIndexDigest = "sha256:samedigest", buildxExit = 0),
        )
        assertEquals(ContainerUpdateStatus.UP_TO_DATE, decision.status)
    }

    // ------------------------------------------------------------------
    // Script shape (the text that ran under bash, dash and sh on the real host)
    // ------------------------------------------------------------------

    @Test
    fun buildxBatchScriptIsPosixAndRunsProbesInWaves() {
        val script = UpdateProbes.containerDigestBatchScript("docker", refs, perProbeTimeoutSec = 25, parallelism = 6)

        assertEquals(
            listOf(
                "echo '===BUILDX==='",
                "if docker buildx version >/dev/null 2>&1; then echo '__BUILDX__=ok'; else echo '__BUILDX__=missing'; fi",
                "if command -v timeout >/dev/null 2>&1; then T=\"timeout 25\"; else T=\"\"; fi",
                "n=0",
                "for ref in 'linuxserver/radarr:latest' 'lscr.io/linuxserver/lidarr:latest' 'jellyfin/jellyfin:latest' " +
                    "'debian:bookworm' 'gcr.io/zenika-hub/alpine-chrome:123'; do",
                "  ( all=${'$'}(${'$'}T docker buildx imagetools inspect --format '{{.Manifest.Digest}}' \"${'$'}ref\" 2>&1); " +
                    "rc=${'$'}?; d=${'$'}(printf '%s\\n' \"${'$'}all\" | grep -oE 'sha256:[0-9a-fA-F]{64}' | head -n 1); " +
                    "why=${'$'}(printf '%s\\n' \"${'$'}all\" | grep -vE 'sha256:[0-9a-fA-F]{64}' | tr '\\n|' '  ' | tr -s ' ' | cut -c1-160); " +
                    "echo \"__PROBE__|${'$'}rc|${'$'}d|${'$'}ref|${'$'}why\" ) &",
                "  n=${'$'}((n+1))",
                "  if [ ${'$'}((n % 6)) -eq 0 ]; then wait; fi",
                "done",
                "wait",
                "echo '===DRIVER==='",
                "docker info --format '{{.Driver}}' 2>/dev/null",
            ),
            script.lines(),
        )
    }

    @Test
    fun manifestBatchScriptCollectsConcurrentRecordsInPrivateFilesAndCleansUp() {
        val script = UpdateProbes.containerManifestBatchScript("docker", listOf("nginx:1.27", "it's:odd"))

        assertEquals(
            listOf(
                "echo '===MANIFESTV==='",
                "if command -v timeout >/dev/null 2>&1; then T=\"timeout 25\"; else T=\"\"; fi",
                "emit() {",
                "  echo \"__MANIFEST_BEGIN__|${'$'}1\"",
                // The document is reduced on the node, so a manifest list cannot flood the channel.
                "  printf '%s\\n' \"${'$'}out\" | grep -oE '\"digest\"[[:space:]]*:[[:space:]]*\"sha256:[0-9a-fA-F]+\"'",
                "  echo \"__MANIFEST_EXIT__=${'$'}2\"",
                "  echo \"__MANIFEST_END__|${'$'}1\"",
                "}",
                "record() {",
                "  out=${'$'}(${'$'}T docker manifest inspect -v \"${'$'}1\" 2>/dev/null)",
                "  rc=${'$'}?",
                "  if [ -n \"${'$'}2\" ]; then emit \"${'$'}1\" \"${'$'}rc\" > \"${'$'}2\"; else emit \"${'$'}1\" \"${'$'}rc\"; fi",
                "}",
                "tmp=${'$'}(mktemp -d 2>/dev/null)",
                "n=0",
                "if [ -n \"${'$'}tmp\" ] && [ -d \"${'$'}tmp\" ]; then",
                // A quote inside a reference survives shellQuote.
                "  for ref in 'nginx:1.27' 'it'\"'\"'s:odd'; do",
                "    n=${'$'}((n+1))",
                "    record \"${'$'}ref\" \"${'$'}tmp/${'$'}n\" &",
                "    if [ ${'$'}((n % 6)) -eq 0 ]; then wait; fi",
                "  done",
                "  wait",
                "  for f in \"${'$'}tmp\"/*; do [ -f \"${'$'}f\" ] && cat \"${'$'}f\"; done",
                "  rm -rf \"${'$'}tmp\"",
                "else",
                "  for ref in 'nginx:1.27' 'it'\"'\"'s:odd'; do",
                "    record \"${'$'}ref\"",
                "  done",
                "fi",
            ),
            script.lines(),
        )
    }

    @Test
    fun theScanListsImagesWithFullIds() {
        assertEquals(
            "echo \"===IMAGES===\"\n" +
                "docker images --no-trunc --format '{{.ID}}|{{.Repository}}|{{.Tag}}|{{.Digest}}|{{.Size}}|{{.CreatedSince}}' 2>/dev/null || true",
            UpdateProbes.updateScanImagesScript("docker"),
        )
    }
}
