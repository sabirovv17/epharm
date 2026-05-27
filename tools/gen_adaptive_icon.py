#!/usr/bin/env python3
"""
Генерация Android adaptive icon foreground (только марк без фона).

Adaptive icon формат:
- 108×108 dp canvas (1.5× от 72-dp launcher), но видимая зона — центральные 66×66 dp
- Foreground: transparent + mark
- Background: solid color (config через @color/ic_launcher_background)
- ОС применяет маску (круг / squircle / etc.) сама

Foreground PNG sizes (px per density):
  mdpi (1×):    108
  hdpi (1.5×):  162
  xhdpi (2×):   216
  xxhdpi (3×):  324
  xxxhdpi (4×): 432
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

# Размер foreground при canonical 108×108 dp с 25% safe-zone padding с каждой стороны.
# То есть mark занимает центральные ~54 dp (66 видимая зона на 108).
SAFE_ZONE = 66.0
CANVAS = 108.0

GREEN_600 = (22, 201, 122, 255)
BLUE_600 = (42, 43, 226, 255)
WHITE = (255, 255, 255, 255)

OUT = Path(__file__).parent / "build_icons" / "android_adaptive"
OUT.mkdir(parents=True, exist_ok=True)


def make_fg(px: int) -> Image.Image:
    """Создаёт прозрачный canvas px×px и рисует receipt+stamp по сетке 64×64
    в центре, занимая SAFE_ZONE / CANVAS ≈ 61% сторон."""
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    mark_diam = px * (SAFE_ZONE / CANVAS) * 1.05  # чуть больше safe-zone для веса
    scale = mark_diam / 64.0
    ox = (px - 64 * scale) / 2
    oy = (px - 64 * scale) / 2

    def sx(v: float) -> float:
        return ox + v * scale

    def sy(v: float) -> float:
        return oy + v * scale

    # Receipt body — белый с зелёным контуром, zig-zag bottom
    body_top, body_bottom = 10.0, 53.0
    body_left, body_right = 16.0, 48.0
    notches = 5
    notch_amp = 2.5
    notch_step = (body_right - body_left) / (notches * 2)

    pts: list[tuple[float, float]] = []
    pts.append((body_left, body_top))
    pts.append((body_right, body_top))
    pts.append((body_right, body_bottom - notch_amp))
    for i in range(notches * 2 + 1):
        x = body_right - i * notch_step
        y = body_bottom + notch_amp if (i % 2 == 1) else body_bottom - notch_amp
        pts.append((x, y))
    pts.append((body_left, body_bottom - notch_amp))
    pts.append((body_left, body_top))

    poly = [(sx(x), sy(y)) for (x, y) in pts]
    draw.polygon(poly, fill=WHITE)
    stroke_w = max(2, int(3 * scale))
    draw.line(poly + [poly[0]], fill=GREEN_600, width=stroke_w, joint="curve")

    # Линии
    line_color = (GREEN_600[0], GREEN_600[1], GREEN_600[2], int(255 * 0.45))
    line_thick = max(2, int(2.2 * scale))
    for ly, lw in [(26, 20), (32, 14), (38, 17)]:
        x1 = 32 - lw / 2
        x2 = 32 + lw / 2
        draw.line([(sx(x1), sy(ly)), (sx(x2), sy(ly))], fill=line_color, width=line_thick)

    # Stamp + крест
    disc_cx, disc_cy, disc_r = 32.0, 13.0, 11.0
    draw.ellipse(
        [sx(disc_cx - disc_r), sy(disc_cy - disc_r), sx(disc_cx + disc_r), sy(disc_cy + disc_r)],
        fill=BLUE_600,
    )
    cross_w, cross_l = 3.0, 11.0
    draw.rounded_rectangle(
        [
            sx(disc_cx - cross_l / 2),
            sy(disc_cy - cross_w / 2),
            sx(disc_cx + cross_l / 2),
            sy(disc_cy + cross_w / 2),
        ],
        radius=max(2, int(0.8 * scale)),
        fill=WHITE,
    )
    draw.rounded_rectangle(
        [
            sx(disc_cx - cross_w / 2),
            sy(disc_cy - cross_l / 2),
            sx(disc_cx + cross_w / 2),
            sy(disc_cy + cross_l / 2),
        ],
        radius=max(2, int(0.8 * scale)),
        fill=WHITE,
    )

    return img


def main() -> None:
    densities = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for d, px in densities.items():
        target = OUT / f"drawable-{d}"
        target.mkdir(exist_ok=True)
        img = make_fg(px)
        img.save(target / "ic_launcher_foreground.png", "PNG", optimize=True)
        print(f"✓ {d}: {px}px")


if __name__ == "__main__":
    main()
