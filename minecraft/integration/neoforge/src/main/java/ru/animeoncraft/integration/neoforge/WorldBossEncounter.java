package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** One shared encounter. The authenticated API owns the schedule and final rewards. */
final class WorldBossEncounter {
    private static final String TAG="aoc_world_boss";
    private static final AtomicBoolean POLLING=new AtomicBoolean(),SENDING=new AtomicBoolean();
    private static State state;
    private static MinecraftServer running;
    private static int nextPoll;
    private static WitherBoss boss;
    private static LivingDeathEvent death;
    private static Instant deathAt;
    private static float healthBeforeHit;
    private static int missingTicks;
    private static boolean scheduleConfirmed;
    private WorldBossEncounter(){}

    static final class State {
        final UUID competition,bossId;
        final Instant starts,ends;
        final BlockPos arena;
        final java.util.Map<String,Long> damage=new java.util.TreeMap<>();
        final java.util.Set<Long> forcedChunks=new java.util.HashSet<>();
        long revision;
        boolean spawned,finished;
        float health=0,maxHealth=0;
        int announced=-1;
        State(JsonObject row,BlockPos point){
            competition=UUID.fromString(row.get("id").getAsString());bossId=UUID.fromString(row.get("boss_uuid").getAsString());
            starts=Instant.parse(row.get("starts_at").getAsString());ends=Instant.parse(row.get("ends_at").getAsString());arena=point;
            if(!ends.isAfter(starts))throw new IllegalArgumentException("Invalid boss window");
        }
        JsonArray contributions(){var rows=new JsonArray();damage.forEach((code,amount)->{var row=new JsonObject();row.addProperty("clan_code",code);row.addProperty("damage_milli",amount);rows.add(row);});return rows;}
        JsonObject json(){
            var row=new JsonObject();row.addProperty("id",competition.toString());row.addProperty("boss_uuid",bossId.toString());
            row.addProperty("starts_at",starts.toString());row.addProperty("ends_at",ends.toString());
            var point=new JsonObject();point.addProperty("x",arena.getX());point.addProperty("y",arena.getY());point.addProperty("z",arena.getZ());row.add("arena",point);
            var forced=new JsonArray();forcedChunks.forEach(forced::add);row.add("forced_chunks",forced);
            row.add("contributions",contributions());row.addProperty("revision",revision);row.addProperty("spawned",spawned);row.addProperty("finished",finished);row.addProperty("health",health);row.addProperty("max_health",maxHealth);return row;
        }
    }
    static boolean isBoss(Entity entity){return entity!=null&&entity.getPersistentData().getBoolean(TAG);}
    private static Path file(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-world-boss.json");}
    private static void save(MinecraftServer server)throws java.io.IOException{
        save(server,state);
    }
    private static void save(MinecraftServer server,State value)throws java.io.IOException{
        var target=file(server);var pending=target.resolveSibling(target.getFileName()+".pending");
        Files.writeString(pending,value.json().toString());Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static void announce(MinecraftServer server,String message){server.getPlayerList().broadcastSystemMessage(Component.literal(message),false);}
    private static void release(MinecraftServer server){
        if(state!=null)unforce(server,state);
        if(boss!=null&&boss.isAlive())boss.discard();boss=null;death=null;deathAt=null;
        java.util.stream.StreamSupport.stream(server.overworld().getAllEntities().spliterator(),false).filter(entity->entity instanceof Projectile&&isBoss(entity)).toList().forEach(Entity::discard);
    }
    static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event){
        if(running!=event.getServer()||state==null)return;
        try{if(boss!=null&&boss.isAlive()){state.health=boss.getHealth();state.maxHealth=boss.getMaxHealth();}save(running);}
        catch(java.io.IOException failure){System.getLogger("AnimeOnCraft").log(System.Logger.Level.ERROR,"World boss state could not be saved at shutdown");}
    }
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){if(running==event.getServer()){state=null;boss=null;death=null;deathAt=null;running=null;nextPoll=0;scheduleConfirmed=false;}}
    private static boolean orphan(Entity entity){return isBoss(entity)&&(state==null||state.finished||!(entity instanceof Projectile)&&!entity.getUUID().equals(state.bossId));}
    static void join(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        if(event.getLevel().isClientSide())return;
        if(event.getEntity() instanceof Projectile projectile&&isBoss(projectile.getOwner()))projectile.getPersistentData().putBoolean(TAG,true);
        if(scheduleConfirmed&&orphan(event.getEntity())){event.getEntity().discard();event.setCanceled(true);}
    }
    private static void cleanup(MinecraftServer server){if(scheduleConfirmed)java.util.stream.StreamSupport.stream(server.overworld().getAllEntities().spliterator(),false).filter(WorldBossEncounter::orphan).toList().forEach(Entity::discard);}
    static JsonObject snapshot(){return state==null?null:state.json();}
    private static void unforce(MinecraftServer server,State value){for(long packed:value.forcedChunks){var pos=new ChunkPos(packed);server.overworld().setChunkForced(pos.x,pos.z,false);}value.forcedChunks.clear();}
    private static void force(MinecraftServer server)throws java.io.IOException{
        var center=new ChunkPos(state.arena);
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            var pos=new ChunkPos(center.x+dx,center.z+dz);
            if(!server.overworld().getForcedChunks().contains(pos.toLong())){state.forcedChunks.add(pos.toLong());save(server);server.overworld().setChunkForced(pos.x,pos.z,true);}
        }
    }
    private static java.util.Set<Long> ownedChunks(JsonObject saved){
        var result=new java.util.HashSet<Long>();if(!saved.has("forced_chunks"))return result;
        var rows=saved.getAsJsonArray("forced_chunks");if(rows.size()>9)throw new IllegalArgumentException("Invalid owned arena chunks");
        var center=new ChunkPos(point(saved.getAsJsonObject("arena")));
        for(var packed:rows){long value=packed.getAsLong();var chunk=new ChunkPos(value);if(Math.abs((long)chunk.x-center.x)>1||Math.abs((long)chunk.z-center.z)>1)throw new IllegalArgumentException("Owned chunk outside arena");result.add(value);}return result;
    }
    private static void cleanupSavedChunks(MinecraftServer server)throws java.io.IOException{
        if(!Files.isRegularFile(file(server)))return;
        if(Files.size(file(server))>16384)throw new IllegalStateException("Oversized boss state");
        var saved=JsonParser.parseString(Files.readString(file(server))).getAsJsonObject();
        for(long packed:ownedChunks(saved)){var chunk=new ChunkPos(packed);server.overworld().setChunkForced(chunk.x,chunk.z,false);}
        saved.add("forced_chunks",new JsonArray());saved.addProperty("finished",true);
        var target=file(server);var temporary=target.resolveSibling(target.getFileName()+".pending");Files.writeString(temporary,saved.toString());Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static void refresh(MinecraftServer server){
        if(!server.isDedicatedServer()||!POLLING.compareAndSet(false,true))return;
        try{
            var settings=ServerSettings.from(System.getenv());
            HttpJson.get(settings.heartbeatUrl().resolve("../competitions/world-boss"),settings.key()).thenAccept(reply->server.execute(()->{
                try{accept(server,reply);}catch(Exception failure){System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING,"World boss schedule could not be applied; retrying");}
            })).whenComplete((ignored,failure)->POLLING.set(false));
        }catch(RuntimeException failure){POLLING.set(false);}
    }
    static void accept(MinecraftServer server,JsonObject reply)throws Exception{
        running=server;
        if(!reply.has("item"))throw new IllegalArgumentException("Missing encounter state");
        if(reply.get("item").isJsonNull()){
            scheduleConfirmed=true;
            if(state!=null&&!state.finished){release(server);state.finished=true;save(server);announce(server,"Событие мирового босса завершено или отменено.");}
            else if(state==null)cleanupSavedChunks(server);
            cleanup(server);return;
        }
        var row=reply.getAsJsonObject("item");UUID id=UUID.fromString(row.get("id").getAsString());
        if(state!=null&&state.competition.equals(id))return;
        release(server);
        state=null;
        var arena=row.has("arena")&&!row.get("arena").isJsonNull()?point(row.getAsJsonObject("arena")):findArena(server);
        var replacement=new State(row,arena);
        if(Files.isRegularFile(file(server))){
            if(Files.size(file(server))>16384)throw new IllegalStateException("Oversized boss state");
            var saved=JsonParser.parseString(Files.readString(file(server))).getAsJsonObject();
            if(saved.get("id").getAsString().equals(id.toString())){
                if(!saved.get("boss_uuid").getAsString().equals(replacement.bossId.toString()))throw new IllegalStateException("Boss identity changed");
                replacement=new State(row,point(saved.getAsJsonObject("arena")));
                for(var item:saved.getAsJsonArray("contributions")){var contribution=item.getAsJsonObject();String clan=contribution.get("clan_code").getAsString();long amount=contribution.get("damage_milli").getAsLong();if(!ClanCodes.valid(clan)||amount<0||amount>1_000_000_000_000L)throw new IllegalArgumentException("Invalid boss contribution");replacement.damage.put(clan,amount);}
                replacement.revision=saved.get("revision").getAsLong();replacement.spawned=saved.get("spawned").getAsBoolean();replacement.finished=saved.get("finished").getAsBoolean();replacement.health=saved.get("health").getAsFloat();replacement.maxHealth=saved.get("max_health").getAsFloat();
                replacement.forcedChunks.addAll(ownedChunks(saved));
            }else cleanupSavedChunks(server);
        }
        if(!replacement.finished){
            var policy=TerritoryPermissions.read(JsonParser.parseString(Files.readString(Path.of("config/animeoncraft-clan-spawns.json"))).getAsJsonObject(),server.overworld().getSeed());
            if(Math.abs((long)replacement.arena.getX())>100000||Math.abs((long)replacement.arena.getZ())>100000||!(WorldLayout.active()?statesArena(replacement.arena.getX(),replacement.arena.getZ()):neutralArena(policy,replacement.arena.getX(),replacement.arena.getZ())))throw new IllegalStateException("Boss arena intersects protected land");
        }
        save(server,replacement);state=replacement;missingTicks=0;scheduleConfirmed=true;cleanup(server);
        if(!state.finished){force(server);announce(server,"Мировой босс: сбор на нейтральной территории "+state.arena.toShortString()+". До появления: "+Math.max(0,java.time.Duration.between(Instant.now(),state.starts).toSeconds())+" сек. Урон считается по кланам.");}
    }
    private static BlockPos point(JsonObject row){return new BlockPos(row.get("x").getAsInt(),row.get("y").getAsInt(),row.get("z").getAsInt());}
    static BlockPos findArena(MinecraftServer server)throws Exception{
        if(WorldLayout.active())return layoutArena(server);
        var config=JsonParser.parseString(Files.readString(Path.of("config/animeoncraft-clan-spawns.json"))).getAsJsonObject();
        var policy=TerritoryPermissions.read(config,server.overworld().getSeed());var common=ClanSpawns.read(server).get("COMMON");
        if(common==null)throw new IllegalStateException("Common spawn required");
        for(int radius:new int[]{128,256,384,512,768,1024,1536})for(int angle=0;angle<16;angle++){
            int x=common.getX()+(int)Math.round(radius*Math.cos(angle*Math.PI/8)),z=common.getZ()+(int)Math.round(radius*Math.sin(angle*Math.PI/8));
            if(!neutralArena(policy,x,z))continue;
            var level=server.overworld();level.getChunk(x>>4,z>>4);int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            var pos=new BlockPos(x,y,z);
            if(y>level.getMinBuildHeight()&&y<level.getMaxBuildHeight()-4&&level.getBlockState(pos.below()).isCollisionShapeFullBlock(level,pos.below())&&level.getFluidState(pos.below()).isEmpty()&&level.getBlockState(pos).isAir()&&level.getBlockState(pos.above()).isAir())return pos;
        }
        throw new IllegalStateException("No safe neutral boss arena found");
    }
    /** States world: the boss fights on the arena the layout built (the event zone nearest to the centre). */
    static BlockPos layoutArena(MinecraftServer server){
        var layout=WorldLayout.state();
        var poi=layout.pois().values().stream().filter(p->"EVENT_ZONE".equals(p.type())).min(java.util.Comparator.comparingDouble(p->Math.hypot(p.x(),p.z())))
            .orElseThrow(()->new IllegalStateException("The world layout has no event zone for the boss"));
        var level=server.overworld();level.getChunk(poi.x()>>4,poi.z()>>4);
        int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,poi.x(),poi.z());
        if(y<=level.getMinBuildHeight()||y>=level.getMaxBuildHeight()-4)throw new IllegalStateException("Boss arena surface not found");
        return new BlockPos(poi.x(),y,poi.z());
    }
    /** No clan land within reach of the arena; the neutral core ({@link RegionPolicy#NOBODY}) is fine. */
    static boolean statesArena(int x,int z){
        for(int dx=-96;dx<=96;dx+=32)for(int dz=-96;dz<=96;dz+=32){
            String owner=TerritoryProtection.ownerAt(x+dx,z+dz);
            if(owner!=null&&!RegionPolicy.NOBODY.equals(owner))return false;
        }
        return true;
    }
    static boolean neutralArena(TerritoryPermissions policy,int x,int z){
        return policy.territories().stream().noneMatch(t->x+96>=t.minX()&&x-96<t.maxX()&&z+96>=t.minZ()&&z-96<t.maxZ())
            &&policy.spawns().stream().noneMatch(p->Math.abs((long)x-p.x())<=96+policy.spawnRadius()&&Math.abs((long)z-p.z())<=96+policy.spawnRadius());
    }
    static void tick(ServerTickEvent.Post event){
        var server=event.getServer();if(!server.isDedicatedServer())return;
        if(server.getTickCount()>=nextPoll){nextPoll=server.getTickCount()+100;refresh(server);}
        if(state==null||state.finished)return;
        try{
            if(death!=null){var completed=death;death=null;if(!completed.isCanceled()&&boss!=null&&boss.getHealth()<=0){finish(server,deathAt.isBefore(state.ends)?"KILLED":"EXPIRED",deathAt);return;}}
            if(!Instant.now().isBefore(state.ends)){finish(server,"EXPIRED");return;}
            if(Instant.now().isBefore(state.starts)){
                long seconds=java.time.Duration.between(Instant.now(),state.starts).toSeconds();
                if(server.getTickCount()%20==0&&(seconds==60||seconds==30||seconds==10)&&state.announced!=(int)seconds){state.announced=(int)seconds;announce(server,"Мировой босс появится через "+seconds+" сек. Координаты: "+state.arena.toShortString());}
            }else{
                var entity=server.overworld().getEntity(state.bossId);
                if(entity instanceof WitherBoss found){if(!isBoss(found))throw new IllegalStateException("Boss UUID belongs to another entity");boss=found;}
                else if(!state.spawned||++missingTicks>100)spawn(server);
                if(boss!=null&&boss.isAlive()){
                    if(Math.abs(boss.getX()-state.arena.getX())>16||Math.abs(boss.getZ()-state.arena.getZ())>16||Math.abs(boss.getY()-state.arena.getY())>32){boss.teleportTo(state.arena.getX()+0.5,state.arena.getY()+3,state.arena.getZ()+0.5);boss.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
                    state.health=boss.getHealth();state.maxHealth=boss.getMaxHealth();
                }
            }
            if(server.getTickCount()%20==0){save(server);if(server.getTickCount()%100==0)progress(server);}
        }catch(Exception failure){System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING,"World boss update failed; state retained for retry");}
    }
    static void spawn(MinecraftServer server)throws Exception{
        // FULL terrain is not enough: entity storage must be ticking before adding
        // a persistent boss, particularly after restarting an active encounter.
        if(!server.overworld().isPositionEntityTicking(state.arena))return;
        var entity=EntityType.WITHER.create(server.overworld());if(entity==null)throw new IllegalStateException("Cannot create world boss");
        entity.setUUID(state.bossId);entity.getPersistentData().putBoolean(TAG,true);entity.setCustomName(Component.literal("Мировой босс AnimeOnCraft"));entity.setPersistenceRequired();
        double maximum=state.maxHealth>0?state.maxHealth:maximumHealth(server.getPlayerList().getPlayers().size());
        entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(maximum);entity.setHealth(state.health>0?Math.min((float)maximum,state.health):(float)maximum);
        entity.moveTo(state.arena.getX()+0.5,state.arena.getY()+3,state.arena.getZ()+0.5,0,0);
        if(!server.overworld().addFreshEntity(entity))throw new IllegalStateException("Boss UUID already exists");
        boss=entity;state.spawned=true;state.health=entity.getHealth();state.maxHealth=entity.getMaxHealth();missingTicks=0;save(server);
        announce(server,"Мировой босс появился! "+state.arena.toShortString()+". Все кланы сражаются за вклад по урону, последний удар не определяет награду.");
    }
    static double maximumHealth(int players){return Math.min(3000,600+80L*Math.max(0,players));}
    private static JsonObject identity(MinecraftServer server){
        var body=new JsonObject();body.addProperty("server_id",ServerSettings.from(System.getenv()).serverId().toString());body.addProperty("competition_id",state.competition.toString());body.addProperty("boss_uuid",state.bossId.toString());return body;
    }
    private static void progress(MinecraftServer server){
        if(!SENDING.compareAndSet(false,true))return;
        try{var settings=ServerSettings.from(System.getenv());var body=identity(server);body.addProperty("revision",state.revision);body.add("arena",state.json().get("arena"));body.add("contributions",state.contributions());
            HttpJson.post(settings.heartbeatUrl().resolve("../competitions/world-boss/progress"),settings.key(),body).whenComplete((reply,failure)->SENDING.set(false));
        }catch(RuntimeException failure){SENDING.set(false);}
    }
    static void finish(MinecraftServer server,String outcome)throws Exception{
        finish(server,outcome,Instant.now());
    }
    private static void finish(MinecraftServer server,String outcome,Instant completedAt)throws Exception{
        var body=identity(server);body.addProperty("event_id",UUID.nameUUIDFromBytes((state.competition+":world-boss-result").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        body.addProperty("outcome",outcome);body.addProperty("completed_at",completedAt.toString());body.addProperty("expires_at",state.ends.plusSeconds(600).toString());body.add("contributions",state.contributions());
        GameplayOutbox.store(server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-gameplay-outbox"),body);
        state.finished=true;save(server);release(server);
        announce(server,outcome.equals("KILLED")?"Мировой босс побеждён! Результат отправлен серверу. Награды кланам — по доле урона от 5%.":"Время мирового босса вышло. Событие завершено без наград.");
    }
    static void preDamage(LivingDamageEvent.Pre event){if(isBoss(event.getEntity()))healthBeforeHit=event.getEntity().getHealth();}
    static void damage(LivingDamageEvent.Post event){
        if(!isBoss(event.getEntity())||state==null||state.finished||!event.getEntity().getUUID().equals(state.bossId)||!(event.getSource().getEntity() instanceof ServerPlayer player))return;
        var identity=GameIdentity.PLAYERS.get(player.getUUID());if(identity==null||identity.clan()==null||Instant.now().isBefore(state.starts)||!Instant.now().isBefore(state.ends))return;
        long amount=Math.round(Math.max(0,Math.min(event.getNewDamage(),healthBeforeHit))*1000d);
        if(amount>0){state.damage.merge(identity.clan(),amount,(a,b)->Math.min(1_000_000_000_000L,a+b));state.revision++;}
    }
    static void death(LivingDeathEvent event){if(state!=null&&isBoss(event.getEntity())&&event.getEntity().getUUID().equals(state.bossId)){death=event;deathAt=Instant.now();}}
    static void grief(net.neoforged.neoforge.event.entity.EntityMobGriefingEvent event){if(isBoss(event.getEntity()))event.setCanGrief(false);}
    static void drops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event){if(isBoss(event.getEntity()))event.setCanceled(true);}
    static void experience(net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent event){if(isBoss(event.getEntity()))event.setDroppedExperience(0);}
    private static boolean fromBoss(Entity entity){return isBoss(entity)||entity instanceof Projectile projectile&&isBoss(projectile.getOwner());}
    static void explosion(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event){if(fromBoss(event.getExplosion().getDirectSourceEntity())||isBoss(event.getExplosion().getIndirectSourceEntity()))event.getAffectedBlocks().clear();}
    static void protectSpawn(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event){
        if(fromBoss(event.getSource().getDirectEntity())||fromBoss(event.getSource().getEntity()))if(TerritoryProtection.protectedPosition(event.getEntity().level(),event.getEntity().blockPosition()))event.setCanceled(true);
    }
    static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event){if(state!=null&&!state.finished)event.getEntity().sendSystemMessage(Component.literal("Мировой босс: "+state.arena.toShortString()+". Результаты кланов — в лаунчере."));}
}
