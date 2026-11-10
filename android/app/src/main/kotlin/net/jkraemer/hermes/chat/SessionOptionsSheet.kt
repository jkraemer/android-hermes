package net.jkraemer.hermes.chat

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jkraemer.hermes.core.options.ModelCapabilities
import net.jkraemer.hermes.core.options.ModelRef
import net.jkraemer.hermes.core.options.REASONING_LEVELS
import net.jkraemer.hermes.core.options.SessionOptions
import net.jkraemer.hermes.core.options.favoritesOffered

/** The model's id without its vendor prefix, and the reasoning level unless it is Hermes' default. */
private fun SessionOptions.chipText(): String {
    val name = model.substringAfterLast('/')
    return when (reasoning) {
        "" -> name
        "none" -> "$name · off"
        else -> "$name · $reasoning"
    }
}

/** The model and reasoning level the session runs on; a tap opens the options sheet. */
@Composable
internal fun ModelChip(options: SessionOptions?, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick,
        label = { Text(options?.chipText() ?: "Model") },
        modifier = Modifier.testTag("model-chip"),
        enabled = enabled && options != null,
    )
}

private val REASONING_LABELS = mapOf("none" to "Off", "xhigh" to "XHigh")

private fun reasoningLabel(level: String) = REASONING_LABELS[level] ?: level.replaceFirstChar(Char::uppercase)

private val UNLOADED_CATALOG_CAPABILITIES = ModelCapabilities(reasoning = true, canDisableReasoning = true, fast = false)

/**
 * What the session runs on, for [viewModel]'s chat only: its model among the favorites that Hermes offers
 * ([onOpenFavorites] chooses them), its reasoning level, fast mode and YOLO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionOptionsSheet(viewModel: ChatViewModel, onOpenFavorites: () -> Unit, onDismiss: () -> Unit) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val catalog by viewModel.modelCatalog.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val deferred by viewModel.switchDeferred.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadModelCatalog() }

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val options = chat.options ?: return@ModalBottomSheet
        val current = ModelRef(options.provider, options.model)
        val capabilities = catalog?.capabilities(current) ?: UNLOADED_CATALOG_CAPABILITIES
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("options-sheet"),
            verticalArrangement = spacedBy(8.dp),
        ) {
            Heading("Model")
            val offered = catalog?.let { favoritesOffered(favorites, it) }
            when {
                offered == null -> CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                offered.isEmpty() -> NoFavorites(favorites.isEmpty(), onOpenFavorites)
                else -> offered.forEach { ModelRow(it, selected = it == current, onClick = { viewModel.switchModel(it) }) }
            }
            if (deferred) Note("Applies from the next message")

            if (capabilities.reasoning) {
                Heading("Reasoning")
                ReasoningChoices(options.reasoning, capabilities.canDisableReasoning, viewModel::setReasoning)
            }
            if (capabilities.fast) SwitchRow("Fast mode", options.fast, viewModel::setFast)
            SwitchRow("YOLO (skip approvals)", options.yolo, viewModel::setYolo)
            if (options.approvalsOff) Note("YOLO is on for every session in Hermes' config")
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelRow(ref: ModelRef, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null)
        Column {
            Text(ref.model, style = MaterialTheme.typography.bodyLarge)
            Text(ref.provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [noneChosen] is whether the user has no favorites at all, as opposed to none Hermes offers now. */
@Composable
private fun NoFavorites(noneChosen: Boolean, onOpenFavorites: () -> Unit) {
    Text(if (noneChosen) "No favorite models yet" else "Hermes offers none of your favorite models", style = MaterialTheme.typography.bodyMedium)
    TextButton(onOpenFavorites) { Text("Choose favorites") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasoningChoices(selected: String, canDisable: Boolean, onChoose: (String) -> Unit) {
    FlowRow(horizontalArrangement = spacedBy(8.dp)) {
        for (level in REASONING_LEVELS) {
            if (level == "none" && !canDisable) continue
            FilterChip(selected == level, onClick = { onChoose(level) }, label = { Text(reasoningLabel(level)) })
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null)
    }
}
