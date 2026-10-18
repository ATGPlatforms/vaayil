package dev.vaayil.feature.tunnel

import dev.vaayil.core.model.Profile

/**
 * J14: the dashboard's pick once the live (ACTIVE) profile has changed from [previousLiveId] to [liveId]. A profile
 * that went live from anywhere else (the Profiles tab, the notification, automation, boot) and isn't the pick
 * replaces it, so the dashboard shows what is live; a disconnect (null), an unchanged live profile (a failed switch,
 * a restart) and a switch to the pick itself keep it.
 */
fun pickAfterLiveChange(pickedId: String?, previousLiveId: String?, liveId: String?): String? =
    if (liveId != null && liveId != previousLiveId && liveId != pickedId) null else pickedId

/**
 * The profile the dashboard disc acts on (F23): the one picked in the pill, else the live one,
 * else the last one that connected, else the newest. Disconnect clears the active record, so
 * without the last-connected step the disc jumped to whichever profile was edited most recently.
 */
fun dashboardTarget(
    profiles: List<Profile>,
    pickedId: String?,
    activeId: String?,
    lastConnectedId: String?,
): Profile? =
    profiles.firstOrNull { it.id == pickedId }
        ?: profiles.firstOrNull { it.id == activeId }
        ?: profiles.firstOrNull { it.id == lastConnectedId }
        ?: profiles.firstOrNull()
