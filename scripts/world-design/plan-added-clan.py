#!/usr/bin/env python3
"""Plan the territory of a clan added to the live states world (World Design stage 8, by hand).

    python3 scripts/world-design/plan-added-clan.py VORIX [--points=keep|annex]

Reads the clan's `outline` (the area the owner drew), `capital`, `outpost` from docs/world-design/added-clans.json and
the current regions from build-world-profile.py output, and writes back into the same file the exact integer polygons:
the new state, the neutral regions it cuts (replacements) and the neutral regions it swallows (archived). Needs
shapely; build-world-profile.py itself only reads the stored polygons, so the world profile stays reproducible without it.

Rules: no state, core, ring or market is touched; a neutral region the outline covers by ≥ 90 % is swallowed whole;
the remaining neutral land keeps one polygon per region (slivers go to the new state). With --points=keep every
capturable point inside the outline stays neutral: a pocket (point radius + 40) joined to the nearest neutral region by
a 48-block corridor. With --points=annex those points become the clan's home points (not capturable).
Shared edges are noded together and rounded once, so neighbouring polygons keep identical vertices (no gaps/overlaps).
"""
from __future__ import annotations

import json
import math
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

from shapely import set_precision
from shapely.geometry import LineString, Point, Polygon
from shapely.ops import nearest_points, polygonize, unary_union

ROOT = Path(__file__).resolve().parents[2]
ADDED = ROOT / "docs/world-design/added-clans.json"
args = [a for a in sys.argv[1:] if not a.startswith("--")]
mode = next((a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--points=")), "keep")
assert mode in ("keep", "annex"), mode
clan = args[0]
added = json.loads(ADDED.read_text(encoding="utf-8"))
spec = added["clans"][clan]
# Plan against the generated world without any added clan (the real profile may already contain this one).
with tempfile.TemporaryDirectory() as work:
    plain = Path(work) / "world-states.toml"
    subprocess.run([sys.executable, str(ROOT / "scripts/world-design/build-world-profile.py"), f"--base-only={plain}"], check=True, capture_output=True)
    catalog = tomllib.loads(plain.read_text(encoding="utf-8"))
base = {r["code"]: r for r in catalog["regions"]}
points = {p["code"]: p for p in catalog["points"]}
FIXED_KINDS = ("STATE", "CORE", "MARKET")
fixed = [c for c, r in base.items() if r["kind"] in FIXED_KINDS or c.startswith("ring.")]
shape = {c: Polygon(r["polygon"]) for c, r in base.items()}

V = Polygon(spec["outline"]).buffer(0)
for c in fixed:
    if V.intersection(shape[c]).area > 0: raise SystemExit(f"outline overlaps {c}; redraw it outside states, Sora and the ring")
neutral = [c for c in base if c not in fixed and shape[c].intersection(V).area > 0]
swallowed = [c for c in neutral if shape[c].intersection(V).area / shape[c].area >= 0.9]
V = unary_union([V] + [shape[c] for c in swallowed])
cut = [c for c in neutral if c not in swallowed]
inside = [p for p in points.values() if p["capturable"] and V.contains(Point(p["x"], p["z"]))]
gift: dict[str, list] = {c: [] for c in cut}
if mode == "keep":
    for p in inside:
        at = Point(p["x"], p["z"])
        best = None
        for c in cut:  # nearest stretch of the outline that borders neutral land
            border = V.boundary.intersection(shape[c].buffer(0.5))
            if border.is_empty: continue
            exit_point = nearest_points(border, at)[0]
            if best is None or exit_point.distance(at) < best[0]: best = (exit_point.distance(at), c, exit_point)
        _, target, exit_point = best
        dx, dz = exit_point.x - at.x, exit_point.y - at.y; length = math.hypot(dx, dz)
        tip = (exit_point.x + dx / length * 30, exit_point.y + dz / length * 30)
        pocket = unary_union([at.buffer(p["radius"] + 40, 16), LineString([(at.x, at.y), tip]).buffer(24, 4)])
        gift[target].append(pocket.intersection(unary_union([V, shape[target]])))
        V = V.difference(pocket)
rest = {c: unary_union([shape[c].difference(V)] + gift[c]) for c in cut}
for _ in range(4):  # slivers of neutral land go to the new state
    changed = False
    for c in cut:
        parts = sorted(getattr(rest[c], "geoms", [rest[c]]), key=lambda g: -g.area)
        for g in parts[1:]:
            V = unary_union([V, g]); rest[c] = rest[c].difference(g); changed = True
    if not changed: break
if V.geom_type != "Polygon" or list(V.interiors): raise SystemExit(f"{clan} territory is not one simple polygon")
for c in cut:
    if rest[c].geom_type != "Polygon" or list(rest[c].interiors):
        print(c, rest[c].geom_type, [round(g.area) for g in getattr(rest[c], "geoms", [rest[c]])], [len(g.interiors) for g in getattr(rest[c], "geoms", [rest[c]])], file=sys.stderr)
        raise SystemExit(f"{c} would split")

# Node every changed boundary together on the block grid (fixed precision 1), so shared edges get the same integer
# vertices in both neighbours; each face goes to the owner it overlaps most; owners are rebuilt from whole faces.
owners = {clan: V, **rest}
network = unary_union([g.boundary for g in owners.values()])
for _ in range(3): network = unary_union(set_precision(network, 1.0))
by_owner: dict[str, list] = {k: [] for k in owners}
for face in polygonize(network):
    share = {k: g.intersection(face).area for k, g in owners.items()}
    owner = max(share, key=share.get)
    if share[owner] > 0: by_owner[owner].append(face)


def ring(geometry) -> list[list[int]]:
    geometry = set_precision(geometry, 1.0)
    if geometry.geom_type != "Polygon" or list(geometry.interiors):
        print(geometry.geom_type, [round(g.area) for g in getattr(geometry, "geoms", [geometry])], file=sys.stderr); raise SystemExit("rounding broke a polygon")
    coords = [(round(x), round(z)) for x, z in geometry.exterior.coords[:-1]]
    out = []
    for i, p in enumerate(coords):  # drop collinear vertices
        a, b = coords[i - 1], coords[(i + 1) % len(coords)]
        if (p[0] - a[0]) * (b[1] - a[1]) - (p[1] - a[1]) * (b[0] - a[0]) != 0: out.append([p[0], p[1]])
    if not Polygon(out).exterior.is_ccw: out.reverse()  # same winding as the generated regions
    return out


result = {}
for k, v in by_owner.items(): result[k] = ring(unary_union(v))


def weld(rings: dict[str, list[list[int]]], fixed: list[list[list[int]]]) -> None:
    """Rounding moves a junction vertex off the straight edge of the neighbour that has no vertex there, leaving a
    sub-block sliver whose block centres belong to nobody. Insert every such vertex into that edge as well."""
    for _ in range(4):
        moved = False
        everyone = [*rings.values(), *fixed]
        for code, poly in rings.items():
            mine = {tuple(p) for p in poly}
            others = {tuple(p) for other in everyone if other is not poly for p in other} - mine
            out = []
            for i, a in enumerate(poly):
                b = poly[(i + 1) % len(poly)]; out.append(a)
                edge = LineString([a, b]); dx, dz = b[0] - a[0], b[1] - a[1]; length2 = dx * dx + dz * dz
                hits = [q for q in others if edge.distance(Point(q)) < 1.0 and 0 < ((q[0] - a[0]) * dx + (q[1] - a[1]) * dz) < length2]
                for q in sorted(hits, key=lambda q: (q[0] - a[0]) * dx + (q[1] - a[1]) * dz): out.append([q[0], q[1]]); moved = True
            rings[code] = out
        if not moved: return
    raise SystemExit("could not weld shared edges")


weld(result, [r["polygon"] for c, r in base.items() if c not in result and c not in swallowed])
spec["polygon"] = result[clan]
spec["points_mode"] = mode
spec["original_polygons"] = {c: base[c]["polygon"] for c in cut + swallowed}
spec["replaced"] = {c: result[c] for c in cut}
spec["archived"] = swallowed
spec["annexed_points"] = [p["code"] for p in inside] if mode == "annex" else []
ADDED.write_text(json.dumps(added, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(json.dumps({"clan": clan, "mode": mode, "area": round(Polygon(result[clan]).area), "vertices": len(result[clan]),
                  "replaced": {c: round(Polygon(p).area / shape[c].area * 100, 1) for c, p in spec["replaced"].items()},
                  "archived": swallowed, "points_inside": [p["code"] for p in inside]}, ensure_ascii=False))
