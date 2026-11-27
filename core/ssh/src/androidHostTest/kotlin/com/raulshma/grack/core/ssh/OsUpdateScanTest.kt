package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.DistroFamily
import com.raulshma.grack.core.model.NodeUpdateReport
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.OsScanStatus
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.currentTimeEpochMs
import com.raulshma.grack.core.model.distroFamilyForDistroId
import com.raulshma.grack.core.model.sudoSecretVaultId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 3B item 1: OS update scanning. Script shapes per distro family, the parsers against
 * real listings, and the controller path: the privileged package-index refresh that precedes
 * an apt/apk listing, the STALE demotion when it cannot run, persistence of the report into
 * `servers.updateReportJson`, and the scan's single audit entry.
 *
 * FIXTURE PROVENANCE — REAL. Every listing and every failure wording below was captured on
 * 2026-11-27 by running the app's own scripts and commands on real distros: Linux Mint 22.2
 * and Debian 12 (apt 2.6.1, sudo 1.9.13p3), Fedora 45 (dnf5 5.4.8), Rocky 9 (dnf 4.14), Arch
 * Linux with pacman-contrib (`checkupdates`), Alpine (apk 3.0.9). Lock fixtures were taken
 * against locks really held by a second process; the sudo wordings against real sudoers
 * states (wrong password, no right, requiretty, sudo absent). A fixture that is abridged, or
 * assembled from two captures of the same host, says so where it is declared. Shapes that
 * were never observed on a node are marked SYNTHETIC at their use site.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class OsUpdateScanTest {

    // ------------------------------------------------------------------
    // REAL listings (see class KDoc)
    // ------------------------------------------------------------------

    /** `apt list --upgradable` on Linux Mint 22.2 after a root `apt-get update`: 44 packages, verbatim. */
    private val aptMintListing = """
        ===OSUPD===
        Listing...
        binutils-common/noble-updates,noble-security 2.42-4ubuntu2.11 amd64 [upgradable from: 2.42-4ubuntu2.10]
        binutils-x86-64-linux-gnu/noble-updates,noble-security 2.42-4ubuntu2.11 amd64 [upgradable from: 2.42-4ubuntu2.10]
        binutils/noble-updates,noble-security 2.42-4ubuntu2.11 amd64 [upgradable from: 2.42-4ubuntu2.10]
        firefox-locale-en/zara 161.0+linuxmint1 amd64 [upgradable from: 160.0.1+linuxmint1]
        firefox/zara 161.0+linuxmint1 amd64 [upgradable from: 160.0.1+linuxmint1]
        gh/unknown 2.106.0 amd64 [upgradable from: 2.105.0]
        gir1.2-nm-1.0/noble-updates,noble-security 1.46.0-1ubuntu2.9 amd64 [upgradable from: 1.46.0-1ubuntu2.8]
        krb5-locales/noble-updates,noble-updates,noble-security,noble-security 1.20.1-6ubuntu2.12 all [upgradable from: 1.20.1-6ubuntu2.10]
        libasound2-data/noble-updates,noble-updates,noble-security,noble-security 1.2.11-1ubuntu0.5 all [upgradable from: 1.2.11-1ubuntu0.3]
        libasound2t64/noble-updates,noble-security 1.2.11-1ubuntu0.5 amd64 [upgradable from: 1.2.11-1ubuntu0.3]
        libatopology2t64/noble-updates,noble-security 1.2.11-1ubuntu0.5 amd64 [upgradable from: 1.2.11-1ubuntu0.3]
        libbinutils/noble-updates,noble-security 2.42-4ubuntu2.11 amd64 [upgradable from: 2.42-4ubuntu2.10]
        libctf-nobfd0/noble-updates,noble-security 2.42-4ubuntu2.11 amd64 [upgradable from: 2.42-4ubuntu2.10]
        libctf0/noble-updates,noble-security 2.42-4ubuntu2.11 amd64 [upgradable from: 2.42-4ubuntu2.10]
        libdebuginfod-common/noble-updates,noble-updates,noble-security,noble-security 0.190-1.1ubuntu0.2 all [upgradable from: 0.190-1.1ubuntu0.1]
        libdebuginfod1t64/noble-updates,noble-security 0.190-1.1ubuntu0.2 amd64 [upgradable from: 0.190-1.1ubuntu0.1]
        libdw1t64/noble-updates,noble-security 0.190-1.1ubuntu0.2 amd64 [upgradable from: 0.190-1.1ubuntu0.1]
        libelf1t64/noble-updates,noble-security 0.190-1.1ubuntu0.2 amd64 [upgradable from: 0.190-1.1ubuntu0.1]
        libexpat1-dev/noble-updates,noble-security 2.6.1-2ubuntu0.7 amd64 [upgradable from: 2.6.1-2ubuntu0.6]
        libexpat1/noble-updates,noble-security 2.6.1-2ubuntu0.7 amd64 [upgradable from: 2.6.1-2ubuntu0.6]
        libgprofng0/noble-updates,noble-security 2.42-4ubuntu2.11 amd64 [upgradable from: 2.42-4ubuntu2.10]
        libgssapi-krb5-2/noble-updates,noble-security 1.20.1-6ubuntu2.12 amd64 [upgradable from: 1.20.1-6ubuntu2.10]
        libk5crypto3/noble-updates,noble-security 1.20.1-6ubuntu2.12 amd64 [upgradable from: 1.20.1-6ubuntu2.10]
        libkrb5-3/noble-updates,noble-security 1.20.1-6ubuntu2.12 amd64 [upgradable from: 1.20.1-6ubuntu2.10]
        libkrb5support0/noble-updates,noble-security 1.20.1-6ubuntu2.12 amd64 [upgradable from: 1.20.1-6ubuntu2.10]
        libnm0/noble-updates,noble-security 1.46.0-1ubuntu2.9 amd64 [upgradable from: 1.46.0-1ubuntu2.8]
        libpcap0.8t64/noble-updates,noble-security 1.10.4-4.1ubuntu3.2 amd64 [upgradable from: 1.10.4-4.1ubuntu3.1]
        libsframe1/noble-updates,noble-security 2.42-4ubuntu2.11 amd64 [upgradable from: 2.42-4ubuntu2.10]
        libspeex1/noble-updates,noble-security 1.2.1-2ubuntu2.24.04.2 amd64 [upgradable from: 1.2.1-2ubuntu2.24.04.1]
        libsqlite3-0/noble-updates 3.45.1-1ubuntu2.11 amd64 [upgradable from: 3.45.1-1ubuntu2.9]
        libsqlite3-dev/noble-updates 3.45.1-1ubuntu2.11 amd64 [upgradable from: 3.45.1-1ubuntu2.9]
        libtiff-dev/noble-updates,noble-security 4.5.1+git230720-4ubuntu2.6 amd64 [upgradable from: 4.5.1+git230720-4ubuntu2.5]
        libtiff6/noble-updates,noble-security 4.5.1+git230720-4ubuntu2.6 amd64 [upgradable from: 4.5.1+git230720-4ubuntu2.5]
        libtiffxx6/noble-updates,noble-security 4.5.1+git230720-4ubuntu2.6 amd64 [upgradable from: 4.5.1+git230720-4ubuntu2.5]
        libxi-dev/noble-updates,noble-security 2:1.8.1-1ubuntu0.1 amd64 [upgradable from: 2:1.8.1-1build1]
        libxi6/noble-updates,noble-security 2:1.8.1-1ubuntu0.1 amd64 [upgradable from: 2:1.8.1-1build1]
        libxrender-dev/noble-updates,noble-security 1:0.9.10-1.1ubuntu0.1 amd64 [upgradable from: 1:0.9.10-1.1build1]
        libxrender1/noble-updates,noble-security 1:0.9.10-1.1ubuntu0.1 amd64 [upgradable from: 1:0.9.10-1.1build1]
        network-manager-config-connectivity-ubuntu/noble-updates,noble-updates,noble-security,noble-security 1.46.0-1ubuntu2.9 all [upgradable from: 1.46.0-1ubuntu2.8]
        network-manager/noble-updates,noble-security 1.46.0-1ubuntu2.9 amd64 [upgradable from: 1.46.0-1ubuntu2.8]
        python3-jwt/noble-updates,noble-updates,noble-security,noble-security 2.7.0-1ubuntu0.6 all [upgradable from: 2.7.0-1ubuntu0.5]
        thunderbird-locale-en-us/zara,zara 1:153.8.0esr+linuxmint1 all [upgradable from: 1:153.7.0esr+linuxmint1]
        thunderbird-locale-en/zara,zara 1:153.8.0esr+linuxmint1 all [upgradable from: 1:153.7.0esr+linuxmint1]
        thunderbird/zara 1:153.8.0esr+linuxmint1 amd64 [upgradable from: 1:153.7.0esr+linuxmint1]
        __APT_LIST_EXIT__=0
    """.trimIndent()

    /**
     * Debian 12 as an unprivileged user against a stale index: the four libpam rows are
     * verbatim from that host. Captured with the pre-fix script, which printed the ignored
     * `__APT_UPDATE_EXIT__=100` of its unprivileged refresh; the marker here is the one the
     * current script prints, as captured separately on the same host (see [aptNothingListed]).
     */
    private val aptDebian12Listing = """
        ===OSUPD===
        Listing...
        libpam-modules-bin/oldstable-security 1.5.2-6+deb12u3 amd64 [upgradable from: 1.5.2-6+deb12u2]
        libpam-modules/oldstable-security 1.5.2-6+deb12u3 amd64 [upgradable from: 1.5.2-6+deb12u2]
        libpam-runtime/oldstable-security 1.5.2-6+deb12u3 all [upgradable from: 1.5.2-6+deb12u2]
        libpam0g/oldstable-security 1.5.2-6+deb12u3 amd64 [upgradable from: 1.5.2-6+deb12u2]
        __APT_LIST_EXIT__=0
    """.trimIndent()

    /** Debian 12 right after an upgrade: apt prints its banner and nothing else. */
    private val aptNothingListed = """
        ===OSUPD===
        Listing...
        __APT_LIST_EXIT__=0
    """.trimIndent()

    /** `dnf -q check-update` on Fedora 45 (dnf5): rows grouped under an `Upgrades` heading, exit 100 = updates exist. */
    private val dnfFedora45Updates = """
        ===OSUPD===
        Upgrades
        curl.x86_64            8.21.0-9.fc45     updates
        dnf5.x86_64            5.4.8.0-2.fc45    updates
        dnf5-plugins.x86_64    5.4.8.0-2.fc45    updates
        elfutils-libelf.x86_64 0.197-1.fc45      updates
        elfutils-libs.x86_64   0.197-1.fc45      updates
        libcurl.x86_64         8.21.0-9.fc45     updates
        libdnf5.x86_64         5.4.8.0-2.fc45    updates
        libdnf5-cli.x86_64     5.4.8.0-2.fc45    updates
        libmodulemd.x86_64     2.15.4-1.fc45     updates
        libnghttp3.x86_64      1.19.0-1.fc45     updates
        librepo.x86_64         1.22.1-1.fc45     updates
        libzstd.x86_64         1.6.0-1.fc45      updates
        openldap.x86_64        2.6.16-1.fc45     updates
        p11-kit.x86_64         0.26.6-1.fc45     updates
        p11-kit-trust.x86_64   0.26.6-1.fc45     updates
        pcre2.x86_64           10.49-1.fc45      updates
        pcre2-syntax.noarch    10.49-1.fc45      updates
        sqlite-libs.x86_64     3.54.1-2.fc45     updates
        tpm2-tss.x86_64        4.2.2-1.fc45      updates
        tzdata.noarch          2026f-1.fc45      updates
        vim-data.noarch        2:9.2.1302-2.fc45 updates
        vim-minimal.x86_64     2:9.2.1302-2.fc45 updates
        zstd.x86_64            1.6.0-1.fc45      updates
        __DNF_EXIT__=100
    """.trimIndent()

    /** `dnf5 check-update` on that same Fedora after its upgrade: no output at all, exit 0. */
    private val dnfNothingListed = """
        ===OSUPD===
        __DNF_EXIT__=0
    """.trimIndent()

    /**
     * Arch `checkupdates` with updates pending: the first six and the last of sixteen rows
     * (the capture kept only those). Exit 0 = updates listed.
     */
    private val archUpdates = """
        ===OSUPD===
        cryptsetup 2.8.8-2 -> 2.8.9-1
        glib2 2.90.1-1 -> 2.90.2-1
        gpgme 2.2.0-1 -> 2.3.1-1
        kmod 34.3-2 -> 34.4-1
        libseccomp 2.6.0-1 -> 2.6.2-1
        tpm2-tss 4.2.2s-1 -> 4.2.2s-2
        zstd 1.5.7-3 -> 1.6.0-1
        __CHECKUPDATES_EXIT__=0
    """.trimIndent()

    /** Arch `checkupdates` after a full upgrade: no rows, exit 2 = nothing to do. */
    private val archCurrent = """
        ===OSUPD===
        __CHECKUPDATES_EXIT__=2
    """.trimIndent()

    /** Arch with `checkupdates` installed but `fakeroot` missing: exit 1, and the tool's own error (the script merges stderr). */
    private val archFakerootMissing = """
        ===OSUPD===
        ==> ERROR: Cannot find the fakeroot binary
        __CHECKUPDATES_EXIT__=1
    """.trimIndent()

    /** Arch without the pacman-contrib package. */
    private val archToolMissing = """
        ===OSUPD===
        __CHECKUPDATES_MISSING__
    """.trimIndent()

    /**
     * `apk version -l '<'` on Alpine 3.24.4: under an `Installed:/Available:` banner the
     * available side is the bare `version-rN`, not `name-version-rN` (trailing column padding removed).
     */
    private val apkAlpineUpdates = """
        ===OSUPD===
        Installed:                                Available:
        libcrypto3-3.5.9-r0                     < 3.5.9-r1
        libssl3-3.5.9-r0                        < 3.5.9-r1
        zlib-1.3.2-r1                           < 1.4-r0
        __APK_EXIT__=0
    """.trimIndent()

    /** REAL: `sudo -S -p ''` on Linux Mint 22.2 after it was given the wrong password (verbatim, blank line included). */
    private val sudoRejectedPassword = """
        Sorry, try again.

        sudo: no password was provided
        sudo: 1 incorrect password attempt
    """.trimIndent()

    /** REAL: `sudo -n` without the right, then apt run unprivileged (what the no-privilege fallback prints). */
    private val sudoFallbackOutput = """
        sudo: a password is required
        E: Could not open lock file /var/lib/dpkg/lock-frontend - open (13: Permission denied)
        E: Unable to acquire the dpkg frontend lock (/var/lib/dpkg/lock-frontend), are you root?
    """.trimIndent()

    // ------------------------------------------------------------------
    // REAL failure output (see class KDoc)
    // ------------------------------------------------------------------

    /** REAL: `sudo -n` for a user with no sudo right at all (Debian 12, sudo 1.9.13p3). */
    private val sudoNotInSudoers = "nosudo is not in the sudoers file."

    /** REAL: `sudo` without a terminal and without `-S`, over a no-tty SSH channel (Debian 12). */
    private val sudoTerminalRequired = """
        sudo: a terminal is required to read the password; either use the -S option to read from standard input or configure an askpass helper
        sudo: a password is required
    """.trimIndent()

    /** REAL: sudoers `Defaults requiretty` (Debian 12); the same line with `-S` and with `-n`. */
    private val sudoRequireTty = "sudo: sorry, you must have a tty to run sudo"

    /** REAL: a node without the sudo package — bash's wording (Rocky 9) and dash's (Debian 12). */
    private val sudoAbsentByShell = listOf(
        "bash: line 1: sudo: command not found",
        "dash: 1: sudo: not found",
    )

    /** REAL: `apt-get update` against an apt lock really held by a second process (Debian 12, apt 2.6.1). */
    private val aptLockUpdateStderr = """
        E: Could not get lock /var/lib/apt/lists/lock. It is held by process 2655 (perl)
        E: Unable to lock directory /var/lib/apt/lists/
    """.trimIndent()

    /**
     * REAL: the apply command's lock options against a really held dpkg lock (Debian 12):
     * the holder is named on stdout while apt waits for its `DPkg::Lock::Timeout`, the refusal
     * lands on stderr, exit 100.
     */
    private val aptLockUpgrade = SshExecResult(
        100,
        """
        Waiting for cache lock: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 2679 (perl)...
        Waiting for cache lock: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 2679 (perl)...
        """.trimIndent(),
        "E: Unable to acquire the dpkg frontend lock (/var/lib/dpkg/lock-frontend), is another process using it?",
        1L,
    )

    /** REAL: `dnf -q -y update` (dnf 4.14, Rocky 9) against `/var/lib/rpm/.rpm.lock` really held. */
    private val dnf4ApplyLockStderr = """
        RPM: error: can't create transaction lock on /var/lib/rpm/.rpm.lock (Resource temporarily unavailable)
        Error: Could not run transaction.
    """.trimIndent()

    /** REAL: `pacman -Su` (the apply command) against a really held pacman database lock (Arch). */
    private val pacmanApplyLockStderr = """
        error: failed to init transaction (unable to lock database)
        error: could not lock database: File exists
          if you're sure a package manager is not already
          running, you can remove /var/lib/pacman/db.lck
    """.trimIndent()

    /** REAL: `apk update` against an unreachable repository (Alpine, apk 3.0.9): one warning, exit 2. */
    private val apkUnreachableRepoStderr =
        "WARNING: updating and opening http://nonexistent.invalid/alpine/v3.21/main: DNS lookup error"

    /** REAL: `apk update` unprivileged (Alpine): exit 99, the package database refused. */
    private val apkUnprivilegedStderr = """
        ERROR: Unable to lock database: Permission denied
        ERROR: Failed to open apk database: Permission denied
    """.trimIndent()

    /** REAL: `apt-get update` that exited 0 with one repository unreachable (Debian 12) — the warning apt buries. */
    private val aptPartialRefreshStderr = """
        W: Failed to fetch http://nonexistent.invalid/debian/dists/bookworm/InRelease  Could not resolve 'nonexistent.invalid'
        W: Some index files failed to download. They have been ignored, or old ones used instead.
    """.trimIndent()

    /**
     * REAL, abridged: `apt-get update` against a repository whose TLS chain the node cannot
     * verify (Debian 12) — the captured stream repeated the certificate warning block; one is kept.
     */
    private val aptUntrustedRefreshStderr = """
        W: https://download.docker.com/linux/debian/dists/bookworm/InRelease: No system certificates available. Try installing ca-certificates.
        W: Failed to fetch https://download.docker.com/linux/debian/dists/bookworm/InRelease  Certificate verification failed: The certificate is NOT trusted. The certificate issuer is unknown.  Could not handshake: Error in the certificate verification. [IP: 108.159.46.101 443]
        W: Some index files failed to download. They have been ignored, or old ones used instead.
    """.trimIndent()

    /** REAL: `apt-get update` against a repository apt refuses to trust — exit 100, not a warning (Debian 12). */
    private val aptUnsignedRepoStderr = """
        W: GPG error: http://archive.ubuntu.com/ubuntu noble InRelease: The following signatures couldn't be verified because the public key is not available: NO_PUBKEY 871920D1991BC93C
        E: The repository 'http://archive.ubuntu.com/ubuntu noble InRelease' is not signed.
    """.trimIndent()

    /** REAL: `apt-get update` against a removed release — exit 100 (Debian 12). */
    private val aptMissingReleaseStderr =
        "E: The repository 'http://deb.debian.org/debian nosuchrelease Release' does not have a Release file."

    /** REAL: `dnf -q check-update` on Rocky 9 (dnf 4.14): a leading blank line, padded columns, exit 100. */
    private val dnfRocky9Updates = """
        ===OSUPD===

        curl-minimal.x86_64                 7.76.1-44.el9_9.1                     baseos
        expat.x86_64                        2.5.0-9.el9_9.8                       baseos
        libcurl-minimal.x86_64              7.76.1-44.el9_9.1                     baseos
        systemd-libs.x86_64                 252-79.el9_9.1.rocky.0.1              baseos
        vim-minimal.x86_64                  2:8.2.2637-44.el9_9                   baseos
        __DNF_EXIT__=100
    """.trimIndent()

    /** REAL: dnf 4.14 (Rocky 9) against an unreachable repository — exit 1, the reason on stderr. */
    private val dnfRocky9Unreachable = """
        ===OSUPD===
        Error: Failed to download metadata for repo 'bad': Cannot download repomd.xml: Cannot download repodata/repomd.xml: All mirrors were tried
        __DNF_EXIT__=1
    """.trimIndent()

    /**
     * REAL: dnf5 5.4 (Fedora 45) against an unreachable repository with the listing script's
     * `skip_if_unavailable=False`. Without that option dnf5 skips the dead repository and
     * exits 0, which would read as up to date.
     */
    private val dnfFedora45Unreachable = """
        ===OSUPD===
        Failed to download metadata (baseurl: "http://nonexistent.invalid/fedora/") for repository "bad": Usable URL not found
        __DNF_EXIT__=1
    """.trimIndent()

    /** REAL: `checkupdates` (Arch) against an unreachable mirror — exit 1, the tool's own error. */
    private val archUnreachableMirror = """
        ===OSUPD===
        ==> ERROR: Cannot fetch updates
        __CHECKUPDATES_EXIT__=1
    """.trimIndent()

    // ------------------------------------------------------------------
    // Script shape per distro family
    // ------------------------------------------------------------------

    @Test
    fun debianListingIsReadOnlyAndReportsItsOwnExit() {
        val script = UpdateProbes.updateListScript(OsCapabilities(distroId = "debian"))
        assertTrue(script.startsWith("echo '===OSUPD==='"), script)
        assertTrue(script.contains("apt list --upgradable 2>/dev/null"), script)
        assertTrue(script.contains("__APT_LIST_EXIT__="), script)
    }

    @Test
    fun theIndexRefreshIsASeparatePrivilegedCommandForTheFamiliesThatNeedRoot() {
        // The lock timeout is the half-minute budget verified against a real held apt lock;
        // without it a scan landing on unattended-upgrades waits until the controller's own timeout.
        assertEquals(30, UpdateProbes.INDEX_REFRESH_LOCK_TIMEOUT_SEC)
        assertEquals("apt-get -o DPkg::Lock::Timeout=30 update -qq", UpdateProbes.indexRefreshCommand(DistroFamily.DEBIAN))
        assertEquals("apk update", UpdateProbes.indexRefreshCommand(DistroFamily.ALPINE))
        // dnf and checkupdates refresh into a per-user cache by themselves.
        assertEquals("", UpdateProbes.indexRefreshCommand(DistroFamily.RHEL))
        assertEquals("", UpdateProbes.indexRefreshCommand(DistroFamily.ARCH))
        assertEquals("", UpdateProbes.indexRefreshCommand(DistroFamily.UNSUPPORTED))
        assertEquals("apt list --upgradable", UpdateProbes.indexListSummary(DistroFamily.DEBIAN))
    }

    @Test
    fun ubuntuRaspbianAndMintMapToTheDebianFamily() {
        assertEquals(DistroFamily.DEBIAN, distroFamilyForDistroId("ubuntu"))
        assertEquals(DistroFamily.DEBIAN, distroFamilyForDistroId("raspbian"))
        assertEquals(DistroFamily.DEBIAN, distroFamilyForDistroId("linuxmint"))
    }

    @Test
    fun rhelScriptTreatsExit100AsData() {
        val script = UpdateProbes.updateListScript(OsCapabilities(distroId = "fedora"))
        // skip_if_unavailable=False makes an unreachable repository fail the listing loudly:
        // the dnf5 default skips it silently and still exits 0 (real Fedora 45).
        assertTrue(script.contains("dnf -q check-update --setopt='*.skip_if_unavailable=False' 2>&1"), script)
        assertFalse(script.contains("2>/dev/null"), script)
        assertTrue(script.contains("__DNF_EXIT__="), script)
        assertEquals("dnf -q check-update --setopt='*.skip_if_unavailable=False'", UpdateProbes.indexListSummary(DistroFamily.RHEL))
        assertEquals(DistroFamily.RHEL, distroFamilyForDistroId("rocky"))
        assertEquals(DistroFamily.RHEL, distroFamilyForDistroId("rhel"))
    }

    @Test
    fun archScriptGuardsCheckupdatesAndKeepsItsStderr() {
        val script = UpdateProbes.updateListScript(OsCapabilities(distroId = "arch"))
        assertTrue(script.contains("command -v checkupdates"), script)
        // stderr is the only place a real `checkupdates` error surfaces (e.g. a missing fakeroot).
        assertTrue(script.contains("checkupdates 2>&1"), script)
        assertTrue(script.contains("__CHECKUPDATES_MISSING__"), script)
        assertTrue(script.contains("__CHECKUPDATES_EXIT__="), script)
        assertEquals(DistroFamily.ARCH, distroFamilyForDistroId("manjaro"))
    }

    @Test
    fun alpineScriptListsWithoutRefreshing() {
        val script = UpdateProbes.updateListScript(OsCapabilities(distroId = "alpine"))
        assertTrue(script.contains("apk version -l '<' 2>/dev/null"), script)
        assertTrue(script.contains("__APK_EXIT__="), script)
        assertFalse(script.contains("apk update"), script)
        assertEquals(DistroFamily.ALPINE, distroFamilyForDistroId("alpine"))
    }

    @Test
    fun unknownDistroProducesTheUnsupportedMarker() {
        val script = UpdateProbes.updateListScript(OsCapabilities(distroId = "nixos"))
        assertTrue(script.contains("__UPDATE_UNSUPPORTED__"), script)
        assertEquals(DistroFamily.UNSUPPORTED, distroFamilyForDistroId("nixos"))
        assertEquals(DistroFamily.UNSUPPORTED, distroFamilyForDistroId(""))
    }

    @Test
    fun noListingScriptChangesTheNode() {
        val ids = listOf("debian", "linuxmint", "fedora", "arch", "alpine", "nixos")
        for (id in ids) {
            val script = UpdateProbes.updateListScript(OsCapabilities(distroId = id))
            // The refresh moved out of the listing on purpose: it needs root and is audited on its own.
            for (verb in listOf("apt-get update", "update -qq", "apk update", "upgrade", "install ", "reboot", "shutdown")) {
                assertFalse(script.contains(verb), "$id listing contains '$verb': $script")
            }
        }
    }

    // ------------------------------------------------------------------
    // Parsers against the REAL listings
    // ------------------------------------------------------------------

    @Test
    fun parsesTheRealMintListing() {
        val section = UpdateParsers.parseOsUpdateSection(aptMintListing, DistroFamily.DEBIAN)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(44, section.packages.size)
        val binutils = section.packages.first()
        assertEquals("binutils-common", binutils.packageName)
        assertEquals("2.42-4ubuntu2.10", binutils.currentVersion)
        assertEquals("2.42-4ubuntu2.11", binutils.candidateVersion)
        // A repo the index does not know still yields a row.
        val gh = section.packages.first { it.packageName == "gh" }
        assertEquals("2.105.0", gh.currentVersion)
        // Epochs and Mint's own repo names survive intact.
        val thunderbird = section.packages.first { it.packageName == "thunderbird" }
        assertEquals("1:153.7.0esr+linuxmint1", thunderbird.currentVersion)
        assertEquals("1:153.8.0esr+linuxmint1", thunderbird.candidateVersion)
        assertEquals("thunderbird", section.packages.last().packageName)
        // The banner is not a package.
        assertTrue(section.packages.none { it.packageName.startsWith("Listing") })
    }

    @Test
    fun parsesTheRealDebian12Listing() {
        val section = UpdateParsers.parseOsUpdateSection(aptDebian12Listing, DistroFamily.DEBIAN)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(4, section.packages.size)
        val libpam0g = section.packages.last()
        assertEquals("libpam0g", libpam0g.packageName)
        assertEquals("1.5.2-6+deb12u2", libpam0g.currentVersion)
        assertEquals("1.5.2-6+deb12u3", libpam0g.candidateVersion)
    }

    @Test
    fun aRealEmptyListingIsUpToDate() {
        val section = UpdateParsers.parseOsUpdateSection(aptNothingListed, DistroFamily.DEBIAN)

        assertEquals(OsScanStatus.OK, section.status)
        assertTrue(section.packages.isEmpty())
    }

    @Test
    fun parsesTheRealDnf5Output() {
        val section = UpdateParsers.parseOsUpdateSection(dnfFedora45Updates, DistroFamily.RHEL)

        assertEquals(OsScanStatus.OK, section.status)
        // dnf5 groups rows under `Upgrades`, which is a heading, not a package.
        assertEquals(23, section.packages.size)
        assertEquals("curl", section.packages.first().packageName)
        assertEquals("8.21.0-9.fc45", section.packages.first().candidateVersion)
        assertEquals("pcre2-syntax", section.packages.first { it.packageName == "pcre2-syntax" }.packageName)
        val vimData = section.packages.first { it.packageName == "vim-data" }
        assertEquals("2:9.2.1302-2.fc45", vimData.candidateVersion)
        // check-update does not print the installed version.
        assertEquals(null, vimData.currentVersion)
    }

    @Test
    fun parsesTheRealDnf4Rows() {
        val section = UpdateParsers.parseOsUpdateSection(dnfRocky9Updates, DistroFamily.RHEL)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(
            listOf("curl-minimal", "expat", "libcurl-minimal", "systemd-libs", "vim-minimal"),
            section.packages.map { it.packageName },
        )
        val systemd = section.packages.first { it.packageName == "systemd-libs" }
        assertEquals("252-79.el9_9.1.rocky.0.1", systemd.candidateVersion)
        val vim = section.packages.first { it.packageName == "vim-minimal" }
        // Epochs survive; check-update does not print the installed evr.
        assertEquals("2:8.2.2637-44.el9_9", vim.candidateVersion)
        assertEquals(null, vim.currentVersion)
    }

    @Test
    fun anUnreachableRepositoryFailsTheDnfListingWithDnfsOwnReason() {
        // dnf 4.14 (Rocky 9) keeps its reason on stderr; dnf5 (Fedora 45) only complains with
        // skip_if_unavailable=False, which the listing script sets.
        val rocky = UpdateParsers.parseOsUpdateSection(dnfRocky9Unreachable, DistroFamily.RHEL)
        assertEquals(OsScanStatus.FAILED, rocky.status)
        assertTrue(
            rocky.detail.startsWith("the node could not reach all of its package repositories — Error: Failed to download metadata"),
            rocky.detail,
        )
        assertTrue(rocky.detail.contains("All mirrors were tried"), rocky.detail)

        val fedora = UpdateParsers.parseOsUpdateSection(dnfFedora45Unreachable, DistroFamily.RHEL)
        assertEquals(OsScanStatus.FAILED, fedora.status)
        assertTrue(
            fedora.detail.startsWith("the node could not reach all of its package repositories"),
            fedora.detail,
        )
        assertTrue(fedora.detail.contains("Usable URL not found"), fedora.detail)
    }

    @Test
    fun dnfExitZeroWithNoRowsIsUpToDate() {
        val section = UpdateParsers.parseOsUpdateSection(dnfNothingListed, DistroFamily.RHEL)
        assertEquals(OsScanStatus.OK, section.status)
        assertTrue(section.packages.isEmpty())
    }

    @Test
    fun dnfExitOneIsAFailureNotAnEmptyList() {
        // SYNTHETIC: the bare-marker shape only — a real failing dnf prints a reason
        // (see [anUnreachableRepositoryFailsTheDnfListingWithDnfsOwnReason]); this pins the
        // no-output fallback, which a held lock on `checkupdates` showed does occur (exit 2,
        // silent, on Arch).
        val section = UpdateParsers.parseOsUpdateSection("===OSUPD===\n__DNF_EXIT__=1", DistroFamily.RHEL)

        assertEquals(OsScanStatus.FAILED, section.status)
        assertEquals("dnf check-update exited 1", section.detail)
        assertTrue(section.packages.isEmpty())
    }

    @Test
    fun parsesTheRealArchCheckupdatesRows() {
        val section = UpdateParsers.parseOsUpdateSection(archUpdates, DistroFamily.ARCH)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(7, section.packages.size)
        val glib2 = section.packages[1]
        assertEquals("glib2", glib2.packageName)
        assertEquals("2.90.1-1", glib2.currentVersion)
        assertEquals("2.90.2-1", glib2.candidateVersion)
    }

    @Test
    fun checkupdatesExitTwoIsUpToDate() {
        // KNOWN LIMITATION (captured): a held pacman db lock also leaves `checkupdates` at
        // exit 2 with no output — pacman's error is swallowed — so this reading is the only
        // one the tool allows. The apply path does name the lock ([pacmanApplyLockStderr]).
        // Tracked in docs/real-node-verification.md.
        val section = UpdateParsers.parseOsUpdateSection(archCurrent, DistroFamily.ARCH)
        assertEquals(OsScanStatus.OK, section.status)
        assertTrue(section.packages.isEmpty())
    }

    @Test
    fun checkupdatesExitOneIsAFailureCarryingTheToolsOwnError() {
        val section = UpdateParsers.parseOsUpdateSection(archFakerootMissing, DistroFamily.ARCH)

        // Ignoring exit 1 here used to report a broken Arch node as "up to date".
        assertEquals(OsScanStatus.FAILED, section.status)
        assertEquals("checkupdates needs fakeroot, which is not installed on the node", section.detail)
        assertTrue(section.packages.isEmpty())
    }

    @Test
    fun checkupdatesThatCannotFetchFailsWithItsOwnError() {
        val section = UpdateParsers.parseOsUpdateSection(archUnreachableMirror, DistroFamily.ARCH)

        assertEquals(OsScanStatus.FAILED, section.status)
        assertEquals(
            "the node could not reach all of its package repositories — ==> ERROR: Cannot fetch updates",
            section.detail,
        )
        assertTrue(section.packages.isEmpty())
    }

    @Test
    fun missingCheckupdatesBinaryIsUnsupported() {
        val section = UpdateParsers.parseOsUpdateSection(archToolMissing, DistroFamily.ARCH)
        assertEquals(OsScanStatus.UNSUPPORTED, section.status)
        assertTrue(section.detail.contains("pacman-contrib"), section.detail)
    }

    @Test
    fun parsesTheRealApkRowsWithBareAvailableVersions() {
        val section = UpdateParsers.parseOsUpdateSection(apkAlpineUpdates, DistroFamily.ALPINE)

        assertEquals(OsScanStatus.OK, section.status)
        assertEquals(listOf("libcrypto3", "libssl3", "zlib"), section.packages.map { it.packageName })
        val libcrypto3 = section.packages.first()
        assertEquals("3.5.9-r0", libcrypto3.currentVersion)
        assertEquals("3.5.9-r1", libcrypto3.candidateVersion)
        val zlib = section.packages.last()
        assertEquals("1.3.2-r1", zlib.currentVersion)
        assertEquals("1.4-r0", zlib.candidateVersion)
    }

    @Test
    fun apkRowsThatRepeatThePackageNameStillParse() {
        // SYNTHETIC: the older-apk row shape documented in [UpdateParsers.parseApkVersion] —
        // real Alpine 3.21 prints the bare version on the available side ([apkAlpineUpdates]).
        val section = UpdateParsers.parseOsUpdateSection(
            "===OSUPD===\nbusybox-1.36.1-r5 < busybox-1.36.1-r7\n__APK_EXIT__=0",
            DistroFamily.ALPINE,
        )
        val busybox = section.packages.single()
        assertEquals("busybox", busybox.packageName)
        assertEquals("1.36.1-r7", busybox.candidateVersion)
    }

    @Test
    fun apkAndAptListingFailuresAreFailedSections() {
        // SYNTHETIC: bare exit markers, pinning that a non-zero listing exit is never read as
        // "up to date" (the real exit-99 apk wording lives in [apkUnprivilegedStderr]).
        val apk = UpdateParsers.parseOsUpdateSection("===OSUPD===\n__APK_EXIT__=99", DistroFamily.ALPINE)
        assertEquals(OsScanStatus.FAILED, apk.status)
        assertTrue(apk.detail.contains("99"), apk.detail)

        val apt = UpdateParsers.parseOsUpdateSection("===OSUPD===\n__APT_LIST_EXIT__=100", DistroFamily.DEBIAN)
        assertEquals(OsScanStatus.FAILED, apt.status)
        assertEquals("apt list exited 100", apt.detail)
    }

    @Test
    fun unsupportedMarkerMapsToUnsupportedStatus() {
        // SYNTHETIC: the marker's own shape (the script echoes it on an unknown distro), not a
        // node capture — nothing to capture, the node prints nothing here.
        val section = UpdateParsers.parseOsUpdateSection("===OSUPD===\n__UPDATE_UNSUPPORTED__", DistroFamily.UNSUPPORTED)
        assertEquals(OsScanStatus.UNSUPPORTED, section.status)
    }

    // ------------------------------------------------------------------
    // Failure diagnosis against REAL sudo and package-manager output
    // ------------------------------------------------------------------

    /** The diagnosis for a privileged command whose stderr was [stderr] (and stdout [stdout]). */
    private fun reasonFor(stderr: String, stdout: String = ""): String =
        UpdateParsers.explainPrivilegedFailure(SshExecResult(1, stdout, stderr, 5L), "pi-user")

    @Test
    fun failureDiagnosisReadsWhatSudoActuallyPrinted() {
        // A rejected password used to surface as a bare "exit 1".
        val rejected = reasonFor(sudoRejectedPassword)
        assertTrue(rejected.startsWith("sudo rejected the stored sudo password for 'pi-user'."), rejected)
        assertTrue(rejected.contains("not root's"), rejected)

        val noRight = reasonFor(sudoFallbackOutput)
        assertTrue(noRight.contains("needs a sudo password and none is stored"), noRight)

        // Real Debian 12 / Rocky 9 wordings, each from the situation it claims to diagnose.
        assertTrue(reasonFor(sudoTerminalRequired).contains("needs a sudo password"), reasonFor(sudoTerminalRequired))
        assertTrue(reasonFor(sudoNotInSudoers).contains("not allowed to run sudo"), reasonFor(sudoNotInSudoers))
        assertTrue(reasonFor(sudoRequireTty).contains("sudoers demands a terminal"), reasonFor(sudoRequireTty))
        for (absent in sudoAbsentByShell) {
            assertTrue(reasonFor(absent).contains("sudo is not installed"), "$absent -> ${reasonFor(absent)}")
        }
        // The unprivileged-fallback refusal: apt's own "are you root?" line.
        assertTrue(reasonFor(sudoFallbackOutput.lines().last()).contains("ran without root"))
        // Nothing recognised: quote the node rather than invent a cause (SYNTHETIC stand-ins).
        assertEquals("some other failure", reasonFor("some other failure"))
        assertEquals("no output was captured (exit 1)", reasonFor(""))
    }

    @Test
    fun aHeldPackageLockIsNamedWithItsHolder() {
        // Refresh against a real apt lock (holder named on stderr)…
        assertEquals(
            "another package manager holds the lock on this node (perl, process 2655) — retry in a few minutes.",
            reasonFor(aptLockUpdateStderr),
        )
        // …and the apply, whose holder is named on stdout while apt waits.
        assertEquals(
            "another package manager holds the lock on this node (perl, process 2679) — retry in a few minutes.",
            UpdateParsers.explainPrivilegedFailure(aptLockUpgrade, "pi-user"),
        )
        // pacman names no process; dnf names neither.
        assertEquals(
            "another package manager holds the lock on this node — retry in a few minutes.",
            reasonFor(pacmanApplyLockStderr),
        )
        assertEquals(
            "another package manager holds the lock on this node — retry in a few minutes.",
            reasonFor(dnf4ApplyLockStderr),
        )
    }

    @Test
    fun anUnreachableRepositoryIsSaidPlainly() {
        val apk = reasonFor(apkUnreachableRepoStderr)
        assertTrue(apk.startsWith("the node could not reach its package repositories — check its network or DNS:"), apk)
        assertTrue(apk.contains("WARNING: updating and opening http://nonexistent.invalid/alpine/v3.21/main: DNS lookup error"), apk)
    }

    @Test
    fun anUnsignedRepositoryAndARemovedReleaseGetTheirOwnReasons() {
        val unsigned = reasonFor(aptUnsignedRepoStderr)
        assertTrue(
            unsigned.startsWith("a repository's signing key is missing or expired on this node, so apt refused its index:"),
            unsigned,
        )
        assertTrue(unsigned.contains("NO_PUBKEY 871920D1991BC93C"), unsigned)

        val missing = reasonFor(aptMissingReleaseStderr)
        assertTrue(missing.startsWith("a configured repository no longer exists on this node (a removed release or mirror):"), missing)
        assertTrue(missing.contains("does not have a Release file"), missing)
    }

    @Test
    fun theQuotedNodeLineIsSanitizedBeforeItReachesABanner() {
        val reason = reasonFor("E: Failed to fetch http://node.invalid/debian API_KEY=hunter2")
        assertTrue(reason.contains("could not reach its package repositories"), reason)
        assertTrue(reason.contains("API_KEY=[REDACTED]"), reason)
        assertFalse(reason.contains("hunter2"), reason)
    }

    @Test
    fun anExitZeroRefreshThatSkippedRepositoriesIsStillAGap() {
        // A clean refresh is no gap at all.
        assertEquals(null, UpdateParsers.indexRefreshGap("", ""))
        assertEquals(null, UpdateParsers.indexRefreshGap("Hit:1 http://deb.debian.org/debian bookworm InRelease", ""))

        // REAL capture: `apt-get update` exited 0 with one repository unreachable —
        // the summary line decides, the first failed fetch is quoted.
        assertEquals(
            "the node could not fetch every repository's index — " +
                "W: Failed to fetch http://nonexistent.invalid/debian/dists/bookworm/InRelease  Could not resolve 'nonexistent.invalid'",
            UpdateParsers.indexRefreshGap("", aptPartialRefreshStderr),
        )
        val untrusted = UpdateParsers.indexRefreshGap(aptUntrustedRefreshStderr, "")
        assertNotNull(untrusted)
        assertTrue(
            untrusted.startsWith("the node could not fetch every repository's index — W: Failed to fetch https://download.docker.com"),
            untrusted,
        )
        // The quoted line is capped, so the long real line ends inside its own text.
        assertTrue(untrusted.contains("Certificate verification failed"), untrusted)
        assertFalse(untrusted.endsWith("443]"), untrusted)
    }

    // ------------------------------------------------------------------
    // Controller path: privileged refresh, listing, STALE demotion
    // ------------------------------------------------------------------

    /** A non-root login with no sudo credential of its own: whether sudo works is up to the node. */
    private val debianServer = Server(
        id = "srv-deb",
        name = "deb-node",
        primaryHost = "10.0.0.5",
        username = "pi-user",
        capabilities = OsCapabilities(distroId = "debian"),
    )

    private val rootServer = debianServer.copy(
        id = "srv-root",
        username = "root",
        capabilities = OsCapabilities(distroId = "debian", isRoot = true),
    )

    private val fedoraServer = debianServer.copy(
        id = "srv-fed",
        name = "fedora-node",
        capabilities = OsCapabilities(distroId = "fedora"),
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

    /**
     * Serves the passwordless-sudo probe with [probeExit], the index refresh with [refresh]
     * and the listing with [listing]. Anything else is a test bug.
     */
    private fun ControllerHarness.serveOs(
        listing: String,
        probeExit: Int = 1,
        refresh: suspend () -> SshExecResult = { SshExecResult(0, "", "", 1L) },
    ) {
        fake.executeHandler = { request ->
            when {
                request.command == HomelabController.SUDO_PROBE_COMMAND ->
                    SshExecResult(probeExit, "", if (probeExit == 0) "" else "sudo: a password is required", 1L)
                // The refresh, bare (root) or sudo-wrapped; the listing never contains "update -qq".
                request.command.contains("update -qq") -> refresh()
                request.command.contains("===OSUPD===") -> SshExecResult(0, listing, "", 1L)
                else -> throw AssertionError("unexpected command: ${request.command}")
            }
        }
    }

    private fun ControllerHarness.commands() = fake.executeRequests.map { it.command }

    @Test
    fun aRootLoginRefreshesTheIndexAsItselfThenLists() = runTest {
        withController { h ->
            h.serveOs(aptMintListing)

            val section = h.controller.scanOsUpdates(rootServer, h.vault)

            assertEquals(OsScanStatus.OK, section.status)
            assertEquals(44, section.packages.size)
            val commands = h.commands()
            assertEquals("apt-get -o DPkg::Lock::Timeout=30 update -qq", commands[0])
            assertTrue(commands[1].startsWith("echo '===OSUPD==='"), commands[1])
            // Root needs no sudo probe, no wrapper and no password.
            assertTrue(commands.none { it.contains("sudo") }, commands.toString())
            assertTrue(h.fake.executeRequests.all { it.stdinText == null })
        }
    }

    @Test
    fun aPasswordlessSudoUserRefreshesThroughSudoN() = runTest {
        withController { h ->
            h.serveOs(aptMintListing, probeExit = 0)

            val section = h.controller.scanOsUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.OK, section.status)
            val commands = h.commands()
            assertEquals(
                listOf(HomelabController.SUDO_PROBE_COMMAND, "sudo -n bash -c 'apt-get -o DPkg::Lock::Timeout=30 update -qq'"),
                commands.take(2),
            )
            assertEquals(HomelabController.INDEX_REFRESH_TIMEOUT_MS, h.fake.executeRequests[1].timeoutMs)
            assertTrue(h.fake.executeRequests.all { it.stdinText == null })
        }
    }

    @Test
    fun aStoredSudoPasswordRidesStdinOnly() = runTest {
        withController { h ->
            val secret = "Sudo-Passw0rd!123"
            val server = debianServer.copy(sudoAuth = SudoAuth.SEPARATE_PASSWORD)
            h.vault.putSecret(sudoSecretVaultId(server.id), secret)
            h.serveOs(aptMintListing, probeExit = 1)

            val section = h.controller.scanOsUpdates(server, h.vault)

            assertEquals(OsScanStatus.OK, section.status)
            // The password exists on exactly one surface: the refresh's stdin, then EOF.
            val delivered = h.fake.executeRequests.filter { it.stdinText != null }
            assertEquals(listOf("sudo -S -p '' bash -c 'apt-get -o DPkg::Lock::Timeout=30 update -qq'"), delivered.map { it.command })
            assertEquals(listOf("$secret\n"), delivered.map { it.stdinText })
            val everythingElse = h.fake.executeRequests.joinToString("\n") { "${it.command} ${it.timeoutMs}" } +
                h.fake.streamRequests.joinToString("\n") { it.command }
            assertFalse(everythingElse.contains(secret))
            assertFalse(section.detail.contains(secret))
        }
    }

    @Test
    fun theRefreshPasswordNeverReachesTheLedgerOrThePersistedReport() = runTest {
        withController { h ->
            val secret = "Sudo-Passw0rd!123"
            val server = debianServer.copy(sudoAuth = SudoAuth.SEPARATE_PASSWORD)
            h.vault.putSecret(sudoSecretVaultId(server.id), secret)
            h.repository.upsertServer(server)
            // A real refusal (see [sudoRejectedPassword]) so the reason text is produced, then leaked if it could be.
            h.serveOs(aptDebian12Listing, probeExit = 1, refresh = { SshExecResult(1, "", sudoRejectedPassword, 900L) })

            val report = h.controller.scanNodeUpdates(server, h.vault)

            assertEquals(OsScanStatus.STALE, report.os?.status)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.single()
            val surfaces = listOf(
                audit.commandSanitized,
                audit.outputSnippet,
                h.repository.getServerById(server.id)?.updateReportJson.orEmpty(),
                report.toString(),
            )
            for (surface in surfaces) {
                assertFalse(surface.contains(secret), surface)
            }
            assertTrue(audit.outputSnippet.contains("sudo rejected the stored sudo password for 'pi-user'"), audit.outputSnippet)
        }
    }

    @Test
    fun withoutAPrivilegePathTheRefreshIsNotAttemptedAndTheListingIsStale() = runTest {
        withController { h ->
            h.serveOs(aptDebian12Listing, probeExit = 1)

            val section = h.controller.scanOsUpdates(debianServer, h.vault)

            // The rows are real (a stale Debian index does list upgrades), but they are a lower bound.
            assertEquals(OsScanStatus.STALE, section.status)
            assertEquals(4, section.packages.size)
            assertTrue(section.detail.startsWith("Package index not refreshed — 'pi-user' is neither root nor allowed passwordless sudo"), section.detail)
            assertTrue(section.detail.contains("lower bound"), section.detail)
            // Run unprivileged it could only fail, so it is never sent.
            assertTrue(h.commands().none { it.contains("update -qq") }, h.commands().toString())
        }
    }

    @Test
    fun aStaleIndexWithNothingListedSaysTheNodeMayStillHaveUpdates() = runTest {
        withController { h ->
            h.serveOs(aptNothingListed, probeExit = 1)

            val section = h.controller.scanOsUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.STALE, section.status)
            assertTrue(section.packages.isEmpty())
            assertTrue(section.detail.contains("Nothing is listed, but that only reflects the old index"), section.detail)
        }
    }

    @Test
    fun aRejectedSudoPasswordIsNamedAsSuchNotAsAnExitCode() = runTest {
        withController { h ->
            val server = debianServer.copy(sudoAuth = SudoAuth.SEPARATE_PASSWORD)
            h.vault.putSecret(sudoSecretVaultId(server.id), "wrong-password")
            h.serveOs(aptDebian12Listing, probeExit = 1, refresh = { SshExecResult(1, "", sudoRejectedPassword, 900L) })

            val section = h.controller.scanOsUpdates(server, h.vault)

            assertEquals(OsScanStatus.STALE, section.status)
            assertTrue(section.detail.contains("sudo rejected the stored sudo password for 'pi-user'"), section.detail)
            assertFalse(section.detail.contains("exit 1"), section.detail)
        }
    }

    @Test
    fun dnfAndArchNodesSendNoRefreshCommand() = runTest {
        withController { h ->
            h.fake.executeHandler = { request ->
                assertTrue(request.command.startsWith("echo '===OSUPD==='"), request.command)
                SshExecResult(0, dnfFedora45Updates, "", 1L)
            }

            val section = h.controller.scanOsUpdates(fedoraServer, h.vault)

            assertEquals(OsScanStatus.OK, section.status)
            assertEquals(23, section.packages.size)
            assertEquals(1, h.fake.executeRequests.size)
        }
    }

    @Test
    fun connectionFailureBecomesAFailedSectionNotAnException() = runTest {
        withController { h ->
            h.serveOs(aptMintListing, probeExit = 0, refresh = { SshExecResult(0, "", "", 1L) })
            h.fake.executeHandler = { _ -> throw IllegalStateException("connection dropped") }

            val section = h.controller.scanOsUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.FAILED, section.status)
            assertEquals(DistroFamily.DEBIAN, section.family)
            assertTrue(section.detail.contains("connection dropped"), section.detail)
        }
    }

    @Test
    fun aRefreshAptRefusesOverAnUnsignedRepositoryIsNamedAsSuch() = runTest {
        withController { h ->
            // REAL capture: a repository whose signing key the node lacks — `apt-get update`
            // exits 100 for this one, it is not a buried warning.
            h.serveOs(aptNothingListed, probeExit = 0, refresh = { SshExecResult(100, "", aptUnsignedRepoStderr, 900L) })

            val section = h.controller.scanOsUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.STALE, section.status)
            assertTrue(
                section.detail.contains("a repository's signing key is missing or expired on this node, so apt refused its index:"),
                section.detail,
            )
            assertTrue(section.detail.contains("NO_PUBKEY 871920D1991BC93C"), section.detail)
        }
    }

    @Test
    fun aRefreshThatGaveUpOnAHeldLockSaysWhoHoldsIt() = runTest {
        withController { h ->
            // REAL capture: `apt-get update` against an apt lock really held by another process.
            h.serveOs(aptNothingListed, probeExit = 0, refresh = { SshExecResult(1, "", aptLockUpdateStderr, 900L) })

            val section = h.controller.scanOsUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.STALE, section.status)
            assertTrue(
                section.detail.contains("another package manager holds the lock on this node (perl, process 2655)"),
                section.detail,
            )
            assertFalse(section.detail.contains("exit 1"), section.detail)
        }
    }

    @Test
    fun aRefreshThatOnlyPartlySucceededLeavesTheListingStale() = runTest {
        withController { h ->
            h.repository.upsertServer(debianServer)
            // REAL capture: one repository unreachable, apt buries it in a warning and still
            // exits 0 — treating that as "refreshed" used to pass a stale index off as current.
            h.serveOs(aptNothingListed, probeExit = 0, refresh = { SshExecResult(0, "", aptPartialRefreshStderr, 900L) })

            val report = h.controller.scanNodeUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.STALE, report.os?.status)
            assertTrue(
                report.os?.detail?.startsWith("Package index only partly refreshed — the node could not fetch every repository's index") == true,
                report.os?.detail,
            )
            assertTrue(
                report.os?.detail?.endsWith("Nothing is listed, but that only reflects the old index — the node may still have updates") == true,
                report.os?.detail,
            )
            val audit = h.singleScanEntry()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertEquals(
                "sudo -n bash -c 'apt-get -o DPkg::Lock::Timeout=30 update -qq'; apt list --upgradable",
                audit.commandSanitized,
            )
            assertTrue(
                audit.outputSnippet.startsWith("apt: 0 pending from a STALE index — the node could not fetch every repository's index"),
                audit.outputSnippet,
            )
        }
    }

    @Test
    fun passwordlessSudoIsProbedAgainWhenTheLoginUserChanges() = runTest {
        withController { h ->
            var probes = 0
            h.fake.executeHandler = { request ->
                when {
                    request.command == HomelabController.SUDO_PROBE_COMMAND -> {
                        probes++
                        // `pi-user` has NOPASSWD; the other login does not.
                        SshExecResult(if (request.server.username == "pi-user") 0 else 1, "", "", 1L)
                    }
                    request.command.contains("update -qq") -> SshExecResult(0, "", "", 1L)
                    request.command.contains("===OSUPD===") -> SshExecResult(0, aptDebian12Listing, "", 1L)
                    else -> throw AssertionError("unexpected command: ${request.command}")
                }
            }
            assertEquals(OsScanStatus.OK, h.controller.scanOsUpdates(debianServer, h.vault).status)

            // Same node id, another login: the first user's right must not carry over.
            val otherUser = debianServer.copy(username = "someone-else")
            val section = h.controller.scanOsUpdates(otherUser, h.vault)

            assertEquals(OsScanStatus.STALE, section.status)
            assertEquals(2, probes)
        }
    }

    // ------------------------------------------------------------------
    // Controller path: persistence and the scan's single audit entry
    // ------------------------------------------------------------------

    private suspend fun ControllerHarness.singleScanEntry() =
        repository.auditEntries.first { it.isNotEmpty() }.also { assertEquals(1, it.size, it.toString()) }.single()

    @Test
    fun aScanIsPersistedAndWritesExactlyOneSuccessfulSystemEntry() = runTest {
        withController { h ->
            h.repository.upsertServer(debianServer)
            h.serveOs(aptMintListing, probeExit = 0)

            val before = currentTimeEpochMs()
            val report = h.controller.scanNodeUpdates(debianServer, h.vault)
            val after = currentTimeEpochMs()

            assertEquals(OsScanStatus.OK, report.os?.status)
            assertEquals(report, NodeUpdateReport.fromJson(h.repository.getServerById(debianServer.id)?.updateReportJson))
            assertTrue(report.scannedAtEpochMs in before..after)

            val audit = h.singleScanEntry()
            assertEquals(AuditCategory.SYSTEM, audit.category)
            assertEquals("Scan for updates", audit.actionTitle)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals(TriggerSource.UI, audit.triggerSource)
            assertEquals(0, audit.exitCode)
            assertTrue(audit.durationMs in 0..(after - before), "duration ${audit.durationMs}")
            assertEquals("sudo -n bash -c 'apt-get -o DPkg::Lock::Timeout=30 update -qq'; apt list --upgradable", audit.commandSanitized)
            assertEquals(
                "apt: 44 pending; index refreshed via passwordless sudo. containers: none (no docker on this node).",
                audit.outputSnippet,
            )
        }
    }

    @Test
    fun aScanAgainstAStaleIndexIsAuditedAsFailedWithTheReason() = runTest {
        withController { h ->
            h.repository.upsertServer(debianServer)
            h.serveOs(aptDebian12Listing, probeExit = 1)

            val report = h.controller.scanNodeUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.STALE, report.os?.status)
            val audit = h.singleScanEntry()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertEquals(-1, audit.exitCode)
            assertEquals("apt list --upgradable", audit.commandSanitized)
            assertTrue(audit.outputSnippet.startsWith("apt: 4 pending from a STALE index — 'pi-user' is neither root"), audit.outputSnippet)
        }
    }

    @Test
    fun safeReadOnlyModeKeepsTheScanReadOnlyAndTheEntryIsBlocked() = runTest {
        withController { h ->
            // No server row: the controller's own poller would add unrelated requests to the log.
            h.repository.updatePreferences { it.copy(safeReadOnlyMode = true) }
            h.serveOs(aptMintListing, probeExit = 0)

            val report = h.controller.scanNodeUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.STALE, report.os?.status)
            assertTrue(report.os?.detail?.contains("Safe Read-Only Mode blocks the index refresh") == true, report.os?.detail)
            // Not even the sudo probe: the refresh is refused before anything is resolved.
            assertEquals(1, h.fake.executeRequests.size)
            val audit = h.singleScanEntry()
            assertEquals(AuditOutcome.BLOCKED, audit.outcome)
            assertTrue(audit.outputSnippet.contains("STALE index — Safe Read-Only Mode blocks the index refresh"), audit.outputSnippet)
        }
    }

    @Test
    fun aListingThatOutlivesItsBudgetFailsTheSectionAndTheEntryIsTimeout() = runTest {
        withController { h ->
            h.serveOs(aptMintListing, probeExit = 0)
            val serve = h.fake.executeHandler
            h.fake.executeHandler = { request ->
                if (request.command.contains("===OSUPD===")) withTimeout(1_000) { delay(2_000); SshExecResult(0, "", "", 1L) } else serve(request)
            }

            val report = h.controller.scanNodeUpdates(debianServer, h.vault)

            assertEquals(OsScanStatus.FAILED, report.os?.status)
            assertTrue(report.os?.detail?.startsWith("Listing timed out") == true, report.os?.detail)
            val audit = h.singleScanEntry()
            assertEquals(AuditOutcome.TIMEOUT, audit.outcome)
        }
    }

    @Test
    fun aCancelledScanWritesItsCancelledEntryAndRethrows() = runTest {
        withController { h ->
            h.fake.executeHandler = { request ->
                when {
                    request.command == HomelabController.SUDO_PROBE_COMMAND -> SshExecResult(0, "", "", 1L)
                    request.command.contains("update -qq") -> SshExecResult(0, "", "", 1L)
                    else -> awaitCancellation() // the listing never answers
                }
            }
            val scan = launch { h.controller.scanNodeUpdates(debianServer, h.vault) }
            runCurrent()

            scan.cancelAndJoin()

            val audit = h.singleScanEntry()
            assertEquals("Scan for updates", audit.actionTitle)
            assertEquals(AuditOutcome.CANCELLED, audit.outcome)
            assertTrue(audit.outputSnippet.startsWith("Cancelled before the scan completed"), audit.outputSnippet)
            assertTrue(audit.durationMs >= 0)
        }
    }

    @Test
    fun aScheduledScanCarriesTheScheduledTriggerSource() = runTest {
        withController { h ->
            h.serveOs(aptMintListing, probeExit = 0)

            h.controller.scanNodeUpdates(debianServer, h.vault, TriggerSource.SCHEDULED)

            assertEquals(TriggerSource.SCHEDULED, h.singleScanEntry().triggerSource)
        }
    }

    @Test
    fun theOsHalfIsHandedOverBeforeTheScanReturns() = runTest {
        withController { h ->
            h.serveOs(aptMintListing, probeExit = 0)
            val seen = mutableListOf<NodeUpdateReport>()

            val report = h.controller.scanNodeUpdates(debianServer, h.vault) { partial -> seen += partial }

            assertEquals(listOf(report), seen)
            assertEquals(44, seen.single().osUpdateCount)
        }
    }

    @Test
    fun aRescanKeepsTheRebootRecommendationOfTheLastApply() = runTest {
        withController { h ->
            h.repository.upsertServer(debianServer)
            h.repository.updateUpdateReport(
                debianServer.id,
                """{"scannedAtEpochMs":1,"rebootRecommended":true}""",
            )
            h.serveOs(aptMintListing, probeExit = 0)
            // The scan reads the previous report off the node as stored, as the UI hands it over.
            val stored = h.repository.getServerById(debianServer.id)!!

            val report = h.controller.scanNodeUpdates(stored, h.vault)

            assertTrue(report.rebootRecommended)
            assertNotNull(NodeUpdateReport.fromJson(h.repository.getServerById(debianServer.id)?.updateReportJson)?.rebootRecommended)
        }
    }
}
