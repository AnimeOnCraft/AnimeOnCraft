package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Guild board packets. The client only shows the server's card; every action is checked again on the server. */
final class GuildPayloads {
    private GuildPayloads() {}
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("animeoncraft", name); }
    static final Set<String> ACTIONS = Set.of("GET", "TAKE", "TURN_IN", "ABANDON", "RANK_UP", "EXAM", "TEAM_TAKE", "TEAM_ABANDON", "REROLL");
    static final int SCREEN_NONE = 0, SCREEN_BOARD = 1, SCREEN_WELCOME = 2;

    record Request(String action, int rank, int index) implements CustomPacketPayload {
        Request { if (!ACTIONS.contains(action) || rank < 0 || rank > 6 || index < 0 || index >= GuildRules.OFFERS) throw new IllegalArgumentException("Invalid guild request"); }
        static final Type<Request> TYPE = new Type<>(id("guild_request_v1"));
        static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of((b, p) -> { b.writeUtf(p.action, 16); b.writeVarInt(p.rank); b.writeVarInt(p.index); }, b -> new Request(b.readUtf(16), b.readVarInt(), b.readVarInt()));
        @Override public Type<Request> type() { return TYPE; }
    }
    /** One quest as the board shows it; icon is an item id (the first item of a tag). party: smallest team (0 solo); done: this player did it in this window. */
    record Quest(String code, int rank, boolean kill, String icon, int count, String name, String task, long min, long max, int xp, int party, boolean done) {
        Quest {
            if (!code.matches("[a-z0-9_]{3,32}") || rank < 0 || rank > 6 || count < 1 || count > 512 || min < 0 || max < min || xp < 0 || party < 0 || party > 4 || name.length() > 48 || task.length() > 64
                || !icon.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || icon.length() > 96) throw new IllegalArgumentException("Invalid guild quest");
        }
        static void write(FriendlyByteBuf b, Quest q) { b.writeUtf(q.code, 32); b.writeVarInt(q.rank); b.writeBoolean(q.kill); b.writeUtf(q.icon, 96); b.writeVarInt(q.count); b.writeUtf(q.name, 48); b.writeUtf(q.task, 64); b.writeVarLong(q.min); b.writeVarLong(q.max); b.writeVarInt(q.xp); b.writeVarInt(q.party); b.writeBoolean(q.done); }
        static Quest read(FriendlyByteBuf b) { return new Quest(b.readUtf(32), b.readVarInt(), b.readBoolean(), b.readUtf(96), b.readVarInt(), b.readUtf(48), b.readUtf(64), b.readVarLong(), b.readVarLong(), b.readVarInt(), b.readVarInt(), b.readBoolean()); }
    }
    /** progress = kills so far, or matching items carried for an item quest. done/limit: quests today (limit 0 = unlimited). exam: 0 not needed, 1 needed, 2 passed. */
    record Snapshot(boolean opened, int rank, int xp, int next, int completed, int cooldown, long earned, long cap, boolean canRankUp, long fee, boolean maxRank,
                    int done, int limit, int exam, List<Quest> offers, Quest active, int progress, int screen, String message,
                    List<Quest> team, List<String> members, boolean leader, Quest teamActive, int teamProgress, int window, long reroll, int teamEntry) implements CustomPacketPayload {
        Snapshot {
            offers = List.copyOf(offers); team = List.copyOf(team); members = List.copyOf(members);
            if (team.size() > 9 || members.size() > 4 || teamProgress < 0 || window < 0 || reroll < 0 || teamEntry < 0 || teamEntry > 100 || members.stream().anyMatch(m -> m.length() > 40)) throw new IllegalArgumentException("Invalid guild team");
            if (rank < 0 || rank > 6 || xp < 0 || next < 0 || completed < 0 || cooldown < 0 || earned < 0 || cap < 0 || fee < 0 || done < 0 || limit < 0 || exam < 0 || exam > 2 || offers.size() > 21 || progress < 0 || screen < 0 || screen > 2 || message.length() > 120)
                throw new IllegalArgumentException("Invalid guild snapshot");
        }
        static final Type<Snapshot> TYPE = new Type<>(id("guild_snapshot_v1"));
        static final StreamCodec<FriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((b, p) -> {
            b.writeBoolean(p.opened); b.writeVarInt(p.rank); b.writeVarInt(p.xp); b.writeVarInt(p.next); b.writeVarInt(p.completed); b.writeVarInt(p.cooldown); b.writeVarLong(p.earned); b.writeVarLong(p.cap);
            b.writeBoolean(p.canRankUp); b.writeVarLong(p.fee); b.writeBoolean(p.maxRank); b.writeVarInt(p.done); b.writeVarInt(p.limit); b.writeVarInt(p.exam); b.writeVarInt(p.offers.size()); for (var q : p.offers) Quest.write(b, q);
            b.writeBoolean(p.active != null); if (p.active != null) Quest.write(b, p.active); b.writeVarInt(p.progress); b.writeVarInt(p.screen); b.writeUtf(p.message, 120);
            b.writeVarInt(p.team.size()); for (var q : p.team) Quest.write(b, q); b.writeVarInt(p.members.size()); for (var m : p.members) b.writeUtf(m, 40);
            b.writeBoolean(p.leader); b.writeBoolean(p.teamActive != null); if (p.teamActive != null) Quest.write(b, p.teamActive); b.writeVarInt(p.teamProgress); b.writeVarInt(p.window); b.writeVarLong(p.reroll); b.writeVarInt(p.teamEntry);
        }, b -> {
            boolean opened = b.readBoolean(); int rank = b.readVarInt(), xp = b.readVarInt(), next = b.readVarInt(), completed = b.readVarInt(), cooldown = b.readVarInt(); long earned = b.readVarLong(), cap = b.readVarLong();
            boolean up = b.readBoolean(); long fee = b.readVarLong(); boolean max = b.readBoolean(); int done = b.readVarInt(), limit = b.readVarInt(), exam = b.readVarInt(); int n = b.readVarInt(); if (n < 0 || n > 21) throw new IllegalArgumentException("Too many offers");
            var offers = new ArrayList<Quest>(); for (int i = 0; i < n; i++) offers.add(Quest.read(b));
            var active = b.readBoolean() ? Quest.read(b) : null;
            int progress = b.readVarInt(), screen = b.readVarInt(); String message = b.readUtf(120);
            int t = b.readVarInt(); if (t < 0 || t > 9) throw new IllegalArgumentException("Too many team quests");
            var team = new ArrayList<Quest>(); for (int i = 0; i < t; i++) team.add(Quest.read(b));
            int m = b.readVarInt(); if (m < 0 || m > 4) throw new IllegalArgumentException("Too many members");
            var members = new ArrayList<String>(); for (int i = 0; i < m; i++) members.add(b.readUtf(40));
            boolean leader = b.readBoolean(); var teamActive = b.readBoolean() ? Quest.read(b) : null;
            return new Snapshot(opened, rank, xp, next, completed, cooldown, earned, cap, up, fee, max, done, limit, exam, offers, active, progress, screen, message,
                team, members, leader, teamActive, b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readVarInt());
        });
        @Override public Type<Snapshot> type() { return TYPE; }
    }
    /** The guild rank shown next to a player's name to everyone nearby; -1: no adventurer card. */
    record Badge(UUID player, int rank) implements CustomPacketPayload {
        Badge { Objects.requireNonNull(player); if (rank < -1 || rank > 6) throw new IllegalArgumentException("Invalid guild rank"); }
        static final Type<Badge> TYPE = new Type<>(id("guild_rank_v1"));
        static final StreamCodec<FriendlyByteBuf, Badge> CODEC = StreamCodec.of((b, p) -> { b.writeUUID(p.player); b.writeVarInt(p.rank + 1); }, b -> new Badge(b.readUUID(), b.readVarInt() - 1));
        @Override public Type<Badge> type() { return TYPE; }
    }
}
