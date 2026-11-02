"""Side-by-side comparison sheets: the Glass design in light mode and its three dark-mode candidates.

  python tools/design-compare/compare.py --android src/android/app/build/outputs/roborazzi \
      --windows artifacts/windows-screenshots --out docs/design-comparison

Android input: the Roborazzi output (`./gradlew :app:recordRoborazziDebug`), one folder per case
(light-en, dark-graphite-en, …). Windows input: `Pairly.Windows.exe --render-screenshots <folder>`.
Either input is optional.
"""
import argparse
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

DARK = [("graphite", "Graphite"), ("navy", "Navy"), ("black", "Black")]
ANDROID_SCREENS = [
    ("home", "Home"), ("onboarding-00-welcome", "Setup: welcome"), ("onboarding-05-notifications", "Setup: a permission step"),
    ("features", "Features"), ("feature-blocked", "When Android blocks a permission"), ("settings", "Settings"),
]
BG = (241, 243, 247)
INK = (24, 26, 33)
MUTED = (96, 101, 115)


def font(size, bold=False):
    names = ["DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf", "arialbd.ttf" if bold else "arial.ttf", "segoeuib.ttf" if bold else "segoeui.ttf"]
    for name in names:
        for folder in ["", "/usr/share/fonts/truetype/dejavu", "C:/Windows/Fonts"]:
            try:
                return ImageFont.truetype(os.path.join(folder, name) if folder else name, size)
            except OSError:
                continue
    return ImageFont.load_default()


def framed(path, width, radius, bezel):
    """A screenshot with rounded corners, a device bezel and a soft shadow."""
    shot = Image.open(path).convert("RGB")
    height = round(shot.height * width / shot.width)
    shot = shot.resize((width, height), Image.LANCZOS)
    w, h = width + 2 * bezel, height + 2 * bezel
    pad = 18
    canvas = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), BG + (0,))
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((pad, pad + 6, pad + w, pad + h + 6), radius + bezel, fill=(0, 0, 0, 70))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(10)))
    ImageDraw.Draw(canvas).rounded_rectangle((pad, pad, pad + w, pad + h), radius + bezel, fill=(28, 30, 36, 255))
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1), radius, fill=255)
    canvas.paste(shot, (pad + bezel, pad + bezel), mask)
    return canvas


def sheet(title, subtitle, columns, rows, out, width=300, radius=26, bezel=8, top_gap=6):
    """columns: header texts; rows: (row label, [image path or None per column])."""
    cells = [[framed(p, width, radius, bezel) if p and os.path.exists(p) else None for p in paths] for _, paths in rows]
    if not any(c for r in cells for c in r):
        print("skipped (no screenshots):", out)
        return
    cell_w = max(c.width for r in cells for c in r if c)
    cell_h = max(c.height for r in cells for c in r if c)
    label_w = 120 if any(label for label, _ in rows) else 30
    margin, header = 48, 150
    W = margin * 2 + label_w + cell_w * len(columns)
    H = header + 50 + (cell_h + top_gap) * len(rows) + margin
    img = Image.new("RGBA", (W, H), BG + (255,))
    d = ImageDraw.Draw(img)
    d.text((margin, 40), title, font=font(40, True), fill=INK)
    d.text((margin, 94), subtitle, font=font(20), fill=MUTED)
    for i, name in enumerate(columns):
        x = margin + label_w + i * cell_w + cell_w // 2
        d.text((x, header + 20), name, font=font(24, True), fill=INK, anchor="mm")
    for r, (label, _) in enumerate(rows):
        y = header + 50 + r * (cell_h + top_gap)
        if label:
            d.text((margin, y + cell_h // 2), label, font=font(22, True), fill=MUTED, anchor="lm")
        for c, cell in enumerate(cells[r]):
            if cell:
                img.alpha_composite(cell, (margin + label_w + c * cell_w + (cell_w - cell.width) // 2, y))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.convert("RGB").save(out, optimize=True)
    print(out, img.size)


def android(root, out):
    def shot(case, screen):
        return os.path.join(root, case, f"{screen}.png")

    columns = [name for _, name in DARK]
    sheet("Pick a dark mode: Home", "Three dark-mode candidates for the Glass design. Try them in the app: Settings > Version (tap 7x) > Design preview.",
          columns, [("Home", [shot(f"dark-{d}-en", "home") for d, _ in DARK]), ("Settings", [shot(f"dark-{d}-en", "settings") for d, _ in DARK])],
          os.path.join(out, "android-00-dark-candidates.png"))
    for index, (screen, name) in enumerate(ANDROID_SCREENS, start=1):
        sheet(name, "Light mode, then each dark-mode candidate.", ["Light"] + columns,
              [("", [shot("light-en", screen)] + [shot(f"dark-{d}-en", screen) for d, _ in DARK])],
              os.path.join(out, f"android-{index:02d}-{screen}.png"), width=260)
    sheet("English, German and the largest font size", "Light mode: the same screens in English, in German, and in German at 200% font size.",
          ["Home", "Permission step", "Settings"],
          [(label, [shot(case, s) for s in ("home", "onboarding-05-notifications", "settings")])
           for case, label in (("light-en", "English"), ("light-de", "Deutsch"), ("light-de-large", "200 %"))],
          os.path.join(out, "android-20-languages-and-font-size.png"), width=260)


def windows(root, out):
    def shot(look, page, lang="en"):
        return os.path.join(root, f"{lang}-{look}-{page}.png")

    looks = ["light"] + [f"dark-{d}" for d, _ in DARK]
    for page, name in (("home", "Home"), ("settings", "Settings"), ("messages", "Messages")):
        sheet(f"Windows: {name}", "Light mode and the three dark-mode candidates (the same as on the phone). Try them: Settings > General > About, click the version 7x.",
              ["Light", "Graphite"], [("", [shot(looks[0], page), shot(looks[1], page)])],
              os.path.join(out, f"windows-{page}.png"), width=760, radius=10, bezel=0, top_gap=14)
        sheet(f"Windows: {name}, dark candidates", "Navy and Black.", ["Navy", "Black"], [("", [shot(looks[2], page), shot(looks[3], page)])],
              os.path.join(out, f"windows-{page}-dark.png"), width=760, radius=10, bezel=0, top_gap=14)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--android")
    parser.add_argument("--windows")
    parser.add_argument("--out", default="docs/design-comparison")
    args = parser.parse_args()
    if args.android:
        android(args.android, args.out)
    if args.windows:
        windows(args.windows, args.out)
