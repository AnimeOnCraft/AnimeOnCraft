package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Opt-in loopback fixture only. Exercises real vanilla actions and registered server events. */
final class TerritoryProtectionProbe {
    private TerritoryProtectionProbe() {}

    static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("animeoncraft.territoryProbe")) return;
        var server = event.getServer();
        if (server.getPort() != 25569 || !"127.0.0.1".equals(server.getLocalIp())
            || !server.getWorldData().getLevelName().startsWith("aoc-territory-probe-")) {
            throw new IllegalStateException("Territory probe requires its own loopback world");
        }
        ClanControlSync.accept(java.util.Map.of());
        if(Boolean.getBoolean("animeoncraft.balanceProbe")){
            try{SpellBalanceProbe.verify(server);}catch(Exception failure){throw new IllegalStateException("Runtime spell balance probe failed",failure);}
        }
        if(Boolean.getBoolean("animeoncraft.controllerRestartProbe")){
            try{ControllerRestartProbe.schedule(server);}catch(Exception failure){throw new IllegalStateException("Controller probe setup failed",failure);}return;
        }
        if(Boolean.getBoolean("animeoncraft.worldBossProbeOnly")){WorldBossProbe.schedule(server);return;}
        var level = server.overworld();
        var owner = new FakePlayer(level, new GameProfile(UUID.fromString("9be87aa0-bdbe-4430-a869-fba5590bec01"), "AocClaimOwner"));
        var foreign = new FakePlayer(level, new GameProfile(UUID.fromString("9be87aa0-bdbe-4430-a869-fba5590bec02"), "AocClaimGuest"));
        owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        foreign.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        try {
            GameIdentity.PLAYERS.put(foreign.getUUID(), new GameIdentity(null, new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
            var root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("config/animeoncraft-clan-spawns.json"))).getAsJsonObject();
            var policy = TerritoryPermissions.read(root, level.getSeed());
            for (var region : policy.territories()) {
            GameIdentity.PLAYERS.put(foreign.getUUID(), new GameIdentity(null, new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
            GameIdentity.PLAYERS.put(owner.getUUID(), new GameIdentity(region.clan(), new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
                var pos = new BlockPos(region.minX() + 2, 100, region.minZ() + 2);
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                check(!foreign.gameMode.destroyBlock(pos) && level.getBlockState(pos).is(Blocks.STONE), "foreign break " + region.clan());
                check(owner.gameMode.destroyBlock(pos) && level.getBlockState(pos).isAir(), "member break " + region.clan());
                level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                place(foreign, pos.below());
                check(level.getBlockState(pos).isAir(), "foreign place " + region.clan());
                place(owner, pos.below());
                check(level.getBlockState(pos).is(Blocks.STONE), "member place " + region.clan());
                level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, Level.ExplosionInteraction.TNT);
                check(level.getBlockState(pos).is(Blocks.STONE), "explosion " + region.clan());
            GameIdentity.PLAYERS.put(foreign.getUUID(), new GameIdentity(ClanCodes.all().stream().filter(c -> !c.equals(region.clan())).findFirst().orElseThrow(), new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
                check(!foreign.gameMode.destroyBlock(pos), "other clan break " + region.clan());
                owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
                foreign.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
                var use = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(foreign,
                    InteractionHand.MAIN_HAND, pos, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(use);
                check(use.isCanceled(), "foreign lava bucket " + region.clan());
                foreign.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                for(var block:java.util.List.of(Blocks.CHEST,Blocks.PURPLE_SHULKER_BOX,Blocks.FURNACE,Blocks.HOPPER)){
                    level.setBlockAndUpdate(pos,block.defaultBlockState());
                    var containerUse=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(foreign,
                        InteractionHand.MAIN_HAND,pos,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(containerUse);
                    check(containerUse.isCanceled(),"foreign container "+block);
                    var ownerUse=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(owner,
                        InteractionHand.MAIN_HAND,pos,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(ownerUse);
                    check(!ownerUse.isCanceled(),"member container "+block);
                }
                var chestPos=new BlockPos(region.minX(),100,region.minZ()+4);
                level.setBlockAndUpdate(chestPos,Blocks.CHEST.defaultBlockState());
                var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(chestPos);
                var outside=chestPos.west();
                var hopperState=Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.FACING,Direction.EAST);
                level.setBlockAndUpdate(outside,hopperState);
                var hopper=(net.minecraft.world.level.block.entity.HopperBlockEntity)level.getBlockEntity(outside);
                hopper.setItem(0,new ItemStack(Items.DIAMOND,3));
                net.minecraft.world.level.block.entity.HopperBlockEntity.pushItemsTick(level,outside,hopperState,hopper);
                check(chest.isEmpty()&&hopper.getItem(0).getCount()==3,"hopper cannot cross boundary");
                var inside=chestPos.east();
                hopperState=hopperState.setValue(net.minecraft.world.level.block.HopperBlock.FACING,Direction.WEST);
                level.setBlockAndUpdate(inside,hopperState);
                hopper=(net.minecraft.world.level.block.entity.HopperBlockEntity)level.getBlockEntity(inside);
                hopper.setItem(0,new ItemStack(Items.DIAMOND,3));
                net.minecraft.world.level.block.entity.HopperBlockEntity.pushItemsTick(level,inside,hopperState,hopper);
                check(!chest.isEmpty(),"hopper works within clan territory");
                var piston = new net.neoforged.neoforge.event.level.PistonEvent.Pre(level,
                    pos.atY(100).offset(-3, 0, 0), Direction.EAST, net.neoforged.neoforge.event.level.PistonEvent.PistonMoveType.EXTEND);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(piston);
                check(piston.isCanceled(), "piston claim boundary " + region.clan());
            }
            for (String clan : ClanCodes.fromConfig(root)) {
                var point = root.getAsJsonObject("clans").getAsJsonObject(clan);
                var pos = new BlockPos(point.get("x").getAsInt(), 100, point.get("z").getAsInt());
                GameIdentity.PLAYERS.put(owner.getUUID(), new GameIdentity(clan, new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
                String other = ClanCodes.all().stream().filter(c -> !c.equals(clan)).findFirst().orElseThrow();
                GameIdentity.PLAYERS.put(foreign.getUUID(), new GameIdentity(other, new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                check(!foreign.gameMode.destroyBlock(pos), "foreign clan spawn break " + clan);
                check(owner.gameMode.destroyBlock(pos) && level.getBlockState(pos).isAir(), "member clan spawn break " + clan);
                level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                place(foreign, pos.below());
                check(level.getBlockState(pos).isAir(), "foreign clan spawn place " + clan);
                place(owner, pos.below());
                check(level.getBlockState(pos).is(Blocks.STONE), "member clan spawn place " + clan);
                level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
                for (var player : java.util.List.of(owner, foreign)) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    var use = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player,
                        InteractionHand.MAIN_HAND, pos, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(use);
                    check(use.isCanceled() == (player == foreign), "clan spawn chest rights " + clan);
                }
            }
            for (var point : policy.spawns()) {
                var pos = new BlockPos(point.x(), 100, point.z());
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                check(!owner.gameMode.destroyBlock(pos) && level.getBlockState(pos).is(Blocks.STONE), "spawn protection");
            }
            var neutral = new BlockPos(2000, 100, 2000);
            level.setBlockAndUpdate(neutral, Blocks.STONE.defaultBlockState());
            check(foreign.gameMode.destroyBlock(neutral) && level.getBlockState(neutral).isAir(), "neutral construction");
            var boundary=policy.territories().getFirst();
            var target=new BlockPos(boundary.minX(),150,boundary.minZ()+10);
            check(!level.setBlockAndUpdate(target,Blocks.FIRE.defaultBlockState()),"natural fire blocked in claim");
            check(level.getBlockState(target).isAir(),"claim fire did not appear");
            var neutralFire=new BlockPos(2000,150,2000);
            check(level.setBlockAndUpdate(neutralFire,Blocks.FIRE.defaultBlockState()),"neutral fire remains permitted");
            var spread=net.minecraft.world.level.material.FlowingFluid.class.getDeclaredMethod("spreadTo",net.minecraft.world.level.LevelAccessor.class,BlockPos.class,net.minecraft.world.level.block.state.BlockState.class,Direction.class,net.minecraft.world.level.material.FluidState.class);
            spread.setAccessible(true);
            spread.invoke(net.minecraft.world.level.material.Fluids.FLOWING_WATER,level,target,level.getBlockState(target),Direction.EAST,net.minecraft.world.level.material.Fluids.FLOWING_WATER.getFlowing(7,false));
            check(level.getBlockState(target).isAir(),"water flow across claim boundary blocked");
            var inside=target.east();
            spread.invoke(net.minecraft.world.level.material.Fluids.FLOWING_WATER,level,inside,level.getBlockState(inside),Direction.EAST,net.minecraft.world.level.material.Fluids.FLOWING_WATER.getFlowing(7,false));
            check(level.getBlockState(inside).is(Blocks.WATER),"water flow inside claim allowed");
            System.out.println("AOC_SPREAD_PROBE_PASS claimed_fire=false neutral_fire=true foreign_fluid=false own_fluid=true");
            CombatProtectionProbe.verify(level,policy.territories().getFirst());
            if(Boolean.getBoolean("animeoncraft.magicBoundaryProbe"))SpellBoundaryProbe.verify(level,policy.territories().getFirst());
            GameplayRewardProbe.verify(event.getServer());
            CaptureProbe.verify(server);
            if(net.neoforged.fml.ModList.get().isLoaded("create"))CreateProtectionProbe.verify(level,policy.territories().getFirst(),policy.territories().get(1));
            if(Boolean.getBoolean("animeoncraft.balanceProbe")){
                check(server.getPackRepository().getSelectedIds().contains("file/animeoncraft-balance"),"balance datapack enabled");
                check(server.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.parse("create:sequenced_assembly/precision_mechanism")).isPresent(),"balanced Create recipe loaded");
                System.out.println("AOC_BALANCE_PROBE_PASS datapack=true create_recipe=true");
            }
            AdventureProbe.verify(server,policy);
            IgnitionProbe.verify(level,policy);
            StorageProbe.verify(level,policy);
            FullModsProbe.verify(level,policy);
            System.out.println("AOC_TERRITORY_PROBE_PASS clans=5 real_break_place_explosion=true protected_spawns="
                + policy.spawns().size() + " editable_clan_spawns=" + ClanCodes.fromConfig(root).size() + " neutral=true");
        } catch (Exception failure) {
            throw new IllegalStateException("AOC_TERRITORY_PROBE_FAILED", failure);
        } finally {
            GameIdentity.PLAYERS.remove(owner.getUUID()); GameIdentity.PLAYERS.remove(foreign.getUUID());
        }
    }

    private static void place(FakePlayer player, BlockPos floor) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 64));
        player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
    }

    private static void check(boolean condition, String label) { if (!condition) throw new IllegalStateException(label); }
}
