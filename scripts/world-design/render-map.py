#!/usr/bin/env python3
"""Render the overview of a pregenerated states world (World Design stage 6).

    python3 scripts/world-design/render-map.py <map.bin> <out-dir> [--max=4096]

Input: <world>/animeoncraft-map/map-<radius>-<step>.bin written by `/aoc worldgen map` (format in MapRender.java).
Writes <out-dir>/map.png (relief only) and <out-dir>/map-overlay.png (regions, roads and objects from
world-states.toml and worldgen-pack/aoc/layout.json drawn over the relief), downscaled to at most --max pixels,
and the launcher underlay <out-dir>/relief.jpg + relief.json (≤ 2048 px, ≤ 3 MiB; import with `world:relief`).
"""
from __future__ import annotations

import json
import sys
import tomllib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
args = [a for a in sys.argv[1:] if not a.startswith("--")]
limit = next((int(a.split("=", 1)[1]) for a in sys.argv[1:] if a.startswith("--max=")), 4096)
src, out = Path(args[0]), Path(args[1])
out.mkdir(parents=True, exist_ok=True)

raw = src.read_bytes()
assert raw[:4] == b"AOCM", "not an AOC map dump"
version, x0, z0, size, step = np.frombuffer(raw[4:24], dtype=">i4")
assert version == 1
n = int(size)
colors = np.frombuffer(raw[24:24 + 4 * n * n], dtype=">u4").reshape(n, n).astype(np.int64)
heights = np.frombuffer(raw[24 + 4 * n * n:24 + 6 * n * n], dtype=">i2").reshape(n, n).astype(np.float64)

inside = colors != 0
depth = (colors >> 24) & 0xFF
rgb = np.stack([(colors >> 16) & 0xFF, (colors >> 8) & 0xFF, colors & 0xFF], axis=-1).astype(np.float64)
# Hill shading from the height gradient (per sample = `step` blocks), water darkens with depth.
gz, gx = np.gradient(heights)
shade = np.clip(1.0 + (-gx - gz) * 0.35 / max(1, int(step)) * 2, 0.55, 1.4)
shade[depth > 0] = 1.0
rgb = rgb * shade[..., None]
rgb = rgb * (1.0 - np.clip(depth, 0, 12)[..., None] * 0.035)
rgb[~inside] = (14, 18, 28)
base = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")
scale = min(1.0, limit / n)
if scale < 1.0:
    base = base.resize((int(n * scale), int(n * scale)), Image.LANCZOS)
base.save(out / "map.png", optimize=True)

# Overlay ------------------------------------------------------------------------------------------------------
W = base.width
blocks_per_px = int(step) / scale


def px(x: float, z: float) -> tuple[float, float]:
    return ((x - x0) / blocks_per_px, (z - z0) / blocks_per_px)


def font(size: int):
    for name in ("DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


clan_colors = json.loads((ROOT / "minecraft/server/clan-world.json").read_text(encoding="utf-8"))["territories"]["colors"]
world = tomllib.loads((ROOT / "minecraft/server/world-config/world-states.toml").read_text(encoding="utf-8"))
layout = json.loads((ROOT / "minecraft/server/world-config/worldgen-pack/aoc/layout.json").read_text(encoding="utf-8"))

overlay = base.convert("RGBA")
layer = Image.new("RGBA", overlay.size, (0, 0, 0, 0))
draw = ImageDraw.Draw(layer)
line = max(1, W // 1400)
for region in world.get("regions", []):
    poly = [px(x, z) for x, z in region.get("polygon", [])]
    if len(poly) < 3:
        continue
    color = clan_colors.get(region.get("clan", ""), None)
    rgba = tuple(int(color[i:i + 2], 16) for i in (0, 2, 4)) if color else (255, 255, 255)
    if region.get("kind") == "STATE":
        draw.polygon(poly, fill=rgba + (34,), outline=rgba + (230,), width=line * 2)
    else:
        draw.polygon(poly, outline=(255, 255, 255, 90), width=line)
road_colors = {"main": (250, 236, 200, 255), "ring": (232, 214, 170, 235), "cross": (205, 190, 150, 235), "back": (190, 170, 130, 220)}
for road in layout["roads"]:
    pts = [px(x, z) for x, z in road["points"]]
    draw.line(pts, fill=road_colors.get(road["style"], (220, 200, 160, 230)), width=line * (3 if road["style"] == "main" else 2), joint="curve")
small, label = font(max(10, W // 230)), font(max(13, W // 150))
marks = {"CAPITAL": (13, (255, 255, 255)), "CLAN_CAPITAL": (10, (255, 255, 255)), "FORT": (6, (255, 210, 120)), "OUTPOST": (5, (255, 240, 160)), "WATCHTOWER": (5, (220, 220, 255)),
         "PASS": (5, (200, 255, 200)), "BRIDGE": (5, (160, 220, 255)), "PORT": (5, (160, 220, 255)), "VILLAGE": (5, (255, 200, 200)),
         "EVENT_ZONE": (6, (255, 120, 120)), "BOSS_LAIR": (6, (255, 120, 120)), "RESOURCE_SITE": (3, (255, 255, 120))}
for poi in layout["pois"]:
    r, fill = marks.get(poi["type"], (4, (255, 255, 255)))
    r = max(2, int(r * W / 2400))
    x, y = px(poi["x"], poi["z"])
    if not (0 <= x < W and 0 <= y < W):
        continue
    draw.ellipse([x - r, y - r, x + r, y + r], fill=fill + (255,), outline=(0, 0, 0, 255), width=1)
    if poi["type"] in ("CAPITAL", "CLAN_CAPITAL", "FORT", "PASS", "BRIDGE", "PORT", "VILLAGE", "EVENT_ZONE", "BOSS_LAIR"):
        text = poi["name"].split(" — ")[0]
        f = label if poi["type"] in ("CAPITAL", "CLAN_CAPITAL") else small
        draw.text((x + r + 3, y - r - 2), text, font=f, fill=(255, 255, 255, 255), stroke_width=2, stroke_fill=(0, 0, 0, 220))
overlay = Image.alpha_composite(overlay, layer).convert("RGB")
ImageDraw.Draw(overlay).text((10, 8), f"Мир Одного Неба · радиус {abs(int(x0))} · 1 px = {blocks_per_px:g} блока", font=label,
                             fill=(255, 255, 255), stroke_width=2, stroke_fill=(0, 0, 0))
overlay.save(out / "map-overlay.png", optimize=True)
# Launcher underlay: the relief alone (the launcher draws regions and markers itself).
relief = base if base.width <= 2048 else base.resize((2048, 2048), Image.LANCZOS)
for quality in (86, 80, 72, 64):
    relief.save(out / "relief.jpg", quality=quality, optimize=True, progressive=True)
    if (out / "relief.jpg").stat().st_size <= 3_000_000:
        break
(out / "relief.json").write_text(json.dumps({"format_version": 1, "world_id": world["world_id"], "image": "relief.jpg",
    "x0": int(x0), "z0": int(z0), "width_blocks": n * int(step), "height_blocks": n * int(step)}, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"size": n, "step": int(step), "pixels": W, "blocks_per_px": blocks_per_px, "relief_bytes": (out / "relief.jpg").stat().st_size, "out": str(out)}))
