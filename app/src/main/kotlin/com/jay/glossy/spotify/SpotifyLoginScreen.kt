package com.jay.glossy.spotify

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var showTokenLogin by rememberSaveable { mutableStateOf(false) }
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var tokenStatus by remember { mutableStateOf<String?>(null) }
    var checkingToken by remember { mutableStateOf(false) }
    var playlistUrl by rememberSaveable { mutableStateOf("") }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cookie = SpotifySession.cookie(context).takeIf(String::isNotBlank)
        checkingCookie = false
        // Not logged in? Start the page load now so it's ready by the time
        // the WebView below composes (and warm even earlier from the parent
        // screen — see IntegrationScreen).
        if (cookie == null) SpotifyLoginPrewarm.warm(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Reserve space for the floating TopAppBar above.
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )

        if (checkingCookie) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else if (cookie == null) {
            Text(
                "Sign in to Spotify to use Canvas and import playlists",
                style = MaterialTheme.typography.bodyLarge,
            )
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { webContext ->
                    // Adopt the prewarmed WebView when we have one — the login
                    // page is already fetching while the user tapped through;
                    // otherwise build a fresh one (it starts its own load).
                    (SpotifyLoginPrewarm.take() ?: newSpotifyLoginWebView(webContext)).apply {
                        fun saveCookieIfEarned() {
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
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                saveCookieIfEarned()
                            }
                        }
                        // A parked/prewarmed page may already sit past the
                        // login redirect — check immediately instead of
                        // waiting for a navigation that may never come.
                        saveCookieIfEarned()
                    }
                },
                onRelease = { view ->
                    // Keep the loaded page alive for the next visit instead
                    // of paying the full page load again.
                    SpotifyLoginPrewarm.park(view)
                },
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SPOTIFY_LOGIN_URL)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Trouble? Log in via browser instead") }
            TextButton(
                onClick = { showTokenLogin = !showTokenLogin },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(if (showTokenLogin) "Hide sp_dc token login" else "Have an sp_dc token? Paste it instead")
            }
            if (showTokenLogin) {
                TokenLoginCard(
                    tokenInput = tokenInput,
                    onTokenInputChange = { tokenInput = it },
                    checking = checkingToken,
                    status = tokenStatus,
                    onSave = {
                        checkingToken = true
                        tokenStatus = null
                        scope.launch {
                            tokenStatus = SpotifySession.saveAndValidateToken(context, tokenInput.trim())
                            if (tokenStatus == "Spotify connected") cookie = SpotifySession.cookie(context)
                            checkingToken = false
                        }
                    },
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                        }.fold({ "Imported $it songs" }, { "Import failed: ${it.message ?: "Unknown error"}" })
                        isImporting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { if (isImporting) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Import playlist") }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showTokenLogin = !showTokenLogin },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(if (showTokenLogin) "Hide sp_dc token login" else "Have an sp_dc token? Paste it instead")
            }
            if (showTokenLogin) {
                TokenLoginCard(
                    tokenInput = tokenInput,
                    onTokenInputChange = { tokenInput = it },
                    checking = checkingToken,
                    status = tokenStatus,
                    onSave = {
                        checkingToken = true
                        tokenStatus = null
                        scope.launch {
                            tokenStatus = SpotifySession.saveAndValidateToken(context, tokenInput.trim())
                            if (tokenStatus == "Spotify connected") cookie = SpotifySession.cookie(context)
                            checkingToken = false
                        }
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        SpotifySession.clear(context)
                        cookie = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect Spotify") }
            }
        }
    }

    TopAppBar(
        title = { Text("Spotify") },
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
}

/** Token entry: paste the sp_dc cookie value and validate it. */
@Composable
private fun TokenLoginCard(
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    checking: Boolean,
    status: String?,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = tokenInput,
            onValueChange = onTokenInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("sp_dc token") },
            placeholder = { Text("Paste your sp_dc cookie value") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                enabled = !checking && tokenInput.trim().length > 20,
                onClick = onSave,
            ) { if (checking) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Check & log in") }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it == "Spotify connected") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
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

private const val LOGIN_URL = SPOTIFY_LOGIN_URL
