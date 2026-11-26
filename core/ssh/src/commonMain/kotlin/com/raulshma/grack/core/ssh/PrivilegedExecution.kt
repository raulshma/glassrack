package com.raulshma.grack.core.ssh

/**
 * Single-quote shell escaping used everywhere a remote command embeds a user-controlled
 * fragment (unit names, paths, ...). `'…'` keeps every character literal; an embedded `'`
 * is closed and reopened as `'"'"'`.
 */
fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

/** How [com.raulshma.grack.core.ssh.HomelabController.executePrivileged] runs a command on the node. */
internal enum class PrivilegeMode {
    /** Login user is root (`capabilities.isRoot`): run the command as-is. */
    ROOT,

    /** `sudo -n true` succeeded (result cached for [SUDO_PROBE_TTL_MS] per server). */
    PASSWORDLESS_SUDO,

    /** A sudo credential is available; it is delivered via stdin only (`sudo -S -p ''`). */
    SUDO_WITH_CREDENTIAL,

    /** No privilege path known: today's fallback `sudo -n <cmd> || <cmd>`. */
    UNPRIVILEGED_FALLBACK,
}

/**
 * Raised when an SFTP transfer fails because the login user lacks permission on the target
 * (JSch `SSH_FX_PERMISSION_DENIED`). Callers use it to trigger the privileged save path
 * (temp upload + `cp -p` backup + `install`), instead of failing the save outright.
 */
class FilePermissionDeniedException(message: String) : Exception(message)
