package io.github.petebutty.viewarr.ui.common

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager

/**
 * Compose keeps a full description of the screen up to date for screen readers whenever ANY accessibility service is
 * on (Compose UI 1.7: AccessibilityManager.isEnabled and a non-empty service list). Launchers such as Projectivy run
 * one just to catch remote buttons (FEEDBACK_GENERIC, no screen reading), and a trace on the Formuler showed that
 * work taking nearly half the main thread while browsing. So: while no screen-reading service is on (TalkBack,
 * braille, spoken / audible feedback, touch exploration), Compose is told the service list is empty; the moment one
 * is switched on, the real list goes back and everything works as before. Reflection on two kept fields
 * (proguard-rules.pro); if they ever move, nothing happens and Compose behaves as stock.
 */
object A11yTrim {
    private val main = Handler(Looper.getMainLooper())
    private var installed = false

    private fun screenReaderOn(am: AccessibilityManager): Boolean =
        am.isTouchExplorationEnabled ||
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK and AccessibilityServiceInfo.FEEDBACK_GENERIC.inv()).isNotEmpty()

    /** The current screen (weakly: the listeners live as long as the app, a screen may not). */
    private var current = java.lang.ref.WeakReference<Activity>(null)

    fun install(activity: Activity) {
        current = java.lang.ref.WeakReference(activity)
        val am = activity.getSystemService(AccessibilityManager::class.java) ?: return
        if (!installed) {
            installed = true
            // Compose resets its list on these changes: apply again just after it has.
            am.addAccessibilityStateChangeListener { main.post { current.get()?.let { apply(it, am) } } }
            am.addTouchExplorationStateChangeListener { main.post { current.get()?.let { apply(it, am) } } }
        }
        main.post { current.get()?.let { apply(it, am) } }
    }

    /** Also after Compose views are (re)attached: Compose refreshes the list then. */
    fun refresh(activity: Activity) {
        val am = activity.getSystemService(AccessibilityManager::class.java) ?: return
        main.post { apply(activity, am) }
    }

    private fun apply(activity: Activity, am: AccessibilityManager) {
        runCatching {
            val list: List<AccessibilityServiceInfo> =
                if (screenReaderOn(am)) am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) else emptyList()
            composeViews(activity.window.decorView).forEach { v ->
                val delegate = v.javaClass.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }.get(v) ?: return@forEach
                delegate.javaClass.getDeclaredField("enabledServices").apply { isAccessible = true }.set(delegate, list)
            }
        }.onFailure { android.util.Log.w("A11yTrim", "not applied: $it") }
    }

    private fun composeViews(v: View): List<View> = buildList {
        if (v is androidx.compose.ui.platform.ViewRootForTest) add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) addAll(composeViews(v.getChildAt(i)))
    }
}
