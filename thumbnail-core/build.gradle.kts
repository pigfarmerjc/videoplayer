plugins {
    kotlin("jvm")
}

layout.buildDirectory.set(file("${System.getProperty("java.io.tmpdir")}/videoplayer-thumbnail-core"))

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
