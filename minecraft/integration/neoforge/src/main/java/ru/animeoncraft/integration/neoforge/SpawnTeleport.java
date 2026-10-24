package ru.animeoncraft.integration.neoforge;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Fixed central spawn; does not change first login, beds or clan respawn rules. */
final class SpawnTeleport {
    private static final BlockPos DESTINATION=new BlockPos(0,90,5);
    private SpawnTeleport() {}
    static void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("spawn").executes(context->{
            var player=context.getSource().getPlayerOrException();
            if(!player.isAlive()||player.isRemoved()){
                context.getSource().sendFailure(Component.literal("Дождись возрождения перед телепортацией."));return 0;
            }
            var reply=SpawnTeleportStore.get(context.getSource().getServer()).queue.request(player.getUUID(),System.currentTimeMillis());
            switch(reply.status()){
                case COOLDOWN -> context.getSource().sendFailure(Component.literal("/spawn будет доступен через "+reply.seconds()+" сек."));
                case PENDING -> context.getSource().sendFailure(Component.literal("Телепортация уже готовится: "+reply.seconds()+" сек."));
                case STARTED -> {
                    player.sendSystemMessage(Component.literal("Телепортация на центральный спавн через 10 секунд."));
                    countdown(player,10);return 1;
                }
            }
            return 0;
        }));
    }
    static void tick(ServerTickEvent.Post event){
        var server=event.getServer();var queue=SpawnTeleportStore.get(server).queue;
        for(var id:queue.pendingPlayers()){
            var player=server.getPlayerList().getPlayer(id);
            if(player==null)queue.cancel(id);else advance(server,player,System.currentTimeMillis());
        }
    }
    static void advance(MinecraftServer server,ServerPlayer player,long now){
        var store=SpawnTeleportStore.get(server);var queue=store.queue;
        if(!player.isAlive()||player.isRemoved()){queue.cancel(player.getUUID());return;}
        var seconds=queue.advance(player.getUUID(),now);
        if(seconds.isEmpty())return;
        if(seconds.getAsInt()>0){countdown(player,seconds.getAsInt());return;}
        var level=server.overworld();
        // The exact coordinates are intentional, including X/Z on the block boundary.
        level.getChunk(-1,0);level.getChunk(0,0);
        var box=player.getDimensions(Pose.STANDING).makeBoundingBox(0,90,5);
        var floor=DESTINATION.below();
        if(!level.getWorldBorder().isWithinBounds(box)||!level.noCollision(player,box)||level.containsAnyLiquid(box)
            ||!level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP)){
            queue.cancel(player.getUUID());
            player.sendSystemMessage(Component.literal("Телепортация отменена: место спавна небезопасно. Сообщи администрации. Перерыв не начислен."));
            player.displayClientMessage(Component.literal("Телепортация отменена: спавн заблокирован."),true);return;
        }
        player.stopRiding();
        if(player.isSleeping())player.stopSleepInBed(true,true);
        if(!player.teleportTo(level,0,90,5,java.util.Set.of(),player.getYRot(),player.getXRot())){
            queue.cancel(player.getUUID());player.sendSystemMessage(Component.literal("Не удалось телепортироваться. Попробуй /spawn ещё раз."));return;
        }
        player.setDeltaMovement(Vec3.ZERO);player.fallDistance=0;
        if(queue.succeeded(player.getUUID(),Math.max(now,System.currentTimeMillis())))store.setDirty();
        player.displayClientMessage(Component.literal("Ты на центральном спавне. /spawn снова через 5 минут."),true);
    }
    private static void countdown(ServerPlayer player,int seconds){player.displayClientMessage(Component.literal("На спавн через "+seconds+" сек."),true);}
    static void logout(PlayerEvent.PlayerLoggedOutEvent event){
        if(event.getEntity() instanceof ServerPlayer player&&player.getServer()!=null)SpawnTeleportStore.get(player.getServer()).queue.cancel(player.getUUID());
    }
    static void death(LivingDeathEvent event){
        if(event.getEntity() instanceof ServerPlayer player&&player.getServer()!=null)SpawnTeleportStore.get(player.getServer()).queue.cancel(player.getUUID());
    }
}
