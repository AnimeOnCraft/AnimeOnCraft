package ru.animeoncraft.integration.neoforge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/** Server-owned deadlines; pending requests are deliberately not persisted. */
final class SpawnTeleportQueue {
    static final long DELAY_MS=10_000,COOLDOWN_MS=300_000;
    enum Status { STARTED, PENDING, COOLDOWN }
    record Reply(Status status,int seconds) {}
    private static final class Pending {
        final long deadline;
        int shown=10;
        Pending(long deadline){this.deadline=deadline;}
    }
    private final Map<UUID,Pending> pending=new HashMap<>();
    private final Map<UUID,Long> cooldowns=new HashMap<>();

    Reply request(UUID player,long now){
        var current=pending.get(player);
        if(current!=null)return new Reply(Status.PENDING,seconds(current.deadline,now));
        int cooldown=seconds(cooldowns.getOrDefault(player,0L),now);
        if(cooldown>0)return new Reply(Status.COOLDOWN,cooldown);
        pending.put(player,new Pending(now+DELAY_MS));
        return new Reply(Status.STARTED,10);
    }
    OptionalInt advance(UUID player,long now){
        var current=pending.get(player);
        if(current==null)return OptionalInt.empty();
        int remaining=seconds(current.deadline,now);
        if(remaining>0&&remaining==current.shown)return OptionalInt.empty();
        current.shown=remaining;
        return OptionalInt.of(remaining);
    }
    boolean succeeded(UUID player,long now){
        var current=pending.get(player);
        if(current==null||now<current.deadline)return false;
        pending.remove(player);cooldowns.put(player,now+COOLDOWN_MS);return true;
    }
    void cancel(UUID player){pending.remove(player);}
    List<UUID> pendingPlayers(){return List.copyOf(pending.keySet());}
    Map<UUID,Long> cooldowns(){return Map.copyOf(cooldowns);}
    void restore(UUID player,long until){if(until>0)cooldowns.put(player,until);}
    private static int seconds(long until,long now){return until<=now?0:(int)Math.min(Integer.MAX_VALUE,1+(until-now-1)/1000);}
}
