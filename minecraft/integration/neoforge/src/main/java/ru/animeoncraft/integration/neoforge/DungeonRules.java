package ru.animeoncraft.integration.neoforge;

import java.util.*;

/**
 * Rift Gate rules without Minecraft state (unit-tested): slots of the rift dimension, what a death in a run takes,
 * who may enter which difficulty, team scaling, grades, the daily chest limit and what is forbidden inside a run.
 */
final class DungeonRules {
    /** Runs live on a grid of 1024 blocks in animeoncraft:rift; at most {@link #SLOTS} at once. */
    static final int SPACING = 1024, SLOTS = 8, COLUMNS = 4, FLOOR = 64;
    /** Room chain starts this far inside a slot cell, so a run never touches the next cell or region file edge. */
    static final int MARGIN = 256;
    private DungeonRules() {}

    enum Access { FULL, HALF, NONE }
    enum Grade { S, A, B, C }

    static int slotX(int slot) { check(slot); return (slot % COLUMNS) * SPACING + MARGIN; }
    static int slotZ(int slot) { check(slot); return (slot / COLUMNS) * SPACING + MARGIN; }
    /** The slot whose cell holds the block column, or -1 outside the grid. */
    static int slotAt(double x, double z) {
        if (x < 0 || z < 0) return -1;
        int col = (int) Math.floor(x / SPACING), row = (int) Math.floor(z / SPACING);
        if (col >= COLUMNS) return -1;
        int slot = row * COLUMNS + col; return slot < SLOTS ? slot : -1;
    }
    /** First free slot, or -1 when all are busy. */
    static int freeSlot(Set<Integer> busy) { for (int s = 0; s < SLOTS; s++) if (!busy.contains(s)) return s; return -1; }
    private static void check(int slot) { if (slot < 0 || slot >= SLOTS) throw new IllegalArgumentException("Rift slot " + slot); }

    /** Stacks lost on a death: the share of the occupied stacks, rounded up, at least one while anything is there. */
    static int lossCount(int stacks, int percent) {
        if (stacks <= 0 || percent <= 0) return 0;
        return Math.min(stacks, Math.max(1, (stacks * percent + 99) / 100));
    }
    /** Which of the occupied main-inventory slots are lost, chosen at random from the death's seed. */
    static List<Integer> lostSlots(List<Integer> occupied, int percent, long seed) {
        var pool = new ArrayList<>(occupied); Collections.shuffle(pool, new Random(seed));
        var out = new ArrayList<>(pool.subList(0, lossCount(pool.size(), percent))); Collections.sort(out); return out;
    }
    /** Main inventory and hotbar only; worn armour, the off hand and Curios live outside these 36 slots. */
    static final int MAIN_SLOTS = 36;
    /** Item ids a death never takes even in the main inventory: backpacks and the Lightman's wallet. */
    static boolean protectedItem(String id) {
        int colon = id.indexOf(':'); String ns = colon < 0 ? "minecraft" : id.substring(0, colon), path = id.substring(colon + 1);
        return ns.equals("sophisticatedbackpacks") || path.contains("backpack") || ns.equals("lightmanscurrency") && path.contains("wallet");
    }

    /** A guild card of the difficulty's rank gets full loot; one rank lower may join a team with someone of full rank for half the loot. */
    static Access access(int rank, int minRank) {
        if (rank < 0) return Access.NONE;
        return rank >= minRank ? Access.FULL : rank == minRank - 1 ? Access.HALF : Access.NONE;
    }
    /** Null when the team may start, otherwise the reason. ranks: guild ranks of the members (-1 without a card). */
    static String teamCheck(List<Integer> ranks, int minRank, int minPlayers, int maxPlayers) {
        if (ranks.size() < minPlayers) return "Нужно от " + minPlayers + " игроков.";
        if (ranks.size() > maxPlayers) return "Не больше " + maxPlayers + " игроков.";
        boolean full = false;
        for (int r : ranks) { var a = access(r, minRank); if (a == Access.NONE) return r < 0 ? "У всех нужна карточка гильдии." : "Ранг участника слишком низкий."; full |= a == Access.FULL; }
        return full ? null : "Нужен хотя бы один участник ранга " + GuildRules.Rank.of(minRank) + ".";
    }
    /** Mob health grows with the team so a solo player and four players take about as long. */
    static double teamHealth(int players, double perPlayer) { return 1 + perPlayer * Math.max(0, players - 1); }
    /** Mobs of one fight wave: a base for one player plus two per extra player, at most 12. */
    static int waveSize(int base, int players) { return Math.min(12, base + 2 * Math.max(0, players - 1)); }

    /** Grade from the time against the dungeon's par and the deaths of the whole team. */
    static Grade grade(long seconds, int deaths, long par) {
        if (seconds <= par && deaths == 0) return Grade.S;
        if (seconds <= par * 3 / 2 && deaths <= 1) return Grade.A;
        if (seconds <= par * 2 || deaths <= 3) return Grade.B;
        return Grade.C;
    }
    /** Extra loot rolls a grade gives. */
    static int gradeRolls(Grade grade) { return switch (grade) { case S -> 2; case A -> 1; default -> 0; }; }
    /** Half loot rounds up, so a half member always gets something. */
    static int rolls(int base, Grade grade, Access access) { int n = base + gradeRolls(grade); return access == Access.HALF ? (n + 1) / 2 : n; }

    /** One idempotent backend reference per player, dungeon and difficulty: the first clear pays once. */
    static String crystalRef(String dungeon, String difficulty) { return "dungeon:" + dungeon + ":" + difficulty.toLowerCase(Locale.ROOT); }

    /** Commands a player cannot use inside a run (the base command without the slash). */
    private static final Set<String> COMMANDS = Set.of("spawn", "home", "homes", "sethome", "back", "tpa", "tpahere", "tpaccept", "rtp", "warp", "warps", "waystones", "trigger");
    static boolean blockedCommand(String input) {
        var s = input.strip(); if (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' '); var root = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':'); if (colon >= 0) root = root.substring(colon + 1);
        return COMMANDS.contains(root);
    }
    /** Items whose use would leave or skip the run: ender pearls, chorus fruit and anything from Waystones. */
    static boolean blockedItem(String id) {
        return id.equals("minecraft:ender_pearl") || id.equals("minecraft:chorus_fruit") || id.startsWith("waystones:");
    }

    /** Week number counted from Monday in Yekaterinburg (the guild calendar): affixes change on Mondays. */
    static long week(long millis) { return Math.floorDiv(java.time.Instant.ofEpochMilli(millis).atZone(BetaPass.ZONE).toLocalDate().toEpochDay() + 3, 7); }
    /** The affix of a week: the catalog list in turn, the same for everyone. */
    static int affix(long week, int count) { return count <= 0 ? -1 : (int) Math.floorMod(week, (long) count); }
    /** Seconds left of a timed difficulty (Mythic), or -1 without a limit. */
    static long timeLeft(long startedAt, long now, int limitSeconds) { return limitSeconds <= 0 ? -1 : Math.max(0, limitSeconds - (now - startedAt) / 1000); }
    /**
     * A guild DUNGEON quest target: {@code dungeon:<code or any>/<lowest difficulty>}. True when a clear of this dungeon
     * at this difficulty counts; difficulties are compared in the catalog order.
     */
    static boolean questMatches(String target, String dungeon, String difficulty, List<String> order) {
        if (!target.startsWith("dungeon:")) return false;
        var body = target.substring(8); int slash = body.indexOf('/'); if (slash < 0) return false;
        var code = body.substring(0, slash); int min = order.indexOf(body.substring(slash + 1).toUpperCase(Locale.ROOT)), got = order.indexOf(difficulty);
        return min >= 0 && got >= min && (code.equals("any") || code.equals(dungeon));
    }

    /** A player who left the game may come back into the run within this window. */
    static boolean reconnect(long leftAt, long now, int seconds) { return leftAt > 0 && now - leftAt <= seconds * 1000L; }
    /** Reward chests left today (Ekaterinburg day as the guild board). */
    static int chestsLeft(int openedToday, int limit) { return Math.max(0, limit - openedToday); }
}
