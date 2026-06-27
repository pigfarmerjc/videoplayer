package com.example.videoplayer.util

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

object AudioEffectManager {
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var currentSessionId: Int = -1
    private var currentPreset: String = "Normal"
    private var currentBoostPercent: Int = 100

    @Synchronized
    fun setVolumeBoost(audioSessionId: Int, percent: Int) {
        if (audioSessionId <= 0) return
        currentBoostPercent = percent
        
        try {
            if (percent <= 100) {
                releaseLoudnessEnhancer()
                return
            }
            
            val enhancer = getOrInitLoudnessEnhancer(audioSessionId) ?: return
            
            // Map 101% - 200% linearly to 0 mB - 2000 mB (+20 dB)
            val targetGainMb = (percent - 100) * 20
            enhancer.setTargetGain(targetGainMb)
            if (!enhancer.enabled) {
                enhancer.enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun applyPreset(audioSessionId: Int, presetName: String) {
        if (audioSessionId <= 0) return
        currentPreset = presetName
        
        try {
            if (presetName == "Normal") {
                releaseEqualizer()
                return
            }
            
            val eq = getOrInitEqualizer(audioSessionId) ?: return
            val numBands = eq.numberOfBands
            
            // Define custom band gains (in millibels, where 100 mB = 1 dB)
            val gains = when (presetName) {
                "Bass Boost" -> shortArrayOf(600, 400, 0, -100, -300)
                "Vocal Clear" -> shortArrayOf(-300, -100, 500, 600, 300)
                "Rock" -> shortArrayOf(500, 300, -200, 300, 600)
                "Pop" -> shortArrayOf(-200, 100, 400, 100, -200)
                "Classical" -> shortArrayOf(400, 200, 0, 200, 300)
                else -> shortArrayOf(0, 0, 0, 0, 0)
            }
            
            val levelRange = eq.bandLevelRange
            val minLevel = levelRange[0]
            val maxLevel = levelRange[1]
            
            for (i in 0 until minOf(numBands.toInt(), gains.size)) {
                val targetLevel = gains[i].coerceIn(minLevel, maxLevel)
                eq.setBandLevel(i.toShort(), targetLevel)
            }
            
            if (!eq.enabled) {
                eq.enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun onSessionChanged(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        
        // Release previous effects for the old session
        releaseAll()
        currentSessionId = audioSessionId
        
        // Re-apply active effects on the new session
        if (currentBoostPercent > 100) {
            setVolumeBoost(currentSessionId, currentBoostPercent)
        }
        if (currentPreset != "Normal") {
            applyPreset(currentSessionId, currentPreset)
        }
    }

    @Synchronized
    fun releaseAll() {
        releaseLoudnessEnhancer()
        releaseEqualizer()
        currentSessionId = -1
    }

    private fun getOrInitLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancer? {
        if (loudnessEnhancer == null || currentSessionId != audioSessionId) {
            releaseLoudnessEnhancer()
            try {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return loudnessEnhancer
    }

    private fun getOrInitEqualizer(audioSessionId: Int): Equalizer? {
        if (equalizer == null || currentSessionId != audioSessionId) {
            releaseEqualizer()
            try {
                // Priority 0, session audioSessionId
                equalizer = Equalizer(0, audioSessionId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return equalizer
    }

    private fun releaseLoudnessEnhancer() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loudnessEnhancer = null
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        equalizer = null
    }
}
