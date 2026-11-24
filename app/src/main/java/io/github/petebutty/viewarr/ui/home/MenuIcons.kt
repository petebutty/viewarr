package io.github.petebutty.viewarr.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.media.Discover
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * The main menu's icons (2026-11-24): one beside each entry's name. (Folding the main list to just its icons beside an
 * entry's own list was tried and taken out: the menu moving about.) Streaming services show their own logo, the one their Android home screen
 * row has (CatalogRepository.serviceLogo, from TMDB), else their first letter.
 */
internal object MenuIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White)).build()
    private val Trending by lazy { icon("trending", "M13.5,0.67s0.74,2.65 0.74,4.8c0,2.06 -1.35,3.73 -3.41,3.73 -2.07,0 -3.63,-1.67 -3.63,-3.73l0.03,-0.36C5.21,7.51 4,10.62 4,14c0,4.42 3.58,8 8,8s8,-3.58 8,-8C20,8.61 17.41,3.8 13.5,0.67zM11.71,19c-1.78,0 -3.22,-1.4 -3.22,-3.14 0,-1.62 1.05,-2.76 2.81,-3.12 1.77,-0.36 3.6,-1.21 4.62,-2.58 0.39,1.29 0.59,2.65 0.59,4.04 0,2.65 -2.15,4.8 -4.8,4.8z") }
    private val New by lazy { icon("new", "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12L11.5,9.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z") }
    private val Sports by lazy { icon("sports", "M19,5h-2V3H7v2H5C3.9,5 3,5.9 3,7v1c0,2.55 1.92,4.63 4.39,4.94c0.63,1.5 1.98,2.63 3.61,2.96V19H7v2h10v-2h-4v-3.1c1.63,-0.33 2.98,-1.46 3.61,-2.96C19.08,12.63 21,10.55 21,8V7C21,5.9 20.1,5 19,5zM5,8V7h2v3.82C5.84,10.4 5,9.3 5,8zM19,8c0,1.3 -0.84,2.4 -2,2.82V7h2V8z") }

    /** A section's or main entry's icon ("Tv", "Movies", "Sports", "Trending"…); null for a service. */
    fun vector(id: String): ImageVector? = when (id) {
        Section.Tv.name -> AppIcons.LiveTv; Section.Movies.name -> AppIcons.Movie; Section.Shows.name -> AppIcons.Tv
        Section.Library.name -> AppIcons.Library; Section.Music.name -> AppIcons.MusicNote; Section.Recordings.name -> AppIcons.Recordings
        Section.Search.name -> AppIcons.Search; Section.Settings.name -> AppIcons.Settings
        "Sports" -> Sports; Discover.TRENDING_ENTRY -> Trending; Discover.NEW_ENTRY -> New
        else -> null
    }

    /** An entry's icon at [size], in the row's own colour (white, or dark on the highlight); a service's logo in colour. */
    @Composable
    fun Entry(id: String, vm: MediaViewModel, size: Dp = 20.dp, tint: Color = LocalContentColor.current) {
        val v = vector(id)
        if (v != null) { androidx.compose.material3.Icon(v, null, modifier = Modifier.size(size), tint = tint); return }
        val name = id.removePrefix("svc:")
        val logo by produceState<Pair<String, Boolean>?>(null, name) { value = runCatching { vm.serviceLogo(name) }.getOrNull() }
        val l = logo
        if (l != null) {
            // An app icon fills its tile; a network's see-through logo sits on a light one (as on the home screen).
            Box(Modifier.size(size + 2.dp).clip(RoundedCornerShape(5.dp)).background(if (l.second) Color.Transparent else Color(0xFFF2F3F5)),
                contentAlignment = Alignment.Center) {
                AsyncImage(l.first, null, contentScale = ContentScale.Fit, modifier = Modifier.size(if (l.second) size + 2.dp else size - 2.dp))
            }
        } else Box(Modifier.size(size + 2.dp).background(tint.copy(alpha = 0.18f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
        }
    }
}
