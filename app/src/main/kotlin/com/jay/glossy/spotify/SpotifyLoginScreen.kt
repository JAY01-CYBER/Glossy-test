package com.jay.glossy.spotify

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.jay.glossy.LocalDatabase
import com.jay.glossy.LocalPlayerAwareWindowInsets
import com.jay.glossy.R
import com.jay.glossy.ui.component.IconButton
import com.jay.glossy.ui.utils.backToMain
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    
    var cookie by remember { mutableStateOf<String?>(null) }
    var checkingCookie by remember { mutableStateOf(true) }
    
    // Naye variables ArchiveTune jaisa data dikhane ke liye
    var userName by remember { mutableStateOf<String?>(null) }
    var myPlaylists by remember { mutableStateOf<List<SpotifyPlaylistImporter.SpotifySimplePlaylist>>(emptyList()) }
    var isFetchingData by remember { mutableStateOf(false) }
    
    var importingPlaylistId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cookie = SpotifySession.cookie(context).takeIf(String::isNotBlank)
        checkingCookie = false
    }
    
    // Jaise hi login ho, user ki profile aur playlists fetch kar lo
    LaunchedEffect(cookie) {
        if (cookie != null) {
            isFetchingData = true
            runCatching {
                SpotifyPlaylistImporter.fetchMyProfileAndPlaylists(context)
            }.onSuccess { (name, lists) ->
                userName = name
                myPlaylists = lists
            }.onFailure { 
                message = "Failed to load playlists"
            }
            isFetchingData = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            ),
    ) {
        TopAppBar(
            title = { Text(if (cookie == null) "Spotify Login" else "Spotify") },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )

        if (checkingCookie) {
            Spacer(modifier = Modifier.weight(1f))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.weight(1f))
        } else if (cookie == null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webContext ->
                    WebView(webContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = WEB_SPOTIFY_USER_AGENT
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                val value = spDcFrom(
                                    CookieManager.getInstance().getCookie("https://open.spotify.com")
                                )
                                if (value.isNotBlank() && value != cookie) {
                                    scope.launch {
                                        SpotifySession.saveCookie(webContext, value)
                                        cookie = value
                                    }
                                }
                            }
                        }
                        loadUrl(WEB_SPOTIFY_LOGIN_URL)
                    }
                }
            )
        } else {
            // Connected UI - ArchiveTune jaisa
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                
                if (isFetchingData) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("Loading your library...", modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    // Profile Info
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Connected as ${userName ?: "User"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${myPlaylists.size} playlists available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }

                    // Playlists List
                    Text("Your Playlists", style = MaterialTheme.typography.titleMedium)
                    
                    myPlaylists.forEach { playlist ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${playlist.tracks} tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            
                            Button(
                                enabled = importingPlaylistId == null && playlist.tracks > 0,
                                onClick = {
                                    importingPlaylistId = playlist.id
                                    message = "Importing ${playlist.name}..."
                                    scope.launch {
                                        message = runCatching {
                                            SpotifyPlaylistImporter.importPlaylist(
                                                context = context,
                                                database = database,
                                                playlistId = playlist.id,
                                                playlistName = playlist.name,
                                            )
                                        }.fold({ "Imported $it songs to your library!" }, { "Import failed: ${it.message}" })
                                        importingPlaylistId = null
                                    }
                                }
                            ) {
                                if (importingPlaylistId == playlist.id) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Import")
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            SpotifySession.clear(context)
                            cookie = null
                            userName = null
                            myPlaylists = emptyList()
                            CookieManager.getInstance().removeAllCookies(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log out") }
                
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun spDcFrom(cookieHeader: String?): String =
    cookieHeader.orEmpty()
        .split(';')
        .firstOrNull { it.trim().startsWith("sp_dc=") }
        ?.substringAfter('=')
        .orEmpty()
        .trim()

private const val WEB_SPOTIFY_LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
private const val WEB_SPOTIFY_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
