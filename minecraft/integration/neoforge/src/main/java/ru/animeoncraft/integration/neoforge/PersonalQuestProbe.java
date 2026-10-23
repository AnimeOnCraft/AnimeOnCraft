package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import java.util.Collection;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

final class PersonalQuestProbe {
    private static MinecraftServer queued;
    static void schedule(MinecraftServer server){queued=server;}
    static void tick(ServerTickEvent.Post event){
        if(queued!=event.getServer())return;
        queued=null;
        try{verify(event.getServer());}catch(Exception failure){throw new IllegalStateException("AOC_PERSONAL_QUEST_PROBE_FAILED",failure);}
    }
    private static void verify(MinecraftServer server)throws Exception{
        Object file=Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile").getField("INSTANCE").get(null);
        check(file!=null,"FTB quest file not initialized");
        var chapters=(Collection<?>)file.getClass().getMethod("getAllChapters").invoke(file);
        check(chapters.size()==5,"Expected five quest chapters");
        Object firstTask=null,firstReward=null;int total=0;
        for(Object chapter:chapters)for(Object quest:(Collection<?>)chapter.getClass().getMethod("getQuests").invoke(chapter)){
            total++;
            check(!(boolean)quest.getClass().getMethod("canBeRepeated").invoke(quest),"Repeatable quest reward");
            for(Object task:(Collection<?>)quest.getClass().getMethod("getTasks").invoke(quest)){
                if(task.getClass().getSimpleName().equals("ItemTask")){
                    var stack=(ItemStack)task.getClass().getMethod("getItemStack").invoke(task);
                    check(!stack.isEmpty()&&!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains("missing"),"Unknown quest item");
                    if(stack.is(Items.OAK_LOG)){firstTask=task;firstReward=((Collection<?>)quest.getClass().getMethod("getRewards").invoke(quest)).iterator().next();}
                }
            }
        }
        check(total==22&&firstTask!=null&&firstReward!=null,"Quest content incomplete");
        var a=UUID.fromString("00000000-0000-4000-8000-000000000011");var b=UUID.fromString("00000000-0000-4000-8000-000000000012");
        var player=new FakePlayer(server.overworld(),new GameProfile(a,"AocQuestProbe"));
        var taskType=Class.forName("dev.ftb.mods.ftbquests.quest.task.Task");
        Object dataA=file.getClass().getMethod("getOrCreateTeamData",UUID.class).invoke(file,a);
        Object dataB=file.getClass().getMethod("getOrCreateTeamData",UUID.class).invoke(file,b);
        dataA.getClass().getMethod("setProgress",taskType,long.class).invoke(dataA,firstTask,16L);
        check((long)dataA.getClass().getMethod("getProgress",taskType).invoke(dataA,firstTask)==16L,"Personal progress not recorded");
        check((long)dataB.getClass().getMethod("getProgress",taskType).invoke(dataB,firstTask)==0L,"Progress leaked to another player");
        var rewardType=Class.forName("dev.ftb.mods.ftbquests.quest.reward.Reward");
        var claim=dataA.getClass().getMethod("claimReward",ServerPlayer.class,rewardType,boolean.class);
        claim.invoke(dataA,player,firstReward,false);int bread=player.getInventory().countItem(Items.BREAD);
        claim.invoke(dataA,player,firstReward,false);
        check(bread==3&&player.getInventory().countItem(Items.BREAD)==3,"Quest reward duplicated or missing");
        var api=Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI").getMethod("api").invoke(null);
        var result=api.getClass().getMethod("validatePartyCreation",ServerPlayer.class).invoke(api,player);
        check(result.getClass().getMethod("status").invoke(result).toString().equals("FAILURE"),"FTB party creation bypassed personal progress policy");
        System.out.println("AOC_PERSONAL_QUEST_PROBE_PASS chapters=5 quests=22 items_valid=true repeatable=false separate_progress=true reward_once=true party_creation_denied=true");
    }
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
