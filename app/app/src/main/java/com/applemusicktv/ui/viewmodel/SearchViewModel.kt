package com.applemusicktv.ui.viewmodel

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.network.GenreDto
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.data.repository.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

data class SearchUiState(
    val query:          String         = "",
    val isLoading:      Boolean        = false,
    val results:        SearchResults? = null,
    val error:          String?        = null,
    val genres:         List<GenreDto> = emptyList(),
    val selectedGenreId: String?       = null,
    val genreContent:   com.applemusicktv.data.network.HomeResponse? = null,
    val genreLoading:   Boolean        = false,
    val categories:     List<com.applemusicktv.data.repository.CategoryGroup> = emptyList(),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val history: com.applemusicktv.data.SearchHistoryPreferences,
) : ViewModel() {

    val recentSearches: StateFlow<List<String>> = history.recent

    private val _state    = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val queryFlow = MutableStateFlow("" to 0L)

    init {
        viewModelScope.launch {
            repo.getCategories().onSuccess { cats -> _state.update { it.copy(categories = cats) } }
        }
        viewModelScope.launch {
            queryFlow
                .debounce(350)
                .collectLatest { (term, _) ->
                    // Empty requests must reach collectLatest so clearing the field cancels
                    // the old request, rather than filtering it out upstream.
                    if (term.isBlank()) return@collectLatest
                    _state.update { it.copy(isLoading = true, error = null) }
                    val result = repo.search(term)
                    currentCoroutineContext().ensureActive()
                    if (_state.value.query.trim() != term) return@collectLatest
                    result.onSuccess { r -> _state.update { it.copy(isLoading = false, results = r) } }
                        .onFailure { _ -> _state.update { it.copy(isLoading = false, error = tr(R.string.msg_072), results = null) } }
                }
        }
    }

    fun onQueryChange(q: String) {
        // Drop stale results immediately; even one Chinese character is a valid query.
        val tooShort = q.isBlank()
        _state.update {
            it.copy(
                query = q, selectedGenreId = null, genreContent = null,
                results = null,
                isLoading = !tooShort,
                error = null,
            )
        }
        queryFlow.value = q.trim() to (queryFlow.value.second + 1)
    }
    /** Called when the user presses Go/Search on the IME — the only place a term is saved. */
    fun commitSearch() {
        val q = _state.value.query.trim()
        if (q.isNotEmpty()) {
            history.add(q)
            queryFlow.value = q to (queryFlow.value.second + 1)
        }
    }
    fun runRecent(term: String) { onQueryChange(term); history.add(term) }
    fun removeRecent(term: String) = history.remove(term)
    fun clearRecents() = history.clear()

    fun clearSearch() { _state.value = SearchUiState(categories = _state.value.categories); queryFlow.value = "" to (queryFlow.value.second + 1) }
    fun selectGenre(id: String) {
        _state.update { it.copy(selectedGenreId = id, genreLoading = true, genreContent = null) }
        viewModelScope.launch {
            repo.getGenreContent(id).onSuccess { r -> _state.update { it.copy(genreContent = r, genreLoading = false) } }
                .onFailure { _state.update { it.copy(genreLoading = false) } }
        }
    }
}
