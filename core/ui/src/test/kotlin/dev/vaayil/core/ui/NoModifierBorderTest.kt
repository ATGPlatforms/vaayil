package dev.vaayil.core.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Film Rule bans `Modifier.border` in `:core:ui`, every feature module and
 * `:app` (DESIGN.md do's/don'ts; spec §3.3.2): boundaries are 1dp seams drawn by
 * [vellumSeam]/`vellumBoundary` (`Sheet.kt`, one closed perimeter via `drawBehind`)
 * or the
 * documented M3 `border` slot of the dropdown menu surface — never a full
 * perimeter border modifier. This test walks the UI sources and fails on any
 * `.border(` modifier call outside `Sheet.kt`, the seam's own file.
 */
class NoModifierBorderTest {

    /** Regex matches both `Modifier.border(` and chained `.border(` calls. */
    private val borderCall = Regex("""\.border\(""")
    private val exemptFiles = setOf("Sheet.kt")

    private fun repoRoot(): File {
        var dir: File = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: break
        }
        check(File(dir, "settings.gradle.kts").exists()) {
            "could not locate the repo root from ${System.getProperty("user.dir")}"
        }
        return dir
    }

    private fun sourceFiles(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `no Modifier border outside the seam implementation`() {
        val root = repoRoot()
        val roots = listOf(
            File(root, "core/ui/src/main"),
            File(root, "app/src/main"),
        ) + File(root, "feature").listFiles { f -> f.isDirectory }.orEmpty()
            .map { File(it, "src/main") }
            .filter { it.exists() }

        val offenders = roots.flatMap { sourceFiles(it) }
            .filter { it.name !in exemptFiles }
            .mapNotNull { file ->
                val lines = file.readLines()
                val hits = lines.withIndex()
                    .filter { (_, line) -> borderCall.containsMatchIn(line) }
                    .map { (index, line) -> "  ${file.relativeTo(root).path}:${index + 1}: ${line.trim()}" }
                if (hits.isEmpty()) null else hits
            }
            .flatten()

        assertTrue(
            offenders.isEmpty(),
            "Modifier.border is banned outside Sheet.kt (vellumSeam) — found:\n" +
                offenders.joinToString("\n"),
        )
    }

}
