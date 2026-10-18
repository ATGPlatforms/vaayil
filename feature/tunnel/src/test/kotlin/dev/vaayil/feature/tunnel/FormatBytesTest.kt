package dev.vaayil.feature.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatBytesTest {
    @Test
    fun `byte boundaries land on correct unit`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.0 MiB", formatBytes(1048576))
        assertEquals("1.0 GiB", formatBytes(1073741824))
    }

    @Test
    fun `fractional values keep one decimal and round large ones`() {
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("150 MiB", formatBytes(150L * 1024 * 1024))
    }
}
