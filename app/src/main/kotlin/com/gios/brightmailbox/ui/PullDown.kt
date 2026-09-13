package com.gios.brightmailbox.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Pull the message down to put it away.
 *
 * A downward drag that starts while the content is already at its top dismisses the
 * reader; the same drag anywhere else scrolls the message, because the content asked for
 * it first. That is the whole rule, and [atTop] is the only thing that decides which of
 * the two a gesture turns out to be.
 *
 * **Why this is a ViewGroup and not a Compose gesture.** A WebView calls
 * [requestDisallowInterceptTouchEvent] the instant it decides it is handling a drag,
 * which orders every parent to stop watching. A Compose `pointerInput` wrapped around an
 * `AndroidView` loses to that: the pull works only if the finger starts in the few pixels
 * above the web content, which reads as a gesture that mostly does not work. WebTools hit
 * exactly this and solved it the same way — override the veto and refuse it while a pull
 * is still possible.
 *
 * Arm on crossing the slop, commit on lift, and a mostly-sideways drag cancels: the same
 * rule BrightControl uses for its edge swipes, so the gesture feels like the rest of the
 * phone rather than like this app.
 */
class PullDownFrame(context: Context) : FrameLayout(context) {

    /** True when the content cannot scroll up any further, so a pull is allowed. */
    var atTop: () -> Boolean = { true }

    /** Committed on lift, once the finger has travelled far enough. */
    var onPull: () -> Unit = {}

    /**
     * How far the finger has travelled down since the pull began, reported on every move.
     *
     * The frame does not draw anything itself — it cannot, the sheet it would have to move
     * is a Compose layout above it — so it reports and the caller translates. This is what
     * makes the sheet follow the finger instead of sitting still and then playing a fixed
     * animation when the finger lifts.
     */
    var onDrag: (Float) -> Unit = {}

    /** The finger left the screen. True when it had travelled far enough to dismiss. */
    var onRelease: (Boolean) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val commit = slop * 6   // ~a centimetre; a stray drag should not dismiss

    /*
     * Screen coordinates, not view coordinates.
     *
     * `ev.y` is relative to this frame, so once the caller starts drawing the sheet lower
     * in response to the pull, `ev.y` shrinks by the same amount and the reported travel
     * collapses — the gesture reads its own output as input. `rawY` is the finger's
     * position on the panel and nothing this app draws can move it.
     */
    private var downY = 0f
    private var downX = 0f
    private var pulling = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                downX = ev.rawX
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                val dx = abs(ev.rawX - downX)
                // Downward, past the slop, and more vertical than horizontal — and only
                // from a top the content has nowhere left to scroll from.
                if (dy > slop && dy > dx * 1.5f && atTop()) {
                    pulling = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (pulling) onDrag((ev.rawY - downY - slop).coerceAtLeast(0f))
                return pulling
            }
            MotionEvent.ACTION_UP -> {
                val travelled = ev.rawY - downY
                val committed = travelled > commit
                if (pulling) onRelease(committed)
                pulling = false
                if (committed) {
                    onPull()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pulling) onRelease(false)
                pulling = false
            }
        }
        return pulling
    }

    /**
     * Refuse the child's veto while a pull is still possible.
     *
     * This is the line that makes the gesture work at all. Without it the WebView's
     * `requestDisallowInterceptTouchEvent(true)` — sent on its very first move event —
     * takes this frame out of the conversation before [onInterceptTouchEvent] ever sees a
     * drag worth acting on.
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && atTop()) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
}
