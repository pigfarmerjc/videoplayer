package com.example.videoplayer.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import kotlin.math.roundToInt

class SimpleGifEncoder(
    private val output: OutputStream,
    private val width: Int,
    private val height: Int,
    private val delayMs: Int,
    private val repeat: Int = 0
) : Closeable {
    private var started = false
    private var closed = false
    private val palette = ByteArray(256 * 3)

    init {
        var p = 0
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    palette[p++] = (r * 51).toByte()
                    palette[p++] = (g * 51).toByte()
                    palette[p++] = (b * 51).toByte()
                }
            }
        }
    }

    fun addFrame(bitmap: Bitmap) {
        check(!closed) { "Encoder is closed" }
        val frame = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        if (!started) {
            writeHeader()
            started = true
        }
        writeGraphicControlExtension()
        writeImageDescriptor()
        writePixels(frame)
        if (frame !== bitmap) frame.recycle()
    }

    override fun close() {
        if (!closed) {
            if (!started) writeHeader()
            output.write(0x3B)
            output.flush()
            closed = true
        }
    }

    private fun writeHeader() {
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        output.write(0xF7)
        output.write(0)
        output.write(0)
        output.write(palette)
        writeNetscapeExtension()
    }

    private fun writeNetscapeExtension() {
        output.write(0x21)
        output.write(0xFF)
        output.write(11)
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(3)
        output.write(1)
        writeShort(repeat)
        output.write(0)
    }

    private fun writeGraphicControlExtension() {
        output.write(0x21)
        output.write(0xF9)
        output.write(4)
        output.write(0)
        writeShort((delayMs / 10).coerceAtLeast(1))
        output.write(0)
        output.write(0)
    }

    private fun writeImageDescriptor() {
        output.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        output.write(0)
    }

    private fun writePixels(bitmap: Bitmap) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val indexed = ByteArray(pixels.size)
        for (i in pixels.indices) indexed[i] = quantize(pixels[i]).toByte()
        output.write(8)
        val encoded = lzwEncode(indexed)
        var offset = 0
        while (offset < encoded.size) {
            val size = minOf(255, encoded.size - offset)
            output.write(size)
            output.write(encoded, offset, size)
            offset += size
        }
        output.write(0)
    }

    private fun quantize(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val ri = (r * 5f / 255f).roundToInt().coerceIn(0, 5)
        val gi = (g * 5f / 255f).roundToInt().coerceIn(0, 5)
        val bi = (b * 5f / 255f).roundToInt().coerceIn(0, 5)
        return ri * 36 + gi * 6 + bi
    }

    private fun lzwEncode(indices: ByteArray): ByteArray {
        val minCodeSize = 8
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var nextCode = endCode + 1
        var codeSize = minCodeSize + 1
        val maxCode = 4095
        val dictionary = HashMap<Long, Int>(4096)
        val writer = BitWriter()

        fun resetDictionary() {
            dictionary.clear()
            nextCode = endCode + 1
            codeSize = minCodeSize + 1
        }

        writer.write(clearCode, codeSize)
        var prefix = indices.first().toInt() and 0xFF
        for (i in 1 until indices.size) {
            val value = indices[i].toInt() and 0xFF
            val key = (prefix.toLong() shl 8) or value.toLong()
            val existing = dictionary[key]
            if (existing != null) {
                prefix = existing
            } else {
                writer.write(prefix, codeSize)
                if (nextCode <= maxCode) {
                    dictionary[key] = nextCode++
                    if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    writer.write(clearCode, codeSize)
                    resetDictionary()
                }
                prefix = value
            }
        }
        writer.write(prefix, codeSize)
        writer.write(endCode, codeSize)
        return writer.finish()
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var bitCount = 0

        fun write(code: Int, size: Int) {
            current = current or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                out.write(current and 0xFF)
                current = current ushr 8
                bitCount -= 8
            }
        }

        fun finish(): ByteArray {
            if (bitCount > 0) out.write(current and 0xFF)
            return out.toByteArray()
        }
    }
}
