package io.github.petebutty.viewarr

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TvApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Shared Compose state that background work writes (the accounts' flow tells WatchSource what's signed in)
        // is created here, before any screen: created during a composition and written from a sync that started at
        // the same moment, Compose threw "reading a state that was created after the snapshot was taken" at start
        // (2026-11-19, the first build with the house sync).
        io.github.petebutty.viewarr.data.sync.WatchSource.known
    }

    /** Posters and backdrops on a 2 GB box: 16-bit bitmaps where possible, a capped memory cache, no crossfade. */
    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
        io.github.petebutty.viewarr.data.net.SmallerImages.weak = am?.isLowRamDevice == true
        // Sized from the real limit (largeHeap, or a raised one), never less than the normal app slice.
        am?.memoryClass?.let { MemoryBudget.normalBytes = maxOf(it.toLong() shl 20, MemoryBudget.budgetFrom(Runtime.getRuntime().maxMemory())) }
        // A 4K screen: pictures decoded smaller (see SmallerImages.screenScale).
        val dm = resources.displayMetrics
        if (maxOf(dm.widthPixels, dm.heightPixels) >= 3000) io.github.petebutty.viewarr.data.net.SmallerImages.screenScale = 0.67f
        return buildLoader()
    }

    /**
     * Gone to the background (the Home button, another app): let go of what can be fetched again, so Android keeps
     * Viewarr when memory is short instead of killing it, and it opens where you left it rather than starting again
     * (2026-11-15: on the 2 GB Shield it was killed two seconds after Projectivy came back). Pictures are kept
     * on disk, so they come straight back.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
            android.util.Log.i("TvApp", "in the background (trim $level): picture memory let go")
        }
    }

    private fun buildLoader(): ImageLoader = ImageLoader.Builder(this)
        .allowRgb565(true)
        .crossfade(false)
        // 18% of the box's normal allowance, not of the large one the app asks for (that's a safety margin only).
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes((MemoryBudget.normalBytes * 0.18).toInt()).build() }
        .respectCacheHeaders(false)
        // Pictures at the size they're shown (smaller still on weak boxes): smaller downloads and less memory.
        .components { add(io.github.petebutty.viewarr.data.net.SmallerImages) }
        // Posters and logos from a home server: its away address when away.
        .okHttpClient { okhttp3.OkHttpClient.Builder().addInterceptor(io.github.petebutty.viewarr.data.net.Reach.Interceptor).build() }
        .build()
}

/**
 * The memory caches are sized from (2026-11-18): Android's NORMAL allowance for an app on this box. Viewarr
 * asks for the large one (largeHeap), but only as a margin against running out: the picture cache and the film buffer
 * stay as big as they were, so a box uses no more memory day to day; the extra is only there before a crash would be.
 */
object MemoryBudget {
    /**
     * What the film buffer and picture cache are sized from: the memory this app is really given, less 128 MB kept free
     * for everything else. On a normal box (384 MB) that's 256 MB, as before; on a box whose limit has been raised (the
     * AM9's Magisk module gives 768 MB) it grows with it, so a bigger box buffers more and keeps more pictures.
     */
    @Volatile var normalBytes: Long = budgetFrom(Runtime.getRuntime().maxMemory())

    fun budgetFrom(max: Long): Long = (max - (128L shl 20)).coerceIn(96L shl 20, 1024L shl 20)
}
