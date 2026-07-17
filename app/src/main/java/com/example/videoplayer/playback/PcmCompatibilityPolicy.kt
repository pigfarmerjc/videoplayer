package com.example.videoplayer.playback

import java.util.Locale

enum class PcmSampleEncoding {
    INTEGER,
    FLOAT
}

enum class PcmByteOrder {
    LITTLE_ENDIAN,
    BIG_ENDIAN
}

data class AudioFormatDescriptor(
    val mimeType: String?,
    val pcmEncoding: PcmSampleEncoding? = null,
    val bitDepth: Int? = null,
    val isSigned: Boolean = true,
    val byteOrder: PcmByteOrder = PcmByteOrder.LITTLE_ENDIAN
) {
    companion object {
        fun pcmInteger(
            bitDepth: Int,
            isSigned: Boolean = true,
            byteOrder: PcmByteOrder = PcmByteOrder.LITTLE_ENDIAN
        ) = AudioFormatDescriptor(
            mimeType = "audio/raw",
            pcmEncoding = PcmSampleEncoding.INTEGER,
            bitDepth = bitDepth,
            isSigned = isSigned,
            byteOrder = byteOrder
        )

        fun pcmFloat(
            bitDepth: Int,
            byteOrder: PcmByteOrder = PcmByteOrder.LITTLE_ENDIAN
        ) = AudioFormatDescriptor(
            mimeType = "audio/raw",
            pcmEncoding = PcmSampleEncoding.FLOAT,
            bitDepth = bitDepth,
            byteOrder = byteOrder
        )
    }
}

object PcmCompatibilityPolicy {
    fun choose(format: AudioFormatDescriptor): EngineChoice {
        if (!format.isPcm() || format.byteOrder != PcmByteOrder.LITTLE_ENDIAN) {
            return EngineChoice.EXO
        }
        val needsVlc = when (format.pcmEncoding) {
            PcmSampleEncoding.FLOAT -> true
            PcmSampleEncoding.INTEGER -> format.isSigned && format.bitDepth in setOf(24, 32)
            null -> false
        }
        return if (needsVlc) EngineChoice.VLC else EngineChoice.EXO
    }

    private fun AudioFormatDescriptor.isPcm(): Boolean {
        val normalizedMimeType = mimeType?.lowercase(Locale.ROOT)
        return pcmEncoding != null && normalizedMimeType in setOf(
            "audio/raw",
            "audio/pcm",
            "audio/x-pcm",
            "audio/l24",
            "audio/l32"
        )
    }
}
