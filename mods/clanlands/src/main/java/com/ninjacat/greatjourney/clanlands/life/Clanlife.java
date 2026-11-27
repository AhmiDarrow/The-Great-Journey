package com.ninjacat.greatjourney.clanlands.life;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.dream.Dream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Living in the Clans' world: the four lake Clans with their camps and cats, the StarClan dream for new cats, the
 * three ways to begin, and joining a Clan. Everything here runs only in worlds that have the Clan lands.
 */
public final class Clanlife {
    private Clanlife() {}

    /** Warrior Cats Events marks a player whose cat setup is finished with this flag. */
    private static final String WCE_SETUP_DONE = "warriorcats_events.starting_items";

    public static void init(IEventBus modBus) {
        Mode.init();
        ChunkWait.init();
        Lead.init();
        Keepsakes.init();
        Healing.init();
        LoreBridge.init();
        com.ninjacat.greatjourney.clanlands.life.gathering.Gatherings.init();
        Dream.init(modBus);
        com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.init(modBus);
        com.ninjacat.greatjourney.clanlands.life.quests.Quests.init(modBus);
        com.ninjacat.greatjourney.clanlands.life.route.RouteLife.init(modBus);
        com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.init(modBus);
        com.ninjacat.greatjourney.clanlands.life.traffic.Traffic.init(modBus);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            var gen = e.getServer().overworld().getChunkSource().getGenerator();
            org.slf4j.LoggerFactory.getLogger(Clanlife.class).info("Great Journey: overworld generator {} ({}), biome source {}, Clan lands {}",
                    gen.getClass().getName(),
                    gen instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator n ? n.generatorSettings().unwrapKey().map(Object::toString).orElse("direct") : "-",
                    gen.getBiomeSource().getClass().getName(), isClanWorld(e.getServer().overworld()));
            // Warrior Cats Events' own "choose where to spawn" picker teleports blind (it dropped a player through the
            // world) and skips our starts: the Clan lands always place players themselves
            if (isClanWorld(e.getServer().overworld())) {
                var teleport = net.snowteb.warriorcats_events.zconfig.WCEServerConfig.SERVER.TELEPORT_WHEN_JOIN;
                if (teleport.get()) teleport.set(false);
            }
            BookClans.setup(e.getServer());
            logCampBiomes(e.getServer().overworld());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> Dialog.reset());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> forgetWceSequences());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) Dialog.forget(p);
        });
        NeoForge.EVENT_BUS.addListener(Clanlife::tick);
        NeoForge.EVENT_BUS.addListener(Clanlife::loggedIn);
        NeoForge.EVENT_BUS.addListener(Clanlife::chunkLoaded);
        NeoForge.EVENT_BUS.addListener(Clanlife::commands);
    }

    /**
     * Warrior Cats Events keeps its delayed jobs in one static list that no world closing ever clears. Every entity
     * joining a level queues one (wait 10 ticks, then refresh the player's shape); a world closed within those ticks
     * (a teleport just before quitting) leaves it behind, and the next world in the same game runs it on the old
     * player: a chunk read on the dead server's level that waits forever, so the new world freezes on its first tick
     * (the free-starts playtest, 2026-10-12: the WindClan world hung after the loner world was quit).
     */
    private static void forgetWceSequences() {
        try {
            var f = net.snowteb.warriorcats_events.managers.SequenceManager.class.getDeclaredField("active");
            f.setAccessible(true);
            ((java.util.Collection<?>) f.get(null)).clear();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(Clanlife.class).warn("Great Journey: could not clear Warrior Cats Events' sequences: {}", ex.toString());
        }
    }

    /** The overworld uses the normal generator, so the Clan lands are there (not superflat, amplified or others). */
    public static boolean isClanWorld(ServerLevel level) {
        return com.ninjacat.greatjourney.clanlands.world.ClanWorld.isClanWorld(level);
    }

    /** One log line per camp naming the biome the overworld's biome source gives there: a quick check of the map. */
    private static void logCampBiomes(ServerLevel ow) {
        var w = com.ninjacat.greatjourney.clanlands.world.ClanWorld.current();
        if (w == null || !isClanWorld(ow)) return;
        var source = ow.getChunkSource().getGenerator().getBiomeSource();
        var sampler = ow.getChunkSource().randomState().sampler();
        for (var c : com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan.values()) {
            var camp = w.lake.camp(c);
            var biome = source.getNoiseBiome((int) camp.x() >> 2, 72 >> 2, (int) camp.z() >> 2, sampler);
            org.slf4j.LoggerFactory.getLogger(Clanlife.class).info("Great Journey: {} camp at {} {} is in {}", c,
                    (int) camp.x(), (int) camp.z(), biome.unwrapKey().map(k -> k.location().toString()).orElse("?"));
        }
    }

    private static void loggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Starts.loggedIn(p);
    }

    private static void tick(ServerTickEvent.Post e) {
        var server = e.getServer();
        Dialog.tick(server);
        int t = server.getTickCount();
        if (!isClanWorld(server.overworld())) return;
        if (t % 6000 == 0) BookClans.refresh(server);
        if (t % 20 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (Journey.stage(p) == Journey.Stage.NEW && wceSetupDone(p)) {
                    if (Mode.of(server) == Mode.CAMPAIGN) {
                        com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.begin(p);
                    } else {
                        Journey.setStage(p, Journey.Stage.DREAM);
                        Dream.begin(p);
                    }
                }
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) shieldUntilStarted(p);
            Starts.tick(server);                        // the start choice in both modes; Free Mode's invitations
            TunnelKeeper.tick(server);
        }
    }

    /**
     * Warrior Cats Events' first join is over: its cat creator has been saved. WCE only marks "starting_items" (inside
     * the "PlayerPersisted" compound) when its own spawn picker teleports the player, and the Clan lands turn that
     * picker off, so the creator's saved flag counts too. Once over, the WCE flag is set as the picker would have, or
     * WCE keeps the player invulnerable and reopens its setup on every login.
     */
    static boolean wceSetupDone(ServerPlayer p) {
        net.minecraft.nbt.CompoundTag persisted = p.getPersistentData().getCompound("PlayerPersisted");
        boolean done = persisted.getBoolean(WCE_SETUP_DONE) || p.getPersistentData().getBoolean(WCE_SETUP_DONE)
                || p.getData(net.snowteb.warriorcats_events.attachments.ModAttachments.PLAYER_WCE_DATA.get()).isFirstLoginHandled();
        if (done && !persisted.getBoolean(WCE_SETUP_DONE)) {
            persisted.putBoolean(WCE_SETUP_DONE, true);
            p.getPersistentData().put("PlayerPersisted", persisted);
            if (p.isInvulnerable() && !p.isCreative() && !p.isSpectator()) p.setInvulnerable(false);
        }
        return done;
    }

    private static final String SHIELD = "greatjourney.shielded";

    /**
     * A cat who has not begun their story yet (the dream, the Gathering at Fourtrees, the start choice) cannot be
     * hurt: the dialogue does not pause the game, and the playtest harness saw a new Campaign player killed by a
     * zombie while the leaders were still speaking. The shield drops the moment the story begins.
     */
    private static void shieldUntilStarted(ServerPlayer p) {
        boolean waiting = Journey.stage(p) != Journey.Stage.PLAYING && !p.isCreative() && !p.isSpectator();
        boolean shielded = p.getPersistentData().getBoolean(SHIELD);
        if (waiting && !shielded) {
            p.getPersistentData().putBoolean(SHIELD, true);
            p.setInvulnerable(true);
        } else if (!waiting && shielded) {
            p.getPersistentData().remove(SHIELD);
            if (!p.isCreative() && !p.isSpectator()) p.setInvulnerable(false);
        }
    }

    private static void chunkLoaded(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            BookClans.populateIfDue(level, e.getChunk().getPos());
            TunnelKeeper.populateIfDue(level, e.getChunk().getPos());
        }
    }

    private static void commands(RegisterCommandsEvent e) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("greatjourney");
        root.then(Commands.literal("join").then(Commands.argument("clan", StringArgumentType.word()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            Info clan = Info.byKey(StringArgumentType.getString(c, "clan"));
            if (clan == null || !Journey.hasOffer(p, clan.key())) {
                p.sendSystemMessage(Component.literal("No Clan has invited you yet. Visit a camp, and they may ask."));
                return 0;
            }
            if (Mode.of(p.getServer()) == Mode.CAMPAIGN) {
                return com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.acceptInvite(p, clan) ? 1 : 0;
            }
            return Joining.join(p, clan) ? 1 : 0;
        })));
        root.then(Commands.literal("notyet").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            Journey.clearOffer(p);
            Dialog.play(p, Dialog.Line.narrate("You dip your head. Perhaps another day."));
            return 1;
        }));
        root.then(Commands.literal("start")
                .then(Commands.literal("kittypet").executes(c -> Starts.begin(c.getSource().getPlayerOrException(), Journey.Start.KITTYPET, null) ? 1 : 0))
                .then(Commands.literal("loner").executes(c -> Starts.begin(c.getSource().getPlayerOrException(), Journey.Start.LONER, null) ? 1 : 0))
                .then(Commands.literal("clan").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (Journey.stage(p) != Journey.Stage.CHOOSING) return 0;
                    Starts.chooseClan(p);
                    return 1;
                }).then(Commands.argument("clan", StringArgumentType.word()).executes(c -> {
                    Info clan = Info.byKey(StringArgumentType.getString(c, "clan"));
                    return clan != null && Starts.begin(c.getSource().getPlayerOrException(), Journey.Start.CLAN, clan) ? 1 : 0;
                }))));
        Starts.commands(root);
        Dream.commands(root);
        com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.commands(root);
        com.ninjacat.greatjourney.clanlands.life.quests.Quests.commands(root);
        com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.commands(root);
        com.ninjacat.greatjourney.clanlands.life.traffic.Traffic.commands(root);
        com.ninjacat.greatjourney.clanlands.life.tour.Tour.commands(root);
        com.ninjacat.greatjourney.clanlands.life.gathering.Gatherings.commands(root);
        e.getDispatcher().register(root);
    }
}
