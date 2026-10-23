package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Explicit disposable-world fixture; never runs on ordinary servers. */
final class AdventureProbe {
    static void verify(MinecraftServer server, TerritoryPermissions policy) {
        if (!Boolean.getBoolean("animeoncraft.adventureProbe")) return;
        var level=server.overworld();
        var region=policy.territories().getFirst();
        var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocTreeProbe"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setShiftKeyDown(true);
        player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_AXE));
        GameIdentity.PLAYERS.put(player.getUUID(),new GameIdentity(region.clan(),new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
        try {
            var own=new BlockPos(region.minX()+8,110,region.minZ()+8);
            tree(level,own);
            player.gameMode.destroyBlock(own);
            check(level.getBlockState(own).isAir() && level.getBlockState(own.above(2)).is(Blocks.OAK_LOG),"vanilla chopping must break only the selected log");
            check(player.getMainHandItem().getDamageValue()>=1,"axe durability not charged");
            GameIdentity.PLAYERS.put(player.getUUID(),new GameIdentity("NYA",new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
            var foreign=own.south(8);
            tree(level,foreign);
            player.gameMode.destroyBlock(foreign);
            check(level.getBlockState(foreign).is(Blocks.OAK_LOG)&&level.getBlockState(foreign.above(2)).is(Blocks.OAK_LOG),"bulk break ran before claim protection");
            var border=new BlockPos(region.minX()-1,110,region.minZ()+12);
            tree(level,border);
            level.setBlockAndUpdate(border.above(2).east(),Blocks.OAK_LOG.defaultBlockState());
            for(var direction:java.util.List.of(net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.UP))level.setBlockAndUpdate(border.above(2).east().relative(direction),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,false).setValue(LeavesBlock.DISTANCE,1));
            player.gameMode.destroyBlock(border);
            check(level.getBlockState(border).isAir() && level.getBlockState(border.above(2)).is(Blocks.OAK_LOG),"neutral chopping must not fell the whole tree");
            check(level.getBlockState(border.above(2).east()).is(Blocks.OAK_LOG),"bulk break crossed protected claim boundary");
            var plain=new BlockPos(2100,110,2100);
            for(int i=0;i<3;i++)level.setBlockAndUpdate(plain.above(i),Blocks.OAK_LOG.defaultBlockState());
            player.gameMode.destroyBlock(plain);
            check(level.getBlockState(plain.above(2)).is(Blocks.OAK_LOG),"logs without natural leaves treated as tree");
            var twilight=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse("twilightforest:twilight_forest")));
            check(twilight!=null,"Twilight Forest dimension missing");
            check(server.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet().stream().anyMatch(id->id.getNamespace().equals("towns_and_towers")),"Towns and Towers structures not registered");
            System.out.println("AOC_ADVENTURE_PROBE_PASS vanilla_chop=true durability=true foreign_root=true claim_boundary=true plain_logs=true twilight_dimension=true towns_structures=true");
            PersonalQuestProbe.schedule(server);
        } finally { GameIdentity.PLAYERS.remove(player.getUUID()); }
    }
    private static void tree(ServerLevel level,BlockPos root){
        level.setBlockAndUpdate(root.below(),Blocks.DIRT.defaultBlockState());
        for(int i=0;i<3;i++)level.setBlockAndUpdate(root.above(i),Blocks.OAK_LOG.defaultBlockState());
        for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL)level.setBlockAndUpdate(root.above(2).relative(direction),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,false).setValue(LeavesBlock.DISTANCE,1));
        level.setBlockAndUpdate(root.above(3),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,false).setValue(LeavesBlock.DISTANCE,1));
    }
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
