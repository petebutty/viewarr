package io.github.petebutty.viewarr.data.media

import android.media.MediaCodecList

/** What this TV box can decode itself, to choose between Plex direct play and Plex transcoding. */
object DeviceCodecs {
    private val decoders: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .map { it.lowercase() }
            .toSet()
    }

    private val plexToMime = mapOf(
        "h264" to "video/avc", "hevc" to "video/hevc", "h265" to "video/hevc",
        "mpeg2video" to "video/mpeg2", "mpeg4" to "video/mp4v-es", "vc1" to "video/wvc1",
        "vp9" to "video/x-vnd.on2.vp9", "vp8" to "video/x-vnd.on2.vp8", "av1" to "video/av01",
    )

    /** Containers the built-in player can open. */
    private val containers = setOf("mkv", "mp4", "m4v", "mov", "ts", "mpegts", "webm", "m2ts")

    fun canPlay(plexVideoCodec: String?, container: String?): Boolean {
        if (container != null && container.lowercase() !in containers) return false
        val mime = plexToMime[plexVideoCodec?.lowercase() ?: return true] ?: return false
        return mime in decoders
    }
}
