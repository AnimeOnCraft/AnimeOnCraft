package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Real spell methods and entity ticks, only in the guarded full-modpack disposable world. */
final class SpellBoundaryProbe {
    private SpellBoundaryProbe() {}
    private static final class Fighter extends FakePlayer {
        Fighter(ServerLevel level,String name){super(level,new GameProfile(UUID.randomUUID(),name));}
        @Override public boolean isInvulnerableTo(DamageSource source){return false;}
        @Override public boolean canHarmPlayer(net.minecraft.world.entity.player.Player other){return true;}
    }
    static void verify(ServerLevel level,Territory region)throws Exception{
        var victim=new Fighter(level,"AocSpellVictim");
        var caster=new Fighter(level,"AocSpellCaster");
        var rival=ClanCodes.all().stream().filter(code->!code.equals(region.clan())).findFirst().orElseThrow();
        var spawnTime=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");spawnTime.setAccessible(true);spawnTime.setInt(victim,0);
        victim.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);caster.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        victim.setPos(2000,100,2000);caster.setPos(1994,100,2000);
        GameIdentity.PLAYERS.put(victim.getUUID(),identity(region.clan()));GameIdentity.PLAYERS.put(caster.getUUID(),identity(rival));
        level.addNewPlayer(victim);
        level.addNewPlayer(caster);
        try{
            firebolt(level,caster,victim);
            if(victim.getHealth()>=20)throw new IllegalStateException("Neutral real firebolt positive control failed");
            victim.setHealth(20);victim.invulnerableTime=0;victim.setRemainingFireTicks(0);
            victim.setPos(region.minX()+4,100,region.minZ()+4);caster.setPos(region.minX()-4,100,region.minZ()+4);
            firebolt(level,caster,victim);
            if(victim.getHealth()!=20||victim.getRemainingFireTicks()>0)throw new IllegalStateException("Firebolt from outside damaged protected player: health="+victim.getHealth()+" fire="+victim.getRemainingFireTicks());
            var fire=spell(level,"FIRE_FIELD",caster);setDamage(fire,8);
            effect(fire,victim);
            if(victim.getHealth()!=20||victim.getRemainingFireTicks()>0)throw new IllegalStateException("Protected fire field damage or ignition: health="+victim.getHealth()+" fire="+victim.getRemainingFireTicks());
            summonAttack(level,caster,victim);
            if(victim.getHealth()!=20)throw new IllegalStateException("Summoned creature crossed closed claim");
            victim.setPos(2000,100,2000);caster.setPos(1994,100,2000);
            summonAttack(level,caster,victim);
            if(victim.getHealth()>=20)throw new IllegalStateException("Neutral summon positive control failed");
            victim.setHealth(20);victim.invulnerableTime=0;
            blizzard(level,caster,victim);
            if(victim.getTicksFrozen()==0)throw new IllegalStateException("Neutral blizzard positive control failed");
            victim.setTicksFrozen(0);victim.setDeltaMovement(Vec3.ZERO);
            victim.setPos(region.minX()+4,100,region.minZ()+4);caster.setPos(region.minX()-4,100,region.minZ()+4);
            blizzard(level,caster,victim);
            if(victim.getTicksFrozen()!=0||victim.getDeltaMovement().lengthSqr()>0.000001)throw new IllegalStateException("Blizzard crossed closed claim: frozen="+victim.getTicksFrozen()+" motion="+victim.getDeltaMovement());
            effect(spell(level,"BLIZZARD_AOE",caster),victim);
            if(victim.getTicksFrozen()!=0)throw new IllegalStateException("Direct blizzard effect crossed closed claim");
            var war=new com.google.gson.JsonObject();war.addProperty("id",UUID.randomUUID().toString());war.addProperty("attacker",rival);war.addProperty("defender",region.clan());
            war.addProperty("starts_at",java.time.Instant.now().minusSeconds(1).toString());war.addProperty("ends_at",java.time.Instant.now().plusSeconds(60).toString());
            var wars=new com.google.gson.JsonArray();wars.add(war);WarWindows.replace(wars);
            firebolt(level,caster,victim);
            if(victim.getHealth()>=20)throw new IllegalStateException("Active raid firebolt positive control failed");
            victim.setHealth(20);victim.invulnerableTime=0;victim.setRemainingFireTicks(0);
            blizzard(level,caster,victim);
            if(victim.getTicksFrozen()==0)throw new IllegalStateException("Active raid blizzard positive control failed");
            victim.setTicksFrozen(0);victim.setDeltaMovement(Vec3.ZERO);
            GameIdentity.PLAYERS.put(caster.getUUID(),identity(region.clan()));
            firebolt(level,caster,victim);
            summonAttack(level,caster,victim);
            if(victim.getHealth()!=20)throw new IllegalStateException("Friendly projectile or summon damage");
            blizzard(level,caster,victim);
            if(victim.getTicksFrozen()!=0||victim.getDeltaMovement().lengthSqr()>0.000001)throw new IllegalStateException("Blizzard applied friendly effects");
            var heal=spell(level,"HEALING_AOE",victim);setDamage(heal,4);victim.setHealth(10);
            effect(heal,victim);
            if(victim.getHealth()<=10)throw new IllegalStateException("Helpful healing was blocked");
            System.out.println("AOC_SPELL_BOUNDARY_PROBE_PASS real_firebolt=true fire_field=true summoned_zombie=true neutral_blizzard=true protected_freeze=false protected_pull=false active_raid=true friendly_freeze=false healing=true");
        }finally{
            level.removePlayerImmediately(victim,Entity.RemovalReason.DISCARDED);
            level.removePlayerImmediately(caster,Entity.RemovalReason.DISCARDED);
            GameIdentity.PLAYERS.remove(victim.getUUID());GameIdentity.PLAYERS.remove(caster.getUUID());
            WarWindows.load();
        }
    }
    private static GameIdentity identity(String clan){return new GameIdentity(clan,new byte[0],false,"",UUID.randomUUID(),UUID.randomUUID());}
    private static Projectile spell(ServerLevel level,String name,LivingEntity caster)throws Exception{
        var holder=(java.util.function.Supplier<?>)Class.forName("io.redspace.ironsspellbooks.registries.EntityRegistry").getField(name).get(null);
        var type=(EntityType<?>)holder.get();var result=(Projectile)type.create(level);
        if(result==null)throw new IllegalStateException("Spell entity missing "+name);
        result.setOwner(caster);return result;
    }
    private static void setDamage(Projectile spell,float value)throws Exception{spell.getClass().getMethod("setDamage",float.class).invoke(spell,value);}
    private static void effect(Projectile spell,LivingEntity victim)throws Exception{spell.getClass().getMethod("applyEffect",LivingEntity.class).invoke(spell,victim);}
    private static void firebolt(ServerLevel level,LivingEntity caster,LivingEntity victim)throws Exception{
        var type=Class.forName("io.redspace.ironsspellbooks.entity.spells.firebolt.FireboltProjectile");
        var bolt=(Projectile)type.getConstructor(net.minecraft.world.level.Level.class,LivingEntity.class).newInstance(level,caster);setDamage(bolt,8);
        var impact=type.getDeclaredMethod("onHitEntity",EntityHitResult.class);impact.setAccessible(true);impact.invoke(bolt,new EntityHitResult(victim));
    }
    private static void blizzard(ServerLevel level,LivingEntity caster,LivingEntity victim)throws Exception{
        var blizzard=spell(level,"BLIZZARD_AOE",caster);blizzard.setPos(victim.getX()+1,victim.getY(),victim.getZ());
        blizzard.getClass().getMethod("setRadius",float.class).invoke(blizzard,5f);
        blizzard.getClass().getMethod("setDuration",int.class).invoke(blizzard,100);
        for(int tick=0;tick<20;tick++){blizzard.tickCount++;blizzard.tick();}
    }
    private static void summonAttack(ServerLevel level,LivingEntity caster,LivingEntity victim)throws Exception{
        var type=Class.forName("io.redspace.ironsspellbooks.entity.mobs.SummonedZombie");
        var zombie=(LivingEntity)type.getConstructor(net.minecraft.world.level.Level.class,LivingEntity.class,boolean.class).newInstance(level,caster,false);
        zombie.setPos(caster.position());
        if(type.getMethod("getSummoner").invoke(zombie)!=caster)throw new IllegalStateException("Summon fixture could not resolve its caster");
        try{type.getMethod("doHurtTarget",Entity.class).invoke(zombie,victim);}finally{zombie.discard();}
    }
}
