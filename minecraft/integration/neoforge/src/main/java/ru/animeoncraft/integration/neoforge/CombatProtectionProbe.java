package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

final class CombatProtectionProbe {
    private CombatProtectionProbe() {}
    static void verify(ServerLevel level,Territory region)throws Exception{
        var victim=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocVictim"));
        var attacker=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"AocFighter"));
        String rival=ClanCodes.all().stream().filter(code->!code.equals(region.clan())).findFirst().orElseThrow();
        try{
            victim.setPos(region.minX()+4,100,region.minZ()+4);attacker.setPos(region.minX()+4,100,region.minZ()+4);
            GameIdentity.PLAYERS.put(victim.getUUID(),identity(region.clan()));
            GameIdentity.PLAYERS.put(attacker.getUUID(),identity(region.clan()));
            if(!damage(victim,attacker).isCanceled())throw new IllegalStateException("Friendly fire permitted");
            GameIdentity.PLAYERS.put(attacker.getUUID(),identity(rival));
            if(!damage(victim,attacker).isCanceled())throw new IllegalStateException("Closed raid allowed player damage");
            var window=new JsonObject();window.addProperty("id",UUID.randomUUID().toString());window.addProperty("attacker",rival);window.addProperty("defender",region.clan());
            window.addProperty("starts_at",java.time.Instant.now().minusSeconds(1).toString());window.addProperty("ends_at",java.time.Instant.now().plusSeconds(60).toString());
            var windows=new JsonArray();windows.add(window);WarWindows.replace(windows);
            if(damage(victim,attacker).isCanceled())throw new IllegalStateException("Active raid denied player damage");
            WarWindows.load();
            if(net.neoforged.fml.ModList.get().isLoaded("irons_spellbooks")){
                var constructor=Class.forName("io.redspace.ironsspellbooks.api.events.SpellPreCastEvent").getConstructors()[0];
                var cast=(net.neoforged.neoforge.event.entity.player.PlayerEvent)constructor.newInstance(attacker,"irons_spellbooks:fireball",1,null,null);
                TerritoryProtection.spell(cast);
                if(!((net.neoforged.bus.api.ICancellableEvent)cast).isCanceled())throw new IllegalStateException("Guest could cast inside protected territory");
            }
            victim.setPos(2000,100,2000);
            if(damage(victim,attacker).isCanceled())throw new IllegalStateException("Neutral-world PvP unexpectedly blocked");
            System.out.println("AOC_COMBAT_PROBE_PASS friendly_fire=false protected_pvp=true active_raid=true neutral_pvp=true spell_guard=true");
        }finally{GameIdentity.PLAYERS.remove(victim.getUUID());GameIdentity.PLAYERS.remove(attacker.getUUID());WarWindows.load();}
    }
    private static GameIdentity identity(String clan){return new GameIdentity(clan,new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID());}
    private static LivingIncomingDamageEvent damage(FakePlayer victim,FakePlayer source){
        var event=new LivingIncomingDamageEvent(victim,new DamageContainer(victim.damageSources().playerAttack(source),1));
        TerritoryProtection.damage(event);return event;
    }
}
