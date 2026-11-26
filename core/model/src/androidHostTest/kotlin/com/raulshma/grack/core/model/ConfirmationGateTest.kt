package com.raulshma.grack.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfirmationGateTest {

    private fun prefs(level: ConfirmationLevel) = UserPreferences(destructiveConfirmation = level)

    @Test
    fun destructiveActionPromptsAtEveryLevelExceptNone() {
        assertTrue(requiresConfirmation(true, prefs(ConfirmationLevel.DIALOG)))
        assertTrue(requiresConfirmation(true, prefs(ConfirmationLevel.HOLD_TO_CONFIRM)))
        assertTrue(requiresConfirmation(true, prefs(ConfirmationLevel.BIOMETRIC)))
        // The bug this gate fixes: reboot used to prompt even with the gate switched off.
        assertFalse(requiresConfirmation(true, prefs(ConfirmationLevel.NONE)))
    }

    @Test
    fun nonDestructiveActionNeverPrompts() {
        ConfirmationLevel.entries.forEach { level ->
            assertFalse(requiresConfirmation(false, prefs(level)), "level $level must not prompt")
        }
    }

    @Test
    fun systemdStopAndRestartAreDestructiveOtherVerbsAreNot() {
        assertTrue(isDestructiveServiceVerb("stop"))
        assertTrue(isDestructiveServiceVerb("restart"))
        assertTrue(isDestructiveServiceVerb(" Restart "))
        assertFalse(isDestructiveServiceVerb("start"))
        assertFalse(isDestructiveServiceVerb("logs"))
    }

    @Test
    fun composeDownIsDestructiveUpAndPullAreNot() {
        assertTrue(isDestructiveComposeVerb("down"))
        assertFalse(isDestructiveComposeVerb("up -d"))
        assertFalse(isDestructiveComposeVerb("pull"))
    }

    @Test
    fun helperMatchesThePerActionModelFlags() {
        // Container stop is destructive and gated; start is not.
        assertTrue(requiresConfirmation(ContainerAction.STOP.isDestructive, prefs(ConfirmationLevel.DIALOG)))
        assertFalse(requiresConfirmation(ContainerAction.START.isDestructive, prefs(ConfirmationLevel.DIALOG)))
        // SIGKILL is destructive; SIGCONT is not.
        assertTrue(requiresConfirmation(ProcessSignal.SIGKILL.isDestructive, prefs(ConfirmationLevel.DIALOG)))
        assertFalse(requiresConfirmation(ProcessSignal.SIGCONT.isDestructive, prefs(ConfirmationLevel.DIALOG)))
    }
}
