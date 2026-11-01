package ru.animeoncraft.integration.neoforge;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

final class ClientGacha {
 private static GachaPayloads.Snapshot data;private static long requested,revealed;private static boolean waiting;private static String pending="";private static int reveals;private static java.util.List<GachaPayloads.Item> lastPulls=java.util.List.of();
 private ClientGacha(){}
 static GachaPayloads.Snapshot data(){return data;}
 static boolean waiting(){return waiting&&System.nanoTime()-requested<10_000_000_000L;}
 /** Nanoseconds since the last roll result arrived; drives the reveal animation. */
 static long sinceReveal(){return System.nanoTime()-revealed;}
 static long sinceRequest(){return System.nanoTime()-requested;}
 /** Counts roll results; the summon screen plays its reveal once for each. */
 static int reveals(){return reveals;}
 /** The last roll's result; a later READ snapshot carries no pulls, so the screen keeps showing these. */
 static java.util.List<GachaPayloads.Item> lastPulls(){return lastPulls;}
 static boolean rolling(){return waiting()&&(pending.equals("ROLL")||pending.equals("FRAG"));}
 static boolean supported(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.hasChannel(GachaPayloads.Request.TYPE.id());}
 static void read(){if(supported()&&!waiting()&&System.nanoTime()-requested>5_000_000_000L)send(new GachaPayloads.Request("READ",0));}
 /** A hint shown on the summon tab; set by the client itself, so it appears without a round trip. */
 private static String notice="";private static long noticeAt;
 static void note(String text){notice=text;noticeAt=System.nanoTime();}
 static String notice(){return System.nanoTime()-noticeAt<12_000_000_000L?notice:"";}
 static final String TOP_UP="Кристаллы покупаются в лаунчере: меню профиля (ник справа вверху) → «Кристаллы».";
 /** Without enough crystals or fragments nothing is sent, so the summon animation never starts. */
 static void roll(int count){if(!supported()||waiting())return;if(data!=null&&data.balance()<data.cost()*count){note("Не хватает кристаллов: нужно "+data.cost()*count+" ◆, у тебя "+data.balance()+" ◆. "+TOP_UP);return;}send(new GachaPayloads.Request("ROLL",count));}
 static void rollWithFragments(int count){if(!supported()||waiting())return;if(data!=null&&data.shards().get(0)<data.shards().get(1)*count){note("Не хватает фрагментов: нужно "+data.shards().get(1)*count+" ✧, у тебя "+data.shards().get(0)+" ✧.");return;}send(new GachaPayloads.Request("FRAG",count));}
 /** Wearing changes appearance only; the answer comes as the usual appearance packet, so no waiting state. */
 static void wear(String code,boolean on){if(supported()&&BetaPassStore.GACHA_SLOTS.containsKey(code))PacketDistributor.sendToServer(new GachaPayloads.Request(on?"WEAR":"OFF",0,code));}
 static boolean worn(String code){var mc=Minecraft.getInstance();return mc.player!=null&&ClientBetaPass.equipped(mc.player.getUUID()).containsValue(code);}
 private static void send(GachaPayloads.Request r){requested=System.nanoTime();waiting=true;pending=r.action();PacketDistributor.sendToServer(r);}
 static void accept(GachaPayloads.Snapshot packet,IPayloadContext c){c.enqueueWork(()->{var mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null||mc.getConnection().getConnection()!=c.connection()||!mc.player.getUUID().equals(packet.player()))return;
  boolean roll=waiting&&(pending.equals("ROLL")||pending.equals("FRAG"));data=packet;waiting=false;pending="";if(roll&&!packet.pulls().isEmpty()){revealed=System.nanoTime();reveals++;lastPulls=packet.pulls();}});}
 static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event){data=null;waiting=false;pending="";lastPulls=java.util.List.of();requested=0;revealed=0;notice="";}
}
