package com.gerardoqm.blescoreboard.core

/**
 * When half time and the "time left" announcements happen.
 * Half of the whole match is reached at the end of the middle period when the number of periods is even,
 * and halfway through the middle period when it is odd.
 */
object MatchRules {
    fun halfTimeEndsPeriod(period: Int, totalPeriods: Int) = totalPeriods % 2 == 0 && period == totalPeriods / 2

    fun halfTimeInsidePeriod(period: Int, totalPeriods: Int) = totalPeriods % 2 == 1 && period == (totalPeriods + 1) / 2

    /** Seconds from now until half time, if it is still ahead in this period. */
    fun secondsUntilHalfTime(remaining: Int, period: Int, totalPeriods: Int, periodLengthSeconds: Int): Int? = when {
        halfTimeEndsPeriod(period, totalPeriods) -> remaining
        halfTimeInsidePeriod(period, totalPeriods) && remaining > periodLengthSeconds / 2 -> remaining - periodLengthSeconds / 2
        else -> null
    }

    /** Whether the clock just passed half time inside the middle period. */
    fun crossedHalfTimeInside(previous: Int, remaining: Int, period: Int, totalPeriods: Int, periodLengthSeconds: Int) =
        halfTimeInsidePeriod(period, totalPeriods) && previous > periodLengthSeconds / 2 && remaining <= periodLengthSeconds / 2

    /**
     * The "time left" to announce as the clock went from `previous` to `remaining` seconds: each chosen time once, as the clock
     * passes it (the shortest one if a long gap skipped several). Only times shorter than the period count.
     */
    fun crossedTimeLeft(chosenMinutes: Set<Int>, previous: Int, remaining: Int, periodLengthSeconds: Int): Int? =
        chosenMinutes.filter { it * 60 < periodLengthSeconds && previous > it * 60 && remaining <= it * 60 }.minOrNull()
}

/** The phone <-> watch messages over the Wear Data Layer. Mirrors the keys the iOS app uses with WatchConnectivity. */
object WatchSync {
    /** Phone -> watch: the whole match state, as a live message and as a data item the watch reads when it wakes. */
    const val PATH_STATE = "/scoreboard/state"
    /** Watch -> phone: only the values the user just changed. */
    const val PATH_COMMAND = "/scoreboard/command"
    /** Phone -> watch: an alert to tap and show. */
    const val PATH_ALERT = "/scoreboard/alert"

    const val HOME = "h"
    const val AWAY = "a"
    const val PERIOD = "p"
    const val RUNNING = "r"
    const val TOTAL = "t"
    const val LABEL = "l"
    const val MINUTES = "m"
    const val SECONDS = "s"
    const val HOME_NAME = "hn"
    const val AWAY_NAME = "an"
    const val LANGUAGE = "lang"
    const val TIMESTAMP = "ts"
    /** Watch -> phone: reset the clock to the full period. */
    const val RESET_CLOCK = "rc"
    /** Watch -> phone: send me the state now. */
    const val SYNC = "sync"

    const val ALERT_ID = "triggerAlert"
    const val ALERT_TEXT = "alertText"

    // alert ids, the same numbers the iOS app sends
    const val ALERT_START = 1
    const val ALERT_PERIOD_ADVANCE = 2
    const val ALERT_MATCH_END = 3
    const val ALERT_HALF_TIME = 4
    const val ALERT_GOAL = 5
    const val ALERT_TIME_LEFT = 6

    const val MAX_SCORE = 99
}
