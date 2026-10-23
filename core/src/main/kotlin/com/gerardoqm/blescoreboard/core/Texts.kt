package com.gerardoqm.blescoreboard.core

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/** "07:45", always with Western digits. */
fun clockText(minutes: Int, seconds: Int): String = String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)

/** The language the app uses: the phone's own, or one picked inside the app (and sent to the watch). */
enum class AppLanguage(val tag: String) {
    System("system"), English("en"), Spanish("es");

    /** "es" or "en", also when the language follows the phone. */
    fun code(phoneLanguage: String = Locale.getDefault().language): String =
        if (this == System) (if (phoneLanguage == "es") "es" else "en") else tag

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: System
    }
}

object Texts {
    /**
     * The ordinal of a period as the scoreboard says it: "1st", "2nd", "3rd", "4th" in English and
     * "1er", "2do", "3er", "4to", ... in Spanish (the unit names, "tiempo", "periodo" and "cuarto", are masculine).
     */
    fun ordinal(number: Int, code: String): String {
        if (code == "es") {
            val forms = mapOf(1 to "1er", 2 to "2do", 3 to "3er", 4 to "4to", 5 to "5to", 6 to "6to", 7 to "7mo", 8 to "8vo", 9 to "9no", 10 to "10mo")
            return forms[number] ?: "$number.º"
        }
        val suffix = when {
            number % 100 in 11..13 -> "th"
            number % 10 == 1 -> "st"
            number % 10 == 2 -> "nd"
            number % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$number$suffix"
    }

    /** The unit name in the plural: "quarter" -> "quarters", "half" -> "halves", "tiempo" -> "tiempos", "mitad" -> "mitades". */
    fun plural(word: String, code: String): String {
        if (code == "es") return word + if (word.lastOrNull()?.let { it in "aeiouáéíóú" } != false) "s" else "es"
        if (listOf("s", "x", "z", "ch", "sh").any { word.endsWith(it) }) return word + "es"
        if (word.endsWith("y") && word.length > 1 && word[word.length - 2] !in "aeiou") return word.dropLast(1) + "ies"
        if (word.endsWith("f")) return word.dropLast(1) + "ves"
        return word + "s"
    }

    /**
     * The LED panel font is ASCII-only, so strip accents and drop anything else ("1.º Período" -> "1. Periodo").
     * Also removes "|", which separates the fields of the text payload.
     */
    fun panelSafe(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).filter { it.code < 0x80 && it != '|' }

    /** The user-visible characters of a text (an emoji with modifiers or joiners is one). */
    fun graphemes(text: String): List<String> {
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) { out += text.substring(start, end); start = end; end = it.next() }
        return out
    }

    /** True for an emoji (e.g. ⚽ 🦁 ❤️), false for letters, digits and symbols such as © or #. */
    fun isEmoji(grapheme: String): Boolean {
        if (grapheme.isEmpty()) return false
        val first = grapheme.codePointAt(0)
        if (isEmojiPresentation(first)) return true
        // a text-style symbol made emoji by a variation selector, a keycap or a joiner
        val count = grapheme.codePointCount(0, grapheme.length)
        return first > 0x7F && count > 1 && isEmojiCapable(first) &&
            (grapheme.contains('️') || grapheme.contains('‍') || grapheme.contains('⃣'))
    }

    private fun isEmojiPresentation(cp: Int): Boolean = when (cp) {
        in 0x1F1E6..0x1F1FF, in 0x1F300..0x1F5FF, in 0x1F600..0x1F64F, in 0x1F680..0x1F6FF,
        in 0x1F900..0x1F9FF, in 0x1FA70..0x1FAFF, 0x1F004, 0x1F0CF, 0x1F18E, in 0x1F191..0x1F19A,
        0x231A, 0x231B, in 0x23E9..0x23EC, 0x23F0, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615, in 0x2648..0x2653,
        0x267F, 0x2693, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26D4, 0x26EA,
        0x26F2, 0x26F3, 0x26F5, 0x26FA, 0x26FD, 0x2705, 0x270A, 0x270B, 0x2728, 0x274C, 0x274E,
        in 0x2753..0x2755, 0x2757, in 0x2795..0x2797, 0x27B0, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B55 -> true
        else -> false
    }

    private fun isEmojiCapable(cp: Int): Boolean =
        cp == 0x00A9 || cp == 0x00AE || cp in 0x2000..0x2BFF || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 || cp >= 0x1F000

    /** The first two emoji of a name, one per picture slot. */
    fun emojis(name: String): List<String> = graphemes(name).filter(::isEmoji).take(2)

    /** The text without its emoji (spoken announcements would read an emoji out by its description). */
    fun withoutEmoji(text: String): String = graphemes(text).filterNot(::isEmoji).joinToString("").trim()

    /**
     * How the panel shows a name: ASCII capitals, up to 20 characters. Each emoji (up to two per team) becomes the byte of
     * its picture slot (1-4); `firstSlot` is 0 for the home name and 2 for the away name.
     */
    fun panelText(text: String, firstSlot: Int = 0): String {
        val out = StringBuilder()
        var used = 0
        for (g in graphemes(text)) {
            if (isEmoji(g)) {
                if (used < 2) { out.append((firstSlot + used + 1).toChar()); used++ }
            } else out.append(panelSafe(g))
        }
        return out.toString().uppercase(Locale.ROOT).take(20)
    }

    /** Alert text for the panel: ASCII capitals, single spaces, at most `limit` characters (32 from protocol 8, 16 before). */
    fun alertText(text: String, limit: Int): String =
        panelSafe(text).uppercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }.joinToString(" ").take(limit)

    /** A board name the firmware accepts: 1-20 printable ASCII characters. */
    fun isValidBoardName(name: String): Boolean =
        name.isNotEmpty() && name.length <= BoardProtocol.DEVICE_NAME_MAX_LENGTH && name.all { it.code in 0x20..0x7E }
}
