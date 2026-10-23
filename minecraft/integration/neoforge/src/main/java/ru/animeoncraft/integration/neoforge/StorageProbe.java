package ru.animeoncraft.integration.neoforge;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

final class StorageProbe {
    static void verify(ServerLevel level,TerritoryPermissions policy){
        if(!net.neoforged.fml.ModList.get().isLoaded("sophisticatedstorage"))return;
        var region=policy.territories().getFirst();var pos=new BlockPos(region.minX()+35,130,region.minZ()+35);
        var owner=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocStorageOwner"));var guest=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocStorageGuest"));
        owner.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);guest.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        GameIdentity.PLAYERS.put(owner.getUUID(),new GameIdentity(region.clan(),new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
        GameIdentity.PLAYERS.put(guest.getUUID(),new GameIdentity(null,new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
        try{
            for(var namespace:java.util.List.of("sophisticatedbackpacks","sophisticatedstorage","farmersdelight","copycats","create_furnitures"))
                check(BuiltInRegistries.ITEM.keySet().stream().anyMatch(id->id.getNamespace().equals(namespace)),"missing items "+namespace);
            int count=0;
            for(var id:java.util.List.of("sophisticatedstorage:chest","sophisticatedstorage:barrel","sophisticatedbackpacks:backpack","farmersdelight:cooking_pot")){
                var key=ResourceLocation.parse(id);check(BuiltInRegistries.BLOCK.containsKey(key),"missing block "+id);
                var block=BuiltInRegistries.BLOCK.get(key);level.setBlockAndUpdate(pos,block.defaultBlockState());
                check(level.getBlockEntity(pos)!=null,"expected container "+id);
                guest.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);owner.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                check(click(guest,pos).isCanceled(),"guest container access "+id);check(!click(owner,pos).isCanceled(),"member container access "+id);
                level.removeBlock(pos,false);count++;
            }
            System.out.println("AOC_STORAGE_PROBE_PASS containers="+count+" member_access=true foreign_access=false registries=true");
        }finally{GameIdentity.PLAYERS.remove(owner.getUUID());GameIdentity.PLAYERS.remove(guest.getUUID());}
    }
    private static PlayerInteractEvent.RightClickBlock click(FakePlayer p,BlockPos pos){var e=new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,pos,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));NeoForge.EVENT_BUS.post(e);return e;}
    private static void check(boolean value,String label){if(!value)throw new IllegalStateException(label);}
}
