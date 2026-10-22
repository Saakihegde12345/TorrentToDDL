import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}
version = "1.1.0"
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
        jvmArgs("-Xmx1024m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "TorrentToDDL"
            packageVersion = "1.1.0"
            description = "Torrent download workspace and authenticated HTTP gateway"
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
tasks.test {
    useJUnit()
    systemProperty("torrenttoddl.worker.classpath", sourceSets["main"].runtimeClasspath.asPath)
}

val prepareWindowsLauncher by tasks.registering {
    doLast {
        // jpackage marks its Windows launcher read-only. Clear that attribute
        // before Compose replaces an older bundled app during a rebuild.
        val launcher = layout.buildDirectory.file("compose/binaries/main/app/TorrentToDDL/TorrentToDDL.exe").get().asFile
        if (nativeHost == "windows" && launcher.exists()) Files.setAttribute(launcher.toPath(), "dos:readonly", false)
    }
}
tasks.matching { it.name == "createDistributable" }.configureEach { dependsOn(prepareWindowsLauncher) }

val verifyWindowsApp by tasks.registering(Exec::class) {
    dependsOn("createDistributable")
    executable(layout.buildDirectory.file(
        "compose/binaries/main/app/TorrentToDDL/TorrentToDDL.exe"
    ).get().asFile.absolutePath)
    args("--smoke-test")
    onlyIf { nativeHost == "windows" }
}

tasks.register<Zip>("packageWindowsPortable") {
    dependsOn(":core:test", "test", verifyWindowsApp)
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
    archiveFileName.set("TorrentToDDL-windows-x64.zip")
    doFirst { check(nativeHost == "windows") { "Build Windows packages on Windows." } }
}

tasks.register<Sync>("headlessDistribution") {
    dependsOn(tasks.jar)
    from(configurations.runtimeClasspath) { into("lib") }
    from(tasks.jar) { into("lib") }
    from(rootProject.file("scripts/run-server.sh"))
    into(layout.buildDirectory.dir("headless"))
}
