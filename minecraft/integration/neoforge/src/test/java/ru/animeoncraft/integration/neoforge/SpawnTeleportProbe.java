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
        ProbePlayer(ServerLevel level){
            super(level.getServer(),level,new GameProfile(UUID.randomUUID(),"AocSpawnProbe"),ClientInformation.createDefault());
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
            var player=new ProbePlayer(server.getLevel(Level.NETHER));player.setPos(30,100,30);
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
            Files.writeString(Path.of("spawn-probe-result.json"),"{\"passed\":true,\"ordinary_player_command\":true,\"countdown\":true,\"cross_dimension_exact_destination\":true,\"cooldown\":true,\"disk_persistence\":true,\"blocked_destination\":true,\"logout_and_death_cancellation\":true}");
            System.out.println("AOC_SPAWN_PROBE_PASS");
        }catch(Throwable failure){failure.printStackTrace();System.out.println("AOC_SPAWN_PROBE_FAILED "+failure.getMessage());}
    }
}
