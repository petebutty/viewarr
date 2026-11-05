package io.github.petebutty.viewarr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.ui.AppRoot
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.ViewarrTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var themeStore: ThemeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { themeStore.palette.collect { AppColors.palette = it } }
        setContent {
            ViewarrTheme { AppRoot() }
        }
    }
}
