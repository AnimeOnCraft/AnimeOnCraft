package ru.animeoncraft.integration.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 1v1 PvP arena in Sora. Players queue with /arena join; a match lasts up to 3 minutes. A lethal hit ends the match
 * instead of killing (no item loss, no gravestone), leaving the arena or logging out forfeits. Winners gain Elo rating
 * and a few earned crystals (backend caps them per week; one pair earns rewards for at most 3 wins a day).
 * The arena is placed by the owner with /aocarena build (refuses on any non-natural block) or /aocarena set.
 */
final class PvpArena {
    private PvpArena() {}
    private record Spot(ResourceKey<Level> dimension,double x,double y,double z,float yRot,float xRot) {
        static Spot of(ServerPlayer player){return new Spot(player.level().dimension(),player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());}
    }
    private static final class Match {
        final UUID id=UUID.randomUUID(),a,b;final Spot returnA,returnB;final long startTick;boolean fighting;
        Match(ServerPlayer a,ServerPlayer b,long tick){this.a=a.getUUID();this.b=b.getUUID();returnA=Spot.of(a);returnB=Spot.of(b);startTick=tick;}
        boolean has(UUID id){return a.equals(id)||b.equals(id);}
        UUID other(UUID id){return a.equals(id)?b:a;}
    }
    private static final ArrayDeque<UUID> QUEUE=new ArrayDeque<>();
    private static Match current;

    /** Used by TerritoryProtection: the two fighters of a running match may hurt each other anywhere in Sora. */
    static boolean fighting(Entity attacker,Entity target){
        var match=current;
        return match!=null&&match.fighting&&attacker!=target&&match.has(attacker.getUUID())&&match.has(target.getUUID());
    }
    static boolean inMatch(UUID player){var match=current;return match!=null&&match.has(player);}

    private static ServerLevel arenaLevel(MinecraftServer server,ArenaStore store){
        if(!store.placed)return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(store.dimension)));
    }
    private static void announce(MinecraftServer server,String text){
        var line=Component.literal("[Арена] ").withStyle(ChatFormatting.GOLD).append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        for(var player:server.getPlayerList().getPlayers())player.sendSystemMessage(line);
    }

    static void tick(ServerTickEvent.Post event){
        var server=event.getServer();long tick=server.getTickCount();
        if(tick%10!=0)return;
        var store=ArenaStore.get(server);var level=arenaLevel(server,store);
        if(current==null){
            QUEUE.removeIf(id->server.getPlayerList().getPlayer(id)==null);
            if(level!=null&&QUEUE.size()>=2)start(server,store,level,server.getPlayerList().getPlayer(QUEUE.pollFirst()),server.getPlayerList().getPlayer(QUEUE.pollFirst()),tick);
            return;
        }
        var match=current;var a=server.getPlayerList().getPlayer(match.a);var b=server.getPlayerList().getPlayer(match.b);
        if(a==null||b==null){finish(server,store,match,a==null?b:a,a==null?match.a:match.b,"вышел из игры");return;}
        if(level==null){draw(server,store,match,"арена недоступна");return;}
        long elapsed=(tick-match.startTick)/20;
        if(!match.fighting){
            long left=ArenaRules.COUNTDOWN_SECONDS-elapsed;
            if(left<=0){match.fighting=true;for(var p:new ServerPlayer[]{a,b})p.displayClientMessage(Component.literal("Бой!").withStyle(ChatFormatting.RED,ChatFormatting.BOLD),true);}
            else for(var p:new ServerPlayer[]{a,b})p.displayClientMessage(Component.literal("Бой начнётся через "+left+"…").withStyle(ChatFormatting.YELLOW),true);
            return;
        }
        for(var p:new ServerPlayer[]{a,b}){
            boolean outside=p.level()!=level||!ArenaRules.inside(p.getX()-(store.x+0.5),p.getZ()-(store.z+0.5))||p.getY()<store.y-3;
            if(outside){finish(server,store,match,p==a?b:a,p.getUUID(),"покинул арену");return;}
        }
        long left=ArenaRules.COUNTDOWN_SECONDS+ArenaRules.MATCH_SECONDS-elapsed;
        if(left<=0){draw(server,store,match,"время вышло");return;}
        if(tick%20==0)for(var p:new ServerPlayer[]{a,b})p.displayClientMessage(Component.literal("Арена · осталось "+left/60+":"+String.format("%02d",left%60)),true);
    }
    private static void start(MinecraftServer server,ArenaStore store,ServerLevel level,ServerPlayer a,ServerPlayer b,long tick){
        if(a==null||b==null||a==b){if(a!=null)QUEUE.addFirst(a.getUUID());if(b!=null&&b!=a)QUEUE.addFirst(b.getUUID());return;}
        // Someone queued and then went into the Rift or died: drop them, keep the other first in line.
        boolean aBusy=a.level().dimension().equals(DungeonServer.RIFT)||!a.isAlive(),bBusy=b.level().dimension().equals(DungeonServer.RIFT)||!b.isAlive();
        if(aBusy||bBusy){if(!bBusy)QUEUE.addFirst(b.getUUID());if(!aBusy)QUEUE.addFirst(a.getUUID());for(var p:new ServerPlayer[]{a,b})if(p==a?aBusy:bBusy)p.sendSystemMessage(Component.literal("Ты выбыл из очереди арены."));return;}
        var match=new Match(a,b,tick);current=match;
        place(a,level,store,-ArenaRules.PAD_OFFSET,-90f);place(b,level,store,ArenaRules.PAD_OFFSET,90f);
        for(var p:new ServerPlayer[]{a,b}){p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);p.clearFire();}
        var ra=store.fighter(a.getUUID(),a.getGameProfile().getName()).rating;var rb=store.fighter(b.getUUID(),b.getGameProfile().getName()).rating;
        announce(server,a.getGameProfile().getName()+" ("+ra+") против "+b.getGameProfile().getName()+" ("+rb+"). Смотреть можно у стен арены.");
    }
    private static void place(ServerPlayer player,ServerLevel level,ArenaStore store,int dx,float yaw){
        player.stopRiding();if(player.isSleeping())player.stopSleepInBed(true,true);
        player.teleportTo(level,store.x+dx+0.5,store.y+1,store.z+0.5,Set.of(),yaw,0f);
        player.fallDistance=0;
    }
    private static void sendBack(MinecraftServer server,ServerPlayer player,Spot spot){
        if(player==null)return;
        var level=server.getLevel(spot.dimension());if(level==null)level=server.overworld();
        player.setHealth(player.getMaxHealth());player.clearFire();player.fallDistance=0;
        player.teleportTo(level,spot.x(),spot.y(),spot.z(),Set.of(),spot.yRot(),spot.xRot());
    }
    private static void endMatch(MinecraftServer server,Match match){
        current=null;
        sendBack(server,server.getPlayerList().getPlayer(match.a),match.returnA);
        sendBack(server,server.getPlayerList().getPlayer(match.b),match.returnB);
    }
    private static void draw(MinecraftServer server,ArenaStore store,Match match,String why){
        endMatch(server,match);
        store.fighter(match.a,null).draws++;store.fighter(match.b,null).draws++;
        announce(server,"Ничья: "+store.fighter(match.a,null).name+" и "+store.fighter(match.b,null).name+" ("+why+").");
    }
    /** {@code winner} may be null when the winner is offline; the result still counts. */
    private static void finish(MinecraftServer server,ArenaStore store,Match match,ServerPlayer winner,UUID loserId,String why){
        if(current!=match)return;
        UUID winnerId=match.other(loserId);
        endMatch(server,match);
        if(!match.fighting){announce(server,"Бой отменён до начала ("+why+").");return;}
        var w=store.fighter(winnerId,winner==null?null:winner.getGameProfile().getName());var l=store.fighter(loserId,null);
        int delta=ArenaRules.ratingDelta(w.rating,l.rating);
        w.rating+=delta;w.wins++;l.rating=ArenaRules.afterLoss(l.rating,delta);l.losses++;
        announce(server,w.name+" победил "+l.name+" ("+why+"). Рейтинг "+w.name+": "+w.rating+" (+"+delta+").");
        String key=ArenaRules.day(System.currentTimeMillis())+"|"+ArenaRules.pair(winnerId,loserId);
        int given=store.rewarded.getOrDefault(key,0);
        if(winner!=null&&given<ArenaRules.REWARDED_WINS_PER_PAIR_PER_DAY){
            store.rewarded.put(key,given+1);
            PremiumCrystals.arena(winner,match.id);
        }else if(winner!=null)winner.sendSystemMessage(Component.literal("Награда за победы над этим игроком сегодня уже получена 3 раза. Рейтинг засчитан.").withStyle(ChatFormatting.GRAY));
        store.setDirty();
    }
    /** A lethal blow inside a match ends it instead of killing: no drops, no gravestone, no respawn. */
    static void death(LivingDeathEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player)||!inMatch(player.getUUID()))return;
        event.setCanceled(true);player.setHealth(player.getMaxHealth());
        var server=player.getServer();var match=current;
        if(server!=null&&match!=null)finish(server,ArenaStore.get(server),match,server.getPlayerList().getPlayer(match.other(player.getUUID())),player.getUUID(),match.fighting?"нокаут":"до начала боя");
    }
    static void stopped(ServerStoppedEvent event){QUEUE.clear();current=null;}

    private static int join(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player=source.getPlayerOrException();var store=ArenaStore.get(source.getServer());
        if(!store.placed){source.sendFailure(Component.literal("Арена ещё не открыта."));return 0;}
        if(inMatch(player.getUUID())){source.sendFailure(Component.literal("Ты уже на арене."));return 0;}
        if(QUEUE.contains(player.getUUID())){source.sendFailure(Component.literal("Ты уже в очереди: "+(new ArrayList<>(QUEUE).indexOf(player.getUUID())+1)+"-й."));return 0;}
        if(player.isSpectator()||player.isCreative()){source.sendFailure(Component.literal("Нужен режим выживания."));return 0;}
        if(player.level().dimension().equals(DungeonServer.RIFT)){source.sendFailure(Component.literal("Из Разлома на арену нельзя."));return 0;}
        QUEUE.addLast(player.getUUID());
        source.sendSuccess(()->Component.literal("Ты в очереди арены ("+QUEUE.size()+"-й). Бой начнётся, когда найдётся соперник. Выйти: /arena leave"),false);
        if(QUEUE.size()==1)announce(source.getServer(),player.getGameProfile().getName()+" ищет соперника: /arena join");
        return 1;
    }
    private static int top(CommandSourceStack source){
        var rows=new ArrayList<>(ArenaStore.get(source.getServer()).fighters.values());
        rows.removeIf(row->row.wins+row.losses+row.draws==0);
        rows.sort(Comparator.comparingInt((ArenaStore.Fighter row)->row.rating).reversed());
        if(rows.isEmpty()){source.sendSuccess(()->Component.literal("Боёв на арене ещё не было."),false);return 0;}
        var text=new StringBuilder("Рейтинг арены:");
        for(int i=0;i<Math.min(10,rows.size());i++){var row=rows.get(i);text.append("\n").append(i+1).append(". ").append(row.name).append(" — ").append(row.rating).append(" (").append(row.wins).append("/").append(row.losses).append(")");}
        source.sendSuccess(()->Component.literal(text.toString()),false);return rows.size();
    }
    private static int stats(CommandSourceStack source,String name){
        var store=ArenaStore.get(source.getServer());
        for(var row:store.fighters.values())if(row.name.equalsIgnoreCase(name)){
            source.sendSuccess(()->Component.literal(row.name+": рейтинг "+row.rating+", побед "+row.wins+", поражений "+row.losses+", ничьих "+row.draws),false);return 1;
        }
        source.sendFailure(Component.literal("Игрок ещё не сражался на арене."));return 0;
    }
    /** Only air, plants, snow and natural ground may be replaced; anything else (including block entities) refuses. */
    static boolean natural(BlockState state){
        if(state.isAir()||state.hasBlockEntity())return !state.hasBlockEntity();
        return state.canBeReplaced()||state.is(BlockTags.DIRT)||state.is(BlockTags.SAND)||state.is(BlockTags.BASE_STONE_OVERWORLD)
            ||state.is(Tags.Blocks.GRAVELS)||state.is(BlockTags.LEAVES)||state.is(BlockTags.FLOWERS)||state.is(BlockTags.SNOW)||state.is(Blocks.CLAY);
    }
    private static int build(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player=source.getPlayerOrException();var level=player.serverLevel();var store=ArenaStore.get(source.getServer());
        if(store.placed){source.sendFailure(Component.literal("Арена уже есть: "+store.x+" "+store.y+" "+store.z+". Сначала /aocarena forget."));return 0;}
        int cx=player.getBlockX(),cy=player.getBlockY()-1,cz=player.getBlockZ();
        int r=ArenaRules.WALL_RADIUS;
        for(int dx=-r-1;dx<=r+1;dx++)for(int dz=-r-1;dz<=r+1;dz++){
            if(ArenaRules.cell(dx,dz)==ArenaRules.Cell.NONE)continue;
            for(int dy=-2;dy<=ArenaRules.CLEAR_HEIGHT;dy++){
                var pos=new BlockPos(cx+dx,cy+dy,cz+dz);
                if(!natural(level.getBlockState(pos))){source.sendFailure(Component.literal("Место занято постройкой: "+pos.toShortString()+" ("+level.getBlockState(pos).getBlock().getName().getString()+"). Ничего не изменено."));return 0;}
            }
        }
        for(int dx=-r-1;dx<=r+1;dx++)for(int dz=-r-1;dz<=r+1;dz++){
            var cell=ArenaRules.cell(dx,dz);if(cell==ArenaRules.Cell.NONE)continue;
            for(int dy=-2;dy<0;dy++)level.setBlock(new BlockPos(cx+dx,cy+dy,cz+dz),Blocks.STONE.defaultBlockState(),3);
            var floor=switch(cell){case PAD->Blocks.CHISELED_STONE_BRICKS;case WALL->Blocks.STONE_BRICKS;default->(dx*dx+dz*dz)%37==0?Blocks.SEA_LANTERN:Blocks.POLISHED_ANDESITE;};
            level.setBlock(new BlockPos(cx+dx,cy,cz+dz),floor.defaultBlockState(),3);
            for(int dy=1;dy<=ArenaRules.CLEAR_HEIGHT;dy++){
                boolean wall=cell==ArenaRules.Cell.WALL&&dy<=ArenaRules.WALL_HEIGHT;
                var state=wall?Blocks.STONE_BRICKS.defaultBlockState():cell==ArenaRules.Cell.WALL&&dy==ArenaRules.WALL_HEIGHT+1&&Math.floorMod(dx*7+dz*13,9)==0?Blocks.LANTERN.defaultBlockState():Blocks.AIR.defaultBlockState();
                level.setBlock(new BlockPos(cx+dx,cy+dy,cz+dz),state,3);
            }
        }
        store.placed=true;store.dimension=level.dimension().location().toString();store.x=cx;store.y=cy;store.z=cz;store.setDirty();
        source.sendSuccess(()->Component.literal("Арена построена, центр пола "+cx+" "+cy+" "+cz+". Игроки: /arena join"),true);
        return 1;
    }
    static void commands(RegisterCommandsEvent event){
        var dispatcher=event.getDispatcher();
        dispatcher.register(Commands.literal("arena")
            .then(Commands.literal("join").executes(context->join(context.getSource())))
            .then(Commands.literal("leave").executes(context->{var player=context.getSource().getPlayerOrException();
                if(QUEUE.remove(player.getUUID())){context.getSource().sendSuccess(()->Component.literal("Ты вышел из очереди арены."),false);return 1;}
                if(inMatch(player.getUUID())){var match=current;finish(context.getSource().getServer(),ArenaStore.get(context.getSource().getServer()),match,context.getSource().getServer().getPlayerList().getPlayer(match.other(player.getUUID())),player.getUUID(),"сдался");return 1;}
                context.getSource().sendFailure(Component.literal("Ты не в очереди."));return 0;}))
            .then(Commands.literal("top").executes(context->top(context.getSource())))
            .then(Commands.literal("stats").executes(context->stats(context.getSource(),context.getSource().getPlayerOrException().getGameProfile().getName()))
                .then(Commands.argument("player",StringArgumentType.word()).executes(context->stats(context.getSource(),StringArgumentType.getString(context,"player"))))));
        dispatcher.register(Commands.literal("aocarena").requires(source->source.hasPermission(4))
            .then(Commands.literal("build").executes(context->build(context.getSource())))
            .then(Commands.literal("set").executes(context->{var player=context.getSource().getPlayerOrException();var store=ArenaStore.get(context.getSource().getServer());
                store.placed=true;store.dimension=player.level().dimension().location().toString();store.x=player.getBlockX();store.y=player.getBlockY()-1;store.z=player.getBlockZ();store.setDirty();
                context.getSource().sendSuccess(()->Component.literal("Центр арены задан без постройки: "+store.x+" "+store.y+" "+store.z),true);return 1;}))
            .then(Commands.literal("forget").executes(context->{if(current!=null){context.getSource().sendFailure(Component.literal("Идёт бой."));return 0;}var store=ArenaStore.get(context.getSource().getServer());store.placed=false;store.setDirty();
                context.getSource().sendSuccess(()->Component.literal("Арена закрыта (блоки не тронуты)."),true);return 1;}))
            .then(Commands.literal("info").executes(context->{var store=ArenaStore.get(context.getSource().getServer());
                context.getSource().sendSuccess(()->Component.literal(store.placed?"Арена: "+store.dimension+" "+store.x+" "+store.y+" "+store.z+", очередь "+QUEUE.size()+(current!=null?", идёт бой":""):"Арена не задана."),false);return 1;})));
    }
}
