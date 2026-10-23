package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/** Entry HUD and rate-limited neutral declarations; ownership is decided exclusively by the API. */
final class CaptureEntry {
    private record Attempt(long until, String message) {}
    private static final Map<UUID,ServerBossEvent> BARS = new HashMap<>();
    private static final Map<String,Attempt> ATTEMPTS = new HashMap<>();
    private static boolean inFlight;
    private CaptureEntry() {}
    static String compact(String text,int max) {int n=text.codePointCount(0,text.length());return n<=max?text:text.substring(0,text.offsetByCodePoints(0,max-1))+"…";}
    static String clock(long seconds){seconds=Math.max(0,seconds);return seconds>=3600?String.format("%d:%02d:%02d",seconds/3600,seconds%3600/60,seconds%60):String.format("%d:%02d",seconds/60,seconds%60);}
    static void reset() { BARS.values().forEach(ServerBossEvent::removeAllPlayers); BARS.clear(); ATTEMPTS.clear(); inFlight=false; }

    static CaptureSites.Point here(MinecraftServer server, ServerPlayer player, CaptureSites.View view) {
        if (player.serverLevel()!=server.overworld()) return null;
        int height=view.rules()==null?12:view.rules().getAsJsonObject("presence").get("height").getAsInt();
        return view.points().values().stream().filter(p->CaptureArena.inZone(player,p,CaptureAnchors.height(server,p),height)
            ||p.contains(player.getX(),player.getZ())&&CaptureAnchors.height(server,p)<server.overworld().getMinBuildHeight())
            .min(java.util.Comparator.comparingInt(CaptureSites.Point::radius).thenComparing(CaptureSites.Point::code)).orElse(null);
    }
    static boolean open(JsonObject rules,long now) {
        var time=Instant.ofEpochSecond(now).atZone(ZoneId.of(rules.get("timezone").getAsString()));
        int minute=time.getHour()*60+time.getMinute();
        for(var pair:rules.getAsJsonObject("window").getAsJsonArray("daily")) {
            int start=minutes(pair.getAsJsonArray().get(0).getAsString()),end=minutes(pair.getAsJsonArray().get(1).getAsString());
            if(start<end?minute>=start&&minute<end:minute>=start||minute<end)return true;
        }
        return false;
    }
    private static int minutes(String text) { var p=text.split(":");return Integer.parseInt(p[0])*60+Integer.parseInt(p[1]); }
    static int needed(CaptureSites.Point point,JsonObject rules) {
        return rules.getAsJsonObject("declare").get(point.resource()!=null&&point.resource().tier()==1?"min_players_t1":"min_players").getAsInt();
    }
    static void second(MinecraftServer server,List<ServerPlayer> players,CaptureSites.View view,long now) {
        ATTEMPTS.entrySet().removeIf(e->e.getValue().until()<now-60);
        var presentIds=new java.util.HashSet<UUID>();
        for(var player:players) {
            presentIds.add(player.getUUID());
            var point=here(server,player,view);
            if(point==null||view.eventFor(point.code())!=null) { remove(player.getUUID());continue; }
            var bar=BARS.computeIfAbsent(player.getUUID(),id->new ServerBossEvent(Component.empty(),BossEvent.BossBarColor.WHITE,BossEvent.BossBarOverlay.PROGRESS));
            bar.addPlayer(player);bar.setProgress(0);
            String text;
            if(!CaptureSites.fresh())text="пауза · нет связи с API";
            else if(point.owner()==null&&"NEUTRAL".equals(point.state())) {
                text=neutral(server,players,player,point,view,now,true);
            } else {
                text=point.owner()+" · "+switch(point.state()) {
                    case "PROTECTED" -> "защита "+clock(point.protectionUntil()==null?0:point.protectionUntil().getEpochSecond()-now);
                    case "AVAILABLE" -> "доступна для перезахвата";
                    default -> "под контролем";
                };
                if(point.resource()!=null&&now%12>=6)text=ResourcePits.status(server,players,point,now);
            }
            bar.setName(Component.literal(compact("«"+point.name()+"»: "+text,62)));
            bar.setColor(point.owner()==null?BossEvent.BossBarColor.WHITE:BossEvent.BossBarColor.BLUE);
        }
        for(var id:List.copyOf(BARS.keySet()))if(!presentIds.contains(id))remove(id);
    }
    private static String left(Instant until,long now) { return until==null?"—":CaptureCommands.duration((int)Math.max(0,until.getEpochSecond()-now)); }
    private static void remove(UUID id) { var bar=BARS.remove(id);if(bar!=null)bar.removeAllPlayers(); }
    static ServerMenuPayloads.CaptureSummary summary(MinecraftServer server,ServerPlayer player) {
        var view=CaptureSites.view();var point=here(server,player,view);long now=Instant.now().getEpochSecond();
        if(point==null)return new ServerMenuPayloads.CaptureSummary("Точка захвата поблизости","Войдите в отмеченную на карте точку. /aoc capture points — ближайшие точки.",false);
        String text;boolean can=false;
        if(!CaptureSites.fresh())text="Нет свежих данных сервера захвата.";
        else if(view.eventFor(point.code())!=null){var e=view.eventFor(point.code());var battle=CaptureArena.battle(e.id());text="Захват "+e.declaredBy()+" · "+(battle==null?"подготовка ещё "+left(e.activeStart(),now):battle.summary());}
        else if(point.owner()==null)text=neutral(server,CaptureArena.players(server),player,point,view,now,false);
        else {
            text="Владелец "+point.owner()+" · "+CaptureCommands.STATES.getOrDefault(point.state(),point.state());
            if("PROTECTED".equals(point.state()))text+=" ещё "+left(point.protectionUntil(),now);
            else if(point.resource()!=null)text+=" · "+point.resource().label();
            var identity=GameIdentity.PLAYERS.get(player.getUUID());
            can=open(view.rules(),now)&&CaptureArena.eligible(player,server,now)&&List.of("CONTROLLED","AVAILABLE").contains(point.state())
                &&("AVAILABLE".equals(point.state())||identity!=null&&!point.owner().equals(identity.clan()));
        }
        return new ServerMenuPayloads.CaptureSummary(point.name(),text,can);
    }
    private static String neutral(MinecraftServer server,List<ServerPlayer> players,ServerPlayer player,CaptureSites.Point point,CaptureSites.View view,long now,boolean begin) {
        if(CaptureAnchors.height(server,point)<server.overworld().getMinBuildHeight())return "не задана высота площадки";
        if(!open(view.rules(),now)){
            var ranges=new java.util.ArrayList<String>();for(var pair:view.rules().getAsJsonObject("window").getAsJsonArray("daily"))ranges.add(pair.getAsJsonArray().get(0).getAsString()+"–"+pair.getAsJsonArray().get(1).getAsString());
            String zone=view.rules().get("timezone").getAsString();return "ничья · окно "+String.join(",",ranges)+" "+("Asia/Yekaterinburg".equals(zone)?"ЕКБ":zone);
        }
        if(player.isCreative()||player.isSpectator())return "ничья · нужен режим выживания";
        var identity=GameIdentity.PLAYERS.get(player.getUUID());
        if(identity==null||identity.clan()==null)return "ничья · аккаунт не подтверждён";
        if(!CaptureArena.eligible(player,server,now))return "ничья · ожидание после входа";
        int count=CaptureArena.present(server,players,point,CaptureAnchors.height(server,point),view.rules().getAsJsonObject("presence").get("height").getAsInt(),now).getOrDefault(identity.clan(),0);
        int need=needed(point,view.rules());
        if(count<need)return "ничья · игроков клана в зоне "+count+"/"+need;
        String key=point.code()+":"+identity.clan();
        var attempt=ATTEMPTS.get(key);
        if(attempt!=null&&attempt.until()>now)return "ничья · "+attempt.message();
        if(!begin)return "ничья · захват начнётся автоматически при выполнении условий";
        if(inFlight)return "ничья · проверяем возможность захвата";
        inFlight=true;ATTEMPTS.put(key,new Attempt(now+8,"запускаем захват"));
        try {
            var settings=ServerSettings.from(System.getenv());var body=new JsonObject();
            body.addProperty("server_id",settings.serverId().toString());body.addProperty("point_code",point.code());
            body.addProperty("actor_user_id",identity.userId().toString());body.addProperty("clan_code",identity.clan());
            body.addProperty("players_in_zone",count);body.addProperty("defenders_online",0);body.addProperty("auto_neutral",true);
            HttpJson.post(settings.heartbeatUrl().resolve("../capture/declare"),settings.key(),body).whenComplete((reply,failure)->server.execute(()->{
                inFlight=false;long received=Instant.now().getEpochSecond();
                if(failure!=null)ATTEMPTS.put(key,new Attempt(received+8,"сервис недоступен, повторим автоматически"));
                else if(reply.get("accepted").getAsBoolean()) {
                    ATTEMPTS.put(key,new Attempt(received+10,"захват начат, загружаем подготовку"));CaptureSites.refresh(server);
                } else {
                    String reason=reply.get("reason").getAsString();
                    String retry=reply.has("retry_at")&&!reply.get("retry_at").isJsonNull()?" Ещё "+left(Instant.parse(reply.get("retry_at").getAsString()),received):"";
                    ATTEMPTS.put(key,new Attempt(received+10,CaptureCommands.explain(reason,view.rules())+retry));
                }
            }));
        } catch(RuntimeException unavailable) {inFlight=false;ATTEMPTS.put(key,new Attempt(now+8,"сервис недоступен"));}
        return "ничья · запускаем захват";
    }
}
