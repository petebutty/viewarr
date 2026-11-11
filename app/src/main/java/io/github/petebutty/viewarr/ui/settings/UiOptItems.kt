package io.github.petebutty.viewarr.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Saves the small display / behaviour settings (UiOpts). */
@HiltViewModel
class UiOptsViewModel @Inject constructor(private val store: ThemeStore) : ViewModel() {
    fun set(name: String, value: Int) { viewModelScope.launch { store.saveOpt(name, value) } }
    fun reset(names: Collection<String>) { viewModelScope.launch { store.resetOpts(names) } }
}

private class OptChoice(override val label: String, val index: Int) : RemoteChoice {
    override fun equals(other: Any?) = other is OptChoice && other.index == index
    override fun hashCode() = index
}

/** A settings row for one UiOpts setting. */
fun optItem(vm: UiOptsViewModel, title: String, description: String, name: String): SettingItem {
    val all = UiOpts.choices.getValue(name).mapIndexed { i, l -> OptChoice(l, i) }
    val now = AppColors.palette.opt(name).coerceIn(0, all.lastIndex)
    return SettingItem(title, description, all, all[now]) { c -> vm.set(name, (c as OptChoice).index) }
}

/** A UiOpts setting as a slider (Left / Right step through its values), e.g. the next episode countdown. */
fun optSlider(vm: UiOptsViewModel, title: String, description: String, name: String, now: Int): SettingItem {
    val all = UiOpts.choices.getValue(name).mapIndexed { i, l -> OptChoice(l, i) }
    return SettingItem(title, description, all, all[now.coerceIn(0, all.lastIndex)], slider = true) { c -> vm.set(name, (c as OptChoice).index) }
}
