package com.example.videoplayer.playback

import java.util.Locale

enum class PcmSampleEncoding {
    INTEGER,
    FLOAT
}

enum class PcmByteOrder {
    UNSPECIFIED,
    LITTLE_ENDIAN,
    BIG_ENDIAN
}

data class AudioFormatDescriptor(
    val mimeType: String?,
    val pcmEncoding: PcmSampleEncoding? = null,
    val bitDepth: Int? = null,
    val isSigned: Boolean? = null,
    val byteOrder: PcmByteOrder = PcmByteOrder.UNSPECIFIED
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
            isSigned = true,
            byteOrder = byteOrder
        )
    }
}

object PcmCompatibilityPolicy {
    fun choose(format: AudioFormatDescriptor): EngineChoice {
        if (!format.isConsistentPcmDescriptor()) {
            return EngineChoice.EXO
        }
        val needsVlc = when (format.pcmEncoding) {
            PcmSampleEncoding.FLOAT -> format.bitDepth == 32
            PcmSampleEncoding.INTEGER -> format.bitDepth == 24 || format.bitDepth == 32
            null -> false
        }
        return if (needsVlc) EngineChoice.VLC else EngineChoice.EXO
    }

    private fun AudioFormatDescriptor.isConsistentPcmDescriptor(): Boolean {
        val normalizedMimeType = mimeType?.lowercase(Locale.ROOT)
        return pcmEncoding != null &&
            isSigned == true &&
            byteOrder == PcmByteOrder.LITTLE_ENDIAN &&
            normalizedMimeType in setOf(
            "audio/raw",
            "audio/pcm",
            "audio/x-pcm"
        )
    }
}
