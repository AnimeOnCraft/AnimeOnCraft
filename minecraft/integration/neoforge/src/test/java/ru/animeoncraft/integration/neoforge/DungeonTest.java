package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Rift Gate: slots, death loss, access, scaling, grades, forbidden things, room chains, catalog, packets and the gate site. */
class DungeonTest {
    @AfterEach void clear() { WorldLayout.clear(); }

    @Test void slotsSitOnA1024GridAndNeverOverlap() {
        var cells = new HashSet<String>();
        for (int s = 0; s < DungeonRules.SLOTS; s++) {
            int x = DungeonRules.slotX(s), z = DungeonRules.slotZ(s);
            assertEquals(s, DungeonRules.slotAt(x, z)); assertEquals(s, DungeonRules.slotAt(x + 500, z + 500), "a whole chain stays in its cell");
            assertTrue(cells.add(x / DungeonRules.SPACING + ":" + z / DungeonRules.SPACING));
        }
        assertEquals(-1, DungeonRules.slotAt(-1, 5)); assertEquals(-1, DungeonRules.slotAt(4096, 0)); assertEquals(-1, DungeonRules.slotAt(0, 2048));
        assertEquals(0, DungeonRules.freeSlot(Set.of())); assertEquals(2, DungeonRules.freeSlot(Set.of(0, 1))); assertEquals(-1, DungeonRules.freeSlot(Set.of(0, 1, 2, 3, 4, 5, 6, 7)));
        assertThrows(IllegalArgumentException.class, () -> DungeonRules.slotX(8));
    }

    @Test void deathTakesTheShareOfStacksRoundedUpAtLeastOne() {
        assertEquals(0, DungeonRules.lossCount(0, 40));
        assertEquals(1, DungeonRules.lossCount(1, 10)); assertEquals(1, DungeonRules.lossCount(9, 10)); assertEquals(2, DungeonRules.lossCount(11, 10));
        assertEquals(4, DungeonRules.lossCount(20, 20)); assertEquals(11, DungeonRules.lossCount(36, 30)); assertEquals(15, DungeonRules.lossCount(36, 40));
        var occupied = List.of(0, 3, 5, 8, 9, 20, 30, 35); var lost = DungeonRules.lostSlots(occupied, 30, 42);
        assertEquals(3, lost.size()); assertTrue(occupied.containsAll(lost)); assertEquals(lost, DungeonRules.lostSlots(occupied, 30, 42), "same seed, same stacks");
        assertEquals(36, DungeonRules.MAIN_SLOTS, "armour, off hand and Curios are outside the main inventory");
        assertTrue(DungeonRules.protectedItem("sophisticatedbackpacks:diamond_backpack")); assertTrue(DungeonRules.protectedItem("lightmanscurrency:wallet_gold"));
        assertFalse(DungeonRules.protectedItem("minecraft:diamond")); assertFalse(DungeonRules.protectedItem("lightmanscurrency:coin_gold"));
    }

    @Test void rankGivesFullAccessOneBelowHalfInATeam() {
        assertEquals(DungeonRules.Access.FULL, DungeonRules.access(3, 3)); assertEquals(DungeonRules.Access.HALF, DungeonRules.access(2, 3)); assertEquals(DungeonRules.Access.NONE, DungeonRules.access(1, 3));
        assertEquals(DungeonRules.Access.NONE, DungeonRules.access(-1, 0), "no guild card, no gate");
        assertNull(DungeonRules.teamCheck(List.of(0), 0, 1, 4));
        assertNotNull(DungeonRules.teamCheck(List.of(2), 3, 1, 4), "half rank alone may not start");
        assertNull(DungeonRules.teamCheck(List.of(3, 2), 3, 1, 4), "half rank with a full one");
        assertNotNull(DungeonRules.teamCheck(List.of(4, 1), 3, 1, 4)); assertNotNull(DungeonRules.teamCheck(List.of(5), 4, 2, 4), "nightmare needs two");
        assertNotNull(DungeonRules.teamCheck(List.of(5, 5, 5, 5, 5), 0, 1, 4)); assertNotNull(DungeonRules.teamCheck(List.of(3, -1), 0, 1, 4));
    }

    @Test void teamScalingGradesAndLootRolls() {
        assertEquals(1.0, DungeonRules.teamHealth(1, 0.6)); assertEquals(2.8, DungeonRules.teamHealth(4, 0.6), 1e-9);
        assertEquals(3, DungeonRules.waveSize(3, 1)); assertEquals(9, DungeonRules.waveSize(3, 4)); assertEquals(12, DungeonRules.waveSize(6, 4));
        assertEquals(DungeonRules.Grade.S, DungeonRules.grade(500, 0, 540)); assertEquals(DungeonRules.Grade.A, DungeonRules.grade(700, 1, 540));
        assertEquals(DungeonRules.Grade.B, DungeonRules.grade(2000, 2, 540)); assertEquals(DungeonRules.Grade.C, DungeonRules.grade(2000, 6, 540));
        assertEquals(5, DungeonRules.rolls(3, DungeonRules.Grade.S, DungeonRules.Access.FULL)); assertEquals(3, DungeonRules.rolls(3, DungeonRules.Grade.S, DungeonRules.Access.HALF));
        assertEquals(2, DungeonRules.chestsLeft(1, 3)); assertEquals(0, DungeonRules.chestsLeft(5, 3));
        assertTrue(DungeonRules.reconnect(1000, 1000 + 180_000, 180)); assertFalse(DungeonRules.reconnect(1000, 1000 + 180_001, 180)); assertFalse(DungeonRules.reconnect(0, 5, 180));
        assertEquals("dungeon:sakura_crypt:mythic", DungeonRules.crystalRef("sakura_crypt", "MYTHIC"));
    }

    @Test void pearlsChorusWaystonesAndSpawnAreForbiddenInside() {
        for (var c : List.of("/spawn", "spawn", "/home base", "/minecraft:spawn", "/tpa Kira", "/back", "/waystones gui")) assertTrue(DungeonRules.blockedCommand(c), c);
        for (var c : List.of("/aocdungeon leave", "/msg Kira hi", "/aocparty leave", "/spawnpoint")) assertFalse(DungeonRules.blockedCommand(c), c);
        for (var i : List.of("minecraft:ender_pearl", "minecraft:chorus_fruit", "waystones:warp_stone", "waystones:return_scroll")) assertTrue(DungeonRules.blockedItem(i), i);
        assertFalse(DungeonRules.blockedItem("minecraft:bread")); assertFalse(DungeonRules.blockedItem("minecraft:bow"));
    }

    @Test void roomChainFollowsThePlanAndFitsTheSlot() {
        for (long seed = 0; seed < 300; seed++) {
            var rooms = DungeonLayout.plan(seed, 5);
            assertEquals(DungeonLayout.Kind.ENTRANCE, rooms.getFirst().kind()); assertEquals(DungeonLayout.Kind.BOSS, rooms.getLast().kind());
            assertEquals(DungeonLayout.Kind.FIGHT, rooms.get(1).kind());
            assertTrue(rooms.get(2).kind() == DungeonLayout.Kind.TRAP || rooms.get(2).kind() == DungeonLayout.Kind.BRIDGE);
            assertEquals(DungeonLayout.Kind.ELITE, rooms.stream().filter(r -> r.kind() == DungeonLayout.Kind.ELITE).findFirst().orElseThrow().kind());
            assertTrue(rooms.size() == 6 || rooms.size() == 7, "5–6 rooms and the boss arena");
            for (int i = 1; i < rooms.size(); i++) {
                assertEquals(rooms.get(i - 1).x1() + DungeonLayout.CORRIDOR + 1, rooms.get(i).x0(), "corridor between rooms");
                assertEquals(i, rooms.get(i).index());
            }
            var b = DungeonLayout.bounds(rooms);
            assertTrue(b[2] - b[0] < DungeonRules.SPACING - DungeonRules.MARGIN - 64 && b[3] - b[1] < 64, "fits the slot cell");
            assertTrue(DungeonLayout.top(rooms) < 20);
            assertEquals(rooms, DungeonLayout.plan(seed, 5), "seeded");
            for (var r : rooms) for (var s : DungeonLayout.spawnPoints(r, 8, seed)) {
                assertTrue(r.inside(s[0] + 0.5, s[1] + 0.5) && !r.pit(s[0], s[1]), "spawn inside, off the pit");
            }
        }
        var seven = DungeonLayout.plan(7, 7); assertTrue(seven.size() >= 8);
        int chunks = DungeonLayout.chunks(DungeonLayout.plan(1, 5), DungeonRules.slotX(0), DungeonRules.slotZ(0)).size();
        assertTrue(chunks <= 60, "a run keeps about a few dozen chunks loaded: " + chunks);
        var bridge = new DungeonLayout.Room(0, DungeonLayout.Kind.BRIDGE, 0, -7, 24, 7, 1);
        assertTrue(bridge.pit(10, 4)); assertFalse(bridge.pit(10, 0), "the bridge"); assertFalse(bridge.pit(2, 4), "the platform");
        var all = new HashSet<DungeonLayout.Kind>(); for (long s = 0; s < 50; s++) for (var r : DungeonLayout.plan(s, 5)) all.add(r.kind());
        assertEquals(EnumSet.allOf(DungeonLayout.Kind.class), all, "every room kind appears");
    }

    @Test void builtInCatalogHasTheSakuraCryptAndTheOwnersNumbers() throws Exception {
        var skipped = new ArrayList<String>(); var c = DungeonCatalog.builtIn(id -> true, id -> true, skipped);
        assertTrue(skipped.isEmpty(), skipped.toString());
        assertEquals(3, c.dailyChests()); assertEquals(180, c.reconnectSeconds()); assertEquals(4, c.maxPlayers());
        assertEquals(List.of("NORMAL", "HARD", "NIGHTMARE", "MYTHIC"), c.difficulties().stream().map(DungeonCatalog.Difficulty::code).toList());
        assertEquals(List.of(0, 10, 30, 80), c.difficulties().stream().map(DungeonCatalog.Difficulty::fee).toList(), "entry fees in copper");
        assertEquals(List.of(10, 20, 30, 40), c.difficulties().stream().map(DungeonCatalog.Difficulty::lossPercent).toList(), "death loss");
        assertEquals(List.of(50, 50, 50, 150), c.difficulties().stream().map(DungeonCatalog.Difficulty::crystals).toList(), "first clear crystals");
        int total = 0; for (var f : c.difficulties()) total += f.crystals(); assertEquals(300, total, "300 per dungeon, 1200 for four");
        assertEquals(List.of(0, 2, 4, 5), c.difficulties().stream().map(DungeonCatalog.Difficulty::minRank).toList(), "F, D, B, A");
        var sakura = c.dungeon("sakura_crypt"); assertTrue(sakura.open()); assertEquals(1, c.dungeons().stream().filter(DungeonCatalog.Dungeon::open).count(), "starting with the Sakura Crypt");
        assertEquals("Вишнёвый сёгун", sakura.bossFor(c.difficulties(), "HARD").name());
        assertEquals("mowziesmobs:ferrous_wroughtnaut", sakura.bossFor(c.difficulties(), "MYTHIC").type(), "Nightmare and above");
        for (var f : c.difficulties()) assertFalse(sakura.loot().get(f.code()).isEmpty());
        assertTrue(sakura.loot().values().stream().flatMap(List::stream).noneMatch(l -> GuildRules.coinId(l.item())), "no coins in dungeon loot");
        var rolled = DungeonCatalog.roll(sakura.loot().get("NORMAL"), 5, 9); assertEquals(5, rolled.size()); assertEquals(rolled, DungeonCatalog.roll(sakura.loot().get("NORMAL"), 5, 9));
        // Unknown mods: their mobs and loot are dropped, the boss falls back to the base one.
        var without = new ArrayList<String>(); var vanilla = DungeonCatalog.builtIn(id -> id.startsWith("minecraft:"), id -> id.startsWith("minecraft:"), without);
        assertEquals("minecraft:wither_skeleton", vanilla.dungeon("sakura_crypt").bossFor(vanilla.difficulties(), "MYTHIC").type());
        assertTrue(without.stream().anyMatch(s -> s.contains("ferrous_wroughtnaut")));
        assertFalse(vanilla.dungeon("rift_forge").open());
    }

    @Test void snapshotSurvivesTheWire() {
        var tier = new DungeonPayloads.Tier("Вишнёвый сёгун", List.of("animeoncraft:rift_essence", "minecraft:diamond"));
        var snapshot = new DungeonPayloads.Snapshot(3, 1, 3, 1234, false,
            List.of(new DungeonPayloads.Dungeon("sakura_crypt", "Склеп Сакуры", "нежить", "Каменные залы", 6, true, 0b101, List.of(tier, tier))),
            List.of(new DungeonPayloads.Difficulty("Мифическая", 80, 40, 5, 3, 50, 28, 0, true, 150, 2, 1200, true)),
            List.of(new DungeonPayloads.Member("Y2K", 4, true)), true, 0, 1, List.of(new DungeonPayloads.Lobby(0, "Kira", 0, 1, 2, 4)), DungeonPayloads.SCREEN_OPEN, "готово",
            "Кровопийцы — мобы лечатся от своих ударов");
        var buffer = new FriendlyByteBuf(Unpooled.buffer()); DungeonPayloads.Snapshot.CODEC.encode(buffer, snapshot);
        assertEquals(snapshot, DungeonPayloads.Snapshot.CODEC.decode(buffer));
        var request = new DungeonPayloads.Request("START", 0, 3, 0); var b2 = new FriendlyByteBuf(Unpooled.buffer()); DungeonPayloads.Request.CODEC.encode(b2, request);
        assertEquals(request, DungeonPayloads.Request.CODEC.decode(b2));
        assertThrows(IllegalArgumentException.class, () -> new DungeonPayloads.Request("TELEPORT", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DungeonPayloads.Request("SOLO", 9, 0, 0));
    }

    /** Every MARKET of the real profile, all clans: the guild and the gate with their margins and paths stay off them. */
    @Test void layoutBuildingsNeverStandOnAnyClanMarket() throws Exception {
        var toml = java.nio.file.Files.readString(WorldLayoutTest.LAYOUT.getParent().getParent().getParent().resolve("world-states.toml"));
        var row = java.util.regex.Pattern.compile("code = \"(market\\.[a-z]+)\"[\\s\\S]*?polygon = \\[\\[(-?\\d+), (-?\\d+)\\], \\[(-?\\d+), (-?\\d+)\\], \\[(-?\\d+), (-?\\d+)\\], \\[(-?\\d+), (-?\\d+)\\]\\]").matcher(toml);
        var layout = WorldLayout.load(WorldLayoutTest.LAYOUT); var gate = layout.pois().get(RiftGate.CODE); var capital = layout.pois().get("core.capital");
        var gateFrame = new RiftGate.Frame(gate.x(), gate.z(), AdventurerGuild.door(capital, gate)); var guild = layout.pois().get("core.guild");
        var guildFrame = new AdventurerGuild.Frame(guild.x(), guild.z(), AdventurerGuild.door(capital, guild)); var markets = new ArrayList<String>();
        while (row.find()) {
            markets.add(row.group(1)); int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int i = 2; i <= 8; i += 2) { int x = Integer.parseInt(row.group(i)), z = Integer.parseInt(row.group(i + 1)); x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); }
            for (int u = -RiftGate.HALF_WIDTH - 2; u <= RiftGate.HALF_WIDTH + 2; u++) for (int v = -RiftGate.PATH; v <= RiftGate.DEPTH + 1; v++) { int x = gateFrame.x(u, v), z = gateFrame.z(u, v);
                assertFalse(x >= x0 && x < x1 && z >= z0 && z < z1, "Rift Gate on " + row.group(1) + " at " + x + "," + z); }
            for (int u = -AdventurerGuild.HALF_WIDTH - 2; u <= AdventurerGuild.HALF_WIDTH + 2; u++) for (int v = -AdventurerGuild.PATH; v <= AdventurerGuild.DEPTH + 1; v++) { int x = guildFrame.x(u, v), z = guildFrame.z(u, v);
                assertFalse(x >= x0 && x < x1 && z >= z0 && z < z1, "Guild on " + row.group(1) + " at " + x + "," + z); }
        }
        assertEquals(Set.of("market.scam", "market.nya", "market.zxc", "market.akuma", "market.yakuza", "market.vorix"), new HashSet<>(markets));
    }

    @Test void gateStandsOnTheRingLawnAwayFromStreetsGuildAndMarkets() throws Exception {
        var layout = WorldLayout.load(WorldLayoutTest.LAYOUT); var ring = SoraRing.of(layout, layout.pois().get("core.capital.expansion"));
        var poi = layout.pois().get(RiftGate.CODE);
        var f = new RiftGate.Frame(poi.x(), poi.z(), AdventurerGuild.door(layout.pois().get("core.capital"), poi));
        assertEquals(net.minecraft.core.Direction.WEST, f.door(), "entrance towards the city centre");
        for (int u = -RiftGate.HALF_WIDTH - 2; u <= RiftGate.HALF_WIDTH + 2; u++) for (int v = -1; v <= RiftGate.DEPTH + 1; v++)
            assertEquals(SoraRing.Cell.LAWN, ring.cell(f.x(u, v), f.z(u, v)), "gate site " + u + "," + v);
        assertEquals(SoraRing.Cell.STREET, ring.cell(f.x(0, -RiftGate.PATH), f.z(0, -RiftGate.PATH)), "path reaches the ring street");
        var guild = layout.pois().get("core.guild"); assertTrue(Math.hypot(guild.x() - poi.x(), guild.z() - poi.z()) > 150);
        var regions = RegionPolicy.parse(ClanMarketTest.catalog());
        for (var plot : regions.regions().stream().filter(RegionPolicy.Region::market).toList()) assertTrue(plot.maxX() < f.x(0, -RiftGate.PATH) - 20, "markets stay in the old city");
        var site = new RiftGate.Site(f, 90); var a = site.arrival();
        assertTrue(site.inHall(a[0], a[1], a[2]), "players come back inside the hall");
        assertTrue(site.portal(new net.minecraft.core.BlockPos(f.x(0, RiftGate.DEPTH - 2), 93, f.z(0, RiftGate.DEPTH - 2))));
        assertFalse(site.portal(new net.minecraft.core.BlockPos(f.x(0, 2), 91, f.z(0, 2))));
        assertTrue(CentralSpawnRules.fromLayout(layout).contains(poi.x() + 8, poi.z()), "no new mobs at the gate");
    }
}
