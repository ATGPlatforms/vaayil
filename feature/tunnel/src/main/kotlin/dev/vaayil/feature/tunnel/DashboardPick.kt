package dev.vaayil.feature.tunnel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable

/** J14: the profile picked in the dashboard's pill ([pickedId], null for none) and how to pick one ([pick]). */
@Stable
class DashboardPick(val pickedId: String?, val pick: (String?) -> Unit)

@Composable
fun rememberDashboardPick(liveId: String?): DashboardPick {
    var savedPick by rememberSaveable { mutableStateOf<String?>(null) }
    var savedLiveId by rememberSaveable { mutableStateOf(liveId) }

    val pickedId = pickAfterLiveChange(savedPick, savedLiveId, liveId)
    SideEffect {
        savedPick = pickedId
        savedLiveId = liveId
    }
    val pick: (String?) -> Unit = remember(liveId) {
        { id ->
            savedPick = id
            savedLiveId = liveId
        }
    }
    return DashboardPick(pickedId, pick)
}
