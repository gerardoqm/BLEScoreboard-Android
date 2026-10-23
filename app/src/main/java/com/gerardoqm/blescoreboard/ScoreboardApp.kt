package com.gerardoqm.blescoreboard

import android.app.Application

/** Holds the one [ScoreboardManager] for the life of the process, so the match survives screens coming and going. */
class ScoreboardApp : Application() {
    lateinit var manager: ScoreboardManager
        private set

    override fun onCreate() {
        super.onCreate()
        manager = ScoreboardManager(this)
    }
}
