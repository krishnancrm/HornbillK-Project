// In file: app/src/main/java/com/example/hornbillk/HornbillkApp.kt
package com.example.hornbillk

import android.app.Application
import android.util.Log

class HornbillkApp : Application() {

    // ⚡ FIX: Make this nullable instead of lateinit to prevent background boot crashes
    var appMonitor: AppMonitor? = null

    // Inside HornbillkApp.kt
    companion object {
        var isAlertActive: Boolean = false
        var activeAnimalName: String = ""
        var activeCameraIndex: Int = 0
        var activePiPhone: String = ""

        // ⚡ ADD THIS NEW LINE:
        var activeVideoUrl: String = ""
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("HornbillkApp", "Application process spawned successfully.")
    }
}