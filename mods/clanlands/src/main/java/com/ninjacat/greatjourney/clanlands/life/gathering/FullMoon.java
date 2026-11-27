package com.ninjacat.greatjourney.clanlands.life.gathering;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.ChunkWait;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.Lead;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Starts;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestState;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import com.ninjacat.greatjourney.clanlore.moon.Moon;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The Gathering at the full moon, as in the books: on the island in the lake, or at Fourtrees while a Campaign's
 * Clans still live in the old forest ({@link Grounds}). In Free Mode and in the Campaign (not while the Clans are on
 * the Great Journey); the Campaign's own scripted Gatherings come first (see {@link Gatherings#storyBusy} and
 * {@link Gatherings#campaignHolds}).
 * <ol>
 *   <li>Late in the afternoon before a full-moon night, each Clan player is invited by their leader ("Tonight is the
 *       full moon. You will come to the Gathering.") and answers Go or Not tonight. A player busy being led
 *       somewhere (a mentor's walk) is asked once the walk is over.</li>
 *   <li>Go: a Clanmate steps up and leads them there with {@link Lead}, round the lake and over the fallen tree to
 *       the clearing. Only if no cat can walk them are they taken to the tree-bridge. Kittypets and loners near the
 *       lake are told the Clans are crossing to the island, and may watch from the shore.</li>
 *   <li>From dusk, once a player comes within sight of the island, the Clans are there: each leader beneath the
 *       Great Oak, and a few warriors, an apprentice and the medicine cat of each Clan sitting in the clearing (scene
 *       cats with their Clan's own names; see {@link Crowd}).</li>
 *   <li>When everyone who said Go has arrived (or, for anyone still on the way, two-thirds through the night), the
 *       leaders tell their Clans' news to everyone within sight of the island ({@link ClanNews}, or a line from the
 *       books when a Clan has none). Clan players who stayed home hear a line of it as a subtitle. Then the Clans go
 *       home, and a player's guide offers to walk them home.</li>
 *   <li>If nobody came, the Gathering still happens: at the end of the night, each Clan's players hear its news.</li>
 *   <li>The truce of the full moon (Clan Lore's {@link Moon}) holds on the island all night; a player who breaks it
 *       clouds the moon over, and the Gathering ends there and then.</li>
 * </ol>
 * The state of a Gathering lives only while the server runs; who was invited each night is kept on the player.
 */
final class FullMoon {
    private FullMoon() {}

    /** The truce area Clan Lore keeps for the island. */
    static final String ISLAND = "greatjourney:island";
    /** Times of day: the invitation, dusk (the Clans set out), full dark, the last call for latecomers, the end. */
    static final long INVITE_FROM = 10000, DUSK = 12000, DARK = 13000, LAST_CALL = 20500, SILENT = 22000;
    /** While a cat who said Go is still on the way, the night is held here, short of the last call. */
    static final long HOLD_AT = 20000;
    /** The Clans appear on the island once a player is within this much of its shore. */
    private static final double SPAWN_NEAR = 160;
    /** Clanless players this far beyond the island's shore are told the Clans are gathering. */
    private static final double SHORE_NOTE = 220;
    /** A called Gathering (operators) ends by itself after this long, if nobody stops it. */
    private static final long CALLED_TICKS = 12000;
    private static final String INVITED = "gathering.invited", WATCHED = "gathering.watched";

    /** Tonight's Gathering, or null. */
    private static Run run;
    /** The last night a Gathering was held, so it is held once. */
    private static long lastNight = Long.MIN_VALUE;
    /** Guides who walked a player to the Gathering: player id to cat id, until the walk home is over. */
    private static final Map<UUID, UUID> GUIDES = new HashMap<>();

    static final class Run {
        /** The night's number plus one (never 0, the "never" of a player's stamp), or minus the game time for a called one. */
        final long id;
        final boolean called;
        final long night, startedAt;
        final Set<UUID> going = new HashSet<>(), arrived = new HashSet<>(), declined = new HashSet<>();
        final Map<Clan, UUID> leaders = new EnumMap<>(Clan.class);
        final List<UUID> crowd = new ArrayList<>();
        boolean spawning, spawned;
        long spawnedAt, lastArrival;
        /** How long the night has been held for cats still on the way (see {@link Gatherings#holdNight}). */
        long held;
        boolean toldWaiting;

        Run(long id, boolean called, long night, long now) {
            this.id = id;
            this.called = called;
            this.night = night;
            this.startedAt = now;
        }
    }

    static void reset() {
        run = null;
        lastNight = Long.MIN_VALUE;
        GUIDES.clear();
    }

    static boolean active() {
        return run != null;
    }

    static String status(ServerLevel ow) {
        if (run == null) {
            return "No Gathering. The moon is " + Moon.phase(ow).words + "; " + Moon.daysUntilFull(ow) + " days to the full moon"
                    + (Moon.cloudedOver(ow) ? " (the truce was broken tonight)" : "") + ".";
        }
        return (run.called ? "A called Gathering" : "Tonight's Gathering") + ": " + run.going.size() + " going, " + run.arrived.size()
                + " arrived, " + run.declined.size() + " staying home, the Clans " + (run.spawned ? "at " + Grounds.current(ClanWorld.current()).place() : "not there yet") + ".";
    }

    // --- the night

    /** Once a second while the moon's meetings run (see {@link Gatherings#running}). */
    static void tick(MinecraftServer server) {
        ServerLevel ow = server.overworld();
        Grounds g = Grounds.current(ClanWorld.current());
        if (g == null) return;
        long t = Moon.timeOfDay(ow), now = ow.getGameTime();
        if (Gatherings.campaignHeldFirstIsland(ow)) {
            // the Campaign has just held its own first Gathering on the island: tonight is its, not ours
            lastNight = Moon.night(ow);
            if (run != null && !run.called) end(ow, g, How.STOPPED);
        }
        if (run != null && !run.called && run.spawned == false && Gatherings.campaignScene(ow, g.x(), g.z(), g.radius() + SPAWN_NEAR)) {
            lastNight = run.night;                          // the Campaign is staging a Gathering here: its scene wins
            end(ow, g, How.STOPPED);
        }
        if (run != null && !stillOn(ow, run, t, now)) end(ow, g, Moon.cloudedOver(ow) ? How.BROKEN : run.called ? How.STOPPED : How.NIGHT_OVER);
        if (run == null) {
            long night = Moon.night(ow);
            if (!Moon.phase(ow).full() || Moon.cloudedOver(ow) || night == lastNight || t < INVITE_FROM || t >= SILENT) return;
            if (Gatherings.campaignFirstIslandAhead(ow)) return;
            if (Gatherings.campaignHolds(ow, g)) {
                lastNight = night;
                return;
            }
            run = new Run(night + 1, false, night, now);
            Moon.safePassage(ow, true);                     // the Clans cross each other's land to get there, in peace
        }
        Run r = run;
        if (!r.called && Gatherings.anyOnTheWay(server, r.going, r.arrived) && r.held < Gatherings.HOLD_MAX_TICKS && t > HOLD_AT) {
            // the Clans wait for everyone who said Go: the moon stays where it is until they are there
            r.held += 20;                                   // checked once a second
            Gatherings.holdNight(server, HOLD_AT);
            t = HOLD_AT;
            if (!r.toldWaiting) {
                r.toldWaiting = true;
                for (UUID u : r.going) {
                    ServerPlayer q = server.getPlayerList().getPlayer(u);
                    if (q != null && !r.arrived.contains(u)) Dialog.subtitle(q, Line.narrate(g.island()
                            ? "The moon hangs over the island. The Clans will not begin until you are there."
                            : "The moon hangs over Fourtrees. The Clans will not begin until you are there."));
                }
            }
        }
        boolean dark = r.called || t >= DUSK;
        for (ServerPlayer p : ow.players()) {
            if (Journey.stage(p) != Journey.Stage.PLAYING || p.isSpectator()) continue;
            Clan c = Crowd.clanOf(p);
            if (c != null) {
                if (!QuestState.quiet(p) && Journey.stamped(p, INVITED) != r.id && Crowd.rank(p) != ClanData.ClanPlayerRank.KIT
                        // (a mentor out with their own apprentice is still called: the apprentice goes home)
                        && (Lead.of(p) == null || com.ninjacat.greatjourney.clanlands.life.quests.Pupils.isPupil(Lead.of(p).cat()))
                        && !com.ninjacat.greatjourney.clanlands.life.tour.Tour.touring(p) && !Gatherings.storyBusy(p)) invite(p, c, r);
                if (g.on(p.getX(), p.getZ()) && r.arrived.add(p.getUUID())) r.lastArrival = now;
            } else if (dark && Journey.stamped(p, WATCHED) != r.id && Crowd.near(p, g.x(), g.z(), g.radius() + SHORE_NOTE) && !Gatherings.storyBusy(p)) {
                Journey.stamp(p, WATCHED, r.id);
                Dialog.subtitle(p, Line.narrate(g.island()
                        ? "Across the water, cats are padding over a fallen tree to the island. It is the full moon, and the Clans meet there in peace. You could watch from the shore."
                        : "Cats are slipping through the trees toward Fourtrees, the great hollow where four oaks grow. It is the full moon, and the Clans meet there in peace. You could watch from the rim."));
            }
        }
        if (dark && !r.spawned && !r.spawning && anyNear(ow, g.x(), g.z(), g.radius() + SPAWN_NEAR)) {
            r.spawning = true;
            ChunkWait.whenLoaded(ow, g.x(), g.z(), (int) g.radius(), () -> spawn(ow, g, r));
        }
        if (r.spawned && ready(ow, g, r, t, now)) tellNews(ow, g, r);
        else if (!r.called && t >= SILENT) end(ow, g, How.NIGHT_OVER);
    }

    private static boolean stillOn(ServerLevel ow, Run r, long t, long now) {
        if (Moon.cloudedOver(ow)) return false;
        if (r.called) return Moon.held(ow) && now - r.startedAt < CALLED_TICKS;
        return Moon.night(ow) == r.night && Moon.phase(ow).full() && t < Moon.dayStart();
    }

    /** The news begins once everyone who said Go is there and has sat down, or late in the night for latecomers. */
    private static boolean ready(ServerLevel ow, Grounds g, Run r, long t, long now) {
        if (witnesses(ow, g).isEmpty()) return false;
        boolean all = true;
        for (UUID u : r.going) {
            ServerPlayer p = ow.getServer().getPlayerList().getPlayer(u);
            if (p != null && p.level() == ow && !r.arrived.contains(u)) all = false;
        }
        boolean settled = now - Math.max(r.spawnedAt, r.lastArrival) >= 20 * 15;
        if (r.called) return (all && settled) || now - r.spawnedAt > 20 * 180;
        return t >= DARK && ((all && settled) || t >= LAST_CALL);
    }

    /** Players who can see the Gathering (on the island or the shore across, in the hollow or up on its rim), less any the Campaign's own Gathering holds. */
    private static List<ServerPlayer> witnesses(ServerLevel ow, Grounds g) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : ow.players()) {
            if (!p.isSpectator() && Crowd.near(p, g.x(), g.z(), g.radius() + g.watch()) && !Gatherings.storyChapter(p)) out.add(p);
        }
        return out;
    }

    private static boolean anyNear(ServerLevel ow, double x, double z, double r) {
        for (ServerPlayer p : ow.players()) if (!p.isSpectator() && Crowd.near(p, x, z, r)) return true;
        return false;
    }

    /** An operator calls a Gathering now, whatever the moon (for testing). */
    static boolean call(ServerLevel ow) {
        Grounds g = Grounds.current(ClanWorld.current());
        if (g == null) return false;
        if (run != null) end(ow, g, How.STOPPED);
        Moon.holdGathering(ow, true);
        run = new Run(-ow.getGameTime() - 1, true, Moon.night(ow), ow.getGameTime());
        return true;
    }

    /** An operator ends the Gathering now. */
    static boolean stop(ServerLevel ow) {
        boolean was = run != null;
        Moon.holdGathering(ow, false);
        if (was) end(ow, Grounds.current(ClanWorld.current()), How.STOPPED);
        return was;
    }

    /**
     * Who speaks for a Clan among its own cats: its leader; where a player leads, their deputy (else a warrior), so no
     * stand-in wearing the player's name stands on the rock or sends them word.
     */
    static String voice(LifeData life, Clan c) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (life.leaderPlayer(c) == null || server == null || life.clanId(c) == null) return life.leader(c);
        var wc = net.snowteb.warriorcats_events.clan.ClanData.get(server.overworld()).getClan(life.clanId(c));
        String warrior = null;
        if (wc != null) {
            for (var cat : wc.clanCats.values()) {
                if (cat.catName == null) continue;
                String name = cat.catName.getString().replace("♂", "").replace("♀", "").trim();
                if ("DEPUTY".equals(cat.catRank)) return name;
                if (warrior == null && "WARRIOR".equals(cat.catRank)) warrior = name;
            }
        }
        return warrior != null ? warrior : life.leader(c);
    }

    // --- the invitation

    private static void invite(ServerPlayer p, Clan c, Run r) {
        Journey.stamp(p, INVITED, r.id);
        ClanWorld w = ClanWorld.current();
        LifeData life = LifeData.get(p.getServer().overworld());
        Info info = Info.of(c);
        String leader = voice(life, c);
        Landmark camp = Homeland.camp(w, c);
        boolean inCamp = Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) < camp.radius() + 24;
        Choice go = Choice.of("Go", "greatjourney gathering go"), no = new Choice("Not tonight", "greatjourney gathering stay", 0xAAAAAA),
                never = new Choice("Don't call me again", "greatjourney quest quiet on", 0xAAAAAA);
        List<Line> lines = new ArrayList<>();
        if (!inCamp) lines.add(Line.narrate("A Clanmate finds you, out of breath, with word from " + leader + "."));
        lines.add(Line.say(leader, info.colour, "Tonight is the full moon, " + Joining.catName(p) + ". You will come to the Gathering.").withChoices(go, no, never));
        Dialog.play(p, lines);
    }

    /** "Go": a Clanmate leads the player to the Gathering. */
    static boolean go(ServerPlayer p) {
        Run r = run;
        Clan c = Crowd.clanOf(p);
        Grounds g = Grounds.current(ClanWorld.current());
        if (r == null || c == null || g == null || Gatherings.storyChapter(p)) {
            Dialog.subtitle(p, Line.narrate("There is no Gathering tonight."));
            return false;
        }
        Info info = Info.of(c);
        r.going.add(p.getUUID());
        r.declined.remove(p.getUUID());
        if (g.on(p.getX(), p.getZ())) {
            if (r.arrived.add(p.getUUID())) r.lastArrival = p.level().getGameTime();
            return true;
        }
        UUID had = GUIDES.get(p.getUUID());
        if (had != null && Lead.of(p) != null && Lead.of(p).cat() != null && had.equals(Lead.of(p).cat().getUUID())) return true;   // already on the way
        if (p.level().dimension() != Level.OVERWORLD) {
            Dialog.subtitle(p, Line.narrate("The Gathering is in the Clans' own world. Come back there first."));
            return false;
        }
        WCatEntity guide = Crowd.guide(p, c, BookClans.Role.WARRIOR, null);
        double[] camp = campOf(c);
        Lead lead = guide == null ? null : Lead.start(p, guide, toGathering(g, p, c, info.colour), (l, how) -> walked(l, how, c, g));
        if (lead == null) {
            // no cat can walk them (nothing could be made, or the cat would not lead): the Clan has gone ahead, and
            // the player is taken to the foot of the tree-bridge (or the rim of Fourtrees) to catch up
            if (guide != null) Crowd.dismiss(p.serverLevel(), guide.getUUID());
            double[] at = g.arrival(camp[0], camp[1]);
            Crowd.carry(p, at[0], at[1], q -> Dialog.subtitle(q, Line.narrate(g.island()
                    ? "You catch up with your Clanmates at the fallen tree. The island lies just across the water."
                    : "You catch up with your Clanmates at the rim of Fourtrees. Below, the four great oaks stand silver in the moonlight.")));
            return true;
        }
        GUIDES.put(p.getUUID(), guide.getUUID());
        String name = Crowd.plain(guide);
        lead.whenWaiting(l -> Dialog.subtitle(l.player(), Line.say(name, info.colour, switch (l.player().getRandom().nextInt(3)) {
            case 0 -> "Keep up! The other Clans won't wait for us.";
            case 1 -> "This way. Stay close to me.";
            default -> "Hurry, or we'll miss the news!";
        }).speakerEntity(l.cat())));
        Dialog.play(p, Line.say(name, info.colour, g.island() ? "I'll walk with you. We cross to the island by the fallen tree. Stay close!"
                : "I'll walk with you. Fourtrees is this way, where the four territories meet. Stay close!").speakerEntity(guide));
        return true;
    }

    /** Quiet paws chosen: off tonight's list of those going (so no night is held for them), and the guide goes. */
    static void leave(ServerPlayer p) {
        if (run != null) run.going.remove(p.getUUID());
        Gatherings.dropGuide(p, GUIDES);
    }

    /** "Not tonight": the player stays home, and hears the news when the Clan comes back. */
    static boolean stay(ServerPlayer p) {
        Clan c = Crowd.clanOf(p);
        if (run == null || c == null) return false;
        run.declined.add(p.getUUID());
        run.going.remove(p.getUUID());
        LifeData life = LifeData.get(p.getServer().overworld());
        Dialog.play(p, Line.say(voice(life, c), Info.of(c).colour, "Then keep watch over the camp while we are gone. You'll hear the news when we're home."));
        return true;
    }

    /**
     * Round the lake (or the Fourtrees hollow) rather than across it to the way in, then in: over the fallen tree into
     * the clearing, or to the hollow's rim on the side of the player's camp and down into the hollow.
     */
    private static List<Lead.Stop> toGathering(Grounds g, ServerPlayer p, Clan c, int colour) {
        double[] camp = campOf(c);
        List<double[]> in = g.wayIn(camp[0], camp[1]);
        List<Lead.Stop> stops = new ArrayList<>();
        for (double[] pt : Starts.wayShore(ClanWorld.current(), p.getX(), p.getZ(), in.get(0)[0], in.get(0)[1])) stops.add(new Lead.Stop(pt[0], pt[1], 12, null));
        String word = g.island() ? "The fallen tree. Dig your claws into the bark, and don't look down." : "Fourtrees. Mind your paws on the way down.";
        stops.add(new Lead.Stop(in.get(0)[0], in.get(0)[1], 6, l -> Dialog.subtitle(l.player(),
                Line.say(Crowd.plain(l.cat()), colour, word).speakerEntity(l.cat()))));
        for (int i = 1; i < in.size(); i++) stops.add(new Lead.Stop(in.get(i)[0], in.get(i)[1], i == in.size() - 1 ? 8 : 6, null));
        return stops;
    }

    /** The Clan's camp where the Clans live now, {x, z}. */
    private static double[] campOf(Clan c) {
        Landmark camp = Homeland.camp(ClanWorld.current(), c);
        return new double[] {camp.x(), camp.z()};
    }

    /** The walk to the Gathering is over: the guide sits down with the player, or goes on without them. */
    private static void walked(Lead l, Lead.End how, Clan c, Grounds g) {
        ServerPlayer p = l.player();
        WCatEntity cat = l.cat();
        Run r = run;
        if (how == Lead.End.DONE && cat != null && cat.isAlive()) {
            Crowd.settle(cat, g.x(), g.z());
            if (r != null) {
                if (r.arrived.add(p.getUUID())) r.lastArrival = p.level().getGameTime();
                if (!p.hasDisconnected()) Dialog.subtitle(p, Line.say(Crowd.plain(cat), Info.of(c).colour,
                        "Here we are. Find a place to sit, and keep your ears open.").speakerEntity(cat));
            } else if (!p.hasDisconnected()) {
                // too late: the leaders have spoken and the Clans are leaving
                Dialog.play(p, Line.say(Crowd.plain(cat), Info.of(c).colour, "We're too late. The Gathering is over, and the Clans are going home.").speakerEntity(cat));
                GUIDES.put(p.getUUID(), cat.getUUID());             // the end cleared it: the walk home is still offered
                offerHome(p);
                Crowd.fade(cat, 20L * 60 * 4);
            }
            return;
        }
        GUIDES.remove(p.getUUID());
        if (cat != null) Crowd.dismiss(p.serverLevel(), cat.getUUID());
        if (r != null && !p.hasDisconnected() && p.isAlive() && !g.on(p.getX(), p.getZ())) {
            Dialog.subtitle(p, Line.narrate(g.island()
                    ? "Your Clanmate has gone on to the island without you. You can still find your own way: follow the shore to the fallen tree off RiverClan's land."
                    : "Your Clanmate has gone on to Fourtrees without you. You can still find your own way: Fourtrees lies where the four territories meet."));
        }
    }

    // --- the Clans at the Gathering

    /** Each leader beneath the Great Oak (or on the Great Rock), and their warriors, an apprentice and the medicine cat around them. */
    private static void spawn(ServerLevel ow, Grounds g, Run r) {
        if (run != r) return;                               // the Gathering ended while the place loaded
        LifeData life = LifeData.get(ow);
        RandomSource rand = RandomSource.create(r.id * 0x9E3779B97F4A7C15L);
        Set<String> taken = new HashSet<>();
        double base = g.base();                             // clear of the fallen tree, or between the oaks
        int i = 0;
        for (Clan c : Clan.values()) {
            double a = base + i++ * Math.PI / 2;
            String leader = voice(life, c);
            taken.add(leader);
            // the leaders stand at the oak's roots (or up on the rock), looking out over the Clans
            WCatEntity l = Crowd.scene(ow, c, leader, BookClans.Role.LEADER, g.seatX() + Math.cos(a) * g.seatR(), g.seatZ() + Math.sin(a) * g.seatR(),
                    g.seatX() + Math.cos(a) * 30, g.seatZ() + Math.sin(a) * 30);
            if (l != null) r.leaders.put(c, l.getUUID());
            List<String> names = new ArrayList<>(Crowd.names(ow, c, "WARRIOR", 2, rand, taken));
            List<String> apprentice = Crowd.names(ow, c, "APPRENTICE", 1, rand, taken);
            List<String> medicine = Crowd.names(ow, c, "MEDICINE", 1, rand, taken);
            List<BookClans.Role> roles = new ArrayList<>();
            for (int k = 0; k < names.size(); k++) roles.add(BookClans.Role.WARRIOR);
            names.addAll(apprentice);
            for (int k = 0; k < apprentice.size(); k++) roles.add(BookClans.Role.APPRENTICE);
            names.addAll(medicine);
            for (int k = 0; k < medicine.size(); k++) roles.add(BookClans.Role.MEDICINE);
            for (int k = 0; k < names.size(); k++) {
                double rr = g.ring() + (k % 2) * 3.5, b = a + (k - (names.size() - 1) / 2.0) * 0.24 * 9 / g.ring();
                WCatEntity cat = Crowd.scene(ow, c, names.get(k), roles.get(k), g.x() + Math.cos(b) * rr, g.z() + Math.sin(b) * rr, g.x(), g.z());
                if (cat != null) r.crowd.add(cat.getUUID());
            }
        }
        r.spawned = true;
        r.spawnedAt = ow.getGameTime();
    }

    // --- the news

    private static void tellNews(ServerLevel ow, Grounds g, Run r) {
        LifeData life = LifeData.get(ow);
        Clan host = Clan.values()[(int) Math.floorMod(r.id, 4L)];
        Map<Clan, List<String>> said = new EnumMap<>(Clan.class);
        for (Clan c : Clan.values()) said.put(c, report(ow, life, c, r.id));
        Info hi = Info.of(host);
        List<Line> lines = new ArrayList<>();
        if (g.island()) {
            lines.add(Line.narrate("The full moon floats above the island and lays a silver path across the lake. In the clearing, cats of four Clans share tongues with old friends."));
            lines.add(Line.narrate("Beneath the Great Oak, the four leaders take their places, and the clearing falls quiet."));
        } else {
            lines.add(Line.narrate("The full moon rides high over Fourtrees, and silver light falls between the four great oaks. In the hollow, cats of four Clans share tongues with old friends."));
            lines.add(Line.narrate("The four leaders leap up onto the Great Rock, and the hollow falls quiet."));
        }
        lines.add(Line.say(voice(life, host), hi.colour, "Cats of all Clans, welcome! Let the Gathering begin.").speakerEntity(entity(ow, r.leaders.get(host))));
        for (int k = 0; k < 4; k++) {
            Clan c = Clan.values()[(host.ordinal() + k) % 4];
            Entity who = entity(ow, r.leaders.get(c));
            List<String> s = said.get(c);
            for (int j = 0; j < s.size(); j += 2) {
                String text = (j == 0 ? Info.of(c).title + " has news. " : "") + s.get(j) + (j + 1 < s.size() ? " " + s.get(j + 1) : "");
                if (s.size() == 1 && isFlavour(c, s.get(0))) text = s.get(0);
                lines.add(Line.say(voice(life, c), Info.of(c).colour, text).speakerEntity(who));
            }
        }
        lines.add(Line.say(voice(life, host), hi.colour, "Then the Gathering is over. May StarClan light your paths home.").speakerEntity(entity(ow, r.leaders.get(host))));
        lines.add(Line.narrate(g.island() ? "Cats call farewells across the clearing as each Clan gathers itself and streams back over the fallen tree, toward its own territory."
                : "Cats call farewells across the hollow as each Clan gathers itself and climbs out between the oaks, toward its own territory."));
        Set<UUID> heard = new HashSet<>();
        for (ServerPlayer p : witnesses(ow, g)) {
            // a quiet cat only passing by gets the line from home, not the whole Gathering in a window
            if (QuestState.quiet(p) && !g.on(p.getX(), p.getZ())) continue;
            heard.add(p.getUUID());
            Dialog.play(p, lines);
            offerHome(p);
        }
        tellAbsent(ow, life, said, heard);
        ClanNews.markTold(ow, ow.getGameTime());
        end(ow, g, How.TOLD);
    }

    /** A player's guide offers to walk them home once the news is told. */
    private static void offerHome(ServerPlayer p) {
        UUID gid = GUIDES.get(p.getUUID());
        Clan c = Crowd.clanOf(p);
        // a guide still walking its player over (they will be asked when they arrive) is left to its walk
        if (gid == null || c == null || !(p.serverLevel().getEntity(gid) instanceof WCatEntity cat) || cat.distanceTo(p) > 40 || Lead.leading(cat)) return;
        if (QuestState.quiet(p)) {                              // no question for a quiet cat: the guide goes home
            linger(p);
            return;
        }
        Dialog.play(p, Line.say(Crowd.plain(cat), Info.of(c).colour, "Ready to go home? I'll lead the way.").speakerEntity(cat).withChoices(
                Choice.of("Lead the way home", "greatjourney gathering home"),
                new Choice("I'll stay a while", "greatjourney gathering linger", 0xAAAAAA)));
    }

    /** "Lead the way home": back over the fallen tree (or up out of the hollow), round the lake and into camp. */
    static boolean home(ServerPlayer p) {
        UUID had = GUIDES.get(p.getUUID());
        if (had != null && p.serverLevel().getEntity(had) instanceof WCatEntity walking && Lead.leading(walking)) return false;   // a second click
        UUID gid = GUIDES.remove(p.getUUID());
        Clan c = Crowd.clanOf(p);
        Grounds g = Grounds.current(ClanWorld.current());
        if (gid == null || c == null || g == null || !(p.serverLevel().getEntity(gid) instanceof WCatEntity cat) || !cat.isAlive()) {
            Dialog.subtitle(p, Line.narrate(g != null && !g.island() ? "Your Clanmates have already gone. You know the way: up out of the hollow, and home through your own territory."
                    : "Your Clanmates have already gone. You know the way: over the fallen tree, and home along the shore."));
            return false;
        }
        Info info = Info.of(c);
        double[] camp = campOf(c);
        List<double[]> off = g.on(p.getX(), p.getZ()) ? g.wayOut(camp[0], camp[1]) : List.of();
        Lead lead = Crowd.leadHome(p, cat, c, off, l -> Dialog.subtitle(l.player(), Line.say(Crowd.plain(l.cat()), info.colour,
                "Home again. Get some sleep: there'll be patrols in the morning.").speakerEntity(l.cat())));
        if (lead == null) {
            Crowd.fade(cat, 20 * 60);
            return false;
        }
        String name = Crowd.plain(cat);
        lead.whenWaiting(l -> Dialog.subtitle(l.player(), Line.say(name, info.colour, "This way home. Don't fall asleep on your paws!").speakerEntity(l.cat())));
        Dialog.subtitle(p, Line.say(name, info.colour, g.island() ? "Come on, then. Stay close on the bridge." : "Come on, then. Stay close on the climb.").speakerEntity(cat));
        return true;
    }

    /** "I'll stay a while": the guide goes home alone. */
    static boolean linger(ServerPlayer p) {
        UUID gid = GUIDES.remove(p.getUUID());
        Clan c = Crowd.clanOf(p);
        if (gid != null && c != null && p.serverLevel().getEntity(gid) instanceof WCatEntity cat) {
            Dialog.subtitle(p, Line.say(Crowd.plain(cat), Info.of(c).colour, "Don't stay out too long. The camp will be asleep.").speakerEntity(cat));
            Crowd.fade(cat, 20 * 60);
        }
        return true;
    }

    /** What a Clan's leader tells the others: its news, the cats it has lost to StarClan, or a line from the books. */
    static List<String> report(ServerLevel ow, LifeData life, Clan c, long seed) {
        List<String> out = new ArrayList<>();
        List<ClanNews.Item> news = ClanNews.untold(ow, c);
        for (ClanNews.Item it : news.subList(Math.max(0, news.size() - 3), news.size())) out.add(it.text());
        long since = ClanNews.lastTold(ow, c);
        List<LifeData.Fallen> fallen = life.starClan();
        int mourned = 0;
        for (int i = fallen.size() - 1; i >= 0 && mourned < 2; i--) {
            LifeData.Fallen f = fallen.get(i);
            if (f.clan() != c || f.gameTime() <= since) continue;
            if (since == Long.MIN_VALUE && ow.getGameTime() - f.gameTime() > Moon.DAY * 8) continue;   // the first Gathering tells only this moon's
            boolean told = false;
            for (String s : out) told |= s.contains(f.name());
            if (told) continue;
            out.add("old age".equals(f.cause()) ? f.name() + " has gone to hunt with StarClan, after a long and good life."
                    : "We grieve for " + f.name() + ", who now walks with StarClan.");
            mourned++;
        }
        if (out.isEmpty()) out.add(flavour(ow, c, seed));
        return out;
    }

    private static final Map<Clan, String[]> FLAVOUR = new EnumMap<>(Map.of(
            Clan.THUNDER, new String[] {"Prey is running well in ThunderClan's woods, and our apprentices are learning fast.",
                    "All is well in ThunderClan. The forest is full of prey, and our borders are strong."},
            Clan.WIND, new String[] {"Prey is running well in WindClan. The rabbits on the moor are fat and swift.",
                    "WindClan is strong. Our warriors run as fast as the wind itself."},
            Clan.RIVER, new String[] {"The lake is full of fish, and RiverClan is well fed.",
                    "All is well in RiverClan. Even our youngest apprentices swim like otters."},
            Clan.SHADOW, new String[] {"ShadowClan's warriors hunt well among the pines, and our borders are strong.",
                    "ShadowClan has nothing to report. All is well."}));

    /** A line from the books for a Clan with no news (a busy nursery, when it has one, is worth a word). */
    private static String flavour(ServerLevel ow, Clan c, long seed) {
        UUID id = LifeData.get(ow).clanId(c);
        ClanData.Clan wc = id == null ? null : ClanData.get(ow).getClan(id);
        int kits = 0;
        if (wc != null) for (ClanData.ClanCat cc : wc.clanCats.values()) if (cc.catRank != null && cc.catRank.toUpperCase(java.util.Locale.ROOT).contains("KIT")) kits++;
        if (kits >= 3 && Math.floorMod(seed, 3L) == 0) return Info.of(c).title + "'s nursery is busy: " + kits + " kits are growing strong.";
        String[] lines = FLAVOUR.get(c);
        return lines[(int) Math.floorMod(seed, (long) lines.length)];
    }

    private static boolean isFlavour(Clan c, String s) {
        for (String f : FLAVOUR.get(c)) if (f.equals(s)) return true;
        return s.contains("'s nursery is busy");
    }

    /** Clan players who did not see the Gathering hear a line of it from their leader, coming home. */
    private static void tellAbsent(ServerLevel ow, LifeData life, Map<Clan, List<String>> said, Set<UUID> heard) {
        for (ServerPlayer p : ow.getServer().getPlayerList().getPlayers()) {
            Clan c = Crowd.clanOf(p);
            if (c == null || heard.contains(p.getUUID()) || Journey.stage(p) != Journey.Stage.PLAYING) continue;
            Clan other = Clan.values()[(c.ordinal() + 1 + p.getRandom().nextInt(3)) % 4];
            Dialog.subtitle(p, Line.say(voice(life, c), Info.of(c).colour, "We are home from the Gathering. " + voice(life, other)
                    + " of " + Info.of(other).title + " says: " + said.get(other).get(0)));
        }
    }

    /** Why a Gathering ended: its news was told, the night ran out first, an operator stopped it, or the truce broke. */
    enum How { TOLD, NIGHT_OVER, STOPPED, BROKEN }

    /** Ends the Gathering: the Clans go home (their cats go once nobody is looking). */
    private static void end(ServerLevel ow, Grounds g, How how) {
        Run r = run;
        run = null;
        if (r == null) return;
        Moon.safePassage(ow, false);
        if (!r.called) lastNight = r.night;
        if (r.called) Moon.holdGathering(ow, false);
        if (how == How.NIGHT_OVER) {
            // nobody was there to hear it, but the Gathering was held all the same
            LifeData life = LifeData.get(ow);
            Map<Clan, List<String>> said = new EnumMap<>(Clan.class);
            for (Clan c : Clan.values()) said.put(c, report(ow, life, c, r.id));
            tellAbsent(ow, life, said, Set.of());
            ClanNews.markTold(ow, ow.getGameTime());
        }
        Crowd.fade(ow, r.crowd, 20L * 60 * 4);
        Crowd.fade(ow, r.leaders.values(), 20L * 60 * 4);
        for (UUID gid : GUIDES.values()) {
            Entity e = ow.getEntity(gid);
            if (e != null && !Lead.leading(e)) Crowd.fade(e, 20L * 60 * 4);
        }
        if (how != How.TOLD) GUIDES.clear();
    }

    /** Clan Lore says the truce was broken at the Gathering place: the leaders end the Gathering at once. */
    static void truceBroken(ServerLevel level, ServerPlayer by, BlockPos where, Moon.TruceArea area) {
        if (run == null || area == null || level.dimension() != Level.OVERWORLD) return;
        Grounds g = Grounds.current(ClanWorld.current());
        if (g == null || !(g.island() ? ISLAND : Gatherings.FOURTREES).equals(area.id())) return;
        LifeData life = LifeData.get(level);
        Clan host = Clan.values()[(int) Math.floorMod(run.id, 4L)];
        Line l = Line.say(voice(life, host), Info.of(host).colour,
                "StarClan has hidden the moon! There will be no more Gathering tonight. Go home, all of you.").speakerEntity(entity(level, run.leaders.get(host)));
        for (ServerPlayer p : witnesses(level, g)) Dialog.play(p, l);
        end(level, g, How.BROKEN);
    }

    private static Entity entity(ServerLevel ow, UUID id) {
        return id == null ? null : ow.getEntity(id);
    }
}
