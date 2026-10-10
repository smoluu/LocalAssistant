package com.localassistant.app.ui.common

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

/**
 * Finding out whether the platform has made this app its default digital
 * assistant, and opening the Settings page that lets the user do it.
 *
 * The assistant role is the platform's own mechanism: an app that declares a
 * voice-interaction service is eligible for [RoleManager.ROLE_ASSISTANT] and the
 * system "Default digital assistant" picker is what binds that role. Reading it
 * here is what lets the app tell the user "you have not set me as the
 * assistant" instead of silently listening forever behind a notification nobody
 * taps.
 *
 * [RoleManager] is the generic role API - the same one used for
 * `ROLE_MICROPHONE`-style capabilities - and `ROLE_ASSISTANT` is the role the
 * voice-interaction service makes the app eligible for. Older platforms have no
 * such role, so every entry point here answers "unknown" rather than guessing,
 * and the settings page is opened through its documented fallback key.
 */

private const val assistantSettingsKey = "android.settings.voice_input"

/**
 * Whether the platform has bound this app as its default digital assistant.
 *
 * Returns null when the platform exposes no assistant role at all, which the
 * caller must treat as "cannot tell" - never as "not set", because claiming the
 * user has to go set something they cannot set would be wrong.
 */
fun isDefaultAssistantHeld(context: Context): Boolean? {
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
    if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
    return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
}

/**
 * The intent that opens the Settings page where the assistant is chosen.
 *
 * Prefers the role request - it is the platform's own way of taking the user to
 * the picker for the role this app is eligible for - and falls back to the
 * documented settings key for platforms where the role API is absent.
 */
fun assistantSettingsIntent(context: Context): Intent {
    val roleManager = context.getSystemService(RoleManager::class.java)
    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }
    return Intent(assistantSettingsKey)
}
