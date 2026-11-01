package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * One run in a rift slot: building, rooms with waves behind closed doors, lives, the boss, personal chests, the grade
 * and the way back. Driven twice a second by {@link DungeonServer}; everything the next start needs is in {@link DungeonStore}.
 */
final class DungeonRun {
    enum State { BUILDING, ACTIVE, VICTORY, FAILED, ENDED }
    static final String TAG = "animeoncraft_dungeon_run";
    static final class Member {
        final UUID id; final String name; final DungeonRules.Access access; final long fee;
        int lives, deaths, rolled; boolean watcher, out, treasure; long leftAt; GameType mode = GameType.SURVIVAL;
        final SimpleContainer chest = new SimpleContainer(27);
        final ServerBossEvent bar = new ServerBossEvent(Component.literal("Врата Разлома"), BossEvent.BossBarColor.PINK, BossEvent.BossBarOverlay.NOTCHED_10);
        Member(UUID id, String name, DungeonRules.Access access, long fee) { this.id = id; this.name = name; this.access = access; this.fee = fee; }
        boolean active(long now, int reconnect) { return !out && !watcher && (leftAt == 0 || DungeonRules.reconnect(leftAt, now, reconnect)); }
    }
    final long id, seed; final int slot, ox, oz;
    final DungeonCatalog.Dungeon dungeon; final DungeonCatalog.Difficulty difficulty;
    final List<DungeonLayout.Room> rooms;
    final Map<UUID, Member> members = new LinkedHashMap<>();
    State state = State.BUILDING;
    DungeonBuilder.Work work;
    int room, wave, teamLives, deaths, ticks;
    boolean roomStarted;
    final Set<UUID> mobs = new HashSet<>();
    UUID boss; ServerBossEvent bossBar; long nextSummon;
    long startedAt, endsAt, finishedSeconds;
    DungeonRules.Grade grade;
    BlockPos treasureChest; final List<BlockPos> chests = new ArrayList<>();

    /** The week's affix of a Nightmare or Mythic run, fixed at the start; null on easier difficulties. */
    final DungeonCatalog.Affix affix;
    static final String ELITE = "animeoncraft_dungeon_elite";
    DungeonRun(long id, int slot, DungeonCatalog.Dungeon dungeon, DungeonCatalog.Difficulty difficulty, long seed, DungeonCatalog.Affix affix) {
        this.id = id; this.slot = slot; this.dungeon = dungeon; this.difficulty = difficulty; this.seed = seed; this.affix = difficulty.affixes() ? affix : null;
        ox = DungeonRules.slotX(slot); oz = DungeonRules.slotZ(slot); rooms = DungeonLayout.plan(seed, dungeon.rooms());
        teamLives = difficulty.lives(); work = new DungeonBuilder.Build(rooms, ox, oz, seed);
    }
    String key() { return dungeon.code() + ":" + difficulty.code(); }
    Member member(UUID id) { return members.get(id); }
    int active(long now, int reconnect) { int n = 0; for (var m : members.values()) if (m.active(now, reconnect)) n++; return n; }
    double x(int local) { return ox + local + 0.5; }
    double z(int local) { return oz + local + 0.5; }
    /** Where a member stands after the start or a death: inside the current room's entrance. */
    double[] respawn() { var r = rooms.get(Math.min(room, rooms.size() - 1)); return new double[] {x(r.entry()[0]), DungeonRules.FLOOR + 1, z(r.entry()[1])}; }
    DungeonLayout.Room current() { return rooms.get(Math.min(room, rooms.size() - 1)); }

    /** Twice a second. Returns true when the run is over and its slot may be wiped. */
    boolean tick(MinecraftServer server, ServerLevel rift, long now) {
        ticks++;
        var catalog = DungeonServer.catalog(server);
        switch (state) {
            case BUILDING -> {
                if (work.step(rift, System.nanoTime() + 15_000_000L)) { work = null; begin(server, rift, now); }
            }
            case ACTIVE -> {
                mobs.removeIf(u -> { var e = rift.getEntity(u); return e == null || !e.isAlive(); });
                if (active(now, catalog.reconnectSeconds()) == 0) { fail(server, "В забеге никого не осталось."); break; }
                if (DungeonRules.timeLeft(startedAt, now, difficulty.timeLimit()) == 0) { fail(server, "время вышло."); break; }
                if (has("SHADOWS")) shadows(server, rift, now, catalog.reconnectSeconds());
                var r = current(); var inside = inside(server, rift, r, now, catalog.reconnectSeconds());
                if (!roomStarted && !inside.isEmpty()) { roomStarted = true; enter(server, rift, r, inside.size()); }
                if (roomStarted) progress(server, rift, r, inside, now);
            }
            case VICTORY, FAILED -> { if (now >= endsAt) { state = State.ENDED; return true; } }
            case ENDED -> { return true; }
        }
        if (ticks % 2 == 0) hud(server, now);
        return false;
    }
    boolean has(String code) { return affix != null && affix.code().equals(code); }
    /** "Shadows": elites stay invisible until an active member comes within the affix radius. */
    private void shadows(MinecraftServer server, ServerLevel rift, long now, int reconnect) {
        double radius = Math.max(1, affix.value());
        for (var u : mobs) if (rift.getEntity(u) instanceof Mob mob && mob.getPersistentData().getBoolean(ELITE)) {
            boolean near = false;
            for (var m : members.values()) { var p = server.getPlayerList().getPlayer(m.id); if (p != null && m.active(now, reconnect) && p.level() == rift && p.distanceToSqr(mob) <= radius * radius) near = true; }
            if (near) mob.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
            else mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, 40, 0, false, false));
        }
    }
    private List<ServerPlayer> inside(MinecraftServer server, ServerLevel rift, DungeonLayout.Room r, long now, int reconnect) {
        var out = new ArrayList<ServerPlayer>();
        for (var m : members.values()) { var p = server.getPlayerList().getPlayer(m.id); if (p != null && m.active(now, reconnect) && p.level() == rift && r.inside(p.getX() - ox, p.getZ() - oz)) out.add(p); }
        return out;
    }
    private void begin(MinecraftServer server, ServerLevel rift, long now) {
        state = State.ACTIVE; startedAt = now;
        var at = respawn();
        for (var m : members.values()) { var p = server.getPlayerList().getPlayer(m.id); if (p != null) DungeonServer.move(p, rift, at[0], at[1], at[2], -90); }
        say(server, "«" + dungeon.name() + "» · " + difficulty.name() + ". Двери откроются после зачистки комнаты. Удачи!", 0xf07aa6);
        if (affix != null) say(server, "Аффикс недели «" + affix.name() + "»: " + affix.description() + ".", 0xffa04a);
        if (difficulty.timeLimit() > 0) say(server, "На забег " + clock(difficulty.timeLimit()) + ". Павшие наблюдают до конца.", 0xffa04a);
        System.out.println("AOC_DUNGEON_BEGIN run=" + id + " slot=" + slot + " " + key() + " rooms=" + rooms.size() + " players=" + members.size());
    }
    private void enter(MinecraftServer server, ServerLevel rift, DungeonLayout.Room r, int present) {
        int players = Math.max(1, active(System.currentTimeMillis(), DungeonServer.catalog(server).reconnectSeconds()));
        switch (r.kind()) {
            case ENTRANCE -> clear(server, rift, r);
            case TREASURE -> { say(server, "Сокровищница: в сундуке есть дополнительная награда.", 0xffd27a); clear(server, rift, r); }
            case TRAP -> say(server, "Ловушка! Беги к дальней двери.", 0xffa04a);
            case BOSS -> { wave = 0; spawnBoss(server, rift, r, players); spawnWave(server, rift, r, players, Math.max(1, difficulty.wave() / 2)); }
            case ELITE -> { wave = 0; var e = dungeon.elite(); spawn(server, rift, e.type(), r, 0, e, true, players); spawnWave(server, rift, r, players, Math.max(1, difficulty.wave() / 2)); say(server, e.name() + " охраняет зал.", 0xffa04a); }
            default -> { wave = 0; spawnWave(server, rift, r, players, difficulty.wave()); }
        }
    }
    private void progress(MinecraftServer server, ServerLevel rift, DungeonLayout.Room r, List<ServerPlayer> inside, long now) {
        switch (r.kind()) {
            case TRAP -> {
                for (var p : inside) if (p.getX() - ox >= r.finish()) { clear(server, rift, r); return; }
                if (ticks % 3 == 0) for (var p : inside) arrow(rift, r, p);
            }
            case BOSS -> {
                var b = boss == null ? null : rift.getEntity(boss);
                if (b instanceof Mob mob && mob.isAlive()) {
                    if (bossBar != null) bossBar.setProgress(Math.max(0, Math.min(1, mob.getHealth() / mob.getMaxHealth())));
                    var s = dungeon.bossFor(DungeonServer.catalog(server).difficulties(), difficulty.code());
                    if (s.summon() != null && s.summonSeconds() > 0 && now >= nextSummon && mobs.size() < 7) {
                        nextSummon = now + s.summonSeconds() * 1000L;
                        for (int i = 0; i < 2; i++) spawnAt(server, rift, s.summon(), mob.getX() + (i == 0 ? 2 : -2), mob.getY(), mob.getZ(), null, false, Math.max(1, inside.size()));
                        say(server, s.name() + " призывает стражей!", 0xffa04a);
                    }
                } else victory(server, rift, now);
            }
            case ENTRANCE, TREASURE -> {}
            default -> {
                if (!mobs.isEmpty()) return;
                if (++wave < r.waves()) { say(server, "Новая волна!", 0xffa04a); spawnWave(server, rift, r, Math.max(1, inside.size()), difficulty.wave()); }
                else clear(server, rift, r);
            }
        }
    }
    private void clear(MinecraftServer server, ServerLevel rift, DungeonLayout.Room r) {
        if (r.kind() == DungeonLayout.Kind.BOSS) return;
        DungeonBuilder.exit(rift, r, ox, oz, true);
        if (r.kind() == DungeonLayout.Kind.TREASURE) treasureChest = new BlockPos(ox + (r.x0() + r.x1()) / 2, DungeonRules.FLOOR + 1, oz);
        room++; roomStarted = false;
        if (r.kind() != DungeonLayout.Kind.ENTRANCE && r.kind() != DungeonLayout.Kind.TREASURE) say(server, "Комната зачищена, дверь открыта (" + room + "/" + (rooms.size() - 1) + ").", 0x7bea8a);
        rift.playSound(null, ox + r.x1(), DungeonRules.FLOOR + 1, oz, net.minecraft.sounds.SoundEvents.IRON_DOOR_OPEN, net.minecraft.sounds.SoundSource.BLOCKS, 1, 0.8f);
    }

    // ------------------------------------------------------------------ mobs
    private void spawnWave(MinecraftServer server, ServerLevel rift, DungeonLayout.Room r, int players, int base) {
        int count = DungeonRules.waveSize(base, players); var spots = DungeonLayout.spawnPoints(r, count, seed + wave * 31L);
        var random = new SplittableRandom(seed ^ (r.index() * 1000L + wave));
        boolean elite = r.kind() == DungeonLayout.Kind.ELITE || r.kind() == DungeonLayout.Kind.BOSS;
        for (int i = 0; i < spots.size(); i++) spawnAt(server, rift, dungeon.mobs().get(random.nextInt(dungeon.mobs().size())), x(spots.get(i)[0]), DungeonRules.FLOOR + 1, z(spots.get(i)[1]), null, elite && i % 2 == 0, players);
    }
    private void spawnBoss(MinecraftServer server, ServerLevel rift, DungeonLayout.Room r, int players) {
        var s = dungeon.bossFor(DungeonServer.catalog(server).difficulties(), difficulty.code());
        var mob = spawnAt(server, rift, s.type(), x(r.x1() - 5), DungeonRules.FLOOR + 1, z(0), s, true, players);
        if (mob == null) { say(server, "Босс не смог появиться — победа засчитана.", 0xffa04a); return; }
        boss = mob.getUUID(); nextSummon = System.currentTimeMillis() + Math.max(5, s.summonSeconds()) * 1000L;
        bossBar = new ServerBossEvent(Component.literal(s.name()), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        for (var m : members.values()) { var p = server.getPlayerList().getPlayer(m.id); if (p != null) bossBar.addPlayer(p); }
        say(server, s.name() + " пробудился!", 0xff5a5a);
    }
    private Mob spawn(MinecraftServer server, ServerLevel rift, String type, DungeonLayout.Room r, int index, DungeonCatalog.Special special, boolean elite, int players) {
        var spot = DungeonLayout.spawnPoints(r, index + 1, seed + 977).get(index);
        return spawnAt(server, rift, type, x(spot[0]), DungeonRules.FLOOR + 1, z(spot[1]), special, elite, players);
    }
    /** A run mob: tagged with the run, persistent, scaled by the difficulty and the team, no gear drops, no guild credit. */
    Mob spawnAt(MinecraftServer server, ServerLevel rift, String type, double x, double y, double z, DungeonCatalog.Special special, boolean elite, int players) {
        var id = ResourceLocation.tryParse(type); if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return null;
        var entity = BuiltInRegistries.ENTITY_TYPE.get(id).create(rift); if (!(entity instanceof Mob mob)) { if (entity != null) entity.discard(); return null; }
        mob.moveTo(x, y, z, rift.random.nextFloat() * 360, 0);
        if (mob instanceof Zombie zombie) zombie.setBaby(false);
        var path = id.getPath();
        if (path.contains("skeleton") || path.equals("stray")) mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(path.startsWith("wither") ? Items.STONE_SWORD : Items.BOW));
        if (elite) { mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); mob.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            if (!path.contains("skeleton") && !path.equals("stray")) mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD)); }
        for (var slot : EquipmentSlot.values()) mob.setDropChance(slot, 0);
        mob.setCanPickUpLoot(false); mob.setPersistenceRequired();
        double team = DungeonRules.teamHealth(players, DungeonServer.catalog(server).teamHealthPerPlayer());
        var health = mob.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            if (special != null && special.health() > 0) health.setBaseValue(special.health()); else if (elite) health.setBaseValue(health.getBaseValue() * 2);
            health.addPermanentModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath("animeoncraft", "dungeon_health"), difficulty.health() * team - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        var damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) {
            if (special != null && special.damage() > 0) damage.setBaseValue(special.damage());
            damage.addPermanentModifier(new AttributeModifier(ResourceLocation.fromNamespaceAndPath("animeoncraft", "dungeon_damage"), difficulty.damage() - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        mob.setHealth(mob.getMaxHealth());
        if (special != null) { mob.setCustomName(Component.literal(special.name()).withColor(0xff7ab8)); mob.setCustomNameVisible(true); }
        mob.getPersistentData().putLong(TAG, id()); mob.getPersistentData().putBoolean("animeoncraft_guild_unnatural", true); if (elite) mob.getPersistentData().putBoolean(ELITE, true);
        if (!rift.addFreshEntity(mob)) return null;
        mobs.add(mob.getUUID());
        return mob;
    }
    private long id() { return id; }
    /** A trap arrow from the nearest dispenser in the side wall towards the player. */
    private void arrow(ServerLevel rift, DungeonLayout.Room r, ServerPlayer p) {
        int lx = (int) Math.round((p.getX() - ox - r.x0() - 3) / 3.0) * 3 + r.x0() + 3; lx = Math.clamp(lx, r.x0() + 3, r.x1() - 3);
        boolean north = rift.random.nextBoolean(); double sx = x(lx), sz = z(north ? r.z0() + 1 : r.z1() - 1) + (north ? -0.4 : 0.4), sy = DungeonRules.FLOOR + 2.5;
        var arrow = new Arrow(rift, sx, sy, sz, new ItemStack(Items.ARROW), null);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED; arrow.setBaseDamage(2.0 * difficulty.damage());
        arrow.shoot(p.getX() - sx, p.getEyeY() - 0.4 - sy, p.getZ() - sz, 1.4f, 4f);
        arrow.getPersistentData().putLong(TAG, id);
        rift.addFreshEntity(arrow);
    }

    // ------------------------------------------------------------------ deaths, victory, defeat
    /** A death inside the run (the vanilla death was cancelled). Returns false when the member is now a watcher. */
    boolean death(MinecraftServer server, ServerPlayer p, Member m) {
        deaths++; m.deaths++;
        boolean spare = difficulty.teamLives() ? teamLives-- > 0 : m.lives-- > 0;
        if (spare) {
            String left = difficulty.teamLives() ? "Жизней команды: " + teamLives : "Твоих жизней: " + m.lives;
            p.sendSystemMessage(Component.literal("Ты пал в Разломе. " + left + ".").withColor(0xffa04a));
            return true;
        }
        m.watcher = true; m.mode = p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR ? GameType.SURVIVAL : p.gameMode.getGameModeForPlayer();
        say(server, p.getGameProfile().getName() + " выбывает из забега и наблюдает.", 0xffa04a);
        return false;
    }
    private void victory(MinecraftServer server, ServerLevel rift, long now) {
        var catalog = DungeonServer.catalog(server); var store = DungeonStore.get(server);
        state = State.VICTORY; finishedSeconds = (now - startedAt) / 1000; endsAt = now + catalog.victorySeconds() * 1000L;
        for (var u : List.copyOf(mobs)) { var e = rift.getEntity(u); if (e != null) e.discard(); } mobs.clear();
        if (bossBar != null) { bossBar.removeAllPlayers(); bossBar = null; }
        grade = DungeonRules.grade(finishedSeconds, deaths, dungeon.parSeconds());
        int present = 0; for (var m : members.values()) if (!m.out) present++;
        chests.addAll(DungeonBuilder.rewardChests(rift, rooms.getLast(), ox, oz, Math.max(1, present)));
        var names = new ArrayList<String>();
        for (var m : members.values()) {
            if (m.out) continue; names.add(m.name);
            var p = server.getPlayerList().getPlayer(m.id); var sp = store.player(m.id); sp.reset(now);
            boolean first = sp.cleared.add(key());
            if (DungeonRules.chestsLeft(sp.chests, catalog.dailyChests()) > 0) { sp.chests++; store.today(now)[DungeonStore.CHESTS]++; fill(server, m, DungeonRules.rolls(difficulty.rolls(), grade, m.access), true); }
            else if (p != null) p.sendSystemMessage(Component.literal("Награды на сегодня уже получены (" + catalog.dailyChests() + " сундука). Забег засчитан, сундук пуст.").withColor(0xffa04a));
            if (p != null) { PremiumCrystals.dungeon(p, dungeon.code(), difficulty.code()); if (first) p.sendSystemMessage(Component.literal("Первая зачистка «" + dungeon.name() + "» · " + difficulty.name() + ": кристаллы Разлома начисляются.").withColor(0xf07aa6)); }
        }
        var best = store.best.get(key());
        if (best == null || finishedSeconds < best.seconds()) store.best.put(key(), new DungeonStore.Best(finishedSeconds, String.join(", ", names)));
        store.won++; store.today(now)[DungeonStore.WINS]++; store.setDirty();
        var cleared = new ArrayList<UUID>(); for (var m : members.values()) if (!m.out) cleared.add(m.id);
        GuildServer.dungeonCleared(server, cleared, dungeon.code(), difficulty.code(), catalog.order());
        say(server, "Победа! Оценка " + grade + " · " + clock(finishedSeconds) + " · смертей " + deaths + ". Сундуки награды — в центре арены. Возврат через " + catalog.victorySeconds() + " с ", 0x7bea8a);
        System.out.println("AOC_DUNGEON_VICTORY run=" + id + " " + key() + " seconds=" + finishedSeconds + " deaths=" + deaths + " grade=" + grade + " players=" + names);
    }
    /** Rolls loot into a member's personal chest. base: the difficulty's essence and rolls only for the boss chest. */
    void fill(MinecraftServer server, Member m, int rolls, boolean essence) {
        var table = dungeon.loot().getOrDefault(difficulty.code(), List.of()); if (essence) m.rolled = rolls;
        long s = seed ^ m.id.getLeastSignificantBits() ^ (essence ? 0 : 0x5DEECE66DL);
        for (var l : DungeonCatalog.roll(table, rolls, s)) {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(l.item())); if (item == Items.AIR) continue;
            var stack = new ItemStack(item, Math.min(l.min(), item.getDefaultMaxStackSize()));
            if (l.enchant() > 0) stack = net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(net.minecraft.util.RandomSource.create(s + l.hashCode()), stack, l.enchant(), server.registryAccess(), java.util.Optional.empty());
            m.chest.addItem(stack);
        }
        if (essence) {
            int n = difficulty.essenceMin() + new SplittableRandom(s).nextInt(difficulty.essenceMax() - difficulty.essenceMin() + 1);
            if (m.access == DungeonRules.Access.HALF) n = (n + 1) / 2;
            if (n > 0) { m.chest.addItem(new ItemStack(DungeonItems.ESSENCE.get(), n)); DungeonStore.get(server).today(System.currentTimeMillis())[DungeonStore.ESSENCE] += n; }
        }
    }
    void fail(MinecraftServer server, String why) {
        if (state != State.ACTIVE && state != State.BUILDING) return;
        state = State.FAILED; endsAt = System.currentTimeMillis() + 5000;
        if (bossBar != null) { bossBar.removeAllPlayers(); bossBar = null; }
        var store = DungeonStore.get(server); store.failed++; store.today(System.currentTimeMillis())[DungeonStore.FAILS]++; store.setDirty();
        say(server, "Забег провален: " + why + " Возврат к Вратам.", 0xff5a5a);
        System.out.println("AOC_DUNGEON_FAILED run=" + id + " " + key() + " reason=" + why);
    }
    /** Ends the run for every member in the next tick (a member left, an operator stopped it). */
    void abort() { state = State.ENDED; }

    // ------------------------------------------------------------------ HUD
    private void hud(MinecraftServer server, long now) {
        var catalog = DungeonServer.catalog(server);
        for (var m : members.values()) {
            var p = server.getPlayerList().getPlayer(m.id);
            if (p == null || m.out) { m.bar.removeAllPlayers(); continue; }
            if (!m.bar.getPlayers().contains(p)) m.bar.addPlayer(p);
            if (bossBar != null && !bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
            String lives = m.watcher ? "наблюдение" : "❤ " + Math.max(0, difficulty.teamLives() ? teamLives : m.lives);
            String text = switch (state) {
                case BUILDING -> "«" + dungeon.name() + "» · Разлом открывается…";
                case ACTIVE -> { long left = DungeonRules.timeLeft(startedAt, now, difficulty.timeLimit());
                    yield dungeon.name() + " · " + difficulty.name() + (affix != null ? " · " + affix.name() : "") + " · " + (left >= 0 ? "⌛ " + clock(left) : clock((now - startedAt) / 1000))
                        + " · " + lives + " · комната " + Math.min(room + 1, rooms.size()) + "/" + rooms.size(); }
                case VICTORY -> "Победа! Оценка " + grade + " · " + clock(finishedSeconds) + " · возврат через " + Math.max(0, (endsAt - now) / 1000) + " с";
                default -> "Возврат к Вратам…";
            };
            m.bar.setName(Component.literal(text));
            m.bar.setProgress(state == State.VICTORY ? 1 : Math.max(0, Math.min(1, room / (float) rooms.size())));
            m.bar.setColor(state == State.FAILED ? BossEvent.BossBarColor.RED : state == State.VICTORY ? BossEvent.BossBarColor.GREEN : BossEvent.BossBarColor.PINK);
        }
    }
    void hideBars() { for (var m : members.values()) m.bar.removeAllPlayers(); if (bossBar != null) bossBar.removeAllPlayers(); }
    static String clock(long seconds) { return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60; }
    void say(MinecraftServer server, String text, int color) {
        for (var m : members.values()) { var p = server.getPlayerList().getPlayer(m.id); if (p != null && !m.out) p.sendSystemMessage(Component.literal(text).withColor(color)); }
    }
}
