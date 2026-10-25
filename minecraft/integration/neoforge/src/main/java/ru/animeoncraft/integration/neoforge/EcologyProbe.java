package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.orangemiracle.cleanmaid.cleanup.CleanupModule;
import com.orangemiracle.cleanmaid.config.ConfigModule;
import com.orangemiracle.cleanmaid.data.CleanOptions;
import com.orangemiracle.cleanmaid.filter.DimensionFilterModule;
import com.orangemiracle.cleanmaid.filter.ItemFilterModule;
import com.orangemiracle.cleanmaid.protection.DeathDropProtectionModule;
import com.orangemiracle.cleanmaid.protection.ProtectionModule;
import com.orangemiracle.cleanmaid.timer.TimerModule;
import com.orangemiracle.cleanmaid.timer.TimerSignal;
import java.util.ArrayList;
import java.util.UUID;
import mcjty.incontrol.InControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Only invoked by the guarded disposable loopback territory fixture. */
final class EcologyProbe {
    private static MinecraftServer pending;
    private static int waitTicks;
    private EcologyProbe() {}
    static void schedule(MinecraftServer server) {
        pending=server;waitTicks=0;
        server.overworld().setChunkForced(32,32,true);
        server.getLevel(Level.NETHER).setChunkForced(32,32,true);
        NeoForge.EVENT_BUS.addListener(EcologyProbe::tick);
    }
    private static void tick(ServerTickEvent.Post event) {
        if(pending==null || event.getServer()!=pending || ++waitTicks<80)return;
        var server=pending;pending=null;
        try {verify(server);} finally {
            server.overworld().setChunkForced(32,32,false);
            server.getLevel(Level.NETHER).setChunkForced(32,32,false);
        }
    }
    private static void check(boolean pass, String message) { if (!pass) throw new IllegalStateException(message); }
    private static ItemEntity item(ServerLevel level, boolean old, boolean named) {
        level.getChunk(32, 32);
        var stack = new ItemStack(Items.DIAMOND);
        if (named) stack.set(DataComponents.CUSTOM_NAME, Component.literal("Protected fixture item"));
        var item = new ItemEntity(level, 512, 100, 512, stack);
        if (old) {
            var tag = new CompoundTag();item.saveWithoutId(tag);tag.putShort("Age", (short)1200);item.load(tag);
        }
        check(level.addFreshEntity(item), "Item insertion failed");
        return item;
    }
    private static boolean denied(ServerLevel level, Mob mob, MobSpawnType type) {
        mob.setSpawnCancelled(false);
        mob.setPos(512, 100, 512);
        var event = new FinalizeSpawnEvent(mob, level, 512, 100, 512,
            level.getCurrentDifficultyAt(new BlockPos(512,100,512)), type, null, null);
        NeoForge.EVENT_BUS.post(event);return event.isSpawnCancelled();
    }
    static void verify(MinecraftServer server) {
        var level = server.overworld();
        var player = new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocEcology"));
        var channel = new io.netty.channel.embedded.EmbeddedChannel(player.connection.getConnection());
        player.setPos(512,100,512);player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        try {
            var configModule = new ConfigModule(LogUtils.getLogger());configModule.initialize();
            var config = configModule.config();
            check(config.general().intervalSeconds()==300 && config.general().maxItemsPerBatch()==256, "Cleanup config not loaded");
            check(config.protection().minimumItemAgeSeconds()==60 && !config.targets().cleanExperienceOrbs(),"Unsafe target config");
            var timer = new TimerModule();int warnings=0,countdowns=0;
            for(int tick=1;tick<=6000;tick++) {
                var signal=timer.tick(config);
                if(signal.type()==TimerSignal.Type.WARNING){warnings++;check(tick==4800,"Warning must be at 60s");}
                if(signal.type()==TimerSignal.Type.COUNTDOWN)countdowns++;
                check((signal.type()==TimerSignal.Type.CLEAN)==(tick==6000),"Cleanup must fire at exactly 300 seconds");
            }
            check(warnings==1 && countdowns==10,"Warning/countdown schedule");
            var old=item(level,true,false);var fresh=item(level,false,false);var named=item(level,true,true);
            var nether=server.getLevel(Level.NETHER);check(nether!=null,"Missing Nether");
            var netherOld=item(nether,true,false);var death=item(level,true,false);
            var drops=new LivingDropsEvent(player,level.damageSources().generic(),new ArrayList<>(java.util.List.of(death)),false);
            NeoForge.EVENT_BUS.post(drops);check(ServerEcology.protectedDeathDrop(death),"Death event protection not registered");
            var saved=new CompoundTag();death.saveWithoutId(saved);death.load(saved);
            check(ServerEcology.protectedDeathDrop(death),"Death protection lost during NBT reload");
            var stand=EntityType.ARMOR_STAND.create(level);check(stand!=null,"Stand creation");stand.setPos(512,100,512);level.addFreshEntity(stand);
            var orb=new net.minecraft.world.entity.ExperienceOrb(level,512,100,512,3);level.addFreshEntity(orb);
            var cleaner=new CleanupModule(new DimensionFilterModule(),new ItemFilterModule(),new ProtectionModule(new ItemFilterModule(),new DeathDropProtectionModule()));
            check(cleaner.startCleanup(server,CleanOptions.automatic(),config),"Cleaner not started");
            for(int i=0;i<1000&&cleaner.isCleaning();i++)cleaner.tickCleanup(server);
            check(!cleaner.isCleaning(),"Cleanup did not finish");
            check(old.isRemoved()&&netherOld.isRemoved(),"Old items not removed in both dimensions: overworld="+old.isRemoved()+" nether="+netherOld.isRemoved()+" age="+old.getAge());
            check(!fresh.isRemoved()&&!named.isRemoved()&&!death.isRemoved(),"Protected items removed; death mixin must run");
            check(!stand.isRemoved()&&!orb.isRemoved(),"Non-item entities removed");
            fresh.discard();named.discard();death.discard();stand.discard();orb.discard();
            long previous=level.getDayTime(),gameTime=level.getGameTime();
            level.setDayTime(18000);ServerEcology.tick(new ServerTickEvent.Post(() -> true, server));
            check(level.getDayTime()==18001 && level.getGameTime()==gameTime,"Night acceleration changed simulation time");
            level.setDayTime(6000);ServerEcology.tick(new ServerTickEvent.Post(() -> true, server));check(level.getDayTime()==6000,"Day was accelerated");
            var cycle=level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);boolean before=cycle.get();cycle.set(false,server);
            level.setDayTime(18000);ServerEcology.tick(new ServerTickEvent.Post(() -> true, server));check(level.getDayTime()==18000,"Frozen day cycle changed");
            cycle.set(before,server);level.setDayTime(previous);
            level.players().add(player);InControl.setup.cache.performCount(level);
            check(InControl.setup.cache.getValidPlayers(level)==1,"Player count for mob cap");
            var phantom=EntityType.PHANTOM.create(level);check(phantom!=null,"Phantom creation");int denied=0;
            for(int i=0;i<400;i++)if(denied(level,phantom,MobSpawnType.NATURAL))denied++;
            check(denied>260 && denied<380,"Phantom reduction not near 80 percent: "+denied);
            var zombie=EntityType.ZOMBIE.create(level);check(zombie!=null,"Zombie creation");
            check(!denied(level,zombie,MobSpawnType.NATURAL),"Low population should allow natural zombie");
            var zombies=new ArrayList<Mob>();
            for(int i=0;i<40;i++) { var z=EntityType.ZOMBIE.create(level);check(z!=null,"Zombie creation");z.setPos(512,100,512);check(level.addFreshEntity(z),"Hostile insertion");zombies.add(z); }
            InControl.setup.cache.performCount(level);
            check(denied(level,zombie,MobSpawnType.NATURAL),"Natural hostile cap not applied");
            for(var reason:new MobSpawnType[]{MobSpawnType.SPAWNER,MobSpawnType.COMMAND,MobSpawnType.TRIGGERED,MobSpawnType.STRUCTURE})
                check(!denied(level,zombie,reason),"Special spawn blocked: "+reason);
            var cow=EntityType.COW.create(level);var wither=EntityType.WITHER.create(level);check(cow!=null&&wither!=null,"Entity creation");
            check(!denied(level,cow,MobSpawnType.NATURAL),"Passive animals blocked");
            check(!denied(level,wither,MobSpawnType.NATURAL),"Boss exception not applied");
            var loaded=new EntityJoinLevelEvent(zombie,level,true);NeoForge.EVENT_BUS.post(loaded);
            check(!loaded.isCanceled(),"Saved mob incorrectly blocked on chunk load");
            zombies.forEach(Mob::discard);level.players().remove(player);
            // InControl deliberately caches counts for several ticks. Refresh only in this fixture.
            InControl.setup.cache=new mcjty.incontrol.rules.support.RuleCache();InControl.setup.cache.performCount(level);
            check(InControl.setup.cache.getValidPlayers(level)==0,"Zero-player cache not refreshed");
            check(denied(level,zombie,MobSpawnType.NATURAL),"Natural spawning allowed with zero players");
            System.out.println("AOC_ECOLOGY_PROBE_PASS cleanup_300s=true dimensions=2 death_protection_nbt=true night_speed=2 mob_cap=40 phantom_denied="+denied+"/400");
        } catch(Exception failure) {
            System.err.println("AOC_TERRITORY_PROBE_FAILED ecology");throw new IllegalStateException("Ecology fixture failed",failure);
        } finally {level.players().remove(player);channel.finishAndReleaseAll();}
    }
}
