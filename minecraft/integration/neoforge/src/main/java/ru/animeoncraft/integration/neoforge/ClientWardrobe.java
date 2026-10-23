package ru.animeoncraft.integration.neoforge;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

final class ClientWardrobe {
 private static WardrobePayloads.Snapshot data;private static WardrobePayloads.Request pending;private static long requested;
 private ClientWardrobe(){}
 static WardrobePayloads.Snapshot data(){return data;}
 static boolean supported(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.hasChannel(WardrobePayloads.Request.TYPE.id());}
 static void read(){if(supported()&&pending==null&&System.nanoTime()-requested>5_000_000_000L)pending=new WardrobePayloads.Request("READ","",0);tick();}
 static void choose(String kind,String code){if(data==null||!data.available()||!supported())return;pending=new WardrobePayloads.Request(kind,code,data.revision());tick();}
 static void tick(){if(pending==null||!supported()||System.nanoTime()-requested<600_000_000L)return;requested=System.nanoTime();PacketDistributor.sendToServer(pending);pending=null;}
 static void accept(WardrobePayloads.Snapshot packet,IPayloadContext c){c.enqueueWork(()->{var mc=Minecraft.getInstance();if(mc.player!=null&&mc.getConnection()!=null&&mc.getConnection().getConnection()==c.connection()&&mc.player.getUUID().equals(packet.player()))data=packet;});}
 static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event){data=null;pending=null;requested=0;}
}
