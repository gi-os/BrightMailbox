package com.gios.brightmailbox.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The LightOS design system, ported.
 *
 * These are not "a black and white theme" — they are the actual numbers out of
 * lightphone/light-sdk's `sdk/ui` module, which is MIT licensed. A plain sideloaded APK
 * can reproduce LightOS exactly instead of approximating it, and the difference is
 * obvious on the panel.
 *
 * Three rules that are easy to get wrong:
 *
 *  - Units scale on WIDTH, type scales on HEIGHT. `gridUnit = screenWidthDp / 27`, and
 *    `sp = designPx * screenHeightDp / 600`. Mixing them makes bars and text drift apart
 *    on any screen that is not exactly the LP3's.
 *  - Secondary is a COLOUR (#BBBBBB), never white at 45% alpha. Alpha on a matte
 *    monochrome LCD dithers; a flat grey does not.
 *  - No ripples anywhere, and the haptic fires on finger-DOWN, not on click.
 */

val Background = Color(0xFF000000)
val Content = Color(0xFFFFFFFF)
val Secondary = Color(0xFFBBBBBB)

/** 27 wide x 31 tall. Every inset, bar height and icon size is expressed in these. */
class Grid(val unit: Dp) {
    operator fun times(n: Float): Dp = unit * n
    val inset: Dp get() = unit * 1f
    val topBar: Dp get() = unit * 3f
    val actionBar: Dp get() = unit * 4f
    val icon: Dp get() = unit * 2f
}

val LocalGrid = staticCompositionLocalOf { Grid(15.dp) }
val LocalType = staticCompositionLocalOf { Type(1f) }

/**
 * The named scale. Design pixels are LP3 values at a 600 dp reference height; the factor
 * on this device is `screenHeightDp / 600` (0.787 at 472 dp).
 */
class Type(private val k: Float) {
    private fun sp(px: Float): TextUnit = (px * k).sp

    val title = TextStyle(fontSize = sp(115f), lineHeight = sp(120f), fontWeight = FontWeight.Normal)
    val subtitle = TextStyle(fontSize = sp(52f), fontWeight = FontWeight.Normal)
    val heading = TextStyle(fontSize = sp(38f), fontWeight = FontWeight.Normal)
    val subheading = TextStyle(fontSize = sp(30f), letterSpacing = sp(0.9f), fontWeight = FontWeight.Normal)
    val copy = TextStyle(fontSize = sp(30f), fontWeight = FontWeight.Normal)

    /** 15% tracking. This is what makes a bar label read as a button without a box. */
    val button = TextStyle(fontSize = sp(30f), letterSpacing = sp(4.5f), fontWeight = FontWeight.Normal)

    /**
     * Body copy. Leading is locked to exactly two grid units by [readerLeading] — see
     * the note there; it is the reason a wheel notch never half-clips a line.
     */
    val paragraph = TextStyle(fontSize = sp(24.5f), fontWeight = FontWeight.Normal)
    val detail = TextStyle(fontSize = sp(20f), fontWeight = FontWeight.Normal)
    val fine = TextStyle(fontSize = sp(25f), fontWeight = FontWeight.Normal)
    val superfine = TextStyle(fontSize = sp(16f), fontWeight = FontWeight.Normal)
}

/**
 * Body leading, in sp, pinned to two grid units.
 *
 * Every line of every Letter then lands on the same set of baselines, so one wheel notch
 * moves the text a whole number of lines and nothing is ever half-clipped at the fold.
 * This has to be MEASURED from the grid rather than set as a `lineHeight` multiplier —
 * a multiplier drifts against the grid as the font scale changes and the clipping comes
 * straight back.
 */
@Composable
fun readerLeading(): TextUnit {
    val g = LocalGrid.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) { (g.unit * 2f).toSp() }
}

@Composable
fun LightTheme(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val grid = Grid((cfg.screenWidthDp / 27f).dp)
    val type = Type(cfg.screenHeightDp / 600f)
    CompositionLocalProvider(
        LocalGrid provides grid,
        LocalType provides type,
        LocalContentColor provides Content,
        LocalTextStyle provides type.copy.copy(color = Content),
        LocalIndication provides NoIndication,
    ) {
        Box(Modifier.fillMaxSize().background(Background)) { content() }
    }
}

/** No ripple, no pressed state, no hover. LightOS has none of them. */
private object NoIndication : androidx.compose.foundation.Indication {
    @Deprecated("Indication#rememberUpdatedInstance is deprecated")
    @Composable
    override fun rememberUpdatedInstance(
        interactionSource: androidx.compose.foundation.interaction.InteractionSource,
    ): androidx.compose.foundation.IndicationInstance =
        object : androidx.compose.foundation.IndicationInstance {
            override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.drawIndication() =
                drawContent()
        }
}

/**
 * A tap target the LightOS way: fires on finger-down with a 45 ms buzz, and draws
 * nothing at all in response.
 */
@Composable
fun Modifier.lightClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    return this.clickable(
        enabled = enabled,
        interactionSource = androidx.compose.runtime.remember {
            androidx.compose.foundation.interaction.MutableInteractionSource()
        },
        indication = null,
    ) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/* ------------------------------------------------------------------ text helpers */

@Composable
fun T(
    text: String,
    style: TextStyle,
    color: Color = Content,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = Text(
    text = text,
    style = if (lineHeight == TextUnit.Unspecified) style else style.copy(lineHeight = lineHeight),
    color = color,
    modifier = modifier,
    maxLines = maxLines,
    overflow = overflow,
    fontFamily = FontFamily.SansSerif,
)

/** A screen with the standard one-unit side inset. */
@Composable
fun Screen(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val g = LocalGrid.current
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = g.inset),
        content = content,
    )
}
