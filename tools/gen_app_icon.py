#!/usr/bin/env python3
"""
Генерация app-icon Receipt Stamp для iOS + Android.

Источник дизайна: _reference/design_handoff_pharmapay/design-tokens.md §7.
Базовый канвас 1024×1024 (App Store master), все мельче ресайзятся `sips` после.

Anatomy (по §7.1, виде GREEN-вариации с §7.5):
  - Squircle tile: gradient `grad/header` (#21D17A → #16A65C, 180°)
  - Receipt body: WHITE fill, GREEN 3-px outline (#16C97A), zig-zag дно (5 V-notches)
  - Receipt lines: GREEN @ 45% opacity, 3 строки убывающей длины
  - Stamp disc: BLUE (#2A2BE2) над верхним краем чека
  - Cross: WHITE plus внутри stamp

Без сторонних SVG-рендереров — рисуем напрямую через PIL.
"""

from __future__ import annotations

import math
import os
from pathlib import Path

from PIL import Image, ImageDraw

# ───────────── константы дизайна ─────────────
SIZE = 1024  # master canvas
R = SIZE / 2

# §7.5: «mark sits centred at ≈ 66% of tile width»
MARK_DIAM_RATIO = 0.66
MARK_DIAM = SIZE * MARK_DIAM_RATIO

# §1 палитра
GREEN_500 = (33, 209, 122, 255)   # #21D17A
GREEN_600 = (22, 201, 122, 255)   # #16C97A
GREEN_A65C = (22, 166, 92, 255)   # #16A65C (header gradient bottom)
BLUE_600 = (42, 43, 226, 255)     # #2A2BE2
WHITE = (255, 255, 255, 255)

# §7.5: squircle radius ≈ 22.4% (но iOS сам кладёт mask поверх квадратной иконки,
# мы создаём ПЛОСКИЙ квадрат — система обрежет в squircle сама).

OUT_DIR = Path(__file__).parent / "build_icons"
OUT_DIR.mkdir(exist_ok=True)


def make_master() -> Image.Image:
    """Возвращает мастер-иконку 1024×1024 RGBA."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))

    # 1) Зелёный gradient-fon (вертикальный, top → bottom): #21D17A → #16A65C
    bg = Image.new("RGBA", (SIZE, SIZE), GREEN_500)
    grad = Image.new("L", (1, SIZE), 0)
    for y in range(SIZE):
        # linear blend 0 → 1
        t = y / (SIZE - 1)
        # Используем альфу маски для накатывания нижнего цвета поверх верхнего
        grad.putpixel((0, y), int(255 * t))
    grad = grad.resize((SIZE, SIZE))
    bottom = Image.new("RGBA", (SIZE, SIZE), GREEN_A65C)
    bg = Image.composite(bottom, bg, grad)
    img.paste(bg, (0, 0))

    # 2) Receipt mark — рисуем по сетке 64×64 и масштабируем до MARK_DIAM.
    #    Receipt body 32×43, stamp disc 22 dia (см. §7.3 «Receipt body 32×43, stamp ≈22»).
    scale = MARK_DIAM / 64.0
    # Сдвиг — центрировано
    ox = (SIZE - 64 * scale) / 2
    oy = (SIZE - 64 * scale) / 2

    def sx(v: float) -> float:
        return ox + v * scale

    def sy(v: float) -> float:
        return oy + v * scale

    draw = ImageDraw.Draw(img)

    # — Receipt body (32×43, с zig-zag bottom edge) —
    # координаты в локальной сетке 64×64, центр x=32, верх y=10, низ y=53
    body_top = 10.0
    body_left = 16.0
    body_right = 48.0
    body_bottom = 53.0
    notches = 5
    # 5 V-notches: 5 пиков и 5 впадин (10 точек) равномерно по нижней кромке.
    notch_amp = 2.5  # амплитуда зубчиков
    notch_step = (body_right - body_left) / (notches * 2)

    # Контур body: top-left → top-right (radius 2 в углах для скругления),
    # right → zig-zag bottom → left → close.
    pts: list[tuple[float, float]] = []
    # Top edge — простая прямая (округлим углы позже не нужно, тонкая stroke).
    pts.append((body_left, body_top))
    pts.append((body_right, body_top))
    pts.append((body_right, body_bottom - notch_amp))
    # zig-zag bottom (start с правой стороны идём влево)
    for i in range(notches * 2 + 1):
        # x от right к left
        x = body_right - i * notch_step
        # чередуем низ и верх
        y = body_bottom if (i % 2 == 1) else body_bottom - notch_amp * 2
        # Скорректируем чтоб «пик» был внизу:
        y = body_bottom + notch_amp if (i % 2 == 1) else body_bottom - notch_amp
        pts.append((x, y))
    pts.append((body_left, body_bottom - notch_amp))
    pts.append((body_left, body_top))

    # Заливка белым
    poly_scaled = [(sx(x), sy(y)) for (x, y) in pts]
    draw.polygon(poly_scaled, fill=WHITE)

    # Контур зелёный, толщина 3 px в исходной сетке → scaled
    stroke_w = max(2, int(3 * scale))
    draw.line(poly_scaled + [poly_scaled[0]], fill=GREEN_600, width=stroke_w, joint="curve")

    # — Receipt lines (3 строки, green-600 @ 45% opacity, descending widths 20/14/17 из §7.1) —
    line_color = (GREEN_600[0], GREEN_600[1], GREEN_600[2], int(255 * 0.45))
    line_thick = max(2, int(2.2 * scale))
    line_y = [26, 32, 38]  # 3 строки, вертикальный шаг 6 в сетке 64
    line_w = [20, 14, 17]
    for ly, lw in zip(line_y, line_w):
        x1 = 32 - lw / 2
        x2 = 32 + lw / 2
        draw.line(
            [(sx(x1), sy(ly)), (sx(x2), sy(ly))],
            fill=line_color,
            width=line_thick,
        )

    # — Stamp disc (blue, r=11, centered x=32, y=14, overlaps top of receipt) —
    disc_cx, disc_cy = 32.0, 13.0
    disc_r = 11.0
    bbox = [
        sx(disc_cx - disc_r),
        sy(disc_cy - disc_r),
        sx(disc_cx + disc_r),
        sy(disc_cy + disc_r),
    ]
    draw.ellipse(bbox, fill=BLUE_600)

    # — White cross внутри stamp (14×3 + 3×14, radius 0.8) —
    cross_w = 3.0
    cross_l = 11.0
    # горизонтальная штанга
    h_bbox = [
        sx(disc_cx - cross_l / 2),
        sy(disc_cy - cross_w / 2),
        sx(disc_cx + cross_l / 2),
        sy(disc_cy + cross_w / 2),
    ]
    draw.rounded_rectangle(h_bbox, radius=max(2, int(0.8 * scale)), fill=WHITE)
    # вертикальная штанга
    v_bbox = [
        sx(disc_cx - cross_w / 2),
        sy(disc_cy - cross_l / 2),
        sx(disc_cx + cross_w / 2),
        sy(disc_cy + cross_l / 2),
    ]
    draw.rounded_rectangle(v_bbox, radius=max(2, int(0.8 * scale)), fill=WHITE)

    return img


def main() -> None:
    master = make_master()
    master_path = OUT_DIR / "icon-1024.png"
    # iOS App Store master — БЕЗ альфа-канала (требование App Store).
    master_rgb = Image.new("RGB", master.size, (255, 255, 255))
    master_rgb.paste(master, mask=master.split()[3])
    master_rgb.save(master_path, "PNG", optimize=True)
    print(f"✓ master: {master_path}")

    # iOS sizes (точки × scale).
    ios_sizes = {
        "Icon-App-20x20@2x.png": 40,
        "Icon-App-20x20@3x.png": 60,
        "Icon-App-20x20@1x.png": 20,
        "Icon-App-29x29@1x.png": 29,
        "Icon-App-29x29@2x.png": 58,
        "Icon-App-29x29@3x.png": 87,
        "Icon-App-40x40@1x.png": 40,
        "Icon-App-40x40@2x.png": 80,
        "Icon-App-40x40@3x.png": 120,
        "Icon-App-60x60@2x.png": 120,
        "Icon-App-60x60@3x.png": 180,
        "Icon-App-76x76@1x.png": 76,
        "Icon-App-76x76@2x.png": 152,
        "Icon-App-83.5x83.5@2x.png": 167,
        "Icon-App-1024x1024@1x.png": 1024,
    }
    ios_dir = OUT_DIR / "ios"
    ios_dir.mkdir(exist_ok=True)
    for name, px in ios_sizes.items():
        resized = master.resize((px, px), Image.LANCZOS)
        if name == "Icon-App-1024x1024@1x.png":
            # App Store master — RGB без альфы
            rgb = Image.new("RGB", resized.size, (255, 255, 255))
            rgb.paste(resized, mask=resized.split()[3])
            rgb.save(ios_dir / name, "PNG", optimize=True)
        else:
            resized.save(ios_dir / name, "PNG", optimize=True)
    print(f"✓ iOS: {len(ios_sizes)} files → {ios_dir}")

    # Android sizes — стандартный mipmap набор.
    android_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    android_dir = OUT_DIR / "android"
    android_dir.mkdir(exist_ok=True)
    for folder, px in android_sizes.items():
        target_dir = android_dir / folder
        target_dir.mkdir(exist_ok=True)
        resized = master.resize((px, px), Image.LANCZOS)
        resized.save(target_dir / "ic_launcher.png", "PNG", optimize=True)
    print(f"✓ Android: {len(android_sizes)} densities → {android_dir}")


if __name__ == "__main__":
    main()
