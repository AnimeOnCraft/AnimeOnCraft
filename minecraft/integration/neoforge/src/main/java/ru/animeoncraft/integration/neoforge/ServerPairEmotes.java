package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** A ten-second, single-use invitation. Both actual admitted accounts must own the emote. */
final class ServerPairEmotes {
    record Invitation(UUID sender,String code,int deadline){}
    private static final Map<UUID,Invitation> incoming=new HashMap<>();
    private static final Map<UUID,UUID> partners=new HashMap<>();
    private static final Map<UUID,Integer> lastInvite=new HashMap<>();
    private ServerPairEmotes(){}
    static void request(EmotePairPayloads.Request request,IPayloadContext context){context.enqueueWork(()->{if(context.player() instanceof ServerPlayer p)request(p,request);});}
    private static void message(ServerPlayer p,String text){p.displayClientMessage(Component.literal(text),true);}
    private static void notice(ServerPlayer receiver,UUID sender,String name,String code,int ticks){if(receiver.connection.hasChannel(EmotePairPayloads.Notice.TYPE.id()))PacketDistributor.sendToPlayer(receiver,new EmotePairPayloads.Notice(sender,name,code,ticks));}
    private static boolean busy(UUID id){return incoming.containsKey(id)||incoming.values().stream().anyMatch(i->i.sender().equals(id));}
    private static boolean ready(ServerPlayer a,ServerPlayer b,String code){
        if(a==b||a.serverLevel()!=b.serverLevel()||!ServerEmotes.owns(a,code)||!ServerEmotes.owns(b,code)||!ServerEmotes.canPlay(a)||!ServerEmotes.canPlay(b)||!a.hasLineOfSight(b)||!a.connection.hasChannel(EmotePairPayloads.Request.TYPE.id())||!b.connection.hasChannel(EmotePairPayloads.Request.TYPE.id()))return false;
        var delta=b.position().subtract(a.position());if(!EmotePairRules.inRange(delta.horizontalDistanceSqr(),delta.y))return false;
        var toward=delta.multiply(1,0,1).normalize();
        return EmotePairRules.facing(a.getLookAngle().multiply(1,0,1).normalize().dot(toward))&&EmotePairRules.facing(b.getLookAngle().multiply(1,0,1).normalize().dot(toward.scale(-1)));
    }
    static void request(ServerPlayer p,EmotePairPayloads.Request r){
        if(!ServerEmotes.admitted(p)||!p.connection.hasChannel(EmotePairPayloads.Request.TYPE.id()))return;
        int now=p.server.getTickCount();
        if(r.action().equals("INVITE")){
            if(now-lastInvite.getOrDefault(p.getUUID(),Integer.MIN_VALUE/2)<EmotePairRules.COOLDOWN_TICKS)return;lastInvite.put(p.getUUID(),now);
            var target=p.server.getPlayerList().getPlayer(r.target());
            if(target==null||!ready(p,target,r.code())||busy(p.getUUID())||busy(r.target())||partners.containsKey(p.getUUID())||partners.containsKey(r.target())){message(p,"Встаньте лицом друг к другу на расстоянии 1–1,8 блока. Оба игрока должны быть свободны.");return;}
            incoming.put(target.getUUID(),new Invitation(p.getUUID(),r.code(),now+EmotePairRules.INVITE_TICKS));notice(target,p.getUUID(),p.getGameProfile().getName(),r.code(),EmotePairRules.INVITE_TICKS);
            message(target,p.getGameProfile().getName()+" предлагает «"+Emotes.find(r.code()).name()+"». Открой меню эмоций, чтобы принять.");message(p,"Приглашение отправлено на 10 секунд.");return;
        }
        var invite=incoming.get(p.getUUID());if(invite==null||!invite.sender().equals(r.target())||!invite.code().equals(r.code()))return;
        incoming.remove(p.getUUID());notice(p,invite.sender(),"","",0);var sender=p.server.getPlayerList().getPlayer(invite.sender());
        if(r.action().equals("DENY")){if(sender!=null)message(sender,"Приглашение отклонено.");return;}
        if(sender==null||EmotePairRules.expired(now,invite.deadline())||!ready(sender,p,r.code())||partners.containsKey(p.getUUID())||partners.containsKey(sender.getUUID())){message(p,"Приглашение истекло или положение игроков изменилось.");return;}
        ServerEmotes.startPair(sender,p,r.code());partners.put(sender.getUUID(),p.getUUID());partners.put(p.getUUID(),sender.getUUID());
    }
    static UUID detach(ServerPlayer p){
        UUID partner=partners.remove(p.getUUID());if(partner!=null)partners.remove(partner);
        for(var id:List.copyOf(incoming.keySet())){var invite=incoming.get(id);if(id.equals(p.getUUID())||invite.sender().equals(p.getUUID())){incoming.remove(id);var target=p.server.getPlayerList().getPlayer(id);if(target!=null)notice(target,invite.sender(),"","",0);}}
        return partner;
    }
    static void tick(net.minecraft.server.MinecraftServer server){
        for(var id:List.copyOf(incoming.keySet())){var invite=incoming.get(id);var a=server.getPlayerList().getPlayer(invite.sender());var b=server.getPlayerList().getPlayer(id);if(a==null||b==null||EmotePairRules.expired(server.getTickCount(),invite.deadline())||!ready(a,b,invite.code())){incoming.remove(id);if(b!=null)notice(b,invite.sender(),"","",0);}}
        for(var id:List.copyOf(partners.keySet())){var partner=partners.get(id);if(partner==null)continue;var a=server.getPlayerList().getPlayer(id);var b=server.getPlayerList().getPlayer(partner);if(a!=null&&(b==null||!ready(a,b,ServerEmotes.current(a))))ServerEmotes.stop(a);}
    }
    static void logout(ServerPlayer p){detach(p);lastInvite.remove(p.getUUID());}
    static void clear(){incoming.clear();partners.clear();lastInvite.clear();}
}
