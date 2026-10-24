package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/** Private full-modpack fixture, excluded from the distributed mod. */
@Mod("aocmarketprobe")
public final class ClanMarketProbe {
    public ClanMarketProbe(){NeoForge.EVENT_BUS.addListener(this::started);}
    private static void check(boolean value,String text){if(!value)throw new IllegalStateException(text);}
    private static PlayerInteractEvent.RightClickBlock use(FakePlayer player,BlockPos pos){
        return new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,pos,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
    }
    private void started(ServerStartedEvent event){
        if(!Boolean.getBoolean("animeoncraft.marketProbe"))return;
        var server=event.getServer();
        if(server.getPort()!=25569||!"127.0.0.1".equals(server.getLocalIp())||!server.getWorldData().getLevelName().startsWith("aoc-clan-market-probe-"))throw new IllegalStateException("Own loopback world required");
        var level=server.overworld();
        var member=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocMarketOwner"));
        var foreign=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocMarketOther"));
        try{
            WorldLayout.load(Path.of("config/market-layout.json"));
            TerritoryProtection.starting(new ServerStartingEvent(server));
            var regions=WorldRegions.current();check(regions!=null&&TerritoryProtection.statesWorld(),"Cached states catalog loaded through production startup");
            var plots=regions.regions().stream().filter(RegionPolicy.Region::market).toList();check(plots.size()==5,"All five plots");
            member.setGameMode(GameType.SURVIVAL);foreign.setGameMode(GameType.SURVIVAL);
            for(var plot:plots){
                String other=plots.stream().map(RegionPolicy.Region::clan).filter(c->!c.equals(plot.clan())).findFirst().orElseThrow();
                GameIdentity.PLAYERS.put(member.getUUID(),new GameIdentity(plot.clan(),new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
                GameIdentity.PLAYERS.put(foreign.getUUID(),new GameIdentity(other,new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
                ClanControlSync.accept(Map.of(plot.clan(),other)); // their distant state is occupied by the other clan
                var wars=new com.google.gson.JsonArray();var war=new com.google.gson.JsonObject();
                war.addProperty("id",UUID.randomUUID().toString());war.addProperty("attacker",other);war.addProperty("defender",plot.clan());
                war.addProperty("starts_at",Instant.now().minusSeconds(60).toString());war.addProperty("ends_at",Instant.now().plusSeconds(600).toString());wars.add(war);WarWindows.replace(wars);
                check(WarWindows.canRaid(plot.clan(),other),"Actual raid window active");
                var pos=new BlockPos((plot.minX()+plot.maxX())/2,100,(plot.minZ()+plot.maxZ())/2);
                member.setPos(pos.getX()+.5,101,pos.getZ()+.5);foreign.setPos(pos.getX()+.5,101,pos.getZ()+.5);
                level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
                check(!foreign.gameMode.destroyBlock(pos)&&level.getBlockState(pos).is(Blocks.STONE),"Foreign occupied-state controller cannot raid market "+plot.clan());
                check(member.gameMode.destroyBlock(pos)&&level.getBlockState(pos).isAir(),"Original clan rebuilds its market "+plot.clan());
                level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                var snapshot=net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(),level,pos);
                var rejected=new net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent(snapshot,Blocks.STONE.defaultBlockState(),foreign);
                NeoForge.EVENT_BUS.post(rejected);check(rejected.isCanceled(),"Foreign placement denied");
                var accepted=new net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent(snapshot,Blocks.STONE.defaultBlockState(),member);
                NeoForge.EVENT_BUS.post(accepted);check(!accepted.isCanceled(),"Member placement allowed");
                member.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);foreign.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                for(var block:List.of(Blocks.CHEST,Blocks.BARREL,Blocks.PURPLE_SHULKER_BOX,Blocks.HOPPER)){
                    level.setBlockAndUpdate(pos,block.defaultBlockState());
                    var theirs=use(foreign,pos);NeoForge.EVENT_BUS.post(theirs);check(theirs.isCanceled(),"Foreign storage denied "+block);
                    var ours=use(member,pos);NeoForge.EVENT_BUS.post(ours);check(!ours.isCanceled(),"Member storage allowed "+block);
                }
                check(TerritoryProtection.hostilePlayerEffectDenied(member,foreign)&&TerritoryProtection.hostilePlayerEffectDenied(foreign,member),"No PvP either way even during a raid");
                var edge=new BlockPos(plot.minX(),100,plot.minZ()+2);
                check(!TerritoryProtection.canTransfer(level,edge.west(),edge),"Automation cannot cross market edge");
                check(TerritoryProtection.canTransfer(level,edge,edge.east()),"Automation works inside a plot");
                var explosion=new net.minecraft.world.level.Explosion(level,null,pos.getX(),100,pos.getZ(),4,false,net.minecraft.world.level.Explosion.BlockInteraction.DESTROY);
                explosion.getToBlow().add(pos);NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.ExplosionEvent.Detonate(level,explosion,new java.util.ArrayList<>()));
                check(explosion.getToBlow().isEmpty(),"Explosions preserve market blocks");
                var core=new BlockPos(0,100,0);level.setBlockAndUpdate(core,Blocks.STONE.defaultBlockState());check(!member.gameMode.destroyBlock(core),"Plaza still protected");
                level.setBlockAndUpdate(pos,net.minecraft.core.registries.BuiltInRegistries.BLOCK.stream().filter(b->b instanceof io.github.lightman314.lightmanscurrency.api.traders.blocks.TraderBlockBase
                    &&net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath().startsWith("display_case_")).findFirst().orElseThrow().defaultBlockState());
                var trader=(io.github.lightman314.lightmanscurrency.api.traders.blockentity.TraderBlockEntity<?>)level.getBlockEntity(pos);
                trader.initialize(member,ItemStack.EMPTY);check(trader.getTraderData()!=null,"Real Lightman's Currency shop initialized");
                var shopping=use(foreign,pos);check(PublicShopAccess.allowed(shopping),"Customer mode allowed");NeoForge.EVENT_BUS.post(shopping);check(!shopping.isCanceled(),"Customer access survives protection");
                foreign.setShiftKeyDown(true);var storage=use(foreign,pos);NeoForge.EVENT_BUS.post(storage);check(storage.isCanceled(),"Shift storage stays protected");foreign.setShiftKeyDown(false);
                foreign.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE));var held=use(foreign,pos);NeoForge.EVENT_BUS.post(held);check(held.isCanceled(),"Held-item administrative access denied");foreign.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            }
            ClanControlSync.accept(Map.of());
            var atlas=WorldAtlas.snapshot();check(atlas!=null&&atlas.points().stream().filter(p->p.kind().equals("MARKET")).count()==5,"All five native map markers");
            check(RegionMapPayload.of(regions).regions().size()==10,"All state and market outlines in one payload");
            check(server.getCommands().getDispatcher().execute("aocmarket",server.createCommandSourceStack().withSuppressedOutput())==5,"Discovery command lists all five plots");
            Files.writeString(Path.of("clan-market-probe-result.json"),"{\"passed\":true,\"plots\":5,\"war_and_controller_isolation\":true,\"build_and_storage_events\":true,\"public_shop_access\":true,\"pvp_denied\":true,\"automation_edges\":true,\"explosion_protection\":true,\"plaza_protection\":true,\"native_map_payload\":true}");
            System.out.println("AOC_CLAN_MARKET_PROBE_PASS");
        }catch(Throwable failure){failure.printStackTrace();System.out.println("AOC_CLAN_MARKET_PROBE_FAILED "+failure.getMessage());}
        finally{GameIdentity.PLAYERS.remove(member.getUUID());GameIdentity.PLAYERS.remove(foreign.getUUID());ClanControlSync.reset();WarWindows.load();}
    }
}
