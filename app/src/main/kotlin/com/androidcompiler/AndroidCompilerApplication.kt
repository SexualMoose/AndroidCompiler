package com.androidcompiler

import android.app.Application
import com.androidcompiler.fleet.FleetReporter
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AndroidCompilerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Crash / freeze / error reports for FleetGuardian (dev hub contract section 6).
        FleetReporter.install(this)
    }
}
