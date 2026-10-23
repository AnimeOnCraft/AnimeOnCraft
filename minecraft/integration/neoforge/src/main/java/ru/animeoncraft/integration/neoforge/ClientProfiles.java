package ru.animeoncraft.integration.neoforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientProfiles {
    private static ProfilePayloads.Snapshot profile;
    private static final Map<UUID,String> titles=new HashMap<>();
    private static long requested;
    private static String pending;
    private ClientProfiles(){}
    static ProfilePayloads.Snapshot profile(){return profile;}
    static boolean supported(){var connection=Minecraft.getInstance().getConnection();return connection!=null&&connection.hasChannel(ProfilePayloads.Request.TYPE.id());}
    static void request(String selection){
        if(!supported())return;long now=System.nanoTime();if(!selection.equals("-"))pending=selection;
        if(pending!=null){if(requested!=0&&now-requested<550_000_000L)return;selection=pending;pending=null;}
        else if(requested!=0&&now-requested<5_000_000_000L)return;
        requested=now;PacketDistributor.sendToServer(new ProfilePayloads.Request(selection));
    }
    static void tick(){if(pending!=null)request("-");}
    static void accept(ProfilePayloads.Snapshot payload,IPayloadContext context){context.enqueueWork(()->{var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=context.connection()||mc.player==null||!mc.player.getUUID().equals(payload.player()))return;profile=payload;});}
    static void title(ProfilePayloads.Title payload,IPayloadContext context){context.enqueueWork(()->{var connection=Minecraft.getInstance().getConnection();if(connection==null||connection.getConnection()!=context.connection())return;if(payload.code().isEmpty())titles.remove(payload.player());else titles.put(payload.player(),payload.code());});}
    static String selected(UUID player){return titles.getOrDefault(player,"");}
    static void logout(ClientPlayerNetworkEvent.LoggingOut event){profile=null;titles.clear();requested=0;pending=null;}
    /** Called only inside vanilla's already-approved name-tag rendering path. */
    public static void renderTitle(net.minecraft.world.entity.Entity entity,com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.client.renderer.MultiBufferSource buffers,int light,float partial){
        if(!(entity instanceof net.minecraft.world.entity.player.Player player))return;
        var mc=Minecraft.getInstance();if(mc.player==null||mc.options.hideGui||player.isDiscrete()||player.isInvisibleTo(mc.player))return;
        var title=ProfileProgression.find(selected(player.getUUID()));var beta=ClientBetaPass.title(player.getUUID());if(title==null&&beta==null)return;
        var dispatcher=mc.getEntityRenderDispatcher();if(!net.neoforged.neoforge.client.ClientHooks.isNameplateInRenderDistance(entity,dispatcher.distanceToSqr(entity)))return;
        var anchor=entity.getAttachments().getNullable(net.minecraft.world.entity.EntityAttachment.NAME_TAG,0,entity.getViewYRot(partial));if(anchor==null)return;
        pose.pushPose();pose.translate(anchor.x,anchor.y+.79,anchor.z);pose.mulPose(dispatcher.cameraOrientation());pose.scale(.025f,-.025f,.025f);
        var text=beta!=null?beta:net.minecraft.network.chat.Component.literal(title.title()).withColor(title.color());
        // Both passes obey depth. Separate the foreground from the background plane so
        // deferred font/background batching cannot darken or cover the actual glyphs.
        float x=-mc.font.width(text)/2f;
        mc.font.drawInBatch(text,x,0,0x20ffffff,false,pose.last().pose(),buffers,net.minecraft.client.gui.Font.DisplayMode.NORMAL,0x66000000,light);
        pose.translate(0,0,.03f);
        mc.font.drawInBatch(text,x,0,0xffffffff,false,pose.last().pose(),buffers,net.minecraft.client.gui.Font.DisplayMode.NORMAL,0,net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);pose.popPose();
    }
}
