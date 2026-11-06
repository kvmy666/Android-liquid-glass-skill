# Liquid Glass with Kyant0 Backdrop: Deep Reference

A production reference for a glass bar, a frosted capsule, and a state-gated moving selector in Android Compose. It covers the library model, the acyclic layer graph, the idle/moving contract, tokens, fallbacks, accessibility, testing, and integration checks. The shape-only skeleton in `templates/LiquidGlassSkeleton.kt` follows the same structure.

## 1. The library model

### 1.1 What Backdrop is

`io.github.kyant0:backdrop` is a Compose library for **backdrop sampling**. A composable subtree is recorded into a layer. A `Modifier.drawBackdrop` surface then samples that recording inside a shape, applies an ordered GPU effect chain, and draws a surface tint, a directional highlight, and an optional shadow.

```text
recorded backdrop
    -> color controls (brightness, contrast, saturation)
    -> blur (pixels)
    -> lens / refraction (pixels, optional depth and chromatic aberration)
    -> onDrawSurface (tint, frost, inner shadow, soft rim)
    -> highlight (directional rim)
    -> optional shadow
```

"Liquid glass" describes the material language. Backdrop is an independent library with similar building blocks; this reference uses only its public API and authors no shader source of its own.

It is not a system-wide blur or a window overlay: it only sees content recorded by your app process. It does not read the device wallpaper. It is not suitable for injecting into SystemUI or another app's process. It is not free: every live surface records and samples content, and the lens is the most expensive effect in the chain.

### 1.2 Dependency and build constraints

```kotlin
// gradle/libs.versions.toml
backdrop = "1.0.1"
backdrop = { module = "io.github.kyant0:backdrop", version.ref = "backdrop" }

// app module
implementation(libs.backdrop)
```

| Constraint | Value | Why it matters |
|---|---|---|
| Backdrop | 1.0.1, AAR | The API used here (`drawBackdrop`, `rememberCombinedBackdrop`, `Highlight`) is the 1.0.1 surface. |
| Kotlin | 2.2.21 | 1.0.1 was resolved against Kotlin 2.2.21. |
| Compose BOM | `2025.10.01` | The library raises Compose UI/foundation to 1.9.5. |
| JVM target | 17 | AGP/Compose requirement. |
| `minSdk` | 33 | Runtime shader/lens path; blur alone needs API 31+. |
| `targetSdk` / `compileSdk` | 36 in the reference setup | Current platform target; adapt to your app. |

`2.0.1` is **not** a drop-in replacement. It is a Compose Multiplatform artifact requiring Kotlin 2.4.10 and CMP 1.12, a different stack. Re-test the effect order and the `Highlight` constructor before changing the coordinate. Pin the version and treat an upgrade as a rendering change, not a routine dependency bump.

### 1.3 API surface

| API | Role | Contract |
|---|---|---|
| `Backdrop` | Sample source | A recorded layer or a combination of layers. |
| `LayerBackdrop` | Concrete recording | Created by `rememberLayerBackdrop()`. |
| `rememberLayerBackdrop()` | Recording factory | Attach it to a composable with `Modifier.layerBackdrop`. |
| `Modifier.layerBackdrop(layer)` | Record a subtree | The subtree is captured into that layer. |
| `rememberCombinedBackdrop(a, b)` | Combine recordings | Argument order is meaningful; combine only acyclic inputs. |
| `Modifier.drawBackdrop(...)` | Draw a glass surface | Samples the backdrop inside `shape` and runs the effect chain. |
| `effects.colorControls(brightness, contrast, saturation)` | Color filter | Runs before blur. |
| `effects.blur(radiusPx)` | Blur | Radius is in **pixels**; convert with `Dp.toPx()`. |
| `effects.lens(height, amount, depthEffect, chromaticAberration)` | Refraction shader | All distances are pixels; requires API 33+. |
| `effects.vibrancy()` | Optional saturation helper | Convenience only; explicit `colorControls` is easier to tune. |
| `Highlight(width, blurRadius, alpha, style)` | Directional rim | Sizes are `Dp`; `Default` is a concrete rim, not a no-op. |
| `HighlightStyle.Default(intensity, angleDegrees, falloff)` | Highlight shape | 0.5 white, 45 degrees, falloff 1.0 in the reference. |
| `Shadow` / `Shadow.Default` | Drop shadow | Default is 24 dp; pass `null` when no shadow is wanted. |
| `onDrawSurface` | Surface draw | Draws tint/frost/inner edge on top of the filtered backdrop, in local shape coordinates. |
| `layerBlock` | Layer transform | Transforms the layer without scaling the sampled backdrop. |

## 2. The layer graph

### 2.1 Separate recordings

The minimum viable graph separates the static scene from the page:

```text
wallpaper layer     static gradient, blooms, or a photo
page layer          page content, cards, scrolling text

barBackdrop = rememberCombinedBackdrop(wallpaper, page)
```

- Content, cards, and controls receive `wallpaper`, never the combined bar layer. A control inside a card therefore samples the static background, not the layer that contains itself. This is the structural fix for recursive capture.
- The pill and the scroll edge receive `barBackdrop`, so they can refract or blur page content.
- A navigation selector adds two more recordings:

```text
pillLayer           the rendered pill (blur, lens, tint, press glow)
rowLayer            icons and labels

idleBackdrop        = rememberCombinedBackdrop(pageBackdrop, pillLayer)
movingBackdrop      = rememberCombinedBackdrop(idleBackdrop, rowLayer)
```

The graph is acyclic:

```text
wallpaper ---> barBackdrop ---> pill/scroll edge
page ---------^     |
                    v
                 pillLayer ---> idleBackdrop ---> movingBackdrop
rowLayer -------------------------^
```

The pill samples `pageBackdrop`; the selector samples the pill output through `pillLayer`; the row is recorded separately. No surface samples the layer that contains it.

### 2.2 Ownership table

| Layer | Records | Sampled by | Why |
|---|---|---|---|
| `wallpaper` | Static gradient/blooms/photo | Content cards and controls; `barBackdrop` | Structure for refraction without self-capture. |
| `page` | Page content | `barBackdrop` only | Scrolling text is refracted by the bar. |
| `pillLayer` | Pill material and press glow | `idleBackdrop` and `movingBackdrop` | The selector lens sees the actual pill output. |
| `rowLayer` | Icons and labels | `movingBackdrop` only | The movement lens has high-contrast text to bend. |
| `barBackdrop` | `wallpaper` + `page` | Pill, scroll edge | The bar sees page content. |
| `idleBackdrop` | `barBackdrop` + pill | Selector at idle | Idle selector samples pill/page but applies no lens. |
| `movingBackdrop` | `idleBackdrop` + row | Selector while moving | Moving lens samples page + pill + row. |
| `LocalGlassBackdrop` | `wallpaper` | Switch, slider | Controls never sample their container. |

### 2.3 Effect order

The effect lambda order is **color filter -> blur -> lens**. The surface tint is drawn afterward by `onDrawSurface`; highlight and shadow are library-level nodes around that draw. Treat the exact compositing order of `Highlight` and `Shadow` as library-owned and verify it with a screenshot test whenever you pin a new version.

**Color controls.** Brightness 0.04, contrast 1.0, saturation 1.5 in the reference. Saturation is the useful knob; brightness near zero avoids washing out the backdrop.

**Blur.** The argument is pixels. Always convert at the draw site: `blur(12.dp.toPx())`. Passing `16f` as if it were dp produces a much weaker blur than intended.

**Lens.** `refractionHeight` and `refractionAmount` are pixel distances. The reference values are 24 dp / 24 dp with `depthEffect = true` and `chromaticAberration = true`. The lens is a runtime shader; it needs API 33+ and is the most expensive effect in the chain.

**Surface draw.** `onDrawSurface` runs in the local shape coordinate space. Tint, frost, moving tint, inner shadow, and soft rim belong here. It is not part of the sampled backdrop, so it cannot recursively capture the surface.

**Highlight and shadow.** `Highlight.Default` is a concrete visible rim, not a neutral value. `Shadow.Default` is 24 dp, not a small default. Pass explicit values or `null`.

**Layer block.** Use `layerBlock` for press scaling:

```kotlin
layerBlock = {
    val scale = if (pressed) 1.012f else 1f
    scaleX = scale
    scaleY = scale
}
```

This scales the layer, not the sampled backdrop, so the page content under the glass stays stable during the press animation.

## 3. The idle/moving contract

### 3.1 State expression

```kotlin
val moving = dragging || press > 0.01f
```

`press` is a spring driven by the drag state. The threshold keeps a settling press from flickering the selector back to idle. The reference press spring is `spring(dampingRatio = 0.7f, stiffness = 500f)`. Everything visual that depends on movement is a function of `moving`: the selector z-index, the row z-index, the selector's sample source, and the effect block.

### 3.2 Z-order table

| State | Selector z | Row z | Selector backdrop | Effects | Surface |
|---|---:|---:|---|---|---|
| Idle | 0 | 1 | `combined(page, pill)` | none | Frost overlay, inner shadow, soft rim |
| Moving | 1 | 0 | `combined(page, pill, row)` | `blur(0)` + lens 24/24, depth + chroma | Frost + faint moving tint |

### 3.3 Why this ordering exists

- **Idle:** the row is above the selector, so icons and labels are drawn crisply. The selector is a frosted capsule under the text, with a static rim and inner shadow. It samples the page and pill so the material shows through, but applies no lens.
- **Moving:** the selector is above the row so the lens can bend the icons and labels. The row stays visible around the capsule.
- **No blur on the selector:** the pill already owns blur. A second blur softens the labels the lens is supposed to reveal and adds a costly pass during the drag. The selector's `blur(0.dp)` is intentional: it keeps the effect chain uniform while disabling the blur pass.
- **State, not constant:** a permanently raised selector gives crisp idle text but no movement lens. A permanently lowered selector gives a lens but fogs the idle labels.

### 3.4 The pill

The pill is a liquid `GlassSurface` inside the pill recording box.

| Property | Reference value | Class |
|---|---|---|
| Height | 64 dp | Reference |
| Shape | `RoundedCornerShape(50)` | Reference |
| Blur | 12 dp | Reference |
| Lens | 24 / 24 dp, depth + chroma | Reference |
| Tint | `#121212` @ 0.40 dark; `#FAFAFA` @ 0.40 light | Reference |
| Border | none on the live path | Design |
| Highlight | `null` on the live path; one owner on the selector | Design |
| Shadow | `null` | Design |

The pill samples `pageBackdrop`, while `pillLayer` records its rendered output. That order lets the selector refract the pill without recursion. If the pill needs an outer box larger than the capsule (for example, to avoid clipping a held selector), keep the recording box as the outer bounds and draw the capsule inside it.

The optional press glow is drawn inside the pill recording box after the pill surface. It therefore becomes part of `pillLayer` and is sampled by the selector on the next frame. A modest reference recipe is a radial white `0.16 * press`, radius `1.5 * selectorHeight`, inset 8 dp, clipped to the pill shape.

### 3.5 The selector

The selector is a separate `Box` with no click handler; the row owns selection and semantics.

| Property | Reference value | Class |
|---|---|---|
| Shape | `RoundedCornerShape(50)` | Reference |
| Rest and held size | capped by the item slot | Design |
| Width/height animation | `spring(0.8f, 350f)` | Reference |
| Position | animated station X; 0.78/420 settle | Reference |
| Drop shadow | 8 dp Compose shadow | Design |
| Backdrop | idle vs moving combined backdrop | Design |
| Idle effects | none | Design |
| Moving effects | `blur(0)` + lens 24/24 | Reference |
| Frost | white 0.12 dark / black 0.06 light, `*(1 - 0.6 * press)` | Design |
| Moving tint | `0.04` of the navigation tint | Design |
| Inner shadow | 1.5 dp inset, black 0.18, 0.75 dp stroke | Design |
| Soft rim | rim color at 0.45x alpha, 0.75 dp stroke | Design |
| Highlight | `Highlight(0.75.dp, 0.4.dp, alpha, Default(0.5f, 45f, 1f))` | Reference |
| Highlight alpha | 0.78 dark / 0.96 light; 0.45x idle | Reference / Design |

The idle selector has **no effects block**. It samples the backdrop so the pill and page show through the frost, but applies no color controls, no blur, and no lens. The frost is the only fill; the highlight, inner shadow, soft rim, and drop shadow provide depth. This keeps the idle capsule readable and avoids a second blur pass behind the labels.

A soft manual rim at 0.45x alpha can echo the library highlight. That is subordinate decoration, not a second equal-weight rim. If you want strict one-owner semantics, drop the echo. A full-strength manual rim plus `Highlight.Default` reads as two edges.

The moving selector is transparent glass: `blur(0)` plus a lens. The 0.04 moving tint is a faint identity tint, not the reference full-row 0.16 selection tint. A filled, frosted capsule is a design choice that combines the reference rim with a floating selector; describe it as your own material, not a pixel match.

### 3.6 The row, cards, and controls

The row is a `Row` with `selectableGroup()` and one `selectable` per tab. Keep its recording separate from the pill so the lens has crisp icon and label content to bend. Use a pure function for hover and selected color so it is unit-testable.

A content card should not be a live backdrop by default. A static Material card with a near-opaque container and a hairline border is cheap and readable. Live glass belongs on navigation and a small number of controls.

Switches and sliders sample `LocalGlassBackdrop`, which is the wallpaper only. If no backdrop is available, render the Material control so previews and tests still work. A scroll edge can blur the combined backdrop at card-scale blur inside an offscreen layer and mask the blur with a vertical `DstIn` gradient; the static fallback replaces it with a multi-stop shade.

## 4. Tokens

### 4.1 How to read the classes

- **Reference** — a value taken from the reference material. Try it first.
- **Design** — a value chosen or tuned for this implementation. It is not a measured reference value.
- **Constraint** — a platform or dependency limit.

Do not relabel a design choice as a reference value. The distinction is the only way a future reader can tell a deliberate deviation from a regression.

### 4.2 Token table

| Token | Value | Class | Notes |
|---|---|---:|---|
| `SATURATION` | 1.5 | Reference | From the reference color matrix. |
| `BRIGHTNESS` | 0.04 | Design | Keeps the backdrop from washing out. |
| `CONTRAST` | 1.0 | Design | Neutral. |
| `NavigationBlur` | 12 dp | Reference | Pill only. |
| `CardBlur` | 24 dp | Design | Cards are static; used by the scroll edge. |
| `ControlBlur` | 12 dp | Design | Control-scale material. |
| `ThinBlur` | 8 dp | Design | Slider thumb. |
| `NavigationLensHeight` / `Amount` | 24 / 24 dp | Reference | Pill and moving selector. |
| `NavigationTint` | `#121212` @ 0.40 dark; `#FAFAFA` @ 0.40 light | Reference | |
| `NavigationSelectionAlpha` | 0.16 | Reference | Full-row selection tint. |
| `MovingTintAlpha` | 0.04 | Design | Faint identity tint on the moving selector. |
| `NavigationReflectionAlpha` | 0.16 | Reference | Press glow multiplier. |
| `NavigationRimWidth` / `Blur` | 0.75 / 0.4 dp | Reference | |
| `NavigationRimAlpha` | 0.78 dark / 0.96 light | Reference | |
| Highlight style | 0.5 white / 45 degrees / falloff 1.0 | Reference | `HighlightStyle.Default(0.5f, 45f, 1f)`. |
| Pill height / shape | 64 dp / 50% | Reference | |
| Selector rest / held | capped by slot | Design | The reference liquid material has no filled selector. |
| Selector frost | white 0.12 dark / black 0.06 light | Design | `*(1 - 0.6 * press)`. |
| Idle highlight | 0.45x full alpha | Design | Keeps a static edge without a full rim. |
| Inner shadow | 1.5 dp inset, black 0.18 | Design | Depth layer. |
| Selector drop shadow | 8 dp | Design | The reference navigation has no shadow. |
| Press glow | white 0.16 x press; radius 1.5 x height; 8 dp inset | Reference | Only while pressed. |
| Card / control tint | white 0.07 / 0.09 | Design | Tune per surface. |
| `minSdk` | 33 | Constraint | Blur needs 31+; lens needs 33+. |
| Emulator quality | no lens, no live surfaces/controls, half blur | Design | Test/emulator tier. |
| Springs | press 0.7/500; stretch 0.8/350; settle 0.78/420 | Reference | Visibility threshold 0.01. |

### 4.3 Token warnings

1. **A large "blur radius" constant may not be a blur.** In some reference material, a value near 40 is part of a highlight angle sweep (`45 + 40 * velocity`), not a blur radius. The glass blur here is 12 dp. Do not port a number without its role.
2. **A progressive blur shader name is not an implementation.** The presence of a shader string does not prove it is used. This reference's scroll edge is a `DstIn` mask, not a progressive-blur pipeline.
3. **The reference navigation has no border and no drop shadow.** Any border and the 8 dp selector shadow are design additions. Label them accordingly.
4. **Keep control values in the token table.** If a switch and slider hardcode 6/9 and 5/7 while the table says 8/12, one of them is drifting. Either use the tokens or delete the unused rows.

## 5. Code skeleton mapping

### 5.1 Shell

```kotlin
@Composable
fun GlassShell(
    content: @Composable (Backdrop) -> Unit,
    bar: @Composable (Backdrop) -> Unit
) {
    val wallpaper = rememberLayerBackdrop()
    val page = rememberLayerBackdrop()
    val barBackdrop = rememberCombinedBackdrop(wallpaper, page)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(wallpaper)) { Wallpaper() }
        Box(Modifier.fillMaxSize().layerBackdrop(page)) {
            CompositionLocalProvider(LocalGlassBackdrop provides wallpaper) {
                content(wallpaper)
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            bar(barBackdrop)
        }
    }
}
```

### 5.2 State-gated selector

```kotlin
val moving = selectorState(dragging, press) == SelectorState.Moving

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
                    lens(24.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
                }
            },
            highlight = {
                Highlight(
                    width = 0.75.dp,
                    blurRadius = 0.4.dp,
                    alpha = if (moving) rimAlpha else rimAlpha * 0.45f,
                    style = HighlightStyle.Default(0.5f, 45f, 1f)
                )
            },
            shadow = null,
            onDrawSurface = {
                drawRect(frost)
                if (moving) drawRect(movingTint)
            }
        )
)
```

The row uses the opposite z-order:

```kotlin
Row(
    Modifier
        .zIndex(if (moving) 0f else 1f)
        .fillMaxSize()
        .selectableGroup()
        .layerBackdrop(rowLayer)
) { /* tabs */ }
```

### 5.3 Pure state contract

```kotlin
enum class SelectorState { Idle, Moving }

fun selectorState(dragging: Boolean, press: Float): SelectorState =
    if (dragging || press > 0.01f) SelectorState.Moving else SelectorState.Idle
```

Test this function. Then make z-order, backdrop choice, and the effect block depend on its result. Keeping the decision in one pure function prevents the most common divergence: one property gated by `moving` and another still constant.

### 5.4 Controls contract

```kotlin
val wallpaper = LocalGlassBackdrop.current
if (wallpaper == null) {
    MaterialFallback()
    return
}
// The control samples wallpaper, never the card that contains it.
```

Preserve semantics in both paths: `Role.Switch`, `toggleableState`, `ProgressBarRangeInfo`, `setProgress`. The fallback is a real code path for previews, tests, and devices without a live backdrop.

### 5.5 Mapping table

| Skeleton | Responsibility |
|---|---|
| `GlassShell` | Wallpaper/page split; bars receive the combined backdrop. |
| `Wallpaper()` | Your static gradient, blooms, or photo. |
| `GlassSurface` | Tint, blur, optional lens, highlight/shadow ownership. |
| `GlassNav` | Pill, selector, row, and the idle/moving gate. |
| `LocalGlassBackdrop` | Wallpaper only, for controls and cards. |
| `selectorState` | Pure idle/moving decision. |
| `GlassSwitch` / `GlassSlider` | Control surfaces sampling the wallpaper. |
| `GlassCard` | Static, near-opaque card; not a live surface. |
| `ScrollEdge` | Masked blur or static shade. |

## 6. Pitfalls and anti-patterns

| Pitfall | What it looks like | Rule |
|---|---|---|
| Raw pixels passed as dp | Glass looks flat or barely blurred | Every distance is `dp.toPx()` at the draw site. |
| Sampling the layer that contains the surface | Native crash in the render thread | Split wallpaper and page; never sample your own layer. |
| Live glass on every card | ANR, 200+ ms frames | Budget one live surface per screen. Cards are static. |
| Blurring the selector | Fogged labels, soft icons | The pill owns blur. The selector is a lens-only layer with zero blur. |
| Lens with a flat backdrop | Invisible effect; "it does nothing" | Record the content that should bend. A lens over a uniform tint has no structure. |
| Relying on `Highlight.Default` / `Shadow.Default` | Unexpected rim or 24 dp halo | Pass explicit tokens or `null`. |
| Two equal-weight rims | Double edge | One rim owner. A soft echo must be clearly subordinate. |
| Constant z-order | Labels fogged, or the movement lens disappears | `moving` gates both the selector z-index and the backdrop. |
| Scaling the glass to animate size | The sampled backdrop stretches or shimmers | Animate layout width/height; use `layerBlock` for press scale. |
| Over-tinting the pill | Dark, muddy, low-contrast material | Keep the 0.40 tint; frost is 0.12, not 0.40. Check light mode. |
| Porting a large "blur" constant | A blur that does not match the reference | Verify the constant's role first; 40 may be a highlight sweep. |
| Porting an unused progressive shader | Dead code or a wrong scroll effect | Do not infer an implementation from a string. Use the `DstIn` mask. |
| Attributing a border/shadow to the reference | "Reference" values that do not exist | The reference navigation has no border and no drop shadow. |
| Treating the emulator as a phone | Lens looks absent or frames collapse | Quality tiers disable lens/live surfaces on emulators. Test on a phone. |
| Assuming API 33 everywhere | Crash or no effect on lower APIs | Blur needs 31+; lens needs 33+; below that use a translucent fallback. |
| Ignoring text contrast | Glass looks good but labels are unreadable | Keep text above the glass or on a near-opaque surface. |

## 7. Performance and fallback

### 7.1 Quality tiers

A deterministic quality switch is better than a silently degraded effect:

| Flag | Phone | Emulator |
|---|---:|---:|
| `lensEnabled` | true | false |
| `liveSurfaces` | true | false |
| `liveControls` | true | false |
| `blurScale` | 1.0 | 0.5 |

Detect emulators or software GPUs from build fingerprints and hardware names, then lower quality deterministically. Log the tier so a report can explain why the lens is missing. Do not change the look on a real phone without an explicit setting.

### 7.2 Fallback paths

| Surface | Live path | Fallback |
|---|---|---|
| Pill | Blur + tint + lens | Static vertical tint gradient + manual rim |
| Selector | `drawBackdrop`; lens gated by quality | Still draws frost/highlight; no lens |
| Switch | `drawBackdrop` track sampled from wallpaper | Material `Switch` when no backdrop; static gradient when no live controls |
| Slider | `drawBackdrop` track/thumb | Material `Slider` when no backdrop; static track/thumb when no live controls |
| Scroll edge | Masked blur (`DstIn`) | Static vertical shade |
| Cards | Not a live surface | Static Material card at high opacity |

The static path must be recognizable: a tint, a border, and a rim. An invisible fallback is a bug, not a graceful degradation. Blur needs API 31+ and the lens needs API 33+; below 31 use a translucent surface, and on API 31-32 use blur only. A lens that compiles on 33 can still fail at runtime on a GPU that does not support the shader, so guard the draw path and keep the fallback.

### 7.3 Performance rules

- Budget one live backdrop surface per screen. Cards and large lists are static.
- Keep live surfaces small. A full-screen live blur plus a scrolling list is the expensive case.
- Avoid animating the blur radius. Animate position and size; use layout animation, not scale.
- Reuse combined backdrops; do not rebuild the graph on every recomposition.
- Record only what is needed. A full-screen page layer updates while scrolling and is the main cost.
- Measure with `dumpsys gfxinfo` on the target phone. An emulator baseline is not a phone budget.
- Suspend or pause live effects when the screen is not visible or the app is in the background.
- Verify the effect with a deliberate drag, not an idle screen.

## 8. Accessibility

- Check text contrast over the glass in dark and light themes. The near-opaque card and the 0.12 frost exist for this.
- Do not encode selection only with tint. Change icon size or weight as well.
- Keep touch targets at least 48 dp. A small switch needs a larger parent hit area if it is the only target.
- Preserve semantics: `Role.Tab`, `selectableGroup`, `Role.Switch`, `toggleableState`, `ProgressBarRangeInfo`, `setProgress`.
- Keep labels above the selector at idle. If a movement lens includes them, keep the lens small and avoid blur so they remain recognizable.
- Respect reduced motion: skip or shorten the lens and press animation; the static frost and rim still communicate selection. Provide a readable non-glass fallback for users who disable transparency or use high-contrast settings.

## 9. Testing

- Unit-test the pure state mapping: idle versus moving, z-order, backdrop selection, hover index, selected color.
- Pin the token table in a test so a refactor cannot silently change blur, lens, or alpha.
- Test the no-backdrop and no-live-surface fallbacks. They are real code paths, not dead branches.
- Grep that no app-authored `RuntimeShader` or AGSL exists if the requirement is "library-only."
- Pin the dependency version and record the tested Kotlin/Compose pair.
- Capture screenshots for dark/light and idle/moving, plus both fallback paths.
- Run a performance capture after a deliberate scroll or drag.
- Keep a device matrix; mark untested devices `unverified` instead of claiming support.
- Add one instrumentation or screenshot test for the selector; unit tests alone do not catch a z-order regression.

## 10. Integration checklist

### 10.1 Dependency and build

- Pin `io.github.kyant0:backdrop:1.0.1` or a deliberately tested version.
- Do not assume a major upgrade is a drop-in; it may target a different Kotlin/Compose stack.
- Keep the dependency and the token table in one place; do not scatter literal dp and alpha values across screens.

### 10.2 Architecture and assets

- Keep the wallpaper/page split. It is the minimum viable structure.
- Give content the wallpaper and the bar the combined layer. Never let a surface sample the layer that contains it.
- Record the pill and row separately, and build idle and moving backdrops from them.
- Do not ship third-party proprietary assets or claim a proprietary source implementation; recreate the look from numbers and your own drawing.
- Document whether a value is a reference value or a design choice.

### 10.3 Scope

- Keep glass app-only. Do not inject a Backdrop surface into SystemUI or another process.
- Treat navigation, a small number of controls, and an edge treatment as the budget. Static cards are the default.
- Provide a recognizable static fallback before shipping the live path.
- Verify the movement lens on a physical device before calling it done.


