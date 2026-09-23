package com.jay.glossy.spotify

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.glossy.spotifycore.Spotify
import com.jay.glossy.spotifycore.SpotifyAuth
import com.jay.glossy.spotifycore.models.SpotifyPlaylist
import com.jay.glossy.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

val SpotifySpDcKey = stringPreferencesKey("spotify_sp_dc")
val SpotifyAccessTokenKey = stringPreferencesKey("spotify_access_token")
val SpotifyAccessTokenExpiresAtKey = longPreferencesKey("spotify_token_expires_at")
val SpotifyAccountNameKey = stringPreferencesKey("spotify_account_name")

@Singleton
class SpotifyLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _playlists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val playlists: StateFlow<List<SpotifyPlaylist>> = _playlists.asStateFlow()

    private val tokenRefreshMutex = Mutex()

    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val token = prefs[SpotifyAccessTokenKey].orEmpty()
        val expiresAt = prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L
        if (token.isNotBlank() && expiresAt > System.currentTimeMillis() + 60000L) {
            Spotify.accessToken = token
            return@withContext true
        }
        val spDc = prefs[SpotifySpDcKey].orEmpty()
        if (spDc.isBlank()) return@withContext false
        
        refreshAccessToken(spDc).isSuccess
    }

    suspend fun connectWithCookies(spDc: String) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            prefs[SpotifySpDcKey] = spDc
            prefs.remove(SpotifyAccessTokenKey)
        }
        Spotify.accessToken = null
        refreshAccessToken(spDc).getOrThrow()
    }

    suspend fun refreshPlaylists(): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        ensureAuthenticated()
        val playlistsList = ArrayList<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val page = Spotify.myPlaylists(limit = 50, offset = offset).getOrThrow()
            if (page.items.isEmpty()) break
            playlistsList += page.items
            offset += page.items.size
            if (offset >= page.total) break
        }
        _playlists.value = playlistsList
        playlistsList
    }

    suspend fun logout() {
        context.dataStore.edit { it.clear() }
        _playlists.value = emptyList()
        Spotify.accessToken = null
    }

    private suspend fun ensureAuthenticated() {
        if (!restoreSession()) error("Not connected")
    }

    private suspend fun refreshAccessToken(spDc: String): Result<Unit> = try {
        tokenRefreshMutex.withLock {
            val token = SpotifyAuth.fetchAccessToken(spDc, "").getOrThrow()
            Spotify.accessToken = token.accessToken
            context.dataStore.edit { p ->
                p[SpotifyAccessTokenKey] = token.accessToken
                p[SpotifyAccessTokenExpiresAtKey] = token.accessTokenExpirationTimestampMs
            }
            Spotify.me().onSuccess { user ->
                context.dataStore.edit { it[SpotifyAccountNameKey] = user.displayName.orEmpty() }
            }
            Result.success(Unit)
        }
    } catch (e: Exception) { Result.failure(e) }
}
