package io.github.petebutty.viewarr

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TvApp : Application(), ImageLoaderFactory {
    /** Posters and backdrops on a 2 GB box: 16-bit bitmaps where possible, a capped memory cache, no crossfade. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .allowRgb565(true)
        .crossfade(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.18).build() }
        .respectCacheHeaders(false)
        .build()
}
