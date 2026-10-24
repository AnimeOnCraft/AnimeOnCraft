"""Guild board balance model: reads the quest file (the shipped one or a server override) and prints
coins and progress per rank, the path F->S and the server's daily coin flow, as Markdown.

  python scripts/guild-balance.py [path/to/guild-quests.json] [--out report.md]

The model is deliberately simple; it is a sanity check for the numbers in the file, not a forecast.
Assumptions (change them here): a solo quest takes as long as the cooldown after it, so one cycle is
2 x cooldown; a team quest takes TEAM_MINUTES; a casual player plays 1 h a day, an active one 3 h.
"""
import json, math, sys, pathlib

RANKS = ['F', 'E', 'D', 'C', 'B', 'A', 'S']
COOLDOWN = {'F': 5, 'E': 10, 'D': 15, 'C': 20, 'B': 25, 'A': 30, 'S': 35}
XP = {'F': 1, 'E': 2, 'D': 3, 'C': 5, 'B': 8, 'A': 12, 'S': 20}
NEXT = {'F': 10, 'E': 30, 'D': 60, 'C': 125, 'B': 240, 'A': 480, 'S': 0}
TEAM_MINUTES = {'B': 30, 'A': 45, 'S': 60}
PROFILES = {'1 ч/день': 1.0, '3 ч/день': 3.0}
EXAM_MINUTES = 40
DEFAULT = pathlib.Path(__file__).resolve().parent.parent / 'minecraft/integration/neoforge/src/main/resources/animeoncraft/guild-quests.json'

def money(c):
    c = int(round(c)); parts = []
    for v, n in ((100, 'зол.'), (10, 'жел.'), (1, 'мед.')):
        if c >= v or (v == 1 and not parts): parts.append(f'{c // v} {n}'); c %= v
        if c == 0: break
    return ' '.join(parts)

def load(path):
    d = json.loads(pathlib.Path(path).read_text(encoding='utf-8'))
    e = d.get('economy', {})
    eco = {'caps': {'F': 100, 'E': 150, 'D': 200, 'C': 300, 'B': 500, 'A': 800, 'S': 1200}, 'fees': {'F': 0, 'E': 20, 'D': 50, 'C': 100, 'B': 300, 'A': 800, 'S': 2000},
           'budget': 3000, 'over': 25, 'over_budget': 25, 'daily': 8}
    eco['caps'].update(e.get('dailyCap', {})); eco['fees'].update(e.get('rankFee', {}))
    eco['budget'] = e.get('serverBudget', eco['budget']); eco['over'] = e.get('overCapPercent', eco['over']); eco['over_budget'] = e.get('overBudgetPercent', eco['over_budget']); eco['daily'] = e.get('dailyQuests', eco['daily'])
    solo = {r: [] for r in RANKS}; team = {r: [] for r in RANKS}
    for q in d['quests']:
        if q.get('exam'): continue
        mid = (q['reward'][0] + q['reward'][1]) / 2
        (team if q.get('party') else solo)[q['rank']].append(mid)
    avg = lambda xs: sum(xs) / len(xs) if xs else 0
    return eco, {r: avg(solo[r]) for r in RANKS}, {r: avg(team[r]) for r in RANKS}

def capped(raw, cap, over):
    return min(raw, cap) + max(0, raw - cap) * over / 100

def day_of(rank, hours, eco, solo, team):
    """Coins (raw, paid), xp and quests for one day of play at this rank."""
    minutes = hours * 60; coins = xp = quests = 0
    if rank in TEAM_MINUTES and team[rank]:
        n = min(int(minutes // TEAM_MINUTES[rank]), 2 * math.ceil(hours / 2))
        coins += n * team[rank]; xp += n * XP[rank]; quests += n; minutes -= n * TEAM_MINUTES[rank]
    board = rank if solo[rank] else 'C'
    n = int(minutes // (2 * COOLDOWN[rank]))
    if RANKS.index(rank) >= RANKS.index('C'): n = min(n, eco['daily'])
    coins += n * solo[board]; quests += n
    if board == rank: xp += n * XP[rank]
    elif RANKS.index(board) == RANKS.index(rank) - 1: xp += n * max(1, XP[board] // 2)
    return coins, capped(coins, eco['caps'][rank], eco['over']), xp, quests

def path(hours, eco, solo, team):
    rank, xp, wallet, days, rows, earned = 'F', 0, 0, 0, [], 0
    while rank != 'S' and days < 2000:
        days += 1; raw, paid, gained, _ = day_of(rank, hours, eco, solo, team); xp += gained; wallet += paid; earned += paid
        up = RANKS[RANKS.index(rank) + 1]
        if xp >= NEXT[rank] and wallet >= eco['fees'][up]:
            wallet -= eco['fees'][up]; xp -= NEXT[rank]; rows.append((up, days, earned)); rank = up
    return rows

def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out = sys.argv[sys.argv.index('--out') + 1] if '--out' in sys.argv else None
    if out in args: args.remove(out)
    src = args[0] if args else DEFAULT
    eco, solo, team = load(src)
    L = [f'# Баланс доски гильдии', '', f'Файл: `{src}`. Бюджет сервера {money(eco["budget"])} в день, сверх потолка игрока {eco["over"]}%, сверх бюджета {min(eco["over"], eco["over_budget"])}%, с ранга C до {eco["daily"]} заданий в день.', '']
    L += ['## Ранги', '', '| Ранг | Личное, в среднем | Командное, на игрока | Потолок в день | Монет за 1 ч | Монет за 3 ч | Опыт за 3 ч | Дней до ранга (3 ч) | Взнос за ранг |', '|---|---|---|---|---|---|---|---|---|']
    flags = []; prev_hour = 0
    for r in RANKS:
        raw1, paid1, _, _ = day_of(r, 1, eco, solo, team); raw3, paid3, xp3, _ = day_of(r, 3, eco, solo, team)
        need = NEXT[r]; days = '—' if not need else (f'{math.ceil(need / xp3)}' if xp3 else '∞')
        up = RANKS[RANKS.index(r) + 1] if r != 'S' else None
        L.append(f'| {r} | {money(solo[r]) if solo[r] else "— (доска C)"} | {money(team[r]) if team[r] else "—"} | {money(eco["caps"][r])} | {money(paid1)} | {money(paid3)} | {xp3} | {days} | {money(eco["fees"][up]) if up else "—"} |')
        if raw3 > eco['caps'][r] * 1.5: flags.append(f'{r}: за 3 ч игрок набирает {money(raw3)} — в 1,5 раза больше потолка, дальше платится {eco["over"]}%.')
        if paid1 < prev_hour: flags.append(f'{r}: за 1 ч платят меньше ({money(paid1)}), чем рангом ниже ({money(prev_hour)}).')
        prev_hour = paid1
    for name, h in PROFILES.items():
        rows = path(h, eco, solo, team)
        L += ['', f'## Путь F→S, {name}', '', '| Ранг | День | Заработано к этому дню |', '|---|---|---|'] + [f'| {r} | {d} | {money(e)} |' for r, d, e in rows]
    fees = sum(eco['fees'][r] for r in RANKS)
    L += ['', f'Взносы F→S вместе: {money(fees)}; экзамен ~{EXAM_MINUTES} мин в модели не учтён.', '']
    L += ['## Сервер за день', '', '| Игроков | Состав F/E/D/C/B/A/S | Выдано без бюджета | Выдано с бюджетом | Сожжено взносами (оценка) |', '|---|---|---|---|---|']
    reach = {'F': 0}; reach.update({r: d for r, d, _ in path(2, eco, solo, team)})
    for players, mix in ((15, (4, 3, 3, 2, 2, 1, 0)), (30, (7, 6, 5, 5, 4, 2, 1)), (60, (14, 12, 10, 10, 8, 4, 2))):
        raw = 0
        for r, n in zip(RANKS, mix): raw += n * day_of(r, 2, eco, solo, team)[1]
        paid = capped(raw, eco['budget'], min(eco['over'], eco['over_budget']))
        # a player pays the next fee once per stay at a rank: fee / days spent there, at 2 h a day
        burned = sum(n * eco['fees'][RANKS[RANKS.index(r) + 1]] / max(1, reach[RANKS[RANKS.index(r) + 1]] - reach[r]) for r, n in zip(RANKS, mix) if r != 'S')
        L.append(f'| {players} | {"/".join(map(str, mix))} | {money(raw)} | {money(paid)} | {money(burned)} |')
        if raw > eco['budget']: flags.append(f'{players} игроков по 2 ч упираются в бюджет сервера: {money(raw)} при бюджете {money(eco["budget"])}')
    L += ['', '## На что обратить внимание', ''] + ([f'- {f}' for f in flags] or ['- Перекосов не найдено.'])
    text = '\n'.join(L) + '\n'
    if out: pathlib.Path(out).write_text(text, encoding='utf-8')
    sys.stdout.buffer.write(text.encode('utf-8'))

if __name__ == '__main__':
    main()
