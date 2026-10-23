package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Real ticks separate chunk-ticket changes, as they do during ordinary play. */
final class WorldBossProbe {
    private static MinecraftServer pending;
    private static JsonObject scheduled;
    private static int stage,nextTick,deadline;
    private static FakePlayer one,two;
    private static long expected;
    private WorldBossProbe(){}
    static void schedule(MinecraftServer server){
        if(server.getPort()!=25569||!"127.0.0.1".equals(server.getLocalIp())||!server.getWorldData().getLevelName().startsWith("aoc-territory-probe-"))throw new IllegalStateException("World boss probe requires disposable server");
        pending=server;stage=0;nextTick=server.getTickCount()+10;deadline=server.getTickCount()+1200;
        var control=server.getWorldPath(LevelResource.ROOT).resolve("aoc-world-boss-probe.json");
        if(Files.isRegularFile(control))try{var saved=JsonParser.parseString(Files.readString(control)).getAsJsonObject();scheduled=saved.getAsJsonObject("schedule");expected=saved.get("expected_damage").getAsLong();stage=3;}catch(Exception failure){throw new IllegalStateException("Probe restart state invalid",failure);}
    }
    private static void advance(MinecraftServer server,int delay){stage++;nextTick=server.getTickCount()+delay;}
    private static JsonObject reply(JsonObject row){var reply=new JsonObject();reply.add("item",row==null?com.google.gson.JsonNull.INSTANCE:row);return reply;}
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var server=event.getServer();if(server!=pending||server.getTickCount()<nextTick)return;
        try{
            if(server.getTickCount()>deadline)throw new IllegalStateException("World boss tick probe deadline");
            switch(stage){
                case 0->{
                    WorldBossHealthProbe.verify(server);
                    if(Boolean.getBoolean("animeoncraft.chunkControl")){
                        for(int x=-5;x<=-3;x++)for(int z=6;z<=8;z++)server.overworld().setChunkForced(x,z,true);
                        stage=10;nextTick=server.getTickCount()+12;return;
                    }
                    scheduled=row(Instant.now().plusSeconds(Boolean.getBoolean("animeoncraft.worldBossEarlyStop")?-5:10),Instant.now().plusSeconds(600));WorldBossEncounter.accept(server,reply(scheduled));advance(server,10);
                }
                case 1->{if(WorldBossEncounter.snapshot().get("spawned").getAsBoolean()&&entity(server)!=null)advance(server,Boolean.getBoolean("animeoncraft.worldBossEarlyStop")?2:200);}
                case 2->{
                    one=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"AocBossScam"));two=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"AocBossNya"));
                    GameIdentity.PLAYERS.put(one.getUUID(),identity("SCAM"));GameIdentity.PLAYERS.put(two.getUUID(),identity("NYA"));
                    var boss=entity(server);boss.setNoAi(true);one.setPos(boss.position());two.setPos(boss.position());float before=boss.getHealth();
                    if(!boss.hurt(boss.damageSources().playerAttack(one),50))throw new IllegalStateException("Cannot damage shared boss");
                    expected=Math.round((before-boss.getHealth())*1000d);
                    if(expected<=0||damage(WorldBossEncounter.snapshot(),"SCAM")!=expected)throw new IllegalStateException("Actual damage not counted");
                    scheduled.add("arena",WorldBossEncounter.snapshot().get("arena"));
                    var control=new JsonObject();control.add("schedule",scheduled);control.addProperty("expected_damage",expected);
                    Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("aoc-world-boss-probe.json"),control.toString());
                    pending=null;System.out.println("AOC_WORLD_BOSS_RESTART_REQUEST");
                }
                case 3->{WorldBossEncounter.accept(server,reply(scheduled));advance(server,10);}
                case 4->{
                    var boss=entity(server);if(boss==null)return;
                    two=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"AocBossNya"));two.setPos(boss.position());GameIdentity.PLAYERS.put(two.getUUID(),identity("NYA"));
                    if(damage(WorldBossEncounter.snapshot(),"SCAM")!=expected)throw new IllegalStateException("Restored contribution differs");
                    var grief=new net.neoforged.neoforge.event.entity.EntityMobGriefingEvent(server.overworld(),boss);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(grief);
                    if(grief.canGrief())throw new IllegalStateException("Boss can grief terrain");
                    boss.setHealth(10);boss.invulnerableTime=0;
                    if(!boss.hurt(boss.damageSources().playerAttack(two),10000)||damage(WorldBossEncounter.snapshot(),"NYA")!=10000)throw new IllegalStateException("Overkill was credited");advance(server,2);
                }
                case 5->{
                    var directory=server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-gameplay-outbox");
                    var resultId=UUID.nameUUIDFromBytes((scheduled.get("id").getAsString()+":world-boss-result").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    var result=JsonParser.parseString(Files.readString(directory.resolve(resultId+".json"))).getAsJsonObject();
                    if(!result.get("outcome").getAsString().equals("KILLED")||result.getAsJsonArray("contributions").size()!=2||Files.exists(directory.resolve(scheduled.get("boss_uuid").getAsString()+".json")))throw new IllegalStateException("Incorrect shared/ordinary reward");advance(server,20);
                }
                case 6->{WorldBossEncounter.accept(server,reply(row(Instant.now().minusSeconds(600),Instant.now().minusSeconds(1))));advance(server,10);}
                case 7->{if(!WorldBossEncounter.snapshot().get("finished").getAsBoolean())throw new IllegalStateException("Timeout remained active");WorldBossEncounter.accept(server,reply(row(Instant.now().plusSeconds(120),Instant.now().plusSeconds(600))));advance(server,20);}
                case 8->{WorldBossEncounter.accept(server,reply(null));if(!WorldBossEncounter.snapshot().get("finished").getAsBoolean())throw new IllegalStateException("Cancellation remained active");advance(server,20);}
                case 9->{
                    if(one!=null)GameIdentity.PLAYERS.remove(one.getUUID());GameIdentity.PLAYERS.remove(two.getUUID());pending=null;
                    System.out.println("AOC_WORLD_BOSS_PROBE_PASS spawn=true actual_damage=true overkill_capped=true restore=true killed_outbox=true no_duplicate_kill_reward=true no_terrain_grief=true timeout=true cancellation=true");
                }
                case 10->{pending=null;System.out.println("AOC_WORLD_BOSS_PROBE_PASS chunk_control_only=true boss_created=false");}
                default->throw new IllegalStateException("Unknown boss probe stage");
            }
        }catch(Exception failure){throw new IllegalStateException("AOC_WORLD_BOSS_PROBE_FAILED",failure);}
    }
    private static WitherBoss entity(MinecraftServer server){return (WitherBoss)server.overworld().getEntity(UUID.fromString(scheduled.get("boss_uuid").getAsString()));}
    private static GameIdentity identity(String code){return new GameIdentity(code,new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID());}
    private static long damage(JsonObject state,String code){for(var item:state.getAsJsonArray("contributions")){var row=item.getAsJsonObject();if(row.get("clan_code").getAsString().equals(code))return row.get("damage_milli").getAsLong();}return 0;}
    private static JsonObject row(Instant starts,Instant ends){var row=new JsonObject();row.addProperty("id",UUID.randomUUID().toString());row.addProperty("boss_uuid",UUID.randomUUID().toString());row.addProperty("starts_at",starts.toString());row.addProperty("ends_at",ends.toString());return row;}
}
