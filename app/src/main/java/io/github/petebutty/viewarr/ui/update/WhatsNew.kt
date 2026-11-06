package io.github.petebutty.viewarr.ui.update

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.BuildConfig
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame

/**
 * "What's new": once, the first time an updated Viewarr opens. A few key points (assets/whats_new.txt), not the
 * full release notes. A fresh install just remembers its version and shows nothing.
 */
@Composable
fun WhatsNewHost() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("whats_new", Context.MODE_PRIVATE)
        val seen = prefs.getString("seen_version", null)
        val current = BuildConfig.VERSION_NAME
        if (seen != current) {
            // Updated from an older version (a fresh install has no record, and the setup is the welcome).
            show = seen != null || prefs.getBoolean("legacy_check", false).not() && hadEarlierInstall(context)
            prefs.edit().putString("seen_version", current).putBoolean("legacy_check", true).apply()
        }
    }
    if (!show) return
    val (heading, points) = remember { read(context) }
    if (points.isEmpty()) return
    val ok = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { ok.requestFocus() } }
    androidx.activity.compose.BackHandler { show = false }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp).trapFocus(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("What's new in Viewarr ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = AppColors.Accent)
            Text(heading, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 4.dp))
            points.forEach { p ->
                Row {
                    Text("•", fontSize = 14.sp, color = AppColors.Accent, modifier = Modifier.width(18.dp))
                    Text(p, fontSize = 14.sp, lineHeight = 19.sp, color = AppColors.TextPrimary)
                }
            }
            Box(Modifier.padding(top = 8.dp)) {
                MenuItem(text = "Got it", selected = false, onClick = { show = false }, modifier = Modifier.focusRequester(ok))
            }
        }
    }
}

/** Installs from before this card existed have no record: count them as updates if the app was set up before. */
private fun hadEarlierInstall(context: Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.lastUpdateTime > info.firstInstallTime
}.getOrDefault(false)

private fun read(context: Context): Pair<String, List<String>> = runCatching {
    val lines = context.assets.open("whats_new.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val heading = lines.firstOrNull { !it.startsWith("- ") } ?: "What's new"
    heading to lines.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }.take(6)
}.getOrDefault("What's new" to emptyList())
