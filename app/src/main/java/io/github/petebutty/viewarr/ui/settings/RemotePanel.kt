package io.github.petebutty.viewarr.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.data.settings.RemoteStore
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteViewModel @Inject constructor(private val store: RemoteStore) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteSettings())

    /** Steps an option to its next choice (wrapping round). */
    fun <E> next(key: String, current: E, all: List<E>) where E : Enum<E>, E : RemoteChoice {
        val n = all[(all.indexOf(current) + 1) % all.size]
        viewModelScope.launch { store.set(key, n) }
    }

    fun reset() = viewModelScope.launch { store.reset() }
}

private data class Option(val section: String?, val title: String, val value: String, val onNext: () -> Unit)

/** Settings → Remote & navigation. OK on an option moves to its next choice; changes apply straight away. */
@Composable
fun RemotePanel(onBack: () -> Unit, vm: RemoteViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { first.requestFocus() }
    }
    val options = listOf(
        Option("Start", "App opens on", s.startPage.label) { vm.next(RemoteStore.START_PAGE, s.startPage, s.startPage.declaringJavaClass.enumConstants!!.toList()) },
        Option("TV guide", "Left", s.guideLeft.label) { vm.next(RemoteStore.GUIDE_LEFT, s.guideLeft, s.guideLeft.declaringJavaClass.enumConstants!!.toList()) },
        Option(null, "Back", s.guideBack.label) { vm.next(RemoteStore.GUIDE_BACK, s.guideBack, s.guideBack.declaringJavaClass.enumConstants!!.toList()) },
        Option(null, "Channel + / −", s.channelKeys.label) { vm.next(RemoteStore.CHANNEL_KEYS, s.channelKeys, s.channelKeys.declaringJavaClass.enumConstants!!.toList()) },
        Option("Watching live TV", "Up / Down", s.zap.label) { vm.next(RemoteStore.ZAP, s.zap, s.zap.declaringJavaClass.enumConstants!!.toList()) },
        Option(null, "Left / Right", s.seek.label) { vm.next(RemoteStore.SEEK, s.seek, s.seek.declaringJavaClass.enumConstants!!.toList()) },
        Option(null, "OK", s.playerOk.label) { vm.next(RemoteStore.PLAYER_OK, s.playerOk, s.playerOk.declaringJavaClass.enumConstants!!.toList()) },
        Option("Movies & Shows", "Moving between rows", s.rowMemory.label) { vm.next(RemoteStore.ROW_MEMORY, s.rowMemory, s.rowMemory.declaringJavaClass.enumConstants!!.toList()) },
        Option(null, "Poster position", s.rowAlign.label) { vm.next(RemoteStore.ROW_ALIGN, s.rowAlign, s.rowAlign.declaringJavaClass.enumConstants!!.toList()) },
        Option(null, "Left at the start of a row", s.rowLeft.label) { vm.next(RemoteStore.ROW_LEFT, s.rowLeft, s.rowLeft.declaringJavaClass.enumConstants!!.toList()) },
        Option(null, "Back", s.mediaBack.label) { vm.next(RemoteStore.MEDIA_BACK, s.mediaBack, s.mediaBack.declaringJavaClass.enumConstants!!.toList()) },
        Option("Menu", "Menu opens on", s.menuOpensOn.label) { vm.next(RemoteStore.MENU_OPENS_ON, s.menuOpensOn, s.menuOpensOn.declaringJavaClass.enumConstants!!.toList()) },
        Option(null, "Back in the menu", s.menuBack.label) { vm.next(RemoteStore.MENU_BACK, s.menuBack, s.menuBack.declaringJavaClass.enumConstants!!.toList()) },
    )
    // A plain column (not lazy) so the first option exists in the same frame and can take focus straight away;
    // otherwise focus falls back to the side menu and the panel closes.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        run {
            Text("Remote & navigation", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
            Text("OK changes an option", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        options.forEachIndexed { i, o ->
            run {
                Column {
                    o.section?.let {
                        Text(it, fontSize = 12.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else 10.dp, bottom = 2.dp))
                    }
                    OptionRow(o.title, o.value, if (i == 0) Modifier.focusRequester(first) else Modifier, o.onNext)
                }
            }
        }
        run {
            Column(Modifier.padding(top = 12.dp)) { MenuItem(text = "Reset to defaults", selected = false, onClick = { vm.reset() }) }
        }
    }
}

@Composable
private fun OptionRow(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
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
            Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
