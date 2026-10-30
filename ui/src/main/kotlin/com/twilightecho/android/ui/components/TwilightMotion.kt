package com.twilightecho.android.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Shared Apple Music-inspired motion language for Twilight Echo:
 * crisp initial response, long silky deceleration, spatial directionality,
 * and automatic respect for the system's Reduce Motion / Animator Duration Scale setting.
 */
object TwilightMotion {
    /** Smooth quintic-like deceleration: immediate onset, calm landing without sluggishness. */
    val EmphasizedDecelerate = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

    /** Clean exit curve for dismissing overlays and outgoing sheets. */
    val EmphasizedAccelerate = CubicBezierEasing(0.32f, 0.0f, 0.67f, 0.0f)

    /** Balanced symmetric curve for in-place container resizing and subtle state shifts. */
    val StandardCurve = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    const val PagePushMs = 300
    const val PagePopMs = 260
    const val TabSwitchMs = 220
    const val SheetExpandMs = 380
    const val SheetCollapseMs = 270
    const val SectionExpandMs = 240
    const val SectionCollapseMs = 180
    const val MicroMs = 180

    /**
     * iOS / Apple Music hierarchical push & pop transition.
     * Pair with a depth-derived [targetZIndex] so the deeper detail page always glides
     * above its parent page during both push and pop.
     */
    fun hierarchicalTransition(
        forward: Boolean,
        motionEnabled: Boolean = true,
        targetZIndex: Float = if (forward) 1f else 0f
    ): ContentTransform {
        if (!motionEnabled) {
            return ContentTransform(
                targetContentEnter = EnterTransition.None,
                initialContentExit = ExitTransition.None,
                targetContentZIndex = targetZIndex
            )
        }
        val enter: EnterTransition
        val exit: ExitTransition
        if (forward) {
            enter = slideInHorizontally(tween(PagePushMs, easing = EmphasizedDecelerate)) { (it * 0.24f).toInt() } +
                fadeIn(tween(210, easing = LinearOutSlowInEasing))
            exit = slideOutHorizontally(tween(PagePushMs, easing = EmphasizedDecelerate)) { -(it * 0.08f).toInt() } +
                fadeOut(tween(180, easing = FastOutLinearInEasing))
        } else {
            enter = slideInHorizontally(tween(PagePopMs, easing = EmphasizedDecelerate)) { -(it * 0.08f).toInt() } +
                fadeIn(tween(200, easing = LinearOutSlowInEasing))
            exit = slideOutHorizontally(tween(PagePopMs, easing = EmphasizedDecelerate)) { (it * 0.24f).toInt() } +
                fadeOut(tween(170, easing = FastOutLinearInEasing))
        }
        return ContentTransform(
            targetContentEnter = enter,
            initialContentExit = exit,
            targetContentZIndex = targetZIndex,
            sizeTransform = SizeTransform(clip = false)
        )
    }

    /**
     * Directional transition between peer tabs or segmented sections.
     * [direction] > 0 moves right/forward, < 0 moves left/backward, 0 cross-fades in place.
     */
    fun directionalTabTransition(
        direction: Int,
        motionEnabled: Boolean = true,
        driftFraction: Float = 0.055f
    ): ContentTransform {
        if (!motionEnabled) {
            return EnterTransition.None togetherWith ExitTransition.None
        }
        if (direction == 0) {
            return ContentTransform(
                targetContentEnter = fadeIn(tween(MicroMs, easing = LinearOutSlowInEasing)),
                initialContentExit = fadeOut(tween(130, easing = FastOutLinearInEasing)),
                sizeTransform = SizeTransform(clip = false)
            )
        }
        val sign = if (direction > 0) 1 else -1
        return ContentTransform(
            targetContentEnter = slideInHorizontally(tween(TabSwitchMs, easing = EmphasizedDecelerate)) {
                sign * (it * driftFraction).toInt()
            } + fadeIn(tween(TabSwitchMs, easing = LinearOutSlowInEasing)),
            initialContentExit = slideOutHorizontally(tween(150, easing = EmphasizedAccelerate)) {
                -sign * (it * (driftFraction * 0.65f)).toInt()
            } + fadeOut(tween(130, easing = FastOutLinearInEasing)),
            sizeTransform = SizeTransform(clip = false)
        )
    }

    /** Subtle crossfade + micro-scale for async state transitions (loading -> empty -> content). */
    fun stateFadeTransition(motionEnabled: Boolean = true): ContentTransform {
        if (!motionEnabled) {
            return EnterTransition.None togetherWith ExitTransition.None
        }
        return ContentTransform(
            targetContentEnter = fadeIn(tween(200, easing = LinearOutSlowInEasing)) +
                scaleIn(tween(220, easing = EmphasizedDecelerate), initialScale = 0.985f),
            initialContentExit = fadeOut(tween(130, easing = FastOutLinearInEasing)),
            sizeTransform = SizeTransform(clip = false)
        )
    }

    /** Full-screen player rising organically from the bottom dock and tucking back down. */
    fun playerSheetEnter(motionEnabled: Boolean = true): EnterTransition =
        if (!motionEnabled) EnterTransition.None
        else slideInVertically(tween(SheetExpandMs, easing = EmphasizedDecelerate)) { (it * 0.88f).toInt() } +
            scaleIn(
                tween(SheetExpandMs, easing = EmphasizedDecelerate),
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(0.5f, 1f)
            ) +
            fadeIn(tween(200, easing = LinearOutSlowInEasing))

    fun playerSheetExit(motionEnabled: Boolean = true): ExitTransition =
        if (!motionEnabled) ExitTransition.None
        else slideOutVertically(tween(SheetCollapseMs, easing = EmphasizedAccelerate)) { (it * 0.88f).toInt() } +
            scaleOut(
                tween(SheetCollapseMs, easing = EmphasizedAccelerate),
                targetScale = 0.95f,
                transformOrigin = TransformOrigin(0.5f, 1f)
            ) +
            fadeOut(tween(190, delayMillis = 35, easing = FastOutLinearInEasing))

    /** Accordion / inline section reveal and collapse. */
    fun sectionExpand(motionEnabled: Boolean = true): EnterTransition =
        if (!motionEnabled) EnterTransition.None
        else expandVertically(
            tween(SectionExpandMs, easing = EmphasizedDecelerate),
            expandFrom = Alignment.Top
        ) + fadeIn(tween(180, delayMillis = 25, easing = LinearOutSlowInEasing))

    fun sectionCollapse(motionEnabled: Boolean = true): ExitTransition =
        if (!motionEnabled) ExitTransition.None
        else shrinkVertically(
            tween(SectionCollapseMs, easing = StandardCurve),
            shrinkTowards = Alignment.Top
        ) + fadeOut(tween(120, easing = FastOutLinearInEasing))

    /** Floating badge / clear-button / pill entrance and exit. */
    fun pillPopEnter(motionEnabled: Boolean = true): EnterTransition =
        if (!motionEnabled) EnterTransition.None
        else scaleIn(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium), initialScale = 0.82f) +
            fadeIn(tween(150))

    fun pillPopExit(motionEnabled: Boolean = true): ExitTransition =
        if (!motionEnabled) ExitTransition.None
        else scaleOut(tween(130, easing = EmphasizedAccelerate), targetScale = 0.82f) +
            fadeOut(tween(120))
}

/** Observes Android's global animator duration scale so all transitions honor Reduce Motion. */
@Composable
fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun readMotionEnabled() =
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
            .getOrDefault(true)
    var enabled by remember(resolver) { mutableStateOf(readMotionEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readMotionEnabled()
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer
            )
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return enabled
}

/**
 * Layer-only tactile press feedback: gently compresses under touch and rebounds on a crisp spring,
 * without triggering recomposition or layout measurement.
 */
fun Modifier.tactilePress(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true
): Modifier = composed {
    val motionEnabled = rememberMotionEnabled()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) pressedScale else 1f,
        animationSpec = if (motionEnabled) spring(dampingRatio = 0.65f, stiffness = 650f) else snap(),
        label = "tactilePress"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
