package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.jetbrains.annotations.Nullable;

/** Runs only in an isolated full-modpack server; never included in the production JAR. */
@Mod("aocspawnprobe")
public final class SpawnTeleportProbe {
    public SpawnTeleportProbe(){NeoForge.EVENT_BUS.addListener(this::started);}
    private static void check(boolean value,String text){if(!value)throw new IllegalStateException(text);}
    private static final class ProbePlayer extends ServerPlayer {
        final List<String> messages=new ArrayList<>();
        ProbePlayer(ServerLevel level){this(level,"AocSpawnProbe");}
        ProbePlayer(ServerLevel level,String name){
            super(level.getServer(),level,new GameProfile(UUID.randomUUID(),name),ClientInformation.createDefault());
            var network=new Connection(PacketFlow.SERVERBOUND){@Override public void setListenerForServerboundHandshake(PacketListener listener){}};
            connection=new ServerGamePacketListenerImpl(level.getServer(),network,this,CommonListenerCookie.createInitial(getGameProfile(),false)){
                @Override public void send(Packet<?> packet){}
                @Override public void send(Packet<?> packet,@Nullable PacketSendListener listener){}
                @Override public boolean hasChannel(net.minecraft.resources.ResourceLocation channel){return false;}
            };
        }
        @Override public void displayClientMessage(Component text,boolean actionBar){messages.add(text.getString());}
        @Override public void sendSystemMessage(Component text){messages.add(text.getString());}
    }
    private void started(ServerStartedEvent event){
        if(!Boolean.getBoolean("animeoncraft.spawnProbe"))return;
        var server=event.getServer();
        if(server.getPort()!=25569||!"127.0.0.1".equals(server.getLocalIp())||!server.getWorldData().getLevelName().startsWith("aoc-spawn-probe-"))throw new IllegalStateException("Own disposable loopback world required");
        try{
            var commands=server.getCommands().getDispatcher();var store=SpawnTeleportStore.get(server);
            var level=server.overworld();level.getChunk(-1,0);level.getChunk(0,0);
            for(int x=-1;x<=1;x++)for(int z=4;z<=6;z++){
                level.setBlockAndUpdate(new BlockPos(x,89,z),Blocks.STONE.defaultBlockState());
                for(int y=90;y<=92;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            }
            var player=new ProbePlayer(server.getLevel(Level.NETHER));
            player.serverLevel().setBlockAndUpdate(new BlockPos(30,99,30),Blocks.STONE.defaultBlockState());
            player.setPos(30,100,30);player.setOnGround(true);
            var source=player.createCommandSourceStack().withPermission(0);
            check(commands.getRoot().getChild("spawn").canUse(source),"Ordinary player can use /spawn");
            try{commands.execute("spawn",server.createCommandSourceStack());throw new IllegalStateException("Console must be player-only");}
            catch(CommandSyntaxException expected){}
            long started=System.currentTimeMillis();check(commands.execute("spawn",source)==1,"Production command begins countdown");
            check(commands.execute("spawn",source)==0,"Spam cannot enqueue another teleport");
            SpawnTeleport.advance(server,player,started+1001);
            check(player.level().dimension().equals(Level.NETHER)&&player.getX()==30,"No early teleport");
            check(player.messages.stream().anyMatch(m->m.contains("9 сек.")),"Visible countdown changes");
            player.fallDistance=40;player.setDeltaMovement(1,-1,1);
            long completed=started+10_100;SpawnTeleport.advance(server,player,completed);
            check(player.level().dimension().equals(Level.OVERWORLD)&&player.getX()==0&&player.getY()==90&&player.getZ()==5,"Exact cross-dimension destination");
            check(player.fallDistance==0&&player.getDeltaMovement().lengthSqr()==0,"No inherited fall damage/velocity");
            check(store.queue.cooldowns().get(player.getUUID())==completed+300_000,"Full cooldown starts after completion");
            SpawnTeleport.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
            check(commands.execute("spawn",player.createCommandSourceStack().withPermission(0))==0,"Logout cannot bypass cooldown");
            var blocked=new ProbePlayer(level);blocked.setPos(20,100,20);var blockedSource=blocked.createCommandSourceStack().withPermission(0);
            level.setBlockAndUpdate(new BlockPos(0,90,5),Blocks.STONE.defaultBlockState());
            started=System.currentTimeMillis();check(commands.execute("spawn",blockedSource)==1,"Blocked fixture queues");SpawnTeleport.advance(server,blocked,started+10_100);
            check(blocked.getX()==20&&!store.queue.cooldowns().containsKey(blocked.getUUID()),"Blocked spawn cancels without cooldown");
            level.setBlockAndUpdate(new BlockPos(0,90,5),Blocks.AIR.defaultBlockState());
            check(commands.execute("spawn",blockedSource)==1,"Can retry after safety cancellation");
            SpawnTeleport.death(new LivingDeathEvent(blocked,blocked.damageSources().generic()));
            check(!store.queue.pendingPlayers().contains(blocked.getUUID()),"Death cancels pending teleport");
            check(commands.execute("spawn",blockedSource)==1,"New life can request again");
            SpawnTeleport.logout(new PlayerEvent.PlayerLoggedOutEvent(blocked));
            check(!store.queue.pendingPlayers().contains(blocked.getUUID()),"Logout cancels countdown");
            server.saveEverything(false,true,true);
            var file=server.getWorldPath(LevelResource.ROOT).resolve("data/animeoncraft-spawn-cooldowns.dat");
            var saved=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());
            var restored=SpawnTeleportStore.load(saved.getCompound("data"),server.registryAccess());
            check(restored.queue.cooldowns().equals(store.queue.cooldowns()),"Actual compressed world NBT roundtrip");
            check(restored.queue.request(player.getUUID(),completed+299_999).status()==SpawnTeleportQueue.Status.COOLDOWN,"Cooldown remains until five minutes");
            check(restored.queue.request(player.getUUID(),completed+300_000).status()==SpawnTeleportQueue.Status.STARTED,"Available at exactly five minutes");
            check(!restored.queue.pendingPlayers().contains(blocked.getUUID()),"Pending teleport never restored");
            var moving=new ProbePlayer(level);moving.setPos(30,90,30);var movingSource=moving.createCommandSourceStack().withPermission(0);
            check(commands.execute("spawn",movingSource)==1,"Movement fixture starts");
            moving.setYRot(90);SpawnTeleport.advance(server,moving,System.currentTimeMillis());
            check(store.queue.pendingPlayers().contains(moving.getUUID()),"Looking does not cancel");
            moving.setPos(30,91.25,30);SpawnTeleport.advance(server,moving,System.currentTimeMillis());
            check(store.queue.pendingPlayers().contains(moving.getUUID()),"Jump in place does not cancel");
            moving.setPos(30.51,90,30);SpawnTeleport.advance(server,moving,System.currentTimeMillis());
            check(!store.queue.pendingPlayers().contains(moving.getUUID())&&!store.queue.cooldowns().containsKey(moving.getUUID()),"Step cancels without cooldown");
            check(commands.execute("spawn",movingSource)==1,"Immediate retry after movement");store.queue.cancel(moving.getUUID());
            GuideBook.login(new PlayerEvent.PlayerLoggedInEvent(moving));
            long books=moving.getInventory().items.stream().filter(s->s.is(net.minecraft.world.item.Items.WRITTEN_BOOK)).mapToLong(net.minecraft.world.item.ItemStack::getCount).sum();
            check(books==1&&moving.getPersistentData().getBoolean(GuideBook.GIVEN_TAG),"Actual written guide given once");
            GuideBook.login(new PlayerEvent.PlayerLoggedInEvent(moving));
            check(moving.getInventory().items.stream().filter(s->s.is(net.minecraft.world.item.Items.WRITTEN_BOOK)).mapToLong(net.minecraft.world.item.ItemStack::getCount).sum()==1,"Login does not duplicate guide");
            check(commands.execute("guidebook",movingSource)==1&&commands.execute("guidebook",movingSource)==0,"Guide command cooldown");
            var arenaOwner=new ProbePlayer(level);arenaOwner.setPos(400,90,400);var admin=arenaOwner.createCommandSourceStack().withPermission(4);
            var obstacle=new BlockPos(401,90,400);level.setBlockAndUpdate(obstacle,Blocks.CHEST.defaultBlockState());
            check(commands.execute("aocarena build",admin)==0&&!ArenaStore.get(server).placed,"Arena refuses occupied block before building");
            check(level.getBlockState(obstacle).is(Blocks.CHEST),"Refused build preserves obstacle");
            level.setBlockAndUpdate(obstacle,Blocks.AIR.defaultBlockState());
            check(commands.execute("aocarena build",admin)==1&&ArenaStore.get(server).placed,"Arena builds in empty place");
            duel(server,level);
            check(commands.execute("anticheat top",admin)==1,"Anticheat command registered");
            Files.writeString(Path.of("spawn-probe-result.json"),"{\"passed\":true,\"ordinary_player_command\":true,\"countdown\":true,\"cross_dimension_exact_destination\":true,\"cooldown\":true,\"disk_persistence\":true,\"blocked_destination\":true,\"logout_and_death_cancellation\":true,\"step_cancels_without_cooldown\":true,\"looking_and_jump_allowed\":true,\"guidebook_once_and_cooldown\":true,\"arena_build_and_obstacle_refusal\":true,\"anticheat_command\":true}");
            System.out.println("AOC_SPAWN_PROBE_PASS");
        }catch(Throwable failure){failure.printStackTrace();System.out.println("AOC_SPAWN_PROBE_FAILED "+failure.getMessage());}
    }
    /** Two server-side actors exercise production match state; not a substitute for two network clients. */
    @SuppressWarnings("unchecked")
    private static void duel(net.minecraft.server.MinecraftServer server,ServerLevel level) throws Exception {
        var a=new ProbePlayer(level,"AocArenaOne");var b=new ProbePlayer(level,"AocArenaTwo");
        a.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);b.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        a.setPos(430,90,400);b.setPos(431,90,400);
        b.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3));
        var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);
        var players=(java.util.Map<UUID,ServerPlayer>)field.get(server.getPlayerList());players.put(a.getUUID(),a);players.put(b.getUUID(),b);
        try{
            var commands=server.getCommands().getDispatcher();
            check(commands.execute("arena join",a.createCommandSourceStack().withPermission(0))==1,"First arena join");
            check(commands.execute("arena join",b.createCommandSourceStack().withPermission(0))==1,"Second arena join");
            var tick=new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(()->true,server);PvpArena.tick(tick);
            check(PvpArena.inMatch(a.getUUID())&&PvpArena.inMatch(b.getUUID())&&!PvpArena.fighting(a,b),"Countdown is not fighting");
            var matchField=PvpArena.class.getDeclaredField("current");matchField.setAccessible(true);var match=matchField.get(null);
            var start=match.getClass().getDeclaredField("startTick");start.setAccessible(true);start.setLong(match,server.getTickCount()-120);
            PvpArena.tick(tick);check(PvpArena.fighting(a,b),"Countdown enables fight");
            var death=new LivingDeathEvent(b,b.damageSources().generic());PvpArena.death(death);
            check(death.isCanceled()&&b.isAlive()&&!PvpArena.inMatch(b.getUUID()),"Knockout cancels actual death and ends match");
            check(b.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND)==3,"Knockout preserves inventory");
            var store=ArenaStore.get(server);check(store.fighter(a.getUUID(),null).wins==1&&store.fighter(a.getUUID(),null).rating==1016,"Winner and Elo");
            check(store.fighter(b.getUUID(),null).losses==1&&store.fighter(b.getUUID(),null).rating==984,"Loser and Elo");
            check(commands.execute("arena top",a.createCommandSourceStack().withPermission(0))==2,"Top includes both actors");
            System.out.println("AOC_ARENA_ACTORS_PASS countdown=true fight=true knockout=true inventory_preserved=true elo=true top=true");
        }finally{players.remove(a.getUUID());players.remove(b.getUUID());}
    }
}
