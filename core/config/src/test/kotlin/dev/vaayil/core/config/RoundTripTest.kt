package dev.vaayil.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class RoundTripTest {
    private val parser = WgConfigParser()

    @Test
    fun `parse serialize parse is identity for accepted subset`() {
        for (name in listOf("realworld1", "realworld2")) {
            val original = javaClass.getResource("/golden/$name.conf")!!.readText()
            val once = parser.parse(original)
            val twice = parser.parse(WgConfigSerializer.serialize(once))
            // Ignored DNS entries (search domains) are by definition outside the accepted
            // subset: the serializer drops them, so the round trip drops them too.
            assertEquals(once.copy(ignoredDnsEntries = emptyList()), twice)
        }
    }
}
