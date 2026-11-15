package com.forbiddenstreams.vod.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * TV-style scrolling: whatever gets D-pad focus glides to a fixed spot ([pivot] from the
 * start of the list) instead of the list jumping just far enough to show it.
 * Applies to scrollable lists inside [content]; nest another one to use a different pivot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScrolling(pivot: Dp, content: @Composable () -> Unit) {
    val pivotPx = with(LocalDensity.current) { pivot.toPx() }
    val spec = remember(pivotPx) { PivotBringIntoViewSpec(pivotPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
private class PivotBringIntoViewSpec(private val pivotPx: Float) : BringIntoViewSpec {
    // A critically damped spring: when the remote is pressed again mid-scroll the animation
    // re-targets and keeps its speed instead of restarting, so held / rapid presses glide.
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - pivotPx
}

/**
 * Normal "scroll just enough to show it" behavior, but only while [enabled] returns true.
 * Detail pages use this so moving onto Play doesn't shift the page; scrolling starts only
 * once focus goes down to the trailers / episodes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GatedScrolling(enabled: () -> Boolean, content: @Composable () -> Unit) {
    val spec = remember(enabled) { GatedBringIntoViewSpec(enabled) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
private class GatedBringIntoViewSpec(private val enabled: () -> Boolean) : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (!enabled()) return 0f
        val end = offset + size
        return when {
            offset < 0f -> offset // above: scroll up to it
            end > containerSize -> if (size <= containerSize) end - containerSize else offset // below
            else -> 0f // already fully visible
        }
    }
}
