package com.shazan.airbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle

class VolumeHelperActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0) // Suppress enter animation

        val action = intent.getIntExtra("VOL_ACTION", AudioManager.ADJUST_RAISE)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                action,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        finish() // Die instantly
        overridePendingTransition(0, 0) // Suppress exit animation
    }
}