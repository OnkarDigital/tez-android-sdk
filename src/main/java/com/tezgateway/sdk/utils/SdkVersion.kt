package com.tezgateway.sdk.utils

/**
 * SDK version info + comparison, used only to show a small "update available"
 * tag in the checkout UI. Does NOT affect payment behaviour in any way — the
 * merchant's integrated version keeps working exactly as before regardless of
 * what the server reports as "latest".
 *
 * IMPORTANT: [CURRENT] must be bumped by hand alongside `versionName` in
 * build.gradle on every release — there is no build-time link between them
 * (kept that way deliberately, so this file never needs BuildConfig / extra
 * Gradle build-feature flags that could affect consumers of the library).
 */
object SdkVersion {

    /** Mirrors build.gradle's defaultConfig.versionName. */
    const val CURRENT: String = "1.0.27"

    /**
     * True when [latest] (as reported by get_checkout_settings.php) is a newer
     * version than [CURRENT]. Compares dot-separated numeric segments (e.g.
     * "1.0.26" > "1.0.25"); any non-numeric/blank input is treated as "no
     * update" rather than guessing, so a malformed value from the server can
     * never wrongly show the badge.
     */
    fun isUpdateAvailable(latest: String, current: String = CURRENT): Boolean {
        if (latest.isBlank()) return false
        val latestParts = latest.trim().split(".").map { it.toIntOrNull() }
        val currentParts = current.trim().split(".").map { it.toIntOrNull() }
        if (latestParts.any { it == null } || currentParts.any { it == null }) return false

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrNull(i) ?: 0
            val c = currentParts.getOrNull(i) ?: 0
            if (l != c) return l > c
        }
        return false
    }
}
