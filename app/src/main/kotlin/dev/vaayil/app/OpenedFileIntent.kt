package dev.vaayil.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * The file another app asked Vaayil to open, or null when the intent is not an open at all (the
 * launcher's, a shortcut's reopen, or any other action).
 *
 * Open with hands its file in [Intent.getData] and Share in [Intent.EXTRA_STREAM]; a sender may
 * put the stream only in ClipData. Everything else is refused without being read:
 * - an app's text is never read: [Intent.EXTRA_TEXT] can be any amount of text an app typed, not
 *   the file it says it is sharing, and importing it would take the app's word for a config, so a
 *   share that carries only text is refused;
 * - a URI that is not `content:` is refused: a `file:` path lies outside another app's grant and
 *   would need a storage permission Vaayil does not hold, while another scheme (a web or resource
 *   URL) is not a file handed over at all. The filters accept no BROWSABLE category either, so a
 *   web page cannot start an import;
 * - a URI served by one of Vaayil's own providers is refused: otherwise an app could hand back
 *   Vaayil's own data and make it read that as a config, a confused deputy reading its own files.
 *
 * [isOwnAuthority] answers that last check; [isOwnProviderAuthority] is its production form. A
 * sender's malformed parcel (a stream extra of another type) is refused too rather than crashing.
 */
internal fun openRequestOf(intent: Intent?, isOwnAuthority: (String) -> Boolean): OpenRequest? {
    if (intent == null) return null
    val uri = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> try {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } catch (_: RuntimeException) {
            null
        }
        else -> return null
    }
    if (uri == null || uri.scheme != "content") return OpenRequest.Refused
    val authority = uri.authority ?: return OpenRequest.Refused
    if (isOwnAuthority(authority)) return OpenRequest.Refused
    return OpenRequest.File(uri)
}

/**
 * Whether [authority] names one of this app's own providers. ContentResolver strips a user-id
 * prefix before it dispatches, so this drops the same prefix, and compares without case: a
 * provider is matched the way the system would match it, however an app writes the URI.
 */
internal fun Context.isOwnProviderAuthority(authority: String): Boolean {
    val name = authority.substringAfterLast('@')
    return ownProviderAuthorities().any { it.equals(name, ignoreCase = true) }
}

private fun Context.ownProviderAuthorities(): List<String> {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, PackageManager.GET_PROVIDERS)
    }
    return info.providers.orEmpty().flatMap { it.authority?.split(';').orEmpty() }
}

/**
 * Whether this launch is the one that may handle its intent. A recreation carries a saved state,
 * and a return from Recents sets [Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY]; handling the
 * intent again would open a second review for the same file — and after a process death the read
 * grant that intent relied on is gone, so the read could only fail.
 */
internal fun isFreshLaunch(savedInstanceState: Bundle?, intent: Intent?): Boolean =
    savedInstanceState == null &&
        intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

/**
 * The type a log line may name: one of the fixed labels the filters accept, or "unset"/"other".
 * A sender controls the type, so only these values ever reach the log.
 */
internal fun loggedOpenType(type: String?): String = when (type) {
    null -> "unset"
    "application/octet-stream", "text/plain" -> type
    else -> "other"
}
