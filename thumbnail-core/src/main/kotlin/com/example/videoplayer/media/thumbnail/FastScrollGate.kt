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
    private var previousLines = IntArray(MAX_VISIBLE_LINES)
    private var previousOffsets = IntArray(MAX_VISIBLE_LINES)
    private var previousSizes = IntArray(MAX_VISIBLE_LINES)
    private var previousCount = 0
    private var currentLines = IntArray(MAX_VISIBLE_LINES)
    private var currentOffsets = IntArray(MAX_VISIBLE_LINES)
    private var currentSizes = IntArray(MAX_VISIBLE_LINES)
    private var currentCount = 0

    fun beginSample() {
        currentCount = 0
    }

    fun addVisibleLine(line: Int, mainAxisOffset: Int, mainAxisSize: Int) {
        if (line < 0 || mainAxisSize <= 0) return
        for (index in 0 until currentCount) {
            if (currentLines[index] == line) {
                currentOffsets[index] = minOf(currentOffsets[index], mainAxisOffset)
                currentSizes[index] = maxOf(currentSizes[index], mainAxisSize)
                return
            }
        }
        if (currentCount == MAX_VISIBLE_LINES) return
        currentLines[currentCount] = line
        currentOffsets[currentCount] = mainAxisOffset
        currentSizes[currentCount] = mainAxisSize
        currentCount++
    }

    fun endSample(elapsedSeconds: Float, mainAxisSpacing: Int): Float {
        val elapsed = elapsedSeconds.takeIf { it.isFinite() && it > 0f }
        val distance = if (elapsed == null || previousCount == 0 || currentCount == 0) {
            0f
        } else {
            sharedLineDistance() ?: disjointLineDistance(mainAxisSpacing.coerceAtLeast(0))
        }
        commitSample()
        return if (elapsed == null) 0f else abs(distance) / elapsed
    }

    fun reset() {
        previousCount = 0
        currentCount = 0
    }

    private fun sharedLineDistance(): Float? {
        for (currentIndex in 0 until currentCount) {
            for (previousIndex in 0 until previousCount) {
                if (currentLines[currentIndex] == previousLines[previousIndex]) {
                    return (previousOffsets[previousIndex] - currentOffsets[currentIndex]).toFloat()
                }
            }
        }
        return null
    }

    private fun disjointLineDistance(mainAxisSpacing: Int): Float {
        val previousFirst = indexOfSmallestLine(previousLines, previousCount)
        val previousLast = indexOfLargestLine(previousLines, previousCount)
        val currentFirst = indexOfSmallestLine(currentLines, currentCount)
        val currentLast = indexOfLargestLine(currentLines, currentCount)
        return when {
            currentLines[currentFirst] > previousLines[previousLast] -> {
                val missingLines = currentLines[currentFirst] - previousLines[previousLast] - 1
                val estimatedLineStride = averageVisibleLineSize() + mainAxisSpacing
                previousOffsets[previousLast] + previousSizes[previousLast] + mainAxisSpacing +
                    missingLines * estimatedLineStride - currentOffsets[currentFirst]
            }
            currentLines[currentLast] < previousLines[previousFirst] -> {
                val missingLines = previousLines[previousFirst] - currentLines[currentLast] - 1
                val estimatedLineStride = averageVisibleLineSize() + mainAxisSpacing
                previousOffsets[previousFirst] -
                    (currentOffsets[currentLast] + currentSizes[currentLast] + mainAxisSpacing +
                        missingLines * estimatedLineStride)
            }
            else -> 0f
        }.toFloat()
    }

    private fun averageVisibleLineSize(): Int {
        var total = 0L
        for (index in 0 until previousCount) total += previousSizes[index]
        for (index in 0 until currentCount) total += currentSizes[index]
        return (total / (previousCount + currentCount).coerceAtLeast(1)).toInt()
    }

    private fun commitSample() {
        previousLines = currentLines.also { currentLines = previousLines }
        previousOffsets = currentOffsets.also { currentOffsets = previousOffsets }
        previousSizes = currentSizes.also { currentSizes = previousSizes }
        previousCount = currentCount
        currentCount = 0
    }

    private fun indexOfSmallestLine(lines: IntArray, count: Int): Int {
        var result = 0
        for (index in 1 until count) {
            if (lines[index] < lines[result]) result = index
        }
        return result
    }

    private fun indexOfLargestLine(lines: IntArray, count: Int): Int {
        var result = 0
        for (index in 1 until count) {
            if (lines[index] > lines[result]) result = index
        }
        return result
    }

    private companion object {
        const val MAX_VISIBLE_LINES = 128
    }
}
