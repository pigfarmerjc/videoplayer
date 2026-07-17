package com.example.videoplayer.performance

import androidx.tracing.trace

object MediaTrace {
    inline fun <T> section(name: String, crossinline block: () -> T): T = trace(name, block)
}
