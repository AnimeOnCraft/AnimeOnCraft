package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

final class TerritoryCapture {
    private record Hold(String war, String attacker, int seconds) {}
    private static final Map<String,Hold> holds=new HashMap<>();
    private TerritoryCapture() {}
    static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();if(!server.isDedicatedServer()||server.getTickCount()%20!=0)return;
        sample(server,server.getPlayerList().getPlayers());
    }
    static void sample(net.minecraft.server.MinecraftServer server,java.util.List<ServerPlayer> players){
        for(var entry:ClanSpawns.read(server).entrySet()){
            String territory=entry.getKey();if(territory.equals("COMMON"))continue;
            var point=entry.getValue().offset(48,0,0);
            var nearby=players.stream().filter(player->player.serverLevel()==server.overworld()
                &&!player.isSpectator()&&player.isAlive()&&Math.abs(player.getX()-point.getX())<=12&&Math.abs(player.getZ()-point.getZ())<=12).toList();
            if(nearby.isEmpty()){holds.remove(territory);continue;}
            int y=server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,point.getX(),point.getZ());
            nearby=nearby.stream().filter(player->Math.abs(player.getY()-y)<=8).toList();
            server.overworld().sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,point.getX()+0.5,y+1,point.getZ()+0.5,8,0.5,1.5,0.5,0.02);
            String defender=ClanControlSync.owner(territory),attacker=null;
            boolean contested=false;
            for(ServerPlayer player:nearby){
                var identity=GameIdentity.PLAYERS.get(player.getUUID());
                if(identity==null||identity.clan()==null||identity.clan().equals(defender)){contested=true;break;}
                if(attacker!=null&&!attacker.equals(identity.clan())){contested=true;break;}
                attacker=identity.clan();
            }
            var war=contested?null:WarWindows.active(defender,attacker);
            if(war==null){holds.remove(territory);continue;}
            var previous=holds.get(territory);
            int seconds=previous!=null&&previous.war.equals(war.id())&&previous.attacker.equals(attacker)?previous.seconds+1:1;
            if(seconds>300)continue;
            holds.put(territory,new Hold(war.id(),attacker,seconds));
            if(seconds==1||seconds%30==0)for(var player:nearby)player.displayClientMessage(Component.literal("Захват "+territory+": "+seconds+" / 300 секунд. Присутствие противника сбросит удержание."),true);
            if(seconds==300){
                try{
                    var settings=ServerSettings.from(System.getenv());var body=new JsonObject();
                    body.addProperty("server_id",settings.serverId().toString());
                    body.addProperty("event_id",UUID.nameUUIDFromBytes((war.id()+":"+territory).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                    body.addProperty("completed_at",java.time.Instant.now().toString());
                    body.addProperty("expires_at",war.ends().plusSeconds(600).toString());
                    body.addProperty("war_window_id",war.id());body.addProperty("territory_code",territory);
                    body.addProperty("attacker_clan_code",attacker);body.addProperty("defender_clan_code",defender);
                    GameplayOutbox.store(server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-gameplay-outbox"),body);
                    for(var player:nearby)player.sendSystemMessage(Component.literal("Точка удержана. Ожидаем подтверждение захвата сервером."));
                }catch(java.io.IOException|RuntimeException failure){holds.remove(territory);System.getLogger("AnimeOnCraft").log(System.Logger.Level.ERROR,"Could not persist territory capture");}
            }
        }
    }
}
