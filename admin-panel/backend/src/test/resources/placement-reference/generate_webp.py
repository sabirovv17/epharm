"""Synthetic repository-owned test pixels; Pillow is used only to encode fixtures, not by the application."""
from pathlib import Path
from PIL import Image, features
import hashlib

root = Path(__file__).resolve().parent
rgb = Image.new('RGB', (16, 12))
rgba = Image.new('RGBA', (16, 12))
for y in range(12):
    for x in range(16):
        color = ((17*x) % 256, (23*y) % 256, (11*x+7*y) % 256)
        rgb.putpixel((x, y), color)
        rgba.putpixel((x, y), color + ((17*x+11*y) % 256,))
rgb.save(root / 'lossy.webp', format='WEBP', quality=82, method=6)
rgb.save(root / 'lossless.webp', format='WEBP', lossless=True, method=6)
rgba.save(root / 'alpha.webp', format='WEBP', quality=90, method=6, exact=True)
for name in ('lossy.webp', 'lossless.webp', 'alpha.webp'):
    data = (root / name).read_bytes()
    print(name, len(data), hashlib.sha256(data).hexdigest(), data[12:16].decode('ascii'))
print('Pillow', Image.__version__, 'libwebp', features.version('webp'))
