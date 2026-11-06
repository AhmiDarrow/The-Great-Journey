package com.ninjacat.greatjourney.clanlands.life.gathering;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.ChunkWait;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.Lead;
import com.ninjacat.greatjourney.clanlands.life.Starts;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The medicine cats' meeting at the half moon. At dusk on a half-moon night the four Clans' medicine cats travel to
 * the Moonpool (or, while a Campaign's Clans live in the old forest, to Mothermouth under the Highstones, where the
 * Moonstone lies deep in the hill: they crouch at the cave's mouth, a liberty) to share tongues with StarClan. Every Clan player hears that their medicine cat has set off; a player
 * who is a medicine cat or a medicine cat's apprentice (Warrior Cats Events' rank) is asked along, and their own
 * medicine cat walks them there. At the pool, anyone who comes sees the medicine cats crouched at the water's edge
 * and touching their noses to it; a medicine cat player lies down with them and dreams (starlit shapes, no book
 * cats). The quests have no medicine path yet, so this is a scene, not a step of one. It runs wherever the
 * Gatherings do; a player the Campaign has busy is not asked. The state lives only while the server runs.
 */
final class HalfMoon {
    private HalfMoon() {}

    static final long DUSK = 12000, LATE = 22000;
    /** While a medicine cat who said Go is still on the way, the night is held here, well before dawn. */
    static final long HOLD_AT = 21000;
    /** The medicine cats appear once a player is this close; they are seen this close. */
    private static final double SPAWN_NEAR = 72, SEE = 36;
    private static final long CALLED_TICKS = 12000;
    private static final String ASKED = "halfmoon.asked";

    private static Run run;
    private static long lastNight = Long.MIN_VALUE;
    private static final Map<UUID, UUID> GUIDES = new HashMap<>();

    static final class Run {
        final long id, night, startedAt;
        final boolean called;
        final Set<UUID> going = new HashSet<>(), arrived = new HashSet<>(), seen = new HashSet<>();
        final Map<Clan, String> healers = new EnumMap<>(Clan.class);
        final Map<Clan, UUID> cats = new EnumMap<>(Clan.class);
        boolean spawning, spawned;
        /** How long the night has been held for medicine cats still on the way. */
        long held;

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

    private static String shrineName() {
        Grounds.Shrine sh = Grounds.shrine(ClanWorld.current());
        return sh == null ? "their meeting place" : sh.name();
    }

    static String status(ServerLevel ow) {
        if (run == null) return "No half-moon meeting. The moon is " + Moon.phase(ow).words + ".";
        return (run.called ? "A called half-moon meeting" : "Tonight's half-moon meeting") + ": " + run.going.size() + " going, "
                + run.arrived.size() + " there, the medicine cats " + (run.spawned ? "at " + shrineName() : "on their way") + ".";
    }

    /** Once a second while the moon's meetings run (see {@link Gatherings#running}). */
    static void tick(MinecraftServer server) {
        ServerLevel ow = server.overworld();
        Grounds.Shrine sh = Grounds.shrine(ClanWorld.current());
        if (sh == null) return;
        long t = Moon.timeOfDay(ow), now = ow.getGameTime();
        if (run != null && !(run.called ? now - run.startedAt < CALLED_TICKS : Moon.night(ow) == run.night && Moon.isHalfMoon(ow) && t < Moon.dayStart())) end(ow);
        if (run == null) {
            long night = Moon.night(ow);
            if (!Moon.isHalfMoon(ow) || night == lastNight || t < DUSK || t >= LATE) return;
            begin(ow, night + 1, false, night);
        }
        Run r = run;
        if (!r.called && Gatherings.anyOnTheWay(server, r.going, r.arrived) && r.held < Gatherings.HOLD_MAX_TICKS && t > HOLD_AT) {
            // the other medicine cats wait at the pool for everyone who said Go: the half moon stays up until then
            r.held += 20;
            Gatherings.holdNight(server, HOLD_AT);
        }
        for (ServerPlayer p : ow.players()) {
            if (Journey.stage(p) != Journey.Stage.PLAYING || p.isSpectator()) continue;
            Clan c = Crowd.clanOf(p);
            if (c != null && Journey.stamped(p, ASKED) != r.id && Lead.of(p) == null && !Gatherings.storyBusy(p)) ask(p, c, r);
            if (c != null && Crowd.near(p, sh.x(), sh.z(), sh.ring() + 6)) r.arrived.add(p.getUUID());
        }
        if (!r.spawned && !r.spawning && anyNear(ow, sh.x(), sh.z(), SPAWN_NEAR)) {
            r.spawning = true;
            ChunkWait.whenLoaded(ow, sh.x(), sh.z(), 20, () -> spawn(ow, sh, r));
        }
        if (r.spawned) {
            for (ServerPlayer p : ow.players()) {
                if (!p.isSpectator() && Crowd.near(p, sh.x(), sh.z(), SEE) && !Gatherings.storyChapter(p) && r.seen.add(p.getUUID())) {
                    Dialog.play(p, scene(ow, p, r, sh));
                }
            }
        }
    }

    private static void begin(ServerLevel ow, long id, boolean called, long night) {
        Run r = new Run(id, called, night, ow.getGameTime());
        RandomSource rand = RandomSource.create(id * 0x632BE59BD9B4E019L);
        Set<String> taken = new HashSet<>();
        for (Clan c : Clan.values()) {
            List<String> n = Crowd.names(ow, c, "MEDICINE", 1, rand, taken);
            r.healers.put(c, n.isEmpty() ? Info.of(c).title + "'s medicine cat" : n.get(0));
        }
        run = r;
    }

    /** An operator calls the half-moon meeting now, whatever the moon (for testing). */
    static boolean call(ServerLevel ow) {
        if (Grounds.shrine(ClanWorld.current()) == null) return false;
        if (run != null) end(ow);
        begin(ow, -ow.getGameTime() - 1, true, Moon.night(ow));
        return true;
    }

    static boolean stop(ServerLevel ow) {
        boolean was = run != null;
        if (was) end(ow);
        return was;
    }

    private static boolean medicine(ServerPlayer p) {
        ClanData.ClanPlayerRank rank = Crowd.rank(p);
        return rank == ClanData.ClanPlayerRank.MEDICINE || rank == ClanData.ClanPlayerRank.MEDICINEAPP;
    }

    /** The Clan hears its medicine cat has gone; a medicine cat player is asked along. */
    private static void ask(ServerPlayer p, Clan c, Run r) {
        Journey.stamp(p, ASKED, r.id);
        Info info = Info.of(c);
        String healer = r.healers.get(c);
        if (!medicine(p)) {
            Dialog.subtitle(p, Line.narrate(healer + " has set off for " + shrineName() + " beneath the half moon, to share tongues with StarClan."));
            return;
        }
        boolean apprentice = Crowd.rank(p) == ClanData.ClanPlayerRank.MEDICINEAPP;
        Dialog.play(p, Line.say(healer, info.colour, apprentice
                        ? "It is the half moon, " + Joining.catName(p) + ". Tonight the medicine cats share tongues with StarClan at " + shrineName() + ". Will you walk with me?"
                        : "It is the half moon. The other medicine cats will be waiting at " + shrineName() + ". Shall we go together?")
                .withChoices(Choice.of("Go", "greatjourney halfmoon go"), new Choice("Not tonight", "greatjourney halfmoon stay", 0xAAAAAA)));
    }

    /** "Go": the player's own medicine cat walks them up into the hills to the Moonpool, or out to the Highstones. */
    static boolean go(ServerPlayer p) {
        Run r = run;
        Clan c = Crowd.clanOf(p);
        Grounds.Shrine sh = Grounds.shrine(ClanWorld.current());
        if (r == null || c == null || sh == null || !medicine(p)) {
            Dialog.subtitle(p, Line.narrate("The medicine cats are not meeting tonight."));
            return false;
        }
        r.going.add(p.getUUID());
        if (Crowd.near(p, sh.x(), sh.z(), sh.ring() + 6)) return true;
        if (p.level().dimension() != Level.OVERWORLD) {
            Dialog.subtitle(p, Line.narrate(sh.pool() ? "The Moonpool is in the hills above the lake. Come back to the Clans' world first."
                    : "Mothermouth is under the Highstones, beyond the moor. Come back to the Clans' world first."));
            return false;
        }
        Info info = Info.of(c);
        WCatEntity guide = r.cats.containsKey(c) ? null : Crowd.guide(p, c, BookClans.Role.MEDICINE, r.healers.get(c));
        List<Lead.Stop> stops = new ArrayList<>();
        for (double[] pt : Starts.wayShore(ClanWorld.current(), p.getX(), p.getZ(), sh.approach()[0], sh.approach()[1])) stops.add(new Lead.Stop(pt[0], pt[1], 12, null));
        double a = sh.angles()[c.ordinal()];
        if (!sh.pool()) stops.add(new Lead.Stop(sh.approach()[0], sh.approach()[1], 6, null));
        stops.add(new Lead.Stop(sh.x() + Math.cos(a) * sh.ring(), sh.z() + Math.sin(a) * sh.ring(), 6, null));
        Lead lead = guide == null ? null : Lead.start(p, guide, stops, (l, how) -> walked(l, how, c, sh));
        if (lead == null) {
            // no cat can walk them (their medicine cat is already at the pool, or nothing could be made): they go up alone
            if (guide != null) Crowd.dismiss(p.serverLevel(), guide.getUUID());
            Crowd.carry(p, sh.approach()[0], sh.approach()[1], q -> Dialog.subtitle(q, Line.narrate(sh.pool()
                    ? "You climb the last of the rocky path and look down into the hollow. The Moonpool shines below."
                    : "You climb the last of the stony slope. Ahead, under the Highstones, gapes Mothermouth: a dark square opening in the rock.")));
            return true;
        }
        GUIDES.put(p.getUUID(), guide.getUUID());
        String name = Crowd.plain(guide);
        lead.whenWaiting(l -> Dialog.subtitle(l.player(), Line.say(name, info.colour, "It's a long climb. Rest if you must, but don't lose me.").speakerEntity(l.cat())));
        Dialog.play(p, Line.say(name, info.colour, sh.pool() ? "Come, then. We follow the stream up into the hills."
                : "Come, then. It's a long way: across the moor and up to the Highstones.").speakerEntity(guide));
        return true;
    }

    static boolean stay(ServerPlayer p) {
        Clan c = Crowd.clanOf(p);
        if (run == null || c == null) return false;
        run.going.remove(p.getUUID());                      // changed their mind: the others do not wait for them
        Dialog.play(p, Line.say(run.healers.get(c), Info.of(c).colour, "Then keep the herbs dry while I'm gone. There will be other half moons."));
        return true;
    }

    /** There the medicine cat crouches with the others; a walk cut short sends it on alone. */
    private static void walked(Lead l, Lead.End how, Clan c, Grounds.Shrine sh) {
        ServerPlayer p = l.player();
        WCatEntity cat = l.cat();
        Run r = run;
        if (how == Lead.End.DONE && r != null && cat != null && cat.isAlive() && !r.cats.containsKey(c)) {
            r.arrived.add(p.getUUID());
            r.cats.put(c, cat.getUUID());
            Crowd.settle(cat, sh.x(), sh.z());
            return;
        }
        GUIDES.remove(p.getUUID());
        if (cat != null) Crowd.dismiss(p.serverLevel(), cat.getUUID());
        if (r != null && r.spawned && !r.cats.containsKey(c) && p.level() instanceof ServerLevel sl) place(sl, sh, r, c);
    }

    /** The medicine cats on the pool's stone ledge (clear of the waterfall), or in an arc at the cave's mouth; a guide on its way fills its own place. */
    private static void spawn(ServerLevel ow, Grounds.Shrine sh, Run r) {
        if (run != r) return;
        for (Clan c : Clan.values()) {
            boolean coming = false;
            for (Map.Entry<UUID, UUID> e : GUIDES.entrySet()) {
                ServerPlayer p = ow.getServer().getPlayerList().getPlayer(e.getKey());
                coming |= p != null && Crowd.clanOf(p) == c && ow.getEntity(e.getValue()) != null;
            }
            if (!coming && !r.cats.containsKey(c)) place(ow, sh, r, c);
        }
        r.spawned = true;
    }

    private static void place(ServerLevel ow, Grounds.Shrine sh, Run r, Clan c) {
        double a = sh.angles()[c.ordinal()];
        WCatEntity cat = Crowd.scene(ow, c, r.healers.get(c), BookClans.Role.MEDICINE, sh.x() + Math.cos(a) * sh.ring(), sh.z() + Math.sin(a) * sh.ring(), sh.x(), sh.z());
        if (cat != null) r.cats.put(c, cat.getUUID());
    }

    /** What a player sees there: the medicine cats sharing tongues with StarClan, and, for a medicine cat, a dream. */
    private static List<Line> scene(ServerLevel ow, ServerPlayer p, Run r, Grounds.Shrine sh) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate(sh.pool()
                ? "Starlight glitters on the Moonpool. Around its edge the medicine cats of the four Clans crouch on the smooth stone, their pelts silver under the half moon."
                : "Under the Highstones, the medicine cats of the four Clans crouch at Mothermouth. Cold air breathes out of the dark: far below, the Moonstone waits for the moon."));
        Clan[] order = Clan.values();
        int first = (int) Math.floorMod(r.id, 4L);
        String[] words = {"StarClan, we have come to share tongues with you. Light our paths.",
                "The herbs are growing well this moon. Let us thank StarClan for them.",
                "Greetings, friends. May StarClan share their wisdom with us tonight."};
        for (int k = 0; k < 2; k++) {
            Clan c = order[(first + k * 2) % 4];
            lines.add(Line.say(r.healers.get(c), Info.of(c).colour, words[(int) Math.floorMod(r.id + k, 3L)]).speakerEntity(entity(ow, r.cats.get(c))));
        }
        lines.add(Line.narrate(sh.pool()
                ? "One by one they stretch out beside the water and touch their noses to its surface. Their eyes close. They will dream with StarClan until the moon sets."
                : "One by one they lie down on the cold stone at the cave's mouth and close their eyes, so close to the Moonstone that StarClan can hear them. They will dream until the moon sets."));
        Clan own = Crowd.clanOf(p);
        if (own != null && medicine(p)) {
            lines.add(Line.say(r.healers.get(own), Info.of(own).colour,
                    sh.pool() ? "Lie down beside me, and touch your nose to the water. If StarClan wishes it, they will share tongues with you too."
                            : "Lie down beside me, here at the mouth of the cave. The Moonstone is close. If StarClan wishes it, they will share tongues with you too.").speakerEntity(entity(ow, r.cats.get(own))));
            lines.add(Line.narrate((sh.pool() ? "The water is as cold as ice. " : "The stone is as cold as ice. ")
                    + "You close your eyes, and in your dream starlit shapes move at the edge of sight, warm and kind, murmuring words you cannot quite catch."));
            lines.add(Line.narrate("When you wake, the half moon is sinking, and you feel strangely at peace."));
        } else {
            lines.add(Line.narrate("None of them looks up. This is a place for medicine cats: only they may share tongues with StarClan here."));
        }
        return lines;
    }

    private static void end(ServerLevel ow) {
        Run r = run;
        run = null;
        if (r == null) return;
        if (!r.called) lastNight = r.night;
        Crowd.fade(ow, r.cats.values(), 20L * 60 * 3);
        for (UUID gid : GUIDES.values()) {
            Entity e = ow.getEntity(gid);
            if (e != null && !Lead.leading(e)) Crowd.fade(e, 20L * 60 * 3);
        }
        GUIDES.clear();
    }

    private static boolean anyNear(ServerLevel ow, double x, double z, double r) {
        for (ServerPlayer p : ow.players()) if (!p.isSpectator() && Crowd.near(p, x, z, r)) return true;
        return false;
    }

    private static Entity entity(ServerLevel ow, UUID id) {
        return id == null ? null : ow.getEntity(id);
    }
}
