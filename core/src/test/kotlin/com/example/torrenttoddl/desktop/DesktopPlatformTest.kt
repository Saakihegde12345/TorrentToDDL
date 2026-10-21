package com.example.torrenttoddl.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopPlatformTest {
    @Test fun mapsDesktopArchitecturesWithoutUsingRosetta() {
        assertEquals(DesktopPlatform("macos", "arm64", ".dylib"), desktopPlatform("Mac OS X", "aarch64"))
        assertEquals(DesktopPlatform("macos", "arm64", ".dylib"), desktopPlatform("Mac OS X", "arm64"))
        assertEquals(DesktopPlatform("macos", "x86_64", ".dylib"), desktopPlatform("Mac OS X", "x86_64"))
        assertEquals(DesktopPlatform("windows", "x86_64", ".dll"), desktopPlatform("Windows 11", "amd64"))
        assertEquals(DesktopPlatform("linux", "x86_64", ".so"), desktopPlatform("Linux", "amd64"))
        assertThrows(IllegalArgumentException::class.java) { desktopPlatform("Linux", "aarch64") }
        assertThrows(IllegalStateException::class.java) { desktopPlatform("Mac OS X", "ppc") }
    }
}
