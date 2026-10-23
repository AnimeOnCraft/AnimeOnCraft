package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class TerritoryProtection {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
    private static MinecraftServer server;
    private static volatile TerritoryPermissions policy;
    private record Ignition(Level level, BlockPos pos) {}
    private static final ThreadLocal<Ignition> ignition = new ThreadLocal<>();
    private TerritoryProtection() {}

    static void starting(ServerStartingEvent event) {
        server = null; policy = null;
        var path = Path.of("config/animeoncraft-clan-spawns.json");
        registerClans(path);
        if (!event.getServer().isDedicatedServer()) return;
        if (!Files.isRegularFile(path)) {
            LOGGER.warn("AOC territory protection inactive: no clan spawn configuration");
            return;
        }
        try {
            if (Files.size(path) > 65536) throw new IllegalArgumentException("Territory config too large");
            if (WorldLayout.active()) {
                // States world: owners come from the backend region catalog (WorldRegions), not from clan-world rectangles.
                var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                int radius = root.has("protection") ? root.getAsJsonObject("protection").get("spawn_radius_blocks").getAsInt() : 16;
                spawnRadius = radius;
                policy = TerritoryPermissions.states(WorldRegions.loadCache(), radius);
                WarWindows.load();
                ClanControlSync.reset();
                server = event.getServer();
                ClanControlSync.refresh(server);
                WorldRegions.refresh(server);
                LOGGER.info("AOC territory protection enabled for a states world: regions={} spawn_radius={}",
                    policy.regions() == null ? "none yet (fail closed)" : policy.regions().regions().size(), radius);
                return;
            }
            policy = TerritoryPermissions.read(JsonParser.parseString(Files.readString(path)).getAsJsonObject(), event.getServer().overworld().getSeed());
            WarWindows.load();
            ClanControlSync.reset();
            server = event.getServer();
            ClanControlSync.refresh(server);
            LOGGER.info("AOC territory protection enabled: clans={} spawn_radius={}", policy.territories().size(), policy.spawnRadius());
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid AOC territory protection config; refusing unprotected startup", failure);
        }
    }

    /** The clan registry comes from the same config as territories; nothing is compiled in. */
    private static void registerClans(Path path) {
        ClanCodes.register(java.util.List.of());
        if (!Files.isRegularFile(path)) return;
        try {
            if (Files.size(path) > 65536) throw new IllegalArgumentException("Territory config too large");
            ClanCodes.register(ClanCodes.fromConfig(JsonParser.parseString(Files.readString(path)).getAsJsonObject()));
            LOGGER.info("AOC clan registry: {}", ClanCodes.all());
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid AOC clan registry config", failure);
        }
    }

    static void stopped(ServerStoppedEvent event) { if (server == event.getServer()) { server = null; policy = null; WorldRegions.reset(); } }

    private static int spawnRadius = 16;
    /** Owner of a column under the active policy (clan code, {@link RegionPolicy#NOBODY} or null); null when protection is off. */
    static String ownerAt(int x, int z) { var current = policy; return current == null ? null : current.territory(x, z); }
    /** True while a states world is protected by the region catalog. */
    static boolean statesWorld() { var current = policy; return current != null && current.states(); }
    /** A newer region catalog arrived (server thread): swap the policy atomically. */
    static void regionsChanged(RegionPolicy regions) {
        if (!statesWorld()) return;
        policy = TerritoryPermissions.states(regions, spawnRadius);
        if (server != null) ClanTerritories.broadcast(server);
        LOGGER.info("AOC region catalog applied: regions={} revision={}", regions.regions().size(), regions.revision().substring(0, 12));
    }

    private static boolean active(LevelAccessor level) {
        return policy != null && level instanceof ServerLevel world && world.getServer() == server && world.dimension().equals(Level.OVERWORLD);
    }

    private static boolean denied(LevelAccessor level, Entity entity, BlockPos pos) {
        if (!active(level)) return false;
        if (!policy.protectedBlock(pos.getX(), pos.getZ())) return false;
        var identity = entity == null ? null : GameIdentity.PLAYERS.get(entity.getUUID());
        String owner = ClanControlSync.owner(policy.territory(pos.getX(), pos.getZ()));
        boolean spawn = policy.spawn(pos.getX(), pos.getZ());
        boolean operator = entity instanceof ServerPlayer player && player.hasPermissions(2);
        boolean raid = !spawn && identity != null && WarWindows.canRaid(owner, identity.clan());
        boolean allowed = identity != null && (operator || (ClanControlSync.ready() && !spawn && (owner == null || owner.equals(identity.clan()) || raid)));
        if(identity==null&&entity instanceof net.neoforged.neoforge.common.util.FakePlayer&&ClanControlSync.ready()&&!spawn){
            allowed=canConstructFrom(level,entity.blockPosition(),pos);
        }
        if (!allowed && entity instanceof ServerPlayer player) player.displayClientMessage(
            Component.literal(policy.spawn(pos.getX(), pos.getZ()) ? "Здесь защищённая точка спавна." : "Строить и ломать блоки здесь могут только участники этого клана."), true);
        return !allowed;
    }

    static void breaking(BlockEvent.BreakEvent event) {
        if (denied(event.getLevel(), event.getPlayer(), event.getPos()) || CaptureSites.pitDenied(event.getLevel(), event.getPlayer(), event.getPos())) event.setCanceled(true);
    }

    static void placing(BlockEvent.EntityPlaceEvent event) {
        // A held resource pit is the owner's: nobody else fills or blocks it.
        if (event.getEntity() instanceof Player && CaptureSites.pitDenied(event.getLevel(), event.getEntity(), event.getPos())) { event.setCanceled(true); return; }
        // Natural non-player placement is handled only in protected regions; neutral world generation is untouched.
        if (!(event.getEntity() instanceof Player) && (!active(event.getLevel()) || !policy.protectedBlock(event.getPos().getX(), event.getPos().getZ()))) return;
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (var snapshot : multi.getReplacedBlockSnapshots()) if (denied(event.getLevel(), event.getEntity(), snapshot.getPos())) {
                event.setCanceled(true); return;
            }
        } else if (denied(event.getLevel(), event.getEntity(), event.getPos())) event.setCanceled(true);
    }

    static void tool(BlockEvent.BlockToolModificationEvent event) {
        if (denied(event.getLevel(), event.getPlayer(), event.getPos()) || CaptureSites.pitDenied(event.getLevel(), event.getPlayer(), event.getPos())) event.setCanceled(true);
    }

    static void trampling(BlockEvent.FarmlandTrampleEvent event) {
        if (active(event.getLevel()) && policy.protectedBlock(event.getPos().getX(), event.getPos().getZ())
            && denied(event.getLevel(), event.getEntity(), event.getPos())) event.setCanceled(true);
    }

    static void explosion(ExplosionEvent.Detonate event) {
        if (active(event.getLevel())) event.getAffectedBlocks().removeIf(pos -> policy.protectedBlock(pos.getX(), pos.getZ()));
        if (event.getLevel() instanceof ServerLevel world && world.dimension().equals(Level.OVERWORLD)) event.getAffectedBlocks().removeIf(CaptureSites::pitBlock);
    }

    static void mobGriefing(net.neoforged.neoforge.event.entity.EntityMobGriefingEvent event) {
        var entity = event.getEntity();
        if (!active(entity.level())) return;
        int x = entity.blockPosition().getX(), z = entity.blockPosition().getZ();
        var current = policy;
        if (current.states()) {
            // States world: the same 64-block approach, sampled around the mob against the region owners.
            for (int dx = -64; dx <= 64; dx += 32) for (int dz = -64; dz <= 64; dz += 32)
                if (current.protectedBlock(x + dx, z + dz)) { event.setCanGrief(false); return; }
            return;
        }
        // Boss attacks reach beyond their feet. Leave neutral wilderness intact,
        // but stop mob griefing in the 64-block approach to claims and protected spawns.
        for (var region : policy.territories()) if ((long)x >= region.minX()-64L && (long)x < region.maxX()+64L
                && (long)z >= region.minZ()-64L && (long)z < region.maxZ()+64L) {
            event.setCanGrief(false); return;
        }
        for (var spawn : policy.spawns()) if (Math.abs((long)x-spawn.x()) <= policy.spawnRadius()+64L
                && Math.abs((long)z-spawn.z()) <= policy.spawnRadius()+64L) {
            event.setCanGrief(false); return;
        }
    }

    static void useBlock(PlayerInteractEvent.RightClickBlock event) {
        var state = event.getLevel().getBlockState(event.getPos());
        if (event.getLevel().getBlockEntity(event.getPos()) != null || state.getMenuProvider(event.getLevel(),event.getPos()) != null) {
            if (!(net.neoforged.fml.ModList.get().isLoaded("lightmanscurrency")&&PublicShopAccess.allowed(event))&&denied(event.getLevel(), event.getEntity(), event.getPos())) {
                event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true); return;
            }
        }
        var item = event.getItemStack().getItem();
        if (!(item instanceof BucketItem) && item != Items.FLINT_AND_STEEL && item != Items.FIRE_CHARGE) return;
        var adjacent = event.getPos().relative(event.getHitVec().getDirection());
        if (denied(event.getLevel(), event.getEntity(), event.getPos()) || denied(event.getLevel(), event.getEntity(), adjacent)) {
            event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true);
        }
    }

    static void useBucket(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getItemStack().getItem() instanceof BucketItem) || !active(event.getLevel())) return;
        var player = event.getEntity(); var start = player.getEyePosition();
        var hit = event.getLevel().clip(new ClipContext(start, start.add(player.getViewVector(1).scale(player.blockInteractionRange())),
            ClipContext.Block.OUTLINE, event.getItemStack().is(Items.BUCKET) ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, player));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        var target = event.getItemStack().is(Items.BUCKET) ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        if (denied(event.getLevel(), player, target)) { event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true); }
    }

    static void piston(PistonEvent.Pre event) {
        if (!active(event.getLevel())) return;
        var origin = event.getPos();
        var direction = event.getDirection();
        var movement = event.getPistonMoveType().isExtend ? direction : direction.getOpposite();
        var head = event.getFaceOffsetPos();
        if (!move(origin, head)) { event.setCanceled(true); return; }
        var resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        for (var pos : resolver.getToPush()) if (!move(origin, pos) || !move(pos, pos.relative(movement))) {
            event.setCanceled(true); return;
        }
        for (var pos : resolver.getToDestroy()) if (!move(origin, pos)) { event.setCanceled(true); return; }
    }

    public static boolean protectedPosition(LevelAccessor level,BlockPos pos){return active(level)&&policy.protectedBlock(pos.getX(),pos.getZ());}
    public static boolean firePlacementDenied(Level level,BlockPos pos){
        if(!protectedPosition(level,pos))return false;
        var allowed=ignition.get();
        return allowed==null||allowed.level()!=level||!allowed.pos().equals(pos);
    }
    public static boolean placePlayerFire(Level level,BlockPos pos,net.minecraft.world.level.block.state.BlockState state,int flags,Player player){
        if(!state.is(Blocks.FIRE)&&!state.is(Blocks.SOUL_FIRE))return level.setBlock(pos,state,flags);
        if(!protectedPosition(level,pos))return level.setBlock(pos,state,flags);
        var identity=player==null?null:GameIdentity.PLAYERS.get(player.getUUID());
        String owner=ClanControlSync.owner(policy.territory(pos.getX(),pos.getZ()));
        boolean operator=player instanceof ServerPlayer p&&p.hasPermissions(2);
        if(policy.spawn(pos.getX(),pos.getZ())||identity==null||(!operator&&(!ClanControlSync.ready()||owner==null||!owner.equals(identity.clan()))))return false;
        var previous=ignition.get();ignition.set(new Ignition(level,pos.immutable()));
        try{return level.setBlock(pos,state,flags);}
        finally{if(previous==null)ignition.remove();else ignition.set(previous);}
    }
    static void fluidPlacement(BlockEvent.FluidPlaceBlockEvent event){
        if(protectedPosition(event.getLevel(),event.getPos())&&(!canTransfer(event.getLevel(),event.getLiquidPos(),event.getPos())
            ||event.getNewState().is(Blocks.FIRE)||event.getNewState().is(Blocks.SOUL_FIRE)))event.setCanceled(true);
    }
    public static boolean canTransfer(LevelAccessor level,BlockPos from,BlockPos to){
        return !active(level)||move(from,to);
    }
    public static boolean canConstructFrom(LevelAccessor level,BlockPos source,BlockPos target){
        if(!active(level))return true;
        if(policy.spawn(target.getX(),target.getZ()))return false;
        String owner=ClanControlSync.owner(policy.territory(target.getX(),target.getZ()));
        if(owner==null)return true;
        return ClanControlSync.ready()&&owner.equals(ClanControlSync.owner(policy.territory(source.getX(),source.getZ())));
    }
    static void registerSpellProtection(){
        if(!net.neoforged.fml.ModList.get().isLoaded("irons_spellbooks"))return;
        try{
            var type=Class.forName("io.redspace.ironsspellbooks.api.events.SpellPreCastEvent").asSubclass(net.neoforged.neoforge.event.entity.player.PlayerEvent.class);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(type,event->spell(event));
        }catch(ClassNotFoundException failure){throw new IllegalStateException("Unsupported Iron's Spells event API",failure);}
    }
    static void spell(net.neoforged.neoforge.event.entity.player.PlayerEvent event){
        if(!event.getClass().getName().equals("io.redspace.ironsspellbooks.api.events.SpellPreCastEvent"))return;
        if(event instanceof net.neoforged.bus.api.ICancellableEvent cancellable && denied(event.getEntity().level(),event.getEntity(),event.getEntity().blockPosition()))cancellable.setCanceled(true);
    }
    static void damage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event){
        if(hostilePlayerEffectDenied(event.getEntity(),event.getSource().getEntity()))event.setCanceled(true);
    }
    public static boolean hostilePlayerEffectDenied(Entity victim,Entity source){
        if(!(victim instanceof ServerPlayer target)||!(source instanceof ServerPlayer attacker))return false;
        var attackerIdentity=GameIdentity.PLAYERS.get(attacker.getUUID());var targetIdentity=GameIdentity.PLAYERS.get(target.getUUID());
        boolean friendly=attackerIdentity!=null&&targetIdentity!=null&&attackerIdentity.clan()!=null&&attackerIdentity.clan().equals(targetIdentity.clan());
        return friendly||denied(target.level(),attacker,target.blockPosition());
    }
    private static boolean move(BlockPos from, BlockPos to) { return !policy.spawn(from.getX(),from.getZ()) && !policy.spawn(to.getX(),to.getZ())
        && java.util.Objects.equals(ClanControlSync.owner(policy.territory(from.getX(),from.getZ())),ClanControlSync.owner(policy.territory(to.getX(),to.getZ()))); }
}
