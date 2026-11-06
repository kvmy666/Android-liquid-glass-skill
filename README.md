# Liquid Glass

![Preview](media/preview.gif)

## Install

```bash
mkdir -p ~/.agents/skills/liquid-glass ~/.claude/skills && cp -r skills/liquid-glass/. ~/.agents/skills/liquid-glass/ && ln -sfn ~/.agents/skills/liquid-glass ~/.claude/skills/liquid-glass
```

Uses `io.github.kyant0:backdrop:1.0.1`. Format: Agent Skills.

## Prompt

```text
Use the `liquid-glass` skill. Build a complete Android Compose bottom navigation bar with a floating glass pill, a moving selector, frosted blur, transparency, edge highlight, inner/drop shadow, and movement-only lens/chromatic refraction. Include tappable tabs, press/drag animation, a settings slider/control row, and keep icons/labels sharp at idle. Follow the skill's layer/backdrop order exactly and add a static fallback when Backdrop is unavailable.
```
