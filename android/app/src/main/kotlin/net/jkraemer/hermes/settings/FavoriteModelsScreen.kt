package net.jkraemer.hermes.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jkraemer.hermes.core.options.ModelCatalog
import net.jkraemer.hermes.core.options.ModelRef
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The lazy list items before the first favorite: the "Favorites" heading. */
private const val ITEMS_BEFORE_FAVORITES = 1

/**
 * Every model Hermes offers, by provider, searchable, each with a star; the favorites come first and are dragged
 * into the order the user wants.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteModelsScreen(viewModel: FavoriteModelsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    // The models may have changed since the screen was last open.
    LaunchedEffect(viewModel) { viewModel.load() }
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.testTag("favorite-models-screen"),
        topBar = {
            TopAppBar(
                title = { Text("Favorite models") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val catalog = state.catalog
            when {
                catalog != null -> Models(catalog, favorites, query, { query = it }, viewModel::toggle, viewModel::move)
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load models", color = MaterialTheme.colorScheme.error)
                    TextButton(viewModel::load) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun Models(
    catalog: ModelCatalog,
    favorites: List<ModelRef>,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (ModelRef) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index - ITEMS_BEFORE_FAVORITES, to.index - ITEMS_BEFORE_FAVORITES)
    }
    val searching = query.isNotBlank()
    val matching = catalog.providers.filter { it.authenticated }.mapNotNull { provider ->
        val models = provider.models.filter { it.contains(query.trim(), ignoreCase = true) }
        if (models.isEmpty()) null else provider to models
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query,
            onQuery,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("Search models") },
            singleLine = true,
        )
        LazyColumn(Modifier.fillMaxSize(), listState) {
            // Reordering the favorites while a search hides some of them would be reordering blind.
            if (!searching && favorites.isNotEmpty()) {
                item(key = "favorites-heading") { Heading("Favorites") }
                items(favorites, key = { "fav:" + it.provider + "/" + it.model }) { ref ->
                    ReorderableItem(reorderState, key = "fav:" + ref.provider + "/" + ref.model) {
                        ListItem(
                            headlineContent = { Text(ref.model) },
                            supportingContent = { Text(if (catalog.offers(ref)) providerName(catalog, ref) else "No longer offered") },
                            leadingContent = {
                                Icon(Icons.Filled.Menu, contentDescription = "Reorder", modifier = Modifier.draggableHandle())
                            },
                            trailingContent = { StarButton(true) { onToggle(ref) } },
                            modifier = Modifier.testTag("fav:" + ref.provider + "/" + ref.model).alpha(if (catalog.offers(ref)) 1f else 0.5f),
                        )
                    }
                }
            }
            if (matching.isEmpty()) {
                item(key = "no-models") {
                    Text(
                        "No matching models",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for ((provider, models) in matching) {
                item(key = "provider:" + provider.slug) { Heading(provider.name) }
                items(models, key = { "model:" + provider.slug + "/" + it }) { model ->
                    val ref = ModelRef(provider.slug, model)
                    val favorite = ref in favorites
                    ListItem(
                        headlineContent = { Text(model) },
                        trailingContent = { StarButton(favorite) { onToggle(ref) } },
                        modifier = Modifier.testTag("model:" + provider.slug + "/" + model),
                    )
                }
            }
        }
    }
}

@Composable
private fun StarButton(favorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(
            Icons.Filled.Star,
            contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun providerName(catalog: ModelCatalog, ref: ModelRef) =
    catalog.providers.firstOrNull { it.slug == ref.provider }?.name ?: ref.provider
