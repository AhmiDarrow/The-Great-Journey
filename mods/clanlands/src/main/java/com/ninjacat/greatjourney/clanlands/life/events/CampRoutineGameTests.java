package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.events.CampRoutine.Act;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The camp routine on a small drawn camp (no world generated): its cats get varied things to do through a day,
 * patrols cross the barrier only along the way in, night sends every cat to its own nest with a guard or two at
 * the entrance, and cats someone else is steering are left alone. The clock is run by hand, a second at a time.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class CampRoutineGameTests {
    private CampRoutineGameTests() {}

    /** A camp drawn in the test's 16 by 16 floor: dens round a middle, the way in to the south. */
    private static CampSite site(GameTestHelper h) {
        BlockPos c = h.absolutePos(new BlockPos(8, 2, 8));
        CampSite s = new CampSite(Clan.THUNDER, null, c, c.getX() + 0.5, c.getZ() + 0.5);
        s.inner = 6.5;
        s.outer = 7.0;
        s.gate = Math.PI / 2;
        Object[][] dens = {
                {DenKind.LEADER, 8, 3}, {DenKind.NURSERY, 4, 4}, {DenKind.WARRIORS, 12, 4},
                {DenKind.APPRENTICES, 12, 12}, {DenKind.ELDERS, 4, 12}, {DenKind.MEDICINE, 2, 8}};
        for (Object[] d : dens) {
            BlockPos at = h.absolutePos(new BlockPos((int) d[1], 2, (int) d[2]));
            s.dens.put((DenKind) d[0], new double[] {at.getX() + 0.5, at.getZ() + 0.5, 2.5});
            int fx = (int) d[1] + Integer.signum(8 - (int) d[1]), fz = (int) d[2] + Integer.signum(8 - (int) d[2]);
            s.fronts.put((DenKind) d[0], h.absolutePos(new BlockPos(fx, 2, fz)));
        }
        s.eat = h.absolutePos(new BlockPos(10, 2, 8));
        s.sun = h.absolutePos(new BlockPos(11, 2, 9));
        s.train = h.absolutePos(new BlockPos(6, 2, 10));
        s.play = h.absolutePos(new BlockPos(5, 2, 6));
        s.way.add(h.absolutePos(new BlockPos(8, 2, 13)));
        s.way.add(h.absolutePos(new BlockPos(8, 2, 14)));
        s.way.add(h.absolutePos(new BlockPos(8, 2, 15)));
        s.guards[0] = h.absolutePos(new BlockPos(7, 2, 12));
        s.guards[1] = h.absolutePos(new BlockPos(9, 2, 12));
        List<BlockPos> spots = List.of(h.absolutePos(new BlockPos(1, 2, 15)), h.absolutePos(new BlockPos(15, 2, 15)));
        int[] n = {0};
        s.spot = (r, far) -> spots.get(n[0]++ % spots.size());
        s.leader = "Teststar";
        return s;
    }

    private static WCatEntity cat(GameTestHelper h, String name, WCatEntity.Rank rank, int gender, BlockPos home) {
        WCatEntity cat = ModEntities.WCAT.get().create(h.getLevel());
        h.assertTrue(cat != null, "a Warrior Cats Events cat can be made");
        BlockPos at = h.absolutePos(new BlockPos(8, 2, 8));
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cat.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setGender(gender);
        cat.setCustomName(Component.literal(name));
        cat.setRank(rank);
        if (rank == WCatEntity.Rank.KIT) cat.setBaby(true);
        cat.setTame(true, false);
        cat.setOwnerUUID(BookClans.standInLeader(Clan.THUNDER));
        h.getLevel().addFreshEntity(cat);
        cat.setHomePosition(home);
        cat.wanderCenter = at;
        cat.mode = WCatEntity.CatMode.WANDER;
        return cat;
    }

    /** A whole Clan: leader, deputy, medicine cat, four warriors, a queen with two kits, two apprentices, an elder. */
    private static List<WCatEntity> clan(GameTestHelper h) {
        List<WCatEntity> cats = new ArrayList<>();
        cats.add(cat(h, "Teststar", WCatEntity.Rank.WARRIOR, 0, h.absolutePos(new BlockPos(8, 2, 3))));
        cats.add(cat(h, "Ashclaw", WCatEntity.Rank.DEPUTY, 0, h.absolutePos(new BlockPos(12, 2, 4))));
        cats.add(cat(h, "Brookleaf", WCatEntity.Rank.MEDICINE, 1, h.absolutePos(new BlockPos(2, 2, 8))));
        String[] warriors = {"Cedarfang", "Dovewing", "Emberpelt", "Flintstripe"};
        for (int i = 0; i < warriors.length; i++) cats.add(cat(h, warriors[i], WCatEntity.Rank.WARRIOR, i % 2, h.absolutePos(new BlockPos(11 + i % 2, 2, 3 + i / 2))));
        cats.add(cat(h, "Gorsetail", WCatEntity.Rank.WARRIOR, 1, h.absolutePos(new BlockPos(4, 2, 4))));
        cats.add(cat(h, "Hollykit", WCatEntity.Rank.KIT, 1, h.absolutePos(new BlockPos(3, 2, 4))));
        cats.add(cat(h, "Ivykit", WCatEntity.Rank.KIT, 0, h.absolutePos(new BlockPos(4, 2, 3))));
        cats.add(cat(h, "Juniperpaw", WCatEntity.Rank.APPRENTICE, 0, h.absolutePos(new BlockPos(12, 2, 12))));
        cats.add(cat(h, "Kestrelpaw", WCatEntity.Rank.APPRENTICE, 1, h.absolutePos(new BlockPos(11, 2, 12))));
        cats.add(cat(h, "Lichenwhisker", WCatEntity.Rank.WARRIOR, 1, h.absolutePos(new BlockPos(4, 2, 12))));
        return cats;
    }

    private static CampRoutine.Now at(long day, int time, long tick) {
        return new CampRoutine.Now(day, time, tick, false, false, false, true, false);
    }

    private static void cleanUp(GameTestHelper h, CampRoutine.Camp camp, List<WCatEntity> cats) {
        CampRoutine.stop(h.getLevel(), camp);
        cats.forEach(WCatEntity::discard);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aCampsCatsKeepBusyThroughTheDay(GameTestHelper h) {
        CampRoutine.Camp camp = new CampRoutine.Camp(site(h));
        List<WCatEntity> cats = clan(h);
        RandomSource r = RandomSource.create(4242);
        Map<UUID, Set<Act>> byCat = new HashMap<>();
        Set<Act> seen = EnumSet.noneOf(Act.class);
        try {
            long tick = 100_000;
            for (int t = 0; t < 12000; t += 20, tick += 20) {
                CampRoutine.run(h.getLevel(), camp, cats, at(3, t, tick), r);
                if (t < 1000) continue;
                for (WCatEntity c : cats) {
                    CampRoutine.Job j = camp.jobs.get(c.getUUID());
                    if (j == null) continue;
                    byCat.computeIfAbsent(c.getUUID(), k -> EnumSet.noneOf(Act.class)).add(j.act);
                    seen.add(j.act);
                }
            }
            for (Act a : List.of(Act.PATROL, Act.HUNT, Act.HERBS, Act.EAT, Act.PLAY, Act.SPAR, Act.WATCH)) {
                h.assertTrue(seen.contains(a), "someone in camp should " + a + " during the day; saw " + seen);
            }
            h.assertTrue(seen.contains(Act.SHARE) || seen.contains(Act.SUN), "cats share tongues or sun themselves; saw " + seen);
            for (WCatEntity c : cats) {
                Set<Act> mine = byCat.getOrDefault(c.getUUID(), Set.of());
                h.assertTrue(mine.size() >= 3, Cats.plainName(c) + " did too little all day: " + mine);
                h.assertTrue(!mine.contains(Act.SLEEP), Cats.plainName(c) + " slept through the day");
            }
            for (WCatEntity c : cats) {
                if (c.getRank() != WCatEntity.Rank.KIT) continue;
                Set<Act> mine = byCat.getOrDefault(c.getUUID(), Set.of());
                h.assertTrue(!mine.contains(Act.PATROL) && !mine.contains(Act.HUNT), "a kit never leaves camp on patrol");
            }
            h.assertTrue(cats.stream().anyMatch(c -> c.getRank() == WCatEntity.Rank.APPRENTICE
                    && byCat.getOrDefault(c.getUUID(), Set.of()).contains(Act.SPAR)), "an apprentice trains");
        } finally {
            cleanUp(h, camp, cats);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void nightSendsEveryCatToItsNest(GameTestHelper h) {
        CampSite site = site(h);
        CampRoutine.Camp camp = new CampRoutine.Camp(site);
        List<WCatEntity> cats = clan(h);
        RandomSource r = RandomSource.create(77);
        try {
            long tick = 500_000;
            for (int t = 16000; t < 18000; t += 20, tick += 20) CampRoutine.run(h.getLevel(), camp, cats, at(4, t, tick), r);
            int guards = 0;
            for (WCatEntity c : cats) {
                CampRoutine.Job j = camp.jobs.get(c.getUUID());
                h.assertTrue(j != null && (j.act == Act.SLEEP || j.act == Act.GUARD), Cats.plainName(c) + " is not abed at midnight: "
                        + (j == null ? "no job" : j.act));
                if (j.act == Act.GUARD) {
                    guards++;
                    h.assertTrue(c.getRank() != WCatEntity.Rank.KIT && c.getRank() != WCatEntity.Rank.APPRENTICE, "only warriors stand guard");
                    double d = Math.min(c.position().distanceTo(Vec3.atBottomCenterOf(site.guards[0])), c.position().distanceTo(Vec3.atBottomCenterOf(site.guards[1])));
                    h.assertTrue(d < 3, Cats.plainName(c) + " guards from " + d + " blocks off the way in");
                    continue;
                }
                double d = c.position().distanceTo(Vec3.atBottomCenterOf(c.getHomePosition()));
                h.assertTrue(d < 2, Cats.plainName(c) + " sleeps " + d + " blocks from its nest");
                h.assertTrue(c.isResting(), Cats.plainName(c) + " is at its nest but not asleep");
                h.assertTrue(c.mode == WCatEntity.CatMode.WANDER, Cats.plainName(c) + " is left out of WANDER mode overnight");
            }
            h.assertTrue(guards >= 1 && guards <= 2, "one or two guards at the way in, got " + guards);
            // and at dawn they wake and go about their day
            for (int t = 23000; t < 24000 + 2600; t += 20, tick += 20) CampRoutine.run(h.getLevel(), camp, cats, at(5, t % 24000, tick), r);
            for (WCatEntity c : cats) {
                CampRoutine.Job j = camp.jobs.get(c.getUUID());
                h.assertTrue(j == null || (j.act != Act.SLEEP && j.act != Act.GUARD), Cats.plainName(c) + " is still abed by mid-morning");
            }
        } finally {
            cleanUp(h, camp, cats);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void busyCatsAndCatsInAnEventAreLeftAlone(GameTestHelper h) {
        CampRoutine.Camp camp = new CampRoutine.Camp(site(h));
        List<WCatEntity> cats = clan(h);
        WCatEntity busy = cats.get(3), fighting = cats.get(4);
        busy.addTag(BookClans.BUSY_TAG);
        ClanEvents.activeEntities.add(fighting.getUUID());
        Vec3 busyAt = busy.position(), fightingAt = fighting.position();
        BlockPos busyCentre = busy.wanderCenter, fightingCentre = fighting.wanderCenter;
        RandomSource r = RandomSource.create(9);
        try {
            long tick = 900_000;
            for (int t = 2000; t < 8000; t += 20, tick += 20) {
                CampRoutine.run(h.getLevel(), camp, cats, at(6, t, tick), r);
                for (WCatEntity c : List.of(busy, fighting)) {
                    CampRoutine.Job given = camp.jobs.get(c.getUUID());
                    h.assertTrue(given == null, Cats.plainName(c) + " was given a job: " + (given == null ? "" : given.act));
                    h.assertTrue(!c.getTags().contains(CampRoutine.TAG) && c.mode == WCatEntity.CatMode.WANDER, Cats.plainName(c) + "'s mode was changed");
                }
            }
            h.assertTrue(busy.position().equals(busyAt) && fighting.position().equals(fightingAt), "the busy cats were moved");
            h.assertTrue(busyCentre.equals(busy.wanderCenter) && fightingCentre.equals(fighting.wanderCenter), "the busy cats' wander centre was changed");
            h.assertTrue(cats.stream().filter(c -> c != busy && c != fighting).allMatch(c -> camp.last.containsKey(c.getUUID())), "the other cats kept their routine");
            // the moment a cat is wanted elsewhere, the routine lets it go
            WCatEntity walker = cats.get(5);
            CampRoutine.Job j = camp.jobs.get(walker.getUUID());
            h.assertTrue(j != null, "a free cat has something to do");
            walker.addTag(BookClans.BUSY_TAG);
            CampRoutine.run(h.getLevel(), camp, cats, at(6, 8000, tick), r);
            h.assertTrue(!camp.jobs.containsKey(walker.getUUID()) && !walker.getTags().contains(CampRoutine.TAG), "a cat that turns busy is let go at once");
        } finally {
            ClanEvents.activeEntities.remove(fighting.getUUID());
            cleanUp(h, camp, cats);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void outingsCrossTheBarrierOnlyByTheWayIn(GameTestHelper h) {
        CampSite site = site(h);
        List<WCatEntity> cats = clan(h);
        RandomSource r = RandomSource.create(3);
        try {
            WCatEntity cat = cats.get(1);
            for (BlockPos from : List.of(h.absolutePos(new BlockPos(4, 2, 5)), h.absolutePos(new BlockPos(12, 2, 3)), h.absolutePos(new BlockPos(8, 2, 8)))) {
                cat.moveTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
                for (Act act : List.of(Act.PATROL, Act.HUNT, Act.HERBS)) {
                    List<CampRoutine.Step> steps = CampRoutine.outing(site, cat, List.of(site.spot.apply(r, true), site.spot.apply(r, true)),
                            act, 1, r, site.eat, CampRoutine.Pose.STROLL, 10);
                    checkCrossings(h, site, cat.position(), steps, act + " from " + from);
                    h.assertTrue(steps.stream().anyMatch(s -> s.kind() == CampRoutine.Step.Kind.PREY) == (act == Act.HUNT), "only hunters bring prey home");
                    h.assertTrue(!site.outside(steps.get(steps.size() - 1).at()), act + " ends back in camp");
                }
            }
            // and a cat found outside comes home the same way
            BlockPos far = h.absolutePos(new BlockPos(2, 2, 15));
            cat.moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
            checkCrossings(h, site, cat.position(), CampRoutine.route(site, cat.getX(), cat.getZ(), h.absolutePos(new BlockPos(5, 2, 5))), "home");
        } finally {
            cats.forEach(WCatEntity::discard);
        }
        h.succeed();
    }

    /**
     * The old forest's camps in the Campaign: each has a way in that runs from inside its barrier to clear of it
     * (up the ravine's cut, over the island's ford to dry land), and their routine runs until the Clans leave on the
     * Great Journey; the lake's camps take over once the Clans settle there.
     */
    @GameTest(template = "empty")
    public static void forestCampsHaveAWayInAndLiveUntilTheJourney(GameTestHelper h) {
        h.assertTrue(CampRoutine.settled(true, 1) && CampRoutine.settled(true, 2), "the forest camps live through acts 1 and 2");
        h.assertTrue(!CampRoutine.settled(true, 3), "no camp runs while the Clans are away on the Great Journey");
        h.assertTrue(CampRoutine.settled(false, 4) && CampRoutine.settled(false, 0), "the lake camps run once the Clans live there (and in Free Mode)");
        for (long seed : new long[] {4242L, 777001L, 20261011L, -42L}) {
            com.ninjacat.greatjourney.clanlands.world.ClanWorld w = com.ninjacat.greatjourney.clanlands.world.ClanWorld.layoutOnly(seed);
            for (Clan c : Clan.values()) {
                for (boolean forest : new boolean[] {true, false}) {
                    var l = forest ? com.ninjacat.greatjourney.clanlands.life.Homeland.forestCamp(w, c) : w.lake.camp(c);
                    var plan = com.ninjacat.greatjourney.clanlands.landmark.CampPlan.of(w, l);
                    String what = (forest ? "forest " : "lake ") + c + " camp, seed " + seed;
                    h.assertTrue(plan != null, what + " has no plan");
                    List<double[]> line = CampSite.line(w, plan);
                    if (line.isEmpty()) {
                        // only an island whose ford reaches no bank keeps its cats home (seed 777001's forest RiverClan)
                        h.assertTrue(plan.style == com.ninjacat.greatjourney.clanlands.landmark.CampPlan.Style.ISLAND, what + " has no way in");
                        continue;
                    }
                    double[] bar = plan.barrier();
                    double first = Math.hypot(line.get(0)[0] - plan.cx, line.get(0)[1] - plan.cz);
                    double[] end = line.get(line.size() - 1);
                    double last = Math.hypot(end[0] - plan.cx, end[1] - plan.cz);
                    h.assertTrue(first > 2 && first < bar[0], what + ": the way in starts inside the barrier, at " + first);
                    h.assertTrue(last > bar[1] + 2, what + ": the way in ends clear of the barrier, at " + last + " (barrier to " + bar[1] + ")");
                    for (int i = 0; i < line.size(); i++) {
                        double[] q = line.get(i);
                        double d = Math.hypot(q[0] - plan.cx, q[1] - plan.cz);
                        if (d >= bar[0] && d <= bar[1]) h.assertTrue(plan.inGate(q[0], q[1]), what + ": a point in the barrier lies off the way in");
                        if (i > 0) h.assertTrue(Math.hypot(q[0] - line.get(i - 1)[0], q[1] - line.get(i - 1)[1]) <= 5.5, what + ": points too far apart");
                    }
                    if (plan.style == com.ninjacat.greatjourney.clanlands.landmark.CampPlan.Style.ISLAND) {
                        h.assertTrue(w.sample(end[0], end[1]).height() >= 62.5, what + ": the ford's end is not on the bank: " + debugFord(w, plan));
                    }
                }
            }
        }
        h.succeed();
    }

    private static String debugFord(com.ninjacat.greatjourney.clanlands.world.ClanWorld w, com.ninjacat.greatjourney.clanlands.landmark.CampPlan plan) {
        StringBuilder sb = new StringBuilder("wallOut " + plan.wallOut + ":");
        for (double t = plan.wallOut; t < plan.wallOut + 40; t += 1) {
            var q = w.sample(plan.cx + Math.cos(plan.gate) * t, plan.cz + Math.sin(plan.gate) * t);
            sb.append(' ').append((int) t).append('=').append(String.format(java.util.Locale.ROOT, "%.1f", q.height())).append(q.zone() == null ? "" : q.zone().name().substring(0, 2));
        }
        return sb.toString();
    }

    /** Every walk from one point to the next that changes sides goes between neighbouring points of the way in. */
    private static void checkCrossings(GameTestHelper h, CampSite site, Vec3 start, List<CampRoutine.Step> steps, String what) {
        double px = start.x, pz = start.z;
        int prevWay = -1;
        boolean crossedOut = false;
        for (CampRoutine.Step s : steps) {
            if (s.kind() != CampRoutine.Step.Kind.GO) continue;
            BlockPos b = s.at();
            int way = site.way.indexOf(b);
            boolean sideChange = site.outside(px, pz) != site.outside(b);
            if (sideChange) {
                h.assertTrue(way >= 0 && prevWay >= 0 && Math.abs(way - prevWay) == 1, what + ": crosses the barrier off the way in, to " + b);
                crossedOut = true;
            }
            px = b.getX() + 0.5;
            pz = b.getZ() + 0.5;
            prevWay = way;
        }
        h.assertTrue(crossedOut, what + ": never went through the way in");
    }
}
