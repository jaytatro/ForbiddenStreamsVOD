package com.forbiddenstreams.vod.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> =
        tween(durationMillis = 260, easing = FastOutSlowInEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - pivotPx
}
