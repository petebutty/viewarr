package io.github.petebutty.viewarr.ui.dvr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.media.CatalogRepository
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.remote.services.SeerrService
import io.github.petebutty.viewarr.data.remote.services.SeerrService.Status
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A guide programme matched to a film or show, with what Seerr already has of it. */
data class GuideMatch(val item: MediaItem, val status: Status, val seasons: List<SeerrService.Season>, val programmeSeason: Int?)

/** Requesting what's in the guide on Seerr (long-press a programme): the film, or a show / season / seasons. */
@HiltViewModel
class GuideRequestViewModel @Inject constructor(
    private val store: AccountStore,
    private val repo: CatalogRepository,
    private val seerr: SeerrService,
) : ViewModel() {
    private val _match = MutableStateFlow<GuideMatch?>(null)
    val match: StateFlow<GuideMatch?> = _match
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private var job: Job? = null

    fun load(p: GuideProgram) {
        job?.cancel()
        _match.value = null
        _message.value = null
        job = viewModelScope.launch {
            if (!io.github.petebutty.viewarr.data.profiles.Access.can(io.github.petebutty.viewarr.data.profiles.Lock.SEERR)) return@launch
            val a = store.current().seerr ?: return@launch
            // Films in the guide usually end their description with the year, e.g. "(2014)".
            // The year as guides write it: "(2014)", or "Thriller · 2024 — …" (the first year-like number).
            val year = p.description?.let { d ->
                Regex("\\((19|20)\\d{2}\\)").findAll(d).lastOrNull()?.value?.trim('(', ')')?.toIntOrNull()
                    ?: Regex("\\b(19|20)\\d{2}\\b").find(d)?.value?.toIntOrNull()
            }
            val item = runCatching { repo.guideMatch(p.title, p.season, year) }.getOrNull() ?: return@launch
            val id = item.tmdbId ?: return@launch
            // What's on your own servers counts as there, whatever Seerr knows (its library scan can miss things).
            val status = if (item.type == MediaType.Movie && repo.onOwnServer(item)) Status.Available
                else runCatching { seerr.status(a, item.type, id) }.getOrDefault(Status.None)
            val seasons = if (item.type != MediaType.Show) emptyList() else {
                val own = runCatching { repo.ownSeasons(item) }.getOrDefault(emptyMap())
                runCatching { seerr.seasons(a, id) }.getOrDefault(emptyList()).map { s ->
                    val have = own[s.number] ?: 0
                    when {
                        have > 0 && have >= s.episodes -> s.copy(status = Status.Available)
                        have > 0 && s.status == Status.None -> s.copy(status = Status.Partial)
                        else -> s
                    }
                }
            }
            _match.value = GuideMatch(item, status, seasons, p.season)
        }
    }

    fun request(m: GuideMatch, seasons: List<Int>?) = viewModelScope.launch {
        val a = store.current().seerr ?: return@launch
        _message.value = "Asking Seerr…"
        val (_, msg) = seerr.request(a, m.item.type, m.item.tmdbId ?: return@launch, seasons)
        _message.value = msg
    }
}

/** The name as matched, so a wrong match shows before anything is asked for: "Taskmaster (2015)". */
fun GuideMatch.name() = item.title + (item.year?.let { " ($it)" } ?: "")

/** The record menu's one line for Seerr, so the menu stays short. */
const val SEERR_MORE = "Request on Seerr…"

/**
 * The record menu's Seerr line: a film is asked for straight away; a show opens [onChoose] (whole show, this
 * season, or chosen seasons). Nothing when there's nothing to ask for (it's all on your servers or requested).
 */
@Composable
fun guideRequestChoices(p: GuideProgram, onChoose: (GuideMatch) -> Unit, vm: GuideRequestViewModel = hiltViewModel()): Pair<List<Pair<String, () -> Unit>>, String?> {
    LaunchedEffect(p.channelId, p.startTime) { vm.load(p) }
    val m by vm.match.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val match = m ?: return emptyList<Pair<String, () -> Unit>>() to message
    val choices = buildList<Pair<String, () -> Unit>> {
        if (match.item.type == MediaType.Movie) {
            if (match.status.askable) add("Request on Seerr: ${match.name()}" to { vm.request(match, null) })
        } else if (match.seasons.isEmpty() || match.seasons.any { it.status.askable }) {
            add(SEERR_MORE to { onChoose(match) })
        }
    }
    return choices to message
}

private val Status.askable get() = this == Status.None || this == Status.Partial

/** "season 9", "seasons 7, 8 and 9". */
private fun seasonsText(n: List<Int>) = when (n.size) {
    1 -> "season ${n[0]}"
    else -> "seasons " + n.dropLast(1).joinToString(", ") + " and " + n.last()
}

/**
 * Requesting a show from the guide, kept short: what's missing (or the whole show when none of it is on your
 * servers), and the season that's on if that's only part of what's missing.
 */
@Composable
fun SeasonPicker(m: GuideMatch, onClose: () -> Unit, vm: GuideRequestViewModel = hiltViewModel()) {
    val wanted = m.seasons.filter { it.status.askable }.map { it.number }
    val message by vm.message.collectAsStateWithLifecycle()
    var sent by remember { mutableStateOf(false) }
    LaunchedEffect(message, sent) { if (sent && message != null && message != "Asking Seerr…") { kotlinx.coroutines.delay(1_600); onClose() } }
    fun ask(seasons: List<Int>?) { sent = true; vm.request(m, seasons) }
    val some = m.seasons.isNotEmpty() && wanted.size < m.seasons.size
    val here = m.programmeSeason?.takeIf { it in wanted && wanted.size > 1 }
    val choices = buildList<Pair<String, () -> Unit>> {
        if (some) add("Request missing episodes (${seasonsText(wanted)})" to { ask(wanted) })
        else add("Request the whole show" to { ask(null) })
        here?.let { add("Just season $it (the one on now)" to { ask(listOf(it)) }) }
        add("Close" to onClose)
    }
    ChoicePanel(title = m.name(), subtitle = "Request on Seerr", choices = choices, message = message, onClose = onClose)
}
