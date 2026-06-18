#!/usr/bin/env python3
"""
Генератор иконки приложения Epharm — фирменный знак-чек в палитре Claude.

Коралловый градиент (плавный) + белый чек по центру с рваным нижним краем +
мягкий штамп-плюс сверху по центру. Рисуем с супер-сэмплингом ×SS и даунскейлом
LANCZOS — идеальные сглаживания без SVG-рендерера.

Выход (assets/icon/):
  app_icon.png            1024² — полная иконка (iOS + legacy Android)
  app_icon_bg.png         1024² — только градиент (Android adaptive background)
  app_icon_foreground.png 1024² — чек+штамп на прозрачном, в safe-zone (adaptive fg)
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter

SS = 4                      # супер-сэмплинг
BASE = 1024
S = BASE * SS               # рабочий холст
OUT = os.path.join(os.path.dirname(__file__), "..", "assets", "icon")

# ── Палитра Claude (ярко-оранжевый коралл) ───────────────────────────────────
# Чуть ярче и оранжевее основного #D97757: низ не уходит в коричневый, переход
# плавный — иконка читается «ярко-оранжевой», а не блёклой/бурой.
CORAL_TOP = (237, 151, 104)   # #ED9768  светлый тёплый коралл
CORAL_MID = (224, 126, 82)    # #E07E52  ярко-оранжевый
CORAL_BOT = (206, 99, 56)     # #CE6338  насыщенный оранжевый (без бурого)
STAMP = (194, 84, 46)         # #C2542E  диск штампа — глубже фона для контраста
WHITE = (255, 255, 255)
LINE = (224, 126, 82, 115)    # «текст» чека — коралл с прозрачностью на белом


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(size):
    """Вертикальный 3-стоповый коралловый градиент (плавный)."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    col = []
    for y in range(size):
        t = y / (size - 1)
        c = lerp(CORAL_TOP, CORAL_MID, t / 0.5) if t < 0.5 else lerp(CORAL_MID, CORAL_BOT, (t - 0.5) / 0.5)
        col.append(c)
    for y in range(size):
        c = col[y]
        for x in range(size):
            px[x, y] = c
    return img


def receipt_outline(cx, top, bottom, half_w, cr, notches, depth):
    """Контур чека: скруглённый верх + рваный (зигзаг) низ. Точки по часовой."""
    left, right = cx - half_w, cx + half_w
    pts = []
    steps = 16
    # верх-левый скруглённый угол (180°→270°)
    for i in range(steps + 1):
        a = math.pi + (math.pi / 2) * (i / steps)
        pts.append((left + cr + cr * math.cos(a), top + cr + cr * math.sin(a)))
    # верх-правый скруглённый угол (270°→360°)
    for i in range(steps + 1):
        a = -math.pi / 2 + (math.pi / 2) * (i / steps)
        pts.append((right - cr + cr * math.cos(a), top + cr + cr * math.sin(a)))
    # правая грань вниз → рваный низ справа-налево
    seg = (2 * half_w) / (2 * notches)
    for k in range(2 * notches + 1):
        x = right - k * seg
        y = bottom if k % 2 == 0 else bottom - depth
        pts.append((x, y))
    return pts


def plus(draw, cx, cy, arm, thick, color):
    """Крест с закруглёнными концами (две капсулы)."""
    r = thick / 2
    draw.rounded_rectangle([cx - thick / 2, cy - arm, cx + thick / 2, cy + arm], radius=r, fill=color)
    draw.rounded_rectangle([cx - arm, cy - thick / 2, cx + arm, cy + thick / 2], radius=r, fill=color)


def draw_mark(layer, k):
    """Рисует чек + штамп на слое RGBA. k — масштаб от BASE к рабочему холсту."""
    d = ImageDraw.Draw(layer)
    cx = (BASE / 2) * k
    # Геометрия чека (в BASE-координатах, умноженных на k) — по центру, низ оптически.
    top, bottom = 300 * k, 850 * k
    half_w, cr = 232 * k, 48 * k
    notches, depth = 7, 24 * k
    outline = [(x, y) for (x, y) in receipt_outline(cx, top, bottom, half_w, cr, notches, depth)]
    d.polygon(outline, fill=WHITE)

    # «Текст» — три капсулы по центру (коралл с альфой).
    tl = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    dt = ImageDraw.Draw(tl)
    for (w, y) in [(300, 486), (322, 568), (250, 650)]:
        x0, x1 = cx - (w / 2) * k, cx + (w / 2) * k
        yy = y * k
        dt.rounded_rectangle([x0, yy - 16 * k, x1, yy + 16 * k], radius=16 * k, fill=LINE)
    layer.alpha_composite(tl)

    # Штамп сверху по центру: белое кольцо + коралловый диск + белый плюс.
    scx, scy = cx, top
    d.ellipse([scx - 146 * k, scy - 146 * k, scx + 146 * k, scy + 146 * k], fill=WHITE)
    d.ellipse([scx - 122 * k, scy - 122 * k, scx + 122 * k, scy + 122 * k], fill=STAMP)
    plus(d, scx, scy, arm=60 * k, thick=30 * k, color=WHITE)


def soft_shadow(size, k):
    """Мягкая тень силуэта чека+кольца под знаком."""
    sil = Image.new("L", (size, size), 0)
    ds = ImageDraw.Draw(sil)
    cx = (BASE / 2) * k
    top, bottom, half_w, cr = 300 * k, 850 * k, 232 * k, 48 * k
    ds.polygon(receipt_outline(cx, top, bottom, half_w, cr, 7, 24 * k), fill=120)
    ds.ellipse([cx - 146 * k, top - 146 * k, cx + 146 * k, top + 146 * k], fill=120)
    sil = sil.filter(ImageFilter.GaussianBlur(20 * k))
    sh = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sh.putalpha(sil)
    # тёмно-коралловая тень
    tint = Image.new("RGBA", (size, size), (120, 50, 28, 255))
    tint.putalpha(sil)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(tint, (0, int(18 * k)))
    return out


def build_full():
    bg = gradient(S).convert("RGBA")
    bg.alpha_composite(soft_shadow(S, SS))
    mark = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_mark(mark, SS)
    bg.alpha_composite(mark)
    return bg.resize((BASE, BASE), Image.LANCZOS).convert("RGB")


def build_foreground():
    """Чек+штамп на прозрачном для Android adaptive foreground.

    flutter_launcher_icons оборачивает foreground в <inset 16%>, поэтому знак
    делаем почти во весь кадр (после инсета он сам сядет в safe-zone ~0.66).
    Обрезаем по bbox знака и масштабируем по высоте до ~0.98 холста, центрируем.
    """
    mark = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_mark(mark, SS)
    mark = mark.resize((BASE, BASE), Image.LANCZOS)
    bbox = mark.getbbox()
    cropped = mark.crop(bbox)
    target = int(BASE * 0.98)
    scale = target / max(cropped.width, cropped.height)
    inner = cropped.resize((round(cropped.width * scale), round(cropped.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    canvas.alpha_composite(inner, ((BASE - inner.width) // 2, (BASE - inner.height) // 2))
    return canvas


def main():
    os.makedirs(OUT, exist_ok=True)
    build_full().save(os.path.join(OUT, "app_icon.png"))
    gradient(S).resize((BASE, BASE), Image.LANCZOS).save(os.path.join(OUT, "app_icon_bg.png"))
    build_foreground().save(os.path.join(OUT, "app_icon_foreground.png"))
    print("icons written to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
