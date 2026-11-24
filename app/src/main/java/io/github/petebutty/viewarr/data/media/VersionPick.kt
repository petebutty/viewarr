package io.github.petebutty.viewarr.data.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Choosing between the copies of a film on your servers (4K or 1080p, Dolby Vision or SDR, Atmos or stereo): the
 * best one this TV can show. Play takes the top one; Play from (hold OK on Play) lists them all, best first.
 */
object VersionPick {
    /** What the TV and box can show: the tallest picture allowed (Best quality allowed), and which HDR kinds. */
    data class Screen(val maxHeight: Int, val dolbyVision: Boolean, val hdr10: Boolean, val hdr10Plus: Boolean, val hlg: Boolean,
        /** Settings → Playback → Internet copies: the highest bitrate (kb/s) for a copy not in this house; null: no limit. */
        val maxKbpsAway: Int? = null)

    @Suppress("DEPRECATION")
    fun screen(context: Context, maxHeight: Int): Screen {
        val d = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val types = runCatching { d?.hdrCapabilities?.supportedHdrTypes?.toSet() }.getOrNull().orEmpty()
        return Screen(
            maxHeight = maxHeight,
            // (And the TV's own word where the box shows it: an Amlogic box reads it from the TV (dv_cap).)
            dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types && DeviceCodecs.has("video/dolby-vision") && tvSaysDv() != false,
            hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types,
            hdr10Plus = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types,
            hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types,
        )
    }

    /** Whether the TV says it shows Dolby Vision (Amlogic boxes), or null when the box doesn't say. */
    private fun tvSaysDv(): Boolean? = runCatching { java.io.File("/sys/class/amhdmitx/amhdmitx0/dv_cap").readText() }.getOrNull()
        ?.takeIf { it.isNotBlank() }?.let { !it.contains("don't support", ignoreCase = true) && !it.contains("not support", ignoreCase = true) }

    /** The box can decode it itself (otherwise only Plex can play it, by converting). */
    fun decodes(v: CopyVersion) = DeviceCodecs.canPlay(v.videoCodec, v.container)

    /** The standard places' order (Play order as it starts): Plex in the house, other servers there, Plex outside, the rest. */
    fun placeRank(home: Boolean, plex: Boolean) = when { home && plex -> 0; home -> 1; plex -> 2; else -> 3 }

    /** Higher is better; copies with nothing known score 0. [home]: on a server in this house (no internet in between). */
    fun score(v: CopyVersion?, s: Screen, home: Boolean, plex: Boolean = false, rank: Int = placeRank(home, plex)): Int {
        // The copy's place in Settings → Playback → Play order ([rank]: 0 for the first of the four server places, up to
        // 3), whatever the quality. Each step is bigger than everything below can add or take away, so quality, the TV
        // and the internet limit only order copies within a place.
        var n = (4 - rank.coerceIn(0, 3)) * 250_000
        v ?: return n
        val h = v.height ?: 0
        // A file the box can't decode: Plex converts it (a step down within its own place); anywhere else it just
        // fails, so it goes below every copy that plays, wherever it is.
        if (!decodes(v)) n -= if (plex) 100_000 else 1_000_000
        // Above Best quality allowed (or the TV): only if there's nothing else.
        if (h > s.maxHeight + 100) n -= 50_000
        // Over the internet and above Internet copies' limit: only if there's nothing smaller (then the smallest).
        val kbps = v.kbps ?: 0
        if (!home && s.maxKbpsAway != null && kbps > s.maxKbpsAway) n -= 40_000 + (kbps - s.maxKbpsAway) / 10
        n += minOf(h, s.maxHeight) * 10
        n += when (v.hdr) {
            // (On a TV without Dolby Vision a plain HDR10 copy is safer than one carrying Dolby Vision over HDR10.)
            // (What's underneath, where the server says: HLG or plain SDR under the Dolby Vision count as those.)
            "Dolby Vision" -> when {
                s.dolbyVision -> 3_000; v.dvOnly -> -20_000
                v.dvBase == "SDR" -> 0
                v.dvBase == "HLG" -> if (s.hlg) 900 else -1_000
                s.hdr10 -> 1_700; else -> -1_500
            }
            "HDR10+" -> when { s.hdr10Plus -> 2_500; s.hdr10 -> 2_000; else -> -1_500 }
            "HDR10" -> if (s.hdr10) 2_000 else -1_500
            "HLG" -> if (s.hlg) 1_000 else -1_000
            else -> 0
        }
        if (v.atmos) n += 800
        n += (v.audioChannels ?: 0).coerceAtMost(8) * 100
        n += ((v.kbps ?: 0) / 1_000).coerceAtMost(150)
        return n
    }

    /** Why a copy isn't the obvious choice, for the Play from list (null: nothing to say). */
    fun note(v: CopyVersion?, s: Screen, plex: Boolean, home: Boolean = true): String? {
        v ?: return null
        return when {
            !decodes(v) -> if (plex) "Plex converts it" else "This box can't play it"
            !home && s.maxKbpsAway != null && (v.kbps ?: 0) > s.maxKbpsAway -> "Too big for your internet (${(v.kbps ?: 0) / 1000} Mb/s)"
            v.hdr == "Dolby Vision" && v.dvOnly && !s.dolbyVision -> "Wrong colours here (Dolby Vision only)"
            v.hdr == "Dolby Vision" && !s.dolbyVision && v.dvBase != null -> "Shown as ${v.dvBase} here"
            (v.height ?: 0) > s.maxHeight + 100 -> "Above ${if (s.maxHeight >= 2000) "4K" else "${s.maxHeight}p"}"
            v.hdr != null && v.hdr != "Dolby Vision" && !s.hdr10 && !s.hlg -> "Shown without HDR"
            else -> null
        }
    }

    /** "TrueHD", "DTS-HD MA"… from a server's codec name. */
    fun audioName(codec: String?, profile: String? = null): String? {
        val c = codec?.lowercase() ?: return null
        val p = profile?.lowercase().orEmpty()
        return when {
            c == "truehd" -> "TrueHD"
            c == "eac3" -> "DD+"
            c == "ac3" -> "Dolby Digital"
            c == "dca" || c == "dts" -> when { "ma" in p -> "DTS-HD MA"; "x" in p -> "DTS:X"; else -> "DTS" }
            c == "aac" -> "AAC"
            c == "flac" -> "FLAC"
            c == "opus" -> "Opus"
            c.startsWith("pcm") -> "PCM"
            else -> c.uppercase()
        }
    }

    fun channelsName(ch: Int?) = when (ch) { null, 0 -> null; 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${ch}ch" }

    /** "Atmos 7.1", "DTS-HD MA 5.1", "AAC Stereo". */
    fun audioLabel(codec: String?, profile: String?, channels: Int?, atmos: Boolean): String? {
        val ch = channelsName(channels)
        return if (atmos) listOfNotNull("Atmos", ch).joinToString(" ") else listOfNotNull(audioName(codec, profile), ch).joinToString(" ").ifEmpty { null }
    }
}
