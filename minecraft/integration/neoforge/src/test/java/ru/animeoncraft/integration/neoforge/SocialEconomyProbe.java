package ru.animeoncraft.integration.neoforge;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Disposable two-client checks of real menus, real consent packets and third-party transactions. */
@Mod("aocsocialprobe")
public final class SocialEconomyProbe {
    private final boolean host="host".equals(System.getProperty("aoc.emoteProbeRole"));
    private final Path root=Path.of(System.getProperty("aoc.emoteProbeRoot"));
    private int phase,ticks,frames;private final long started=System.nanoTime();
    public SocialEconomyProbe(){if(!root.toAbsolutePath().toString().replace('\\','/').contains("/.tools/emote-multiplayer-"))throw new IllegalStateException("Disposable directory required");NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::tick);if(host)NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,(PlayerEvent.PlayerLoggedInEvent e)->{if(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p&&p.getGameProfile().getName().equals("AocObserver"))p.teleportTo(p.serverLevel(),.5,121,1.9,180,0);});}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private void signal(String name){try{Files.writeString(root.resolve(name+".ok"),"ok");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private boolean ready(String name){return Files.exists(root.resolve(name+".ok"));}
    private void shot(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{});}
    private void click(double x,double y){var s=Minecraft.getInstance().screen;check(s!=null,"Open menu");double k=Math.min(s.width/1100d,s.height/620d);s.mouseClicked((s.width-1100*k)/2+x*k,(s.height-620*k)/2+y*k,0);}
    private static boolean trading(){var p=Minecraft.getInstance().player;return p!=null&&p.containerMenu.getClass().getName().equals("com.securetrade.menu.TradeMenu");}
    private static void offer(){var mc=Minecraft.getInstance();int id=mc.player.containerMenu.containerId;mc.gameMode.handleInventoryMouseClick(id,81,0,ClickType.PICKUP,mc.player);mc.gameMode.handleInventoryMouseClick(id,0,0,ClickType.PICKUP,mc.player);}
    private static void lock(){var s=Minecraft.getInstance().screen;check(s!=null,"Trade screen");s.mouseClicked((s.width-356)/2d+70,(s.height-205)/2d+108,0);}
    private void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();ticks++;
        try{check(System.nanoTime()-started<540_000_000_000L,"Timeout phase="+phase);if(mc.player==null){if(mc.screen!=null&&ticks%30==0)for(var c:mc.screen.children())if(c instanceof net.minecraft.client.gui.components.Button b&&b.active&&Set.of("Proceed","I know what I'm doing!","Yes").contains(b.getMessage().getString()))b.onPress();return;}
            ClientEmotes.read();var server=mc.getSingleplayerServer();
            if(host){
                if(phase==0){server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.setGameMode(GameType.SURVIVAL);p.serverLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,server);for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++)p.serverLevel().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,120,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());p.teleportTo(p.serverLevel(),.5,121,.5,0,0);});phase=1;frames=0;}
                else if(phase==1&&++frames>40&&ClientEmotes.inventory()!=null){check(ClientEmotes.inventory().owned().size()==5,"Five starter emotes");ClientControls.audit(root.resolve("controls-audit.json"));check(ClientEmoteMenu.OPEN.getKey().getName().equals("key.keyboard.j"),"J default");for(var key:mc.options.keyMappings)if(key!=ClientEmoteMenu.OPEN)check(key.isUnbound()||!ClientEmoteMenu.OPEN.same(key),"Emote key conflict: "+key.getName());var before=ClientEmoteFavorites.all();ClientEmoteFavorites.toggle("HIGH_FIVE");check(ClientEmoteFavorites.contains("HIGH_FIVE"),"Favorite added");ClientEmoteFavorites.logout();check(ClientEmoteFavorites.contains("HIGH_FIVE"),"Favorite saved/reloaded");ClientEmoteFavorites.toggle("HIGH_FIVE");check(ClientEmoteFavorites.all().equals(before),"Favorite removed");server.setUsesAuthentication(false);check(server.publishServer(GameType.SURVIVAL,true,Integer.getInteger("aoc.emoteProbePort")),"LAN publish");signal("host-ready");net.minecraft.client.KeyMapping.click(ClientEmoteMenu.OPEN.getKey());phase=2;frames=0;}
                else if(phase==2&&++frames>30){check(mc.screen instanceof EmoteMenuScreen,"Quick key opens registered emote menu");shot("favorites");click(650,120);check(((EmoteMenuScreen)mc.screen).showsAll(),"Catalog tab");phase=3;frames=0;}
                else if(phase==3&&++frames>30&&ready("observer-ready")){check(ClientEmotes.current().isEmpty(),"Preview is local only");shot("emote-catalog");mc.setScreen(null);ClientEmotes.choose("HIGH_FIVE");phase=4;}
                else if(phase==4&&ready("five")){check(ClientEmotes.current().equals("HIGH_FIVE"),"Both partners play");ClientEmotes.choose("");phase=5;frames=0;}
                else if(phase==5&&++frames>80&&ready("five-stopped")){check(ClientEmotes.current().isEmpty(),"Stop cancels both");ClientEmotes.choose("HANDSHAKE");phase=6;frames=0;}
                else if(phase==6&&ready("denied")&&++frames>80){check(ClientEmotes.current().isEmpty(),"Deny never starts");ClientEmotes.choose("HANDSHAKE");phase=7;}
                else if(phase==7&&ready("handshake")){check(ClientEmotes.current().equals("HANDSHAKE"),"Handshake synchronized");server.execute(()->{var p=server.getPlayerList().getPlayers().stream().filter(v->v.getGameProfile().getName().equals("AocObserver")).findFirst().orElseThrow();p.hurt(p.damageSources().generic(),1);});phase=8;frames=0;}
                else if(phase==8&&++frames>25&&ready("pair-damage")){check(ClientEmotes.current().isEmpty(),"Damage cancels both");check(mc.options.getCameraType()==net.minecraft.client.CameraType.FIRST_PERSON,"Camera restored");server.submit(()->{try{economySetup(server);}catch(Exception e){throw new IllegalStateException(e);}return true;}).get(10,TimeUnit.SECONDS);signal("trade-request");phase=9;frames=0;}
                else if(phase==9&&trading()&&++frames>20){shot("secure-trade");offer();phase=10;frames=0;}
                else if(phase==10&&++frames>20){lock();phase=11;frames=0;}
                else if(phase==11&&!trading()&&ready("trade-complete")){server.submit(()->{try{economyVerify(server);}catch(Exception e){throw new IllegalStateException(e);}return true;}).get(10,TimeUnit.SECONDS);signal("host-done");phase=12;}
                else if(phase==12&&ready("observer-done")){phase=13;System.out.println("AOC_SOCIAL_HOST_PASS favorites=true preview=true consent=true pair_cancel=true economy=true trade=true");mc.stop();}
            }else{
                var other=mc.level.players().stream().filter(p->p.getGameProfile().getName().equals("AocWearer")).findFirst().orElse(null);if(other==null)return;
                if(phase==0&&ClientEmotes.inventory()!=null){check(ClientEmotes.inventory().owned().size()==5,"Observer starters");signal("observer-ready");phase=1;}
                else if(phase==1&&ClientEmotes.invitation()!=null){check(ClientEmotes.current().isEmpty()&&!ClientEmotes.playing(other.getUUID(),"HIGH_FIVE"),"Consent required");var i=ClientEmotes.invitation();net.neoforged.neoforge.network.PacketDistributor.sendToServer(new EmotePairPayloads.Request("ACCEPT",UUID.randomUUID(),i.code()));mc.setScreen(new EmoteMenuScreen("HIGH_FIVE",true));phase=2;frames=0;}
                else if(phase==2&&++frames>20){check(ClientEmotes.invitation()!=null,"Wrong sender rejected");check(ClientEmotes.current().isEmpty(),"Preview sends no play");shot("pair-invitation");click(790,555);phase=3;frames=0;}
                else if(phase==3&&ClientEmotes.current().equals("HIGH_FIVE")&&ClientEmotes.playing(other.getUUID(),"HIGH_FIVE")&&ClientEmotes.renderCount(other.getUUID(),"HIGH_FIVE")>12){shot("high-five");signal("five");phase=4;}
                else if(phase==4&&ClientEmotes.current().isEmpty()&&!ClientEmotes.playing(other.getUUID(),"HIGH_FIVE")){signal("five-stopped");phase=5;}
                else if(phase==5&&ClientEmotes.invitation()!=null){ClientEmotes.answer(false);signal("denied");phase=6;}
                else if(phase==6&&ClientEmotes.invitation()!=null){ClientEmotes.answer(true);phase=7;}
                else if(phase==7&&ClientEmotes.current().equals("HANDSHAKE")&&ClientEmotes.playing(other.getUUID(),"HANDSHAKE")&&ClientEmotes.renderCount(other.getUUID(),"HANDSHAKE")>12){shot("handshake");signal("handshake");phase=8;}
                else if(phase==8&&ClientEmotes.current().isEmpty()&&!ClientEmotes.playing(other.getUUID(),"HANDSHAKE")){check(mc.options.getCameraType()==net.minecraft.client.CameraType.FIRST_PERSON,"Observer camera restored");signal("pair-damage");phase=9;}
                else if(phase==9&&ready("trade-request")){mc.player.connection.sendCommand("trade accept");phase=10;frames=0;}
                else if(phase==10&&trading()&&++frames>20){offer();phase=11;frames=0;}
                else if(phase==11&&++frames>20){lock();phase=12;}
                else if(phase==12&&!trading()){signal("trade-complete");phase=13;}
                else if(phase==13&&ready("host-done")){signal("observer-done");System.out.println("AOC_SOCIAL_OBSERVER_PASS paired_remote=true consent=true trade=true");phase=14;mc.stop();}
            }
        }catch(Exception e){System.out.println("AOC_SOCIAL_FAILED role="+(host?"host":"observer")+" phase="+phase+" message="+e.getMessage());throw new IllegalStateException(e);}
    }
    private static void economySetup(net.minecraft.server.MinecraftServer server)throws Exception{
        var common=io.github.lightman314.lightmanscurrency.LCConfig.COMMON;check(!common.coinMintCanMint.get(),"Mint disabled in actual mod");check(!common.allowSpawnerEntityDrops.get()&&!common.allowFakePlayerCoinDrops.get(),"Automated coin farming disabled");check(!common.changeVanillaTrades.get(),"Vanilla villager trades preserved");
        var config=Class.forName("com.securetrade.TradeConfig");check(((Number)config.getField("MAX_TRADE_DISTANCE").get(null).getClass().getMethod("get").invoke(config.getField("MAX_TRADE_DISTANCE").get(null))).doubleValue()==16,"Actual server distance config");
        var a=server.getPlayerList().getPlayers().stream().filter(p->p.getGameProfile().getName().equals("AocWearer")).findFirst().orElseThrow();var b=server.getPlayerList().getPlayers().stream().filter(p->p!=a).findFirst().orElseThrow();a.getInventory().clearContent();b.getInventory().clearContent();a.getInventory().setItem(0,new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("lightmanscurrency:coin_copper")),7));b.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3));a.inventoryMenu.broadcastChanges();b.inventoryMenu.broadcastChanges();server.getCommands().performPrefixedCommand(a.createCommandSourceStack(),"trade AocObserver");
    }
    private static void economyVerify(net.minecraft.server.MinecraftServer server)throws Exception{
        var a=server.getPlayerList().getPlayers().stream().filter(p->p.getGameProfile().getName().equals("AocWearer")).findFirst().orElseThrow();var b=server.getPlayerList().getPlayers().stream().filter(p->p!=a).findFirst().orElseThrow();var coin=BuiltInRegistries.ITEM.get(ResourceLocation.parse("lightmanscurrency:coin_copper"));check(a.getInventory().countItem(Items.DIAMOND)==3&&a.getInventory().countItem(coin)==0&&b.getInventory().countItem(Items.DIAMOND)==0&&b.getInventory().countItem(coin)==7,"Actual network trade transfers exactly once");
        var type=Class.forName("com.securetrade.menu.TradeSession");var session=type.getConstructor(net.minecraft.server.level.ServerPlayer.class,net.minecraft.server.level.ServerPlayer.class).newInstance(a,b);var offered=(net.minecraft.world.SimpleContainer)type.getField("inventory1").get(session);offered.setItem(0,a.getInventory().removeItem(0,2));type.getMethod("onItemsChanged").invoke(session);type.getMethod("setLocked",net.minecraft.server.level.ServerPlayer.class,boolean.class).invoke(session,a,true);check(type.getField("player1Locked").getBoolean(session),"Offer locked");a.getInventory().add(offered.removeItem(0,1));type.getMethod("onItemsChanged").invoke(session);check(!type.getField("player1Locked").getBoolean(session),"Changing offer clears confirmation");type.getMethod("cancelTrade").invoke(session);type.getMethod("cancelTrade").invoke(session);check(a.getInventory().countItem(Items.DIAMOND)==3&&b.getInventory().countItem(coin)==7,"Cancellation retries return items exactly once");
        var pos=new net.minecraft.core.BlockPos(2,121,0);var block=BuiltInRegistries.BLOCK.stream().filter(v->v instanceof io.github.lightman314.lightmanscurrency.api.traders.blocks.TraderBlockBase&&BuiltInRegistries.BLOCK.getKey(v).getPath().startsWith("display_case_")).findFirst().orElseThrow();a.serverLevel().setBlockAndUpdate(pos,block.defaultBlockState());var be=(io.github.lightman314.lightmanscurrency.api.traders.blockentity.TraderBlockEntity<?>)a.serverLevel().getBlockEntity(pos);be.initialize(a,ItemStack.EMPTY);var trader=(io.github.lightman314.lightmanscurrency.common.traders.item.ItemTraderData)be.getTraderData();check(trader!=null,"Player shop initialized");b.getInventory().selected=8;
        var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false);var interaction=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(b,net.minecraft.world.InteractionHand.MAIN_HAND,pos,hit);check(PublicShopAccess.allowed(interaction),"Normal shopper access");b.setShiftKeyDown(true);check(!PublicShopAccess.allowed(interaction),"Storage interaction remains protected");b.setShiftKeyDown(false);
        trader.getTrade(0).setItem(new ItemStack(Items.BREAD),0);trader.getTrade(0).setCost(io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue.fromItemOrValue(coin,2,2));check(trader.getStorage().addItem(new ItemStack(Items.BREAD)),"Finite shop stock");var coinSlots=new net.minecraft.world.SimpleContainer(2);for(int slot=0;slot<b.getInventory().getContainerSize();slot++)if(b.getInventory().getItem(slot).is(coin))coinSlots.setItem(0,b.getInventory().removeItem(slot,7));var context=io.github.lightman314.lightmanscurrency.api.traders.TradeContext.create(trader,b,false).withCoinSlots(coinSlots).withItemHandler(new net.neoforged.neoforge.items.wrapper.InvWrapper(b.getInventory())).build();int bread=b.getInventory().countItem(Items.BREAD);var purchase=trader.TryExecuteTrade(context,0);check(purchase.isSuccess(),"Actual shop purchase: "+purchase);check(b.getInventory().countItem(Items.BREAD)==bread+1&&coinSlots.countItem(coin)==5,"Shop charges exact price");check(!trader.TryExecuteTrade(context,0).isSuccess(),"Empty stock cannot sell twice");check(b.getInventory().countItem(Items.BREAD)==bread+1&&coinSlots.countItem(coin)==5,"No charge or duplicate on empty stock");b.getInventory().add(coinSlots.removeItemNoUpdate(0));check(b.getInventory().countItem(coin)==5,"Remaining payment coins returned");
    }
}
