import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Read-only inspection of the owner's loopback preview. No class transformation. */
public final class AocBalanceRead {
    public static void main(String[] args) throws Exception {
        if(args.length!=3||!args[0].matches("[0-9]+"))throw new IllegalArgumentException("PID, agent and output required");
        var vm=VirtualMachine.attach(args[0]);
        try{vm.loadAgent(Path.of(args[1]).toAbsolutePath().toString(),Path.of(args[2]).toAbsolutePath().toString());}
        finally{vm.detach();}
    }
    public static void agentmain(String output,Instrumentation instrumentation) throws Exception {
        var file=Path.of(output).toAbsolutePath().normalize();
        var allowed=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().getParent().getParent();
        if(!file.startsWith(allowed)||!file.getFileName().toString().equals("balance-runtime-result.json"))throw new IllegalArgumentException("Output must stay in the local .tools directory");
        Class<?> hooks=null;
        for(var candidate:instrumentation.getAllLoadedClasses())if(candidate.getName().equals("net.neoforged.neoforge.server.ServerLifecycleHooks")){hooks=candidate;break;}
        if(hooks==null)throw new IllegalStateException("Not a NeoForge server");
        var server=hooks.getMethod("getCurrentServer").invoke(null);
        if(server==null||!(Boolean)server.getClass().getMethod("isDedicatedServer").invoke(server)
            ||!Integer.valueOf(25565).equals(server.getClass().getMethod("getPort").invoke(server))
            ||!"127.0.0.1".equals(server.getClass().getMethod("getLocalIp").invoke(server)))throw new IllegalStateException("Only the dedicated loopback preview may be inspected");
        var loader=hooks.getClassLoader();
        var done=new CompletableFuture<Void>();
        ((Executor)server).execute(()->{
            try{
                Map<String,Object> result=new LinkedHashMap<>();
                result.put("checked_at",java.time.Instant.now().toString());result.put("read_only",true);
                var parameters=Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigParameter",true,loader);
                var abstractSpell=Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell",true,loader);
                var manager=Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigManager",true,loader);
                var getter=manager.getMethod("getSpellConfigValue",abstractSpell,parameters);
                var registry=Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry",true,loader).getField("REGISTRY").get(null);
                int count=0;
                for(var spell:(Iterable<?>)registry){
                    requireNumber(getter.invoke(null,spell,parameters.getField("POWER_MULTIPLIER").get(null)),0.9,"spell power");
                    requireNumber(getter.invoke(null,spell,parameters.getField("MANA_MULTIPLIER").get(null)),1.1,"spell mana cost");
                    count++;
                }
                if(count==0)throw new IllegalStateException("No spells registered");
                result.put("spells_checked",count);result.put("all_spell_power_multiplier",0.9);result.put("all_spell_mana_multiplier",1.1);
                var iron=Class.forName("io.redspace.ironsspellbooks.config.ServerConfigs",true,loader);
                requireNumber(value(iron.getField("MANA_REGEN_MULTIPLIER").get(null)),0.9,"mana regen");
                requireNumber(value(iron.getField("MAX_UPGRADES").get(null)),2,"max upgrades");
                requireNumber(value(iron.getField("SCROLL_RECYCLE_CHANCE").get(null)),0.35,"scroll recycle");
                result.put("mana_regen",0.9);result.put("max_upgrades",2);result.put("scroll_recycle",0.35);
                var create=Class.forName("com.simibubi.create.infrastructure.config.AllConfigs",true,loader).getMethod("server").invoke(null);
                var kinetics=create.getClass().getField("kinetics").get(create);
                requireNumber(value(kinetics.getClass().getField("maxBlocksMoved").get(kinetics)),512,"Create moving blocks");
                var amethyst=value(kinetics.getClass().getField("amethystMovement").get(kinetics)).toString();
                if(!amethyst.equals("UNMOVABLE"))throw new IllegalStateException("Create amethyst movement differs");
                result.put("create_max_blocks_moved",512);result.put("create_amethyst_movement",amethyst);
                var attributes=Class.forName("net.minecraft.world.entity.ai.attributes.Attributes",true,loader);
                var holder=attributes.getField("MAX_HEALTH").get(null);
                var holderType=Class.forName("net.minecraft.core.Holder",true,loader);
                var healthAttribute=holderType.getMethod("value").invoke(holder);
                result.put("minecraft_max_health_limit",healthAttribute.getClass().getMethod("getMaxValue").invoke(healthAttribute));
                result.put("passed",true);result.put("pvp_playtest_completed",false);
                var gsonClass=Class.forName("com.google.gson.Gson",true,loader);
                var gson=gsonClass.getConstructor().newInstance();
                Files.writeString(file,gsonClass.getMethod("toJson",Object.class).invoke(gson,result)+"\n");
                done.complete(null);
            }catch(Throwable failure){done.completeExceptionally(failure);}
        });
        done.get(30,TimeUnit.SECONDS);
    }
    private static Object value(Object config) throws Exception{return config.getClass().getMethod("get").invoke(config);}
    private static void requireNumber(Object actual,double expected,String label){
        if(!(actual instanceof Number number)||Math.abs(number.doubleValue()-expected)>0.000001)throw new IllegalStateException(label+" differs from the reviewed balance profile: "+actual);
    }
}
