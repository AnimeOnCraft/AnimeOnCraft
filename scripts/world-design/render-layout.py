#!/usr/bin/env python3
"""Render the top-down layout probe dumps (World Design stage 5) as shaded PNGs and one contact sheet.

    python3 scripts/world-design/render-layout.py <renders-dir> <out-dir>

Each dump holds the map colour and surface height of every column; hill shading comes from the height
gradient, water depth darkens the colour. Writes <out-dir>/<name>.png (3 px per block) and sheet.png.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

src, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
tiles: list[tuple[str, Image.Image]] = []
for file in sorted(src.glob("*.json")):
    data = json.loads(file.read_text(encoding="utf-8"))
    n = data["size"]
    raw = np.array(data["colors"], dtype=np.int64).reshape(n, n)
    heights = np.array(data["heights"], dtype=np.float64).reshape(n, n)
    depth = (raw >> 24) & 0xFF
    rgb = np.stack([(raw >> 16) & 0xFF, (raw >> 8) & 0xFF, raw & 0xFF], axis=-1).astype(np.float64)
    gz, gx = np.gradient(heights)
    shade = np.clip(1.0 + (-gx - gz) * 0.12, 0.55, 1.35)
    rgb = rgb * shade[..., None]
    rgb = rgb * (1.0 - np.clip(depth, 0, 12)[..., None] * 0.035)
    image = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").resize((n * 3, n * 3), Image.NEAREST)
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, image.width, 18], fill=(0, 0, 0))
    draw.text((6, 3), f"{data['name']}  ({data['x0'] + n // 2}, {data['z0'] + n // 2})", fill=(255, 255, 255))
    image.save(out / f"{file.stem}.png", optimize=True)
    tiles.append((file.stem, image))

if tiles:
    cell = 420
    cols = 4
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell, rows * cell), (20, 20, 24))
    for i, (_, image) in enumerate(tiles):
        thumb = image.copy(); thumb.thumbnail((cell - 8, cell - 8))
        sheet.paste(thumb, ((i % cols) * cell + 4, (i // cols) * cell + 4))
    sheet.save(out / "sheet.png", optimize=True)
print(json.dumps({"rendered": len(tiles), "out": str(out)}))
