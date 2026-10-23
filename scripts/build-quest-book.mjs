import {mkdirSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
const chapters=[
 ['Начало приключения','minecraft:crafting_table',[
  ['Запас древесины','minecraft:oak_log',16,'Собери 16 дубовых брёвен. Для быстрой рубки дерева присядь с топором. На чужой территории рубка запрещена.'],
  ['Своя мастерская','minecraft:crafting_table',1,'Сделай верстак. Место для базы выбирай на территории своего клана.'],
  ['Первая плавка','minecraft:furnace',1,'Сделай печь для первых руд и еды.'],
  ['Железный инструмент','minecraft:iron_pickaxe',1,'Подготовь железную кирку. Предметы для заданий остаются у тебя.']]],
 ['Магия','irons_spellbooks:arcane_essence',[
  ['Основы знаний','minecraft:book',1,'Сделай книгу. Изучай рецепты магических предметов в EMI.'],
  ['Магическая эссенция','irons_spellbooks:arcane_essence',8,'Собери эссенцию для магических рецептов. JER показывает доступную информацию о добыче.'],
  ['Первые чернила','irons_spellbooks:common_ink',1,'Найди или создай обычные чернила для свитков.'],
  ['Место для заклинаний','irons_spellbooks:arcane_anvil',1,'Подготовь магическую наковальню. Союзники твоего клана защищены от дружественного урона.']]],
 ['Механизмы Create','create:cogwheel',[
  ['Андезитовый сплав','create:andesite_alloy',8,'Создай материал для первых механизмов.'],
  ['Передача вращения','create:shaft',16,'Сделай валы. Механизмы не должны пересекать чужие защищённые территории.'],
  ['Шестерни','create:cogwheel',8,'Подготовь шестерни и изучи подсказки Ponder для механизмов.'],
  ['Энергия воды','create:water_wheel',1,'Установи водяное колесо как первый источник вращения.'],
  ['Механический пресс','create:mechanical_press',1,'Построй пресс. Расход материалов проверяй по текущим рецептам EMI.']]],
 ['Исследование мира','minecraft:compass',[
  ['Дальняя дорога','minecraft:bow',1,'Подготовь лук и припасы перед вылазкой.'],
  ['Ориентир','minecraft:compass',1,'Сделай компас. Для карты мира и территорий кланов используй Xaero World Map (M). Цветные границы и подсказки показывают владельца территории.'],
  ['Наблюдатель','minecraft:spyglass',1,'Сделай подзорную трубу для разведки. Новые поселения Towns and Towers ищи в новых чанках.'],
  ['Жемчужина странника','minecraft:ender_pearl',1,'Добудь жемчуг Края. Прежде чем бросать его, учитывай правила территорий.']]],
 ['Сумеречный лес','twilightforest:naga_scale',[
  ['Открыть Сумеречный лес',null,1,'Сделай бассейн воды 2×2, окружи его подходящими цветами или растениями и брось алмаз. Проверяй прогрессию измерения по достижениям; эта отметка лишь памятка о портале.'],
  ['Победить Нагу','twilightforest:naga_scale',1,'Найди лабиринт Наги и получи чешую. Это первый шаг местной последовательности боссов.'],
  ['Башня Лича','twilightforest:lich_trophy',1,'После Наги исследуй башню Лича. Читай достижения: они подсказывают порядок прохождения.'],
  ['Огненный металл','twilightforest:fiery_ingot',4,'Продолжай прохождение Сумеречного леса и собери огненные слитки.'],
  ['Рыцарский металл','twilightforest:knightmetal_ingot',4,'Исследуй подземные крепости и получи рыцарский металл.']]],
];
const root='minecraft/modpack/quest-book',files=[],lang={};
const id=(prefix,n)=>prefix+BigInt(n).toString(16).toUpperCase().padStart(15,'0');
function save(relative,value){const bytes=JSON.stringify(value,null,2)+'\n';mkdirSync(path.dirname(path.join(root,relative)),{recursive:true});writeFileSync(path.join(root,relative),bytes);files.push({path:relative,sha256:createHash('sha256').update(bytes).digest('hex')});}
save('data.snbt',{version:13,title:'AnimeOnCraft — книга приключений',fallback_locale:'ru_ru',default_reward_team:false,default_consume_items:false,default_autoclaim_rewards:'disabled'});
save('chapter_groups.snbt',{chapter_groups:[]});
let number=0;
for(const [index,[title,icon,entries]]of chapters.entries()){
 const chapterId=id('1',index+1);lang['chapter.'+chapterId+'.title']=title;
 const quests=[];let previous;
 for(const [qi,[name,item,count,description]]of entries.entries()){
  const n=++number,qid=id('2',n),task={id:id('3',n),type:item?'item':'checkmark',...(item?{item,count,consume_items:false}:{})};
  const quest={id:qid,x:qi*3,y:0,shape:'square',size:1,can_repeat:false,tasks:[task],dependencies:previous?[previous]:[],rewards:item?[{id:id('4',n),type:'item',item:index===3?'minecraft:torch':'minecraft:bread',count:index===3?8:3}]:[]};
  lang['quest.'+qid+'.title']=name;lang['quest.'+qid+'.quest_desc']=[description];quests.push(quest);previous=qid;
 }
 save('chapters/aoc_'+(index+1)+'.snbt',{id:chapterId,filename:'aoc_'+(index+1),group:'',order_index:index,icon,default_quest_shape:'square',default_repeatable_quest:false,consume_items:false,quests});
}
save('lang/ru_ru.snbt',lang);save('lang/en_us.snbt',lang);
writeFileSync('minecraft/server/quest-assets.json',JSON.stringify({format_version:1,id:'aoc-personal-quests-v1',source_directory:root,chapters:chapters.length,quests:number,progress:'personal',files},null,2)+'\n');
console.log(JSON.stringify({chapters:chapters.length,quests:number,files:files.length}));
