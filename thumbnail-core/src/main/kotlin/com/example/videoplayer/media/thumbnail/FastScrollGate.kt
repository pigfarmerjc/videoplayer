package com.example.videoplayer.media.thumbnail

class FastScrollGate(
    private val enterVelocity: Float = 1_800f,
    private val exitVelocity: Float = 700f,
    private val slowSamplesToExit: Int = 2
) {
    private var fast = false
    private var slowSamples = 0

    fun update(isScrollInProgress: Boolean, velocity: Float): Boolean {
        if (!isScrollInProgress) {
            fast = false
            slowSamples = 0
            return false
        }
        val safeVelocity = velocity.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        if (!fast) {
            fast = safeVelocity >= enterVelocity
            return fast
        }
        if (safeVelocity <= exitVelocity) {
            slowSamples++
            if (slowSamples >= slowSamplesToExit) fast = false
        } else {
            slowSamples = 0
        }
        return fast
    }
}
