#!/usr/bin/env python3
"""Builds the AnimeOnCraft "states" worldgen datapack (World Design stage 3).

Output: minecraft/server/world-config/worldgen-pack/  → install as <world>/datapacks/animeoncraft-states
  aoc/masks.json + aoc/*.png      macro rasters, 1 px = 16 blocks, read by the NeoForge mod (WorldMasks)
  data/minecraft/...              overworld continents/erosion/noise_settings steered by the masks
  data/animeoncraft/neoforge/...  ore weighting by region (RegionWeightedFeatures)
Also writes docs/world-design/masks-preview.png. Deterministic: same inputs → same bytes.

    python3 scripts/world-design/world-blueprint.py   # geometry, blueprint.json
    python3 scripts/world-design/build-worldgen-pack.py
"""
from __future__ import annotations

import copy
import json
import math
import shutil
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OUT = ROOT / "minecraft/server/world-config/worldgen-pack"
BLUEPRINT = json.loads((ROOT / "docs/world-design/blueprint.json").read_text(encoding="utf-8"))
W = BLUEPRINT["world"]
HALF, PIXEL = W["border_half"], 16
N = HALF * 2 // PIXEL
SEED = 20261020
FAMILIES = ["FERROUS", "NONFERROUS", "PRECIOUS", "ARCANE", "TECH", "TIMBER", "AGRARIAN", "ALCHEMY", "MARINE"]
ORE_FAMILIES = ["FERROUS", "NONFERROUS", "PRECIOUS", "ARCANE", "TECH"]   # the rest is steered by climate

# Multiplier per matrix score; -2 depends on the family (survival floor for iron, none for magic).
MULTIPLIER = {2: 1.8, 1: 1.3, 0: 1.0, -1: 0.55}
MISSING = {"FERROUS": 0.2, "NONFERROUS": 0.1, "PRECIOUS": 0.2, "ARCANE": 0.0, "TECH": 0.2}
mult = lambda family, score: MISSING[family] if score == -2 else MULTIPLIER[score]

# Vanilla placed features per family (ids verified against the 1.21.1 server jar). Modded ores are
# appended from the worldgen inventory (scripts/world-design/mod-ores.json) once the probe has listed them.
VANILLA_ORES = {
    "FERROUS": ["minecraft:ore_iron_middle", "minecraft:ore_iron_small", "minecraft:ore_iron_upper", "minecraft:ore_coal_lower", "minecraft:ore_coal_upper"],
    "NONFERROUS": ["minecraft:ore_copper", "minecraft:ore_copper_large"],
    "PRECIOUS": ["minecraft:ore_gold", "minecraft:ore_gold_extra", "minecraft:ore_gold_lower", "minecraft:ore_diamond", "minecraft:ore_diamond_buried",
                 "minecraft:ore_diamond_large", "minecraft:ore_diamond_medium", "minecraft:ore_emerald"],
    "ARCANE": ["minecraft:ore_lapis", "minecraft:ore_lapis_buried", "minecraft:amethyst_geode"],
    "TECH": ["minecraft:ore_redstone", "minecraft:ore_redstone_lower"],
}
MOD_ORES_FILE = HERE / "mod-ores.json"

# Vanilla climate bands: temperature -0.45/-0.15/0.2/0.55, humidity -0.35/-0.1/0.1/0.3; erosion ≥0.55 near
# the coast gives swamps; temperature ≥0.55 gives desert at any humidity. Targets were tuned on probe previews.
# Climate/terrain targets per zone: continentalness, erosion, temperature, humidity and how much vanilla
# noise stays on top (detail). Vanilla parameter space is [-1, 1]; continentalness < -0.11 is ocean.
Z = dict
ZONES = {
    "OCEAN":  Z(c=-0.75, e=0.0, t=0.0, h=0.0, dc=0.15, de=0.5, dt=0.8, dh=0.8),
    "GULF":   Z(c=-0.50, e=0.0, t=0.4, h=0.2, dc=0.10, de=0.5, dt=0.6, dh=0.6),
    "CORE":   Z(c=0.20, e=0.45, t=0.15, h=0.0, dc=0.20, de=0.30, dt=0.30, dh=0.30),
    "RING":   Z(c=0.25, e=0.30, t=0.15, h=0.0, dc=0.25, de=0.45, dt=0.50, dh=0.50),
    "SCAM":   Z(c=0.55, e=-0.55, t=-0.75, h=0.0),
    "NYA":    Z(c=0.45, e=-0.20, t=0.05, h=-0.05),   # forests, meadows, cherry plateaus
    "ZXC":    Z(c=0.25, e=0.35, t=0.28, h=-0.25),    # savanna and plains, flat
    "AKUMA":  Z(c=0.12, e=0.50, t=0.00, h=0.90),     # dark forest + swamps (erosion ≥0.55)
    "YAKUZA": Z(c=0.06, e=0.25, t=0.45, h=0.10),
    "W1":     Z(c=0.60, e=-0.75, t=-0.30, h=0.00),   # Железный перевал: high ridge, pass
    "W2":     Z(c=0.20, e=0.35, t=0.15, h=0.20),     # Долина мельниц: wide farmland valley
    "W3":     Z(c=0.35, e=0.10, t=0.90, h=-0.85),    # Пепельная гряда: hot and dry
    "W4":     Z(c=-0.04, e=0.75, t=0.45, h=0.75),    # Мангровая дельта: near sea level, wet
    "W5":     Z(c=0.30, e=-0.35, t=-0.50, h=0.10),   # Северный тракт: cold coast
    "RIM":    Z(c=0.30, e=0.0, t=0.0, h=0.0, dc=0.30, de=0.9, dt=1.0, dh=1.0),
    "FRONTIER": Z(c=0.30, e=0.0, t=0.0, h=0.0, dc=0.35, de=1.0, dt=1.0, dh=1.0),
}
STATE_DETAIL = dict(dc=0.25, de=0.35, dt=0.35, dh=0.35)
SLOTS = {  # docs/FRONTIER_EXPANSION.md: theme, exporter, missing
    "A": (Z(c=0.50, e=-0.45, t=-0.10, h=0.50), "ALCHEMY", "MARINE"),
    "B": (Z(c=0.50, e=-0.35, t=0.90, h=-0.90), "PRECIOUS", "TIMBER"),   # badlands plateaus
    "C": (Z(c=0.35, e=-0.15, t=0.30, h=-0.20), "NONFERROUS", "AGRARIAN"),
    "D": (Z(c=0.25, e=0.30, t=0.40, h=0.85), "TIMBER", "FERROUS"),     # jungle/bamboo (warm, not hot)
    "E": (Z(c=0.55, e=-0.30, t=-0.55, h=-0.20), "ARCANE", "AGRARIAN"),
    "F": (Z(c=0.35, e=0.30, t=-0.95, h=-0.30), "FERROUS", "ALCHEMY"),
    "G": (Z(c=0.35, e=-0.15, t=0.00, h=-0.15), "AGRARIAN", "NONFERROUS"),  # meadows, lavender
    "H": (Z(c=0.30, e=0.20, t=0.95, h=-0.95), "TECH", "MARINE"),       # desert (erosion below swamp band)
    "I": (Z(c=0.50, e=-0.40, t=0.85, h=-0.40), "PRECIOUS", "TIMBER"),
    "J": (Z(c=-0.30, e=0.30, t=0.40, h=0.60), "MARINE", "FERROUS"),   # archipelago; islands added below
}
SLOT_DETAIL = dict(dc=0.30, de=0.40, dt=0.40, dh=0.40)
RANGES = {  # mask → (min, max) of the density function value
    "continents": (-1.2, 1.0), "continents_detail": (0.0, 1.0), "erosion": (-1.0, 1.0), "erosion_detail": (0.0, 1.0),
    "temperature": (-1.0, 1.0), "temperature_detail": (0.0, 1.0), "vegetation": (-1.0, 1.0), "vegetation_detail": (0.0, 1.0),
}


def coords():
    c = (np.arange(N) * PIXEL - HALF + PIXEL / 2).astype(np.float64)
    return np.meshgrid(c, c)


def value_noise(seed: int, cells: int) -> np.ndarray:
    """Smooth noise in [-1, 1] with `cells` lattice cells across the map (bicubic upsampled)."""
    rng = np.random.default_rng(seed)
    grid = rng.random((cells + 1, cells + 1)).astype(np.float32)
    img = Image.fromarray(grid, mode="F").resize((N, N), Image.BICUBIC)
    return np.asarray(img, dtype=np.float64) * 2 - 1


def fractal(seed: int) -> np.ndarray:
    return 0.55 * value_noise(seed, 12) + 0.3 * value_noise(seed + 1, 30) + 0.15 * value_noise(seed + 2, 80)


def blur(a: np.ndarray, sigma_px: float) -> np.ndarray:
    radius = int(sigma_px * 3)
    k = np.exp(-0.5 * (np.arange(-radius, radius + 1) / sigma_px) ** 2); k /= k.sum()
    pad = np.pad(a, radius, mode="edge")
    rows = np.apply_along_axis(lambda r: np.convolve(r, k, mode="valid"), 1, pad)
    return np.apply_along_axis(lambda c: np.convolve(c, k, mode="valid"), 0, rows)


def ang_diff(a, b):
    return np.abs((a - b + 180) % 360 - 180)


def zones():
    X, Zc = coords()
    R = np.hypot(X, Zc)
    B = (np.degrees(np.arctan2(X, -Zc)) + 360) % 360
    coast = W["land_radius"] + 450 * fractal(SEED)             # ragged continent edge
    zone = np.full((N, N), "OCEAN", dtype=object)
    zone[R <= coast] = "FRONTIER"
    zone[R <= W["rim_radius"]] = "RIM"
    clans = BLUEPRINT["clans"]
    for i, wedge in enumerate(BLUEPRINT["borderlands"], start=1):
        zone[(R > W["ring_radius"]) & (R <= W["state_radius"]) & (ang_diff(B, wedge["bearing"]) <= W["wedge_half_deg"])] = f"W{i}"
    for code, clan in clans.items():
        zone[(R > W["ring_radius"]) & (R <= W["state_radius"]) & (ang_diff(B, clan["bearing"]) <= W["state_half_deg"])] = code
    zone[R <= W["ring_radius"]] = "RING"
    zone[R <= W["core_radius"]] = "CORE"
    g = W["gulf"]
    gulf = (R >= g["from_r"]) & (ang_diff(B, g["bearing"]) <= g["half"] + 1.2 * fractal(SEED + 7)) & (zone != "CORE")
    zone[gulf & (R > W["state_radius"] - 500)] = "GULF"
    slot = np.full((N, N), "", dtype=object)
    for s in BLUEPRINT["frontier_slots"]:
        letter = s["id"][-1]
        inside = np.hypot(X - s["x"], Zc - s["z"]) <= s["radius"] * (1 + 0.08 * fractal(SEED + ord(letter)))
        slot[inside & np.isin(zone, ["FRONTIER", "GULF"])] = letter
    return X, Zc, R, zone, slot


def build():
    X, Zc, R, zone, slot = zones()
    fields = {k: np.zeros((N, N)) for k in ["c", "e", "t", "h", "dc", "de", "dt", "dh"]}
    for name, params in ZONES.items():
        merged = {**STATE_DETAIL, **params}
        mask = zone == name
        for k in fields: fields[k][mask] = merged[k]
    for letter, (params, _exp, _miss) in SLOTS.items():
        mask = slot == letter
        merged = {**SLOT_DETAIL, **params}
        for k in fields: fields[k][mask] = merged[k]
    # Archipelago J: islands poke out of the gulf.
    islands = (slot == "J") & (fractal(SEED + 99) > 0.15)
    fields["c"][islands] = 0.05
    # Smooth transitions: ~240 blocks for climate, ~120 for terrain.
    for k in ["t", "h", "dt", "dh"]: fields[k] = blur(fields[k], 15)
    for k in ["c", "e", "dc", "de"]: fields[k] = blur(fields[k], 7.5)
    # A little low-frequency variation inside regions so they are not uniform.
    fields["e"] += 0.12 * fractal(SEED + 21) * (zone != "OCEAN")
    fields["t"] += 0.06 * fractal(SEED + 22)
    fields["h"] += 0.08 * fractal(SEED + 23)
    masks = {
        "continents": fields["c"], "continents_detail": fields["dc"], "erosion": np.clip(fields["e"], -1, 1), "erosion_detail": fields["de"],
        "temperature": np.clip(fields["t"], -1, 1), "temperature_detail": fields["dt"], "vegetation": np.clip(fields["h"], -1, 1), "vegetation_detail": fields["dh"],
    }
    # Region index raster (no blur) and ore profiles.
    order = ["OCEAN", "GULF", "CORE", "RING", *BLUEPRINT["clans"].keys(), "W1", "W2", "W3", "W4", "W5", "RIM", "FRONTIER", *[f"SLOT_{l}" for l in SLOTS]]
    index = {name: i for i, name in enumerate(order)}
    regions = np.zeros((N, N), dtype=np.uint8)
    for name in order:
        if name.startswith("SLOT_"): regions[slot == name[5:]] = index[name]
        else: regions[(zone == name) & (slot == "")] = index[name]
    profiles = []
    codes = {"OCEAN": "sea.ocean", "GULF": "sea.gulf", "CORE": "core", "RING": "border.ring", "RIM": "rim", "FRONTIER": "frontier.wild"}
    for name in order:
        multipliers = {f: 1.0 for f in ORE_FAMILIES}
        if name in BLUEPRINT["clans"]:
            for f, score in BLUEPRINT["clans"][name]["resources"].items():
                if f in ORE_FAMILIES: multipliers[f] = mult(f, score)
            code = f"state.{name.lower()}"
        elif name.startswith("SLOT_"):
            _, exporter, missing = SLOTS[name[5:]]
            if exporter in ORE_FAMILIES: multipliers[exporter] = 1.8
            if missing in ORE_FAMILIES: multipliers[missing] = MISSING[missing]
            code = f"frontier.slot.{name[5:].lower()}"
        elif name.startswith("W"):
            code = f"border.{name.lower()}"
        else:
            code = codes[name]
        profiles.append({"index": index[name], "code": code, "multipliers": multipliers})
    return masks, regions, profiles, zone, slot


def encode(values: np.ndarray, lo: float, hi: float) -> Image.Image:
    data = np.clip(np.round((values - lo) / (hi - lo) * 255), 0, 255).astype(np.uint8)
    return Image.fromarray(data, mode="L")


def write_pack(masks, regions, profiles):
    if OUT.exists(): shutil.rmtree(OUT)
    aoc = OUT / "aoc"; aoc.mkdir(parents=True)
    (OUT / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 48, "description": "AnimeOnCraft states world generation (World Design stage 3)"}}, indent=2) + "\n")
    names = {}
    for name, values in masks.items():
        lo, hi = RANGES[name]
        encode(values, lo, hi).save(aoc / f"{name}.png", optimize=True)
        names[name] = f"{name}.png"
    Image.fromarray(regions, mode="L").save(aoc / "regions.png", optimize=True)
    (aoc / "masks.json").write_text(json.dumps({"format_version": 1, "origin_x": -HALF, "origin_z": -HALF, "pixel": PIXEL,
        "masks": names, "regions": {"file": "regions.png", "profiles": profiles}}, indent=1) + "\n")

    mask = lambda name: {"type": "animeoncraft:mask", "mask": name, "min": RANGES[name][0], "max": RANGES[name][1]}
    steer = lambda name, original: {"type": "minecraft:add", "argument1": mask(name), "argument2": {"type": "minecraft:mul", "argument1": mask(name + "_detail"), "argument2": original}}
    df = OUT / "data/minecraft/worldgen/density_function/overworld"; df.mkdir(parents=True)
    for name in ["continents", "erosion"]:
        vanilla = json.loads((HERE / "vanilla" / f"{name}.json").read_text())
        assert vanilla["type"] == "minecraft:flat_cache"
        (df / f"{name}.json").write_text(json.dumps({"type": "minecraft:flat_cache", "argument": steer(name, vanilla["argument"])}, indent=2) + "\n")
    settings = json.loads((HERE / "vanilla/noise_settings_overworld.json").read_text())
    router = settings["noise_router"]
    router["temperature"] = steer("temperature", router["temperature"])
    router["vegetation"] = steer("vegetation", router["vegetation"])
    # Large ore veins come from noise, not features, and would bypass regional asymmetry.
    settings["ore_veins_enabled"] = False
    ns = OUT / "data/minecraft/worldgen/noise_settings"; ns.mkdir(parents=True)
    (ns / "overworld.json").write_text(json.dumps(settings, separators=(",", ":")) + "\n")

    families = copy.deepcopy(VANILLA_ORES)
    if MOD_ORES_FILE.exists():
        for family, ids in json.loads(MOD_ORES_FILE.read_text())["families"].items(): families.setdefault(family, []).extend(ids)
    bm = OUT / "data/animeoncraft/neoforge/biome_modifier"; bm.mkdir(parents=True)
    (bm / "region_ores.json").write_text(json.dumps({"type": "animeoncraft:region_weighted_features", "families": families}, indent=2) + "\n")
    (OUT / "README.md").write_text("AnimeOnCraft states worldgen datapack. Generated by scripts/world-design/build-worldgen-pack.py — do not edit by hand.\n"
                                   "Install as <world>/datapacks/animeoncraft-states before the world is created. See docs/WORLDGEN_PIPELINE.md.\n")


def preview(masks, zone, slot):
    t, h = masks["temperature"], masks["vegetation"]
    land = masks["continents"] > -0.11
    rgb = np.zeros((N, N, 3))
    rgb[..., 0] = 0.45 + 0.45 * t; rgb[..., 1] = 0.45 + 0.35 * h - 0.1 * np.abs(t); rgb[..., 2] = 0.45 - 0.35 * t
    shade = np.clip(0.75 - 0.35 * masks["erosion"], 0.3, 1.0)[..., None]
    rgb = rgb * shade
    rgb[~land] = [0.12, 0.25, 0.42]
    edges = np.zeros((N, N), bool)
    z2 = np.where(slot != "", "S" + slot.astype(str), zone).astype(str)
    edges[1:, :] |= z2[1:, :] != z2[:-1, :]; edges[:, 1:] |= z2[:, 1:] != z2[:, :-1]
    rgb[edges & land] = [0.05, 0.05, 0.05]
    img = Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8)).resize((1000, 1000), Image.LANCZOS)
    img.save(ROOT / "docs/world-design/masks-preview.png", optimize=True)


if __name__ == "__main__":
    masks, regions, profiles, zone, slot = build()
    write_pack(masks, regions, profiles)
    # The pack directory was recreated: regenerate the world profile and aoc/layout.json (POIs, roads, exclusions).
    import runpy
    runpy.run_path(str(Path(__file__).with_name("build-world-profile.py")), run_name="__main__")
    preview(masks, zone, slot)
    land = (masks["continents"] > -0.11).mean()
    print(json.dumps({"size": N, "land_fraction": round(float(land), 3), "regions": len(profiles),
                      "files": sorted(str(p.relative_to(OUT)) for p in OUT.rglob("*") if p.is_file())}, ensure_ascii=False))
