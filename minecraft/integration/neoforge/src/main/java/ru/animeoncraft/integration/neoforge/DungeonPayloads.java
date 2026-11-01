package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Rift Gate packets. The client only shows what the server sent; every action is checked again on the server. */
final class DungeonPayloads {
    private DungeonPayloads() {}
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("animeoncraft", name); }
    static final Set<String> ACTIONS = Set.of("GET", "SOLO", "CREATE", "JOIN", "READY", "LEAVE", "START", "EXIT");
    static final int SCREEN_NONE = 0, SCREEN_OPEN = 1;
    private static final String ID = "[a-z0-9_.-]+:[a-z0-9_/.-]+";

    record Request(String action, int dungeon, int difficulty, int target) implements CustomPacketPayload {
        Request { if (!ACTIONS.contains(action) || dungeon < 0 || dungeon > 7 || difficulty < 0 || difficulty > 5 || target < 0 || target > 15) throw new IllegalArgumentException("Invalid dungeon request"); }
        static final Type<Request> TYPE = new Type<>(id("dungeon_request_v1"));
        static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of((b, p) -> { b.writeUtf(p.action, 16); b.writeVarInt(p.dungeon); b.writeVarInt(p.difficulty); b.writeVarInt(p.target); },
            b -> new Request(b.readUtf(16), b.readVarInt(), b.readVarInt(), b.readVarInt()));
        @Override public Type<Request> type() { return TYPE; }
    }
    /** One difficulty of one dungeon: its boss and up to six loot icons (item ids). */
    record Tier(String boss, List<String> icons) {
        Tier { icons = List.copyOf(icons); if (boss.length() > 32 || icons.size() > 6 || icons.stream().anyMatch(i -> !i.matches(ID) || i.length() > 96)) throw new IllegalArgumentException("Invalid dungeon tier"); }
        static void write(FriendlyByteBuf b, Tier t) { b.writeUtf(t.boss, 32); b.writeVarInt(t.icons.size()); for (var i : t.icons) b.writeUtf(i, 96); }
        static Tier read(FriendlyByteBuf b) { var boss = b.readUtf(32); int n = b.readVarInt(); if (n < 0 || n > 6) throw new IllegalArgumentException("Too many icons"); var icons = new ArrayList<String>(); for (int i = 0; i < n; i++) icons.add(b.readUtf(96)); return new Tier(boss, icons); }
    }
    /** cleared: bit per difficulty this player has cleared. */
    record Dungeon(String code, String name, String theme, String description, int rooms, boolean open, int cleared, List<Tier> tiers) {
        Dungeon { tiers = List.copyOf(tiers); if (!code.matches("[a-z0-9_]{3,32}") || name.length() > 32 || theme.length() > 16 || description.length() > 48 || rooms < 0 || rooms > 8 || tiers.size() > 6) throw new IllegalArgumentException("Invalid dungeon view"); }
        static void write(FriendlyByteBuf b, Dungeon d) { b.writeUtf(d.code, 32); b.writeUtf(d.name, 32); b.writeUtf(d.theme, 16); b.writeUtf(d.description, 48); b.writeVarInt(d.rooms); b.writeBoolean(d.open); b.writeVarInt(d.cleared); b.writeVarInt(d.tiers.size()); for (var t : d.tiers) Tier.write(b, t); }
        static Dungeon read(FriendlyByteBuf b) {
            var code = b.readUtf(32); var name = b.readUtf(32); var theme = b.readUtf(16); var description = b.readUtf(48); int rooms = b.readVarInt(); boolean open = b.readBoolean(); int cleared = b.readVarInt();
            int n = b.readVarInt(); if (n < 0 || n > 6) throw new IllegalArgumentException("Too many tiers"); var tiers = new ArrayList<Tier>(); for (int i = 0; i < n; i++) tiers.add(Tier.read(b));
            return new Dungeon(code, name, theme, description, rooms, open, cleared, tiers);
        }
    }
    /** health/damage: multipliers ×10; access: 0 full, 1 half loot, 2 closed for this player. */
    /** timeLimit: seconds for the whole run (0 none); affixes: the week's affix applies. */
    record Difficulty(String name, int fee, int loss, int minRank, int minPlayers, int health, int damage, int lives, boolean teamLives, int crystals, int access, int timeLimit, boolean affixes) {
        Difficulty { if (name.length() > 24 || fee < 0 || loss < 0 || loss > 100 || minRank < 0 || minRank > 6 || minPlayers < 1 || minPlayers > 4 || health < 0 || damage < 0 || lives < 0 || crystals < 0 || access < 0 || access > 2 || timeLimit < 0) throw new IllegalArgumentException("Invalid difficulty view"); }
        static void write(FriendlyByteBuf b, Difficulty d) { b.writeUtf(d.name, 24); b.writeVarInt(d.fee); b.writeVarInt(d.loss); b.writeVarInt(d.minRank); b.writeVarInt(d.minPlayers); b.writeVarInt(d.health); b.writeVarInt(d.damage); b.writeVarInt(d.lives); b.writeBoolean(d.teamLives); b.writeVarInt(d.crystals); b.writeVarInt(d.access); b.writeVarInt(d.timeLimit); b.writeBoolean(d.affixes); }
        static Difficulty read(FriendlyByteBuf b) { return new Difficulty(b.readUtf(24), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean()); }
    }
    record Member(String name, int rank, boolean ready) {
        Member { if (name.length() > 24 || rank < -1 || rank > 6) throw new IllegalArgumentException("Invalid lobby member"); }
    }
    /** An open team at the gate: index is what JOIN sends as target. */
    record Lobby(int index, String leader, int dungeon, int difficulty, int members, int max) {
        Lobby { if (index < 0 || index > 15 || leader.length() > 24 || dungeon < 0 || dungeon > 7 || difficulty < 0 || difficulty > 5 || members < 0 || members > 4 || max < 1 || max > 4) throw new IllegalArgumentException("Invalid lobby"); }
    }
    /** lobbyDungeon -1: the player is in no lobby. */
    record Snapshot(int rank, int chests, int limit, long coins, boolean inRun, List<Dungeon> dungeons, List<Difficulty> difficulties,
                    List<Member> members, boolean leader, int lobbyDungeon, int lobbyDifficulty, List<Lobby> lobbies, int screen, String message, String affix) implements CustomPacketPayload {
        Snapshot {
            dungeons = List.copyOf(dungeons); difficulties = List.copyOf(difficulties); members = List.copyOf(members); lobbies = List.copyOf(lobbies);
            if (rank < -1 || rank > 6 || chests < 0 || limit < 0 || coins < 0 || dungeons.size() > 8 || difficulties.size() > 6 || members.size() > 4 || lobbies.size() > 16
                || lobbyDungeon < -1 || lobbyDungeon > 7 || lobbyDifficulty < -1 || lobbyDifficulty > 5 || screen < 0 || screen > 1 || message.length() > 120 || affix.length() > 96) throw new IllegalArgumentException("Invalid dungeon snapshot");
        }
        static final Type<Snapshot> TYPE = new Type<>(id("dungeon_snapshot_v1"));
        static final StreamCodec<FriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.rank + 1); b.writeVarInt(p.chests); b.writeVarInt(p.limit); b.writeVarLong(p.coins); b.writeBoolean(p.inRun);
            b.writeVarInt(p.dungeons.size()); for (var d : p.dungeons) Dungeon.write(b, d);
            b.writeVarInt(p.difficulties.size()); for (var d : p.difficulties) Difficulty.write(b, d);
            b.writeVarInt(p.members.size()); for (var m : p.members) { b.writeUtf(m.name(), 24); b.writeVarInt(m.rank() + 1); b.writeBoolean(m.ready()); }
            b.writeBoolean(p.leader); b.writeVarInt(p.lobbyDungeon + 1); b.writeVarInt(p.lobbyDifficulty + 1);
            b.writeVarInt(p.lobbies.size()); for (var l : p.lobbies) { b.writeVarInt(l.index()); b.writeUtf(l.leader(), 24); b.writeVarInt(l.dungeon()); b.writeVarInt(l.difficulty()); b.writeVarInt(l.members()); b.writeVarInt(l.max()); }
            b.writeVarInt(p.screen); b.writeUtf(p.message, 120); b.writeUtf(p.affix, 96);
        }, b -> {
            int rank = b.readVarInt() - 1, chests = b.readVarInt(), limit = b.readVarInt(); long coins = b.readVarLong(); boolean inRun = b.readBoolean();
            int n = b.readVarInt(); if (n < 0 || n > 8) throw new IllegalArgumentException("Too many dungeons"); var dungeons = new ArrayList<Dungeon>(); for (int i = 0; i < n; i++) dungeons.add(Dungeon.read(b));
            n = b.readVarInt(); if (n < 0 || n > 6) throw new IllegalArgumentException("Too many difficulties"); var difficulties = new ArrayList<Difficulty>(); for (int i = 0; i < n; i++) difficulties.add(Difficulty.read(b));
            n = b.readVarInt(); if (n < 0 || n > 4) throw new IllegalArgumentException("Too many members"); var members = new ArrayList<Member>(); for (int i = 0; i < n; i++) members.add(new Member(b.readUtf(24), b.readVarInt() - 1, b.readBoolean()));
            boolean leader = b.readBoolean(); int ld = b.readVarInt() - 1, lf = b.readVarInt() - 1;
            n = b.readVarInt(); if (n < 0 || n > 16) throw new IllegalArgumentException("Too many lobbies"); var lobbies = new ArrayList<Lobby>();
            for (int i = 0; i < n; i++) lobbies.add(new Lobby(b.readVarInt(), b.readUtf(24), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
            int screen = b.readVarInt(); var message = b.readUtf(120);
            return new Snapshot(rank, chests, limit, coins, inRun, dungeons, difficulties, members, leader, ld, lf, lobbies, screen, message, b.readUtf(96));
        });
        @Override public Type<Snapshot> type() { return TYPE; }
    }
}
