package com.jay.glossy.spotify

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.glossy.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotifyAccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SpotifyLibraryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpotifyAccountUiState(isLoading = true))
    val uiState: StateFlow<SpotifyAccountUiState> = _uiState.asStateFlow()
    val playlists = repository.playlists

    init {
        restoreSession()
    }

    fun restoreSession() {
        viewModelScope.launch(Dispatchers.IO) {
            val isAuth = repository.restoreSession()
            val name = context.dataStore.data.first()[SpotifyAccountNameKey].orEmpty()
            _uiState.update { it.copy(isAuthenticated = isAuth, accountName = name, isLoading = false) }
            if (isAuth) refreshPlaylists()
        }
    }

    fun connectWithCookies(spDc: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            runCatching { repository.connectWithCookies(spDc) }
                .onSuccess { 
                    val name = context.dataStore.data.first()[SpotifyAccountNameKey].orEmpty()
                    _uiState.update { it.copy(isAuthenticated = true, accountName = name, isLoading = false) }
                    refreshPlaylists()
                }
                .onFailure { _uiState.update { state -> state.copy(isLoading = false) } }
        }
    }

    fun refreshPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshPlaylists()
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.logout()
            _uiState.value = SpotifyAccountUiState()
        }
    }
}

data class SpotifyAccountUiState(
    val isAuthenticated: Boolean = false,
    val accountName: String = "",
    val isLoading: Boolean = false
)
