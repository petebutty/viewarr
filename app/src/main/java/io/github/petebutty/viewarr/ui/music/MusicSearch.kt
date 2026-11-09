package io.github.petebutty.viewarr.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * Search → Music: artists, albums and songs from every music source. An artist or album opens on the Music page
 * ([onOpen]); a song plays straight away.
 */
@Composable
fun MusicSearchResults(query: String, canFocus: Boolean, onOpen: (MusicCard) -> Unit, vm: MusicViewModel = hiltViewModel()) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val r by vm.search.collectAsStateWithLifecycle()
    LaunchedEffect(query, connected) { vm.search(query) }
    if (connected != true || r.query != query.trim()) return
    if (r.artists.isEmpty() && r.albums.isEmpty() && r.songs.isEmpty()) return
    val free = Modifier.focusProperties { this.canFocus = canFocus }
    Column {
        if (r.artists.isNotEmpty()) {
            Heading("Music · Artists")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(r.artists, key = { _, c -> c.key }) { _, c -> MusicCardView(c, 96.dp, free) { onOpen(c) } }
            }
        }
        if (r.albums.isNotEmpty()) {
            Heading("Music · Albums")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(r.albums, key = { _, c -> c.key }) { _, c -> MusicCardView(c, 96.dp, free) { onOpen(c) } }
            }
        }
        if (r.songs.isNotEmpty()) {
            Heading("Music · Songs")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(r.songs, key = { i, s -> "$i:${s.id}" }) { i, s ->
                    Surface(
                        onClick = { vm.play(r.songs, i) },
                        modifier = free.width(260.dp),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
                            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
                        ),
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(s.art?.small, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${s.artist} · ${s.album}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}
