package com.januarius.gpxstats

import android.app.Application
import com.januarius.gpxstats.data.AppDatabase
import com.januarius.gpxstats.repo.SettingsStore
import com.januarius.gpxstats.repo.TrackRepository

class GpxStatsApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val repository: TrackRepository by lazy {
        TrackRepository(database.trackDao(), applicationContext)
    }

    val settings: SettingsStore by lazy { SettingsStore(applicationContext) }
}
