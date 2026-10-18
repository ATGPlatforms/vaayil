package dev.vaayil.core.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

const val DEFAULT_MAX_INPUT_BYTES = 1_048_576 // 1 MiB: a wg-quick config or a snapshot is far smaller

class InputTooLargeException(val maxBytes: Int) : IOException("input exceeds $maxBytes bytes")

/** Reads [input] as UTF-8, failing with [InputTooLargeException] once more than [maxBytes] arrive. */
fun readBoundedText(input: InputStream, maxBytes: Int = DEFAULT_MAX_INPUT_BYTES): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw InputTooLargeException(maxBytes)
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}
