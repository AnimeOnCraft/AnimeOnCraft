package ru.animeoncraft.integration.neoforge;

import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.lwjgl.glfw.GLFW;

final class ClientServerMenu {
    private static ServerMenuData data;private static long received,requested;private static boolean down;
    private static ServerMenuPayloads.CaptureSummary capture;private static long captureReceived;
    private ClientServerMenu(){}
    static boolean supported(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.hasChannel(ServerMenuPayloads.Request.TYPE.id());}
    static boolean enabled(){var c=Minecraft.getInstance().getConnection();return c!=null&&(supported()||c.hasChannel(SkinPayload.TYPE.id()));}
    static boolean held(){return down;}
    static void key(InputEvent.Key event){var mc=Minecraft.getInstance();if(!mc.options.keyPlayerList.matches(event.getKey(),event.getScanCode()))return;if(event.getAction()==GLFW.GLFW_RELEASE)down=false;if(event.getAction()==GLFW.GLFW_PRESS){down=true;if(mc.screen==null&&mc.player!=null&&enabled())mc.setScreen(new ServerMenuScreen());}}
    static void layer(RenderGuiLayerEvent.Pre event){if(Minecraft.getInstance().screen instanceof ServerMenuScreen&&event.getName().equals(VanillaGuiLayers.TAB_LIST))event.setCanceled(true);}
    static void tick(ClientTickEvent.Post event){ClientControls.initialize();ClientProfiles.tick();ClientBetaPass.tick();ClientWardrobe.tick();ClientEmotes.tick();var mc=Minecraft.getInstance();if(!mc.isWindowActive())down=false;if(mc.screen instanceof ServerMenuScreen screen){request();if(screen.page()==ServerMenuLogic.Page.BETA_PASS||screen.page()==ServerMenuLogic.Page.WARDROBE)ClientBetaPass.request("GET",0,"","");if(screen.page()==ServerMenuLogic.Page.WARDROBE){ClientWardrobe.read();ClientEmotes.read();}if(screen.page()==ServerMenuLogic.Page.PROFILE)ClientProfiles.request("-");}}
    static void request(){long now=System.nanoTime();if(!supported()||requested!=0&&now-requested<10_000_000_000L)return;requested=now;PacketDistributor.sendToServer(new ServerMenuPayloads.Request());}
    static void accept(ServerMenuPayloads.Snapshot payload,IPayloadContext context){context.enqueueWork(()->{var c=Minecraft.getInstance().getConnection();if(c==null||c.getConnection()!=context.connection())return;replace(payload.data());});}
    static void replace(ServerMenuData value){data=value;received=System.nanoTime();}
    static void acceptCapture(ServerMenuPayloads.CaptureSummary value,IPayloadContext context){context.enqueueWork(()->{var c=Minecraft.getInstance().getConnection();if(c==null||c.getConnection()!=context.connection())return;capture=value;captureReceived=System.nanoTime();});}
    static ServerMenuPayloads.CaptureSummary capture(){return capture!=null&&System.nanoTime()-captureReceived<15_000_000_000L?capture:null;}
    static ServerMenuData data(){return data!=null&&data.available()&&System.nanoTime()-received<30_000_000_000L?data:null;}
    static String status(){if(!supported())return "Обновление меню ещё не установлено на этом сервере.";if(requested==0||received==0&&System.nanoTime()-requested<7_000_000_000L)return "Загружаем информацию о кланах…";return "Данные кланов временно недоступны. Повторяем запрос…";}
    static List<PlayerInfo> online(){var c=Minecraft.getInstance().getConnection();return c==null?List.of():c.getListedOnlinePlayers().stream().sorted(java.util.Comparator.comparing(p->p.getProfile().getName(),String.CASE_INSENSITIVE_ORDER)).toList();}
    static String clan(UUID id){var d=data();if(d!=null){var m=d.members().stream().filter(p->p.uuid().equals(id)).findFirst();if(m.isPresent())return m.get().clan();}var code=ClientSkins.clan(id);return code==null?"":code;}
    static String ownClan(){var player=Minecraft.getInstance().player;return player==null?"":clan(player.getUUID());}
    static int color(String code){var d=data();return d==null?0xb7a3d4:d.clans().stream().filter(c->c.code().equals(code)).findFirst().map(ServerMenuData.Clan::color).orElse(0xb7a3d4);}
    static void logout(ClientPlayerNetworkEvent.LoggingOut event){data=null;capture=null;captureReceived=0;received=0;requested=0;down=false;var mc=Minecraft.getInstance();if(mc.screen instanceof ServerMenuScreen)mc.setScreen(null);}
}
