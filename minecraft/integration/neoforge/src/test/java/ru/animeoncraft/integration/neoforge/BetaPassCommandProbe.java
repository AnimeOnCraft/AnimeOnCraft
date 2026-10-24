package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Isolated command/permission/disk-persistence test; not included in the release JAR. */
@Mod("aocbetapasscommandprobe")
public final class BetaPassCommandProbe {
    public BetaPassCommandProbe() { NeoForge.EVENT_BUS.addListener(this::started); }
    private static void check(boolean value,String message) { if(!value)throw new IllegalStateException(message); }
    private static void rejects(CommandDispatcher<CommandSourceStack> commands,String command,CommandSourceStack source)throws Exception {
        try { commands.execute(command,source); } catch(CommandSyntaxException expected) { return; }
        throw new IllegalStateException("Command must be rejected: "+command);
    }
    private void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("animeoncraft.betaPassCommandProbe"))return;
        var server=event.getServer();
        if(server.getPort()!=25569||!"127.0.0.1".equals(server.getLocalIp())||!server.getWorldData().getLevelName().startsWith("aoc-beta-levels-probe-"))throw new IllegalStateException("Disposable beta command world required");
        try {
            var commands=server.getCommands().getDispatcher();var source=server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            var root=commands.getRoot().getChild("aocbetapass");check(root!=null,"Command registered");
            for(int permission:new int[]{0,1,2,3}) {
                check(!root.canUse(source.withPermission(permission)),"Grant is restricted to permission four");
                rejects(commands,"aocbetapass addlevels AocBpOffline 3",source.withPermission(permission));
            }
            check(root.canUse(source),"Console and level-four admins can use the command");
            var target=new GameProfile(UUID.randomUUID(),"AocBpOffline");var unknown=new GameProfile(UUID.randomUUID(),"AocBpUnknown");
            var untouched=new GameProfile(UUID.randomUUID(),"AocBpOther");
            server.getProfileCache().add(target);server.getProfileCache().add(unknown);server.getProfileCache().add(untouched);
            var store=BetaPassStore.get(server);var entry=store.player(target.getId());entry.xp=165;entry.dayClaims=2;entry.weekClaims=4;
            entry.daily[0]=1234;entry.weekly[0]=5678;entry.rewards=1;entry.equipped.put("TITLE","BETA_SCOUT");store.player(untouched.getId()).xp=75;
            check(commands.execute("aocbetapass addlevels AocBpOffline 3",source)==3,"Exactly three levels granted");
            check(entry.xp==465&&entry.rewards==1&&entry.dayClaims==2&&entry.weekClaims==4&&entry.daily[0]==1234&&entry.weekly[0]==5678,"Quest state and partial XP preserved");
            check("BETA_SCOUT".equals(entry.equipped.get("TITLE")),"Equipped title preserved");
            check(store.player(untouched.getId()).xp==75,"Other player unchanged");
            for(String count:new String[]{"0","-1","11","2147483647"})rejects(commands,"aocbetapass addlevels AocBpOffline "+count,source);
            check(entry.xp==465,"Invalid counts do not mutate XP");
            rejects(commands,"aocbetapass addlevels AocBpUnknown 1",source);check(store.existingPlayer(unknown.getId())==null,"No progress created for an unknown player");
            check(commands.execute("aocbetapass close",source)==1&&!store.open,"Existing close command preserved");
            check(commands.execute("aocbetapass addlevels AocBpOffline 1",source)==1&&entry.xp==565&&!store.open,"Explicit admin compensation does not reopen the pass");
            check(commands.execute("aocbetapass open",source)==1&&store.open,"Existing open command preserved");
            check(commands.execute("aocbetapass addlevels AocBpOffline 10",source)==5&&entry.xp==1000,"Level capped at ten");
            check(commands.execute("aocbetapass addlevels AocBpOffline 1",source)==0&&entry.xp==1000,"Repeated grants at cap are a no-op");
            check(entry.rewards==1,"Rewards remain claimable, without forced claim or equipment change");
            server.saveEverything(false,true,true);
            var saved=server.getWorldPath(LevelResource.ROOT).resolve("data/animeoncraft-beta-rift-1.dat");
            var data=NbtIo.readCompressed(saved,NbtAccounter.unlimitedHeap());var restored=BetaPassStore.load(data.getCompound("data"),server.registryAccess());
            check(restored.existingPlayer(target.getId()).xp==1000&&restored.existingPlayer(target.getId()).rewards==1,"Grant restored from actual saved NBT");
            check(restored.existingPlayer(unknown.getId())==null&&restored.existingPlayer(untouched.getId()).xp==75,"No accidental player mutation on disk");
            var result=new JsonObject();result.addProperty("passed",true);result.addProperty("offline_target",true);result.addProperty("permission_4_only",true);
            result.addProperty("fractional_xp_preserved",true);result.addProperty("level_cap",10);result.addProperty("invalid_args_rejected",true);
            result.addProperty("unknown_player_rejected",true);result.addProperty("other_players_unchanged",true);result.addProperty("quests_rewards_equipment_preserved",true);
            result.addProperty("open_close_preserved",true);result.addProperty("disk_nbt_restored",true);
            Files.writeString(Path.of("beta-pass-command-probe-result.json"),result.toString()+"\n");System.out.println("AOC_BETA_PASS_COMMAND_PROBE_PASS "+result);
        }catch(Exception failure){System.out.println("AOC_BETA_PASS_COMMAND_PROBE_FAILED "+failure);throw new IllegalStateException("Beta pass command probe failed",failure);}
    }
}
