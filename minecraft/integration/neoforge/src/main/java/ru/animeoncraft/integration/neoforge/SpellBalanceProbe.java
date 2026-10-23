package ru.animeoncraft.integration.neoforge;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

/** Real config initialization in the disposable world, before testing spell interactions. */
final class SpellBalanceProbe {
    private SpellBalanceProbe() {}
    static void verify(MinecraftServer server)throws Exception{
        if(server.getPort()!=25569||!"127.0.0.1".equals(server.getLocalIp())
                ||!server.getWorldData().getLevelName().startsWith("aoc-territory-probe-"))
            throw new IllegalStateException("Spell balance probe requires disposable world");
        NeoForge.EVENT_BUS.post(new OnDatapackSyncEvent(server.getPlayerList(),null));
        var spellType=Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
        var parameterType=Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigParameter");
        var manager=Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigManager");
        var getter=manager.getMethod("getSpellConfigValue",spellType,parameterType);
        var registry=Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry").getField("REGISTRY").get(null);
        int count=0;
        for(var spell:(Iterable<?>)registry){
            require(getter.invoke(null,spell,parameterType.getField("POWER_MULTIPLIER").get(null)),0.9,"spell power");
            require(getter.invoke(null,spell,parameterType.getField("MANA_MULTIPLIER").get(null)),1.1,"spell mana");count++;
        }
        if(count!=111)throw new IllegalStateException("Pinned spell registry changed: "+count);
        var configs=Class.forName("io.redspace.ironsspellbooks.config.ServerConfigs");
        for(var entry:java.util.Map.of("MANA_REGEN_MULTIPLIER",0.9,"MAX_UPGRADES",2.0,"SCROLL_RECYCLE_CHANCE",0.35).entrySet()){
            var value=configs.getField(entry.getKey()).get(null);
            require(value.getClass().getMethod("get").invoke(value),entry.getValue(),entry.getKey());
        }
        System.out.println("AOC_SPELL_BALANCE_RUNTIME_PASS spells=111 power=0.9 mana=1.1 mana_regen=0.9 sync=OnDatapackSync");
    }
    private static void require(Object value,double expected,String key){
        if(!(value instanceof Number number)||Math.abs(number.doubleValue()-expected)>0.000001)
            throw new IllegalStateException("Runtime balance differs: "+key+"="+value);
    }
}
