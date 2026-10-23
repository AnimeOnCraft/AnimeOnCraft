package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** Invoked only by the existing disposable-world/port-gated protection probe. */
final class GameplayRewardProbe {
    private GameplayRewardProbe() {}
    static void verify(MinecraftServer server) throws Exception {
        var player=new FakePlayer(server.overworld(),new GameProfile(UUID.randomUUID(),"AocBossHunter"));
        var cancelled=EntityType.WITHER.create(server.overworld());
        var accepted=EntityType.WITHER.create(server.overworld());
        if(cancelled==null||accepted==null)throw new IllegalStateException("Boss fixture creation failed");
        GameIdentity.PLAYERS.put(player.getUUID(),new GameIdentity("SCAM",new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID()));
        Consumer<LivingDeathEvent> preventDeath=event->{if(event.getEntity()==cancelled)event.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW,false,LivingDeathEvent.class,preventDeath);
        var directory=server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-gameplay-outbox");
        try{
            var event=new LivingDeathEvent(cancelled,player.damageSources().playerAttack(player));
            NeoForge.EVENT_BUS.post(event);
            if(!event.isCanceled())throw new IllegalStateException("Death cancellation fixture did not run");
            if(Files.exists(directory.resolve(cancelled.getUUID()+".json")))throw new IllegalStateException("Cancelled boss death awarded clan XP");
            NeoForge.EVENT_BUS.post(new LivingDeathEvent(accepted,player.damageSources().playerAttack(player)));
            if(!Files.isRegularFile(directory.resolve(accepted.getUUID()+".json")))throw new IllegalStateException("Confirmed boss death did not persist reward");
            System.out.println("AOC_REWARD_PROBE_PASS cancelled_death_reward=false confirmed_death_persisted=true");
        }finally{
            NeoForge.EVENT_BUS.unregister(preventDeath);
            GameIdentity.PLAYERS.remove(player.getUUID());
        }
    }
}
