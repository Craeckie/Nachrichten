package de.heute.nachrichten.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import de.heute.nachrichten.R

/**
 * Opens a resolved stream URL in an external video player.
 *
 * Strategy (honors the "VLC or mpv" requirement; relies on the `<queries>` block in the
 * manifest so package detection works on API 30+):
 *  - exactly one of VLC / mpv installed → launch it directly,
 *  - both installed (or neither) → show the system "Open with" chooser so the user picks,
 *  - nothing can handle it → return false (caller shows a message).
 *
 * VLC and mpv both play progressive MP4 and HLS `.m3u8` natively.
 */
object PlayerLauncher {

    private const val VLC = "org.videolan.vlc"
    private const val MPV = "is.xyz.mpv"

    /** Returns true if a player activity was started. */
    fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val installed = listOf(VLC, MPV).filter { isInstalled(context, it) }
        return try {
            if (installed.size == 1) {
                context.startActivity(intent.setPackage(installed.first()))
            } else {
                val chooser = Intent.createChooser(intent, context.getString(R.string.open_with))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) return false
                context.startActivity(chooser)
            }
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean =
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}
