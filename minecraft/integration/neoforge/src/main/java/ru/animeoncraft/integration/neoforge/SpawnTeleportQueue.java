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
    /** Where the player stood when /spawn was accepted. Rotation is ignored: looking around is allowed. */
    record Origin(String dimension,double x,double y,double z) {}
    /** Walking moves ~0.2 blocks per tick, so a step cancels within a few ticks; a jump in place (≈1.25) does not. */
    static final double MAX_HORIZONTAL=0.5,MAX_VERTICAL=1.3;
    private static final class Pending {
        final long deadline;
        final Origin origin;
        int shown=10;
        Pending(long deadline,Origin origin){this.deadline=deadline;this.origin=origin;}
    }
    private final Map<UUID,Pending> pending=new HashMap<>();
    private final Map<UUID,Long> cooldowns=new HashMap<>();

    Reply request(UUID player,long now){return request(player,now,null);}
    Reply request(UUID player,long now,Origin origin){
        var current=pending.get(player);
        if(current!=null)return new Reply(Status.PENDING,seconds(current.deadline,now));
        int cooldown=seconds(cooldowns.getOrDefault(player,0L),now);
        if(cooldown>0)return new Reply(Status.COOLDOWN,cooldown);
        pending.put(player,new Pending(now+DELAY_MS,origin));
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
    /** True when a pending request must be cancelled because the player left the starting spot. */
    boolean moved(UUID player,Origin current){
        var request=pending.get(player);
        return request!=null&&request.origin!=null&&movedFrom(request.origin,current);
    }
    static boolean movedFrom(Origin start,Origin current){
        if(!start.dimension().equals(current.dimension()))return true;
        double dx=current.x()-start.x(),dz=current.z()-start.z();
        return dx*dx+dz*dz>MAX_HORIZONTAL*MAX_HORIZONTAL||Math.abs(current.y()-start.y())>MAX_VERTICAL;
    }
    void cancel(UUID player){pending.remove(player);}
    List<UUID> pendingPlayers(){return List.copyOf(pending.keySet());}
    Map<UUID,Long> cooldowns(){return Map.copyOf(cooldowns);}
    void restore(UUID player,long until){if(until>0)cooldowns.put(player,until);}
    private static int seconds(long until,long now){return until<=now?0:(int)Math.min(Integer.MAX_VALUE,1+(until-now-1)/1000);}
}
