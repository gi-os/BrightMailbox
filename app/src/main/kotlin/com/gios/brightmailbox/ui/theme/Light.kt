package com.gios.brightmailbox.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
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
 *  - Secondary is a COLOR (#BBBBBB), never white at 45% alpha. Alpha on a matte
 *    monochrome LCD dithers; a flat gray does not.
 *  - No ripples anywhere, and the haptic fires on finger-DOWN, not on click.
 */

val Background = Color(0xFF000000)
val Content = Color(0xFFFFFFFF)
val Secondary = Color(0xFFBBBBBB)

/** 27 wide x 31 tall. Every inset, bar height and icon size is expressed in these. */
class Grid(val unit: Dp) {
    operator fun times(n: Float): Dp = unit * n
    val inset: Dp get() = unit * 1f
    /**
     * Three units of header over every screen was two units of title and one of air, on a
     * panel 31 units tall — a tenth of the screen spent saying where you already are. The
     * bar now fits its own type with the same proportion of air around it.
     */
    val topBar: Dp get() = unit * 2.2f
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

    /**
     * The word at the top of a screen: NOTICES, PARCELS, SETTINGS.
     *
     * Its own entry rather than [subheading], which is what it used to be, because a screen
     * title is not a heading over anything — there is exactly one per screen, it never
     * competes with body copy, and on a 3.9" panel every line it takes is a line of mail
     * nobody can see. At 30 px in a three-unit bar it was the largest thing on screens whose
     * whole content was the list underneath it.
     *
     * Smaller, with tracking to compensate: 15% is what makes a bar label read as a control
     * rather than as text, and the same trick keeps a 22 px word reading as a title instead
     * of as a caption.
     */
    val screenTitle = TextStyle(fontSize = sp(22f), letterSpacing = sp(2.4f), fontWeight = FontWeight.Normal)
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
    ) {
        Box(Modifier.fillMaxSize().background(Background)) { content() }
    }
}

/*
 * There is no custom Indication here on purpose.
 *
 * The obvious way to kill ripples globally is a no-op Indication provided through
 * LocalIndication, but Indication.rememberUpdatedInstance and IndicationInstance are
 * deprecated at ERROR level in current Compose. Every tap target in this app goes
 * through lightClickable, which passes indication = null directly, so nothing needs to
 * be provided globally and there is no deprecated API to carry.
 */

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

/**
 * The same target, with a hold on it.
 *
 * Separate from [lightClickable] rather than an optional parameter, because
 * `combinedClickable` costs a gesture detector on every row that uses it and most rows
 * have nothing to hold for.
 *
 * The two gestures buzz differently on purpose: a tap gets the light `TextHandleMove`
 * tick every control in the app gets, a hold gets `LongPress`, which is the heavier one.
 * That difference is the only confirmation the finger gets that the hold registered
 * before anything appears on the screen.
 */
@androidx.compose.runtime.Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.lightHoldable(
    enabled: Boolean = true,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
): Modifier {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    return this.combinedClickable(
        enabled = enabled,
        interactionSource = androidx.compose.runtime.remember {
            androidx.compose.foundation.interaction.MutableInteractionSource()
        },
        indication = null,
        onLongClick = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            onLongClick()
        },
    ) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/**
 * Slide a row sideways to do something to it, and let it go to change your mind.
 *
 * `detectHorizontalDragGestures` and not a drag on both axes, which matters more than it
 * looks: it waits for HORIZONTAL touch slop before claiming the pointer, so a finger
 * moving down the screen never reaches this and the list scrolls exactly as it did. A
 * two-axis detector would win the race half the time and the list would feel sticky.
 *
 * The row moves under the finger the whole way, because a gesture that does nothing until
 * it succeeds gives you no way to learn where the line is. Past [threshold] it commits and
 * the row is gone; short of it the row goes back where it was.
 *
 * Left only. A row that goes both ways has to explain which way means what, and there is
 * one verb here.
 */
@Composable
fun Modifier.swipeAway(
    threshold: Dp = 84.dp,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
): Modifier {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val limit = with(androidx.compose.ui.platform.LocalDensity.current) { threshold.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(0f) }
    if (onLeft == null && onRight == null) return this

    return this
        .onSizeChanged { width = it.width.toFloat() }
        .offset { IntOffset(offset.value.toInt(), 0) }
        .pointerInput(limit, onLeft == null, onRight == null) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        val armed = when {
                            offset.value <= -limit -> onLeft
                            offset.value >= limit -> onRight
                            else -> null
                        }
                        if (armed != null) {
                            /*
                             * Off the edge first, then the work — so the row leaves by
                             * moving rather than by vanishing out from under the finger.
                             * It leaves the way it was pushed, which is the only thing
                             * that makes two directions read as two different actions
                             * rather than as one action with a wobble.
                             */
                            val exit = if (offset.value < 0) -width else width
                            offset.animateTo(exit, tween(140))
                            armed()
                        } else {
                            offset.animateTo(0f, tween(160))
                        }
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, tween(160)) } },
            ) { change, drag ->
                change.consume()
                /*
                 * A direction with nothing behind it does not move.
                 *
                 * Clamping rather than ignoring: the row stays put against a push that
                 * would do nothing, which says "not that way" in the only language a
                 * gesture has. Letting it slide and spring back would read as a failed
                 * action rather than as an absent one.
                 */
                val low = if (onLeft != null) -width else 0f
                val high = if (onRight != null) width else 0f
                val next = (offset.value + drag).coerceIn(low, high)
                // One buzz, crossing the line in either direction, so the finger knows it
                // has armed before it lifts rather than after.
                val wasArmed = kotlin.math.abs(offset.value) >= limit
                val nowArmed = kotlin.math.abs(next) >= limit
                if (!wasArmed && nowArmed) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                scope.launch { offset.snapTo(next) }
            }
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
    // For text that is one line by its nature — a count, a clock, a label in a bar.
    // maxLines alone is not enough: it lets the line break and then hides the rest, so a
    // number can still lose its tail. softWrap = false refuses the break in the first place.
    softWrap: Boolean = true,
) = Text(
    text = text,
    style = if (lineHeight == TextUnit.Unspecified) style else style.copy(lineHeight = lineHeight),
    color = color,
    modifier = modifier,
    maxLines = maxLines,
    overflow = overflow,
    softWrap = softWrap,
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
