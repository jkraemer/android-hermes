package net.jkraemer.hermes.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jkraemer.hermes.core.chat.RpcPeer
import net.jkraemer.hermes.core.options.ModelCatalog
import net.jkraemer.hermes.core.options.ModelRef
import net.jkraemer.hermes.core.options.modelCatalog

/** [catalog] is null until the models are read; [loading] while they are being read, [failed] when they could not be. */
data class FavoriteModelsState(val catalog: ModelCatalog? = null, val loading: Boolean = true, val failed: Boolean = false)

/** The models Hermes offers, once [load]ed, and the favorites among them. */
class FavoriteModelsViewModel(private val peer: () -> RpcPeer, private val store: FavoritesStore) : ViewModel() {
    private val _state = MutableStateFlow(FavoriteModelsState())
    val state: StateFlow<FavoriteModelsState> = _state.asStateFlow()

    val favorites: StateFlow<List<ModelRef>> = store.favorites

    /** Reads the models; those read before stay on show meanwhile, and when they cannot be read again. */
    fun load() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            try {
                _state.value = FavoriteModelsState(peer().modelCatalog(null), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = it.catalog == null) }
            }
        }
    }

    fun toggle(ref: ModelRef) = store.toggle(ref)

    fun move(from: Int, to: Int) = store.move(from, to)
}
