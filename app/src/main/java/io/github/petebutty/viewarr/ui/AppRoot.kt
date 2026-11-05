package io.github.petebutty.viewarr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.repository.AuthRepository
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.ui.home.HomeScreen
import io.github.petebutty.viewarr.ui.setup.SetupWizard
import io.github.petebutty.viewarr.ui.theme.AppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StartState {
    data object Loading : StartState
    data object NeedsSetup : StartState
    /** session is null when Live TV was skipped in setup. */
    data class Ready(val session: Session?) : StartState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    sessions: SessionStore,
    private val accounts: AccountStore,
    private val auth: AuthRepository,
    private val guide: GuideRepository,
    remoteStore: io.github.petebutty.viewarr.data.settings.RemoteStore,
    playbackStore: io.github.petebutty.viewarr.data.settings.PlaybackStore,
) : ViewModel() {
    val playback = playbackStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, io.github.petebutty.viewarr.data.settings.PlaybackSettings())
    val remote = remoteStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, io.github.petebutty.viewarr.data.settings.RemoteSettings())

    // Installs from before optional setup steps existed have no flag: a saved session means they're set up.
    val state = combine(sessions.session, accounts.setupComplete) { session, done ->
        when {
            done == false -> StartState.NeedsSetup
            done == null && session == null -> StartState.NeedsSetup
            else -> StartState.Ready(session)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StartState.Loading)

    /** Signs out of everything and returns to setup. */
    fun signOut() {
        viewModelScope.launch {
            guide.clear()
            accounts.clear()
            auth.signOut()
            accounts.setSetupComplete(false)
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val playbackSettings by vm.playback.collectAsStateWithLifecycle()
    // Show the splash for at least ~1.6 s (and until the start state is known).
    var splashDone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_600)
        splashDone = true
    }
    androidx.compose.runtime.CompositionLocalProvider(
        io.github.petebutty.viewarr.data.settings.LocalRemote provides remote,
        io.github.petebutty.viewarr.data.settings.LocalPlayback provides playbackSettings,
    ) {
    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        if (!splashDone || state == StartState.Loading) {
            io.github.petebutty.viewarr.ui.common.ViewarrSplash()
        } else when (val s = state) {
            StartState.Loading -> Unit
            StartState.NeedsSetup -> SetupWizard()
            is StartState.Ready -> HomeScreen(session = s.session, onSignOut = vm::signOut)
        }
    }
}
}
