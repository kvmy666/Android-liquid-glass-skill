/*
 * Shape reference only; adapt to your app; not a drop-in library.
 *
 * Minimal Kyant0 Backdrop liquid-glass skeleton: shell recordings, the
 * idle/moving selector contract, and the state-gated z-order. It is not a
 * drop-in library; replace the placeholders and the minimal GlassSurface with
 * your own production code.
 */
package liquidglass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.roundToInt

/**
 * Replace with your static wallpaper: a gradient, blooms, or a photo layer.
 * Never record page content here; controls sample this layer so glass never
 * samples the layer that contains itself.
 */
@Composable
fun Wallpaper() {
    // Draw your static background here.
}

/**
 * Theme-aware navigation tint: #121212 at 0.40 in dark, #FAFAFA at 0.40 in
 * light. This placeholder always returns the dark value.
 */
fun glassNavigationTint(): Color = Color(0xFF121212).copy(alpha = 0.40f)

/**
 * Minimal app-side surface. Replace it with your production surface; the point
 * shown here is ownership: the bar blurs, the selector only refracts.
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    liquid: Boolean = false,
    tint: Color = Color.White.copy(alpha = 0.07f),
    showBorder: Boolean = true,
    highlight: (() -> Highlight)? = { Highlight.Default },
    shadow: (() -> Shadow)? = { Shadow.Default },
    content: @Composable () -> Unit = {}
) {
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(brightness = 0.04f, contrast = 1f, saturation = 1.5f)
                blur((if (liquid) 12.dp else 24.dp).toPx())
                if (liquid) {
                    lens(
                        refractionHeight = 24.dp.toPx(),
                        refractionAmount = 24.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = true
                    )
                }
            },
            highlight = highlight,
            shadow = shadow,
            onDrawSurface = { drawRect(tint) }
        )
    ) {
        content()
    }
    // showBorder is intentionally not drawn here; add Modifier.border(...) in your app.
}

/** Controls and cards sample the wallpaper only. */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Shell: wallpaper and page are separate recordings; the bar sees both. */
@Composable
fun GlassShell(
    content: @Composable (Backdrop) -> Unit,
    bar: @Composable (Backdrop) -> Unit
) {
    val wallpaper = rememberLayerBackdrop()
    val page = rememberLayerBackdrop()
    val barBackdrop = rememberCombinedBackdrop(wallpaper, page)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(wallpaper)
        ) {
            Wallpaper()
        }
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(page)
        ) {
            CompositionLocalProvider(LocalGlassBackdrop provides wallpaper) {
                content(wallpaper)
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            bar(barBackdrop)
        }
    }
}

/**
 * Navigation: the pill owns blur; the selector owns a movement-only lens.
 * Idle -> row above selector (crisp text). Moving -> selector above row and
 * sampling page + pill + row, so the lens has content to bend.
 */
@Composable
fun GlassNav(pageBackdrop: Backdrop) {
    val pillLayer = rememberLayerBackdrop()
    val rowLayer = rememberLayerBackdrop()
    val idleBackdrop = rememberCombinedBackdrop(pageBackdrop, pillLayer)
    val movingBackdrop = rememberCombinedBackdrop(idleBackdrop, rowLayer)

    var dragging by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "press"
    )
    val moving = selectorState(dragging, press) == SelectorState.Moving

    // Placeholders: replace with your own layout animation.
    val selectorX = 0f
    val selectorY = 0f
    val selectorWidth = 71.dp
    val selectorHeight = 57.dp

    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(pillLayer)
        ) {
            GlassSurface(
                backdrop = pageBackdrop,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(50),
                liquid = true,
                tint = glassNavigationTint(),
                showBorder = false,
                highlight = null,
                shadow = null
            ) {}
        }

        Box(
            Modifier
                .zIndex(if (moving) 1f else 0f)
                .offset { IntOffset(selectorX.roundToInt(), selectorY.roundToInt()) }
                .size(selectorWidth, selectorHeight)
                .drawBackdrop(
                    backdrop = if (moving) movingBackdrop else idleBackdrop,
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        if (moving) {
                            blur(0.dp.toPx())
                            lens(
                                refractionHeight = 24.dp.toPx(),
                                refractionAmount = 24.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = true
                            )
                        }
                    },
                    highlight = {
                        Highlight(
                            width = 0.75.dp,
                            blurRadius = 0.4.dp,
                            alpha = if (moving) 0.78f else 0.78f * 0.45f,
                            style = HighlightStyle.Default(0.5f, 45f, 1f)
                        )
                    },
                    shadow = null,
                    onDrawSurface = {
                        // Use white frost in dark and black frost in light in your app.
                        drawRect(Color.White.copy(alpha = 0.12f * (1f - 0.6f * press)))
                        if (moving) drawRect(Color.White.copy(alpha = 0.04f))
                    }
                )
        )

        Row(
            Modifier
                .zIndex(if (moving) 0f else 1f)
                .fillMaxSize()
                .selectableGroup()
                .layerBackdrop(rowLayer)
        ) {
            // tabs
        }
    }
}

/** Pure state contract: test this, then gate z-order, backdrop, and effects on it. */
enum class SelectorState { Idle, Moving }

fun selectorState(dragging: Boolean, press: Float): SelectorState =
    if (dragging || press > 0.01f) SelectorState.Moving else SelectorState.Idle
