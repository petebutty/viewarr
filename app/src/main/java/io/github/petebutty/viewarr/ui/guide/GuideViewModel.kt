package io.github.petebutty.viewarr.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** row = selected channel index; focusTime = time anchor (TiviMate keeps the column time when moving up/down). */
data class GuideCursor(val row: Int, val focusTime: Long, val windowStart: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val repo: GuideRepository,
    private val sessions: SessionStore,
) : ViewModel() {

    val sync = repo.sync
    val groups = repo.observeGroups().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _groupId = MutableStateFlow<Int?>(null)
    val groupId = _groupId.asStateFlow()

    val channels: StateFlow<List<ChannelListItem>> = _groupId
        .flatMapLatest { repo.observeChannels(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now = _now.asStateFlow()

    private val _cursor = MutableStateFlow(initialCursor(_now.value))
    val cursor = _cursor.asStateFlow()

    val programs: StateFlow<Map<Int, List<GuideProgram>>> = combine(
        _groupId,
        _cursor.map { it.row / BLOCK }.distinctUntilChanged(),
        _cursor.map { it.windowStart }.distinctUntilChanged(),
    ) { g, block, ws -> Triple(g, block, ws) }
        .flatMapLatest { (g, block, ws) ->
            repo.observeProgramBlock(
                groupId = g,
                offset = blockOffset(block),
                count = BLOCK * 3,
                start = ws - BUFFER_MS,
                end = ws + VISIBLE_MS + BUFFER_MS,
            ).map { rows -> rows.groupBy { it.channelId } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val fetchedPast = HashSet<String>()

    init {
        viewModelScope.launch {
            sessions.session.filterNotNull().distinctUntilChanged().collect { repo.syncIfStale(it) }
        }
        viewModelScope.launch {
            repo.generation.collect {
                fetchedPast.clear()
                ensurePast()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val previous = _now.value
                val t = System.currentTimeMillis()
                _now.value = t
                // Follow live TV only if the user was looking at "now"; leave past/future browsing alone.
                _cursor.update { c ->
                    if (c.focusTime in previous..t) c.copy(focusTime = t, windowStart = maxOf(c.windowStart, floorToStep(t - STEP_MS)))
                    else c
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { sessions.session.first()?.let { repo.sync(it) } }
    }

    fun selectGroup(id: Int?) {
        _groupId.value = id
        _cursor.value = initialCursor(System.currentTimeMillis())
    }

    fun moveVertical(delta: Int) {
        val count = channels.value.size
        if (count == 0) return
        _cursor.update { it.copy(row = (it.row + delta).coerceIn(0, count - 1)) }
        ensurePast()
    }

    fun moveRight() {
        val c = _cursor.value
        val list = rowPrograms(c.row)
        val current = programAt(list, c.focusTime)
        val next = if (current != null) list.firstOrNull { it.startTime >= current.endTime }
        else list.firstOrNull { it.startTime > c.focusTime }
        val target = next?.startTime ?: (c.focusTime + STEP_MS)
        if (target > _now.value + MAX_FORWARD_MS) return
        var ws = c.windowStart
        while (target >= ws + VISIBLE_MS - STEP_MS) ws += STEP_MS
        _cursor.value = c.copy(focusTime = target, windowStart = ws)
    }

    fun moveLeft() {
        val c = _cursor.value
        val list = rowPrograms(c.row)
        val current = programAt(list, c.focusTime)
        val prev = if (current != null) list.lastOrNull { it.endTime <= current.startTime }
        else list.lastOrNull { it.endTime <= c.focusTime }
        val earliest = _now.value - MAX_PAST_MS
        val target = maxOf(prev?.startTime ?: (c.focusTime - STEP_MS), earliest)
        if (target >= c.focusTime) return
        var ws = c.windowStart
        while (target < ws) ws -= STEP_MS
        _cursor.value = c.copy(focusTime = target, windowStart = ws)
        ensurePast()
    }

    fun isAtNow(): Boolean {
        val c = _cursor.value
        return c.focusTime in _now.value - 60_000.._now.value + 60_000 || programAt(rowPrograms(c.row), c.focusTime)?.let {
            it.startTime <= _now.value && it.endTime > _now.value
        } == true
    }

    /** Moves the guide to a channel (used when zapping in the player), at live time. */
    fun selectChannel(channelId: Int) {
        val index = channels.value.indexOfFirst { it.id == channelId }
        if (index < 0) return
        val t = System.currentTimeMillis()
        _cursor.value = GuideCursor(row = index, focusTime = t, windowStart = floorToStep(t))
    }

    /**
     * Finds a channel by its number (typed on the remote). Looks in the current category first, then in all
     * channels; if it's in another category the guide switches to All channels so it can be shown.
     */
    fun findByNumber(number: Int, onResult: (ChannelListItem?) -> Unit) {
        viewModelScope.launch {
            val wanted = number.toDouble()
            channels.value.firstOrNull { it.number == wanted }?.let { return@launch onResult(it) }
            val hit = repo.observeChannels(null).first().firstOrNull { it.number == wanted }
            if (hit != null && _groupId.value != null) {
                _groupId.value = null
                channels.first { list -> list.any { it.id == hit.id } }
            }
            onResult(hit)
        }
    }

    suspend fun channelById(id: Int): ChannelListItem? = repo.observeChannels(null).first().firstOrNull { it.id == id }

    /** Channels whose name or number matches a search, across all categories. */
    suspend fun searchChannels(q: String): List<ChannelListItem> {
        val t = q.trim()
        if (t.length < 2) return emptyList()
        return repo.observeChannels(null).first().filter { c ->
            c.name.contains(t, ignoreCase = true) || c.number?.let { n -> (if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()) == t } == true
        }.take(30)
    }

    /** Makes a channel tunable from anywhere: switches to All channels if it isn't in the current category. */
    fun reveal(channel: ChannelListItem, onReady: () -> Unit) {
        viewModelScope.launch {
            if (channels.value.none { it.id == channel.id }) {
                _groupId.value = null
                channels.first { list -> list.any { it.id == channel.id } }
            }
            onReady()
        }
    }

    /** Jump back to live TV on the current channel. */
    fun goToNow() {
        val t = System.currentTimeMillis()
        _cursor.update { it.copy(focusTime = t, windowStart = floorToStep(t)) }
    }

    private fun ensurePast() {
        val c = _cursor.value
        val syncedFrom = repo.syncedFrom()
        val wantFrom = c.windowStart - BUFFER_MS
        if (wantFrom >= syncedFrom) return
        viewModelScope.launch {
            val s = sessions.session.first() ?: return@launch
            val all = channels.value
            val from = maxOf(0, c.row - BLOCK)
            val keys = all.subList(from, minOf(all.size, from + BLOCK * 3))
                .filter { it.guideKey != it.uuid }
                .map { it.guideKey }
                .toSet()
            var chunk = Math.floorDiv(wantFrom, CHUNK_MS) * CHUNK_MS
            while (chunk < syncedFrom) {
                val chunkStart = chunk
                for (key in keys) {
                    if (!fetchedPast.add("$key|$chunkStart")) continue
                    launch { repo.loadPastChunk(s, key, chunkStart, chunkStart + CHUNK_MS) }
                }
                chunk += CHUNK_MS
            }
        }
    }

    private fun rowPrograms(row: Int): List<GuideProgram> {
        val channel = channels.value.getOrNull(row) ?: return emptyList()
        return programs.value[channel.id].orEmpty()
    }

    companion object {
        const val STEP_MS = 30 * 60_000L
        const val VISIBLE_MS = 150 * 60_000L
        private const val BUFFER_MS = 90 * 60_000L
        private const val MAX_FORWARD_MS = 34 * 3_600_000L
        private const val MAX_PAST_MS = 3 * 24 * 3_600_000L
        private const val CHUNK_MS = 6 * 3_600_000L
        private const val BLOCK = 10

        fun floorToStep(t: Long) = t - Math.floorMod(t, STEP_MS)

        private fun blockOffset(block: Int) = maxOf(0, (block - 1) * BLOCK)

        private fun initialCursor(now: Long) = GuideCursor(row = 0, focusTime = now, windowStart = floorToStep(now))

        fun programAt(list: List<GuideProgram>, time: Long): GuideProgram? =
            list.firstOrNull { it.startTime <= time && it.endTime > time }
    }
}
