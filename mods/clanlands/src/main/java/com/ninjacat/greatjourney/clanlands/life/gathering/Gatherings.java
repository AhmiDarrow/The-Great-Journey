package com.ninjacat.greatjourney.clanlands.life.gathering;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import com.ninjacat.greatjourney.clanlands.life.campaign.Campaign;
import com.ninjacat.greatjourney.clanlands.life.campaign.CampaignData;
import com.ninjacat.greatjourney.clanlands.life.campaign.Chapter;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import com.ninjacat.greatjourney.clanlore.moon.Moon;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The moon's meetings: the Gathering at every full moon ({@link FullMoon}) and the medicine cats' half-moon meeting
 * ({@link HalfMoon}), wherever the Clans live now: on the island and at the Moonpool at the lake, at Fourtrees and
 * Mothermouth in the Campaign's old forest ({@link Grounds}). They run in Free Mode and in the Campaign, except while
 * the Campaign's Clans are away on the Great Journey. The leaders tell what has happened in their Clans since the
 * last Gathering from {@link ClanNews}.
 *
 * <p>The Campaign's own scripted Gatherings come first. A player at the opening Gathering at Fourtrees (chapter
 * CHOOSE) or still to see the first Gathering at the lake (chapter GATHERING), or in the middle of one of its scenes
 * or dialogues, is not invited and does not see ours. Ours does not begin on a night a newcomer is waking into the
 * Fourtrees opening, nor at the lake before the Campaign's first island Gathering has been held, nor on the night it
 * is; and one whose Clans have not shown up yet gives way if the Campaign's own scene cats appear there.
 *
 * <p>The moon itself, and the full-moon truce, are Clan Lore's ({@link Moon}). In every Clan-lands world the island
 * and Fourtrees are registered with it as truce areas, so the truce holds there on any full-moon night (the
 * Campaign's own Gatherings included).
 */
public final class Gatherings {
    private Gatherings() {}

    static final String FOURTREES = "greatjourney:fourtrees";

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> places(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            FullMoon.reset();
            HalfMoon.reset();
            Crowd.reset();
            firstIslandPending = false;
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            MinecraftServer server = e.getServer();
            if (server.getTickCount() % 20 != 11) return;
            Crowd.tick(server);
            if (!running(server)) return;
            FullMoon.tick(server);
            HalfMoon.tick(server);
        });
        NeoForge.EVENT_BUS.addListener(Crowd::entityJoined);
        Moon.onTruceBroken(FullMoon::truceBroken);
    }

    /** On the Campaign's own scene cats (Campaign's SCENE_TAG): the leaders at its scripted Gatherings. */
    static final String CAMPAIGN_SCENE = "greatjourney_scene";
    /** The Campaign's first island Gathering had not been held yet when last looked (see {@link #campaignHeldFirstIsland}). */
    private static boolean firstIslandPending;

    /**
     * Whether the moon's meetings run in this world: the Clan lands, in Free Mode, or in the Campaign except while the
     * Clans are away on the Great Journey (between leaving the forest and settling at the lake).
     */
    static boolean running(MinecraftServer server) {
        ServerLevel ow = server.overworld();
        if (ClanWorld.current() == null || !ClanWorld.isClanWorld(ow)) return false;
        return Mode.of(server) == Mode.FREE ? !Homeland.forest() : !journeying(ow);
    }

    /** Tonight's meeting under the moon, for the map: the Gathering on a full moon, the medicine cats' on a half. */
    public static java.util.List<com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark> marks(net.minecraft.server.level.ServerPlayer p) {
        java.util.List<com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark> out = new java.util.ArrayList<>();
        MinecraftServer server = p.getServer();
        if (!running(server) || Crowd.clanOf(p) == null
                || com.ninjacat.greatjourney.clanlands.life.Journey.stage(p) != com.ninjacat.greatjourney.clanlands.life.Journey.Stage.PLAYING) return out;
        ServerLevel ow = server.overworld();
        ClanWorld w = ClanWorld.current();
        Grounds g = Grounds.current(w);
        var moon = com.ninjacat.greatjourney.clanlore.moon.Moon.phase(ow);
        if (g != null && moon.full() && !com.ninjacat.greatjourney.clanlore.moon.Moon.cloudedOver(ow)) {
            out.add(com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark.at("gathering", "The Gathering at " + g.place() + ", tonight", g.x(), g.z()));
        }
        Grounds.Shrine sh = Grounds.shrine(w);
        if (sh != null && com.ninjacat.greatjourney.clanlore.moon.Moon.isHalfMoon(ow) && HalfMoon.medicine(p)) {
            out.add(com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark.at("halfmoon", "The medicine cats meet at " + sh.name() + ", tonight", sh.x(), sh.z()));
        }
        return out;
    }

    /** The Campaign's Clans are on the Great Journey. */
    static boolean journeying(ServerLevel ow) {
        CampaignData cd = CampaignData.get(ow.getServer().overworld());
        return cd.departed() && cd.clansInForest();
    }

    private static boolean campaign(ServerLevel ow) {
        return Mode.of(ow.getServer()) == Mode.CAMPAIGN;
    }

    /** The player's Campaign chapter is one of its own Gatherings: the opening at Fourtrees, or the first at the lake. */
    static boolean storyChapter(ServerPlayer p) {
        if (!campaign(p.serverLevel())) return false;
        Chapter ch = Campaign.chapter(p);
        return ch == Chapter.CHOOSE || ch == Chapter.GATHERING;
    }

    /** The Campaign has the player busy: one of its Gatherings, a dialogue going on, or its scene cats close by. */
    static boolean storyBusy(ServerPlayer p) {
        if (!campaign(p.serverLevel())) return false;
        return storyChapter(p) || Dialog.busy(p) || campaignScene(p.serverLevel(), p.getX(), p.getZ(), 48);
    }

    /** The Campaign's scene cats are loaded within r of x, z (it is staging a scene there). */
    static boolean campaignScene(ServerLevel ow, double x, double z, double r) {
        if (!campaign(ow)) return false;
        AABB box = new AABB(x - r, ow.getMinBuildHeight(), z - r, x + r, ow.getMaxBuildHeight(), z + r);
        return !ow.getEntitiesOfClass(Entity.class, box, e -> e.getTags().contains(CAMPAIGN_SCENE)).isEmpty();
    }

    /** At the lake, the Campaign has not held its first island Gathering yet: ours waits for it. */
    static boolean campaignFirstIslandAhead(ServerLevel ow) {
        return campaign(ow) && !Homeland.forest() && !CampaignData.get(ow.getServer().overworld()).complete();
    }

    /** True once, when the Campaign's first island Gathering has just been held (that night is the Campaign's). */
    static boolean campaignHeldFirstIsland(ServerLevel ow) {
        boolean pending = campaignFirstIslandAhead(ow);
        boolean held = firstIslandPending && !pending && campaign(ow) && !Homeland.forest();
        firstIslandPending = pending;
        return held;
    }

    /**
     * Whether the Campaign stages a Gathering of its own tonight, so ours stays away: a newcomer is waking into the
     * opening at Fourtrees (chapter CHOOSE, which also brings on the full moon in singleplayer), or its scene cats are
     * already at the place.
     */
    static boolean campaignHolds(ServerLevel ow, Grounds g) {
        if (!campaign(ow)) return false;
        if (Homeland.forest()) {
            for (ServerPlayer p : ow.getServer().getPlayerList().getPlayers()) if (Campaign.chapter(p) == Chapter.CHOOSE) return true;
        }
        return campaignScene(ow, g.x(), g.z(), g.radius() + 160);
    }

    /**
     * The longest the moon waits for a cat still on the way to a meeting: ten minutes of real time, so a player who
     * wandered off or went AFK cannot hold the night for ever.
     */
    static final long HOLD_MAX_TICKS = 20L * 60 * 10;

    /**
     * Holds the night where it is: winds the clock of every level back to {@code at} (time of day) if it has passed
     * it, as {@code /time set} would, so the moon stays up while a cat is still walking to the meeting. The day's
     * count (and so the moon's phase) is kept.
     */
    static void holdNight(MinecraftServer server, long at) {
        ServerLevel ow = server.overworld();
        long t = Moon.timeOfDay(ow);
        if (t <= at) return;
        long back = t - at;
        for (ServerLevel l : server.getAllLevels()) {
            if (!l.dimensionType().hasFixedTime()) l.setDayTime(l.getDayTime() - back);
        }
    }

    /** Players who said Go to a meeting and are still on their way to it (online, in the Clans' world, alive). */
    static boolean anyOnTheWay(MinecraftServer server, java.util.Set<java.util.UUID> going, java.util.Set<java.util.UUID> arrived) {
        for (java.util.UUID u : going) {
            if (arrived.contains(u)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(u);
            if (p != null && p.isAlive() && !p.isSpectator() && p.level() == server.overworld()) return true;
        }
        return false;
    }

    /** The island and Fourtrees become truce areas for Clan Lore's full-moon truce. */
    private static void places(MinecraftServer server) {
        ClanWorld w = ClanWorld.current();
        if (w == null || !ClanWorld.isClanWorld(server.overworld())) return;
        Grounds island = Grounds.island(w);
        // the island, its channel and the island half of the fallen tree
        if (island != null) Moon.truceArea(FullMoon.ISLAND, Level.OVERWORLD, island.x(), island.z(), island.radius() + 14);
        // the hollow and the woods round its rim
        Grounds ft = Grounds.fourtrees(w);
        if (ft != null) Moon.truceArea(FOURTREES, Level.OVERWORLD, ft.x(), ft.z(), ft.radius() + 24);
    }

    // --- commands

    /**
     * /greatjourney gathering start|stop|status (operators: call a Gathering now whatever the moon, end it, see it),
     * and go|stay|home|linger (the answers to the invitation and the walk home, run from the dialogue's choices).
     * /greatjourney halfmoon (operators: call the medicine cats' meeting now), with stop and status, and go|stay.
     */
    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("gathering")
                .then(Commands.literal("start").requires(s -> s.hasPermission(2)).executes(c -> {
                    if (!usable(c)) return 0;
                    ServerLevel ow = c.getSource().getServer().overworld();
                    if (!FullMoon.call(ow)) return fail(c, "This world has no Gathering island.");
                    c.getSource().sendSuccess(() -> Component.literal("A Gathering is called on the island: the Clans' players are invited now."), true);
                    return 1;
                }))
                .then(Commands.literal("stop").requires(s -> s.hasPermission(2)).executes(c -> {
                    if (!FullMoon.stop(c.getSource().getServer().overworld())) return fail(c, "There is no Gathering to stop.");
                    c.getSource().sendSuccess(() -> Component.literal("The Gathering is over."), true);
                    return 1;
                }))
                .then(Commands.literal("status").requires(s -> s.hasPermission(2)).executes(c -> {
                    String s = FullMoon.status(c.getSource().getServer().overworld());
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                }))
                .then(Commands.literal("go").executes(c -> FullMoon.go(c.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("stay").executes(c -> FullMoon.stay(c.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("home").executes(c -> FullMoon.home(c.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("linger").executes(c -> FullMoon.linger(c.getSource().getPlayerOrException()) ? 1 : 0)));
        // the bare command is the operators' (it calls the meeting); go and stay answer the medicine cat's question
        root.then(Commands.literal("halfmoon")
                .executes(c -> {
                    if (!c.getSource().hasPermission(2)) return fail(c, "Only an operator can call the half-moon meeting.");
                    if (!usable(c)) return 0;
                    if (!HalfMoon.call(c.getSource().getServer().overworld())) return fail(c, "This world has no Moonpool.");
                    c.getSource().sendSuccess(() -> Component.literal("The medicine cats set off for the Moonpool."), true);
                    return 1;
                })
                .then(Commands.literal("stop").requires(s -> s.hasPermission(2)).executes(c -> {
                    if (!HalfMoon.stop(c.getSource().getServer().overworld())) return fail(c, "There is no half-moon meeting to stop.");
                    c.getSource().sendSuccess(() -> Component.literal("The medicine cats' meeting is over."), true);
                    return 1;
                }))
                .then(Commands.literal("status").requires(s -> s.hasPermission(2)).executes(c -> {
                    String s = HalfMoon.status(c.getSource().getServer().overworld());
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                }))
                .then(Commands.literal("go").executes(c -> HalfMoon.go(c.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("stay").executes(c -> HalfMoon.stay(c.getSource().getPlayerOrException()) ? 1 : 0)));
    }

    private static boolean usable(CommandContext<CommandSourceStack> c) {
        MinecraftServer server = c.getSource().getServer();
        if (running(server)) return true;
        boolean lands = ClanWorld.current() != null && ClanWorld.isClanWorld(server.overworld());
        fail(c, lands && Mode.of(server) == Mode.CAMPAIGN && journeying(server.overworld())
                ? "The Clans are away on the Great Journey: there are no Gatherings until they have found their new home."
                : "The moon's meetings need the Clan lands, with the Clans at home.");
        return false;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String why) {
        c.getSource().sendFailure(Component.literal(why));
        return 0;
    }
}
