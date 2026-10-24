#!/usr/bin/env python3
"""Build the world catalog profile of the states world from the blueprint.

    python3 scripts/world-design/build-world-profile.py

Reads docs/world-design/blueprint.json (world-blueprint.py) and writes
minecraft/server/world-config/world-states.toml: region polygons (core, ring,
five states, five borderland wedges, rim, reserved frontier slots), all
strategic points and resource sites with radii, and a reference to
capture-rules.toml. Deterministic: the same blueprint gives the same file.
The backend imports it with `world:import --profile …/world-states.toml`;
the current world keeps using world.toml until the transition (stage 7).
"""
from __future__ import annotations

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BLUEPRINT = json.loads((ROOT / "docs/world-design/blueprint.json").read_text(encoding="utf-8"))
OUT = ROOT / "minecraft/server/world-config/world-states.toml"
W = BLUEPRINT["world"]
CORE_R, RING_R, STATE_R, RIM_R = W["core_radius"], W["ring_radius"], W["state_radius"], W["rim_radius"]
STATE_HALF, WEDGE_HALF = W["state_half_deg"], W["wedge_half_deg"]
GULF = W["gulf"]
STEP = 2.0  # degrees between arc vertices


def xz(r: float, b: float) -> tuple[int, int]:
    rad = math.radians(b)
    return int(round(r * math.sin(rad))), int(round(-r * math.cos(rad)))


def arc(r: float, b0: float, b1: float) -> list[tuple[int, int]]:
    n = max(1, math.ceil(abs(b1 - b0) / STEP))
    return [xz(r, b0 + (b1 - b0) * i / n) for i in range(n + 1)]


def sector(r0: float, r1: float, b0: float, b1: float, notch: dict | None = None) -> list[tuple[int, int]]:
    """Annular sector; `notch` cuts the outer edge back to notch['from_r'] inside its bearing range."""
    outer: list[tuple[int, int]] = []
    if notch and r1 > notch["from_r"] > r0:
        n0, n1 = notch["bearing"] - notch["half"], notch["bearing"] + notch["half"]
        outer = arc(r1, b0, n0) + arc(notch["from_r"], n0, n1) + arc(r1, n1, b1)
    else:
        outer = arc(r1, b0, b1)
    inner = arc(r0, b1, b0) if r0 > 0 else []
    poly = outer + inner
    dedup = [p for i, p in enumerate(poly) if p != poly[i - 1]]
    return dedup


def circle(cx: int, cz: int, r: float, n: int = 48) -> list[tuple[int, int]]:
    return [(cx + int(round(r * math.sin(2 * math.pi * i / n))), cz + int(round(-r * math.cos(2 * math.pi * i / n)))) for i in range(n)]


def inside(poly, x, z) -> bool:
    px, pz, hit = x + 0.5, z + 0.5, False
    for i in range(len(poly)):
        (xi, zi), (xj, zj) = poly[i], poly[i - 1]
        if (zi > pz) != (zj > pz) and px < (xj - xi) * (pz - zi) / (zj - zi) + xi:
            hit = not hit
    return hit


regions: list[dict] = []
# The core uses exactly the inner-arc vertices of the ring pieces (odd degrees), so shared edges coincide.
regions.append(dict(code="core", name="Сора и Сердце мира", kind="CORE", pvp="OFF", build="NONE",
                    polygon=[xz(CORE_R, b) for b in range(-25, 335, 2)]))
clans = BLUEPRINT["clans"]
for clan, row in clans.items():
    b = row["bearing"]
    notch = GULF if abs((GULF["bearing"] - b + 180) % 360 - 180) < STATE_HALF else None
    cap = row["capital"]
    regions.append(dict(code=f"state.{clan.lower()}", name=f"Земли {clan}", kind="STATE", clan=clan, pvp="WAR_ONLY", build="MEMBERS",
                        capital=cap, spawn=cap, polygon=sector(RING_R, STATE_R, b - STATE_HALF, b + STATE_HALF, notch)))
    regions.append(dict(code=f"ring.{clan.lower()}", name="Кольцо застав", kind="BORDERLAND", pvp="ON", build="OPEN",
                        polygon=sector(CORE_R, RING_R, b - STATE_HALF, b + STATE_HALF)))
    # The rim behind a state: the gulf cuts straight through it, leaving two pieces.
    rb0, rb1 = b - STATE_HALF, b + STATE_HALF
    if notch:
        n0, n1 = GULF["bearing"] - GULF["half"], GULF["bearing"] + GULF["half"]
        pieces = [(rb0, n0, "a"), (n1, rb1, "b")]
    else:
        pieces = [(rb0, rb1, "")]
    for p0, p1, suffix in pieces:
        regions.append(dict(code=f"rim.{clan.lower()}{suffix}", name="Внешний вал", kind="RIM", pvp="ON", build="OPEN",
                            polygon=sector(STATE_R, RIM_R, p0, p1)))
for i, wedge in enumerate(BLUEPRINT["borderlands"], start=1):
    b = wedge["bearing"]
    regions.append(dict(code=f"border.w{i}", name=wedge["name"], kind="BORDERLAND", pvp="ON", build="OPEN",
                        polygon=sector(RING_R, STATE_R, b - WEDGE_HALF, b + WEDGE_HALF)))
    regions.append(dict(code=f"ring.w{i}", name="Кольцо застав", kind="BORDERLAND", pvp="ON", build="OPEN",
                        polygon=sector(CORE_R, RING_R, b - WEDGE_HALF, b + WEDGE_HALF)))
    regions.append(dict(code=f"rim.w{i}", name="Внешний вал", kind="RIM", pvp="ON", build="OPEN",
                        polygon=sector(STATE_R, RIM_R, b - WEDGE_HALF, b + WEDGE_HALF)))
for slot in BLUEPRINT["frontier_slots"]:
    letter = slot["id"].rsplit(".", 1)[1].lower()
    regions.append(dict(code=f"frontier.slot.{letter}", name=f"Свободные земли {letter.upper()}", kind="FRONTIER_SLOT", status="RESERVED",
                        pvp="ON", build="OPEN", polygon=circle(slot["x"], slot["z"], slot["radius"], 48)))

# Equal 24×24 plots between Sora's five radial streets, clear of the plaza, gate hall and walls.
# Only rights/overlays change in an existing world: never rebuild Sora or erase player blocks.
for clan, row in clans.items():
    sx, sz = xz(80, row["bearing"] + 180 / len(clans))
    regions.append(dict(code=f"market.{clan.lower()}", name=f"Торговый участок {clan}", kind="MARKET", clan=clan,
                        pvp="OFF", build="MEMBERS", polygon=[(sx-12,sz-12),(sx+12,sz-12),(sx+12,sz+12),(sx-12,sz+12)]))


def region_of(x: int, z: int) -> str | None:
    for region in regions:
        if inside(region["polygon"], x, z):
            return region["code"]
    return None


POINT_RADIUS = {"OUTPOST": 16, "WATCHTOWER": 16, "FORT": 32, "PASS": 24, "BRIDGE": 24, "VILLAGE": 32, "PORT": 24,
                "CAPITAL": 64, "CLAN_CAPITAL": 48, "EVENT_ZONE": 48, "BOSS_LAIR": 48}
SITE_RADIUS = {1: 16, 2: 20, 3: 24, 4: 32}
points: list[dict] = []
for p in BLUEPRINT["points"]:
    points.append(dict(code=p["id"].lower(), type=p["type"], name=p["name"], x=p["x"], z=p["z"], radius=POINT_RADIUS[p["type"]],
                       capturable=bool(p["capturable"]), region=region_of(p["x"], p["z"])))
for s in BLUEPRINT["resource_sites"]:
    points.append(dict(code=s["id"].lower(), type="RESOURCE_SITE", name=s["name"], x=s["x"], z=s["z"], radius=SITE_RADIUS[s["tier"]],
                       capturable=True, region=region_of(s["x"], s["z"]), resource=dict(family=s["family"], tier=s["tier"], kind=s["kind"])))

# ---------------------------------------------------------------------------------------------
# Roads (World Design stage 5): Sora → outpost → capital, the ring road between outposts, wedge
# crossings capital → pass/bridge/port → neighbour capital, and the back roads to the watchtowers.
# They are both map connections (catalog) and build instructions for the server mod (layout.json).
# ---------------------------------------------------------------------------------------------
by_code = {p["code"]: p for p in points}
ORDER = list(clans)
def densify(waypoints: list[tuple[int, int]], step: int = 96) -> list[list[int]]:
    out: list[list[int]] = []
    for (x0, z0), (x1, z1) in zip(waypoints, waypoints[1:]):
        n = max(1, math.ceil(math.hypot(x1 - x0, z1 - z0) / step))
        for i in range(n):
            out.append([round(x0 + (x1 - x0) * i / n), round(z0 + (z1 - z0) * i / n)])
    out.append(list(waypoints[-1]))
    return out

roads: list[dict] = []
SORA_GATE_R = 112  # the city wall of Sora; roads start at its gates
for clan, row in clans.items():
    b, low = row["bearing"], clan.lower()
    outpost, capital = by_code[f"ring.outpost.{low}"], by_code[f"region.{low}.capital"]
    roads.append(dict(code=f"road.main.{low}", kind="ROAD", style="main", width=5, start="core", end=f"state.{low}",
                      points=densify([xz(SORA_GATE_R, b), (outpost["x"], outpost["z"]), (capital["x"], capital["z"])])))
    tower = by_code[f"rim.watchtower.{low}"]
    roads.append(dict(code=f"road.back.{low}", kind="ROAD", style="back", width=3, start=f"state.{low}", end=region_of(tower["x"], tower["z"]),
                      points=densify([(capital["x"] + 0, capital["z"] + 0), (tower["x"], tower["z"])])))
for i, clan in enumerate(ORDER):
    nxt = ORDER[(i + 1) % len(ORDER)]
    b0, b1 = clans[clan]["bearing"], clans[nxt]["bearing"] if clans[nxt]["bearing"] > clans[clan]["bearing"] else clans[nxt]["bearing"] + 360
    arc_points = [list(xz(1250, b0 + (b1 - b0) * k / 36)) for k in range(37)]
    roads.append(dict(code=f"road.ring.{clan.lower()}-{nxt.lower()}", kind="ROAD", style="main", width=4,
                      start=f"ring.{clan.lower()}", end=f"ring.{nxt.lower()}", points=arc_points))
for i, wedge in enumerate(BLUEPRINT["borderlands"], start=1):
    a, c = wedge["between"]
    crossing = next(p for p in points if p["code"].startswith(f"border.w{i}.") and p["type"] in ("PASS", "BRIDGE", "PORT"))
    ca, cc = by_code[f"region.{a.lower()}.capital"], by_code[f"region.{c.lower()}.capital"]
    roads.append(dict(code=f"road.cross.w{i}", kind=crossing["type"], style="cross", width=4, start=f"state.{a.lower()}", end=f"state.{c.lower()}",
                      via=crossing["code"], points=densify([(ca["x"], ca["z"]), (crossing["x"], crossing["z"]), (cc["x"], cc["z"])])))

problems = []
for road in roads:
    if road["end"] is None or road["start"] == road["end"]:
        problems.append(f"{road['code']}: invalid regions")
# Anchors recovered from the original completed generation, never from roofs. Kept separately from
# the blueprint/masks so this correction does not change the world's terrain digest.
capture_sites = json.loads((ROOT / "minecraft/server/world-config/capture-sites-states.json").read_text(encoding="utf-8"))
assert capture_sites["world_id"] == "states-20261020" and capture_sites["seed"] == "20261020"
for point in points:
    override = capture_sites["points"].get(point["code"])
    if override:
        assert (point["x"], point["z"]) == (override["x"], override["z"]), point["code"]
        point["y"] = override["y"]
        if "resource" in point:
            point["resource"]["yield"] = override["output"]
            point["resource"]["kind"] = override.get("kind", point["resource"]["kind"])
for point in points:
    region = next((r for r in regions if r["code"] == point["region"]), None)
    if point["capturable"] and region and region["kind"] == "STATE":
        problems.append(f"{point['code']} is capturable inside {region['code']}")
if problems:
    raise SystemExit("\n".join(problems))


def toml_value(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (list, tuple)):
        return "[" + ", ".join(toml_value(v) for v in value) + "]"
    if isinstance(value, dict):
        return "{ " + ", ".join(f"{k} = {toml_value(v)}" for k, v in value.items()) + " }"
    raise TypeError(value)


lines = [
    "# AnimeOnCraft world catalog — states world (World Design). GENERATED by",
    "# scripts/world-design/build-world-profile.py from docs/world-design/blueprint.json; do not edit by hand.",
    "# Used on test servers now and imported for the new world at the transition (stage 7).",
    "format_version = 1",
    'world_id = "states-20261020"',
    'display_name = "Мир Одного Неба"',
    f"border_half = {W['border_half']}",
    'capture_rules = "capture-rules.toml"',
    "",
]
for region in regions:
    lines.append("[[regions]]")
    for key in ("code", "name", "kind", "clan", "status", "pvp", "build", "capital", "spawn"):
        if key in region:
            value = region[key]
            if key in ("capital", "spawn"):
                value = {"x": value["x"], "z": value["z"]}
            lines.append(f"{key} = {toml_value(value)}")
    lines.append("polygon = " + toml_value([list(p) for p in region["polygon"]]))
    lines.append("")
for point in points:
    lines.append("[[points]]")
    for key in ("code", "type", "name", "region", "x", "y", "z", "radius", "capturable", "resource"):
        if point.get(key) is not None:
            lines.append(f"{key} = {toml_value(point[key])}")
    lines.append("")
for road in roads:
    lines.append("[[connections]]")
    for key, value in (("code", road["code"]), ("from", road["start"]), ("to", road["end"]), ("kind", road["kind"])):
        lines.append(f"{key} = {toml_value(value)}")
    lines.append("polyline = " + toml_value(road["points"]))
    lines.append("")
OUT.write_text("\n".join(lines), encoding="utf-8")

# Build instructions for the server mod (WorldLayout): POIs with palettes, roads, and the zones where random
# structures (villages, dungeons) must not start. Lives in the world datapack next to the masks; it is not part
# of the mask digest, so building can be re-planned without touching terrain generation.
CLAN_WORLD = json.loads((ROOT / "minecraft/server/clan-world.json").read_text(encoding="utf-8"))
NEUTRAL = {"wall": "minecraft:stone_bricks", "roof": "minecraft:dark_oak_planks", "accent": "minecraft:white_wool", "floor": "minecraft:polished_andesite"}
THEMES = {
    "core": {"wall": "minecraft:quartz_bricks", "roof": "minecraft:light_blue_terracotta", "accent": "minecraft:white_wool", "floor": "minecraft:smooth_stone"},
    "border.w1": {"wall": "minecraft:deepslate_bricks", "roof": "minecraft:spruce_planks", "accent": "minecraft:white_wool", "floor": "minecraft:cobbled_deepslate"},
    "border.w2": {"wall": "minecraft:stone_bricks", "roof": "minecraft:oak_planks", "accent": "minecraft:white_wool", "floor": "minecraft:polished_andesite"},
    "border.w3": {"wall": "minecraft:polished_blackstone_bricks", "roof": "minecraft:nether_bricks", "accent": "minecraft:white_wool", "floor": "minecraft:polished_basalt"},
    "border.w4": {"wall": "minecraft:mud_bricks", "roof": "minecraft:mangrove_planks", "accent": "minecraft:white_wool", "floor": "minecraft:packed_mud"},
    "border.w5": {"wall": "minecraft:stone_bricks", "roof": "minecraft:dark_oak_planks", "accent": "minecraft:white_wool", "floor": "minecraft:cobblestone"},
    "rim": {"wall": "minecraft:andesite", "roof": "minecraft:spruce_planks", "accent": "minecraft:white_wool", "floor": "minecraft:cobblestone"},
}
def theme(point: dict) -> str:
    region = point["region"] or ""
    if region.startswith("state."): return region.split(".")[1].upper()
    if region.startswith("border.w"): return region
    if region.startswith("rim"): return "rim"
    if region == "core" or region.startswith("ring."): return "core"
    return "neutral"
palettes = {"neutral": NEUTRAL, **THEMES, **CLAN_WORLD["castle_palettes"]}
EXCLUSION_MARGIN = 80
CITY_R = {"CAPITAL": 300, "CLAN_CAPITAL": 260}
layout = {
    "format_version": 1,
    "world_id": "states-20261020",
    "palettes": palettes,
    "pois": [dict(code=p["code"], type=p["type"], name=p["name"], x=p["x"], z=p["z"], radius=p["radius"], palette=theme(p),
                  **({"family": p["resource"]["family"], "tier": p["resource"]["tier"], "kind": p["resource"]["kind"]} if "resource" in p else {}),
                  **({"road": next((r["code"] for r in roads if r.get("via") == p["code"]), None)} if p["type"] in ("PASS", "BRIDGE", "PORT") else {}))
             for p in points],
    "roads": [dict(code=r["code"], kind=r["kind"], style=r["style"], width=r["width"], points=r["points"]) for r in roads],
    "exclusions": [dict(code=p["code"], x=p["x"], z=p["z"], radius=CITY_R.get(p["type"], p["radius"]) + EXCLUSION_MARGIN) for p in points],
}
PACK_AOC = ROOT / "minecraft/server/world-config/worldgen-pack/aoc"
if PACK_AOC.is_dir():
    (PACK_AOC / "layout.json").write_text(json.dumps(layout, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
# Server config of the states world (config/animeoncraft-clan-spawns.json on the new server, stage 7): the same schema
# as clan-world.json, but no rectangle territories — protection comes from the backend region catalog. Clan spawns are
# the capitals (castles there are built by the layout, not by ClanCastles), the common spawn is on Sora's plaza.
capitals = {p["region"].split(".")[1].upper(): p for p in points if p["type"] == "CLAN_CAPITAL"}
spawns = {
    "format_version": 1,
    "world_name": "states-20261020",
    "seed": "20261020",
    "level_type": "minecraft:normal",
    "protection": CLAN_WORLD["protection"],
    "common": {"x": 0, "y": 0, "z": 12},
    "clans": {code: {"x": capitals[code]["x"], "y": 0, "z": capitals[code]["z"]} for code in CLAN_WORLD["clans"]},
    "castle_palettes": CLAN_WORLD["castle_palettes"],
}
(ROOT / "minecraft/server/world-config/clan-spawns-states.json").write_text(json.dumps(spawns, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"regions": len(regions), "points": len(points), "roads": len(roads), "road_blocks": round(sum(math.hypot(b[0]-a[0], b[1]-a[1]) for r in roads for a, b in zip(r["points"], r["points"][1:]))), "capturable": sum(p["capturable"] for p in points),
                  "resource_sites": sum("resource" in p for p in points), "unassigned": [p["code"] for p in points if p["region"] is None]}, ensure_ascii=False))
