package com.example.videoplayer.media.thumbnail

import kotlin.math.abs

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

class GridScrollVelocityTracker {
    private var previousIndex: Int? = null
    private var previousOffset = 0
    private var previousColumns = 1

    fun update(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        columns: Int,
        averageLineSizePx: Float,
        elapsedSeconds: Float
    ): Float {
        val safeColumns = columns.coerceAtLeast(1)
        val previous = previousIndex
        if (previous == null || previousColumns != safeColumns) {
            remember(firstVisibleItemIndex, firstVisibleItemScrollOffset, safeColumns)
            return 0f
        }

        val lineSize = averageLineSizePx.takeIf { it.isFinite() && it > 0f }
        val elapsed = elapsedSeconds.takeIf { it.isFinite() && it > 0f }
        val velocity = if (lineSize == null || elapsed == null) {
            0f
        } else {
            val previousRow = previous / safeColumns
            val currentRow = firstVisibleItemIndex / safeColumns
            val rowDistance = (currentRow - previousRow) * lineSize
            val offsetDistance = firstVisibleItemScrollOffset - previousOffset
            abs(rowDistance + offsetDistance) / elapsed
        }

        remember(firstVisibleItemIndex, firstVisibleItemScrollOffset, safeColumns)
        return velocity
    }

    fun reset() {
        previousIndex = null
        previousOffset = 0
        previousColumns = 1
    }

    private fun remember(index: Int, offset: Int, columns: Int) {
        previousIndex = index
        previousOffset = offset
        previousColumns = columns
    }
}
