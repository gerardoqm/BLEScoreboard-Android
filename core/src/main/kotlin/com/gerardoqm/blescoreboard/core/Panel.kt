package com.gerardoqm.blescoreboard.core

/** An 8-bit RGB color, the form stored on the phone and sent to the board. */
data class Rgb(val r: Int, val g: Int, val b: Int) {
    /** 16-bit RGB565, the format the LED panel uses. */
    val rgb565: Int get() = ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (b shr 3)

    /** 0xRRGGBB */
    val packed: Int get() = (r shl 16) or (g shl 8) or b

    companion object {
        /** The color of an RGB565 value, as the panel shows it. */
        fun fromRgb565(v: Int) = Rgb((v shr 11 and 0x1F) * 255 / 31, (v shr 5 and 0x3F) * 255 / 63, (v and 0x1F) * 255 / 31)
        fun fromPacked(v: Int) = Rgb(v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
    }
}

/**
 * Every piece of text on the panel that has its own color. The order is the wire order of the "set colors" command and
 * of the visibility bitmask: keep it in sync with the firmware.
 */
enum class PanelElement(val defaultColor: Rgb) {
    TimerRunning(Rgb(0, 255, 0)),
    TimerStopped(Rgb(255, 120, 0)),
    Period(Rgb(255, 255, 0)),
    HomeName(Rgb(0, 120, 255)),
    HomeScore(Rgb(0, 120, 255)),
    AwayName(Rgb(255, 40, 40)),
    AwayScore(Rgb(255, 40, 40)),
    AlertGame(Rgb(0, 255, 0)),
    AlertPeriod(Rgb(0, 220, 255));

    /** The timer has one switch for both of its colors, kept on the running row. */
    val visibilityKey: PanelElement get() = if (this == TimerStopped) TimerRunning else this
    val hasVisibilitySwitch: Boolean get() = this != TimerStopped

    companion object {
        val defaults: Map<PanelElement, Rgb> get() = entries.associateWith { it.defaultColor }

        /** Wire format: bit i set means element i is shown (the timer's stopped bit follows its running bit). */
        fun visibilityMask(visible: Map<PanelElement, Boolean>): Int {
            var mask = 0
            entries.forEachIndexed { i, e -> if (visible[e.visibilityKey] != false) mask = mask or (1 shl i) }
            return mask
        }
    }
}

/** The panel's 3x5 pixel font, the same shapes the firmware draws. */
object LedFont {
    private val glyphs: Map<Char, List<String>> = mapOf(
        '0' to listOf("111", "101", "101", "101", "111"),
        '1' to listOf("010", "110", "010", "010", "111"),
        '2' to listOf("111", "001", "111", "100", "111"),
        '3' to listOf("111", "001", "111", "001", "111"),
        '4' to listOf("101", "101", "111", "001", "001"),
        '5' to listOf("111", "100", "111", "001", "111"),
        '6' to listOf("111", "100", "111", "101", "111"),
        '7' to listOf("111", "001", "010", "010", "010"),
        '8' to listOf("111", "101", "111", "101", "111"),
        '9' to listOf("111", "101", "111", "001", "111"),
        'A' to listOf("010", "101", "111", "101", "101"),
        'B' to listOf("110", "101", "110", "101", "110"),
        'C' to listOf("011", "100", "100", "100", "011"),
        'D' to listOf("110", "101", "101", "101", "110"),
        'E' to listOf("111", "100", "110", "100", "111"),
        'F' to listOf("111", "100", "110", "100", "100"),
        'G' to listOf("011", "100", "101", "101", "011"),
        'H' to listOf("101", "101", "111", "101", "101"),
        'I' to listOf("111", "010", "010", "010", "111"),
        'J' to listOf("001", "001", "001", "101", "010"),
        'K' to listOf("101", "101", "110", "101", "101"),
        'L' to listOf("100", "100", "100", "100", "111"),
        'M' to listOf("101", "111", "111", "101", "101"),
        'N' to listOf("111", "101", "101", "101", "101"),
        'O' to listOf("010", "101", "101", "101", "010"),
        'P' to listOf("110", "101", "110", "100", "100"),
        'Q' to listOf("010", "101", "101", "110", "011"),
        'R' to listOf("110", "101", "110", "101", "101"),
        'S' to listOf("011", "100", "010", "001", "110"),
        'T' to listOf("111", "010", "010", "010", "010"),
        'U' to listOf("101", "101", "101", "101", "111"),
        'V' to listOf("101", "101", "101", "101", "010"),
        'W' to listOf("101", "101", "111", "111", "101"),
        'X' to listOf("101", "101", "010", "101", "101"),
        'Y' to listOf("101", "101", "010", "010", "010"),
        'Z' to listOf("111", "001", "010", "100", "111"),
        '/' to listOf("001", "001", "010", "100", "100"),
        '%' to listOf("101", "001", "010", "100", "101"),
        ':' to listOf("0", "1", "0", "1", "0"),
        ' ' to listOf("00", "00", "00", "00", "00"),
        '-' to listOf("000", "000", "111", "000", "000"),
    )
    private val iconSpace = List(5) { "00000" }

    /** The bytes 1-4 stand for the emoji pictures of the team names: 5 columns of space, drawn separately. */
    fun iconSlot(ch: Char): Int? = if (ch.code in 1..4) ch.code - 1 else null

    fun glyph(ch: Char): List<String> {
        if (iconSlot(ch) != null) return iconSpace
        return glyphs[ch.uppercaseChar()] ?: glyphs.getValue('-')
    }

    /** Width of a string in font pixels, with one pixel between letters. */
    fun units(text: String): Int = if (text.isEmpty()) 0 else text.fold(-1) { acc, c -> acc + glyph(c)[0].length + 1 }
}

/** An 80 x 40 pixel picture of the panel, drawn with the same layout as the firmware. -1 = unlit, otherwise 0xRRGGBB. */
class PanelFrame {
    val pixels = IntArray(WIDTH * HEIGHT) { -1 }

    fun set(x: Int, y: Int, c: Rgb, clipX: IntRange? = null) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return
        if (clipX != null && x !in clipX) return
        pixels[y * WIDTH + x] = c.packed
    }

    fun text(t: String, x: Int, y: Int, scale: Int, color: Rgb, clipX: IntRange? = null, icons: List<IntArray> = emptyList()) {
        var cx = x
        for (ch in t) {
            val g = LedFont.glyph(ch)
            val slot = LedFont.iconSlot(ch)
            val ns = BoardProtocol.NAME_ICON_SIZE
            if ((scale == 2 || scale == 3) && slot != null && slot < icons.size && icons[slot].size == ns * ns) {
                val n = ns * scale / 2           // 10 pixels in names, 15 in alerts, like the firmware
                for (row in 0 until n) for (col in 0 until n) {
                    val v = icons[slot][(row * ns / n) * ns + (col * ns / n)]
                    if (v != 0) set(cx + col, y + row, Rgb.fromRgb565(v), clipX)
                }
            }
            g.forEachIndexed { row, line ->
                line.forEachIndexed { col, bit ->
                    if (bit == '1') for (dx in 0 until scale) for (dy in 0 until scale) set(cx + col * scale + dx, y + row * scale + dy, color, clipX)
                }
            }
            cx += (g[0].length + 1) * scale
        }
    }

    companion object {
        const val WIDTH = 80
        const val HEIGHT = 40
        private val DIVIDER = Rgb(40, 40, 40)

        /** Scroll position of a name that does not fit its slot: pause at the start, then 70 ms per pixel, like the firmware. */
        fun marqueeOffset(textUnits: Int, timeSeconds: Double): Int {
            val width = textUnits * 2
            if (width <= 36) return 0
            val dwell = 1.5; val step = 0.07; val travel = (width + 18) * step
            val phase = timeSeconds % (dwell + travel)
            return if (phase < dwell) 0 else ((phase - dwell) / step).toInt()
        }

        /** The scoreboard with the real match state: the same layout the firmware draws. */
        fun live(
            homeName: String, awayName: String, homeScore: Int, awayScore: Int, period: Int, totalPeriods: Int,
            minutes: Int, seconds: Int, running: Boolean, colors: Map<PanelElement, Rgb>,
            visible: Map<PanelElement, Boolean> = emptyMap(), timeSeconds: Double, icons: List<IntArray> = emptyList(),
        ): PanelFrame {
            fun c(e: PanelElement) = colors[e] ?: e.defaultColor
            fun shown(e: PanelElement) = visible[e.visibilityKey] != false
            val f = PanelFrame()
            if (shown(PanelElement.TimerRunning)) {
                f.text(clockText(minutes, seconds), 2, 1, 2, c(if (running) PanelElement.TimerRunning else PanelElement.TimerStopped))
            }
            val periodText = "$period/$totalPeriods"
            if (shown(PanelElement.Period)) f.text(periodText, 78 - LedFont.units(periodText) * 2, 1, 2, c(PanelElement.Period))
            for (j in 0 until 26) f.set(40, 12 + j, DIVIDER)
            for ((value, center, element) in listOf(Triple(homeScore, 20, PanelElement.HomeScore), Triple(awayScore, 60, PanelElement.AwayScore))) {
                if (!shown(element)) continue
                val text = value.toString()
                f.text(text, center - LedFont.units(text) * 3 / 2, 13, 3, c(element))
            }
            for ((name, slotX, element) in listOf(Triple(homeName, 2, PanelElement.HomeName), Triple(awayName, 42, PanelElement.AwayName))) {
                if (!shown(element)) continue
                val units = LedFont.units(name); val width = units * 2; val clip = slotX until slotX + 36
                if (width <= 36) {
                    f.text(name, slotX + (36 - width) / 2, 29, 2, c(element), clip, icons)
                } else {
                    val offset = marqueeOffset(units, timeSeconds)
                    f.text(name, slotX - offset, 29, 2, c(element), clip, icons)
                    f.text(name, slotX - offset + width + 18, 29, 2, c(element), clip, icons)
                }
            }
            return f
        }

        /** The sample scoreboard used on the colors page. */
        fun sample(colors: Map<PanelElement, Rgb>, clockRunning: Boolean, visible: Map<PanelElement, Boolean> = emptyMap()) =
            live("LION", "BEAR", 12, 9, 2, 4, 7, 45, clockRunning, colors, visible, 0.0)

        /** Same timing as the firmware: text scrolls right to left at 3x (18 ms per pixel) while a 2 pixel border flashes at 2 Hz. */
        fun alertDurationSeconds(text: String): Double = ((WIDTH + LedFont.units(text) * 3) * 18 + 400) / 1000.0

        /** One frame of an alert, or null once it has finished. */
        fun alert(text: String, color: Rgb, elapsedSeconds: Double, icon: IntArray? = null, nameIcons: List<IntArray> = emptyList()): PanelFrame? {
            if (elapsedSeconds >= alertDurationSeconds(text)) return null
            val f = PanelFrame()
            if ((elapsedSeconds * 1000 / 250).toInt() % 2 == 0) {
                for (i in 0 until WIDTH) for (k in 0 until 2) { f.set(i, k, color); f.set(i, HEIGHT - 1 - k, color) }
                for (j in 0 until HEIGHT) for (k in 0 until 2) { f.set(k, j, color); f.set(WIDTH - 1 - k, j, color) }
            }
            val textX = WIDTH - (elapsedSeconds * 1000 / 18).toInt()
            f.text(text, textX, 12, 3, color, icons = nameIcons)
            val s = BoardProtocol.GOAL_ICON_SIZE
            if (icon != null && icon.size == s * s) {       // the icon rides in front of the text
                for (row in 0 until s) for (col in 0 until s) {
                    val v = icon[row * s + col]
                    if (v != 0) f.set(textX - 19 + col, 12 + row, Rgb.fromRgb565(v))
                }
            }
            return f
        }
    }
}

/** An alert playing on the simulated panel. */
data class AlertPlayback(
    val text: String,
    val color: Rgb,
    val startMs: Long,
    /** 16 x 16 RGB565 pixels (0 = transparent) scrolled in front of the text, for goal alerts. */
    val icon: IntArray? = null,
    /** The pictures behind emoji characters (bytes 1-4) in the text. */
    val nameIcons: List<IntArray> = emptyList(),
)
