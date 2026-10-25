package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Summon (gacha) packets. The client only asks; the backend rolls and the snapshot is what it answered. */
final class GachaPayloads {
    private GachaPayloads() {}
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("animeoncraft", name); }
    /** READ, ROLL for crystals or FRAG for fragments (count 1 or 10), WEAR an owned accessory (code), OFF to take it off (code). */
    record Request(String action, int count, String code) implements CustomPacketPayload {
        Request(String action, int count) { this(action, count, ""); }
        Request {
            if (!Set.of("READ", "ROLL", "FRAG", "WEAR", "OFF").contains(action) || count != 0 && count != 1 && count != 10
                || (action.equals("ROLL") || action.equals("FRAG")) != (count != 0)
                || (action.equals("WEAR") || action.equals("OFF")) != BetaPassStore.GACHA_SLOTS.containsKey(code)
                || !code.isEmpty() && !BetaPassStore.GACHA_SLOTS.containsKey(code)) throw new IllegalArgumentException("Invalid gacha request");
        }
        static final Type<Request> TYPE = new Type<>(id("gacha_request_v1"));
        static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of((b, p) -> { b.writeUtf(p.action, 8); b.writeVarInt(p.count); b.writeUtf(p.code, 32); }, b -> new Request(b.readUtf(8), b.readVarInt(), b.readUtf(32)));
        @Override public Type<Request> type() { return TYPE; }
    }
    record Item(String code, String name, String slot, int tier, boolean owned) {
        Item { if (!code.matches("[A-Z][A-Z0-9_]{0,31}") || name.length() > 48 || !slot.matches("[A-Z]{1,8}") || tier < 0 || tier > 5) throw new IllegalArgumentException("Invalid gacha item"); }
        static void write(FriendlyByteBuf b, Item i) { b.writeUtf(i.code, 32); b.writeUtf(i.name, 48); b.writeUtf(i.slot, 8); b.writeVarInt(i.tier); b.writeBoolean(i.owned); }
        static Item read(FriendlyByteBuf b) { return new Item(b.readUtf(32), b.readUtf(48), b.readUtf(8), b.readVarInt(), b.readBoolean()); }
    }
    /** rates are per mille: five, four, three, consolation. pulls: the last roll (code CRYSTALS = consolation, owned=false = a repeat paid in fragments).
     * shards: fragment balance, fragments per roll, drought, drought limit, fragments for a repeated ★3, ★4, ★5. */
    record Snapshot(UUID player, boolean available, String banner, int cost, int balance, int paid, List<Integer> rates, int soft5, int hard5, int hard4, int consolation, int worst,
                    int pity5, int pity4, int total, boolean complete, List<Item> items, List<Item> pulls, String message, List<Integer> shards) implements CustomPacketPayload {
        Snapshot {
            Objects.requireNonNull(player); rates = List.copyOf(rates); shards = List.copyOf(shards); items = List.copyOf(items); pulls = List.copyOf(pulls);
            if (rates.size() != 4 || items.size() > 32 || pulls.size() > 10 || banner.length() > 48 || message.length() > 120 || balance < 0 || paid < 0 || shards.size() != 7 || shards.stream().anyMatch(v -> v < 0)) throw new IllegalArgumentException("Invalid gacha snapshot");
        }
        static Snapshot unavailable(UUID player, String message) { return new Snapshot(player, false, "", 0, 0, 0, List.of(0, 0, 0, 0), 0, 0, 0, 0, 0, 0, 0, 0, false, List.of(), List.of(), message, List.of(0, 0, 0, 0, 0, 0, 0)); }
        static final Type<Snapshot> TYPE = new Type<>(id("gacha_snapshot_v1"));
        static final StreamCodec<FriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.player); b.writeBoolean(p.available); b.writeUtf(p.banner, 48); b.writeVarInt(p.cost); b.writeVarInt(p.balance); b.writeVarInt(p.paid);
            for (int r : p.rates) b.writeVarInt(r); b.writeVarInt(p.soft5); b.writeVarInt(p.hard5); b.writeVarInt(p.hard4); b.writeVarInt(p.consolation); b.writeVarInt(p.worst);
            b.writeVarInt(p.pity5); b.writeVarInt(p.pity4); b.writeVarInt(p.total); b.writeBoolean(p.complete);
            b.writeVarInt(p.items.size()); for (var i : p.items) Item.write(b, i); b.writeVarInt(p.pulls.size()); for (var i : p.pulls) Item.write(b, i); b.writeUtf(p.message, 120); for (int s : p.shards) b.writeVarInt(s);
        }, b -> {
            var player = b.readUUID(); boolean available = b.readBoolean(); String banner = b.readUtf(48); int cost = b.readVarInt(), balance = b.readVarInt(), paid = b.readVarInt();
            var rates = List.of(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()); int soft5 = b.readVarInt(), hard5 = b.readVarInt(), hard4 = b.readVarInt(), consolation = b.readVarInt(), worst = b.readVarInt();
            int pity5 = b.readVarInt(), pity4 = b.readVarInt(), total = b.readVarInt(); boolean complete = b.readBoolean();
            int n = b.readVarInt(); if (n < 0 || n > 32) throw new IllegalArgumentException("Too many items"); var items = new ArrayList<Item>(); for (int i = 0; i < n; i++) items.add(Item.read(b));
            int m = b.readVarInt(); if (m < 0 || m > 10) throw new IllegalArgumentException("Too many pulls"); var pulls = new ArrayList<Item>(); for (int i = 0; i < m; i++) pulls.add(Item.read(b));
            return new Snapshot(player, available, banner, cost, balance, paid, rates, soft5, hard5, hard4, consolation, worst, pity5, pity4, total, complete, items, pulls, b.readUtf(120), List.of(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
        });
        @Override public Type<Snapshot> type() { return TYPE; }
    }
}
