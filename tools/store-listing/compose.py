"""Frames the raw store renders with a headline for the Microsoft Store listing.

  python tools/store-listing/compose.py --raw <render folder> --listing store/listing.json --out <folder>

<render folder> holds <lang>-<file>.png from `Pairly.Windows.exe --render-store-screenshots`;
<lang> is the first part of each listing language ("en-us" -> "en"). Output: <out>/<listing
language>/<file>.png at 1920x1080, the size the Store shows best on desktop.
"""
import argparse
import json
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
TOP = (38, 44, 120)       # deep indigo
BOTTOM = (79, 91, 232)    # brand indigo #4F5BE8
WINDOW_WIDTH = 1540
RADIUS = 18


def font(size, bold=False):
    names = (["segoeuib.ttf", "seguisb.ttf"] if bold else ["segoeui.ttf"]) + \
        (["DejaVuSans-Bold.ttf"] if bold else ["DejaVuSans.ttf"])
    for folder in ["C:/Windows/Fonts", "/usr/share/fonts/truetype/dejavu", ""]:
        for name in names:
            path = os.path.join(folder, name) if folder else name
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default()


def background():
    img = Image.new("RGB", (W, H))
    draw = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        draw.line([(0, y), (W, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
    # A soft light glow behind the window.
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).ellipse((W * 0.15, H * 0.35, W * 0.85, H * 1.4), fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    return Image.composite(Image.new("RGB", (W, H), (150, 160, 255)), img, glow)


def compose(raw_path, title, subtitle, out_path):
    canvas = background().convert("RGBA")
    draw = ImageDraw.Draw(canvas)
    draw.text((W // 2, 92), title, font=font(68, bold=True), fill="white", anchor="mm")
    draw.text((W // 2, 162), subtitle, font=font(32), fill=(225, 229, 255), anchor="mm")

    shot = Image.open(raw_path).convert("RGBA")
    height = round(shot.height * WINDOW_WIDTH / shot.width)
    shot = shot.resize((WINDOW_WIDTH, height), Image.LANCZOS)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, shot.width - 1, shot.height - 1), RADIUS, fill=255)
    x, y = (W - WINDOW_WIDTH) // 2, 226

    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((x, y + 18, x + WINDOW_WIDTH, y + height + 18), RADIUS, fill=(10, 12, 40, 150))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(30)))
    canvas.paste(shot, (x, y), mask)  # runs off the bottom edge on purpose

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    canvas.convert("RGB").save(out_path, optimize=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--listing", default="store/listing.json")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    listings = json.load(open(args.listing, encoding="utf-8"))["listings"]
    missing = []
    for language, listing in listings.items():
        prefix = language.split("-")[0]
        for shot in listing["screenshots"]:
            raw = os.path.join(args.raw, f"{prefix}-{shot['file']}.png")
            if not os.path.exists(raw):
                missing.append(raw)
                continue
            out = os.path.join(args.out, language, f"{shot['file']}.png")
            compose(raw, shot["title"], shot["subtitle"], out)
            print(out)
    if missing:
        raise SystemExit("Missing renders:\n" + "\n".join(missing))


if __name__ == "__main__":
    main()
