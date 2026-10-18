package dev.vaayil.core.ui

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The Vellum palette (DESIGN.md): a warm paper board — `Canvas` — deepened by up
 * to three "film" steps (`Container1..5` are one through five films in the full
 * ladder; nesting in content caps at three). Ink, boundaries and accents are
 * static constants; the one-film invariant (OKLCH dL -0.0206, dC +0.0050, hue 85)
 * is pinned by `VellumTokensTest`, never computed at runtime.
 */
object VellumColors {
    /** The bare board; also every lifted sheet. */
    val Canvas = Color(0xFFFBFAF7)

    /** One film: cards, row groups. */
    val Container1 = Color(0xFFF6F3EC)

    /** Two films: nested blocks, badges. */
    val Container2 = Color(0xFFF1ECE1)

    /** Three films: wells, hero seat. */
    val Container3 = Color(0xFFECE5D7)

    /** Four films: segment track, switch off-track (deepest nesting used by controls). */
    val Container4 = Color(0xFFE7DECD)

    /** Five films: the floor; content nesting stops at three. */
    val Container5 = Color(0xFFE1D7C3)

    val OnSurface = Color(0xFF23221D)
    val OnSurfaceVariant = Color(0xFF57544A)

    /** 1dp dividers — decorative only (1.41:1), never load-bearing. */
    val Hairline = Color(0xFFDDD4C2)

    /** 1dp closed cut edge (full perimeter since the 2026-09-21 device-fix) — decorative only (1.51:1). */
    val Seam = Color(0xFFCEC2A9)

    /** Every load-bearing boundary: 3.31:1+ against every ladder step. */
    val Stroke3 = Color(0xFF7C7264)

    /** Sage means sanctuary: active, verified, primary affirmations. */
    val Primary = Color(0xFF3E5A42)
    val PrimaryContainer = Color(0xFFCFE0CE)
    val OnPrimaryContainer = Color(0xFF22371F)

    /** Sparkline stroke, active dot, static connected ring. */
    val SageMark = Color(0xFF4A7350)

    /** Transport data. */
    val Slate = Color(0xFF3F5C78)

    /** DNS / advanced accents. */
    val Lavender = Color(0xFF5A4E78)

    /** Connected surface (status pill, hero window). */
    val FieldActive = Color(0xFFDCE8D9)
    val OnFieldActive = Color(0xFF28402C)

    /** Disconnected surface. */
    val FieldIdle = Color(0xFFEAE4D8)

    /** Idle state dot (= stroke-3, 3.73:1 on field-idle — not a mid grey). */
    val IdleDot = Color(0xFF7C7264)

    val Warning = Color(0xFFF3E2C3)
    val OnWarning = Color(0xFF5E4218)

    /** Warning stroke only — never text (3.54:1 on canvas). */
    val WarningMark = Color(0xFFB9762B)

    val Error = Color(0xFFF7DBD4)
    val OnError = Color(0xFF7B241C)

    /** Error stroke and error ring. */
    val ErrorMark = Color(0xFFA93A31)

    /** The M3 `error` slot: inline field errors. */
    val M3Error = Color(0xFFBA1A1A)

    /** Avatar pastels, cycled by profile id. */
    val Pastel1 = Color(0xFFDCE8D9)
    val OnPastel1 = Color(0xFF2A3F2E)
    val Pastel2 = Color(0xFFD8E3EE)
    val OnPastel2 = Color(0xFF233A4C)
    val Pastel3 = Color(0xFFE2DCEC)
    val OnPastel3 = Color(0xFF332C48)

    /** Dialog and dropdown backdrop: #23221D @ 44%. */
    val Scrim = Color(0x7023221D)

    /** Disabled content (on-surface-variant @ 72%); exempt from 1.4.3, meets 3:1 anyway. */
    val Disabled = Color(0xFF85827A)

    /** Canvas plus the five film steps, lightest to darkest. */
    val ladder: List<Color> = listOf(Canvas, Container1, Container2, Container3, Container4, Container5)
}

/**
 * Schibsted Grotesk (OFL-1.1; statics bundled in this module's res/font, notice in
 * docs/fonts-schibsted-grotesk.md): the neo-grotesque face of Vellum. Weights 400-700
 * only — the scale never exceeds 600 except the 11sp label at 700.
 */
val Schibsted: FontFamily = FontFamily(
    Font(R.font.schibsted_grotesk_regular, FontWeight.Normal),
    Font(R.font.schibsted_grotesk_medium, FontWeight.Medium),
    Font(R.font.schibsted_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.schibsted_grotesk_bold, FontWeight.Bold),
)

/** Live telemetry counters: tabular figures so digits never jitter (DESIGN.md §3). */
val VellumTelemetryNumStyle = TextStyle(
    fontFamily = Schibsted,
    fontWeight = FontWeight.SemiBold,
    fontSize = 21.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.004).em,
    fontFeatureSettings = "tnum",
)

/**
 * Bottom-nav labels (DESIGN.md §5 Navigation): 12sp/16sp, +0.010em tracking,
 * sentence case on Schibsted. Weight is per state at the call site — 600 selected,
 * 500 unselected (the base carries 500).
 */
val VellumNavLabelStyle = TextStyle(
    fontFamily = Schibsted,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.010.em,
)

/**
 * A count of paper films laid over the board. Nesting is capped at three so no
 * text ever lands under a four-times-darkened surface; `Sheet` (Task 3) enforces
 * the *cumulative* depth, not the local parameter.
 */
@JvmInline
value class Films private constructor(val count: Int) {
    companion object {
        val Board = Films(0)
        val One = Films(1)
        val Two = Films(2)
        val Three = Films(3)

        fun of(n: Int): Films {
            require(n in 0..3) { "Vellum caps nesting at 3 films (got $n)" }
            return Films(n)
        }
    }
}

val LocalFilms = compositionLocalOf { Films.Board }

/** True when the user has removed animations (animator duration scale == 0). */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads `Settings.Global.ANIMATOR_DURATION_SCALE` — what both "Remove animations"
 * and the developer-options animation scale write — and reacts to changes live.
 * Every `rememberInfiniteTransition` and `animate*` call site must honor it.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return produceState(animatorScale(resolver) == 0f, resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { value = animatorScale(resolver) == 0f }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }.value
}

private fun animatorScale(r: ContentResolver) =
    Settings.Global.getFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
