---
name: liquid-glass
description: Build, debug, or port Kyant0 Backdrop liquid-glass UI in Android Compose. Use for glass pills, nav selectors, frosted surfaces, lens/refraction, chromatic edges, blur ownership, z-order bugs, and fallback/performance reviews.
---

# Liquid Glass with Kyant0 Backdrop

Portable playbook for a glass pill and a moving navigation selector. Full model, tokens, pitfalls, performance, accessibility, testing, and integration notes: [reference.md](reference.md). Shape-only skeleton: [templates/LiquidGlassSkeleton.kt](templates/LiquidGlassSkeleton.kt).

## When to use

- An Android Compose app shell with a floating glass bar, pill, or nav selector.
- You need lens/refraction, chromatic aberration, frosted tint, or a directional rim.
- You are debugging a z-order, blur-ownership, or "the effect disappeared" bug.
- You are porting a reference liquid-glass material into another app.

## When not to use

- SystemUI, window overlays, or anything outside your own app process. Backdrop records only app content.
- API < 31 for blur, or < 33 for the lens shader, unless you ship a static fallback.
- You only need a translucent card. Use a normal Material surface; live glass is expensive.
- You want to sample the device wallpaper. Backdrop does not read it; draw your own static layer.

## The one rule

**Background blur belongs to the bar, never the moving selector.**

The pill owns `blur(12.dp)`. The selector is a movement-only lens: `blur(0.dp)` plus `lens(24/24)`. A second blur on the selector fogs the icons and labels the lens is meant to reveal, and adds a costly pass during the drag.

Corollaries:

- The idle selector has no lens or blur; it is a frosted capsule with a static rim under crisp text.
- Lens and chromatic aberration are gated by `moving`; idle has no lens.
- Every distance is `dp.toPx()` at the draw site; `blur(16f)` is 16 px, not 16 dp.

## Dependency and constraints

| Item | Value |
|---|---|
| Backdrop | `io.github.kyant0:backdrop:1.0.1` (AAR) |
| Kotlin | 2.2.21 (resolved against) |
| Compose BOM | 2025.10.01 (Compose UI/foundation 1.9.5) |
| JVM target | 17 |
| `minSdk` | 33 for the full lens path; blur alone needs 31+ |
| `targetSdk` / `compileSdk` | 36 in the reference setup; adapt as needed |

`2.0.1` is **not** a drop-in: it is a Compose Multiplatform artifact requiring Kotlin 2.4.10 and CMP 1.12, a different stack. Re-test the effect order and the `Highlight` constructor before changing the coordinate.

## Layer model

Record in this order; never let a surface sample the layer that contains it.

```text
wallpaper layer  static gradient/blooms (never page content)
page layer       scrolling page content
barBackdrop      = combined(wallpaper, page)       -> pill + scroll edge

pill layer       the rendered pill (blur/lens/tint)
row layer        icons + labels
idleBackdrop     = combined(page, pill)
movingBackdrop   = combined(idleBackdrop, row)     -> selector while moving
```

- Content, cards, and controls sample `wallpaper`, not the combined bar layer.
- The pill samples `page`; the selector samples the pill through `pillLayer`.
- The selector's moving backdrop must include the row; without it the lens has no high-contrast content to bend.
- `LocalGlassBackdrop` carries the wallpaper to controls, so a switch or slider never samples its own card.

## State contract

```kotlin
val moving = dragging || press > 0.01f
```

| State | Selector z | Row z | Backdrop | Effects |
|---|---:|---:|---|---|
| Idle | 0 | 1 | `combined(page, pill)` | none; frost + rim only |
| Moving | 1 | 0 | `combined(page, pill, row)` | `blur(0)` + lens 24/24, depth + chroma |

- Idle: row above selector -> crisp icons and labels.
- Moving: selector above row -> the lens sees and bends the row.
- Z-order is a function of state, not a constant.
- Animate position and size through layout; use `layerBlock` for press scale, never scale the sampled backdrop.

## Reference and design tokens

`Reference` = value taken from the reference material; `Design` = a choice tuned for this implementation; `Constraint` = platform limit. Do not label a design value "measured".

| Token | Value | Class | Note |
|---|---|---:|---|
| Navigation blur | 12 dp | Reference | Pill only; selector 0 |
| Lens | 24 / 24 dp, depth + chroma | Reference | Pill and moving selector |
| Highlight width / blur | 0.75 / 0.4 dp | Reference | Selector rim |
| Highlight alpha | 0.78 dark / 0.96 light | Reference | Idle 0.45x is Design |
| Highlight style | 0.5 white / 45 degrees / falloff 1.0 | Reference | `HighlightStyle.Default` |
| Navigation tint | `#121212` @ 0.40 dark / `#FAFAFA` @ 0.40 light | Reference | |
| Full-row selection tint | 0.16 | Reference | Moving tint 0.04 is Design |
| Pill height / shape | 64 dp / 50% | Reference | |
| Press reflection | 0.16 x press | Reference | |
| Springs | press 0.7/500; stretch 0.8/350; settle 0.78/420 | Reference | |
| Saturation / brightness / contrast | 1.5 / 0.04 / 1.0 | Reference / Design | Saturation from the reference color matrix |
| Selector frost | white 0.12 dark / black 0.06 light | Design | `*(1 - 0.6 * press)` |
| Inner shadow | 1.5 dp inset, black 0.18 | Design | |
| Selector drop shadow | 8 dp | Design | Reference navigation has no shadow |
| Card / control / thin blur | 24 / 12 / 8 dp | Design | Cards are static |
| `minSdk` | 33 | Constraint | Blur 31+, lens 33+ |

## Code shape

```kotlin
val wallpaper = rememberLayerBackdrop()
val page = rememberLayerBackdrop()
val bar = rememberCombinedBackdrop(wallpaper, page)

val pill = rememberLayerBackdrop()
val row = rememberLayerBackdrop()
val idle = rememberCombinedBackdrop(page, pill)
val movingBackdrop = rememberCombinedBackdrop(idle, row)

val moving = dragging || press > 0.01f

// selector
Modifier
    .zIndex(if (moving) 1f else 0f)
    .drawBackdrop(
        backdrop = if (moving) movingBackdrop else idle,
        shape = { RoundedCornerShape(50) },
        effects = {
            if (moving) {
                blur(0.dp.toPx())
                lens(24.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
            }
        },
        highlight = { Highlight(0.75.dp, 0.4.dp, rimAlpha, HighlightStyle.Default(0.5f, 45f, 1f)) },
        shadow = null,
        onDrawSurface = { drawRect(frost) }
    )

// row uses the opposite z-order
Modifier.zIndex(if (moving) 0f else 1f).layerBackdrop(row)
```

`rimAlpha` is theme-aware (0.78 dark, 0.96 light); the idle factor is 0.45x. `frost` is white in dark theme and black in light theme.

## Debug checklist

| Symptom | Likely cause | Fix |
|---|---|---|
| Icons or labels look blurred/fogged | Selector has a blur | Remove selector blur; keep 12 dp on the pill |
| Lens looks invisible or flat | Backdrop lacks the row (or any structure) | Add `row` to the moving backdrop; a lens over uniform tint does nothing |
| Two bright rims / heavy halo | Manual rim plus `Highlight.Default`, or default 24 dp `Shadow` | One rim owner; pass an explicit `Highlight`; pass `shadow = null` |
| Dark, muddy, foggy glass | Tint too high or blur too large | 0.40 nav tint, 12 dp pill blur, 0.12 frost; check light mode |
| Idle selector invisible | It has no effects and no static edge | Add a static rim/highlight (0.45x) and inner shadow; do **not** add blur |
| Movement lens disappeared | Row is above selector in every state | Gate z-order and backdrop with `moving` |
| Glass stretches or shimmers | The sampled backdrop is being scaled | Animate layout width/height; use `layerBlock` for press scale |
| Crash on preview/design switch | Destroying and recreating a native view | Keep persistent panes; switch visibility/active scene only |
| ANR or 200 ms frames | Too many live surfaces | One live surface per screen; static cards; quality tier |

Also: never sample the layer that contains the surface (recursive capture -> native crash). Split wallpaper and page.

## Portability checklist

- **Fallback:** no backdrop -> Material/opaque control; no live surface -> static gradient + border + manual rim. The static path must be recognizable.
- **Performance:** one live surface per screen; keep it small; never animate blur radius; reuse combined backdrops; pause when not visible; measure `dumpsys gfxinfo` on a phone, not an emulator.
- **Accessibility:** check text contrast in dark/light; do not encode selection only by tint (change icon size/weight too); 48 dp targets; keep `Role.Tab`, `selectableGroup`, `Role.Switch`, `ProgressBarRangeInfo`; respect reduced motion.
- **Tests:** unit-test `selectorState`, z-order, and backdrop mapping; pin the token table; test both fallback paths; pin the dependency; screenshot dark/light and idle/moving.
- **Integration:** app-only; never sample your own layer; do not ship third-party proprietary assets; document reference versus design-choice values.
- **Dependency:** pin `1.0.1` and re-run the compatibility spike before any major upgrade.

## Files

- [reference.md](reference.md) — full library model, layer graph, contract, tokens, pitfalls, performance, accessibility, testing, and integration.
- [templates/LiquidGlassSkeleton.kt](templates/LiquidGlassSkeleton.kt) — shape-only shell/nav/state skeleton; adapt it, do not treat it as drop-in.
