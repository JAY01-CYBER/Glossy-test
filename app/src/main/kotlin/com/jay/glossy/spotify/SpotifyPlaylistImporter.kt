package com.jay.glossy.spotify

import android.content.Context
import com.jay.glossy.db.MusicDatabase
import com.jay.glossy.db.entities.PlaylistEntity
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.YouTube.SearchFilter
import com.metrolist.innertube.models.SongItem
import com.metrolist.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

object SpotifyPlaylistImporter {
    private val json = Json { ignoreUnknownKeys = true }
    private const val RETRY_ATTEMPTS = 4
    private const val MAX_CONCURRENT_RESOLUTIONS = 8 // ArchiveTune jaisa concurrent batching

    suspend fun importPlaylist(
        context: Context,
        database: MusicDatabase,
        playlistUrl: String,
        playlistName: String,
    ): Int = withContext(Dispatchers.IO) {
        val playlistId = Regex("playlist[/:]([A-Za-z0-9]+)").find(playlistUrl)?.groupValues?.get(1)
            ?: error("Enter a Spotify playlist URL")
        val token = SpotifySession.token(context) ?: error("Spotify session expired")
        val tracks = fetchTracks(playlistId, token)
        if (tracks.isEmpty()) error("Playlist has no playable tracks")

        val entity = PlaylistEntity(name = playlistName)
        database.transaction { insert(entity) }
        val localPlaylist = database.playlist(entity.id).firstOrNull() ?: error("Could not create local playlist")
        
        // Concurrent fetching for lightning speed
        val songIds = mutableListOf<String>()
        tracks.chunked(MAX_CONCURRENT_RESOLUTIONS).forEach { chunk ->
            val resolvedChunk = coroutineScope {
                chunk.map { track ->
                    async {
                        YouTube.search("${track.title} ${track.artist}", SearchFilter.FILTER_SONG).getOrNull()?.items
                            ?.filterIsInstance<SongItem>()?.firstOrNull()?.let { song ->
                                val metadata = song.toMediaMetadata()
                                database.transaction { insert(metadata) }
                                metadata.id
                            }
                    }
                }.awaitAll().filterNotNull()
            }
            songIds.addAll(resolvedChunk)
        }
        
        val pairList = songIds.map { it to null }
        database.addSongsToPlaylist(localPlaylist, pairList)
        songIds.size
    }

    private suspend fun fetchTracks(playlistId: String, token: SpotifySession.Token): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var next: String? = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=100"
        while (next != null) {
            val root = fetchWithRetry(next!!, token)
            tracks += root["items"]?.jsonArray.orEmpty().mapNotNull { item ->
                val track = item.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
                val title = track["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val artist = track["artists"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SpotifyTrack(title, artist)
            }
            next = root["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        return tracks
    }

    private suspend fun fetchWithRetry(url: String, token: SpotifySession.Token): kotlinx.serialization.json.JsonObject {
        var backoffMs = 1_000L
        repeat(RETRY_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(backoffMs)
                backoffMs *= 2
            }
            val clientToken = SpotifySession.clientToken()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("Authorization", "Bearer ${token.accessToken}")
                clientToken?.let { connection.setRequestProperty("Client-Token", it) }
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                val code = connection.responseCode
                if (code in 200..299) {
                    return json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
                }
                if (code != 429) error("Spotify returned HTTP $code")
            } finally {
                connection.disconnect()
            }
        }
        error("Spotify rate limited the request — try again in a minute")
    }

    private data class SpotifyTrack(val title: String, val artist: String)
}
