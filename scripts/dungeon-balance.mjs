// Rift Gate economy model: reads dungeons.json and guild-quests.json (shipped or a server override) and prints, as Markdown,
// what a player spends on entries, earns from the guild's DUNGEON quests and gets in essence, and the server's daily coin flow.
//   node scripts/dungeon-balance.mjs [dungeons.json] [guild-quests.json] [--out report.md]
// A sanity check for the numbers in the files, not a forecast. Assumptions are below and easy to change.
import fs from 'node:fs';import path from 'node:path';
const root=path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/,'$1')),'..');
const args=process.argv.slice(2),outIndex=args.indexOf('--out'),out=outIndex>=0?args[outIndex+1]:null;
const files=args.filter((a,i)=>!a.startsWith('--')&&i!==outIndex+1);
const dungeonsFile=files[0]??path.join(root,'minecraft/integration/neoforge/src/main/resources/animeoncraft/dungeons.json');
const guildFile=files[1]??path.join(root,'minecraft/integration/neoforge/src/main/resources/animeoncraft/guild-quests.json');
const D=JSON.parse(fs.readFileSync(dungeonsFile,'utf8')),G=JSON.parse(fs.readFileSync(guildFile,'utf8'));
// Assumptions: run length is the dungeon's par times a factor per difficulty (bigger teams, more mobs); a run is won this often;
// a casual player runs dungeons 1 h a day, an active one 2 h; a team splits its wins evenly.
const LENGTH={NORMAL:1,HARD:1.2,NIGHTMARE:1.5,MYTHIC:1.8},WIN={NORMAL:0.95,HARD:0.85,NIGHTMARE:0.7,MYTHIC:0.5},PROFILES={'1 ч/день':60,'2 ч/день':120};
const RANKS=['F','E','D','C','B','A','S'];
const money=c=>{c=Math.round(c);if(c<=0)return '0 мед.';const p=[];for(const [v,n] of [[100,'зол.'],[10,'жел.'],[1,'мед.']])if(c>=v){p.push(`${Math.floor(c/v)} ${n}`);c%=v;}return p.join(' ');};
const open=D.dungeons.filter(d=>d.open);const par=open.length?open.reduce((a,d)=>a+d.parSeconds,0)/open.length:600;
const quests=G.quests.filter(q=>q.kind==='DUNGEON');const mid=q=>(q.reward[0]+q.reward[1])/2;
const questPay=f=>{const order=D.difficulties.map(x=>x.code);const fits=quests.filter(q=>order.indexOf(q.target.split('/')[1].toUpperCase())<=order.indexOf(f));return fits.length?Math.max(...fits.map(mid)):0;};
const L=['# Баланс подземелий (Врата Разлома)','',`Каталог: \`${dungeonsFile}\`, задания: \`${guildFile}\`. Открыто подземелий: ${open.length}, средний пар ${Math.round(par/60)} мин, сундуков в день ${D.dailyChests}.`,''];
L.push('## Сложности','','| Сложность | Вход | Команда | Забег, мин | Победа | Вход в час | Эссенция за сундук | Эссенция в день (3 сундука) | Задание гильдии DUNGEON | Кристаллы за первую зачистку |','|---|---|---|---|---|---|---|---|---|---|');
const flags=[];const rows={};
for(const f of D.difficulties){
  const minutes=par/60*LENGTH[f.code]+3,runsHour=60/minutes,win=WIN[f.code],ess=(f.essence[0]+f.essence[1])/2;
  const feeHour=f.fee*runsHour,day=Math.min(D.dailyChests,runsHour*win*2)*ess,quest=questPay(f.code);
  rows[f.code]={minutes,runsHour,win,ess,feeHour,quest};
  L.push(`| ${f.name} | ${money(f.fee)} | ${f.minPlayers}–${D.maxPlayers} | ${minutes.toFixed(0)} | ${Math.round(win*100)}% | ${money(feeHour)} | ${ess} | ${day.toFixed(1)} | ${quest?money(quest):'—'} | ${f.crystals} ◆ |`);
  if(quest&&f.fee>quest)flags.push(`${f.name}: вход ${money(f.fee)} дороже задания гильдии ${money(quest)} — задание не окупает даже один забег.`);
  if(quest&&quest>8*f.fee&&f.fee>0)flags.push(`${f.name}: задание гильдии ${money(quest)} больше 8 входов — подземелья становятся источником монет.`);
}
L.push('',`Кристаллы за первые зачистки: ${D.difficulties.reduce((a,f)=>a+f.crystals,0)} ◆ на подземелье, ${D.difficulties.reduce((a,f)=>a+f.crystals,0)*D.dungeons.length} ◆ за все ${D.dungeons.length}.`,'');
L.push('## Игрок за день','','Задание гильдии DUNGEON засчитывается одно в день (личное) плюс одно командное в наборе на 2 часа; монеты платит гильдия со своими потолками.','','| Профиль | Сложность | Забегов | Сожжено входом | Монеты из заданий | Итог | Сундуков |','|---|---|---|---|---|---|---|');
for(const [name,minutes] of Object.entries(PROFILES))for(const f of D.difficulties){
  const r=rows[f.code],runs=Math.floor(minutes/r.minutes),burned=runs*f.fee,earned=runs>0&&r.quest?r.quest*(f.minPlayers>1?Math.ceil(minutes/120):1):0;
  L.push(`| ${name} | ${f.name} | ${runs} | ${money(burned)} | ${money(earned)} | ${earned>=burned?'+':'−'}${money(Math.abs(earned-burned))} | ${Math.min(D.dailyChests,Math.floor(runs*r.win))} |`);
}
L.push('','## Сервер за день','','Доля игроков, которые ходят в подземелья, и их сложность по рангам гильдии (F–C: Обычная/Сложная, B: Кошмар, A–S: Мифическая), 1 ч в день.','','| Игроков | Ходят в подземелья | Сожжено входом | Выплачено заданиями (оценка) | Чистый сток монет |','|---|---|---|---|---|');
const pick=r=>RANKS.indexOf(r)>=5?'MYTHIC':RANKS.indexOf(r)>=4?'NIGHTMARE':RANKS.indexOf(r)>=2?'HARD':'NORMAL';
for(const [players,mix] of [[15,[4,3,3,2,2,1,0]],[30,[7,6,5,5,4,2,1]],[60,[14,12,10,10,8,4,2]]]){
  let burned=0,paid=0,going=0;
  RANKS.forEach((rank,i)=>{const n=Math.round(mix[i]*0.5);going+=n;const f=D.difficulties.find(x=>x.code===pick(rank));const r=rows[f.code];const runs=Math.floor(60/r.minutes);burned+=n*runs*f.fee;paid+=n*(runs>0?r.quest:0);});
  L.push(`| ${players} | ${going} | ${money(burned)} | ${money(paid)} | ${burned>=paid?'':'−'}${money(Math.abs(burned-paid))} |`);
  if(paid>burned*3)flags.push(`${players} игроков: задания DUNGEON выплачивают ${money(paid)} при стоке ${money(burned)} — подземелья больше дают монет, чем сжигают (это нормально, если гильдия держит бюджет).`);
}
L.push('','## На что обратить внимание','',...(flags.length?flags.map(f=>`- ${f}`):['- Перекосов не найдено.']),'');
const text=L.join('\n');if(out){fs.mkdirSync(path.dirname(path.resolve(out)),{recursive:true});fs.writeFileSync(out,text);}process.stdout.write(text);
