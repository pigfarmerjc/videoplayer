package com.example.videoplayer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ProductSurfaceContractTest {
    @Test
    fun standaloneAudioAndSearchAreAbsent() {
        val root = File(System.getProperty("user.dir"))
        val source = File(root, "src/main/java/com/example/videoplayer").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(source.contains("AudioPlayerScreen("))
        assertFalse(source.contains("READ_MEDIA_AUDIO"))
        assertFalse(source.contains("SearchBar("))
        assertFalse(source.contains("Icons.Default.Search"))
    }
}
