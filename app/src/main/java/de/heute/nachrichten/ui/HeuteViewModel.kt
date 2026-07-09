package de.heute.nachrichten.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.heute.nachrichten.data.Episode
import de.heute.nachrichten.data.ZdfRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime

sealed interface UiState {
    data object Loading : UiState
    data class Success(val episodes: List<Episode>) : UiState
    data class Error(val message: String) : UiState
}

/** One-shot effects the UI consumes (launch a player or show an error). */
sealed interface PlayEvent {
    data class Launch(val url: String) : PlayEvent
    data class Failed(val message: String) : PlayEvent
}

class HeuteViewModel : ViewModel() {

    private val repo = ZdfRepository()

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** True while a pull-to-refresh reload is in flight on top of an already-loaded list. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** canonical IDs of episodes currently being resolved (drives a per-card spinner). */
    private val _resolving = MutableStateFlow<Set<String>>(emptySet())
    val resolving: StateFlow<Set<String>> = _resolving.asStateFlow()

    private val _events = MutableSharedFlow<PlayEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PlayEvent> = _events.asSharedFlow()

    private val _currentTime = MutableStateFlow(LocalDateTime.now())
    val currentTime: StateFlow<LocalDateTime> = _currentTime.asStateFlow()

    init {
        refresh()
        startTimeUpdater()
    }

    private fun startTimeUpdater() {
        viewModelScope.launch {
            while (true) {
                delay(3_600_000) // Update every hour
                _currentTime.value = LocalDateTime.now()
            }
        }
    }

    fun refresh() {
        val isReload = _state.value is UiState.Success
        if (isReload) {
            _isRefreshing.value = true
        } else {
            _state.value = UiState.Loading
        }
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repo.loadEpisodes())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to load episodes.")
            }
            _isRefreshing.value = false
        }
    }

    fun openEpisode(episode: Episode) {
        if (episode.canonical in _resolving.value) return
        viewModelScope.launch {
            _resolving.value = _resolving.value + episode.canonical
            try {
                _events.emit(PlayEvent.Launch(repo.resolveStreamUrl(episode)))
            } catch (e: Exception) {
                _events.emit(PlayEvent.Failed(e.message ?: "Could not open video."))
            } finally {
                _resolving.value = _resolving.value - episode.canonical
            }
        }
    }
}
