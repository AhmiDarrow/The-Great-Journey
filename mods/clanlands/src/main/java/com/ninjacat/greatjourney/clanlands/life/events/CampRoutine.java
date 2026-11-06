package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.campaign.CampaignData;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.block.entity.FreshkillPileBlockEntity;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.MossBallEntity;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity.CatMode;

/**
 * The camp's day, so a Clan looks alive while a player is near. At dawn and in the afternoon two or three cats go out
 * on patrol through the camp's way in and come back the same way, hunters carrying prey that Warrior Cats Events'
 * own goal drops on the fresh-kill pile; the medicine cat goes out for herbs; cats eat at the pile, share tongues in
 * pairs, sun themselves, nap; kits play by the nursery (with a moss ball) while a queen sits watching; apprentices
 * spar with a warrior; elders rest by their den. At night every cat goes to its own nest and sleeps, and a guard or
 * two sit inside the way in.
 * <p>
 * Each cat has one activity at a time with its own end. The routine moves a cat only with Warrior Cats Events' own
 * wandering, patrolling and homing switched off (its FOLLOW mode, with no owner to follow); it lingers in WANDER mode
 * with WCE's own poses (chilling, resting) or in SIT mode. A cat leaves and enters the camp only along the way in.
 * It leaves alone a cat that is {@link BookClans#BUSY_TAG busy}, in a Clan event, on WCE's own patrol or called by
 * a leader, a player's own cat, and one a player has just given an order. Camps without a player within
 * {@link EventsConfig#ROUTINE_RANGE} blocks are not run; nothing here loads a chunk.
 */
public final class CampRoutine {
    private CampRoutine() {}

    /** On a cat while the routine holds it out of WANDER mode; saved, so a cat left so by a restart is given back. */
    public static final String TAG = "greatjourney_routine";
    /** On the moss ball the routine sets out for the kits. */
    static final String BALL_TAG = "greatjourney_routine_ball";

    /** What a cat is doing. */
    enum Act { STROLL, CHILL, NAP, EAT, SHARE, SUN, PLAY, SPAR, WATCH, TEND, PATROL, HUNT, HERBS, HOME, SLEEP, GUARD }

    /** A cat's part in the camp's day, from its rank and the den it sleeps in. */
    enum Part { LEADER, DEPUTY, MEDICINE, WARRIOR, QUEEN, ELDER, APPRENTICE, KIT }

    /** How a cat spends a stay: strolling round a spot (WCE wander), chilling, resting, sitting, sparring, on guard. */
    enum Pose { STROLL, CHILL, REST, SIT, SPAR, GUARD }

    /** The moment the camp is run at: the day's number and time, the game clock, and the sky. */
    record Now(long day, int time, long tick, boolean wet, boolean storm, boolean leafBare, boolean greenleaf, boolean fog) {
        static Now of(ServerLevel ow) {
            long t = ow.getDayTime();
            Seasons.Season s = Seasons.season(ow);
            return new Now((t + 1000) / 24000, (int) (t % 24000), ow.getGameTime(), ow.isRaining(), ow.isThundering(),
                    s == Seasons.Season.LEAF_BARE, s == Seasons.Season.GREENLEAF, Weather.foggy(ow));
        }
    }

    /** One part of an activity: walk to a spot, stay there a while, take prey from the hunt, eat from the pile. */
    record Step(Kind kind, BlockPos at, Pose pose, int seconds, boolean night) {
        enum Kind { GO, STAY, PREY, EAT }
    }

    static Step go(BlockPos at) {
        return new Step(Step.Kind.GO, at, null, 0, false);
    }

    static Step stay(Pose pose, BlockPos at, int seconds) {
        return new Step(Step.Kind.STAY, at, pose, seconds, false);
    }

    /** A stay that lasts until this cat's own dawn. */
    static Step night(Pose pose, BlockPos at) {
        return new Step(Step.Kind.STAY, at, pose, 0, true);
    }

    static final class Job {
        final Act act;
        final List<Step> steps;
        int i = -1;
        long until, bestAt, started;
        double best;
        UUID partner;
        /** The mode the routine last gave the cat: anything else means someone else has given it an order. */
        CatMode expect;
        boolean swat;
        BlockPos paceTo;
        long paceAt, paceUntil;

        Job(Act act, List<Step> steps) {
            this.act = act;
            this.steps = steps;
        }

        boolean done() {
            return i >= steps.size();
        }

        Step current() {
            return i >= 0 && i < steps.size() ? steps.get(i) : null;
        }

        /** A stay the cat can be called away from (to a patrol, to a friend). */
        boolean idle(CampSite site, WCatEntity cat) {
            Step s = current();
            return (s == null || s.kind() == Step.Kind.STAY) && partner == null && !site.outside(cat.getX(), cat.getZ())
                    && switch (act) { case STROLL, CHILL, NAP, SUN, WATCH, TEND, EAT -> true; default -> false; };
        }
    }

    /** One camp's routine: each cat's job, today's outings, the moss ball. Per camp, never per player. */
    static final class Camp {
        CampSite site;
        long builtAt;
        final Map<UUID, Job> jobs = new HashMap<>();
        final Map<UUID, Act> last = new HashMap<>();
        final Map<UUID, Integer> outings = new HashMap<>();
        final Map<UUID, Long> handsOff = new HashMap<>();
        final Set<String> done = new HashSet<>();
        final Set<UUID> guards = new HashSet<>();
        Set<UUID> moved = Set.of();
        long day = Long.MIN_VALUE, guardsDay = Long.MIN_VALUE, ballIdle;
        UUID ball;

        Camp(CampSite site) {
            this.site = site;
        }
    }

    private static final Map<Clan, Camp> CAMPS = new EnumMap<>(Clan.class);
    private static MinecraftServer server;

    // the world's side: which camps run, once a second -------------------------------------------------------------

    /** Once a second (from {@link ClanEvents}): runs every camp with a player near, and lets the others go. */
    static void everySecond(ServerLevel ow) {
        if (server != ow.getServer()) {
            CAMPS.clear();
            server = ow.getServer();
        }
        // the camps where the Clans live now (Homeland): the old forest's in the Campaign's first acts, else the lake's
        boolean on = EventsConfig.CAMP_ROUTINE.get() && (!Homeland.forest() || settled(true, CampaignData.get(ow).act()));
        double range = EventsConfig.ROUTINE_RANGE.get();
        LifeData life = LifeData.get(ow);
        for (Clan c : Clan.values()) {
            Camp camp = CAMPS.get(c);
            Landmark l = Cats.camp(c);
            boolean active = on && l != null && life.clanId(c) != null && playerNear(ow, l.x(), l.z(), range)
                    && Cats.campLoaded(ow, c) && Cats.campCatsLoaded(ow, c)
                    && !(ClanEvents.activeKind != null && ClanEvents.activeClan == c);    // the Clan's big event steers its cats
            if (camp != null && (!active || !camp.site.landmark.equals(l))) {
                stop(ow, camp);
                CAMPS.remove(c);
                camp = null;
            }
            if (!active) continue;
            if (camp == null || ow.getGameTime() - camp.builtAt > 20 * 300) {
                CampSite site = CampSite.of(ow, c, l);
                if (site == null) continue;
                if (camp == null) {
                    camp = new Camp(site);
                    clearBalls(ow, site);
                    CAMPS.put(c, camp);
                }
                camp.site = site;
                camp.builtAt = ow.getGameTime();
            }
            camp.site.leader = life.leader(c);
            camp.moved = WeatherData.get(ow).moved.keySet();
            run(ow, camp, Cats.atCamp(ow, c), Now.of(ow), ow.getRandom());
        }
    }

    /**
     * Whether the Clans have camps to live in: always at the lake; in the old forest until the Clans leave it on the
     * Great Journey (act 3, when the forest's cats walk away), and none until the lake is founded.
     */
    static boolean settled(boolean forest, int act) {
        return !forest || act < 3;
    }

    private static boolean playerNear(ServerLevel ow, double x, double z, double range) {
        for (ServerPlayer p : ow.players()) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= range * range) return true;
        }
        return false;
    }

    /** Whether a player is close enough to see a cat jump from one spot to another. */
    private static boolean seen(ServerLevel level, double x, double y, double z) {
        for (ServerPlayer p : level.players()) if (p.distanceToSqr(x, y, z) < 24 * 24) return true;
        return false;
    }

    /** Lets every cat of a camp go back to Warrior Cats Events' own ways, and takes the moss ball in. */
    static void stop(ServerLevel level, Camp camp) {
        for (UUID id : camp.jobs.keySet()) if (level.getEntity(id) instanceof WCatEntity cat) release(cat, camp.site);
        camp.jobs.clear();
        if (camp.ball != null && level.getEntity(camp.ball) != null) level.getEntity(camp.ball).discard();
        camp.ball = null;
    }

    /** The server stops: every camp's cats go back to their own ways before they are saved. */
    static void stopAll(MinecraftServer s) {
        for (Camp camp : CAMPS.values()) stop(s.overworld(), camp);
        CAMPS.clear();
        server = null;
    }

    /** A cat saved mid-activity (a crash, or the routine turned off since) loads as a wandering camp cat again. */
    static void onJoin(net.minecraft.world.entity.Entity e) {
        if (!(e instanceof WCatEntity cat) || !cat.getTags().contains(TAG)) return;
        for (Camp camp : CAMPS.values()) if (camp.jobs.containsKey(cat.getUUID())) return;
        cat.removeTag(TAG);
        cat.setAttacking(false);
        if (cat.mode != CatMode.WANDER) {
            cat.mode = CatMode.WANDER;
            cat.setOrderedToSit(false);
        }
    }

    private static void clearBalls(ServerLevel level, CampSite site) {
        double r = site.outer + 12;
        for (MossBallEntity b : level.getEntitiesOfClass(MossBallEntity.class, new AABB(site.cx - r, level.getMinBuildHeight(), site.cz - r,
                site.cx + r, level.getMaxBuildHeight(), site.cz + r), b -> b.getTags().contains(BALL_TAG))) b.discard();
    }

    // the routine ---------------------------------------------------------------------------------------------------

    /** One second of a camp's day for the Clan's cats at camp ({@code all}). */
    static void run(ServerLevel level, Camp camp, List<WCatEntity> all, Now now, RandomSource r) {
        CampSite site = camp.site;
        if (camp.day != now.day()) {
            camp.day = now.day();
            camp.done.clear();
            camp.outings.clear();
        }
        camp.handsOff.values().removeIf(t -> t < now.tick());
        Set<UUID> here = new HashSet<>();
        List<WCatEntity> cats = new ArrayList<>();
        for (WCatEntity cat : all) {
            here.add(cat.getUUID());
            if (free(camp, cat)) cats.add(cat);
            else letGo(camp, cat);
        }
        // jobs of cats gone from camp (to StarClan, or out of the loaded land) are forgotten
        camp.jobs.keySet().removeIf(id -> {
            if (here.contains(id)) return false;
            if (level.getEntity(id) instanceof WCatEntity cat) release(cat, site);
            return true;
        });
        guardsFor(camp, cats, now);
        outings(level, camp, cats, now, r);
        for (WCatEntity cat : cats) {
            Job job = camp.jobs.get(cat.getUUID());
            if (job != null && job.expect != null && cat.mode != job.expect) {
                // a player (or another mod) gave the cat an order: leave it be for ten minutes
                camp.jobs.remove(cat.getUUID());
                cat.removeTag(TAG);
                camp.handsOff.put(cat.getUUID(), now.tick() + 20L * 600);
                continue;
            }
            if (cat.getTarget() != null && cat.getTarget().isAlive()) continue;     // fighting: the routine waits
            if (job == null || job.done()) {
                if (job == null && cat.getTags().contains(TAG)) release(cat, site);    // left mid-walk by a restart
                job = choose(level, camp, cat, cats, now, r);
                if (job == null) continue;
                camp.jobs.put(cat.getUUID(), job);
                camp.last.put(cat.getUUID(), job.act);
            }
            step(level, camp, cat, job, now, r);
        }
        ball(level, camp, now);
    }

    /** Whether the routine may steer this cat now. */
    static boolean free(Camp camp, WCatEntity cat) {
        Set<String> tags = cat.getTags();
        return cat.isAlive() && !cat.isPassenger() && !cat.isBeingCarried && cat.isTame()
                && BookClans.standInLeader(camp.site.clan).equals(cat.getOwnerUUID())       // a player's own cat is theirs
                && !tags.contains(BookClans.BUSY_TAG) && !tags.contains(Happenings.LOST_TAG)
                && !ClanEvents.activeEntities.contains(cat.getUUID()) && !ClanEvents.raiders.containsKey(cat.getUUID())
                && !camp.moved.contains(cat.getUUID())
                && !cat.onBorderPatrolFlag && !cat.onHuntingPatrolFlag && !cat.returnHomeFlag && !cat.returningFromPatrol
                && !cat.tellingCatsToPatrol && !cat.leaderCallingToFollowFlag && !cat.leaderCallingToSitFlag
                && !camp.handsOff.containsKey(cat.getUUID());
    }

    /** Stops steering a cat someone else now steers. A busy cat's mode is its new keeper's to set. */
    private static void letGo(Camp camp, WCatEntity cat) {
        if (camp.jobs.remove(cat.getUUID()) == null && !cat.getTags().contains(TAG)) return;
        if (cat.getTags().contains(BookClans.BUSY_TAG)) {
            cat.removeTag(TAG);
            cat.setAttacking(false);
        } else {
            release(cat, camp.site);
        }
    }

    /** Back to Warrior Cats Events' own wandering round the camp. */
    static void release(WCatEntity cat, CampSite site) {
        cat.setAttacking(false);
        if (cat.getTags().contains(TAG)) {
            cat.removeTag(TAG);
            if (cat.mode != CatMode.WANDER) {
                cat.mode = CatMode.WANDER;
                cat.setOrderedToSit(false);
                cat.getNavigation().stop();
            }
            if (!site.outside(cat.getX(), cat.getZ())) cat.wanderCenter = site.centre;
        }
    }

    private static void mode(WCatEntity cat, Job job, CatMode m) {
        if (m == CatMode.WANDER) {
            cat.removeTag(TAG);
            if (cat.mode == CatMode.SIT) cat.setOrderedToSit(false);
        } else {
            cat.addTag(TAG);
        }
        if (m != CatMode.SIT && cat.isOrderedToSit()) cat.setOrderedToSit(false);
        cat.mode = m;
        job.expect = m;
    }

    static Part partOf(WCatEntity cat, CampSite site) {
        if (cat.getRank() == WCatEntity.Rank.KIT || cat.isBaby()) return Part.KIT;
        if (cat.getRank() == WCatEntity.Rank.MEDICINE) return Part.MEDICINE;
        if (cat.getRank() == WCatEntity.Rank.APPRENTICE) return Part.APPRENTICE;
        // Clan Lore's role on the cat first (its elders and queens sleep in their dens), then the den it sleeps in
        switch (Roles.role(cat)) {
            case LEADER -> {
                return Part.LEADER;
            }
            case ELDER -> {
                return Part.ELDER;
            }
            case QUEEN -> {
                return Part.QUEEN;
            }
            default -> {}
        }
        if (!site.leader.isEmpty() && Cats.plainName(cat).equals(site.leader)) return Part.LEADER;
        if (cat.getRank() == WCatEntity.Rank.DEPUTY) return Part.DEPUTY;
        DenKind den = site.denAt(cat.getHomePosition());
        if (den == DenKind.LEADER) return Part.LEADER;
        if (den == DenKind.NURSERY) return Part.QUEEN;
        if (den == DenKind.ELDERS) return Part.ELDER;
        return Part.WARRIOR;
    }

    /**
     * Whether it is night for this cat: from a little after sunset until a little after dawn, each cat going to its
     * nest and waking at its own moment, so the camp doesn't all move at once.
     */
    static boolean night(int time, WCatEntity cat) {
        int h = cat.getUUID().hashCode() & 0x7fffffff;
        int bed = 12600 + h % 700, wake = 23000 + (h / 700) % 1400;
        return wake <= 24000 ? time >= bed && time < wake : time >= bed || time < wake - 24000;
    }

    static boolean between(int time, int from, int to) {
        return from <= to ? time >= from && time < to : time >= from || time < to;
    }

    private static BlockPos home(WCatEntity cat, CampSite site) {
        BlockPos h = cat.getHomePosition();
        return h == null || h.equals(BlockPos.ZERO) || Math.hypot(h.getX() - site.cx, h.getZ() - site.cz) > site.outer + 8 ? site.centre : h;
    }

    private static BlockPos or(BlockPos p, BlockPos fallback) {
        return p != null ? p : fallback;
    }

    /** A spot a step or two from {@code p}, where a cat can stand (else {@code p}), so cats don't stand in one block. */
    private static BlockPos near(ServerLevel level, BlockPos p, RandomSource r, int spread) {
        for (int i = 0; i < 4; i++) {
            BlockPos q = CampSite.stand(level, p.getX() + r.nextInt(spread * 2 + 1) - spread, p.getZ() + r.nextInt(spread * 2 + 1) - spread, p.getY());
            if (q != null && Math.abs(q.getY() - p.getY()) <= 1) return q;
        }
        return p;
    }

    /**
     * The steps from (fx, fz) to {@code to}: across the barrier only by the way in, every point of it in order, and
     * through the open middle when crossing a wide camp (a straight line runs into the dens).
     */
    static List<Step> route(CampSite site, double fx, double fz, BlockPos to) {
        List<Step> out = new ArrayList<>();
        boolean fromOut = site.outside(fx, fz), toOut = site.outside(to);
        if (fromOut != toOut && !site.way.isEmpty()) {
            List<BlockPos> way = site.way;
            BlockPos inside = way.get(0);
            if (!fromOut) {
                if (Math.hypot(fx - inside.getX(), fz - inside.getZ()) > 10 && Math.hypot(fx - site.cx, fz - site.cz) > 4) out.add(go(site.centre));
                for (BlockPos p : way) out.add(go(p));
                out.add(go(to));
                return out;
            }
            for (int k = way.size() - 1; k >= 0; k--) out.add(go(way.get(k)));
            fx = inside.getX() + 0.5;
            fz = inside.getZ() + 0.5;
        }
        if (!toOut && Math.hypot(fx - to.getX(), fz - to.getZ()) > 10 && Math.hypot(fx - site.cx, fz - site.cz) > 4
                && Math.hypot(to.getX() - site.cx, to.getZ() - site.cz) > 4) out.add(go(site.centre));
        out.add(go(to));
        return out;
    }

    private static List<Step> then(List<Step> steps, Step... more) {
        steps.addAll(List.of(more));
        return steps;
    }

    private static int secs(RandomSource r, int lo, int hi) {
        return lo + r.nextInt(hi - lo + 1);
    }

    // choosing ------------------------------------------------------------------------------------------------------

    /** The night's guards: one or two warriors, a different pair each night. */
    private static void guardsFor(Camp camp, List<WCatEntity> cats, Now now) {
        if (camp.guardsDay == now.day()) return;
        camp.guardsDay = now.day();
        camp.guards.clear();
        if (camp.site.way.isEmpty()) return;
        List<WCatEntity> fit = cats.stream().filter(x -> {
            Part p = partOf(x, camp.site);
            return p == Part.WARRIOR || p == Part.DEPUTY;
        }).sorted(Comparator.comparing(WCatEntity::getUUID)).toList();
        if (fit.isEmpty()) return;
        int n = fit.size() >= 5 ? 2 : 1;
        for (int i = 0; i < n; i++) camp.guards.add(fit.get((int) ((now.day() * n + i) % fit.size())).getUUID());
    }

    /** The cat's next activity, by its part, the time of day and the sky. */
    static Job choose(ServerLevel level, Camp camp, WCatEntity cat, List<WCatEntity> cats, Now now, RandomSource r) {
        CampSite site = camp.site;
        double x = cat.getX(), z = cat.getZ();
        if (site.outside(x, z)) {
            // out of camp with nothing to do (left there by WCE's own patrol, or a restart): home by the way in
            if (site.way.isEmpty()) return null;
            BlockPos to = near(level, site.centre, r, 2);
            return new Job(Act.HOME, then(route(site, x, z, to), stay(Pose.STROLL, to, 10)));
        }
        Part part = partOf(cat, site);
        if (night(now.time(), cat)) {
            if (camp.guards.contains(cat.getUUID()) && site.guards[0] != null) {
                BlockPos g = site.guards[camp.guards.stream().sorted().toList().indexOf(cat.getUUID()) % 2];
                return new Job(Act.GUARD, then(route(site, x, z, g), night(Pose.GUARD, g)));
            }
            BlockPos h = home(cat, site);
            return new Job(Act.SLEEP, then(route(site, x, z, h), night(Pose.REST, h)));
        }
        Act act = pick(part, now, r);
        if (act == camp.last.get(cat.getUUID()) && r.nextFloat() < 0.6F) act = pick(part, now, r);
        return build(level, camp, cat, part, act, cats, now, r);
    }

    private static Act pick(Part part, Now now, RandomSource r) {
        boolean dry = !now.wet();
        boolean evening = between(now.time(), 11000, 12600);
        int sun = dry && !now.leafBare() ? (now.greenleaf() ? 5 : 3) : 0, eat = evening ? 6 : 1;
        Map<Act, Integer> w = new EnumMap<>(Act.class);
        switch (part) {
            case KIT -> {
                w.put(Act.PLAY, dry ? 6 : 0);
                w.put(Act.SPAR, dry ? 2 : 1);
                w.put(Act.NAP, dry ? 2 : 5);
                w.put(Act.CHILL, 1);
            }
            case QUEEN -> {
                w.put(Act.WATCH, 4);
                w.put(Act.SHARE, 2);
                w.put(Act.NAP, dry ? 1 : 3);
                w.put(Act.EAT, eat);
            }
            case ELDER -> {
                w.put(Act.NAP, now.leafBare() ? 5 : 3);
                w.put(Act.CHILL, 3);
                w.put(Act.SUN, sun / 2);
                w.put(Act.SHARE, 2);
                w.put(Act.EAT, eat);
            }
            case APPRENTICE -> {
                w.put(Act.SPAR, 5);
                w.put(Act.TEND, 2);
                w.put(Act.CHILL, 1);
                w.put(Act.STROLL, dry ? 1 : 0);
                w.put(Act.EAT, eat);
            }
            case MEDICINE -> {
                w.put(Act.TEND, 3);
                w.put(Act.CHILL, 3);
                w.put(Act.EAT, eat);
            }
            case LEADER -> {
                w.put(Act.WATCH, 4);
                w.put(Act.SHARE, 2);
                w.put(Act.STROLL, dry ? 2 : 0);
                w.put(Act.EAT, eat);
            }
            default -> {
                w.put(Act.SHARE, 3);
                w.put(Act.SUN, sun);
                w.put(Act.STROLL, dry ? (part == Part.DEPUTY ? 3 : 2) : 0);
                w.put(Act.WATCH, 1);
                w.put(Act.NAP, dry ? 1 : 3);
                w.put(Act.EAT, eat);
            }
        }
        int total = w.values().stream().mapToInt(Integer::intValue).sum();
        int roll = r.nextInt(Math.max(1, total));
        for (Map.Entry<Act, Integer> e : w.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) return e.getKey();
        }
        return Act.CHILL;
    }

    /** The steps of a day activity in camp; a pair activity gives the partner its half too. */
    private static Job build(ServerLevel level, Camp camp, WCatEntity cat, Part part, Act act, List<WCatEntity> cats, Now now, RandomSource r) {
        CampSite site = camp.site;
        double x = cat.getX(), z = cat.getZ();
        BlockPos own = or(site.fronts.get(denOf(part, cat, site)), site.centre);
        switch (act) {
            case STROLL -> {
                BlockPos to = near(level, site.centre, r, 3);
                return new Job(act, then(route(site, x, z, to), stay(Pose.STROLL, to, secs(r, 25, 50))));
            }
            case NAP -> {
                BlockPos h = home(cat, site);
                return new Job(act, then(route(site, x, z, h), stay(Pose.REST, h, secs(r, 40, 90))));
            }
            case EAT -> {
                BlockPos to = near(level, or(site.eat, site.centre), r, 1);
                return new Job(act, then(route(site, x, z, to), new Step(Step.Kind.EAT, to, null, 0, false), stay(Pose.CHILL, to, secs(r, 15, 25))));
            }
            case SUN -> {
                BlockPos to = near(level, or(site.sun, site.centre), r, 2);
                return new Job(act, then(route(site, x, z, to), stay(Pose.CHILL, to, secs(r, 40, 90))));
            }
            case PLAY -> {
                BlockPos to = near(level, or(site.play, own), r, 2);
                watchKits(level, camp, cats, now, r);
                return new Job(act, then(route(site, x, z, to), stay(Pose.STROLL, or(site.play, own), secs(r, 40, 80))));
            }
            case WATCH -> {
                BlockPos spot = switch (part) {
                    case QUEEN -> or(site.fronts.get(DenKind.NURSERY), own);
                    case LEADER -> or(site.fronts.get(DenKind.LEADER), or(site.sun, own));
                    default -> or(site.guards[r.nextInt(2)], own);
                };
                return new Job(act, then(route(site, x, z, spot), stay(Pose.SIT, spot, secs(r, 45, 90))));
            }
            case TEND -> {
                DenKind[] visit = part == Part.MEDICINE ? new DenKind[] {DenKind.ELDERS, DenKind.NURSERY, DenKind.WARRIORS, DenKind.APPRENTICES}
                        : new DenKind[] {DenKind.ELDERS, DenKind.NURSERY};
                BlockPos to = near(level, or(site.fronts.get(visit[r.nextInt(visit.length)]), own), r, 1);
                return new Job(act, then(route(site, x, z, to), stay(Pose.CHILL, to, secs(r, 25, 40))));
            }
            case SHARE, SPAR -> {
                WCatEntity mate = partner(camp, cat, part, act, cats, now);
                if (mate != null) return pair(level, camp, cat, mate, act, now.wet(), r);
                BlockPos to = near(level, own, r, 1);
                return new Job(Act.CHILL, then(route(site, x, z, to), stay(Pose.CHILL, to, secs(r, 30, 60))));
            }
            default -> {
                BlockPos to = now.wet() ? home(cat, site) : near(level, own, r, 1);
                return new Job(Act.CHILL, then(route(site, x, z, to), stay(Pose.CHILL, to, secs(r, 30, 70))));
            }
        }
    }

    /** The den whose front a cat idles at: its own (kits and queens the nursery's, elders theirs). */
    private static DenKind denOf(Part part, WCatEntity cat, CampSite site) {
        return switch (part) {
            case KIT, QUEEN -> DenKind.NURSERY;
            case ELDER -> DenKind.ELDERS;
            case APPRENTICE -> DenKind.APPRENTICES;
            case MEDICINE -> DenKind.MEDICINE;
            case LEADER -> DenKind.LEADER;
            default -> {
                DenKind d = site.denAt(cat.getHomePosition());
                yield d != null ? d : DenKind.WARRIORS;
            }
        };
    }

    /** A companion for sharing tongues or sparring: free now, or only idling, and not already paired. */
    private static WCatEntity partner(Camp camp, WCatEntity cat, Part part, Act act, List<WCatEntity> cats, Now now) {
        WCatEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (WCatEntity o : cats) {
            if (o == cat || o.getTarget() != null || night(now.time(), o) || camp.site.outside(o.getX(), o.getZ())) continue;
            Job oj = camp.jobs.get(o.getUUID());
            if (oj != null && !oj.done() && !oj.idle(camp.site, o)) continue;
            Part op = partOf(o, camp.site);
            int score;
            if (act == Act.SPAR) {
                if (part == Part.KIT) score = op == Part.KIT ? 2 : -1;
                else score = op == Part.WARRIOR || op == Part.DEPUTY ? 3 : op == Part.APPRENTICE ? 1 : -1;   // a mentor first
                // its own mentor (or apprentice) above all: Clan Lore's pairing, trained together
                if (o.getUUID().equals(Roles.mentorOf(cat)) || o.getUUID().equals(Roles.apprenticeOf(cat))) score = 5;
            } else {
                if (op == Part.KIT) continue;
                score = (op == part ? 2 : 1) + (oj == null || oj.done() ? 1 : 0);
            }
            if (score < 0) continue;
            score = score * 100 - (int) Math.min(99, o.distanceTo(cat));
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    private static Job pair(ServerLevel level, Camp camp, WCatEntity cat, WCatEntity mate, Act act, boolean wet, RandomSource r) {
        CampSite site = camp.site;
        Part part = partOf(cat, site);
        BlockPos spot = act == Act.SPAR ? or(part == Part.KIT ? site.play : site.train, site.centre)
                : wet ? home(cat, site) : r.nextInt(3) == 0 && site.sun != null ? site.sun
                : or(site.fronts.get(denOf(part, cat, site)), site.centre);
        BlockPos a = near(level, spot, r, 1), b = near(level, spot, r, 1);
        int len = act == Act.SPAR ? secs(r, 30, 50) : secs(r, 40, 80);
        Pose pose = act == Act.SPAR ? Pose.SPAR : Pose.CHILL;
        Job mine = new Job(act, then(route(site, cat.getX(), cat.getZ(), a), stay(pose, spot, len)));
        Job theirs = new Job(act, then(route(site, mate.getX(), mate.getZ(), b), stay(pose, spot, len)));
        if (act == Act.SPAR) {
            mine.steps.add(stay(Pose.CHILL, a, 15));
            theirs.steps.add(stay(Pose.CHILL, b, 15));
        }
        mine.partner = mate.getUUID();
        theirs.partner = cat.getUUID();
        Job old = camp.jobs.put(mate.getUUID(), theirs);
        if (old != null) theirs.expect = old.expect;
        camp.last.put(mate.getUUID(), act);
        return mine;
    }

    /** Kits at play: the queen comes to sit by the nursery and watch, unless she is busy with something real. */
    private static void watchKits(ServerLevel level, Camp camp, List<WCatEntity> cats, Now now, RandomSource r) {
        CampSite site = camp.site;
        for (WCatEntity q : cats) {
            if (partOf(q, site) != Part.QUEEN || night(now.time(), q)) continue;
            Job qj = camp.jobs.get(q.getUUID());
            if (qj != null && !qj.done() && (qj.act == Act.WATCH || !qj.idle(site, q))) continue;
            BlockPos spot = or(site.fronts.get(DenKind.NURSERY), or(site.play, site.centre));
            Job watch = new Job(Act.WATCH, then(route(site, q.getX(), q.getZ(), spot), stay(Pose.SIT, spot, secs(r, 60, 100))));
            if (qj != null) watch.expect = qj.expect;
            camp.jobs.put(q.getUUID(), watch);
            camp.last.put(q.getUUID(), Act.WATCH);
            return;
        }
    }

    // outings -------------------------------------------------------------------------------------------------------

    /**
     * Today's outings, each once when its time comes: the dawn border patrol, a morning hunt, an afternoon patrol
     * (a hunt in leaf-bare), a late hunt in leaf-bare and every other day, and the medicine cat's herb gathering in
     * the morning and (outside leaf-bare) the afternoon. None in a thunderstorm; one patrol out at a time.
     */
    private static void outings(ServerLevel level, Camp camp, List<WCatEntity> cats, Now now, RandomSource r) {
        if (camp.site.way.isEmpty() || now.storm()) return;
        int t = now.time();
        boolean out = camp.jobs.values().stream().anyMatch(j -> !j.done() && (j.act == Act.PATROL || j.act == Act.HUNT));
        if (!out) {
            if (between(t, 23200, 1200)) patrol(level, camp, cats, now, r, "dawn", Act.PATROL);
            else if (between(t, 2500, 4500)) patrol(level, camp, cats, now, r, "morning", Act.HUNT);
            else if (between(t, 6500, 8500)) patrol(level, camp, cats, now, r, "afternoon", now.leafBare() ? Act.HUNT : Act.PATROL);
            else if (between(t, 8500, 10500) && (now.leafBare() || now.day() % 2 == 0)) patrol(level, camp, cats, now, r, "late", Act.HUNT);
        }
        if (between(t, 1500, 4000)) herbs(level, camp, cats, now, r, "herbs-morning");
        else if (between(t, 7000, 9500) && !now.leafBare()) herbs(level, camp, cats, now, r, "herbs-afternoon");
    }

    private static boolean able(Camp camp, WCatEntity cat, Now now) {
        Job j = camp.jobs.get(cat.getUUID());
        return !night(now.time(), cat) && (j == null || j.done() || j.idle(camp.site, cat)) && cat.getTarget() == null
                && cat.getHealth() >= cat.getMaxHealth() * 0.7F && !cat.hasDisease(DiseaseTypes.BROKEN_PAW) && !cat.hasDisease(DiseaseTypes.SORE_PADS);
    }

    private static void patrol(ServerLevel level, Camp camp, List<WCatEntity> cats, Now now, RandomSource r, String key, Act act) {
        if (camp.done.contains(key)) return;
        CampSite site = camp.site;
        List<WCatEntity> pool = new ArrayList<>(cats.stream().filter(x -> {
            Part p = partOf(x, site);
            return (p == Part.WARRIOR || p == Part.DEPUTY || p == Part.APPRENTICE) && able(camp, x, now);
        }).toList());
        if (pool.size() < 2) return;                                              // wait for the cats to wake
        // a warrior leads (the deputy at dawn), whoever has been out least today; apprentices go along behind
        pool.sort(Comparator.comparingInt((WCatEntity x) -> camp.outings.getOrDefault(x.getUUID(), 0) * 10
                - (key.equals("dawn") && partOf(x, site) == Part.DEPUTY ? 5 : 0) + (partOf(x, site) == Part.APPRENTICE ? 100 : 0)));
        if (partOf(pool.get(0), site) == Part.APPRENTICE) return;
        List<BlockPos> spots = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            BlockPos s = site.spot.apply(r, act == Act.PATROL);
            if (s != null) spots.add(s);
        }
        if (spots.isEmpty()) return;
        camp.done.add(key);
        int n = Math.min(pool.size(), 2 + (pool.size() >= 3 && r.nextFloat() < 0.6F ? 1 : 0));
        BlockPos back = act == Act.HUNT ? or(site.eat, site.centre) : site.centre;
        for (int i = 0; i < n; i++) {
            WCatEntity cat = pool.get(i);
            Job old = camp.jobs.get(cat.getUUID());
            Job job = new Job(act, outing(site, cat, spots, act, i, r, back, act == Act.HUNT ? Pose.STROLL : Pose.CHILL, act == Act.HUNT ? 12 : 20));
            if (old != null) job.expect = old.expect;
            camp.jobs.put(cat.getUUID(), job);
            camp.last.put(cat.getUUID(), act);
            camp.outings.merge(cat.getUUID(), 1, Integer::sum);
        }
    }

    private static void herbs(ServerLevel level, Camp camp, List<WCatEntity> cats, Now now, RandomSource r, String key) {
        if (camp.done.contains(key) || now.wet()) return;                       // herbs are gathered dry
        CampSite site = camp.site;
        for (WCatEntity cat : cats) {
            if (partOf(cat, site) != Part.MEDICINE || !able(camp, cat, now)) continue;
            BlockPos spot = site.spot.apply(r, false);
            if (spot == null) return;
            camp.done.add(key);
            BlockPos den = or(site.fronts.get(DenKind.MEDICINE), site.centre);
            Job old = camp.jobs.get(cat.getUUID());
            Job job = new Job(Act.HERBS, outing(site, cat, List.of(spot), Act.HERBS, 0, r, den, Pose.CHILL, secs(r, 30, 45)));
            if (old != null) job.expect = old.expect;
            camp.jobs.put(cat.getUUID(), job);
            camp.last.put(cat.getUUID(), Act.HERBS);
            return;
        }
    }

    /**
     * An outing's steps: out by the whole way in, a while at each spot (strolling there, as WCE's wander does round
     * a patrol's stops), back in by the whole way in, hunters given their catch once inside, and on to {@code end}.
     */
    static List<Step> outing(CampSite site, WCatEntity cat, List<BlockPos> spots, Act act, int member, RandomSource r,
                             BlockPos end, Pose endPose, int endSecs) {
        List<Step> steps = route(site, cat.getX(), cat.getZ(), spots.get(0));
        steps.remove(steps.size() - 1);
        for (BlockPos s : spots) {
            BlockPos at = s.offset(member % 2 == 0 ? member : -member, 0, member / 2);
            steps.add(go(at));
            steps.add(stay(Pose.STROLL, s, act == Act.HUNT ? secs(r, 40, 60) : secs(r, 30, 45)));
        }
        for (int k = site.way.size() - 1; k >= 0; k--) steps.add(go(site.way.get(k)));
        if (act == Act.HUNT) steps.add(new Step(Step.Kind.PREY, site.way.get(0), null, 0, false));
        BlockPos in = site.way.get(0);
        steps.addAll(route(site, in.getX() + 0.5, in.getZ() + 0.5, end));
        steps.add(stay(endPose, end, endSecs));
        return steps;
    }

    // doing ---------------------------------------------------------------------------------------------------------

    private static void step(ServerLevel level, Camp camp, WCatEntity cat, Job job, Now now, RandomSource r) {
        if (job.i < 0) {
            job.started = now.tick();
            if (job.expect == null) job.expect = cat.mode;
            next(level, camp, cat, job, now, r);
        }
        Step s = job.current();
        if (s == null) return;
        if (s.kind() == Step.Kind.GO) walk(level, camp, cat, job, s, now, r);
        else if (s.kind() == Step.Kind.STAY) stay(level, camp, cat, job, s, now, r);
    }

    private static double flat(WCatEntity cat, BlockPos p) {
        return Math.hypot(cat.getX() - (p.getX() + 0.5), cat.getZ() - (p.getZ() + 0.5));
    }

    private static boolean at(WCatEntity cat, BlockPos p) {
        return flat(cat, p) < 1.7 && Math.abs(cat.getY() - p.getY()) < 3;
    }

    /** Moves on to the job's next step and starts it; the last step done, the cat is left wandering where it is. */
    private static void next(ServerLevel level, Camp camp, WCatEntity cat, Job job, Now now, RandomSource r) {
        job.i++;
        while (!job.done()) {
            Step s = job.steps.get(job.i);
            switch (s.kind()) {
                case GO -> {
                    if (at(cat, s.at())) {
                        job.i++;
                        continue;
                    }
                    mode(cat, job, CatMode.FOLLOW);
                    job.best = flat(cat, s.at());
                    job.bestAt = now.tick();
                    moveTo(cat, s.at(), speed(cat, job));
                    return;
                }
                case STAY -> {
                    begin(level, camp, cat, job, s, now, r);
                    return;
                }
                case PREY -> prey(cat, camp.site.clan, now, r);
                case EAT -> eat(level, camp.site, cat);
            }
            job.i++;
        }
        cat.setAttacking(false);
        if (cat.mode != CatMode.WANDER) mode(cat, job, CatMode.WANDER);
    }

    private static double speed(WCatEntity cat, Job job) {
        return job.act == Act.PATROL || job.act == Act.HUNT ? 1.1 : cat.isBaby() ? 1.15 : 1.0;
    }

    private static void moveTo(WCatEntity cat, BlockPos p, double speed) {
        cat.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, speed);
    }

    private static void walk(ServerLevel level, Camp camp, WCatEntity cat, Job job, Step s, Now now, RandomSource r) {
        if (at(cat, s.at())) {
            next(level, camp, cat, job, now, r);
            return;
        }
        double d = flat(cat, s.at());
        if (d < job.best - 0.75) {
            job.best = d;
            job.bestAt = now.tick();
        } else if (now.tick() - job.bestAt > 200) {
            // no nearer in ten seconds: close enough, else hop the last stretch where no player can see it (the
            // steps never cross the barrier, so neither does the hop), else give the activity up
            if (d < 3.5) {
                next(level, camp, cat, job, now, r);
            } else if (!seen(level, cat.getX(), cat.getY(), cat.getZ()) && !seen(level, s.at().getX(), s.at().getY(), s.at().getZ())) {
                cat.getNavigation().stop();
                cat.teleportTo(s.at().getX() + 0.5, s.at().getY(), s.at().getZ() + 0.5);
                next(level, camp, cat, job, now, r);
            } else {
                camp.jobs.remove(cat.getUUID());
                release(cat, camp.site);
                camp.handsOff.put(cat.getUUID(), now.tick() + 20L * 30);
            }
            return;
        }
        if (cat.getNavigation().isDone() || now.tick() / 20 % 3 == 0) moveTo(cat, s.at(), speed(cat, job));
    }

    private static void begin(ServerLevel level, Camp camp, WCatEntity cat, Job job, Step s, Now now, RandomSource r) {
        job.until = now.tick() + 20L * s.seconds();
        cat.getNavigation().stop();
        switch (s.pose()) {
            case STROLL -> {
                mode(cat, job, CatMode.WANDER);
                cat.wanderCenter = s.at();
            }
            case CHILL -> {
                mode(cat, job, CatMode.WANDER);
                cat.wanderCenter = s.at();
                cat.setChilling(true, 20 * s.seconds());
            }
            case REST -> {
                mode(cat, job, CatMode.WANDER);
                cat.wanderCenter = s.at();
                cat.setResting(true, s.night() ? 6000 : 20 * s.seconds());
            }
            case SIT, GUARD -> {
                mode(cat, job, CatMode.SIT);
                face(cat, camp.site, s);
                job.paceAt = now.tick() + 20L * secs(r, 45, 80);
            }
            case SPAR -> mode(cat, job, CatMode.FOLLOW);
        }
    }

    private static void stay(ServerLevel level, Camp camp, WCatEntity cat, Job job, Step s, Now now, RandomSource r) {
        if (s.night() ? !night(now.time(), cat) : now.tick() >= job.until) {
            if (job.swat) cat.setAttacking(false);
            next(level, camp, cat, job, now, r);
            return;
        }
        switch (s.pose()) {
            case REST -> {
                // asleep until dawn: WCE wakes a cat when its rest runs out, so a sleeper at its nest is put back down
                if (s.night() && !cat.isResting() && flat(cat, s.at()) < 2.5) cat.setResting(true, 6000);
            }
            case SPAR -> spar(level, camp, cat, job, s, now, r);
            case GUARD -> guard(camp, cat, job, s, now, r);
            default -> { }
        }
    }

    /** Faces a sitter outward when it guards the way in, else toward the camp's middle. */
    private static void face(WCatEntity cat, CampSite site, Step s) {
        double dx, dz;
        if (s.pose() == Pose.GUARD || s.at().equals(site.guards[0]) || s.at().equals(site.guards[1])) {
            dx = Math.cos(site.gate);
            dz = Math.sin(site.gate);
        } else {
            dx = site.cx - cat.getX();
            dz = site.cz - cat.getZ();
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        cat.setYRot(yaw);
        cat.setYBodyRot(yaw);
        cat.setYHeadRot(yaw);
    }

    /** A guard sits a while, gets up, pads to the way in's other side, and sits again. */
    private static void guard(Camp camp, WCatEntity cat, Job job, Step s, Now now, RandomSource r) {
        CampSite site = camp.site;
        if (job.paceTo != null) {
            if (at(cat, job.paceTo) || now.tick() > job.paceUntil) {
                job.paceTo = null;
                mode(cat, job, CatMode.SIT);
                face(cat, site, s);
                job.paceAt = now.tick() + 20L * secs(r, 45, 80);
            } else if (cat.getNavigation().isDone()) {
                moveTo(cat, job.paceTo, 0.9);
            }
        } else if (now.tick() >= job.paceAt && site.guards[0] != null && site.guards[1] != null) {
            job.paceTo = flat(cat, site.guards[0]) < flat(cat, site.guards[1]) ? site.guards[1] : site.guards[0];
            job.paceUntil = now.tick() + 20L * 12;
            mode(cat, job, CatMode.FOLLOW);
            moveTo(cat, job.paceTo, 0.9);
        }
    }

    /** Sparring: the pair dance round each other a step or two apart, taking turns to swat. */
    private static void spar(ServerLevel level, Camp camp, WCatEntity cat, Job job, Step s, Now now, RandomSource r) {
        Entity e = job.partner == null ? null : level.getEntity(job.partner);
        Job pj = e == null ? null : camp.jobs.get(e.getUUID());
        Step ps = pj == null ? null : pj.current();
        if (job.swat) {
            cat.setAttacking(false);
            job.swat = false;
        }
        if (!(e instanceof WCatEntity mate) || ps == null || ps.pose() != Pose.SPAR || ps.kind() != Step.Kind.STAY) {
            // the partner is not there yet: wait a little, then give up and rest instead
            if (now.tick() - (job.until - 20L * s.seconds()) > 20 * 25 || pj == null || pj.done()) job.until = now.tick();
            return;
        }
        cat.getLookControl().setLookAt(mate, 30.0F, 30.0F);
        if ((now.tick() / 20 + (cat.getId() & 1)) % 2 != 0) return;              // the pair take turns
        double a = r.nextDouble() * Math.PI * 2, d = 1.3 + r.nextDouble() * 0.9;
        double x = mate.getX() + Math.cos(a) * d, z = mate.getZ() + Math.sin(a) * d;
        if (Math.hypot(x - s.at().getX() - 0.5, z - s.at().getZ() - 0.5) > 3.5) {
            x = s.at().getX() + 0.5;
            z = s.at().getZ() + 0.5;
        }
        cat.getNavigation().moveTo(x, cat.getY(), z, 1.35);
        if (cat.distanceTo(mate) < 2.6 && r.nextBoolean()) {
            cat.setAttacking(true);                                                   // WCE's swipe animation; no harm done
            job.swat = true;
        }
    }

    /** A hunter's catch, by its Clan's land: fish for RiverClan, rabbit on the moor, frogs in ShadowClan's marsh. */
    private static void prey(WCatEntity cat, Clan clan, Now now, RandomSource r) {
        if ((now.leafBare() || now.fog()) && r.nextBoolean()) return;              // prey is scarce, or lost in the mist
        String[] kinds = switch (clan) {
            case RIVER -> new String[] {"minecraft:cod", "minecraft:salmon", "warriorcats_events:mouse"};
            case WIND -> new String[] {"minecraft:rabbit", "warriorcats_events:mouse", "warriorcats_events:pigeon"};
            case SHADOW -> new String[] {"warriorcats_events:frog", "warriorcats_events:mouse", "warriorcats_events:squirrel"};
            default -> new String[] {"warriorcats_events:mouse", "warriorcats_events:squirrel", "warriorcats_events:pigeon"};
        };
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(kinds[r.nextInt(kinds.length)]));
        if (item != net.minecraft.world.item.Items.AIR) cat.getCatInventory().addItem(new ItemStack(item));
    }

    /** A cat eats a piece from the fresh-kill pile (or the prey it carries, if the pile is bare). */
    private static void eat(ServerLevel level, CampSite site, WCatEntity cat) {
        if (site.pile != null && level.hasChunk(site.pile.getX() >> 4, site.pile.getZ() >> 4)
                && level.getBlockEntity(site.pile) instanceof FreshkillPileBlockEntity pile) {
            var h = pile.getItemHandler();
            for (int i = 0; i < h.getSlots(); i++) {
                if (!h.extractItem(i, 1, false).isEmpty()) {
                    pile.setChanged();
                    level.sendBlockUpdated(site.pile, level.getBlockState(site.pile), level.getBlockState(site.pile), 3);
                    cat.heal(2);
                    return;
                }
            }
        }
        for (int i = 0; i < cat.getCatInventory().getContainerSize(); i++) {
            if (cat.isFood(cat.getCatInventory().getItem(i))) {
                cat.eatPreyInInventory();
                return;
            }
        }
    }

    /** The kits' moss ball: set out by the nursery while they play, taken in a minute after they stop. */
    private static void ball(ServerLevel level, Camp camp, Now now) {
        boolean playing = camp.jobs.values().stream().anyMatch(j -> j.act == Act.PLAY && j.current() != null && j.current().kind() == Step.Kind.STAY);
        Entity ball = camp.ball == null ? null : level.getEntity(camp.ball);
        CampSite site = camp.site;
        if (playing) {
            camp.ballIdle = 0;
            if (ball == null && site.play != null && level.hasChunk(site.play.getX() >> 4, site.play.getZ() >> 4)) {
                MossBallEntity b = ModEntities.MOSS_BALL.get().create(level);
                if (b == null) return;
                Item moss = BuiltInRegistries.ITEM.get(ResourceLocation.parse("warriorcats_events:moss_ball"));
                if (moss != net.minecraft.world.item.Items.AIR) b.setStack(new ItemStack(moss));
                b.moveTo(site.play.getX() + 0.5, site.play.getY() + 0.3, site.play.getZ() + 0.5);
                b.addTag(BALL_TAG);
                level.addFreshEntity(b);
                camp.ball = b.getUUID();
            }
        } else if (ball != null) {
            if (camp.ballIdle == 0) camp.ballIdle = now.tick();
            else if (now.tick() - camp.ballIdle > 20 * 60) {
                ball.discard();
                camp.ball = null;
                camp.ballIdle = 0;
            }
        }
    }
}
