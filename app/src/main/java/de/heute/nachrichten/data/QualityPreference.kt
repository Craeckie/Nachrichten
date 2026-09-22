package de.heute.nachrichten.data

import android.content.Context

// The progressive video quality the user picked in the format selector, persisted across
// launches. `null` means "best" (today's default behavior): resolution then falls through
// to ZdfClient.pickBestProgressive's ordinary best-first pick.
class QualityPreference(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var quality: String?
        get() = prefs.getString(KEY_QUALITY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_QUALITY) else putString(KEY_QUALITY, value)
            }.apply()
        }

    private companion object {
        const val PREFS_NAME = "quality-preference"
        const val KEY_QUALITY = "quality"
    }
}
