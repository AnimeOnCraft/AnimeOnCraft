package ru.animeoncraft.integration.neoforge;

import java.lang.reflect.Proxy;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/** Personal quest progress; clan membership remains managed by AnimeOnCraft. */
final class PersonalQuestPolicy {
    private static boolean configured;
    static void starting(ServerStartingEvent event){
        if(configured||!net.neoforged.fml.ModList.get().isLoaded("ftbteams"))return;
        try {
            var apiClass=Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            var api=apiClass.getMethod("api").invoke(null);
            var validator=Class.forName("dev.ftb.mods.ftbteams.api.PartyCreationValidator");
            var result=Class.forName("dev.ftb.mods.ftbteams.api.PartyCreationValidator$CreationResult");
            Object deny=result.getMethod("fail",Component.class).invoke(null,Component.literal("В AnimeOnCraft задания личные. Кланы выбираются в лаунчере."));
            Object handler=Proxy.newProxyInstance(validator.getClassLoader(),new Class<?>[]{validator},(proxy,method,args)->switch(method.getName()){
                case "validatePartyCreation" -> deny;
                case "toString" -> "AnimeOnCraft personal quests";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API").getMethod("addPartyCreationValidator",validator).invoke(api,handler);
            configured=true;
        } catch(ReflectiveOperationException failure){throw new IllegalStateException("Personal quest policy could not be installed",failure);}
    }
}
