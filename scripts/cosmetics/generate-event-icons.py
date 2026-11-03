"""Create rarity-star placeholders, or pack explicitly supplied artwork into the fixed six-cell Minecraft atlas.
Optional --source: approved artwork in three columns and two rows.
Default output contains stars only, as requested by the owner for this release.
"""
from pathlib import Path
import argparse, math
from PIL import Image, ImageDraw
ORDER = ["SAKURA_WINGS", "KITSUNE_SAKURA", "RIFT_HALF_MASK", "OMAMORI_CHARM", "SAKURA_CHILD", "RIFT_SEER"]
ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path, default=None)
parser.add_argument("--output", type=Path, default=ROOT / "minecraft/integration/neoforge/src/main/resources/assets/animeoncraft/textures/gui/gacha_rewards.png")
args = parser.parse_args()
atlas = Image.new("RGBA", (1536, 256))
if args.source is None:
    for i, tier in enumerate([5, 4, 4, 4, 3, 3]):
        draw = ImageDraw.Draw(atlas)
        color = (255, 202, 92, 255) if tier == 5 else (211, 135, 255, 255) if tier == 4 else (240, 144, 190, 255)
        for j in range(tier):
            cx = i * 256 + 128 + (j - (tier - 1) / 2) * 42
            points = [(cx + (19 if k % 2 == 0 else 8.4) * math.cos(-math.pi / 2 + k * math.pi / 5), 128 + (19 if k % 2 == 0 else 8.4) * math.sin(-math.pi / 2 + k * math.pi / 5)) for k in range(10)]
            draw.polygon(points, fill=color)
else:
    source = Image.open(args.source).convert("RGBA")
    assert source.width % 3 == 0 and source.height % 2 == 0 and source.width // 3 == source.height // 2, "Expected a 3 x 2 grid of square source cells"
    assert source.getchannel("A").getextrema()[0] == 0, "Source must retain real transparency"
    cell = source.width // 3
    for i, code in enumerate(ORDER):
        icon = source.crop(((i % 3) * cell, (i // 3) * cell, (i % 3 + 1) * cell, (i // 3 + 1) * cell))
        bounds = icon.getchannel("A").getbbox()
        assert bounds, code
        icon = icon.crop(bounds)
        icon.thumbnail((228, 228), Image.Resampling.LANCZOS)
        atlas.alpha_composite(icon, (i * 256 + (256 - icon.width) // 2, (256 - icon.height) // 2))
        a = atlas.crop((i * 256, 0, (i + 1) * 256, 256)).getchannel("A").getbbox()
        assert a and a[0] >= 14 and a[1] >= 14 and a[2] <= 242 and a[3] <= 242, code
args.output.parent.mkdir(parents=True, exist_ok=True)
atlas.save(args.output, optimize=True)
print(f"Packed {len(ORDER)} reward cells: {atlas.width}x{atlas.height} RGBA, 14px minimum margin")
