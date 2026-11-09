package com.shazan.airbridge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak

/**
 * Type scale. MASTER.md names Plus Jakarta Sans; we use the system sans (its "system font,
 * utility" mood) to avoid bundling or downloading a font. Body ≥ 14 sp, line height ≈ 1.4–1.5.
 */
private val Sans = FontFamily.Default

// Whole words only (no hyphenation: it split names like "AirBridge" across lines). Paragraph breaking keeps
// lines even; headings balance their lines. Screens never get narrower than 360 dp (see usableDensity),
// so whole words always fit.
private fun TextStyle.para() = copy(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.None)
private fun TextStyle.heading() = copy(lineBreak = LineBreak.Heading, hyphens = Hyphens.None)

val Typography = Typography(
    displayMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = 4.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp).heading(),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp).heading(),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp).heading(),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp).heading(),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp).para(),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp).para(),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp).para(),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp).para(),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp).para(),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp).para(),
)

/**
 * Indian (and other non-Latin) scripts: no extra letter spacing — it splits the headline (shirorekha)
 * that joins Devanagari, Bengali and Gujarati letters and breaks conjuncts apart — and roomier lines,
 * because vowel signs sit above and below the letters (ि ी ु ू ं ृ) and collide with tight Latin line heights.
 */
fun typographyFor(locale: java.util.Locale): Typography {
    if (usesLatinScript(locale)) return Typography
    fun TextStyle.script() = copy(
        letterSpacing = 0.sp,
        lineHeight = (fontSize.value * 1.6f).sp,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
    )
    val t = Typography
    return t.copy(
        headlineSmall = t.headlineSmall.script(), titleLarge = t.titleLarge.script(), titleMedium = t.titleMedium.script(),
        titleSmall = t.titleSmall.script(), bodyLarge = t.bodyLarge.script(), bodyMedium = t.bodyMedium.script(),
        bodySmall = t.bodySmall.script(), labelLarge = t.labelLarge.script(), labelMedium = t.labelMedium.script(),
        labelSmall = t.labelSmall.script(),
    )
}

private fun usesLatinScript(locale: java.util.Locale): Boolean {
    val script = android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale.forLocale(locale)).script
    return script.isEmpty() || script == "Latn"
}

