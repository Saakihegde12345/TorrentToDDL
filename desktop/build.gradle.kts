import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}
version = "1.0.0"
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
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    if (macArm64) {
        runtimeOnly(files(armNativeJar))
    } else {
        runtimeOnly("org.libtorrent4j:libtorrent4j-$nativeHost:2.1.0-31")
    }
    testImplementation("junit:junit:4.13.2")
}
compose.desktop {
    application {
        mainClass = "com.example.torrenttoddl.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "TorrentToDDL"
            packageVersion = "1.0.0"
            description = "Local torrent-to-HTTP gateway — desktop preview"
            vendor = "TorrentToDDL"
            includeAllModules = true
            windows {
                menuGroup = "TorrentToDDL"
                upgradeUuid = "0830a43f-d4bf-4d9e-84ad-74ec740756f4"
                shortcut = true
            }
            macOS { bundleID = "com.example.torrenttoddl.desktop" }
        }
    }
}
tasks.register<JavaExec>("smokeTest") {
    dependsOn(tasks.classes)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.torrenttoddl.desktop.MainKt")
    args("--smoke-test")
}
tasks.test { useJUnit() }
