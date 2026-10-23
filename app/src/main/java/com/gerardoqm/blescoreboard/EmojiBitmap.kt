package com.gerardoqm.blescoreboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.gerardoqm.blescoreboard.core.BoardProtocol

/** Turns an emoji into a small RGB565 picture for the panel. */
object EmojiBitmap {
    /** The emoji that can mark a goal. */
    val goalChoices = listOf("⚽", "🥅", "🏆", "🥇", "🔥", "⭐", "🎯", "👏", "🎉", "🏀", "🏈", "⚾", "🏐", "🏒")

    private val cache = HashMap<String, IntArray?>()

    /** 16 x 16, for the goal alert. */
    fun goalIcon(emoji: String): IntArray? = make(emoji, BoardProtocol.GOAL_ICON_SIZE, 13f)

    /** 10 x 10, inside team names. */
    fun nameIcon(emoji: String): IntArray? = make(emoji, BoardProtocol.NAME_ICON_SIZE, 8.5f)

    /** Draws the emoji on black and returns it as RGB565 pixels, row by row. Black stays 0, which the panel treats as see-through. */
    @Synchronized
    fun make(emoji: String, size: Int, textSize: Float): IntArray? {
        if (emoji.isEmpty()) return null
        val key = "$emoji/$size"
        if (cache.containsKey(key)) return cache[key]
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; textAlign = Paint.Align.CENTER }
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(emoji, size / 2f, baseline, paint)
        val argb = IntArray(size * size)
        bitmap.getPixels(argb, 0, size, 0, 0, size, size)
        bitmap.recycle()
        val pixels = IntArray(size * size) { i ->
            val p = argb[i]
            val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
            ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (b shr 3)
        }
        val result = if (pixels.any { it != 0 }) pixels else null
        cache[key] = result
        return result
    }
}
