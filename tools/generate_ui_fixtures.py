"""Original artwork and tagged audio for emulator/UI tests; never bundled in the app.

Requires Pillow, numpy, soundfile and mutagen. Output: app/src/androidTest/assets/music.
"""
from pathlib import Path
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import soundfile as sf
from mutagen.flac import FLAC, Picture

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/androidTest/assets/music"
OUT.mkdir(parents=True, exist_ok=True)
FONT = "C:/Windows/Fonts/arial.ttf"
BOLD = "C:/Windows/Fonts/arialbd.ttf"

def cover(name, bg, ink, sun, number):
    im = Image.new("RGB", (900, 900), bg)
    d = ImageDraw.Draw(im)
    d.ellipse((430, 200, 790, 560), fill=sun)
    d.polygon([(0, 570), (300, 400), (900, 680), (900, 900), (0, 900)], fill=ink)
    d.polygon([(0, 710), (460, 580), (900, 810), (900, 900), (0, 900)], fill=bg)
    d.text((55, 48), "TWILIGHT SESSIONS  /  " + number, fill=ink, font=ImageFont.truetype(FONT, 22))
    d.text((48, 90), name, fill=ink, font=ImageFont.truetype(BOLD, 96), spacing=-8)
    d.text((55, 832), "ORIGINAL UI TEST ARTWORK", fill=ink, font=ImageFont.truetype(FONT, 20))
    return im

albums = [
    ("Afterglow", "Paper Lantern", "#DCC8AE", "#754936", "#D78555", "01", ["Golden Hour", "A Quiet Return"]),
    ("Blue Hours", "North Shore", "#BACBD0", "#344E60", "#F0DFC3", "02", ["Blue Hours", "Low Tide"]),
    ("Still Life", "Moss Garden", "#DADDC8", "#4F6451", "#AE9370", "03", ["Open Windows", "Somewhere Slowly"]),
]
sample_rate = 44100
t = np.arange(sample_rate * 42) / sample_rate
for index, (album, artist, bg, ink, sun, number, titles) in enumerate(albums):
    picture_path = OUT / (number + ".jpg")
    cover(album.replace(" ", "\n"), bg, ink, sun, number).save(picture_path, quality=93)
    for j, title in enumerate(titles):
        file = OUT / (f"{index * 2 + j + 1:02d}-" + title.replace(" ", "-") + ".flac")
        # A quiet, real stereo signal for testing decoder/clock/seek. No external recordings.
        signal = (0.025 * np.sin(2 * math.pi * (220 + 55 * index) * t) +
                  0.015 * np.sin(2 * math.pi * (330 + 55 * j) * t))
        sf.write(file, np.column_stack([signal, signal]), sample_rate, subtype="PCM_16")
        tags = FLAC(file)
        tags["title"] = title
        tags["artist"] = artist
        tags["album"] = album
        tags["tracknumber"] = str(j + 1)
        picture = Picture()
        picture.type = 3
        picture.mime = "image/jpeg"
        picture.width = picture.height = 900
        picture.depth = 24
        picture.data = picture_path.read_bytes()
        tags.add_picture(picture)
        tags.save()
        file.with_suffix(".lrc").write_text(
            "[00:00.00]风把暮色带进窗沿\n[00:06.00]我们把时间交给唱片\n"
            "[00:12.00]让一首歌慢慢走远\n[00:18.00]听见安静，也听见想念\n"
            "[00:24.00]在蓝色的夜里停留\n[00:30.00]直到下一次日落\n", encoding="utf-8")
    print(album)
