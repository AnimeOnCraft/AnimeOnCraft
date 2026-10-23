package ru.animeoncraft.integration.neoforge;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

final class ClientClanAchievements {
    private static ClanAchievementPayload data;private static long received;
    private ClientClanAchievements(){}
    static ClanAchievementPayload data(){return data!=null&&System.nanoTime()-received<30_000_000_000L?data:null;}
    static void accept(ClanAchievementPayload payload,IPayloadContext context){context.enqueueWork(()->{var c=Minecraft.getInstance().getConnection();if(c==null||c.getConnection()!=context.connection())return;data=payload;received=System.nanoTime();});}
    static void logout(ClientPlayerNetworkEvent.LoggingOut event){data=null;received=0;}
}
