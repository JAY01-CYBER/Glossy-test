package com.jay.glossy.spotify

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
    var playlistUrl by rememberSaveable { mutableStateOf("") }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cookie = SpotifySession.cookie(context).takeIf(String::isNotBlank)
        checkingCookie = false
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
            // Clean, full-screen WebView (ArchiveTune Style)
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
                        settings.userAgentString = SPOTIFY_WEBVIEW_USER_AGENT
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                // Background me chupke se token (cookie) nikalna
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
                        
                        loadUrl(SPOTIFY_LOGIN_URL)
                    }
                }
            )
        } else {
            // Connected State UI (Fast Playlist Import)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Spotify connected",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = playlistUrl,
                    onValueChange = { playlistUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Spotify playlist URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Local playlist name") },
                    singleLine = true,
                )
                Button(
                    enabled = !isImporting && playlistUrl.isNotBlank(),
                    onClick = {
                        isImporting = true
                        message = null
                        scope.launch {
                            message = runCatching {
                                SpotifyPlaylistImporter.importPlaylist(
                                    context = context,
                                    database = database,
                                    playlistUrl = playlistUrl,
                                    playlistName = playlistName.ifBlank { "Spotify playlist" },
                                )
                            }.fold({ "Imported $it songs superfast!" }, { "Import failed: ${it.message ?: "Unknown error"}" })
                            isImporting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { 
                    if (isImporting) CircularProgressIndicator(Modifier.size(20.dp)) 
                    else Text("Import playlist") 
                }
                
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                
                Spacer(Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            SpotifySession.clear(context)
                            cookie = null
                            CookieManager.getInstance().removeAllCookies(null) // Clear browser session
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disconnect Spotify") }
            }
        }
    }
}

/** Extract the sp_dc cookie value from a raw Cookie header string. */
private fun spDcFrom(cookieHeader: String?): String =
    cookieHeader.orEmpty()
        .split(';')
        .firstOrNull { it.trim().startsWith("sp_dc=") }
        ?.substringAfter('=')
        .orEmpty()
        .trim()

private const val SPOTIFY_LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
private const val SPOTIFY_WEBVIEW_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
