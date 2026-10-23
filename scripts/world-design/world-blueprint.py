#!/usr/bin/env python3
"""AnimeOnCraft world blueprint (design stage, no game code).

Single source of the numbers quoted in docs/WORLD_*.md: zone geometry, POI and
Resource Site coordinates, resource matrix checks, distances, travel times and
pregeneration estimates. Writes docs/world-design/blueprint.json and
docs/world-design/world-layout.svg/.png. Pure numpy + Pillow; run from repo root:

    python3 scripts/world-design/world-blueprint.py
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "world-design"

# --------------------------------------------------------------------------
# Geometry. Bearing: degrees clockwise from north. Minecraft north is -Z,
# east is +X, so x = r*sin(b), z = -r*cos(b).
# --------------------------------------------------------------------------
BORDER = 10_000            # world border half-size (20 000 x 20 000)
CAPITAL_SAFE_R = 300       # PvP off
CORE_R = 1_000             # neutral core
RING_R = 1_500             # "Кольцо застав" borderland ring
STATE_R = 4_200            # outer edge of starting clan regions
RIM_R = 5_200              # "Внешний вал" outer borderland
LAND_R = 9_700             # continent edge (ocean beyond)
STATE_HALF = 25.0          # clan sector half-width, degrees
WEDGE_HALF = 11.0          # borderland wedge half-width (25+11 = 36)
GULF = dict(bearing=288.0, half=6.0, from_r=3_700)  # "Закатный залив"

CLANS = {  # bearing, registry colour (current clan-world.json)
    "SCAM": (0.0, "#E84A5F"),
    "NYA": (72.0, "#F47DCB"),
    "ZXC": (144.0, "#4DA8FF"),
    "AKUMA": (216.0, "#A970FF"),
    "YAKUZA": (288.0, "#FFB347"),
}
ORDER = list(CLANS)

FAMILIES = ["FERROUS", "NONFERROUS", "PRECIOUS", "ARCANE", "TECH",
            "TIMBER", "AGRARIAN", "ALCHEMY", "MARINE"]
STRATEGIC = {"FERROUS", "NONFERROUS", "PRECIOUS", "ARCANE"}
# -2 missing, -1 scarce, 0 normal, +1 good, +2 abundant/exporter
MATRIX = {
    "SCAM":   [+2, +1, +1, -2, +1,  0, -2, -1,  0],
    "NYA":    [-2, -1, -1, +1, -1, +2, +2, +1, -1],
    "ZXC":    [+1, +2, -1, -1, +2, -1,  0, -2,  0],
    "AKUMA":  [-1, -2,  0, +2,  0, +1, -1, +2, -1],
    "YAKUZA": [-1, -1, +2, -2, -1,  0, +1,  0, +2],
}
MULTIPLIER = {+2: 1.8, +1: 1.3, 0: 1.0, -1: 0.55, -2: 0.2}

WEDGES = [  # bearing, between, name, profile
    (36.0, ("SCAM", "NYA"), "Железный перевал", "горный перевал, шахты"),
    (108.0, ("NYA", "ZXC"), "Долина мельниц", "речная долина, фермы, мосты"),
    (180.0, ("ZXC", "AKUMA"), "Пепельная гряда", "вулканические холмы, руины"),
    (252.0, ("AKUMA", "YAKUZA"), "Мангровая дельта", "дельта, протоки, острова"),
    (324.0, ("YAKUZA", "SCAM"), "Северный тракт", "скалистое побережье, крепости"),
]


def xz(r: float, b: float) -> tuple[int, int]:
    rad = math.radians(b)
    snap = lambda v: int(round(v / 16.0)) * 16
    return snap(r * math.sin(rad)), snap(-r * math.cos(rad))


def ang_diff(a: float, b: float) -> float:
    return abs((a - b + 180) % 360 - 180)


# --------------------------------------------------------------------------
# POI catalogue
# --------------------------------------------------------------------------
pois: list[dict] = []
sites: list[dict] = []


def poi(pid, kind, name, r, b, zone, **extra):
    x, z = xz(r, b)
    pois.append(dict(id=pid, type=kind, name=name, x=x, z=z, zone=zone, **extra))


def site(sid, name, family, tier, r, b, zone, physical=True):
    x, z = xz(r, b)
    sites.append(dict(id=sid, name=name, family=family, tier=tier, x=x, z=z,
                      zone=zone, kind="PIT" if physical else "CACHE"))


poi("core.capital", "CAPITAL", "Сора — столица Одного Неба", 0, 0, "CORE", capturable=False)
poi("core.boss_arena", "EVENT_ZONE", "Разлом Одного Неба (мировой босс)", 800, 180, "CORE", capturable=False)
for clan, (b, _) in CLANS.items():
    poi(f"region.{clan.lower()}.capital", "CLAN_CAPITAL", f"Столица {clan}", 2_700, b, clan, capturable=False)
    poi(f"ring.outpost.{clan.lower()}", "OUTPOST", f"Застава у ворот {clan}", 1_250, b, "RING", capturable=True)
    tower_b = b - 9 if clan == "YAKUZA" else b  # YAKUZA: tower on the gulf shore
    poi(f"rim.watchtower.{clan.lower()}", "WATCHTOWER", f"Дозорная башня за {clan}", 4_700, tower_b, "RIM", capturable=True)
poi("region.yakuza.port", "PORT", "Порт YAKUZA (домашний)", 3_850, 288, "YAKUZA", capturable=False)

WEDGE_POI = {
    36.0: [("FORT", "Форт Перевала"), ("PASS", "Перевал Ветров"), ("VILLAGE", "Деревня рудокопов")],
    108.0: [("FORT", "Форт Мельниц"), ("BRIDGE", "Большой мост"), ("VILLAGE", "Мельничная деревня")],
    180.0: [("FORT", "Пепельная крепость"), ("PASS", "Огненный проход"), ("VILLAGE", "Деревня у лавовых полей")],
    252.0: [("FORT", "Форт Дельты"), ("PORT", "Порт Дельты"), ("VILLAGE", "Деревня на сваях")],
    324.0: [("FORT", "Северная цитадель"), ("BRIDGE", "Мост через фьорд"), ("VILLAGE", "Рыбацкая деревня")],
}
for i, (b, pair, wname, _) in enumerate(WEDGES, start=1):
    for (kind, name), r in zip(WEDGE_POI[b], (2_700, 2_000, 3_500)):
        poi(f"border.w{i}.{kind.lower()}", kind, name, r, b, f"W{i}", capturable=True)

poi("frontier.settlement.n", "VILLAGE", "Вольный посёлок Север", 7_300, 0 + 18, "FRONTIER", capturable=True)
poi("frontier.settlement.e", "VILLAGE", "Вольный посёлок Восток", 7_300, 126, "FRONTIER", capturable=True)
poi("frontier.settlement.s", "VILLAGE", "Вольный посёлок Юг", 7_300, 198, "FRONTIER", capturable=True)
poi("frontier.port.gulf", "PORT", "Бухта контрабандистов", 9_000, 297, "FRONTIER", capturable=True)
poi("frontier.event.rift", "EVENT_ZONE", "Звёздный разлом (ивенты)", 8_300, 72 + 36 - 10, "FRONTIER", capturable=False)
poi("frontier.boss.lair", "BOSS_LAIR", "Логово древнего зверя", 8_600, 216 + 20, "FRONTIER", capturable=False)

# Resource Sites — see docs/RESOURCE_SYSTEM.md for the selection rules.
ring = [(36, "TIMBER", "Священная роща"), (108, "FERROUS", "Старая штольня"),
        (180, "AGRARIAN", "Общинные поля"), (252, "MARINE", "Озёрная рыбня"),
        (324, "NONFERROUS", "Медный ручей")]
for b, fam, name in ring:
    site(f"site.ring.{b}", name, fam, 1, 1_250, b, "RING")
wedge_sites = {
    36: [("FERROUS", 2, "Железная жила перевала"), ("ARCANE", 3, "Аметистовая расщелина")],
    108: [("ALCHEMY", 2, "Светлая топь"), ("PRECIOUS", 3, "Золотое русло")],
    180: [("NONFERROUS", 2, "Цинковые осыпи"), ("FERROUS", 3, "Пепельные копи")],
    252: [("NONFERROUS", 2, "Медные отмели"), ("TECH", 3, "Затонувшая лаборатория")],
    324: [("AGRARIAN", 2, "Северные пашни"), ("ARCANE", 3, "Ледяной кристальный грот")],
}
for i, (b, *_rest) in enumerate(WEDGES, start=1):
    for (fam, tier, name), r in zip(wedge_sites[int(b)], (1_800, 3_950)):
        site(f"site.w{i}.t{tier}", name, fam, tier, r, b, f"W{i}")
rim = [(36, "PRECIOUS", "Глубинный алмазный разлом"), (108, "ARCANE", "Кратер мифрила"),
       (180, "ALCHEMY", "Сад пепельных цветов"), (252, "FERROUS", "Затопленный рудник"),
       (324, "TECH", "Красный карьер")]
for b, fam, name in rim:
    site(f"site.rim.{b}", name, fam, 3, 4_700, b + 6, "RIM")
frontier = [(8_200, 54, "ARCANE", 4, "Кратер звёздного кристалла", True),
            (8_200, 198 + 32, "PRECIOUS", 4, "Сердце горы", True),
            (7_600, 162, "NONFERROUS", 3, "Цинковое плато", True),
            (9_300, 280, "MARINE", 3, "Жемчужная отмель", False),
            (7_800, 340, "ALCHEMY", 3, "Лунный лес", True),
            (6_000, 90 + 8, "FERROUS", 2, "Брошенный прииск", True),
            (6_000, 234 - 8, "TIMBER", 2, "Древний лес", True),
            (6_000, 306 + 8, "TECH", 2, "Старая электростанция", False)]
for r, b, fam, tier, name, phys in frontier:
    site(f"site.frontier.{int(b)}", name, fam, tier, r, b, "FRONTIER", physical=phys)

# Frontier slots: A–E inner (between two states), F–J outer (behind one state)
slots = []
for i, b in enumerate([36, 108, 180, 252, 324]):
    x, z = xz(6_700, b)
    slots.append(dict(id=f"frontier.slot.{'ABCDE'[i]}", ring="INNER", bearing=b, x=x, z=z, radius=1_400))
for i, (clan, (b, _)) in enumerate(CLANS.items()):
    x, z = xz(8_000, b)
    slots.append(dict(id=f"frontier.slot.{'FGHIJ'[i]}", ring="OUTER", bearing=b, x=x, z=z,
                      radius=1_400, note="архипелаг в Закатном заливе" if clan == "YAKUZA" else ""))

# --------------------------------------------------------------------------
# Zone classification raster (1 px = 16 blocks = 1 chunk)
# --------------------------------------------------------------------------
N = BORDER * 2 // 16
coords = (np.arange(N) * 16 - BORDER + 8).astype(np.float64)
X, Z = np.meshgrid(coords, coords)
R = np.hypot(X, Z)
B = (np.degrees(np.arctan2(X, -Z)) + 360) % 360

zone = np.full((N, N), "OCEAN", dtype=object)
land = R <= LAND_R
zone[land] = "FRONTIER"
zone[R <= RIM_R] = "RIM"
for i, (b, *_r) in enumerate(WEDGES, start=1):
    zone[(R > RING_R) & (R <= STATE_R) & (np.vectorize(ang_diff)(B, b) <= WEDGE_HALF)] = f"W{i}"
for clan, (b, _) in CLANS.items():
    zone[(R > RING_R) & (R <= STATE_R) & (np.vectorize(ang_diff)(B, b) <= STATE_HALF)] = clan
zone[R <= RING_R] = "RING"
zone[R <= CORE_R] = "CORE"
gulf = (R >= GULF["from_r"]) & (np.vectorize(ang_diff)(B, GULF["bearing"]) <= GULF["half"])
zone[gulf & (R > STATE_R)] = "GULF"
slot_mask = np.zeros((N, N), dtype=object)
for s in slots:
    m = (np.hypot(X - s["x"], Z - s["z"]) <= s["radius"]) & land
    slot_mask[m] = s["id"]


def area(mask) -> int:
    return int(np.count_nonzero(mask)) * 256


def zone_at(x, z):
    i = int((z + BORDER) // 16)
    j = int((x + BORDER) // 16)
    return zone[i, j]


stats = {}
for name in ORDER + ["CORE", "RING", "RIM", "FRONTIER", "GULF", "OCEAN"] + [f"W{i}" for i in range(1, 6)]:
    stats[name] = area(zone == name)
slot_area = {s["id"]: area(slot_mask == s["id"]) for s in slots}

# --------------------------------------------------------------------------
# Checks — design invariants also reused as future automated tests
# --------------------------------------------------------------------------
problems = []
for clan, row in MATRIX.items():
    if sum(row) != 0:
        problems.append(f"{clan}: matrix sum {sum(row)} != 0")
    if +2 not in row or -2 not in row:
        problems.append(f"{clan}: needs at least one exporter (+2) and one missing (-2) family")
    if all(v >= 0 for v in row):
        problems.append(f"{clan}: has full resource set")
col = {f: sum(MATRIX[c][k] for c in ORDER) for k, f in enumerate(FAMILIES)}
for f in FAMILIES:
    n = sum(1 for s in sites if s["family"] == f)
    need = 3 if f in STRATEGIC else 2
    if n < need:
        problems.append(f"{f}: only {n} neutral sites (< {need})")
for p in pois + sites:
    actual = zone_at(p["x"], p["z"])
    expected = p["zone"]
    if expected != actual:
        problems.append(f"{p['id']}: expected zone {expected}, got {actual}")
for s in slots:  # slots must lie only in FRONTIER/GULF-free land
    inside = zone[slot_mask == s["id"]]
    allowed = {"FRONTIER", "GULF"} if s.get("note") else {"FRONTIER"}  # J = archipelago
    bad = set(inside) - allowed
    if bad:
        problems.append(f"{s['id']}: overlaps {bad}")
# neighbour complementarity: each adjacent pair has a family where one is +2/+1 and other -2
pairs = []
for i, c in enumerate(ORDER):
    d = ORDER[(i + 1) % 5]
    trade = [f for k, f in enumerate(FAMILIES)
             if (MATRIX[c][k] >= 1 and MATRIX[d][k] <= -1) or (MATRIX[d][k] >= 1 and MATRIX[c][k] <= -1)]
    pairs.append((c, d, trade))
    if len(trade) < 2:
        problems.append(f"{c}-{d}: weak trade complementarity {trade}")

# --------------------------------------------------------------------------
# Distances, travel, pregeneration
# --------------------------------------------------------------------------
cap = {c: next(p for p in pois if p["id"] == f"region.{c.lower()}.capital") for c in ORDER}
dist = lambda a, b: math.hypot(a["x"] - b["x"], a["z"] - b["z"])
SPEEDS = {"пешком (бег)": 5.6, "лошадь": 10.0, "лодка": 8.0, "поезд Create": 25.0}
routes = [("Сора → столица клана", 2_700)]
routes.append(("столица → соседняя столица", dist(cap["SCAM"], cap["NYA"])))
routes.append(("столица → несоседняя столица", dist(cap["SCAM"], cap["ZXC"])))
routes.append(("Сора → внутренний слот (A–E)", 6_700))
routes.append(("Сора → внешний слот (F–J)", 8_000))
routes.append(("столица клана → ближайший форт", dist(cap["SCAM"], next(p for p in pois if p["id"] == "border.w1.fort"))))
travel = [(name, round(d), {k: round(d / v / 60, 1) for k, v in SPEEDS.items()}) for name, d in routes]

pregen = []
for side in (20_000, 25_000, 30_000):
    land_r = side / 2 * 0.97
    chunks = math.pi * land_r ** 2 / 256
    pregen.append(dict(size=f"{side}×{side}", land_chunks=round(chunks),
                       hours_at_10cps=round(chunks / 10 / 3600, 1),
                       hours_at_25cps=round(chunks / 25 / 3600, 1),
                       disk_gb_4mb_region=round(chunks / 1024 * 4 / 1024, 1),
                       disk_gb_8mb_region=round(chunks / 1024 * 8 / 1024, 1)))
stage_a_chunks = round(math.pi * (RIM_R + 300) ** 2 / 256)

# --------------------------------------------------------------------------
# Render
# --------------------------------------------------------------------------
OUT.mkdir(parents=True, exist_ok=True)
COLORS = {"CORE": "#d9d2b6", "RING": "#b9b39a", "RIM": "#9a9682", "FRONTIER": "#5f7a58",
          "GULF": "#2a5d86", "OCEAN": "#1d3b57"}
for i in range(1, 6):
    COLORS[f"W{i}"] = "#8f8a73"
for c, (_, col_) in CLANS.items():
    COLORS[c] = col_
rgb = lambda h: tuple(int(h[k:k + 2], 16) for k in (1, 3, 5))
img = np.zeros((N, N, 3), dtype=np.uint8)
for name, hexc in COLORS.items():
    img[zone == name] = rgb(hexc)
for s in slots:
    m = slot_mask == s["id"]
    img[m] = (img[m] * 0.55 + np.array(rgb("#c9e7a5")) * 0.45).astype(np.uint8)
SCALE = 1
im = Image.fromarray(img).resize((1000, 1000), Image.NEAREST)
draw = ImageDraw.Draw(im)
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 13)
    big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 18)
except OSError:
    font = big = ImageFont.load_default()
px = lambda x, z: ((x + BORDER) / (2 * BORDER) * 1000, (z + BORDER) / (2 * BORDER) * 1000)
for b in range(0, 360, 2):  # radial ticks off
    pass
for s in slots:
    cx, cz = px(s["x"], s["z"])
    rr = s["radius"] / (2 * BORDER) * 1000
    draw.ellipse([cx - rr, cz - rr, cx + rr, cz + rr], outline="#e8ffd0", width=2)
    draw.text((cx - 5, cz - 8), s["id"][-1], fill="#ffffff", font=big)
ICON = {"FORT": "#ffffff", "PASS": "#ffe08a", "BRIDGE": "#ffe08a", "VILLAGE": "#a0ffb0",
        "PORT": "#8fd6ff", "OUTPOST": "#ffffff", "WATCHTOWER": "#dddddd"}
for p in pois:
    x, y = px(p["x"], p["z"])
    if p["type"] in ICON:
        draw.rectangle([x - 4, y - 4, x + 4, y + 4], fill=ICON[p["type"]], outline="#000")
    elif p["type"] in ("EVENT_ZONE", "BOSS_LAIR"):
        draw.polygon([(x, y - 7), (x + 7, y + 6), (x - 7, y + 6)], fill="#ff4040", outline="#000")
    elif p["type"] == "CLAN_CAPITAL":
        draw.ellipse([x - 7, y - 7, x + 7, y + 7], fill="#000000", outline="#ffffff", width=2)
TIER_R = {1: 3, 2: 4, 3: 5, 4: 7}
for s in sites:
    x, y = px(s["x"], s["z"])
    r = TIER_R[s["tier"]]
    draw.ellipse([x - r, y - r, x + r, y + r], fill="#ffd400", outline="#000")
x, y = px(0, 0)
draw.ellipse([x - 9, y - 9, x + 9, y + 9], fill="#ffffff", outline="#000", width=2)
draw.text((x + 10, y - 8), "Сора", fill="#000", font=big)
for clan, (b, _) in CLANS.items():
    lx, lz = px(*xz(3_500, b))
    draw.text((lx - 28, lz - 10), clan, fill="#000000", font=big)
for i, (b, _pair, name, _p) in enumerate(WEDGES, start=1):
    lx, lz = px(*xz(4_400, b))
    draw.text((lx - 40, lz - 6), name, fill="#ffffff", font=font)
draw.text((10, 10), "AnimeOnCraft — схема мира 20 000 × 20 000 (1 px = 20 блоков)", fill="#ffffff", font=big)
legend = [("■ форт/застава/башня", "#ffffff"), ("■ перевал/мост", "#ffe08a"), ("■ деревня", "#a0ffb0"),
          ("■ порт", "#8fd6ff"), ("● Resource Site (размер = tier)", "#ffd400"),
          ("▲ ивент / босс", "#ff4040"), ("◯ слот Frontier A–J", "#e8ffd0")]
for k, (t, c) in enumerate(legend):
    draw.text((10, 900 + k * 14), t, fill=c, font=font)
draw.text((935, 10), "N ↑", fill="#ffffff", font=big)
im.save(OUT / "world-layout.png", optimize=True)

blueprint = dict(
    format_version=1, status="DESIGN — не применено к миру",
    world=dict(border_half=BORDER, land_radius=LAND_R, capital_safe_radius=CAPITAL_SAFE_R,
               core_radius=CORE_R, ring_radius=RING_R, state_radius=STATE_R, rim_radius=RIM_R,
               state_half_deg=STATE_HALF, wedge_half_deg=WEDGE_HALF, gulf=GULF),
    clans={c: dict(bearing=b, color=col_, capital=dict(x=cap[c]["x"], z=cap[c]["z"]),
                   resources=dict(zip(FAMILIES, MATRIX[c]))) for c, (b, col_) in CLANS.items()},
    borderlands=[dict(id=f"W{i}", bearing=b, between=list(pair), name=n, profile=p)
                 for i, (b, pair, n, p) in enumerate(WEDGES, start=1)],
    frontier_slots=slots, points=pois, resource_sites=sites,
    area_blocks2=stats, slot_area_blocks2=slot_area, family_column_sums=col,
    travel_minutes=travel, pregeneration=pregen, stage_a_chunks=stage_a_chunks,
    neighbour_trade=pairs, problems=problems)
(OUT / "blueprint.json").write_text(json.dumps(blueprint, ensure_ascii=False, indent=1), encoding="utf-8")

print("problems:", problems or "none")
print("areas (M blocks²):", {k: round(v / 1e6, 2) for k, v in stats.items()})
print("slot areas:", {k[-1]: round(v / 1e6, 2) for k, v in slot_area.items()})
print("col sums:", col)
for c, d, t in pairs:
    print("trade", c, d, t)
for t in travel:
    print("travel", t)
for p in pregen:
    print("pregen", p)
print("stage A chunks", stage_a_chunks)
print("points", len(pois), "capturable", sum(1 for p in pois if p.get("capturable")), "sites", len(sites))
for f in FAMILIES:
    print(f, [f"{s['name']} T{s['tier']}" for s in sites if s["family"] == f])
