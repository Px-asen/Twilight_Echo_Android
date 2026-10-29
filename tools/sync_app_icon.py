"""Import the desktop app icon as Android launcher, themed and notification icons.

Usage: python tools/sync_app_icon.py --source ../Twilight_Echo/build/icon.png
Requires Pillow. Generated resources are checked in; ordinary builds need no Python.
"""

import argparse
from pathlib import Path

from PIL import Image, ImageChops


ROOT = Path(__file__).resolve().parents[1]
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT.parent / "Twilight_Echo/build/icon.png")
    args = parser.parse_args()
    source = Image.open(args.source).convert("RGBA")
    res = ROOT / "app/src/main/res"
    notification = ROOT / "media-session/src/main/res"

    # Keep an unmodified desktop reference in the test APK, independently of the
    # Android foreground extraction, so device tests catch placeholder artwork.
    save(source.resize((96, 96), Image.Resampling.LANCZOS),
         ROOT / "app/src/androidTest/assets/icons/desktop-reference.png")

    # Extract the blue ribbon's silhouette, excluding the near-white tile. Retain the
    # antialiased boundary for Android 13 themed icons and the single-color status icon.
    red, _, blue, alpha = source.split()
    mask = ImageChops.multiply(
        ImageChops.subtract(blue, red).point(lambda value: round(255 * min(1, max(0, (value - 10) / 12)))),
        alpha,
    )
    # Android supplies its own full-bleed background and mask. Import only the ribbon,
    # not the desktop tile's rounded edge (which otherwise leaves dark corner outlines).
    foreground = Image.new("RGBA", (432, 432))
    icon = source.copy()
    icon.putalpha(mask)
    foreground.alpha_composite(icon.resize((288, 288), Image.Resampling.LANCZOS), (72, 72))
    save(foreground, res / "drawable-nodpi/ic_launcher_foreground.png")

    themed = Image.new("RGBA", (432, 432))
    ribbon = Image.new("RGBA", (288, 288), "white")
    ribbon.putalpha(mask.resize((288, 288), Image.Resampling.LANCZOS))
    themed.alpha_composite(ribbon, (72, 72))
    save(themed, res / "drawable-nodpi/ic_launcher_monochrome.png")
    bounds = mask.getbbox()
    if bounds is None:
        raise ValueError("The desktop icon has no blue ribbon")
    silhouette = mask.crop(bounds)
    for density, scale in DENSITIES.items():
        save(source.resize((round(48 * scale), round(48 * scale)), Image.Resampling.LANCZOS),
             res / f"mipmap-{density}/ic_launcher.png")
        side = round(24 * scale)
        inset = round(2 * scale)
        art = silhouette.copy()
        art.thumbnail((side - inset * 2, side - inset * 2), Image.Resampling.LANCZOS)
        small = Image.new("RGBA", (side, side))
        glyph = Image.new("RGBA", art.size, "white")
        glyph.putalpha(art)
        small.alpha_composite(glyph, ((side - art.width) // 2, (side - art.height) // 2))
        save(small, notification / f"drawable-{density}/ic_twilight_notification.png")


if __name__ == "__main__":
    main()
