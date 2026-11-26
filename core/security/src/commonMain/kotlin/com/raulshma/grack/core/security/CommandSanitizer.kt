package com.raulshma.grack.core.security

object CommandSanitizer {
    private val envSecretRegex = Regex(
        """\b([A-Z0-9_]*(?:PASS|PASSWORD|PASSWD|TOKEN|SECRET|API_KEY|ACCESS_KEY|PRIVATE_KEY|AUTH)[A-Z0-9_]*)=("[^"]*"|'[^']*'|\S+)""",
        RegexOption.IGNORE_CASE,
    )
    private val cliFlagSecretRegex = Regex(
        """(--?(?:password|passwd|pass|token|secret|api-key|auth-token|key-passphrase)\s*[=\s]\s*)("[^"]*"|'[^']*'|\S+)""",
        RegexOption.IGNORE_CASE,
    )
    private val bearerRegex = Regex(
        """(Bearer\s+)[A-Za-z0-9\-._~+/]+=*""",
        RegexOption.IGNORE_CASE,
    )
    private val sudoStdinRegex = Regex(
        """(echo\s+)("[^"]*"|'[^']*'|\S+)(\s*\|\s*sudo\s+-S)""",
        RegexOption.IGNORE_CASE,
    )
    private val pemBlockRegex = Regex(
        """-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----""",
    )
    private val sshPassRegex = Regex(
        """(sshpass\s+-p\s*)("[^"]*"|'[^']*'|\S+)""",
        RegexOption.IGNORE_CASE,
    )

    fun sanitize(command: String): String {
        var result = command
        result = pemBlockRegex.replace(result, "[REDACTED_PRIVATE_KEY]")
        result = sudoStdinRegex.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]${match.groupValues[3]}"
        }
        result = sshPassRegex.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        result = envSecretRegex.replace(result) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        result = cliFlagSecretRegex.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        result = bearerRegex.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        return result
    }
}
