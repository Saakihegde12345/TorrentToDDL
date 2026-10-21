plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}
kotlin { jvmToolchain(17) }
val nativeHost = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows"
    System.getProperty("os.name").startsWith("Mac") -> "macos"
    else -> "linux"
}
val macArm64 = nativeHost == "macos" &&
    System.getProperty("os.arch").lowercase() in setOf("arm64", "aarch64")
val armNativeJar = rootProject.file(".native-build/macos-arm64/libtorrent4j-macos-arm64-2.1.0-31.jar")
dependencies {
    api("org.libtorrent4j:libtorrent4j:2.1.0-31")
    api("org.nanohttpd:nanohttpd:2.3.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation("junit:junit:4.13.2")
    if (macArm64) {
        testRuntimeOnly(files(armNativeJar))
    } else {
        testRuntimeOnly("org.libtorrent4j:libtorrent4j-$nativeHost:2.1.0-31")
    }
}
tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
