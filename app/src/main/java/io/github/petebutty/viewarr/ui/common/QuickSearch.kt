package io.github.petebutty.viewarr.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/** True while search is popped up over the current screen: players underneath leave the remote to it. */
val LocalQuickSearchOpen = staticCompositionLocalOf { false }
