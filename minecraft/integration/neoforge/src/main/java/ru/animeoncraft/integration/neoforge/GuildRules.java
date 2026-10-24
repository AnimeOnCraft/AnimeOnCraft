package ru.animeoncraft.integration.neoforge;

import java.time.Instant;
import java.util.*;

/**
 * Adventurers' Guild rules without Minecraft state: ranks F..S (rank = level), card XP, cooldowns, coin caps and
 * the change for rank fees. Coins are Lightman's Currency "main" chain values in copper (×10 per step).
 */
final class GuildRules {
    /** level 1..7; cooldown after a finished or abandoned quest; XP for a quest of this rank; XP to reach the next rank. */
    enum Rank {
        F(1, 5, 1, 10, 0, 100), E(2, 10, 2, 30, 20, 150), D(3, 15, 3, 60, 50, 200), C(4, 20, 5, 125, 100, 300),
        B(5, 25, 8, 240, 300, 500), A(6, 30, 12, 480, 800, 800), S(7, 35, 20, 0, 2000, 1200);
        final int level, cooldownMinutes, xp, next, fee, dailyCap;
        Rank(int level, int cooldownMinutes, int xp, int next, int fee, int dailyCap) { this.level = level; this.cooldownMinutes = cooldownMinutes; this.xp = xp; this.next = next; this.fee = fee; this.dailyCap = dailyCap; }
        Rank up() { return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null; }
        static Rank of(int ordinal) { return values()[Math.clamp(ordinal, 0, values().length - 1)]; }
    }
    /** Highest rank with solo quests on the board; B and above get team quests in the next stage. */
    static final Rank BOARD_UP_TO = Rank.C;
    /** Highest rank reachable. */
    static final Rank RANK_UP_TO = Rank.S;
    /** From this rank the board shows team quests; a new set every two hours (Ekaterinburg clock), the same for everyone. */
    static final Rank TEAM_FROM = Rank.B;
    static final int TEAM_PER_RANK = 2, MAX_PARTY = 4;
    static final long WINDOW_MILLIS = 2 * 3_600_000L;
    /** The rank-up out of this rank needs the guild exam. */
    static final Rank EXAM_RANK = Rank.C;
    static final int OFFERS = 3;
    /**
     * Coin numbers a server tunes without a new mod build ("economy" in the quest file, then /aocguild reload).
     * caps: coins a day at the full rate per rank; fees: burned to reach the rank; budget: server coins a day at the full rate;
     * overCapPercent: share paid over the player's cap; overBudgetPercent: share paid once the server budget is spent (0 makes it hard);
     * rerollFee: burned to put new quests on the board; teamEntryPercent: share of a team reward per member the leader burns to take it;
     * dailyQuests: hand-ins a day from rank C (Ekaterinburg day), F–D are unlimited.
     */
    record Economy(int[] caps, int[] fees, int budget, int overCapPercent, int overBudgetPercent, int dailyQuests, int rerollFee, int teamEntryPercent) {
        Economy {
            caps = caps.clone(); fees = fees.clone(); int n = Rank.values().length;
            if (caps.length != n || fees.length != n || budget < 0 || budget > 10_000_000 || overCapPercent < 0 || overCapPercent > 100 || overBudgetPercent < 0 || overBudgetPercent > 100 || dailyQuests < 1 || dailyQuests > 100
                || rerollFee < 0 || rerollFee > 1_000_000 || teamEntryPercent < 0 || teamEntryPercent > 100
                || Arrays.stream(caps).anyMatch(v -> v < 0 || v > 1_000_000) || Arrays.stream(fees).anyMatch(v -> v < 0 || v > 10_000_000))
                throw new IllegalArgumentException("Invalid guild economy");
        }
        static final Economy DEFAULT = new Economy(Arrays.stream(Rank.values()).mapToInt(r -> r.dailyCap).toArray(), Arrays.stream(Rank.values()).mapToInt(r -> r.fee).toArray(), 3000, 25, 25, 8, 5, 10);
        @Override public String toString() { return "caps=" + Arrays.toString(caps) + " fees=" + Arrays.toString(fees) + " budget=" + budget + " over=" + overCapPercent + "% over_budget=" + overBudgetPercent + "% daily=" + dailyQuests + " reroll=" + rerollFee + " team_entry=" + teamEntryPercent + "%"; }
    }
    static volatile Economy economy = Economy.DEFAULT;
    static int cap(Rank rank) { return economy.caps()[rank.ordinal()]; }
    /** Fee burned to reach this rank. */
    static int fee(Rank rank) { return economy.fees()[rank.ordinal()]; }
    /** What the leader burns to take a team quest: teamEntryPercent of the reward for each member, rounded up. */
    static long teamEntry(long reward, int members) { return (reward * members * economy.teamEntryPercent() + 99) / 100; }
    static final long[] COINS = {100_000, 10_000, 1_000, 100, 10, 1};
    /** Every player's purse after the coin reset: coins come only from the guild. */
    static final long START_COPPER = 64;
    private static final java.util.regex.Pattern MONEY = java.util.regex.Pattern.compile("lightmanscurrency:(coin|coinpile|coinblock)_(copper|iron|gold|emerald|diamond|netherite)|lightmanscurrency:coin_ancient");
    /** Real money items (coins, piles, blocks, ancient coins); chocolate coins, wallets and machines are not. */
    static boolean coinId(String id) { return MONEY.matcher(id).matches(); }
    static final String[] COIN_IDS = {"lightmanscurrency:coin_netherite", "lightmanscurrency:coin_diamond", "lightmanscurrency:coin_emerald",
        "lightmanscurrency:coin_gold", "lightmanscurrency:coin_iron", "lightmanscurrency:coin_copper"};
    private static final String[] SHORT = {"нез.", "алм.", "изум.", "зол.", "жел.", "мед."};
    private GuildRules() {}

    static String day(long millis) { return Instant.ofEpochMilli(millis).atZone(BetaPass.ZONE).toLocalDate().toString(); }
    static long cooldownMillis(Rank rank) { return rank.cooldownMinutes * 60_000L; }

    /** Card XP for a quest: full for own rank, half one rank below, nothing further down (coins are still paid). */
    static int xpFor(Rank player, Rank quest) {
        if (quest.ordinal() > player.ordinal()) return 0;
        if (quest == player) return quest.xp;
        return quest.ordinal() == player.ordinal() - 1 ? Math.max(1, quest.xp / 2) : 0;
    }
    /** Smallest team for a team quest of this rank: B 2, A 3, S 4. */
    static int partyMin(Rank quest) { return Math.min(MAX_PARTY, 2 + quest.ordinal() - TEAM_FROM.ordinal()); }
    private static long offset(long millis) { return BetaPass.ZONE.getRules().getOffset(Instant.ofEpochMilli(millis)).getTotalSeconds() * 1000L; }
    static long window(long millis) { return Math.floorDiv(millis + offset(millis), WINDOW_MILLIS); }
    static long windowEnds(long millis) { return (window(millis) + 1) * WINDOW_MILLIS - offset(millis); }
    /** Team quest coins for one member: full from the quest's rank, half one rank below, nobody lower takes part. */
    static long teamShare(long reward, Rank member, Rank quest) {
        if (member.ordinal() >= quest.ordinal()) return reward;
        return member.ordinal() == quest.ordinal() - 1 ? reward / 2 : 0;
    }
    static int teamXp(Rank member, Rank quest) {
        if (member.ordinal() >= quest.ordinal()) return quest.xp;
        return member.ordinal() == quest.ordinal() - 1 ? Math.max(1, quest.xp / 2) : 0;
    }
    static int dailyQuests(Rank rank) { return rank.ordinal() >= Rank.C.ordinal() ? economy.dailyQuests() : 0; }
    static boolean canRankUp(Rank rank, int xp, boolean exam) { return rank.up() != null && rank.ordinal() < RANK_UP_TO.ordinal() && xp >= rank.next && (rank != EXAM_RANK || exam); }

    /** Pays the full amount inside both caps; over them overCapPercent, or the smaller overBudgetPercent once the server budget runs out. */
    static long payout(long reward, Rank rank, long earnedToday, long serverIssuedToday) {
        var e = economy; long budgetLeft = e.budget() - serverIssuedToday;
        long full = Math.max(0, Math.min(reward, Math.min(e.caps()[rank.ordinal()] - earnedToday, budgetLeft)));
        int percent = budgetLeft < reward ? Math.min(e.overCapPercent(), e.overBudgetPercent()) : e.overCapPercent();
        return full + (reward - full) * percent / 100;
    }
    /** Reward rolled when the quest is taken, so the board shows the same number at hand-in. */
    static long roll(int min, int max, long seed) { return min + Math.floorMod(new SplittableRandom(seed).nextLong(), (long) max - min + 1); }

    /** Largest coins first. */
    static long[] coins(long copper) {
        if (copper < 0) throw new IllegalArgumentException("Negative amount");
        var out = new long[COINS.length]; long left = copper;
        for (int i = 0; i < COINS.length; i++) { out[i] = left / COINS[i]; left %= COINS[i]; }
        return out;
    }
    static long value(long[] counts) { long v = 0; for (int i = 0; i < COINS.length; i++) v += counts[i] * COINS[i]; return v; }

    /** Coins to take for a fee and the change to hand back: cheapest coins first, then change from the last one. */
    record Payment(long[] take, long change) {}
    static Payment pay(long[] held, long fee) {
        if (value(held) < fee) return null;
        var take = new long[COINS.length]; long paid = 0;
        for (int i = COINS.length - 1; i >= 0 && paid < fee; i--) {
            long need = (fee - paid + COINS[i] - 1) / COINS[i], n = Math.min(held[i], need);
            take[i] = n; paid += n * COINS[i];
        }
        return new Payment(take, paid - fee);
    }

    static String money(long copper) {
        if (copper == 0) return "0 мед.";
        var c = coins(copper); var parts = new ArrayList<String>();
        for (int i = 0; i < COINS.length; i++) if (c[i] > 0) parts.add(c[i] + " " + SHORT[i]);
        return String.join(" ", parts);
    }

    /** Three distinct offers of one rank for a player's board, stable until the seed changes. */
    static List<Integer> offers(int poolSize, long seed, int rank) { return offers(poolSize, seed, rank, OFFERS); }
    static List<Integer> offers(int poolSize, long seed, int rank, int count) {
        if (poolSize <= 0) return List.of();
        var random = new SplittableRandom(seed * 31 + rank); var picked = new LinkedHashSet<Integer>();
        while (picked.size() < Math.min(count, poolSize)) picked.add(random.nextInt(poolSize));
        return List.copyOf(picked);
    }
    /** A paid new board: the next seed whose offers share as few quests as possible with the current ones (none, when the pools allow it). */
    static long freshSeed(long seed, int[] poolSizes) {
        long best = seed + 1; int bestSame = Integer.MAX_VALUE;
        for (long s = seed + 1; s <= seed + 2048 && bestSame > 0; s++) {
            int same = 0;
            for (int r = 0; r < poolSizes.length; r++) { var old = offers(poolSizes[r], seed, r); for (var i : offers(poolSizes[r], s, r)) if (old.contains(i)) same++; }
            if (same < bestSame) { bestSame = same; best = s; }
        }
        return best;
    }
}
