package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Production chat rendering with synthetic review messages, only in a disposable integrated world. */
@Mod("aocnextchatprobe")
public final class NextChatClientProbe {
    private int ticks,phase,frames;
    public NextChatClientProbe(){
        if(!Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("menu-client-"))throw new IllegalStateException("Disposable review directory required");
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::tick);
    }
    private void shot(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{});}
    private void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();ticks++;
        if(mc.player==null){if(mc.screen!=null&&ticks%30==0)for(var c:mc.screen.children())if(c instanceof Button b&&b.active&&java.util.Set.of("Proceed","I know what I'm doing!","Yes").contains(b.getMessage().getString()))b.onPress();return;}
        if(ticks<130)return;
        try{
            if(phase==0){
                // ClientPlayer exists before the initial inventory synchronization.
                if(mc.player.getInventory().countItem(net.minecraft.world.item.Items.WRITTEN_BOOK)!=1){if(++frames>200)throw new IllegalStateException("Real joining client did not receive exactly one guide book");return;}
                mc.getSingleplayerServer().execute(()->{
                    var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new ChatPayloads.Line(1,UUID.randomUUID(),"PacketProbe","NYA",2,"","",false,"AOC_REAL_CHAT_PACKET"));
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new ChatPayloads.Notice(3,0,"AOC_REAL_NOTICE_PACKET"));
                });
                phase=10;frames=0;
            }else if(phase==10){
                boolean line=ClientChat.LOG.view(ChatLog.Tab.GLOBAL).stream().anyMatch(e->e.line().line()!=null&&e.line().line().text().equals("AOC_REAL_CHAT_PACKET"));
                boolean notice=ClientChat.LOG.view(ChatLog.Tab.INFO).stream().anyMatch(e->e.line().notice()!=null&&e.line().notice().text().equals("AOC_REAL_NOTICE_PACKET"));
                if(line&&notice){System.out.println("AOC_REAL_CHAT_PAYLOAD_PASS integrated_connection=true guidebook_login=true");phase=11;}
                else if(++frames>200)throw new IllegalStateException("Real chat payload was not received");
            }else if(phase==11){
                ClientChat.SETTINGS.enabled=true;ClientChat.SETTINGS.time=true;
                ClientChat.LOG.clear();
                for(int i=0;i<28;i++){
                    int tab=i%3;
                    var line=new ChatPayloads.Line(tab,UUID.randomUUID(),i%2==0?"Akira":"Sora",i%2==0?"NYA":"SCAM",2,"","",false,
                        i==27?"Привет! Гильдия и арена ждут: https://example.test/guide":"Сообщение для проверки истории "+(i+1)+" · собираемся на приключение");
                    ClientChat.LOG.add(ChatLog.Tab.of(tab).bit(),new ClientChat.Item(line,null,null),System.currentTimeMillis(),false);
                }
                ClientChat.LOG.add(ChatLog.CHAT_TABS,new ClientChat.Item(null,null,Component.literal("Akira шепчет вам: встретимся у гильдии")),System.currentTimeMillis(),false);
                ClientChat.LOG.add(ChatLog.Tab.INFO.bit(),new ClientChat.Item(null,null,Component.literal("Телепортация отменена: движение. Перерыв не начислен.")),System.currentTimeMillis(),false);
                ClientChat.LOG.select(ChatLog.Tab.GLOBAL);
                mc.options.guiScale().set(2);mc.resizeDisplay();mc.setScreen(new ChatScreen(""));phase=1;frames=0;
            }else if(phase==1&&++frames==25){shot("next-chat-scale2");mc.options.guiScale().set(3);mc.resizeDisplay();mc.setScreen(new ChatScreen(""));phase=2;frames=0;}
            else if(phase==2&&++frames==25){shot("next-chat-scale3");ClientChat.gearOpen=true;phase=3;frames=0;}
            else if(phase==3&&++frames==25){shot("next-chat-settings-scale3");ClientChat.gearOpen=false;ClientChat.emojiOpen=true;phase=4;frames=0;}
            else if(phase==4&&++frames==25){shot("next-chat-emoji-scale3");phase=5;frames=0;}
            else if(phase==5&&++frames==25){
                var toggle=ClientChat.class.getDeclaredMethod("toggle",int.class);toggle.setAccessible(true);
                int original=ClientChat.LOG.since(0,true).size();
                toggle.invoke(null,0);ClientChat.tick(null);
                var field=net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");field.setAccessible(true);
                int mirrored=((java.util.List<?>)field.get(mc.gui.getChat())).size();
                toggle.invoke(null,0);ClientChat.tick(null);
                if(ClientChat.LOG.since(0,true).size()!=original)throw new IllegalStateException("Toggle duplicated log entries");
                toggle.invoke(null,0);ClientChat.tick(null);
                if(((java.util.List<?>)field.get(mc.gui.getChat())).size()!=mirrored)throw new IllegalStateException("Repeated toggle duplicated vanilla mirror");
                toggle.invoke(null,0);
                for(int setting=1;setting<5;setting++){boolean before=ClientChat.SETTINGS.get(setting);toggle.invoke(null,setting);if(ClientChat.SETTINGS.get(setting)==before)throw new IllegalStateException("Setting did not toggle");toggle.invoke(null,setting);}
                System.out.println("AOC_NEXT_CHAT_CLIENT_PASS synthetic_review_messages=true gui_scales=2,3 toggle_no_duplicates=true gear_settings=true real_chat_payloads=true guidebook_login=true");mc.stop();phase=6;
            }
        }catch(Throwable failure){failure.printStackTrace();System.out.println("AOC_NEXT_CHAT_CLIENT_FAILED "+failure.getMessage());mc.stop();phase=6;}
    }
}
