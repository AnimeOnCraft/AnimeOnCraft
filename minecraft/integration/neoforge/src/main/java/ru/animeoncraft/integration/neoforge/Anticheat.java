package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ru.animeoncraft.integration.neoforge.AnticheatRules.Check;
import ru.animeoncraft.integration.neoforge.AnticheatRules.Level;

/**
 * Server-side suspicion system. It never kicks or bans: it scores players, writes a log in the world folder,
 * tells online operators (ALERT and above) and sends a report to the backend (phone panel + optional Telegram).
 * Creative/spectator, flight abilities, elytra, riding, levitation, teleports and recent damage are exempt.
 */
final class Anticheat {
    private Anticheat() {}
    private static final class Track {
        String dimension; double x,y,z; boolean ready;
        double windowDistance; int windowTicks,overWindows;
        int airTicks; double airStartY;
        long exemptUntilTick;
        final ArrayDeque<Long> attacks=new ArrayDeque<>();
        BlockPos breakPos; long breakStartTick=-1;
        long minedSince; int stone,valuable; long lastXray;
        double score; long scoreAt; Level reported; String name="";
        final Map<Check,Long> lastReport=new EnumMap<>(Check.class);
        final Map<Check,Integer> counts=new EnumMap<>(Check.class);
    }
    private static final Map<UUID,Track> TRACKS=new HashMap<>();
    private static final Set<UUID> MUTED=new HashSet<>();
    private static boolean backendWarned;

    private static Track track(ServerPlayer player){var track=TRACKS.computeIfAbsent(player.getUUID(),ignored->new Track());track.name=player.getGameProfile().getName();return track;}
    private static long tick(ServerPlayer player){return player.getServer()==null?0:player.getServer().getTickCount();}
    private static void exempt(ServerPlayer player,int ticks){var track=track(player);track.exemptUntilTick=Math.max(track.exemptUntilTick,tick(player)+ticks);track.ready=false;}

    static void tick(ServerTickEvent.Post event){
        var server=event.getServer();long now=server.getTickCount();
        for(var player:server.getPlayerList().getPlayers())movement(server,player,now);
    }
    private static void movement(MinecraftServer server,ServerPlayer player,long now){
        var track=track(player);String dimension=player.level().dimension().location().toString();
        double x=player.getX(),y=player.getY(),z=player.getZ();
        if(!track.ready||!dimension.equals(track.dimension)){reset(track,dimension,x,y,z);return;}
        double dx=x-track.x,dz=z-track.z,dy=y-track.y,step=Math.sqrt(dx*dx+dz*dz);
        track.x=x;track.y=y;track.z=z;
        // A jump of more than 8 blocks in one tick is a teleport (commands, pearls, spells, rift gates, dungeons).
        if(step>8||Math.abs(dy)>8){track.exemptUntilTick=now+40;clearMovement(track);return;}
        boolean exempt=now<track.exemptUntilTick||player.isSpectator()||player.isCreative()||player.mayFly()
            ||player.isPassenger()||player.isFallFlying()||player.isSleeping()||player.isDeadOrDying()||player.isAutoSpinAttack()
            ||player.hasEffect(MobEffects.LEVITATION)||player.hasEffect(MobEffects.SLOW_FALLING);
        if(exempt){clearMovement(track);return;}
        track.windowDistance+=step;track.windowTicks++;
        if(track.windowTicks>=20){
            double allowed=AnticheatRules.allowedSpeed(player.getAttributeValue(Attributes.MOVEMENT_SPEED),
                player.level().getBlockState(player.getOnPos()).is(BlockTags.ICE),player.hasEffect(MobEffects.DOLPHINS_GRACE));
            if(track.windowDistance>allowed){
                if(++track.overWindows>=AnticheatRules.SPEED_WINDOWS){
                    flag(server,player,Check.SPEED,String.format(Locale.ROOT,"Скорость %.1f бл/с %d с подряд (допустимо до %.1f)",track.windowDistance,AnticheatRules.SPEED_WINDOWS,allowed));
                    track.overWindows=0;
                }
            }else track.overWindows=0;
            track.windowDistance=0;track.windowTicks=0;
        }
        boolean supported=player.onGround()||player.isInWater()||player.isInLava()||player.onClimbable()||player.isInPowderSnow;
        if(supported){track.airTicks=0;return;}
        if(track.airTicks==0)track.airStartY=y;
        if(++track.airTicks>=AnticheatRules.FLY_TICKS){
            if(y>=track.airStartY-1.0)flag(server,player,Check.FLY,String.format(Locale.ROOT,"В воздухе %d с без снижения (высота %.1f → %.1f)",AnticheatRules.FLY_TICKS/20,track.airStartY,y));
            track.airTicks=0;
        }
    }
    private static void reset(Track track,String dimension,double x,double y,double z){track.dimension=dimension;track.x=x;track.y=y;track.z=z;track.ready=true;clearMovement(track);}
    private static void clearMovement(Track track){track.windowDistance=0;track.windowTicks=0;track.overWindows=0;track.airTicks=0;}

    static void attack(AttackEntityEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player)||player.isCreative()||player.isSpectator())return;
        var server=player.getServer();if(server==null)return;
        var track=track(player);long now=System.currentTimeMillis();
        double distance=Math.sqrt(event.getTarget().getBoundingBox().distanceToSqr(player.getEyePosition()));
        double range=player.entityInteractionRange();
        if(AnticheatRules.reach(distance,range))flag(server,player,Check.REACH,String.format(Locale.ROOT,"Удар с %.2f бл. (допустимо %.2f) по %s",distance,range,event.getTarget().getType().toShortString()));
        track.attacks.addLast(now);
        while(!track.attacks.isEmpty()&&track.attacks.peekFirst()<now-1000)track.attacks.pollFirst();
        if(track.attacks.size()>AnticheatRules.MAX_ATTACKS_PER_SECOND){
            flag(server,player,Check.CLICK_RATE,track.attacks.size()+" ударов за секунду");track.attacks.clear();
        }
    }
    static void startBreak(PlayerInteractEvent.LeftClickBlock event){
        if(!(event.getEntity() instanceof ServerPlayer player)||event.getAction()!=PlayerInteractEvent.LeftClickBlock.Action.START)return;
        var track=track(player);track.breakPos=event.getPos().immutable();track.breakStartTick=tick(player);
    }
    static void broken(BlockEvent.BreakEvent event){
        if(!(event.getPlayer() instanceof ServerPlayer player)||player.isCreative()||player.isSpectator())return;
        var server=player.getServer();if(server==null)return;
        var track=track(player);var state=event.getState();var pos=event.getPos();
        if(pos.equals(track.breakPos)&&track.breakStartTick>=0){
            int expected=AnticheatRules.expectedBreakTicks(state.getDestroyProgress(player,player.level(),pos));
            long actual=tick(player)-track.breakStartTick;
            if(AnticheatRules.fastBreak(expected,actual))flag(server,player,Check.FAST_BREAK,"Блок "+state.getBlock().getName().getString()+" сломан за "+actual+" тиков (честно не меньше "+expected+")");
        }
        track.breakPos=null;track.breakStartTick=-1;
        long now=System.currentTimeMillis();
        if(now-track.minedSince>AnticheatRules.XRAY_WINDOW_MS){track.minedSince=now;track.stone=0;track.valuable=0;}
        if(state.is(BlockTags.BASE_STONE_OVERWORLD)||state.is(BlockTags.BASE_STONE_NETHER))track.stone++;
        else if(state.is(Tags.Blocks.ORES_DIAMOND)||state.is(Tags.Blocks.ORES_EMERALD)||state.is(Blocks.ANCIENT_DEBRIS)){
            track.valuable++;
            if(AnticheatRules.xray(track.stone,track.valuable)&&now-track.lastXray>=AnticheatRules.XRAY_REPEAT_MS){
                track.lastXray=now;
                flag(server,player,Check.XRAY,"За 30 мин: "+track.valuable+" ценных руд на "+track.stone+" камня ("+AnticheatRules.percent(track.stone,track.valuable)+"). Проверь, не в шахте ли он.");
            }
        }
    }
    static void damaged(LivingDamageEvent.Post event){if(event.getEntity() instanceof ServerPlayer player)exempt(player,30);}
    static void teleported(EntityTeleportEvent event){if(event.getEntity() instanceof ServerPlayer player)exempt(player,40);}
    static void respawn(PlayerEvent.PlayerRespawnEvent event){if(event.getEntity() instanceof ServerPlayer player)exempt(player,60);}
    static void dimension(PlayerEvent.PlayerChangedDimensionEvent event){if(event.getEntity() instanceof ServerPlayer player)exempt(player,60);}
    static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player)exempt(player,100);}
    static void logout(PlayerEvent.PlayerLoggedOutEvent event){var track=TRACKS.get(event.getEntity().getUUID());if(track!=null){track.ready=false;track.attacks.clear();track.breakPos=null;}}
    static void stopped(ServerStoppedEvent event){TRACKS.clear();MUTED.clear();backendWarned=false;}

    private static void flag(MinecraftServer server,ServerPlayer player,Check check,String detail){
        var track=track(player);long now=System.currentTimeMillis();
        track.score=AnticheatRules.add(track.score,track.scoreAt,now,check);track.scoreAt=now;track.counts.merge(check,1,Integer::sum);
        var level=AnticheatRules.level(track.score);int score=(int)Math.round(track.score);
        log(server,player,check,level,score,detail);
        if(!AnticheatRules.shouldReport(track.reported,level,track.lastReport.get(check),now))return;
        track.lastReport.put(check,now);track.reported=level;
        if(level!=Level.WATCH)notifyOperators(server,player,check,level,score,detail);
        send(server,player,check,level,score,detail);
    }
    static String label(Check check){
        return switch(check){case SPEED->"скорость";case FLY->"полёт";case REACH->"дальность удара";case FAST_BREAK->"быстрое копание";case CLICK_RATE->"частота ударов";case XRAY->"руды (X-ray)";};
    }
    private static String levelLabel(Level level){return switch(level){case WATCH->"наблюдение";case ALERT->"подозрение";case CRITICAL->"СИЛЬНОЕ подозрение";};}
    private static void notifyOperators(MinecraftServer server,ServerPlayer suspect,Check check,Level level,int score,String detail){
        var text=Component.literal("[Античит] ").withStyle(level==Level.CRITICAL?ChatFormatting.RED:ChatFormatting.GOLD)
            .append(Component.literal(suspect.getGameProfile().getName()+" · "+levelLabel(level)+" · "+label(check)+" (счёт "+score+")\n").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(detail).withStyle(ChatFormatting.GRAY));
        for(var operator:server.getPlayerList().getPlayers())if(operator.hasPermissions(2)&&!MUTED.contains(operator.getUUID())&&operator!=suspect)operator.sendSystemMessage(text);
    }
    private static void log(MinecraftServer server,ServerPlayer player,Check check,Level level,int score,String detail){
        try{
            var line=Instant.now()+" "+player.getGameProfile().getName()+" "+player.getUUID()+" "+check+" "+level+" score="+score+" "
                +player.level().dimension().location()+" "+player.blockPosition().toShortString()+" "+detail.replace('\n',' ')+System.lineSeparator();
            Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-anticheat.log"),line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(java.io.IOException|RuntimeException failure){System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING,"Anticheat log write failed");}
    }
    static JsonObject report(UUID serverId,ServerPlayer player,Check check,Level level,int score,String detail,Instant at){
        var body=new JsonObject();
        body.addProperty("server_id",serverId.toString());body.addProperty("report_id",UUID.randomUUID().toString());
        body.addProperty("minecraft_uuid",player.getUUID().toString());body.addProperty("player_name",safeName(player.getGameProfile().getName()));
        body.addProperty("check",check.name());body.addProperty("level",level.name());body.addProperty("score",Math.max(0,Math.min(AnticheatRules.MAX_SCORE,score)));
        body.addProperty("detail",safeDetail(detail));body.addProperty("dimension",player.level().dimension().location().toString());
        var pos=player.blockPosition();body.addProperty("x",pos.getX());body.addProperty("y",pos.getY());body.addProperty("z",pos.getZ());
        body.addProperty("occurred_at",at.toString());
        return body;
    }
    static String safeName(String name){var clean=name==null?"":name.replaceAll("[^A-Za-z0-9_]","_");if(clean.isEmpty())clean="unknown";return clean.length()>16?clean.substring(0,16):clean;}
    static String safeDetail(String detail){var clean=detail.replaceAll("[\\u0000-\\u001f]"," ").trim();if(clean.isEmpty())clean="—";return clean.length()>300?clean.substring(0,300):clean;}
    private static void send(MinecraftServer server,ServerPlayer player,Check check,Level level,int score,String detail){
        try{
            var settings=ServerSettings.from(System.getenv());
            var body=report(settings.serverId(),player,check,level,score,detail,Instant.now());
            HttpJson.post(settings.integrationUrl("/integration/v1/anticheat/reports"),settings.key(),body).exceptionally(failure->{
                if(!backendWarned){backendWarned=true;System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING,"Anticheat report not delivered; it stays in animeoncraft-anticheat.log");}
                return null;
            });
        }catch(RuntimeException notConfigured){/* Local worlds without backend: the log and operator chat still work. */}
    }

    static void commands(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("anticheat").requires(source->source.hasPermission(2))
            .then(Commands.literal("top").executes(context->{
                long now=System.currentTimeMillis();var rows=new ArrayList<Track>();
                for(var track:TRACKS.values())if(AnticheatRules.decay(track.score,track.scoreAt,now)>=1)rows.add(track);
                rows.sort(Comparator.comparingDouble((Track track)->AnticheatRules.decay(track.score,track.scoreAt,now)).reversed());
                if(rows.isEmpty()){context.getSource().sendSuccess(()->Component.literal("Подозрительных игроков нет."),false);return 1;}
                var text=new StringBuilder("Подозрительность (за последнее время):");
                for(var track:rows.subList(0,Math.min(10,rows.size()))){
                    double score=AnticheatRules.decay(track.score,track.scoreAt,now);
                    text.append("\n").append(track.name).append(" — ").append((int)Math.round(score)).append(" · ").append(levelLabel(AnticheatRules.level(score))).append(" · ").append(track.counts);
                }
                context.getSource().sendSuccess(()->Component.literal(text.toString()),false);return rows.size();
            }))
            .then(Commands.literal("player").then(Commands.argument("target",EntityArgument.player()).executes(context->{
                var target=EntityArgument.getPlayer(context,"target");var track=TRACKS.get(target.getUUID());long now=System.currentTimeMillis();
                double score=track==null?0:AnticheatRules.decay(track.score,track.scoreAt,now);
                var text=target.getGameProfile().getName()+": счёт "+(int)Math.round(score)+" ("+levelLabel(AnticheatRules.level(score))+"), срабатывания: "+(track==null?"{}":track.counts.toString());
                context.getSource().sendSuccess(()->Component.literal(text),false);return 1;
            })))
            .then(Commands.literal("notify")
                .then(Commands.literal("on").executes(context->{MUTED.remove(context.getSource().getPlayerOrException().getUUID());context.getSource().sendSuccess(()->Component.literal("Уведомления античита включены."),false);return 1;}))
                .then(Commands.literal("off").executes(context->{MUTED.add(context.getSource().getPlayerOrException().getUUID());context.getSource().sendSuccess(()->Component.literal("Уведомления античита в чате выключены до перезапуска."),false);return 1;}))));
    }
}
