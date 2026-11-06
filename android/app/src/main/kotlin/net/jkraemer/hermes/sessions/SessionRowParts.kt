package net.jkraemer.hermes.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jkraemer.hermes.core.sessions.SessionSummary

/** What a session is called in lists: its title, else the start of the conversation. */
val SessionSummary.displayTitle: String get() = title ?: preview ?: "Untitled"

private fun outlinedIcon(name: String, pathData: String) = ImageVector.Builder(
    name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

// The Material "schedule" and "chat bubble outline" icons, which material-icons-core does not have
// and material-icons-extended is too large to pull in for.
private val ScheduleIcon = outlinedIcon(
    "Schedule",
    "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
)
private val ChatBubbleIcon = outlinedIcon(
    "ChatBubbleOutline",
    "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM20,16H5.17L4,17.17V4h16v12z",
)

/** A clock for a cron run, a chat bubble for any other session. */
@Composable
fun KindIcon(session: SessionSummary, size: Dp = 24.dp) {
    Icon(
        if (session.isCron) ScheduleIcon else ChatBubbleIcon,
        contentDescription = if (session.isCron) "Cron run" else "Chat",
        Modifier.size(size),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun UnreadDot() {
    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape).testTag("unread-dot"))
}
