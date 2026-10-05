package com.vibe.music.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibe.music.remote.AlbumRef
import com.vibe.music.remote.ArtistRef
import com.vibe.music.ui.common.Artwork

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Error(val message: String) : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
}

@Composable
fun AlbumCard(album: AlbumRef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(148.dp).clickable(onClick = onClick)) {
        Artwork(album.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(8.dp), Icons.Rounded.Album)
        Spacer(Modifier.height(8.dp))
        Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        album.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ArtistAvatar(artist: ArtistRef, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 120) {
    Column(
        modifier.width(size.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artist.artworkUrl, Modifier.size(size.dp), CircleShape, Icons.Rounded.Person)
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text("Interpret", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Horní lišta, která se při scrollu zabarví a ukáže název. */
@Composable
fun CollapsingTopBar(title: String, color: Color, alpha: Float, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = alpha))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            // Tmavý kruh pod šipkou, aby byla vidět i na světlé fotce
            Box(
                Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.35f * (1 - alpha)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zpět", tint = Color.White)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(alpha).weight(1f),
        )
    }
}

@Composable
fun LoadingOrError(state: LoadState<*>, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CollapsingTopBar("", Color.Transparent, 0f, onBack)
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (state) {
                is LoadState.Error -> {
                    Text("Nepodařilo se načíst", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("Zkusit znovu") }
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}
