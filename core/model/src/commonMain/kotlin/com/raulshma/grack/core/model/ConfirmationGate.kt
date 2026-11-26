package com.raulshma.grack.core.model

/**
 * Single source of truth for whether an in-app destructive action must pass a confirmation
 * gate (ActionSafetyDialog / HoldToConfirmPill / biometric prompt) before it executes.
 *
 * Every in-app call site (view models, panes, widgets) asks this helper instead of
 * hand-rolling its own rule, so the gate cannot be bypassed by one pane while another
 * honours it, and the [ConfirmationLevel.NONE] setting is honoured everywhere.
 */
fun requiresConfirmation(
    actionIsDestructive: Boolean,
    prefs: UserPreferences,
): Boolean = actionIsDestructive && prefs.destructiveConfirmation != ConfirmationLevel.NONE

/** systemd verbs that interrupt a running unit and therefore count as destructive. */
fun isDestructiveServiceVerb(verb: String): Boolean =
    verb.trim().lowercase() in DESTRUCTIVE_SERVICE_VERBS

/** Compose stack verbs that tear a stack down and therefore count as destructive. */
fun isDestructiveComposeVerb(verb: String): Boolean =
    verb.trim().lowercase() in DESTRUCTIVE_COMPOSE_VERBS

private val DESTRUCTIVE_SERVICE_VERBS = setOf("stop", "restart")

private val DESTRUCTIVE_COMPOSE_VERBS = setOf("down")
