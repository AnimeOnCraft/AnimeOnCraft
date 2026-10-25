package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Invoked only after TerritoryProtectionProbe's dedicated loopback/world guard. */
final class PublicWaystoneProbe {
    private PublicWaystoneProbe() {}
    private static void check(boolean pass,String message) { if(!pass)throw new IllegalStateException(message); }
    private static PlayerInteractEvent.RightClickBlock click(FakePlayer player,BlockPos pos) {
        var event=new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,pos,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        NeoForge.EVENT_BUS.post(event);return event;
    }
    static void verify(MinecraftServer server) {
        var level=server.overworld();
        var guest=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocWaystoneGuest"));
        var creator=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocWaystoneOwner"));
        // An unauthenticated FakePlayer is otherwise treated as automation originating in its own region.
        creator.setPos(512,100,512);
        // Real mod activation checks negotiated channels even on FakePlayer. Keep these in-memory only.
        var guestChannel=new io.netty.channel.embedded.EmbeddedChannel(guest.connection.getConnection());
        var creatorChannel=new io.netty.channel.embedded.EmbeddedChannel(creator.connection.getConnection());
        guest.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        Object previous=null;java.lang.reflect.Field field=null;
        try {
            field=TerritoryProtection.class.getDeclaredField("policy");field.setAccessible(true);previous=field.get(null);
            String data="{\"revision\":\""+"b".repeat(64)+"\",\"world_id\":\"fixture\",\"regions\":[{\"code\":\"core\",\"kind\":\"CORE\",\"build_policy\":\"NONE\",\"polygon\":[[-32,-32],[32,-32],[32,32],[-32,32]]},{\"code\":\"state\",\"kind\":\"STATE\",\"clan_code\":\"SCAM\",\"build_policy\":\"MEMBERS\",\"polygon\":[[128,128],[256,128],[256,256],[128,256]]}]}";
            field.set(null,TerritoryPermissions.states(RegionPolicy.parse(com.google.gson.JsonParser.parseString(data).getAsJsonObject()),16));
            GameIdentity.PLAYERS.put(guest.getUUID(),new GameIdentity("NYA",new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
            var waystoneApi=Class.forName("net.blay09.mods.waystones.api.Waystone");
            var activation=Class.forName("net.blay09.mods.waystones.core.PlayerWaystoneManager").getMethod("isWaystoneActivated",Player.class,waystoneApi);
            int x=0;
            for(String id:new String[]{"waystones:waystone","waystones:mossy_waystone","waystones:sandy_waystone"}) {
                var pos=new BlockPos(x,100,0);x+=4;
                var block=BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(id));check(block!=Blocks.AIR,"Waystones missing");
                level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                var lower=block.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER);
                level.setBlockAndUpdate(pos,lower);level.setBlockAndUpdate(pos.above(),lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER));
                block.setPlacedBy(level,pos,lower,creator,new ItemStack(block));
                var be=level.getBlockEntity(pos);check(be!=null,"Waystone block entity missing");
                var waystone=be.getClass().getMethod("getWaystone").invoke(be);
                check(!(boolean)activation.invoke(null,guest,waystone),"Guest unexpectedly knows fresh stone");
                var held=id.equals("waystones:mossy_waystone")?new ItemStack(Items.DIAMOND_SWORD):ItemStack.EMPTY;
                guest.setItemInHand(InteractionHand.MAIN_HAND,held);
                var event=click(guest,pos);check(!event.isCanceled(),"Public activation cancelled: "+id);
                check(event.getUseItem()==net.neoforged.neoforge.common.util.TriState.FALSE,"Held item actions must remain disabled");
                check(!click(guest,pos.above()).isCanceled(),"Upper half blocked");
                var target=id.equals("waystones:sandy_waystone")?pos.above():pos;
                guest.gameMode.useItemOn(guest,level,held,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(target),Direction.UP,target,false));
                check((boolean)activation.invoke(null,guest,waystone),"Real Waystones activation failed: "+id);
                check(!guest.gameMode.destroyBlock(pos),"Guest can break public waystone");
                guest.setShiftKeyDown(true);check(click(guest,pos).isCanceled(),"Public editing allowed");guest.setShiftKeyDown(false);
                for(var dangerous:new net.minecraft.world.item.Item[]{Items.LAVA_BUCKET,Items.FLINT_AND_STEEL}) {
                    guest.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(dangerous));check(click(guest,pos).isCanceled(),"Dangerous item allowed");
                }
                guest.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                check(click(creator,pos).isCanceled(),"Unauthenticated activation allowed");
            }
            var chest=new BlockPos(12,100,0);level.setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());
            check(click(guest,chest).isCanceled(),"Public chest opened");
            var foreign=new BlockPos(140,100,140);
            level.setBlockAndUpdate(foreign,Blocks.STONE.defaultBlockState());
            check(!guest.gameMode.destroyBlock(foreign),"Foreign claim broken");
            System.out.println("AOC_PUBLIC_WAYSTONE_PROBE_PASS activation=3 upper_half=true block_edits_denied=true containers_denied=true dangerous_items_denied=true editing_denied=true admission_required=true");
        } catch(Exception failure) {
            System.out.println("AOC_TERRITORY_PROBE_FAILED public_waystones "+failure.getClass().getSimpleName()+": "+failure.getMessage());
            throw new IllegalStateException("Public waystone probe failed",failure);
        } finally {
            guestChannel.finishAndReleaseAll();creatorChannel.finishAndReleaseAll();
            GameIdentity.PLAYERS.remove(guest.getUUID());
            try {if(field!=null&&previous!=null)field.set(null,previous);}catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
        }
    }
}
