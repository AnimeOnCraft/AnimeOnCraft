#!/usr/bin/env python3
"""Renders WorldgenProbe output (preview.json + census.json) into a picture and a short report.

    python3 scripts/world-design/render-worldgen-preview.py <results dir> [<vanilla results dir>]

Writes <results>/preview.png, <results>/report.json. With a vanilla baseline the report also has ore
density ratios per region (states / vanilla) for the five ore families.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FAMILY_BLOCKS = {
    "FERROUS": ["iron_ore", "coal_ore"],
    "NONFERROUS": ["copper_ore", "zinc_ore"],
    "PRECIOUS": ["gold_ore", "diamond_ore", "emerald_ore"],
    "ARCANE": ["lapis_ore", "mithril_ore", "budding_amethyst"],
    "TECH": ["redstone_ore"],
}
GROUPS = [  # (substring, colour) — first match wins
    ("frozen_ocean", (150, 180, 220)), ("deep", (20, 50, 110)), ("ocean", (35, 80, 150)), ("river", (60, 120, 200)),
    ("beach", (225, 210, 150)), ("shore", (150, 150, 140)), ("ice_spikes", (220, 240, 255)), ("frozen", (230, 240, 250)),
    ("snow", (240, 245, 250)), ("peaks", (200, 205, 215)), ("slopes", (215, 225, 235)), ("tundra", (170, 180, 160)),
    ("badlands", (205, 110, 50)), ("desert", (230, 205, 130)), ("dune", (225, 200, 130)), ("wasteland", (150, 130, 100)),
    ("volcan", (110, 50, 40)), ("savanna", (175, 170, 90)), ("dryland", (190, 170, 110)), ("scrub", (165, 160, 100)),
    ("mangrove", (60, 90, 50)), ("swamp", (75, 95, 55)), ("bayou", (70, 90, 60)), ("bog", (95, 90, 60)), ("marsh", (90, 120, 70)),
    ("wetland", (80, 110, 75)), ("jungle", (40, 120, 40)), ("rainforest", (40, 110, 50)), ("bamboo", (90, 150, 50)),
    ("cherry", (240, 160, 200)), ("blossom", (240, 190, 220)), ("jacaranda", (170, 130, 220)), ("lavender", (180, 150, 220)),
    ("mushroom", (160, 110, 170)), ("fungal", (150, 100, 160)), ("dark_forest", (35, 70, 35)), ("ominous", (60, 40, 70)),
    ("mystic", (120, 140, 200)), ("taiga", (60, 100, 80)), ("conifer", (55, 95, 75)), ("fir", (70, 110, 85)), ("redwood", (110, 80, 60)),
    ("grove", (180, 210, 200)), ("birch", (120, 170, 90)), ("maple", (190, 100, 50)), ("seasonal", (170, 120, 60)), ("aspen", (200, 180, 80)),
    ("forest", (60, 125, 55)), ("woodland", (75, 130, 60)), ("windswept", (130, 140, 120)), ("hills", (120, 145, 100)), ("highland", (125, 150, 95)),
    ("crag", (110, 110, 110)), ("cliff", (120, 130, 110)), ("meadow", (140, 185, 110)), ("plains", (130, 180, 90)), ("field", (150, 190, 95)),
    ("prairie", (165, 185, 95)), ("pasture", (140, 185, 100)), ("grassland", (135, 175, 85)), ("shrubland", (125, 160, 90)),
]


def colour(biome: str) -> tuple[int, int, int]:
    for key, rgb in GROUPS:
        if key in biome:
            return rgb
    h = hashlib.sha256(biome.encode()).digest()
    return (80 + h[0] % 120, 80 + h[1] % 120, 80 + h[2] % 120)


def family_counts(ores: dict[str, int]) -> dict[str, int]:
    return {f: sum(v for k, v in ores.items() if any(k.endswith(b) or k.endswith("deepslate_" + b) for b in blocks)) for f, blocks in FAMILY_BLOCKS.items()}


def main(states_dir: Path, vanilla_dir: Path | None):
    preview = json.loads((states_dir / "preview.json").read_text())
    size, palette = preview["size"], preview["palette"]
    biomes = np.array(preview["biomes"]).reshape(size, size)
    heights = np.array(preview["heights"], dtype=float).reshape(size, size)
    regions = np.array(preview["regions"]).reshape(size, size)
    lut = np.array([colour(b) for b in palette], dtype=float)
    rgb = lut[biomes]
    shade = np.clip(0.75 + (heights - preview["sea_level"]) / 260, 0.45, 1.35)
    rgb = np.clip(rgb * shade[..., None], 0, 255)
    edge = np.zeros_like(regions, bool)
    edge[1:, :] |= regions[1:, :] != regions[:-1, :]; edge[:, 1:] |= regions[:, 1:] != regions[:, :-1]
    rgb[edge & (heights > preview["sea_level"] - 4)] = (15, 15, 15)
    img = Image.fromarray(rgb.astype(np.uint8)).resize((960, 960), Image.NEAREST)
    draw = ImageDraw.Draw(img)
    try: font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 15)
    except OSError: font = ImageFont.load_default()
    half = preview["half"]
    for label, x, z in [("SCAM", 0, -2704), ("NYA", 2560, -832), ("ZXC", 1584, 2192), ("AKUMA", -1584, 2192), ("YAKUZA", -2560, -832), ("Сора", 0, 0)]:
        px, pz = (x + half) / (2 * half) * 960, (z + half) / (2 * half) * 960
        draw.text((px - 24, pz - 8), label, fill=(255, 255, 255), font=font, stroke_width=2, stroke_fill=(0, 0, 0))
    draw.text((8, 8), f"preview: шаг {preview['step']} блоков, {len(palette)} биомов", fill=(255, 255, 255), font=font, stroke_width=2, stroke_fill=(0, 0, 0))
    img.save(states_dir / "preview.png", optimize=True)

    # Biome composition per region (from the preview grid).
    region_names = {}
    masks = Path(__file__).resolve().parents[2] / "minecraft/server/world-config/worldgen-pack/aoc/masks.json"
    if masks.exists():
        region_names = {p["index"]: p["code"] for p in json.loads(masks.read_text())["regions"]["profiles"]}
    composition = {}
    for index in sorted(set(regions.flatten().tolist())):
        sel = biomes[regions == index]
        counts = np.bincount(sel, minlength=len(palette))
        top = np.argsort(counts)[::-1][:6]
        composition[region_names.get(index, str(index))] = {palette[i]: round(counts[i] / sel.size, 3) for i in top if counts[i]}
    report = {"biomes_total": len(palette), "namespaces": sorted({b.split(":")[0] for b in palette}), "composition": composition}

    census = json.loads((states_dir / "census.json").read_text())
    per_chunk = {r: {f: c / max(1, v["chunks"]) for f, c in family_counts(v["ores"]).items()} for r, v in census["regions"].items()}
    report["census"] = {"chunks": census["chunks"], "seconds": round(census["seconds"]), "ores_per_chunk": {r: {f: round(x, 1) for f, x in v.items()} for r, v in per_chunk.items()},
                        "structures": {r: v["structures"] for r, v in census["regions"].items()}}
    if vanilla_dir:
        base = json.loads((vanilla_dir / "census.json").read_text())["regions"]["vanilla"]
        baseline = {f: c / base["chunks"] for f, c in family_counts(base["ores"]).items()}
        report["vanilla_ores_per_chunk"] = {f: round(x, 1) for f, x in baseline.items()}
        report["ratio_vs_vanilla"] = {r: {f: round(v[f] / baseline[f], 2) if baseline[f] else None for f in v} for r, v in per_chunk.items()}
    (states_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(json.dumps({k: report[k] for k in report if k != "composition"}, ensure_ascii=False)[:4000])


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]) if len(sys.argv) > 2 else None)
