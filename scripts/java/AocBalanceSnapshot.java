import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Observe mismatches as well as successful settings; only the loopback development server. */
public final class AocBalanceSnapshot {
    public static void main(String[] args) throws Exception {
        if(args.length!=3||!args[0].matches("[0-9]+"))throw new IllegalArgumentException("PID, agent and output required");
        var vm=VirtualMachine.attach(args[0]);
        try{vm.loadAgent(Path.of(args[1]).toAbsolutePath().toString(),Path.of(args[2]).toAbsolutePath().toString());}finally{vm.detach();}
    }
    public static void agentmain(String output,Instrumentation instrumentation)throws Exception{
        Class<?> hooks=null;
        for(var type:instrumentation.getAllLoadedClasses())if(type.getName().equals("net.neoforged.neoforge.server.ServerLifecycleHooks")){hooks=type;break;}
        if(hooks==null)throw new IllegalStateException("Not NeoForge");
        var server=hooks.getMethod("getCurrentServer").invoke(null);
        if(server==null||!Integer.valueOf(25565).equals(server.getClass().getMethod("getPort").invoke(server))
            ||!"127.0.0.1".equals(server.getClass().getMethod("getLocalIp").invoke(server)))throw new IllegalStateException("Loopback only");
        var loader=hooks.getClassLoader();var done=new CompletableFuture<Void>();
        ((Executor)server).execute(()->{try{
            Map<String,Object> data=new LinkedHashMap<>();data.put("checked_at",java.time.Instant.now().toString());data.put("read_only",true);
            var attributes=Class.forName("net.minecraft.world.entity.ai.attributes.Attributes",true,loader);
            var holder=attributes.getField("MAX_HEALTH").get(null);
            var attribute=Class.forName("net.minecraft.core.Holder",true,loader).getMethod("value").invoke(holder);
            data.put("minecraft_max_health_limit",attribute.getClass().getMethod("getMaxValue").invoke(attribute));
            var parameters=Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigParameter",true,loader);
            var spellType=Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell",true,loader);
            var manager=Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigManager",true,loader);
            var getter=manager.getMethod("getSpellConfigValue",spellType,parameters);
            var registry=Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry",true,loader).getField("REGISTRY").get(null);
            var rows=new ArrayList<Map<String,Object>>();
            for(var spell:(Iterable<?>)registry){
                Map<String,Object> row=new LinkedHashMap<>();row.put("spell",spellType.getMethod("getSpellId").invoke(spell).toString());
                row.put("power",getter.invoke(null,spell,parameters.getField("POWER_MULTIPLIER").get(null)));
                row.put("mana",getter.invoke(null,spell,parameters.getField("MANA_MULTIPLIER").get(null)));rows.add(row);
            }
            data.put("spells",rows);
            var gsonType=Class.forName("com.google.gson.Gson",true,loader);var gson=gsonType.getConstructor().newInstance();
            Files.writeString(Path.of(output),gsonType.getMethod("toJson",Object.class).invoke(gson,data)+"\n");done.complete(null);
        }catch(Throwable failure){done.completeExceptionally(failure);}});done.get(30,TimeUnit.SECONDS);
    }
}
