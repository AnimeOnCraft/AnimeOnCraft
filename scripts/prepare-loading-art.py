"""Build the early loader's font atlas from the existing launcher font; all outputs stay in the workspace."""
from pathlib import Path
import struct
import subprocess
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "minecraft/integration/loading-window/src/main/resources/aoc_loading"
OUT.mkdir(parents=True, exist_ok=True)
FONT = ROOT / "minecraft/integration/neoforge/src/main/resources/assets/animeoncraft/font/menu.ttf"
font = ImageFont.truetype(str(FONT), 64)
ascent, descent = font.getmetrics()
if 4 + ascent + descent > 96:
    raise RuntimeError("Font metrics exceed the loading atlas cell")
characters = list(dict.fromkeys([*range(32, 127), *range(0x410, 0x450), 0x401, 0x451, 0x2013, 0x2014, 0x2026, 0x2022, 0xb7]))
atlas = Image.new("RGBA", (1024, 1536), (255, 255, 255, 0))
draw = ImageDraw.Draw(atlas)
with (OUT / "font.bin").open("wb") as output:
    output.write(struct.pack(">5i", 0x414F4346, atlas.width, atlas.height, ascent, len(characters)))
    for index, code in enumerate(characters):
        x, y = (index % 12) * 80, (index // 12) * 96
        char = chr(code)
        if draw.textbbox((x + 4, y + 4 + ascent), char, font=font, anchor="ls")[2] > x + 80:
            raise RuntimeError(f"Glyph exceeds cell: {char}")
        draw.text((x + 4, y + 4 + ascent), char, font=font, fill=(255, 255, 255, 255), anchor="ls")
        output.write(struct.pack(">4i", code, x, y, round(font.getlength(char) * 1024)))
atlas.save(OUT / "font.png", optimize=True)
ffmpeg = ROOT / ".tools/video-python/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe"
enhanced = ROOT / ".tools/main-menu-upscale-20261025/enhanced-frames/frame_0000.png"
if enhanced.exists():
    subprocess.run([str(ffmpeg), "-y", "-i", str(enhanced), "-vf", "scale=1920:1080:flags=lanczos", str(OUT / "background.png")], check=True, capture_output=True)
else:
    original = ROOT / "minecraft/integration/neoforge/src/main/resources/assets/animeoncraft/textures/title/frame_0000.jpg"
    with Image.open(original) as image:
        if image.size != (1920, 1080):
            raise RuntimeError("Prepare the Full HD title background first")
        image.save(OUT / "background.png", optimize=True)
print(f"Prepared Full HD loading artwork and {len(characters)} glyphs in {OUT}")
