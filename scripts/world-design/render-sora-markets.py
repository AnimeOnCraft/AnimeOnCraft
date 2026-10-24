"""Schematic review image from the exact game-test catalog; does not read or change a Minecraft world."""
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[2]
regions = json.loads((root / "minecraft/integration/neoforge/src/test/resources/regions-markets.json").read_text(encoding="utf-8"))["regions"]
layout = json.loads((root / "minecraft/server/world-config/worldgen-pack/aoc/layout.json").read_text(encoding="utf-8"))
out = root / ".tools/sora-market-refinement-20261024"
out.mkdir(parents=True, exist_ok=True)
image = Image.new("RGB", (1080, 1040), "#100d1c")
d = ImageDraw.Draw(image)
def font(size, bold=False):
    return ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf", size)
def text(at, value, fill="#eee8ff", size=20, bold=False, anchor="mm"):
    d.text(at, value, fill=fill, font=font(size,bold), anchor=anchor)
scale, cx, cz = 3.0, 540, 460
def pos(x,z): return (cx+x*scale, cz+z*scale)
text((540,45), "СОРА · КЛАНОВЫЙ РЫНОК", size=31, bold=True)
text((540,86), "Схема размещения · одинаковые участки 36 × 36 блоков", fill="#aaa0bf", size=20)
for line in range(-100,101,20):
    d.line([pos(line,-110),pos(line,110)],fill="#211b30")
    d.line([pos(-110,line),pos(110,line)],fill="#211b30")
d.ellipse([pos(-110,-110),pos(110,110)], outline="#706583", width=6)
d.ellipse([pos(-36,-36),pos(36,36)], fill="#30273f", outline="#786489", width=2)
for road in layout["roads"]:
    if not road["code"].startswith("road.main."): continue
    x,z=road["points"][0]; a=math.atan2(x,-z)
    d.line([pos(36*math.sin(a),-36*math.cos(a)),pos(110*math.sin(a),-110*math.cos(a))],fill="#81768a",width=13)
    ex=round(82*math.sin(a)+13*math.cos(a)); ez=round(-82*math.cos(a)+13*math.sin(a))
    d.rectangle([pos(ex-5,ez-5),pos(ex+5,ez+5)],fill="#4a4158",outline="#928398",width=2)
d.rectangle([pos(-9,45),pos(9,60)], fill="#4c496b",outline="#a59ac4",width=2)
text(pos(0,52),"П",size=20,bold=True)
d.ellipse([pos(-3,-3),pos(3,3)],fill="#8ad9fb")
text(pos(0,-13),"Общая",size=18)
text(pos(0,12),"площадь",size=18)
plots = [r for r in regions if r["kind"]=="MARKET"]
for p in plots:
    xs=[v[0] for v in p["polygon"]]; zs=[v[1] for v in p["polygon"]]
    color="#"+p["color"]; rgb=tuple(int(p["color"][i:i+2],16)//3 for i in (0,2,4))
    d.rectangle([pos(min(xs),min(zs)),pos(max(xs),max(zs))],fill=rgb,outline=color,width=4)
    x=(min(xs)+max(xs))/2; z=(min(zs)+max(zs))/2
    text(pos(x,z-3),p["clan_code"],size=16,bold=True)
    text(pos(x,z+5),f"{max(xs)-min(xs)} × {max(zs)-min(zs)}",fill="#d2c7e6",size=13)
text((540,114),"СЕВЕР ↑",size=15,fill="#a69ab8")
text((540,815),"Строит свой клан · покупают все · без PvP и рейдов",size=22,bold=True)
for i,p in enumerate(sorted(plots,key=lambda r:r["code"])):
    xs=[v[0] for v in p["polygon"]]; zs=[v[1] for v in p["polygon"]]
    text((185+(i%3)*280,875+(i//3)*60),p["clan_code"],fill="#"+p["color"],size=19,bold=True)
    text((185+(i%3)*280,901+(i//3)*60),f"X {min(xs)}…{max(xs)-1}  Z {min(zs)}…{max(zs)-1}",size=15,fill="#b9accd")
text((540,1010),"Локальный проект · схема по координатам, не скриншот игрового мира",fill="#857a95",size=15)
image.save(out / "market-plan.png")
print(out / "market-plan.png")
