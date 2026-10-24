"""Exact before/after geometry, rendered as a schematic; no screenshot/world editing."""
import argparse
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--plan-dir", default=".tools/sora-market-refinement-20261024")
OUT = ROOT / parser.parse_args().plan_dir
OUT.mkdir(parents=True, exist_ok=True)
regions = json.loads((ROOT / "minecraft/integration/neoforge/src/test/resources/regions-markets.json").read_text("utf-8"))["regions"]
plots = [r for r in regions if r["kind"] == "MARKET"]
plan = json.loads((OUT / "expansion-plan.json").read_text("utf-8"))
old_side, new_side = plan["old_side"], plan["new_side"]
old = {p["code"]: p["before"] for p in plan["markets"]}
assert {p["code"]: p["after"] for p in plan["markets"]} == {p["code"]: p["polygon"] for p in plots}
layout = json.loads((ROOT / "minecraft/server/world-config/worldgen-pack/aoc/layout.json").read_text("utf-8"))
image = Image.new("RGB", (1460, 1000), "#10121b")
d = ImageDraw.Draw(image)

def text(x, y, value, size=20, color="#ecf0f6", bold=False):
    font = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf", size)
    d.text((x, y), value, fill=color, font=font, anchor="mm")

text(730, 43, "БОЛЬШЕ МЕСТА ДЛЯ КЛАНОВЫХ МАГАЗИНОВ", 32, bold=True)
text(730, 85, "Сора · схема по точным координатам · одинаковый масштаб", 21, "#9aa9bd")
scale, cy = 2.45, 430
for cx, before in [(370, True), (1090, False)]:
    def pos(x, z): return cx + x * scale, cy + z * scale
    def rect(poly):
        xs = [v[0] for v in poly]; zs = [v[1] for v in poly]
        return [pos(min(xs), min(zs)), pos(max(xs), max(zs))]
    d.rounded_rectangle((cx-335, 120, cx+335, 740), radius=22, fill="#1b2230", outline="#344154", width=2)
    side = old_side if before else new_side
    text(cx, 151, f"{'БЫЛО' if before else 'СТАЛО'}  ·  {side} × {side}", 25, "#b5c2d3" if before else "#8ee6aa", True)
    d.ellipse([pos(-110, -110), pos(110, 110)], fill="#86b33e", outline="#e1e5c4", width=5)
    d.ellipse([pos(-106, -106), pos(106, 106)], outline="#547c32", width=2)
    for road in layout["roads"]:
        if not road["code"].startswith("road.main."): continue
        x, z = road["points"][0]; angle = math.atan2(x, -z)
        d.line([pos(36*math.sin(angle), -36*math.cos(angle)), pos(108*math.sin(angle), -108*math.cos(angle))], fill="#737c85", width=12)
        ex = round(82*math.sin(angle)+13*math.cos(angle)); ez = round(-82*math.cos(angle)+13*math.sin(angle))
        d.rectangle([pos(ex-5, ez-5), pos(ex+5, ez+5)], fill="#576373", outline="#c4d0d9", width=2)
    d.ellipse([pos(-36, -36), pos(36, 36)], fill="#737c85", outline="#d5d8e0", width=3)
    d.ellipse([pos(-27, -27), pos(27, 27)], outline="#e1e3e9", width=2)
    d.ellipse([pos(-19, -19), pos(19, 19)], outline="#e1e3e9", width=2)
    text(*pos(0, -4), "СОРА", 19, bold=True)
    text(*pos(0, 7), "центр", 14)
    d.rectangle([pos(-9, 45), pos(9, 60)], fill="#54596e", outline="#b7c3d5", width=2)
    for p in plots:
        poly = old[p["code"]] if before else p["polygon"]
        color = "#"+p["color"]
        d.rectangle(rect(poly), fill=color, outline="#f2f5f7", width=2)
        if not before:
            (x0, y0), (x1, y1) = rect(old[p["code"]])
            for x in range(round(x0), round(x1), 9):
                d.line((x, y0, min(x+4, x1), y0), fill="#ffffff", width=2)
                d.line((x, y1, min(x+4, x1), y1), fill="#ffffff", width=2)
            for y in range(round(y0), round(y1), 9):
                d.line((x0, y, x0, min(y+4, y1)), fill="#ffffff", width=2)
                d.line((x1, y, x1, min(y+4, y1)), fill="#ffffff", width=2)
        sx = sum(v[0] for v in poly)/4; sz = sum(v[1] for v in poly)/4
        text(*pos(sx, sz-4), "$", 22, "#111c2e", True)
        text(*pos(sx, sz+5), p["clan_code"], 12 if before else 14, "#111c2e", True)
    area = f"{side*side:,}".replace(",", " ")
    text(cx, 714, f"{area} блока² на клан", 23, bold=True)

growth = f"{plan['growth_percent']:.1f}".replace(".", ",")
text(730, 786, f"+{new_side-old_side} блока к стороне    ·    +{new_side*new_side-old_side*old_side} блока²    ·    +{growth}% площади", 25, "#8ee6aa", True)
text(730, 831, f"Пунктир справа — прежнее положение {old_side} × {old_side}. Центры теперь ближе к площади.", 20, "#bdc8d8")
text(730, 870, "Сдвиг к центру: около 6 блоков; у ZXC — 1 блок, чтобы сохранить зал порталов.", 20, "#bdc8d8")
text(730, 913, "Свои строят  ·  покупают все  ·  без PvP и рейдов", 23, bold=True)
text(730, 969, "Локальная подготовка · это схема границ, не новый скриншот с сервера", 17, "#8290a5")
target = OUT / "market-comparison.png"
image.save(target)
print(target)
