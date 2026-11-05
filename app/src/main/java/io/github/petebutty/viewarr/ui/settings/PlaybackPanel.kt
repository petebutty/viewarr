package io.github.petebutty.viewarr.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.settings.PlaybackSettings
import io.github.petebutty.viewarr.data.settings.PlaybackStore
import io.github.petebutty.viewarr.data.settings.PlayerEngine
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.data.settings.RemoteStore
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModelSettings @Inject constructor(
    private val store: PlaybackStore,
    private val remoteStore: RemoteStore,
) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings())
    val remote = remoteStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteSettings())

    fun <E> next(key: String, current: E) where E : Enum<E>, E : RemoteChoice {
        val all = current.declaringJavaClass.enumConstants!!.toList()
        viewModelScope.launch { store.set(key, all[(all.indexOf(current) + 1) % all.size]) }
    }

    fun <E> nextRemote(key: String, current: E) where E : Enum<E>, E : RemoteChoice {
        val all = current.declaringJavaClass.enumConstants!!.toList()
        viewModelScope.launch { remoteStore.set(key, all[(all.indexOf(current) + 1) % all.size]) }
    }

    fun reset() = viewModelScope.launch { store.reset() }
}

private class Setting(val section: String?, val title: String, val value: String, val hint: String? = null, val onNext: () -> Unit)

/** Settings → Playback: player, audio, subtitles and quality. OK on a setting moves to its next choice. */
@Composable
fun PlaybackPanel(onBack: () -> Unit, vm: PlaybackViewModelSettings = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val r by vm.remote.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { first.requestFocus() }
    }
    val mpv = s.engine == PlayerEngine.Mpv
    val items = listOf(
        Setting("Player", "Films, shows & recordings", s.engine.label, "mpv handles more formats; built-in uses less power") { vm.next(PlaybackStore.ENGINE, s.engine) },
        Setting(null, "Skip with Left / Right", r.seek.label) { vm.nextRemote(RemoteStore.SEEK, r.seek) },
        Setting(null, "Resume", s.resume.label) { vm.next(PlaybackStore.RESUME, s.resume) },
        Setting("Audio", "Preferred language", s.audioLanguage.label) { vm.next(PlaybackStore.AUDIO_LANG, s.audioLanguage) },
        Setting(null, "Surround passthrough", s.passthrough.label, "Only if you have an AV receiver or soundbar") { vm.next(PlaybackStore.PASSTHROUGH, s.passthrough) },
        Setting(null, "Channels", s.downmix.label) { vm.next(PlaybackStore.DOWNMIX, s.downmix) },
        Setting(null, "Night mode", s.nightMode.label, if (mpv) null else "Works with the mpv player") { vm.next(PlaybackStore.NIGHT, s.nightMode) },
        Setting("Subtitles", "Show subtitles", s.subtitles.label) { vm.next(PlaybackStore.SUBS, s.subtitles) },
        Setting(null, "Subtitle language", s.subtitleLanguage.label) { vm.next(PlaybackStore.SUB_LANG, s.subtitleLanguage) },
        Setting(null, "Subtitle size", s.subtitleSize.label) { vm.next(PlaybackStore.SUB_SIZE, s.subtitleSize) },
        Setting("Quality", "Plex", s.plexQuality.label) { vm.next(PlaybackStore.PLEX_QUALITY, s.plexQuality) },
        Setting(null, "mpv decoding", s.mpvDecoding.label) { vm.next(PlaybackStore.MPV_DECODING, s.mpvDecoding) },
    )
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Playback", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        Text("OK changes a setting. Applies to the next thing you play.", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        items.forEachIndexed { i, o ->
            Column {
                o.section?.let {
                    Text(it, fontSize = 12.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else 10.dp, bottom = 2.dp))
                }
                SettingRow(o, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
        Column(Modifier.padding(top = 12.dp)) { MenuItem(text = "Reset playback settings", selected = false, onClick = { vm.reset() }) }
    }
}

@Composable
private fun SettingRow(o: Setting, modifier: Modifier) {
    Surface(
        onClick = o.onNext,
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(o.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(o.value, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            o.hint?.let { Text(it, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
