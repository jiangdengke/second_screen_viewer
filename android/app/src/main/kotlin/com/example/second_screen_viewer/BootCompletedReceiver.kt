package com.example.second_screen_viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        try {
            val bootEventElapsed = if (action == Intent.ACTION_BOOT_COMPLETED) {
                SystemClock.elapsedRealtime()
            } else null
            ControlHttpService.start(context, bootEventElapsed)
        } catch (error: Exception) {
            Log.e("SecondScreenViewer", "Failed to start control service after boot", error)
        }
    }
}
