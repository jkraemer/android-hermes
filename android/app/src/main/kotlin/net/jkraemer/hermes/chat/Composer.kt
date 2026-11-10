package net.jkraemer.hermes.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.jkraemer.hermes.sessions.pathIcon

/**
 * The message field with send, or stop while a turn runs. Empty and unfocused it is one pill; focused or holding text
 * it is a card with the field above a row of actions, whose start [leading] fills.
 */
@Composable
internal fun Composer(
    running: Boolean,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    keyboardVisible: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    // Closing the keyboard (back, or its hide key) leaves the field focused; let go of the focus
    // then, so an empty composer folds back into its one-row pill.
    val focusManager = LocalFocusManager.current
    var keyboardWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardVisible) {
        if (keyboardWasVisible && !keyboardVisible) focusManager.clearFocus()
        keyboardWasVisible = keyboardVisible
    }
    val expanded = focused || text.isNotEmpty()
    val sendOrStop = @Composable {
        if (running) {
            FilledIconButton(onStop, Modifier.semantics { contentDescription = "Stop" }) {
                Box(Modifier.size(14.dp).background(LocalContentColor.current, RoundedCornerShape(2.dp)))
            }
        } else {
            FilledIconButton(
                onClick = {
                    onSend(text.trim())
                    text = ""
                },
                enabled = canSend && text.isNotBlank(),
            ) { Icon(ArrowUpwardIcon, contentDescription = "Send") }
        }
    }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("composer-box"),
        shape = if (expanded) RoundedCornerShape(24.dp) else CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // The field has the same place in both shapes, so it keeps its focus as the composer expands.
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MessageField(
                    text,
                    onTextChange = { text = it },
                    Modifier.weight(1f).padding(vertical = if (expanded) 8.dp else 0.dp).onFocusChanged { focused = it.isFocused },
                )
                if (!expanded) sendOrStop()
            }
            if (expanded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    leading()
                    Spacer(Modifier.weight(1f))
                    sendOrStop()
                }
            }
        }
    }
}

/** Grows with its text up to six lines, then scrolls. */
@Composable
private fun MessageField(text: String, onTextChange: (String) -> Unit, modifier: Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    BasicTextField(
        text,
        onTextChange,
        modifier.testTag("composer"),
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 6,
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("Message Hermes", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                field()
            }
        },
    )
}

// The Material "arrow upward" icon, which material-icons-core does not have.
private val ArrowUpwardIcon = pathIcon("ArrowUpward", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
