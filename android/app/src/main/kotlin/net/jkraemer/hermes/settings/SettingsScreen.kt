package net.jkraemer.hermes.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The app's settings; [favoriteCount] is how many favorite models are set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(favoriteCount: Int, onBack: () -> Unit, onOpenFavoriteModels: () -> Unit) {
    Scaffold(
        modifier = Modifier.testTag("settings-screen"),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        ListItem(
            headlineContent = { Text("Favorite models") },
            supportingContent = { Text(if (favoriteCount == 0) "None" else favoriteCount.toString()) },
            modifier = Modifier.padding(padding).clickable(onClick = onOpenFavoriteModels),
        )
    }
}
