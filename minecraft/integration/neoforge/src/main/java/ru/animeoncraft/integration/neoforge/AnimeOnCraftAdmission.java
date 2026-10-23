package ru.animeoncraft.integration.neoforge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@Mod("animeoncraft")
public final class AnimeOnCraftAdmission {
    private static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(ResourceLocation.fromNamespaceAndPath("animeoncraft", "admission"));
    private static final Map<ServerConfigurationPacketListenerImpl, AdmissionTask> PENDING = new ConcurrentHashMap<>();

    public AnimeOnCraftAdmission(IEventBus bus) {
        BossHealthRange.initialize();
        Worldgen.register(bus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldgenProbe::started);
        bus.addListener(AnimeOnCraftAdmission::payloads);
        bus.addListener(AnimeOnCraftAdmission::tasks);
        var events = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        events.addListener(CentralSpawn::starting);
        events.addListener(CentralSpawn::stopped);
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,CentralSpawn::placement);
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,CentralSpawn::join);
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,PlayerChat::chat);
        events.addListener(PlayerChat::login);
        events.addListener(ServerEmotes::tick);events.addListener(ServerEmotes::tracking);events.addListener(ServerEmotes::logout);events.addListener(ServerEmotes::stopped);
        events.addListener(ServerWardrobe::tick);events.addListener(ServerWardrobe::logout);events.addListener(ServerWardrobe::stopped);events.addListener(BetaPassServer::login);events.addListener(BetaPassServer::logout);events.addListener(BetaPassServer::tracking);events.addListener(BetaPassServer::tick);events.addListener(BetaPassServer::stopped);events.addListener(BetaPassServer::commands);
        events.addListener(PlayerProfiles::login);events.addListener(PlayerProfiles::logout);events.addListener(PlayerProfiles::tracking);events.addListener(PlayerProfiles::tick);events.addListener(PlayerProfiles::stopped);
        events.addListener(WorldAtlas::login);
        events.addListener(WorldAtlas::tick);
        events.addListener(WorldAtlas::stopped);
        if(Boolean.getBoolean("animeoncraft.chatSpawnProbe"))events.addListener(ChatSpawnProbe::started);
        events.addListener(WarCommands::register);
        events.addListener(TerritoryCapture::tick);
        // World Design stage 4: capture engine (inactive until the backend's active world has capture rules).
        events.addListener(CaptureCommands::register);
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,ResourcePits::interact);
        // World Design stage 5: POI and road builder (only acts in a states world with aoc/layout.json).
        events.addListener(LayoutJobs::register);
        events.addListener(LayoutJobs::tick);
        events.addListener(LayoutJobs::stopped);
        if(System.getProperty("animeoncraft.layoutProbe")!=null){events.addListener(LayoutProbe::started);events.addListener(LayoutProbe::tick);}
        if(Boolean.getBoolean("animeoncraft.rehearsal")){events.addListener(StatesRehearsalProbe::started);events.addListener(StatesRehearsalProbe::tick);}
        events.addListener(CaptureSites::tick);
        // World Design stages 7–8: region catalog of a states world for protection (inactive in the current world).
        events.addListener(WorldRegions::tick);
        events.addListener(CaptureArena::tick);
        events.addListener(CaptureArena::login);
        events.addListener(CaptureArena::logout);
        events.addListener(CaptureArena::stopped);
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,CaptureArena::death);
        if(Boolean.getBoolean("animeoncraft.captureProbe"))events.addListener(CapturePointsProbe::tick);
        events.addListener(ClanPositions::tick);
        events.addListener(ClanPositions::login);
        events.addListener(ClanPositions::logout);
        if(Boolean.getBoolean("animeoncraft.acceptance.clanPositions"))events.addListener(ClanPositionProbe::login);
        events.addListener(GameplayCommands::register);
        events.addListener(WorldBossEncounter::tick);
        events.addListener(WorldBossEncounter::stopped);
        events.addListener(WorldBossEncounter::stopping);
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,ArenaChunkLifecycle::stopping);
        events.addListener(WorldBossEncounter::join);
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,WorldBossEncounter::preDamage);
        events.addListener(WorldBossEncounter::damage);
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,WorldBossEncounter::death);
        events.addListener(WorldBossEncounter::grief);
        events.addListener(WorldBossEncounter::drops);
        events.addListener(WorldBossEncounter::experience);
        events.addListener(WorldBossEncounter::explosion);
        events.addListener(WorldBossEncounter::protectSpawn);
        events.addListener(WorldBossEncounter::login);
        events.addListener(GameplayOutbox::tick);
        events.addListener(TerritoryProtection::starting);
        events.addListener(TerritoryProtection::stopped);
        TerritoryProtection.registerSpellProtection();
        events.addListener(TerritoryProtection::damage);
        // Run before bulk-break mods can mutate the first block.
        events.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,false,TerritoryProtection::breaking);
        events.addListener(PersonalQuestPolicy::starting);
        events.addListener(PersonalQuestProbe::tick);
        events.addListener(TerritoryProtection::placing);
        events.addListener(TerritoryProtection::tool);
        events.addListener(TerritoryProtection::trampling);
        events.addListener(TerritoryProtection::explosion);
        events.addListener(TerritoryProtection::mobGriefing);
        events.addListener(TerritoryProtection::piston);
        events.addListener(TerritoryProtection::useBlock);
        events.addListener(TerritoryProtection::useBucket);
        events.addListener(TerritoryProtection::fluidPlacement);
        events.addListener(TerritoryProtectionProbe::started);
        if(Boolean.getBoolean("animeoncraft.territoryProbe"))events.addListener(WorldBossProbe::tick);
        if(Boolean.getBoolean("animeoncraft.territoryProbe"))events.addListener(ControllerRestartProbe::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldSurvey::started);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClanSpawns::started);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClanSpawns::login);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClanSpawns::respawn);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClanSpawns::logout);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClanSpawns::clonePlayer);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClanTerritories::login);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CapeJoin::login);
        // Observe cancellation by other mods before awarding clan XP.
        events.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,GameplayEvents::death);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClanControlSync::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClanOnlineHeartbeat::tick);
        events.addListener(ServerMenuSync::logout);
        events.addListener(ServerMenuSync::stopped);
        if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.register(bus);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        var beta=event.registrar("1").optional();
        beta.playToServer(EmotePairPayloads.Request.TYPE,EmotePairPayloads.Request.CODEC,ServerPairEmotes::request);
        beta.playToClient(EmotePairPayloads.Notice.TYPE,EmotePairPayloads.Notice.CODEC,(p,c)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.emotePair(p,c);});
        beta.playToServer(EmotePayloads.Request.TYPE,EmotePayloads.Request.CODEC,ServerEmotes::request);
        beta.playToClient(EmotePayloads.Inventory.TYPE,EmotePayloads.Inventory.CODEC,(p,c)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.emoteInventory(p,c);});
        beta.playToClient(EmotePayloads.State.TYPE,EmotePayloads.State.CODEC,(p,c)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.emoteState(p,c);});
        beta.playToServer(WardrobePayloads.Request.TYPE,WardrobePayloads.Request.CODEC,ServerWardrobe::request);
        beta.playToClient(WardrobePayloads.Snapshot.TYPE,WardrobePayloads.Snapshot.CODEC,(p,c)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.wardrobe(p,c);});
        beta.playToServer(BetaPassPayloads.Request.TYPE,BetaPassPayloads.Request.CODEC,BetaPassServer::request);
        beta.playToClient(BetaPassPayloads.Snapshot.TYPE,BetaPassPayloads.Snapshot.CODEC,(p,c)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.beta(p,c);});
        beta.playToClient(BetaPassPayloads.Appearance.TYPE,BetaPassPayloads.Appearance.CODEC,(p,c)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.cosmetics(p,c);});
        var menu=event.registrar("1").optional();
        menu.playToClient(ServerMenuPayloads.CaptureSummary.TYPE,ServerMenuPayloads.CaptureSummary.CODEC,(p,c)->{
            if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientServerMenu.acceptCapture(p,c);
        });
        menu.playToClient(ClanAchievementPayload.TYPE,ClanAchievementPayload.CODEC,(payload,context)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.achievements(payload,context);});
        menu.playToServer(ProfilePayloads.Request.TYPE,ProfilePayloads.Request.CODEC,PlayerProfiles::request);
        menu.playToClient(ProfilePayloads.Snapshot.TYPE,ProfilePayloads.Snapshot.CODEC,(payload,context)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.profile(payload,context);});
        menu.playToClient(ProfilePayloads.Title.TYPE,ProfilePayloads.Title.CODEC,(payload,context)->{if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.title(payload,context);});
        menu.playToServer(ServerMenuPayloads.Request.TYPE,ServerMenuPayloads.Request.CODEC,ServerMenuSync::request);
        menu.playToClient(ServerMenuPayloads.Snapshot.TYPE,ServerMenuPayloads.Snapshot.CODEC,(payload,context)->{
            if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.menu(payload,context);
        });
        var registrar = event.registrar("2").optional();
        registrar.configurationToClient(AdmissionPayloads.Challenge.TYPE, AdmissionPayloads.Challenge.CODEC, AnimeOnCraftAdmission::challenge);
        registrar.configurationToServer(AdmissionPayloads.Proof.TYPE, AdmissionPayloads.Proof.CODEC, AnimeOnCraftAdmission::proof);
        registrar.playToClient(SkinPayload.TYPE,SkinPayload.CODEC,(payload,context)->{
            if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.receive(payload,context);
        });
        registrar.playToClient(CapeTexturePayload.TYPE,CapeTexturePayload.CODEC,(payload,context)->{
            if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.cape(payload,context);
        });
        registrar.playToClient(ClanPositionsPayload.TYPE,ClanPositionsPayload.CODEC,(payload,context)->{
            if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.positions(payload,context);
        });
        registrar.playToClient(TerritoryPayload.TYPE,TerritoryPayload.CODEC,(payload,context)->{
            if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.territories(payload,context);
        });
        registrar.playToClient(RegionMapPayload.TYPE,RegionMapPayload.CODEC,(payload,context)->{
            if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.regionMap(payload,context);
        });
        registrar.playToClient(WorldAtlasPayload.TYPE,WorldAtlasPayload.CODEC,(payload,context)->{
            if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT)ClientRegistration.atlas(payload,context);
        });
    }

    private static final class ClientRegistration {
        static void emotePair(EmotePairPayloads.Notice p,IPayloadContext c){ClientEmotes.notice(p,c);}
        static void emoteInventory(EmotePayloads.Inventory p,IPayloadContext c){ClientEmotes.inventory(p,c);}
        static void emoteState(EmotePayloads.State p,IPayloadContext c){ClientEmotes.state(p,c);}
        static void achievements(ClanAchievementPayload payload,IPayloadContext context){ClientClanAchievements.accept(payload,context);}
        static void wardrobe(WardrobePayloads.Snapshot p,IPayloadContext c){ClientWardrobe.accept(p,c);}
        static void beta(BetaPassPayloads.Snapshot p,IPayloadContext c){ClientBetaPass.accept(p,c);}
        static void cosmetics(BetaPassPayloads.Appearance p,IPayloadContext c){ClientBetaPass.appearance(p,c);}
        static void profile(ProfilePayloads.Snapshot payload,IPayloadContext context){ClientProfiles.accept(payload,context);}
        static void title(ProfilePayloads.Title payload,IPayloadContext context){ClientProfiles.title(payload,context);}
        static void register(IEventBus bus){
            bus.addListener(ClientEmoteMenu::register);
            var events=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
            events.addListener(ClientEmotes::logout);
            events.addListener(ClientWardrobe::logout);events.addListener(ClientBetaPass::logout);bus.addListener(BetaCosmeticRenderer::register);bus.addListener(DetailedCosmetics::reload);events.addListener(ClientClanAchievements::logout);events.addListener(ClientProfiles::logout);events.addListener(ClientServerMenu::key);events.addListener(ClientServerMenu::tick);events.addListener(ClientServerMenu::layer);events.addListener(ClientServerMenu::logout);
            if(Boolean.getBoolean("animeoncraft.atlasClientProbe"))AtlasClientProbe.register();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientWorldAtlas::logout);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientWorldAtlas::tick);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientSavedServers::tick);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientSkins::logout);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientClanPositions::logout);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientClanPositions::tick);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientTerritories::logout);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CapeRenderProbe::tick);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientNameTags::render);
        }
        static void receive(SkinPayload payload,IPayloadContext context){ClientSkins.accept(payload,context);}
        static void cape(CapeTexturePayload payload,IPayloadContext context){ClientSkins.acceptCape(payload,context);}
        static void positions(ClanPositionsPayload payload,IPayloadContext context){ClientClanPositions.accept(payload,context);}
        static void territories(TerritoryPayload payload,IPayloadContext context){ClientTerritories.accept(payload,context);}
        static void regionMap(RegionMapPayload payload,IPayloadContext context){ClientTerritories.acceptPolygons(payload,context);}
        static void atlas(WorldAtlasPayload payload,IPayloadContext context){ClientWorldAtlas.accept(payload,context);}
        static void menu(ServerMenuPayloads.Snapshot payload,IPayloadContext context){ClientServerMenu.accept(payload,context);}
    }

    private static void tasks(RegisterConfigurationTasksEvent event) {
        if (event.getListener() instanceof ServerConfigurationPacketListenerImpl listener
                && listener.getMainThreadEventLoop() instanceof MinecraftServer server && server.isDedicatedServer()) {
            event.register(new AdmissionTask(listener));
        }
    }

    private static void challenge(AdmissionPayloads.Challenge challenge, IPayloadContext context) {
        try {
            ClientTicketBridge.ticket(challenge, context.connection().getRemoteAddress()).whenComplete((ticket, failure) ->
                    context.enqueueWork(() -> {
                        if (failure != null) context.disconnect(Component.literal(HttpJson.updateRequired(failure)?"Вышло обновление AnimeOnCraft. Обновите лаунчер и заново запустите Minecraft.":"Не удалось получить игровой код. Запустите игру через AnimeOnCraft."));
                        else context.reply(new AdmissionPayloads.Proof(challenge.connection(), ticket));
                    }));
        } catch (RuntimeException failure) {
            context.disconnect(Component.literal("Для входа требуется запуск через AnimeOnCraft."));
        }
    }

    private static void proof(AdmissionPayloads.Proof proof, IPayloadContext context) {
        if (!(context.listener() instanceof ServerConfigurationPacketListenerImpl listener)) return;
        AdmissionTask task = PENDING.get(listener);
        if (task == null || !task.connection.equals(proof.connection()) || !task.submitted.compareAndSet(false, true)) {
            if (task != null) PENDING.remove(listener, task);
            context.disconnect(Component.literal("Недействительный игровой код. Подключитесь заново."));
            return;
        }
        var profile = listener.getOwner();
        AdmissionHttpClient.redeem(task.settings, task.connection, profile.getName(), profile.getId(), proof.ticket())
                .whenComplete((result, failure) -> listener.getMainThreadEventLoop().execute(() -> {
                    if (!PENDING.remove(listener, task) || !listener.getConnection().isConnected()) return;
                    if (failure == null) {
                        GameIdentity.PLAYERS.put(profile.getId(),result);
                        listener.finishCurrentTask(TASK);
                    }
                    else listener.disconnect(Component.literal(HttpJson.updateRequired(failure)?"Вышло обновление AnimeOnCraft. Обновите лаунчер и заново запустите Minecraft.":"Вход не разрешён. Проверьте доступ в AnimeOnCraft и подключитесь заново."));
                }));
    }

    private static final class AdmissionTask implements ICustomConfigurationTask {
        private final ServerConfigurationPacketListenerImpl listener;
        private final UUID connection = UUID.randomUUID();
        private final AtomicBoolean submitted = new AtomicBoolean();
        private ServerSettings settings;

        AdmissionTask(ServerConfigurationPacketListenerImpl listener) { this.listener = listener; }

        @Override public void run(Consumer<CustomPacketPayload> sender) {
            try {
                settings = ServerSettings.from(System.getenv());
                if (!listener.hasChannel(AdmissionPayloads.Challenge.TYPE) || !listener.hasChannel(AdmissionPayloads.Proof.TYPE)) {
                    listener.disconnect(Component.literal("Установите сборку AnimeOnCraft для входа на сервер."));
                    return;
                }
                PENDING.put(listener, this);
                CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS).execute(() -> listener.getMainThreadEventLoop().execute(() -> {
                    if (PENDING.remove(listener, this)) listener.disconnect(Component.literal("Время входа истекло. Подключитесь заново через AnimeOnCraft."));
                }));
                sender.accept(new AdmissionPayloads.Challenge(settings.serverId(), connection));
            } catch (RuntimeException failure) {
                PENDING.remove(listener, this);
                listener.disconnect(Component.literal("Игровой вход на сервере ещё не настроен."));
            }
        }

        @Override public ConfigurationTask.Type type() { return TASK; }
    }
}
