package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientEmotes {
    static final Map<String,Long> draws=new HashMap<>();
    private static final Map<UUID,Map<String,Long>> entityDraws=new HashMap<>();
    static long renderCount(UUID id,String code){return entityDraws.getOrDefault(id,Map.of()).getOrDefault(code,0L);}
    private record Playing(String code,long started){}
    private static final Map<UUID,Playing> playing=new HashMap<>();
    private static EmotePayloads.Inventory inventory;private static long requested;
    private static CameraType previousCamera;
    private static EmotePairPayloads.Notice invitation;private static long invitationAt;
    private static String previewCode="";private static float previewTicks;
    private static final Map<PlayerModel<?>,Float> lifts=new WeakHashMap<>();
    private ClientEmotes(){}
    static boolean supported(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.hasChannel(EmotePayloads.Request.TYPE.id());}
    static EmotePayloads.Inventory inventory(){return inventory;}
    static String current(){var p=Minecraft.getInstance().player;var a=p==null?null:playing.get(p.getUUID());return a==null?"":a.code();}
    static boolean playing(UUID id,String code){var a=playing.get(id);return a!=null&&a.code().equals(code);}
    static void read(){if(!supported()||System.nanoTime()-requested<3_000_000_000L)return;requested=System.nanoTime();PacketDistributor.sendToServer(new EmotePayloads.Request("READ"));}
    static void choose(String code){if(!supported()||inventory==null||!inventory.available()||!code.isEmpty()&&!inventory.owned().contains(code))return;if(Emotes.paired(code)){invite(code);return;}PacketDistributor.sendToServer(new EmotePayloads.Request(code));Minecraft.getInstance().setScreen(null);}
    static void invite(String code){var mc=Minecraft.getInstance();if(!pairSupported()||mc.player==null||mc.level==null)return;var target=mc.level.players().stream().filter(p->p!=mc.player&&mc.player.distanceToSqr(p)<=3.24&&mc.player.hasLineOfSight(p)&&mc.player.getLookAngle().multiply(1,0,1).normalize().dot(p.position().subtract(mc.player.position()).multiply(1,0,1).normalize())>=.8).min(Comparator.comparingDouble(p->mc.player.distanceToSqr(p))).orElse(null);if(target==null){mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Подойди к напарнику и встаньте лицом друг к другу на расстоянии 1–1,8 блока."),true);return;}PacketDistributor.sendToServer(new EmotePairPayloads.Request("INVITE",target.getUUID(),code));mc.setScreen(null);}
    static boolean pairSupported(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.hasChannel(EmotePairPayloads.Request.TYPE.id());}
    static void notice(EmotePairPayloads.Notice packet,IPayloadContext context){context.enqueueWork(()->{var c=Minecraft.getInstance().getConnection();if(c==null||c.getConnection()!=context.connection())return;invitation=packet.code().isEmpty()?null:packet;invitationAt=System.nanoTime();});}
    static EmotePairPayloads.Notice invitation(){if(invitation!=null&&(System.nanoTime()-invitationAt)/50_000_000L>=invitation.remaining())invitation=null;return invitation;}
    static void answer(boolean accept){var i=invitation();if(i==null||!pairSupported())return;PacketDistributor.sendToServer(new EmotePairPayloads.Request(accept?"ACCEPT":"DENY",i.sender(),i.code()));invitation=null;Minecraft.getInstance().setScreen(null);}
    static void preview(String code,float ticks,Runnable draw){var previous=previewCode;float priorTicks=previewTicks;previewCode=code;previewTicks=ticks;try{draw.run();}finally{previewCode=previous;previewTicks=priorTicks;}}
    static void inventory(EmotePayloads.Inventory packet,IPayloadContext context){context.enqueueWork(()->{var c=Minecraft.getInstance().getConnection();if(c!=null&&c.getConnection()==context.connection())inventory=packet;});}
    static void state(EmotePayloads.State packet,IPayloadContext context){context.enqueueWork(()->{var mc=Minecraft.getInstance();var c=mc.getConnection();if(c==null||c.getConnection()!=context.connection()||mc.player==null)return;
        boolean self=packet.player().equals(mc.player.getUUID()),was=playing.containsKey(packet.player());
        if(packet.code().isEmpty()){playing.remove(packet.player());if(self)restoreCamera();}
        else {playing.put(packet.player(),new Playing(packet.code(),System.nanoTime()-packet.elapsed()*50_000_000L));if(self&&!was&&mc.options.getCameraType()==CameraType.FIRST_PERSON){previousCamera=CameraType.FIRST_PERSON;mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);}}
    });}
    static void tick(){var mc=Minecraft.getInstance();if(mc.player==null)return;long now=System.nanoTime();playing.entrySet().removeIf(e->(now-e.getValue().started())/50_000_000L>=Emotes.find(e.getValue().code()).ticks()||!e.getKey().equals(mc.player.getUUID())&&mc.level.getPlayerByUUID(e.getKey())==null);if(!playing.containsKey(mc.player.getUUID()))restoreCamera();}
    private static void restoreCamera(){var mc=Minecraft.getInstance();if(previousCamera!=null){if(mc.options.getCameraType()==CameraType.THIRD_PERSON_BACK)mc.options.setCameraType(previousCamera);previousCamera=null;}}
    static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e){playing.clear();entityDraws.clear();draws.clear();inventory=null;requested=0;invitation=null;previewCode="";ClientEmoteFavorites.logout();restoreCamera();}
    public static void reset(PlayerModel<?> model){var lift=lifts.remove(model);if(lift!=null)for(var part:List.of(model.head,model.body,model.rightArm,model.leftArm,model.rightLeg,model.leftLeg))part.y-=lift;}
    public static boolean apply(PlayerModel<?> model,LivingEntity entity){
        boolean preview=!previewCode.isEmpty()&&entity==Minecraft.getInstance().player;var a=preview?new Playing(previewCode,0):playing.get(entity.getUUID());if(a==null||!entity.isAlive())return false;float elapsed=preview?previewTicks:(System.nanoTime()-a.started())/50_000_000f;var entry=Emotes.find(a.code());float w=EmoteMotion.weight(elapsed,entry.ticks());if(w<=0)return false;
        var f=EmoteMotion.frame(a.code(),elapsed);part(model.head,f.head(),w,f.lift());part(model.body,f.body(),w,f.lift());part(model.rightArm,f.rightArm(),w,f.lift());part(model.leftArm,f.leftArm(),w,f.lift());part(model.rightLeg,f.rightLeg(),w,f.lift());part(model.leftLeg,f.leftLeg(),w,f.lift());
        draws.merge(a.code(),1L,Long::sum);if(!preview)entityDraws.computeIfAbsent(entity.getUUID(),ignored->new HashMap<>()).merge(a.code(),1L,Long::sum);lifts.put(model,f.lift()*w);model.hat.copyFrom(model.head);model.jacket.copyFrom(model.body);model.rightSleeve.copyFrom(model.rightArm);model.leftSleeve.copyFrom(model.leftArm);model.rightPants.copyFrom(model.rightLeg);model.leftPants.copyFrom(model.leftLeg);return true;
    }
    private static void part(ModelPart part,EmoteMotion.Part target,float w,float lift){part.xRot+=(target.x()-part.xRot)*w;part.yRot+=(target.y()-part.yRot)*w;part.zRot+=(target.z()-part.zRot)*w;part.y+=lift*w;}
}
