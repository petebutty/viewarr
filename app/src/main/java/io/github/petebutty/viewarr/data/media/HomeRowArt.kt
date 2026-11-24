package io.github.petebutty.viewarr.data.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Pictures for Viewarr's home-screen rows, drawn on the box (2026-11-20: "right now you can't tell what each
 * row is"; the launcher cut channel logos to fill its wide cards).
 *  - A row's own icon: its name on a coloured tile (the launcher shows it beside the row).
 *  - A channel card: the channel's logo whole, on a dark 16:9 card, its name underneath when there's no logo.
 * Cards are served to the launcher by [HomeRowArtProvider] (content://…homerowart/<file>).
 */
object HomeRowArt {
    const val AUTHORITY = "io.github.petebutty.viewarr.homerowart"
    private const val DIR = "homerow_art"

    fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }
    fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

    /** The row's icon: up to two short lines of its name on a tile coloured by the name. */
    fun rowIcon(name: String): Bitmap {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = ((name.lowercase().hashCode() and 0x7fffffff) % 360).toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.42f)) }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 56f, 56f, bg)
        val words = name.replace("·", " ").replace("+", "+ ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = when {
            words.size <= 1 -> listOf(words.firstOrNull().orEmpty())
            else -> {
                // Two lines as even as the words allow.
                val all = words.joinToString(" ")
                var best = listOf(all); var bestDiff = Int.MAX_VALUE
                for (i in 1 until words.size) {
                    val a = words.take(i).joinToString(" "); val b = words.drop(i).joinToString(" ")
                    val d = kotlin.math.abs(a.length - b.length)
                    if (d < bestDiff) { bestDiff = d; best = listOf(a, b) }
                }
                best
            }
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        // As big as fits the tile's width (and two lines its height).
        var ts = 120f
        val maxW = size * 0.84f
        while (ts > 20f && (lines.any { p.apply { textSize = ts }.measureText(it) > maxW } || ts * lines.size * 1.15f > size * 0.8f)) ts -= 4f
        p.textSize = ts
        val lh = ts * 1.12f
        var y = size / 2f - (lh * (lines.size - 1)) / 2f - (p.descent() + p.ascent()) / 2f
        for (l in lines) { c.drawText(l, size / 2f, y, p); y += lh }
        return bmp
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 6_000; conn.readTimeout = 8_000
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /**
     * A service row's icon: its logo. An app icon ([square]) fills the tile; a network's transparent logo sits on a
     * light tile. Null: the logo didn't load (the row keeps its name tile).
     */
    fun serviceIcon(url: String, square: Boolean): Bitmap? {
        val logo = fetch(url) ?: return null
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val tile = RectF(0f, 0f, size.toFloat(), size.toFloat())
        if (square) {
            val path = android.graphics.Path().apply { addRoundRect(tile, 56f, 56f, android.graphics.Path.Direction.CW) }
            c.clipPath(path)
            c.drawBitmap(logo, null, tile, paint)
        } else {
            c.drawRoundRect(tile, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF2, 0xF3, 0xF5) })
            val box = size * 0.74f
            val s = minOf(box / logo.width, box / logo.height)
            val w = logo.width * s; val h = logo.height * s
            c.drawBitmap(logo, null, RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f), paint)
        }
        logo.recycle()
        return bmp
    }

    /**
     * A film's or show's wide card (16:9): its backdrop, darkened along the bottom, with its title logo (or name) on
     * it, as the streaming services draw theirs. Saved; null when there's no backdrop.
     */
    fun titleCard(context: Context, key: String, backdrop: String, logoUrl: String?, title: String): Uri? = runCatching {
        val w = 640; val h = 360
        val art = fetch(backdrop) ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Cover the card (cut evenly at the edges).
        val s = maxOf(w.toFloat() / art.width, h.toFloat() / art.height)
        val aw = art.width * s; val ah = art.height * s
        c.drawBitmap(art, null, RectF((w - aw) / 2f, (h - ah) / 2f, (w + aw) / 2f, (h + ah) / 2f), paint)
        art.recycle()
        val shade = Paint().apply { shader = android.graphics.LinearGradient(0f, h * 0.35f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(215, 0, 0, 0), android.graphics.Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        val logo = logoUrl?.let { fetch(it) }
        if (logo != null && logo.width > 0) {
            val maxW = w * 0.48f; val maxH = h * 0.30f
            val ls = minOf(maxW / logo.width, maxH / logo.height)
            val lw = logo.width * ls; val lh = logo.height * ls
            c.drawBitmap(logo, null, RectF(w * 0.06f, h * 0.92f - lh, w * 0.06f + lw, h * 0.92f), paint)
            logo.recycle()
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 40f }
            while (p.textSize > 20f && p.measureText(title) > w * 0.86f) p.textSize -= 2f
            c.drawText(title, w * 0.06f, h * 0.90f, p)
        }
        val file = "t_" + key.filter { it.isLetterOrDigit() || it == '-' }.take(80) + ".jpg"
        File(dir(context), file).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        bmp.recycle()
        uriFor(file)
    }.getOrNull()

    /**
     * A channel's card showing what's on it now: the programme's picture filling the card, darkened along the bottom,
     * the channel's logo on a small dark plate at the top left and the programme's name along the bottom. Saved under
     * [file]; null when the picture didn't load.
     */
    fun programmeCard(context: Context, file: String, picture: String, channelLogo: String?, programme: String): Uri? = runCatching {
        val w = 640; val h = 360
        val art = fetch(picture) ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val s = maxOf(w.toFloat() / art.width, h.toFloat() / art.height)
        val aw = art.width * s; val ah = art.height * s
        c.drawBitmap(art, null, RectF((w - aw) / 2f, (h - ah) / 2f, (w + aw) / 2f, (h + ah) / 2f), paint)
        art.recycle()
        val shade = Paint().apply { shader = android.graphics.LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(220, 0, 0, 0), android.graphics.Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        channelLogo?.let { fetch(it) }?.let { logo ->
            if (logo.width > 0 && logo.height > 0) {
                val maxW = w * 0.24f; val maxH = h * 0.15f
                val ls = minOf(maxW / logo.width, maxH / logo.height)
                val lw = logo.width * ls; val lh = logo.height * ls
                val pad = 10f
                c.drawRoundRect(RectF(18f, 18f, 18f + lw + pad * 2, 18f + lh + pad * 2), 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 0, 0, 0) })
                c.drawBitmap(logo, null, RectF(18f + pad, 18f + pad, 18f + pad + lw, 18f + pad + lh), paint)
            }
            logo.recycle()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 38f }
        var text = programme
        while (p.textSize > 22f && p.measureText(text) > w * 0.88f) p.textSize -= 2f
        while (text.length > 4 && p.measureText(text) > w * 0.88f) text = text.dropLast(2).trimEnd() + "…"
        c.drawText(text, w * 0.06f, h * 0.90f, p)
        File(dir(context), file).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        bmp.recycle()
        uriFor(file)
    }.getOrNull()

    /** Card pictures not drawn again for two days: gone (programmes change, and each channel's old ones pile up). */
    fun tidy(context: Context) {
        val cut = System.currentTimeMillis() - 2 * 86_400_000L
        dir(context).listFiles()?.filter { it.name.startsWith("p_") && it.lastModified() < cut }?.forEach { it.delete() }
        dir(context).listFiles()?.filter { it.name.startsWith("t_") && it.lastModified() < System.currentTimeMillis() - 14 * 86_400_000L }?.forEach { it.delete() }
    }

    /** A channel's card: its logo whole on a dark 16:9 card (the name instead when the logo didn't load). Saved; null on failure. */
    fun channelCard(context: Context, key: String, logoUrl: String?, name: String): Uri? = runCatching {
        val w = 640; val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0x1B, 0x1E, 0x25))
        val logo = logoUrl?.let { fetch(it) }
        if (logo != null && logo.width > 0 && logo.height > 0) {
            val boxW = w * 0.72f; val boxH = h * 0.62f
            val s = minOf(boxW / logo.width, boxH / logo.height)
            val lw = logo.width * s; val lh = logo.height * s
            val dst = RectF((w - lw) / 2f, (h - lh) / 2f, (w + lw) / 2f, (h + lh) / 2f)
            c.drawBitmap(logo, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            logo.recycle()
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER; textSize = 64f }
            while (p.textSize > 24f && p.measureText(name) > w * 0.86f) p.textSize -= 4f
            c.drawText(name, w / 2f, h / 2f - (p.descent() + p.ascent()) / 2f, p)
        }
        val file = "ch_" + key.filter { it.isLetterOrDigit() || it == '-' }.take(60) + ".png"
        File(dir(context), file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        uriFor(file)
    }.getOrNull()
}

/** Hands the home screen its card pictures (read-only files; nothing private: channel logos and names). */
class HomeRowArtProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = if (uri.lastPathSegment?.endsWith(".jpg") == true) "image/jpeg" else "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: return null
        val f = File(HomeRowArt.dir(ctx), name)
        return if (f.isFile) ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) else null
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
