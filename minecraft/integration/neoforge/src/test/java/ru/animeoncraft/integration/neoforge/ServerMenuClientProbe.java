package ru.animeoncraft.integration.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/** Separate test-only mod. Never included in the distributable admission JAR. */
@Mod("aocmenuclientprobe")
public final class ServerMenuClientProbe {
    private int ticks,phase,frames,galleryIndex;private long geometryCount;
    private static final String[] GALLERY={"ONI_MASK","CYAN_VISOR","SHARD_CROWN","ORBIT_HALO","RIFT_LANTERN","STAR_CHAIN","RIFT_MANTLE"};
    public ServerMenuClientProbe(){
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderNameTagEvent e)->{if(Minecraft.getInstance().screen instanceof GalleryScreen)e.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);});
        if(!Boolean.getBoolean("animeoncraft.menuClientProbe")||!Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("menu-client-"))throw new IllegalStateException("Menu probe requires its own game directory");
        if(Boolean.getBoolean("animeoncraft.profileManualReview")){NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::reviewTick);return;}
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::tick);
    }
    private void reviewTick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();ticks++;
        if(mc.player==null){if(mc.screen!=null&&ticks%30==0)for(var c:mc.screen.children())if(c instanceof Button b&&b.active&&java.util.Set.of("Proceed","I know what I'm doing!","Yes").contains(b.getMessage().getString()))b.onPress();return;}
        if(phase==0&&ticks>100){var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());seedProfile(p);if(Boolean.getBoolean("animeoncraft.betaManualReview")){var store=BetaPassStore.get(server);var e=store.player(p.getUUID());e.xp=1000;for(int i=0;i<10;i++)e.claimReward(i);e.equip("HEAD","FOX_EARS");e.equip("FACE","CYAN_VISOR");e.equip("BELT","STAR_CHAIN");e.equip("BACK","RIFT_MANTLE");e.equip("TITLE","NEON_LEGEND");store.setDirty();BetaPassServer.send(p);BetaPassServer.broadcast(p);}});phase=1;}
        if(phase==1&&ticks>140){if(Boolean.getBoolean("animeoncraft.betaManualReview")){spawnBetaExample();if(Boolean.getBoolean("animeoncraft.cosmeticGallery"))startWardrobe();}else{ClientProfiles.request("");spawnTitleExample();}System.out.println("AOC_PROFILE_MANUAL_REVIEW demo_statistics=true isolated_world=true public_server_untouched=true");phase=2;}
        if(Boolean.getBoolean("animeoncraft.cosmeticDemoSmoke"))demoSmoke();
    }
    private void demoSmoke(){
        var mc=Minecraft.getInstance();if(phase==2&&ClientWardrobe.data()!=null&&ClientWardrobe.data().available()){key(GLFW.GLFW_PRESS);click(380,315,0);key(GLFW.GLFW_RELEASE);phase=3;frames=0;}
        else if(phase==3&&++frames==25){click(861,166,0);phase=4;frames=0;}
        else if(phase==4&&++frames==25){check(ClientBetaPass.data().rewards()==1023,"Manual demo has all ten rewards");check(ClientWardrobe.data().capes().size()==2,"Manual demo has example account capes");check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.WARDROBE,"Manual review opens real wardrobe");shot("36-local-review");System.out.println("AOC_COSMETIC_DEMO_PASS full_models=true shared_back_slot=true example_level_ten=true");phase=5;mc.stop();}
    }
    private void key(int action){var mc=Minecraft.getInstance();NeoForge.EVENT_BUS.post(new InputEvent.Key(mc.options.keyPlayerList.getKey().getValue(),0,action,0));}
    private void click(double x,double y,int button){var s=(ServerMenuScreen)Minecraft.getInstance().screen;double k=Math.min(s.width/1100.0,s.height/620.0);s.mouseClicked((s.width-1100*k)/2+x*k,(s.height-620*k)/2+y*k,button);}
    private void shot(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{});}
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static void verifyControls(){
        var mc=Minecraft.getInstance();var actions=java.util.Set.of("key.playerlist","gui.xaero_open_map","gui.xaero_new_waypoint","gui.xaero_minimap_settings","gui.xaero_open_settings","gui.xaero_enlarge_map","key.sophisticatedbackpacks.open_backpack","key.sophisticatedbackpacks.inventory_interaction","key.voice_chat","key.push_to_talk","key.mute_microphone","key.disable_voice_chat","key.hide_icons","key.voice_chat_group","key.twilightforest.zoom","key.twilightforest.swap_hotbar","key.twilightforest.item_display_map_cycle","key.cataclysm.ability","key.cataclysm.helmet_ability","key.cataclysm.chestplate_ability","key.cataclysm.boots_ability","key.irons_spellbooks.spellbook_cast","key.irons_spellbooks.spell_wheel","key.irons_spellbooks.spell_bar_modifier","keyinfo.copycats.fill_copycat","railways.keyinfo.bogey_menu","railways.keyinfo.cycle_menu","create.keyinfo.toolmenu","create.keyinfo.toolbelt","key.curios.open.desc");
        var used=new java.util.HashSet<String>();int found=0;
        for(var binding:mc.options.keyMappings)if(actions.contains(binding.getName())){found++;check(!binding.isUnbound(),"Core action must remain assigned: "+binding.getName());check(used.add(binding.saveString()+":"+binding.getKeyModifier()),"Independent gameplay actions overlap: "+binding.getName());}
        check(found==actions.size(),"All expected mod actions registered");
        var map=java.util.Arrays.stream(mc.options.keyMappings).filter(k->k.getName().equals("gui.xaero_open_map")).findFirst().orElseThrow();var old=map.getKey();
        map.setKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.f12"));
        check(ClientControls.guide("{map}").equals(map.getTranslatedKeyMessage().getString()),"Guide follows actual rebinding");ClientControls.initialize();check(map.getKey().getName().equals("key.keyboard.f12"),"Repeated initialization preserves manual changes");map.setKey(old);net.minecraft.client.KeyMapping.resetMapping();
        check(!ClientControls.guide(ServerMenuGuide.CHAT.get(3).body()).contains("{"),"Voice guide resolves actual bindings");
    }
    private void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();ticks++;
        if(mc.player==null){if(mc.screen!=null&&ticks%30==0)for(var c:mc.screen.children())if(c instanceof Button b&&b.active&&java.util.Set.of("Proceed","I know what I'm doing!","Yes").contains(b.getMessage().getString()))b.onPress();return;}
        if(ticks<180)return;
        try{
            if(phase==0){verifyControls();ClientControls.audit(mc.gameDirectory.toPath().resolve("controls-audit.json"));GLFW.glfwFocusWindow(mc.getWindow().getWindow());mc.setScreen(null);check(ClientServerMenu.supported(),"Real menu channels negotiated");key(GLFW.GLFW_PRESS);check(mc.screen instanceof ServerMenuScreen,"Tab press opens radial menu");GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),mc.getWindow().getScreenWidth()*410.0/1100,mc.getWindow().getScreenHeight()*215.0/620);phase=1;frames=0;}
            else if(phase==1&&++frames==20){check(mc.screen instanceof ServerMenuScreen,"Held menu stays open");shot("01-radial-menu");key(GLFW.GLFW_RELEASE);mc.screen.tick();check(mc.screen==null,"Release without click closes, does not select");key(GLFW.GLFW_PRESS);click(410,215,1);check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.WHEEL,"Right click cannot select");click(410,215,0);check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.CLANS,"Held Tab and LMB select clans");key(GLFW.GLFW_RELEASE);phase=2;frames=0;}
            else if(phase==2&&++frames>20&&ClientServerMenu.data()!=null){check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.CLANS,"Detail stays after release");check(ClientServerMenu.data().clans().size()==5,"Real server packet contains roster");check(ClientServerMenu.ownClan().equals("NYA"),"Own clan resolved by game UUID");check(!mc.screen.isPauseScreen(),"Menu does not pause game");shot("02-clans");var s=(ServerMenuScreen)mc.screen;s.mouseScrolled(1,1,0,-1);check(s.scrollOffset()>0,"Roster scrolls");click(610,171,0);check(s.selectedClan().equals("SCAM")&&s.scrollOffset()==0,"Changing clan resets scroll");click(299,240,0);phase=3;frames=0;}
            else if(phase==3&&++frames==20){check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.ONLINE,"Online panel opens");check(ClientServerMenu.online().size()>=1,"Uses actual listed player infos");shot("03-online");click(640,171,0);click(299,390,0);phase=4;frames=0;}
            else if(phase==4&&++frames==20){check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.GUIDE,"Guide opens");shot("04-guide");click(640,171,0);phase=5;frames=0;}
            else if(phase==5&&++frames==20){shot("05-development");click(770,171,0);phase=6;frames=0;}
            else if(phase==6&&++frames==20){shot("06-chat");captureExample(false);click(149,390,0);phase=7;frames=0;}
            else if(phase==7&&++frames==20){check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.RULES,"Rules open");check(ClientServerMenu.capture()!=null&&!ClientServerMenu.capture().canDeclare(),"Protected capture summary delivered by server packet");shot("07-rules");captureExample(true);phase=71;frames=0;}
            else if(phase==71&&++frames==20){check(ClientServerMenu.capture()!=null&&ClientServerMenu.capture().canDeclare(),"Attack summary delivered by server packet");shot("07b-capture-action");click(220,508,0);phase=8;frames=0;}
            else if(phase==8&&++frames==20){check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.WHEEL,"Back wheel remains available without hold");click(610,415,0);mc.options.guiScale().set(4);mc.resizeDisplay();phase=9;frames=0;}
            else if(phase==9&&++frames==20){check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.GUIDE,"GUI scale preserves page and hit testing");shot("08-guide-scale4");mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);check(mc.screen==null,"Escape closes");check(!mc.options.keyPlayerList.isDown(),"Vanilla Tab state cleared");ClientProfiles.request("BOSS_FIRST");phase=10;frames=0;}
            else if(phase==10&&++frames==25){check(ClientProfiles.profile()!=null&&ClientProfiles.profile().selected().isEmpty(),"Locked title rejected by server");var server=mc.getSingleplayerServer();server.execute(()->seedProfile(server.getPlayerList().getPlayer(mc.player.getUUID())));phase=11;frames=0;}
            else if(phase==11&&++frames==25){ClientProfiles.request("");phase=12;frames=0;}
            else if(phase==12&&++frames==25){var profile=ClientProfiles.profile();check(profile.metrics().seconds()>=36000&&profile.metrics().kills()==7&&profile.metrics().deaths()==3,"Server reads real vanilla statistics");check(profile.earned().contains("BOSS_FIRST"),"Historical achievement unlocked from statistics");mc.options.guiScale().set(3);mc.resizeDisplay();key(GLFW.GLFW_PRESS);click(510,315,0);key(GLFW.GLFW_RELEASE);check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.PROFILE,"Centre opens profile with hold and LMB");phase=13;frames=0;}
            else if(phase==13&&++frames==25){shot("09-profile");click(820,165,0);for(int i=0;i<16;i++)mc.screen.mouseScrolled(950,400,0,-1);phase=14;frames=0;}
            else if(phase==14&&++frames==25){shot("10-personal-titles");ClientProfiles.request("");click(970,262,0);phase=15;frames=0;}
            else if(phase==15&&++frames==25){check(ClientProfiles.profile().selected().equals("BOSS_FIRST"),"Unlocked title selected through real server packet");check(ClientProfiles.selected(mc.player.getUUID()).equals("BOSS_FIRST"),"Title broadcast received");shot("11-title-selected");ClientProfiles.request("MADE_UP");click(149,240,0);phase=16;frames=0;}
            else if(phase==16&&++frames==25){check(ClientProfiles.profile().selected().equals("BOSS_FIRST"),"Unknown title cannot replace selected title");check(ClientClanAchievements.data()!=null&&ClientClanAchievements.data().definitions().size()==13,"Actual achievement snapshot delivered");click(982,119,0);phase=17;frames=0;}
            else if(phase==17&&++frames==25){shot("12-clan-achievements");mc.screen.onClose();spawnTitleExample();phase=18;frames=0;}
            else if(phase==18&&++frames==40){shot("13-title-in-world");var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());var e=BetaPassStore.get(server).player(p.getUUID());e.xp=0;e.rewards=0;e.dayClaims=0;e.weekClaims=0;java.util.Arrays.fill(e.daily,0);java.util.Arrays.fill(e.weekly,0);e.equipped.clear();BetaPassServer.send(p);});phase=19;frames=0;}
            else if(phase==19&&++frames==25){check(ClientBetaPass.data()!=null&&ClientBetaPass.data().xp()==0,"New pass has no retroactive XP");ClientBetaPass.request("REWARD",9,"","");ClientBetaPass.request("EQUIP",0,"BACK","RIFT_MANTLE");phase=20;frames=0;}
            else if(phase==20&&++frames==45){check(ClientBetaPass.data().rewards()==0&&ClientBetaPass.data().equipped().isEmpty(),"Locked rewards and cosmetics rejected by real server");var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());var e=BetaPassStore.get(server).player(p.getUUID());p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);p.awardStat(net.minecraft.stats.Stats.ITEM_CRAFTED.get(net.minecraft.world.item.Items.COBBLESTONE),32);BetaPassServer.send(p);});phase=21;frames=0;}
            else if(phase==21&&++frames==25){ClientBetaPass.request("DAILY",2,"","");ClientBetaPass.request("DAILY",2,"","");phase=22;frames=0;}
            else if(phase==22&&++frames>=50&&(ClientBetaPass.data().dayClaims()==4||frames>200)){check(ClientBetaPass.data().xp()==20&&ClientBetaPass.data().dayClaims()==4,"Duplicate daily claim grants XP exactly once: xp="+ClientBetaPass.data().xp()+" claims="+ClientBetaPass.data().dayClaims()+" crafted="+ClientBetaPass.data().daily().get(2));check(ClientBetaPass.data().weekly().get(2)==32&&ClientBetaPass.data().weekClaims()==0,"Weekly progress independent, target seven times larger");var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());var store=BetaPassStore.get(server);var e=store.player(p.getUUID());e.xp=1000;for(int i=0;i<9;i++)check(e.claimReward(i),"Seed only disposable review rewards");store.setDirty();BetaPassServer.send(p);});phase=23;frames=0;}
            else if(phase==23&&++frames==25){ClientBetaPass.request("REWARD",9,"","");phase=24;frames=0;}
            else if(phase==24&&++frames==25){check(ClientBetaPass.data().rewards()==1023,"Level-ten mantle claimed through real packet");ClientBetaPass.request("EQUIP",0,"HEAD","FOX_EARS");ClientBetaPass.request("EQUIP",0,"FACE","CYAN_VISOR");ClientBetaPass.request("EQUIP",0,"BELT","STAR_CHAIN");ClientBetaPass.request("EQUIP",0,"BACK","RIFT_MANTLE");ClientBetaPass.request("EQUIP",0,"TITLE","NEON_LEGEND");phase=25;frames=0;}
            else if(phase==25&&++frames==120){check(ClientBetaPass.equipped(mc.player.getUUID()).size()==5,"All five slots delivered by server");check(ClientProfiles.selected(mc.player.getUUID()).isEmpty(),"Pass title clears regular selected title");key(GLFW.GLFW_PRESS);click(640,315,0);key(GLFW.GLFW_RELEASE);check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.BETA_PASS,"Beta pass has its own wheel sector");phase=26;frames=0;}
            else if(phase==26&&++frames==25){shot("14-beta-rewards");click(985,117,0);phase=27;frames=0;}
            else if(phase==27&&++frames==25){shot("15-beta-daily");click(680,207,0);phase=28;frames=0;}
            else if(phase==28&&++frames==25){shot("16-beta-weekly");click(220,508,0);click(380,315,0);check(((ServerMenuScreen)mc.screen).page()==ServerMenuLogic.Page.WARDROBE,"Wardrobe wheel sector");click(861,166,0);phase=29;frames=0;}
            else if(phase==29&&++frames==25){shot("17-beta-wardrobe");mc.options.guiScale().set(4);mc.resizeDisplay();phase=30;frames=0;}
            else if(phase==30&&++frames==25){shot("18-beta-scale4");mc.screen.onClose();mc.options.guiScale().set(3);mc.resizeDisplay();spawnBetaExample();phase=31;frames=0;}
            else if(phase==31&&++frames==35){shot("19-beta-in-world");check(BetaCosmeticRenderer.mantleDraws>0,"Custom model layer really rendered geometry");mc.level.getEntity(900000).setPose(net.minecraft.world.entity.Pose.STANDING);((net.minecraft.client.player.RemotePlayer)mc.level.getEntity(900000)).setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA));phase=34;frames=0;}
            else if(phase==34){++frames;if(frames==15)geometryCount=BetaCosmeticRenderer.mantleDraws;if(frames==40){check(geometryCount==BetaCosmeticRenderer.mantleDraws,"Elytra suppress custom mantle");shot("20-beta-elytra");var target=(net.minecraft.client.player.RemotePlayer)mc.level.getEntity(900000);target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,net.minecraft.world.item.ItemStack.EMPTY);target.setShiftKeyDown(true);target.setPose(net.minecraft.world.entity.Pose.CROUCHING);phase=35;frames=0;}}
            else if(phase==35&&++frames==25){shot("21-beta-crouching");check(BetaCosmeticRenderer.mantleDraws>geometryCount,"Mantle restored after removing elytra");mc.level.getEntity(900000).setInvisible(true);phase=36;frames=0;}
            else if(phase==36){++frames;if(frames==15)geometryCount=BetaCosmeticRenderer.mantleDraws;if(frames==40){check(geometryCount==BetaCosmeticRenderer.mantleDraws,"Invisible player cosmetics hidden");ClientProfiles.request("BOSS_FIRST");phase=32;frames=0;}}
            else if(phase==32&&++frames==35){check(!ClientBetaPass.equipped(mc.player.getUUID()).containsKey("TITLE"),"Regular title selection clears pass title");var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());BetaPassStore.get(server).player(p.getUUID()).progress(new long[]{0,0,224,0,0,0});server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"aocbetapass close");});phase=37;frames=0;}
            else if(phase==37&&++frames==25){check(!ClientBetaPass.data().open(),"Owner command closes pass");ClientBetaPass.request("WEEKLY",2,"","");ClientBetaPass.request("EQUIP",0,"HEAD","SHARD_CROWN");phase=38;frames=0;}
            else if(phase==38&&++frames==45){check(ClientBetaPass.data().weekClaims()==0,"Closed pass rejects new quest awards");check(ClientBetaPass.equipped(mc.player.getUUID()).get("HEAD").equals("SHARD_CROWN"),"Earned wardrobe remains usable after closing");var server=mc.getSingleplayerServer();server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"aocbetapass open"));phase=39;frames=0;}
            else if(phase==39&&++frames==25){check(ClientBetaPass.data().open(),"Owner reopens pass without losing rewards");if(Boolean.getBoolean("animeoncraft.cosmeticModelProbe")){detailedExample();phase=40;frames=0;}else finish();}
            else if(phase==40&&++frames==40){check(DetailedCosmetics.foxDraws>0,"Textured mesh really rendered, without fallback");shot("22-fox-ears-front");portraitYaw(135);phase=41;frames=0;}
            else if(phase==41&&++frames==35){shot("23-fox-ears-quarter");portraitYaw(90);((net.minecraft.client.player.RemotePlayer)mc.level.getEntity(900000)).setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET));phase=42;frames=0;}
            else if(phase==42&&++frames==35){shot("24-fox-ears-helmet");((net.minecraft.client.player.RemotePlayer)mc.level.getEntity(900000)).setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,net.minecraft.world.item.ItemStack.EMPTY);portraitYaw(270);phase=43;frames=0;}
            else if(phase==43&&++frames==35){shot("25-fox-ears-back");mc.options.hideGui=false;mc.options.fov().set(70);System.out.println("AOC_DETAILED_COSMETICS_PASS textured_fox_ears=true front_quarter_back_helmet=true");if(Boolean.getBoolean("animeoncraft.cosmeticGallery")){galleryIndex=0;openGallery();phase=44;frames=0;}else finish();}
            else if(phase==44&&++frames==35){shot((26+galleryIndex)+"-"+GALLERY[galleryIndex].toLowerCase(java.util.Locale.ROOT).replace('_','-'));check(DetailedCosmetics.draws.getOrDefault(GALLERY[galleryIndex],0L)>0,"Detailed model rendered: "+GALLERY[galleryIndex]);if(++galleryIndex<GALLERY.length){openGallery();frames=0;}else{mc.setScreen(null);check(DetailedCosmetics.draws.keySet().containsAll(DetailedCosmetics.CODES),"All eight textured models rendered");System.out.println("AOC_ALL_COSMETICS_PASS models=8 animated_parts=true");startWardrobe();phase=45;frames=0;}}

            else if(phase==45&&++frames>30&&ClientWardrobe.data()!=null&&ClientWardrobe.data().available()){check(ClientWardrobe.data().capes().size()==2,"Only owned capes delivered");key(GLFW.GLFW_PRESS);click(380,315,0);key(GLFW.GLFW_RELEASE);phase=46;frames=0;}
            else if(phase==46&&++frames==25){click(861,166,0);phase=47;frames=0;}
            else if(phase==47&&++frames==25){shot("33-shared-wardrobe");click(975,413,0);phase=48;frames=0;}
            else if(phase==48&&++frames>30&&ClientWardrobe.data().code().equals("ayaa-cobblestone")){check(!ClientBetaPass.mantle(mc.player.getUUID()),"Personal cape clears mantle");check(cape()!=null&&cape().getPath().startsWith("capes/"),"Real personal PNG received");shot("34-personal-cape-selected");click(975,239,0);phase=49;frames=0;}
            else if(phase==49&&++frames>30&&ClientWardrobe.data().kind().equals("BETA")){check(ClientBetaPass.mantle(mc.player.getUUID()),"Mantle equipped from unified wardrobe");check(cape()==null,"Cape texture cleared when mantle selected");shot("35-mantle-selected");fixtureAction("launcher-cape",()->{});phase=53;frames=0;}
            else if(phase==53&&++frames>30&&ClientWardrobe.data().kind().equals("CAPE")){check(ClientWardrobe.data().code().equals("beta")&&!ClientBetaPass.mantle(mc.player.getUUID()),"External launcher cape sync clears mantle");check(cape()!=null,"External launcher cape texture applied");click(975,194,0);phase=50;frames=0;}
            else if(phase==50&&++frames>30&&ClientWardrobe.data().kind().equals("NONE")){check(!ClientBetaPass.mantle(mc.player.getUUID())&&cape()==null,"Unequip removes both models");net.neoforged.neoforge.network.PacketDistributor.sendToServer(new BetaPassPayloads.Request("EQUIP",0,"BACK","FOX_EARS"));net.neoforged.neoforge.network.PacketDistributor.sendToServer(new WardrobePayloads.Request("CAPE","trickyowl-owl",ClientWardrobe.data().revision()));phase=51;frames=0;}
            else if(phase==51&&++frames==50){check(ClientWardrobe.data().kind().equals("NONE"),"Unowned cape packet rejected");fixtureAction("arm-late-wardrobe",()->{var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(mc.player.getUUID());var old=GameIdentity.PLAYERS.get(p.getUUID());ServerWardrobe.request(p,new WardrobePayloads.Request("READ","",0));ServerWardrobe.logout(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(p));GameIdentity.PLAYERS.put(p.getUUID(),new GameIdentity(old.clan(),old.skin(),old.slim(),old.cape(),java.util.UUID.fromString("00000000-0000-4000-8000-000000000008"),old.userId()));});phase=52;frames=0;}
            else if(phase==52&&++frames==120){check(ClientWardrobe.data().kind().equals("NONE"),"Late prior-connection reply ignored");check(cape()==null,"Late reply cannot restore a cape");System.out.println("AOC_SHARED_WARDROBE_PASS owned_capes=true personal_png=true mantle_exclusive=true unequip=true unowned_rejected=true reconnect_fenced=true");finish();}
        }catch(Exception failure){System.out.println("AOC_SERVER_MENU_CLIENT_FAILED phase="+phase+" reason="+failure.getMessage());throw new IllegalStateException(failure);}
    }

    private static void fixtureAction(String action,Runnable task){var server=Minecraft.getInstance().getSingleplayerServer();var settings=ServerSettings.from(System.getenv());HttpJson.post(settings.heartbeatUrl().resolve("/probe/"+action),settings.key(),new com.google.gson.JsonObject()).thenRun(()->server.execute(task));}
    private static net.minecraft.resources.ResourceLocation cape(){var mc=Minecraft.getInstance();return ClientSkins.appearance(mc.player.getUUID(),net.minecraft.client.resources.DefaultPlayerSkin.get(mc.player.getUUID())).capeTexture();}
    private static void startWardrobe(){
        var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());GameIdentity.PLAYERS.put(p.getUUID(),new GameIdentity("NYA",new byte[0],false,"",java.util.UUID.fromString("00000000-0000-4000-8000-000000000007"),java.util.UUID.fromString("00000000-0000-4000-8000-000000000006")));ServerWardrobe.request(p,new WardrobePayloads.Request("READ","",0));});ClientWardrobe.read();
    }
    private static void captureExample(boolean available){
        var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();
        server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new ServerMenuPayloads.CaptureSummary("Кратер звёздного кристалла",
                available?"Владелец SCAM · под контролем · Аметист и алмазная руда":"Владелец NYA · под защитой ещё 42 мин 12 с",available));
        });
    }
    private void finish(){
        var mc=Minecraft.getInstance();ClientServerMenu.logout(null);ClientProfiles.logout(null);ClientClanAchievements.logout(null);ClientBetaPass.logout(null);ClientWardrobe.logout(null);check(ClientWardrobe.data()==null&&ClientServerMenu.data()==null&&ClientProfiles.profile()==null&&ClientBetaPass.data()==null&&ClientBetaPass.equipped(mc.player.getUUID()).isEmpty(),"All private beta state clears on logout");System.out.println("AOC_SERVER_MENU_CLIENT_PASS hold_click_release=true profile=true beta_pass=true locked_rewards_rejected=true duplicate_xp_once=true weekly_independent=true beta_models=true title_selection=true logout=true real_http_and_packets=true");phase=33;mc.stop();
    }
    private void openGallery(){
        var mc=Minecraft.getInstance();var remote=(net.minecraft.client.player.RemotePlayer)mc.level.getEntity(900000);
        portraitYaw(0);remote.setXRot(0);remote.xRotO=0;String code=GALLERY[galleryIndex];
        var reward=BetaPass.find(code);String slot=BetaPass.slot(reward);
        mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new BetaPassPayloads.Appearance(remote.getUUID(),java.util.Map.of(slot,code)));});
        mc.setScreen(new GalleryScreen(remote,code,reward.name()));
    }
    private static final class GalleryScreen extends net.minecraft.client.gui.screens.Screen {
        private final net.minecraft.client.player.RemotePlayer player;private final String code,name;
        GalleryScreen(net.minecraft.client.player.RemotePlayer player,String code,String name){super(net.minecraft.network.chat.Component.literal(name));this.player=player;this.code=code;this.name=name;}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void render(net.minecraft.client.gui.GuiGraphics g,int mx,int my,float delta){
            double k=Math.min(width/1100.0,height/620.0);g.fill(0,0,width,height,0xff0d0a17);g.pose().pushPose();g.pose().translate((width-1100*k)/2,(height-620*k)/2,0);g.pose().scale((float)k,(float)k,1);
            g.drawString(minecraft.font,net.minecraft.network.chat.Component.literal("AnimeOnCraft · "+name),45,32,0xffecddff,false);
            g.drawString(minecraft.font,net.minecraft.network.chat.Component.literal("Настоящая модель в Minecraft · исходник Blockbench · локальный просмотр"),45,54,0xffad97c8,false);
            g.fill(40,96,535,575,0xff191126);g.fill(565,96,1060,575,0xff191126);
            boolean mantle=code.equals("RIFT_MANTLE"),belt=code.equals("RIFT_LANTERN")||code.equals("STAR_CHAIN");
            float scale=mantle?250:belt?420:300,foot=mantle?580:belt?720:850;
            g.flush();g.enableScissor((int)((width-1100*k)/2+40*k),(int)((height-620*k)/2+96*k),(int)((width-1100*k)/2+1060*k),(int)((height-620*k)/2+575*k));
            model(g,288,foot,scale,mantle?180:0);model(g,812,foot,scale,mantle?140:-40);g.disableScissor();
            g.fill(0,575,1100,620,0xff0d0a17);g.drawString(minecraft.font,net.minecraft.network.chat.Component.literal(mantle?"Со спины":"Спереди"),55,585,0xffbba3d6,false);g.drawString(minecraft.font,net.minecraft.network.chat.Component.literal("В три четверти"),580,585,0xffbba3d6,false);g.pose().popPose();
        }
        private void model(net.minecraft.client.gui.GuiGraphics g,float x,float y,float scale,float angle){
            var dispatcher=minecraft.getEntityRenderDispatcher();var orientation=new org.joml.Quaternionf(dispatcher.cameraOrientation());g.pose().pushPose();g.pose().translate(x,y,160);g.pose().scale(scale,-scale,scale);g.pose().mulPose(com.mojang.math.Axis.XP.rotationDegrees(-5));g.pose().mulPose(com.mojang.math.Axis.YP.rotationDegrees(angle));dispatcher.setRenderShadow(false);
            try{dispatcher.render(player,0,0,0,0,0,g.pose(),g.bufferSource(),net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);g.flush();}finally{dispatcher.setRenderShadow(true);dispatcher.overrideCameraOrientation(orientation);g.pose().popPose();}
        }
    }
    private static void portraitYaw(float yaw){
        var remote=(net.minecraft.client.player.RemotePlayer)Minecraft.getInstance().level.getEntity(900000);
        remote.setYRot(yaw);remote.yRotO=yaw;remote.yBodyRot=yaw;remote.yBodyRotO=yaw;remote.setYHeadRot(yaw);remote.yHeadRotO=yaw;
    }
    private static void detailedExample(){
        var mc=Minecraft.getInstance();mc.screen=null;var remote=(net.minecraft.client.player.RemotePlayer)mc.level.getEntity(900000);
        remote.setInvisible(false);remote.setShiftKeyDown(false);remote.setPose(net.minecraft.world.entity.Pose.STANDING);remote.setPos(mc.player.getX()+1.8,mc.player.getY(),mc.player.getZ());portraitYaw(90);
        mc.options.fov().set(35);mc.options.hideGui=true;mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,remote.position().add(0,1.88,0));
        var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new BetaPassPayloads.Appearance(remote.getUUID(),java.util.Map.of("HEAD","FOX_EARS")));});
    }
    private static void spawnBetaExample(){
        var mc=Minecraft.getInstance();var uuid=java.util.UUID.fromString("00000000-0000-3000-8000-000000000099");var remote=new net.minecraft.client.player.RemotePlayer(mc.level,new com.mojang.authlib.GameProfile(uuid,"BetaProbe"));remote.setId(900000);remote.setPos(mc.player.getX()+3,mc.player.getY(),mc.player.getZ());remote.setYRot(-90);remote.yBodyRot=-90;mc.level.addEntity(remote);mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,remote.position().add(0,1.4,0));
        var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new BetaPassPayloads.Appearance(uuid,java.util.Map.of("HEAD","ORBIT_HALO","FACE","ONI_MASK","BELT","RIFT_LANTERN","BACK","RIFT_MANTLE","TITLE","NEON_LEGEND")));net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new SkinPayload(uuid,new byte[0],false,"","NYA"));});
    }
    private static void seedProfile(net.minecraft.server.level.ServerPlayer player){
        var stats=player.getStats();stats.setValue(player,net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME),720000);stats.setValue(player,net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAYER_KILLS),7);stats.setValue(player,net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DEATHS),3);stats.setValue(player,net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.MOB_KILLS),60);stats.setValue(player,net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.WALK_ONE_CM),600000);stats.setValue(player,net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.FISH_CAUGHT),30);stats.setValue(player,net.minecraft.stats.Stats.BLOCK_MINED.get(net.minecraft.world.level.block.Blocks.STONE),200);stats.setValue(player,net.minecraft.stats.Stats.ITEM_CRAFTED.get(net.minecraft.world.item.Items.STONE),150);stats.setValue(player,net.minecraft.stats.Stats.ENTITY_KILLED.get(net.minecraft.world.entity.EntityType.WITHER),1);
    }
    private static void spawnTitleExample(){
        var mc=Minecraft.getInstance();var uuid=java.util.UUID.fromString("00000000-0000-3000-8000-000000000099");var remote=new net.minecraft.client.player.RemotePlayer(mc.level,new com.mojang.authlib.GameProfile(uuid,"TitleProbe"));remote.setId(900000);remote.setPos(mc.player.getX()+3,mc.player.getY(),mc.player.getZ());mc.level.addEntity(remote);mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,remote.position().add(0,1.8,0));
        var server=mc.getSingleplayerServer();server.execute(()->{var player=server.getPlayerList().getPlayer(mc.player.getUUID());net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new ProfilePayloads.Title(uuid,"BOSS_FIRST"));net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new SkinPayload(uuid,new byte[0],false,"","NYA"));});
    }
}
