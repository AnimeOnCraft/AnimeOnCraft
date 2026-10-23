package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Real ignition item calls, in the existing isolated territory fixture only. */
final class IgnitionProbe {
    static void verify(ServerLevel level, TerritoryPermissions policy) {
        var owner=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocFireOwner"));
        var guest=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocFireGuest"));
        owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);guest.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        try {
            for(var region:policy.territories()){
                GameIdentity.PLAYERS.put(owner.getUUID(),identity(region.clan()));
                GameIdentity.PLAYERS.put(guest.getUUID(),identity(ClanCodes.all().stream().filter(c->!c.equals(region.clan())).findFirst().orElseThrow()));
                var floor=new BlockPos(region.minX()+20,120,region.minZ()+20);
                for(var item:java.util.List.of(Items.FLINT_AND_STEEL,Items.FIRE_CHARGE)){
                    level.setBlockAndUpdate(floor,Blocks.NETHERRACK.defaultBlockState());level.removeBlock(floor.above(),false);
                    use(guest,floor,item);check(level.getBlockState(floor.above()).isAir(),"foreign ignition blocked");
                    use(owner,floor,item);check(level.getBlockState(floor.above()).is(Blocks.FIRE),"member ignition "+region.clan()+" "+item);
                    var other=floor.above().east();check(!level.setBlockAndUpdate(other,Blocks.FIRE.defaultBlockState()),"permission does not leak to natural fire");
                    level.removeBlock(floor.above(),false);
                }
                // A minimal real 4x5 obsidian frame, width along X.
                var origin=floor.offset(5,0,0);
                for(int x=0;x<4;x++)for(int y=0;y<5;y++)level.setBlockAndUpdate(origin.offset(x,y,0),(x==0||x==3||y==0||y==4)?Blocks.OBSIDIAN.defaultBlockState():Blocks.AIR.defaultBlockState());
                use(owner,origin.offset(1,0,0),Items.FLINT_AND_STEEL);
                check(level.getBlockState(origin.offset(1,1,0)).is(Blocks.NETHER_PORTAL),"member nether portal "+region.clan());
            }
            var spawn=policy.spawns().getFirst();var floor=new BlockPos(spawn.x(),120,spawn.z());
            level.setBlockAndUpdate(floor,Blocks.NETHERRACK.defaultBlockState());level.removeBlock(floor.above(),false);use(owner,floor,Items.FLINT_AND_STEEL);
            check(level.getBlockState(floor.above()).isAir(),"spawn ignition blocked");
            var neutral=new BlockPos(2200,120,2200);level.setBlockAndUpdate(neutral,Blocks.NETHERRACK.defaultBlockState());level.removeBlock(neutral.above(),false);use(guest,neutral,Items.FLINT_AND_STEEL);
            check(level.getBlockState(neutral.above()).is(Blocks.FIRE),"neutral ignition allowed");
            System.out.println("AOC_IGNITION_PROBE_PASS clans=5 member_flint=true member_fire_charge=true member_portal=true foreign_fire=false natural_fire=false spawn_fire=false neutral_fire=true");
        }finally{GameIdentity.PLAYERS.remove(owner.getUUID());GameIdentity.PLAYERS.remove(guest.getUUID());}
    }
    private static GameIdentity identity(String clan){return new GameIdentity(clan,new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID());}
    private static void use(FakePlayer p,BlockPos floor,Item item){p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item,2));p.gameMode.useItemOn(p,p.serverLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,0.5,0),Direction.UP,floor,false));}
    private static void check(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
