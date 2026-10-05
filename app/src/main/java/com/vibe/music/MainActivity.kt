package com.vibe.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.app.SearchManager
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.music.data.Settings
import com.vibe.music.ui.VibeRoot
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.TrackActions
import com.vibe.music.ui.common.importVibeFile
import com.vibe.music.ui.theme.VibeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        val container = (application as VibeApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
            val shared by sharedText.collectAsStateWithLifecycle()

            // Oprávnění pro notifikaci stahování (Android 13+)
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            VibeTheme(dynamicColor = settings.dynamicColor) {
                CompositionLocalProvider(LocalContainer provides container) {
                    VibeRoot(sharedText = shared, onSharedConsumed = { sharedText.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Hlasové hledání („pusť … na Vibe“) → rádio k první nalezené skladbě
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
            val c = (application as VibeApp).container
            lifecycleScope.launch {
                val found = runCatching { c.ytMusic.searchSongs(query).firstOrNull()?.toEntity() }.getOrNull()
                if (found != null) TrackActions(c).startRadio(found)
                else if (query.isBlank()) c.player.togglePlay()
            }
            return
        }
        // Soubor Vibe (sdílený playlist / záloha)
        val fileUri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (fileUri != null) {
            val container = (application as VibeApp).container
            lifecycleScope.launch { importVibeFile(this@MainActivity, container, fileUri) }
            return
        }
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it }
        }
    }
}
