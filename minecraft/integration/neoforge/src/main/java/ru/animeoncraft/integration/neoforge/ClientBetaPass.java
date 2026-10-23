package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client only holds server receipts. Pending clicks keep their order across screens. */
public final class ClientBetaPass {
    private static BetaPassPayloads.Snapshot data;
    private static final Map<UUID,Map<String,String>> cosmetics=new HashMap<>();
    private static final ArrayDeque<BetaPassPayloads.Request> pending=new ArrayDeque<>();
    private static long requested;
    private ClientBetaPass(){}
    static boolean supported(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.hasChannel(BetaPassPayloads.Request.TYPE.id());}
    static BetaPassPayloads.Snapshot data(){return data;}
    static Map<String,String> equipped(UUID id){return cosmetics.getOrDefault(id,Map.of());}
    static void request(String action,int index,String slot,String code){if(!supported())return;var r=new BetaPassPayloads.Request(action,index,slot,code);if(!action.equals("GET")){if(pending.size()<8)pending.add(r);}else if(pending.isEmpty()&&System.nanoTime()-requested>5_000_000_000L)pending.add(r);tick();}
    static void tick(){if(!supported()||pending.isEmpty()||System.nanoTime()-requested<550_000_000L)return;requested=System.nanoTime();PacketDistributor.sendToServer(pending.remove());}
    static void accept(BetaPassPayloads.Snapshot packet,IPayloadContext c){c.enqueueWork(()->{var mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null||mc.getConnection().getConnection()!=c.connection()||!mc.player.getUUID().equals(packet.player()))return;data=packet;cosmetics.put(packet.player(),packet.equipped());});}
    static void appearance(BetaPassPayloads.Appearance packet,IPayloadContext c){c.enqueueWork(()->{var connection=Minecraft.getInstance().getConnection();if(connection!=null&&connection.getConnection()==c.connection())cosmetics.put(packet.player(),packet.equipped());});}
    static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event){data=null;cosmetics.clear();pending.clear();requested=0;}
    static MutableComponent title(UUID id){String code=equipped(id).get("TITLE");return code==null?null:shimmer(code,System.currentTimeMillis());}
    static MutableComponent shimmer(String code,long now){var r=BetaPass.find(code);if(r==null||!BetaPass.title(code))return Component.empty();var result=Component.empty();String text=r.name();double phase=(now%6000)/6000.0;for(int i=0;i<text.length();i++){float hue=(float)((phase+(double)i/Math.max(1,text.length())*.3)%1);float base=code.equals("BETA_SCOUT")?.48f:.48f;int rgb=java.awt.Color.HSBtoRGB(base+hue*.38f,.67f,1)&0xffffff;result.append(Component.literal(text.substring(i,i+1)).withColor(rgb));}return result;}
    public static boolean mantle(UUID id){return equipped(id).containsValue("RIFT_MANTLE");}
}
