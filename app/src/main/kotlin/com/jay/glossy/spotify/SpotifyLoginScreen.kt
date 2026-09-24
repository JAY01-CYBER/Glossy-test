package com.jay.glossy.spotify

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun SpotifyLoginScreen(
    navController: NavController,
    viewModel: SpotifyAccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    
    var importingPlaylistId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            ),
    ) {
        TopAppBar(
            title = { Text(if (!uiState.isAuthenticated) "Spotify Login" else "Spotify") },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        if (uiState.isLoading && !uiState.isAuthenticated) {
            Spacer(modifier = Modifier.weight(1f))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.weight(1f))
        } else if (!uiState.isAuthenticated) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webContext ->
                    WebView(webContext).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                val spDc = CookieManager.getInstance().getCookie("https://open.spotify.com")
                                    ?.split(';')?.firstOrNull { it.trim().startsWith("sp_dc=") }
                                    ?.substringAfter('=')?.trim()
                                
                                if (!spDc.isNullOrBlank()) {
                                    viewModel.connectWithCookies(spDc)
                                }
                            }
                        }
                        loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                    }
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Connected as ${uiState.accountName.ifBlank { "User" }}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${playlists.size} playlists available", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

                Text("Your Playlists", style = MaterialTheme.typography.titleMedium)
                
                playlists.forEach { playlist ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${playlist.tracks?.total ?: 0} tracks", style = MaterialTheme.typography.bodySmall)
                        }
                        
                        Button(
                            enabled = importingPlaylistId == null,
                            onClick = {
                                importingPlaylistId = playlist.id
                                message = "Importing ${playlist.name}..."
                                scope.launch {
                                    message = runCatching {
                                        SpotifyPlaylistImporter.importPlaylist(database, playlist.id, playlist.name)
                                    }.fold({ "Imported $it songs!" }, { "Import failed: ${it.message}" })
                                    importingPlaylistId = null
                                }
                            }
                        ) {
                            if (importingPlaylistId == playlist.id) CircularProgressIndicator(Modifier.size(16.dp)) else Text("Import")
                        }
                    }
                }
                
                OutlinedButton(
                    onClick = {
                        CookieManager.getInstance().removeAllCookies(null)
                        viewModel.logout()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log out") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
