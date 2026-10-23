package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Renewable output of resource sites (docs/RESOURCE_SYSTEM.md, "Два вида выхода").
 *
 * PIT: the first refresh records the family blocks already inside the zone as the pit template and, for ore
 * families with too few blocks, seeds a vein underground. Later refreshes put template blocks back where they
 * were mined (air only; player builds are never overwritten), scaled by the owner's yield multiplier.
 * CACHE: a stash filled with the family items; only the owner clan collects it (/aoc resource collect).
 * A refresh waits while a player is inside the zone or the zone is not loaded. Each refresh is applied once
 * per backend due time (stored next to the template), so a lost acknowledgement never doubles items.
 */
final class ResourcePits {
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final System.Logger LOG = System.getLogger("AnimeOnCraft");
    private static final java.util.Map<String,String> ERRORS = new java.util.HashMap<>();
    private static final java.util.Map<String,Long> RETRY = new java.util.HashMap<>();
    private ResourcePits() {}

    static Path directory(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-capture"); }
    private static Path file(MinecraftServer server, String kind, String code) {
        if (!code.matches("[a-z0-9][a-z0-9._-]{1,63}")) throw new IllegalArgumentException("Invalid point code");
        return directory(server).resolve(kind).resolve(code + ".json");
    }
    static JsonObject read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return null;
        if (Files.size(path) > 1_048_576) throw new IOException("Resource file too large");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    static void write(Path path, JsonObject value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".pending");
        Files.writeString(temporary, value.toString());
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** At most one due refresh per second; the acknowledgement is asynchronous. */
    static void second(MinecraftServer server, List<ServerPlayer> players, CaptureSites.View view, long now) {
        if (IN_FLIGHT.get()) return;
        ResourceRefreshChunks.releaseIfFinished(server,view,players);
        for (var due : view.refreshes()) {
            var point = view.points().get(due.pointCode());
            if (point == null || point.resource() == null) continue;
            if(RETRY.getOrDefault(point.code(),0L)>now)continue;
            if(!occupied(players,server,point)&&!loaded(server.overworld(),point)) { ResourceRefreshChunks.request(server,point);continue; }
            try {
                Integer restored = apply(server, players, point, due);
                if (restored == null) continue; // deferred: player inside or not loaded
                acknowledge(due, restored);
                return;
            } catch (IOException | RuntimeException failure) {
                ERRORS.put(point.code(),"ошибка шаблона — требуется проверка администратором");
                RETRY.put(point.code(),now+30);
                LOG.log(System.Logger.Level.WARNING, "Resource refresh failed for " + point.code() + ": "+failure.getMessage()+"; retry in 30 s");
            }
        }
    }

    private static void acknowledge(CaptureSites.Refresh due, int restored) {
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        try {
            var settings = ServerSettings.from(System.getenv());
            var body = new JsonObject();
            body.addProperty("server_id", settings.serverId().toString()); body.addProperty("point_code", due.pointCode());
            body.addProperty("due_at", due.dueAt().toString()); body.addProperty("restored", restored); body.addProperty("multiplier", due.multiplier());
            HttpJson.post(settings.heartbeatUrl().resolve("../capture/refreshed"), settings.key(), body).whenComplete((reply, failure) -> IN_FLIGHT.set(false));
        } catch (RuntimeException unavailable) { IN_FLIGHT.set(false); }
    }

    static boolean occupied(List<ServerPlayer> players, MinecraftServer server, CaptureSites.Point point) {
        double reach = point.radius() + 4.0;
        for (var player : players) {
            if (player.serverLevel() != server.overworld() || player.isSpectator()) continue;
            double dx = player.getX() - (point.x() + 0.5), dz = player.getZ() - (point.z() + 0.5);
            if (dx * dx + dz * dz <= reach * reach) return true;
        }
        return false;
    }
    static boolean loaded(ServerLevel level, CaptureSites.Point point) {
        int r = point.radius();
        for (int x=(point.x()-r)>>4;x<=((point.x()+r)>>4);x++) for(int z=(point.z()-r)>>4;z<=((point.z()+r)>>4);z++)
            if (!level.hasChunk(x,z)) return false;
        return true;
    }

    /** Applies one due refresh; null means "not now". Returns the number of restored blocks or added items. */
    static Integer apply(MinecraftServer server, List<ServerPlayer> players, CaptureSites.Point point, CaptureSites.Refresh due) throws IOException {
        var level = server.overworld();
        if (occupied(players, server, point) || !loaded(level, point)) return null;
        var rules = CaptureSites.view().rules();
        if (rules == null) return null;
        boolean pit = "PIT".equals(point.resource().kind());
        Path path = file(server, pit ? "pits" : "caches", point.code());
        var state = read(path);
        if (state != null && due.dueAt().toString().equals(state.has("applied_due") ? state.get("applied_due").getAsString() : null))
            return state.has("applied_count") ? state.get("applied_count").getAsInt() : 0; // already applied, only the acknowledgement was lost
        int count;
        if (pit) {
            if (state == null || !state.has("output") || !state.get("output").equals(point.resource().output())) {
                if (state!=null) {
                    Path backup=path.resolveSibling(path.getFileName()+".before-v2");
                    if (!Files.exists(backup)) Files.copy(path,backup);
                }
                state = template(level, point, rules);
                write(path,state); // persist planned positions before changing any block
            }
            count = restore(level, point, state, due.multiplier());
        } else {
            if (state == null) { state = new JsonObject(); state.add("items", new JsonArray()); state.addProperty("refreshes", 0); }
            count = fillCache(point, state, rules, due.multiplier());
            var barrel=new BlockPos(point.x(),CaptureAnchors.height(server,point),point.z());
            if(level.getBlockState(barrel).isAir()||level.getBlockState(barrel).is(Blocks.WATER))level.setBlock(barrel,Blocks.BARREL.defaultBlockState(),Block.UPDATE_ALL);
        }
        state.addProperty("applied_due", due.dueAt().toString()); state.addProperty("applied_count", count);
        write(path, state);
        ERRORS.remove(point.code());
        return count;
    }

    private static Block block(String id) {
        var location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) return null;
        return BuiltInRegistries.BLOCK.get(location);
    }

    /** First refresh: record family blocks already in the zone, then seed a vein when an ore family has too few. */
    static JsonObject template(ServerLevel level, CaptureSites.Point point, JsonObject rules) {
        var output = point.resource().output();
        var family = output.has("blocks") ? output : rules.getAsJsonObject("families").getAsJsonObject(point.resource().family());
        var wanted = new java.util.HashMap<Block, String>();
        if (family != null) for (var value : family.getAsJsonArray("blocks")) {
            var found = block(value.getAsString());
            if (found == null) LOG.log(System.Logger.Level.INFO, "Resource block not in this modpack, skipped: " + value.getAsString());
            else wanted.put(found, value.getAsString());
        }
        int r = point.radius(), surface = CaptureAnchors.height(level.getServer(),point);
        if(surface<level.getMinBuildHeight())throw new IllegalStateException("Resource floor unavailable");
        int bottom = Math.max(level.getMinBuildHeight(), surface - r - 8), top = Math.min(level.getMaxBuildHeight() - 1, surface + 24);
        var blocks = new JsonArray();
        var mutable = new BlockPos.MutableBlockPos();
        int target = rules.getAsJsonObject("yield").getAsJsonArray("pit_blocks").get(point.resource().tier() - 1).getAsInt();
        if(output.has("limit"))target=Math.min(target,output.get("limit").getAsInt());
        int reserved=0;
        if(family!=null&&family.has("seed"))for(var pair:family.getAsJsonArray("seed")){
            if(block(pair.getAsJsonArray().get(0).getAsString())==null)throw new IllegalStateException("Required resource block unavailable");
            reserved+=pair.getAsJsonArray().get(1).getAsInt();
        }
        int existingLimit=Math.max(0,target-Math.min(target,reserved));
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r) continue;
            for (int y = bottom; y <= top; y++) {
                var found = level.getBlockState(mutable.set(point.x() + dx, y, point.z() + dz)).getBlock();
                String id = wanted.get(found);
                if (id != null && blocks.size() < existingLimit) blocks.add(entry(dx, y, dz, id));
            }
        }
        if (family != null && family.has("seed") && blocks.size() < target) seed(level, point, family.getAsJsonArray("seed"), target - blocks.size(), surface, blocks);
        if (blocks.isEmpty()) throw new IllegalStateException("No safe resource positions");
        if(family!=null&&family.has("seed"))for(var pair:family.getAsJsonArray("seed")){
            String required=pair.getAsJsonArray().get(0).getAsString();boolean found=false;
            for(var value:blocks)if(value.getAsJsonArray().get(3).getAsString().replace(":deepslate_",":").equals(required)){found=true;break;}
            if(!found)throw new IllegalStateException("No safe position for promised resource");
        }
        var state = new JsonObject();
        state.addProperty("format_version", 2); state.addProperty("x", point.x()); state.addProperty("z", point.z());
        state.add("output",output.deepCopy());state.addProperty("limit",target);
        state.addProperty("created_at", Instant.now().toString()); state.add("blocks", blocks);
        return state;
    }
    private static JsonArray entry(int dx, int y, int dz, String id) {
        var row = new JsonArray(); row.add(dx); row.add(y); row.add(dz); row.add(id); return row;
    }

    /** Deterministic vein below the surface, replacing only natural stone, deepslate or dirt. */
    private static void seed(ServerLevel level, CaptureSites.Point point, JsonArray weights, int amount, int surface, JsonArray blocks) {
        var choices = new ArrayList<String>();
        for (var value : weights) {
            var pair = value.getAsJsonArray();
            if (block(pair.get(0).getAsString()) == null) continue;
            for (int i = 0; i < pair.get(1).getAsInt(); i++) choices.add(pair.get(0).getAsString());
        }
        if (choices.isEmpty()) throw new IllegalStateException("Resource seed blocks unavailable");
        var random = new Random(point.code().hashCode() * 31L + point.x() * 7L + point.z());
        int r = Math.max(4, point.radius() - 2), placed = 0;
        var used = new java.util.HashSet<Long>();
        for(var value:blocks){var row=value.getAsJsonArray();used.add(new BlockPos(point.x()+row.get(0).getAsInt(),row.get(1).getAsInt(),point.z()+row.get(2).getAsInt()).asLong());}
        boolean surfaceOutput=point.resource().output().has("placement")&&"SURFACE".equals(point.resource().output().get("placement").getAsString());
        for (int attempt = 0; attempt < amount * 100 && placed < amount; attempt++) {
            int dx = random.nextInt(2 * r + 1) - r, dz = random.nextInt(2 * r + 1) - r, depth = 3 + random.nextInt(Math.max(4, r));
            if (dx * dx + dz * dz > r * r) continue;
            int y=surface-depth;
            if(surfaceOutput) {
                y=surface;
                // Follow natural local terrain, not the tops of POI lanterns/buildings/trees.
                for(int dy=8;dy>=-8;dy--) {
                    var soil=new BlockPos(point.x()+dx,surface+dy-1,point.z()+dz);
                    if(naturalSurface(level.getBlockState(soil))&&surfaceSpace(level.getBlockState(soil.above()))){y=surface+dy;break;}
                }
            }
            var pos = new BlockPos(point.x() + dx, y, point.z() + dz);
            if (!used.add(pos.asLong()) || pos.getY() <= level.getMinBuildHeight()) continue;
            var current = level.getBlockState(pos);
            if (surfaceOutput ? !(surfaceSpace(current)&&naturalSurface(level.getBlockState(pos.below())))
                : !naturalSurface(current)) continue;
            String id = choices.get(placed%choices.size());
            Block ore = block(id);
            if (ore == null) continue;
            if (current.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES) && id.startsWith("minecraft:") && id.endsWith("_ore")) {
                var deep = block(id.replace("minecraft:", "minecraft:deepslate_"));
                if (deep != null) { ore = deep; id = id.replace("minecraft:", "minecraft:deepslate_"); }
            }
            var row=entry(dx,pos.getY(),dz,id);row.add(true);blocks.add(row);
            placed++;
        }
    }

    private static boolean naturalSurface(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(BlockTags.DIRT)||state.is(BlockTags.STONE_ORE_REPLACEABLES)||state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
            ||state.is(Blocks.SAND)||state.is(Blocks.RED_SAND)||state.is(Blocks.GRAVEL)||state.is(Blocks.SNOW_BLOCK)
            ||state.is(Blocks.TERRACOTTA)||state.is(Blocks.ORANGE_TERRACOTTA)||state.is(Blocks.YELLOW_TERRACOTTA)||state.is(Blocks.WHITE_TERRACOTTA)
            ||state.is(Blocks.RED_TERRACOTTA)||state.is(Blocks.BROWN_TERRACOTTA)||state.is(Blocks.LIGHT_GRAY_TERRACOTTA)
            ||BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("biomesoplenty:dried_salt");
    }
    private static boolean surfaceSpace(net.minecraft.world.level.block.state.BlockState state) {
        return state.isAir()||state.is(Blocks.SHORT_GRASS)||state.is(Blocks.TALL_GRASS)||state.is(Blocks.FERN)||state.is(Blocks.LARGE_FERN)||state.is(Blocks.SNOW);
    }

    /** Puts mined template blocks back (air only), the owner's multiplier deciding how many of the missing ones. */
    static int restore(ServerLevel level, CaptureSites.Point point, JsonObject state, double multiplier) {
        var missing = new ArrayList<BlockPos>(); var ids = new ArrayList<Block>();
        var rows=new ArrayList<JsonArray>();
        for (var value : state.getAsJsonArray("blocks")) {
            var row = value.getAsJsonArray();
            var pos = new BlockPos(point.x() + row.get(0).getAsInt(), row.get(1).getAsInt(), point.z() + row.get(2).getAsInt());
            var block = block(row.get(3).getAsString());
            if (block == null) continue;
            var current = level.getBlockState(pos);
            boolean planned=row.size()>4&&row.get(4).getAsBoolean();
            boolean natural=planned&&naturalSurface(current);
            boolean crop=current.is(block)&&(block instanceof net.minecraft.world.level.block.CropBlock c&&!c.isMaxAge(current)
                ||block==Blocks.SWEET_BERRY_BUSH&&current.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE)<3);
            if(current.is(block)&&planned)row.set(4,new com.google.gson.JsonPrimitive(false));
            boolean surfaceSeed=planned&&point.resource().output().has("placement")&&"SURFACE".equals(point.resource().output().get("placement").getAsString())&&surfaceSpace(current);
            if (natural || surfaceSeed || crop || current.isAir() || current.is(Blocks.WATER) && block != Blocks.WATER) { missing.add(pos); ids.add(block);rows.add(row); }
        }
        int limit=state.has("limit")?state.get("limit").getAsInt():CaptureSites.view().rules().getAsJsonObject("yield").getAsJsonArray("pit_blocks").get(point.resource().tier()-1).getAsInt();
        int allowed = Math.min(missing.size(),(int) Math.ceil(limit * Math.max(0, Math.min(1, multiplier))));
        int restored=0;
        for (int i = 0; i < allowed; i++) {
            var pos=missing.get(i);var block=ids.get(i);var fresh=block.defaultBlockState();
            if(block instanceof net.minecraft.world.level.block.CropBlock crop) {
                if(level.getBlockState(pos.below()).is(BlockTags.DIRT)||rows.get(i).size()>4&&rows.get(i).get(4).getAsBoolean()&&naturalSurface(level.getBlockState(pos.below())))level.setBlock(pos.below(),Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE,7),Block.UPDATE_CLIENTS);
                if(!level.getBlockState(pos.below()).is(Blocks.FARMLAND))continue;
                fresh=crop.getStateForAge(crop.getMaxAge());
            } else if(block==Blocks.SWEET_BERRY_BUSH) {
                if(!level.getBlockState(pos.below()).is(BlockTags.DIRT)&&rows.get(i).size()>4&&rows.get(i).get(4).getAsBoolean()&&naturalSurface(level.getBlockState(pos.below())))level.setBlock(pos.below(),Blocks.DIRT.defaultBlockState(),Block.UPDATE_CLIENTS);
                fresh=fresh.setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE,3);
            }
            if(level.setBlock(pos,fresh,Block.UPDATE_ALL))restored++;
            if(rows.get(i).size()>4)rows.get(i).set(4,new com.google.gson.JsonPrimitive(false));
        }
        return restored;
    }

    static int fillCache(CaptureSites.Point point, JsonObject state, JsonObject rules, double multiplier) {
        var cache = point.resource().output().has("items") ? point.resource().output() : rules.getAsJsonObject("caches").getAsJsonObject(point.resource().family());
        int slots = rules.getAsJsonObject("yield").get("cache_slots").getAsInt(), refreshes = state.get("refreshes").getAsInt();
        if (cache == null || refreshes >= slots) return 0; // full: nothing more until the owner collects
        var items = state.getAsJsonArray("items");
        int added = 0;
        for (var value : cache.getAsJsonArray("items")) {
            var pair = value.getAsJsonArray();
            String id = pair.get(0).getAsString();
            var location = ResourceLocation.tryParse(id);
            if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) continue;
            int count = multiplier <= 0 ? 0 : Math.max(1, (int) Math.round(pair.get(1).getAsInt() * multiplier));
            boolean merged = false;
            for (var existing : items) {
                var row = existing.getAsJsonArray();
                if (row.get(0).getAsString().equals(id)) { row.set(1, new com.google.gson.JsonPrimitive(row.get(1).getAsInt() + count)); merged = true; break; }
            }
            if (!merged) { var row = new JsonArray(); row.add(id); row.add(count); items.add(row); }
            added += count;
        }
        state.addProperty("refreshes", refreshes + 1);
        return added;
    }

    /**
     * Gives the stash to an owner clan member standing in the zone. What does not fit stays in the stash.
     * The stash file is written before the call returns; if that write fails, the items are taken back,
     * so a failure never pays out twice. Entries of items missing from this modpack are kept, not dropped.
     */
    static int collect(MinecraftServer server, ServerPlayer player, CaptureSites.Point point) throws IOException {
        Path path = file(server, "caches", point.code());
        var state = read(path);
        if (state == null) return 0;
        var remaining = new JsonArray();
        var given = new java.util.LinkedHashMap<net.minecraft.world.item.Item, Integer>();
        int total = 0;
        var inventory = player.getInventory();
        for (var value : state.getAsJsonArray("items")) {
            var row = value.getAsJsonArray();
            var location = ResourceLocation.tryParse(row.get(0).getAsString());
            int count = row.get(1).getAsInt();
            if (count <= 0) continue;
            if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) { remaining.add(row); continue; }
            var item = BuiltInRegistries.ITEM.get(location);
            int max = item.getDefaultInstance().getMaxStackSize();
            while (count > 0) {
                var stack = new ItemStack(item, Math.min(max, count));
                int offered = stack.getCount();
                inventory.add(stack); // shrinks the stack to what did not fit
                int taken = offered - stack.getCount();
                count -= taken; total += taken; given.merge(item, taken, Integer::sum);
                if (taken < offered) break; // inventory full
            }
            if (count > 0) { var rest = new JsonArray(); rest.add(row.get(0).getAsString()); rest.add(count); remaining.add(rest); }
        }
        state.add("items", remaining);
        if (remaining.isEmpty()) state.addProperty("refreshes", 0);
        try { write(path, state); }
        catch (IOException failure) {
            for (var entry : given.entrySet()) inventory.clearOrCountMatchingItems(stack -> stack.is(entry.getKey()), entry.getValue(), player.inventoryMenu.getCraftSlots());
            throw failure;
        }
        return total;
    }

    static String status(MinecraftServer server,List<ServerPlayer> players,CaptureSites.Point point,long now) {
        if(ERRORS.containsKey(point.code()))return ERRORS.get(point.code());
        var next=point.resource().nextRefresh();
        if(next==null||next.getEpochSecond()<=now) {
            if(occupied(players,server,point))return "пополнение ждёт выхода игроков из зоны";
            return loaded(server.overworld(),point)?"пополнение выполняется":"ожидается загрузка зоны";
        }
        return "пополнение через "+CaptureCommands.duration((int)(next.getEpochSecond()-now))+("CACHE".equals(point.resource().kind())?" · получить: ПКМ по бочке или /aoc resource collect":"");
    }
    static void interact(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if(event.getHand()!=net.minecraft.world.InteractionHand.MAIN_HAND||!(event.getEntity() instanceof ServerPlayer player))return;
        var point=CaptureEntry.here(player.server,player,CaptureSites.view());
        if(point==null||point.resource()==null||!"CACHE".equals(point.resource().kind()))return;
        // Only the site's own centre barrel; arbitrary player containers are not redirected.
        if(event.getPos().getX()!=point.x()||event.getPos().getZ()!=point.z()||Math.abs(event.getPos().getY()-CaptureAnchors.height(player.server,point))>2
            ||!event.getLevel().getBlockState(event.getPos()).is(Blocks.BARREL))return;
        event.setCanceled(true);event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        try {CaptureCommands.collect(player.createCommandSourceStack());}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid){player.displayClientMessage(net.minecraft.network.chat.Component.literal("Тайник недоступен."),true);}
    }
    static void reset(MinecraftServer server) { ResourceRefreshChunks.release(server);ERRORS.clear();RETRY.clear();IN_FLIGHT.set(false); }
}
