package com.ninjacat.greatjourney.clanlands.life;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A Clan cat leading one player somewhere: a guide taking a kittypet to a camp, a mentor showing an apprentice the
 * territory. The cat walks a few blocks ahead to each stop in turn, stops and looks back when the player falls
 * behind, and is brought to the player's side when the player gets far away. Long routes are walked in stretches of
 * up to {@link #PLAN_REACH} blocks, so the land ahead only has to be loaded near the player.
 *
 * <p>Each stretch is a path the player can walk too: worked out ahead of time on the land as it stands, over steps
 * of at most one block up and short drops, round walls, cliffs, ravines and deep water (swum only when there is no
 * dry way), never through fire or lava. The cat walks it and never jumps ahead of its player: Kiki's mentor led her
 * into a marsh and up to a cliff, then turned up on top of it and waited for her to climb. A stop no walkable way
 * reaches is passed by ({@link #whenBlocked}); only the very last one, if it cannot be walked to, is still reached
 * the old way, the cat finding its own way round. At each stop, once the cat and the player are both there, the
 * stop's callback runs (a line of dialogue, the next scene).
 *
 * <p>The same steering can keep a cat near a player instead ({@link #accompany}): a mentor watching a hunt.
 *
 * <p>Every lead belongs to one player and one cat; a player has one lead at a time and a cat leads one player. The
 * cat wears {@link BookClans#BUSY_TAG} while it leads, and its own Warrior Cats Events mode and home are kept on it
 * and put back after. A lead lives only while the server runs: a stop, a logout, a death or a change of dimension
 * ends it cleanly ({@link End#ABANDONED}), and a cat that loads again still marked as led is put back as it was.
 * Whoever started the lead can then simply offer it again.
 */
public final class Lead {
    /** How a lead ended: every stop reached (or the time to accompany is up), or given up. */
    public enum End { DONE, ABANDONED }

    /** A place to lead to; {@code reached} runs once the cat and the player are both within {@code radius}. */
    public record Stop(double x, double z, double radius, Consumer<Lead> reached) {}

    @FunctionalInterface
    public interface Ended {
        void ended(Lead lead, End how);
    }

    /** On a cat while it leads, beside the busy tag; its own mode and home are kept in its data meanwhile. */
    public static final String LED_TAG = "greatjourney_led";
    /**
     * On a cat brought into the world only to guide someone: a guide whose walk was cut short (a restart, a logout)
     * has gone home by the time its chunk loads again, and is removed then. A guide who delivers its player stays
     * with its Clan: whoever led with it takes the tag off.
     */
    public static final String GUIDE_TAG = "greatjourney_guide";
    private static final String SAVED = "greatjourney_lead";

    /** Walks on again once the player is this close behind. */
    static final double AHEAD = 6;
    /** Stops and looks back when the player is further behind than this. */
    static final double WAIT = 9;
    /** Further than this from the player, the cat is brought to them (only ever where the player cannot see it). */
    static final double CATCH_UP = 72;
    /** A waiting cat further than this from its player walks back to them instead of standing. */
    static final double WALK_BACK = 22;
    /** A stop further than this gets a planned way ({@link Route}) instead of a straight one. */
    static final double PLANNED = 48;
    /** A waypoint of the planned way counts as passed this close. */
    static final double WAYPOINT = 7;
    /** A stop counts once the player is within this (or the stop's own radius, if larger). */
    static final double TOGETHER = 9;
    /** Ticks between decisions. */
    static final int STEP = 5;
    /** Decisions without getting closer before the cat looks for another way (three seconds). */
    static final int STUCK_STEPS = 12;
    /** How far ahead one planned stretch of the walk reaches. */
    static final double PLAN_REACH = 40;
    /** A planned stretch must end at least this much nearer the stop than the cat stands, or it is no way on. */
    static final double PROGRESS = 2;
    /** Tries in a row without a way on before the stop is passed by. */
    static final int BLOCKED_AFTER = 4;
    /** Decisions a fight may hold the walk (fifteen seconds) before the cat leaves it. */
    static final int FIGHT_STEPS = 60;
    /** Times the last stop may be got nearer by the old way round before it counts as reached. */
    static final int LAST_STOP_PUTS = 3;
    /** Search effort for one stretch: the most places the path finder looks at. */
    static final int MAX_NODES = 2400;
    /** Times in a row the player was caught up with while getting further from the stop, before the cat gives up. */
    static final int GIVE_UP = 4;
    /** The cat's "wait for me" callback runs at most this often. */
    static final int WAIT_SAY_EVERY = 20 * 25;

    private static final Map<UUID, Lead> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Lead> BY_CAT = new HashMap<>();

    private final ServerPlayer player;
    private final ServerLevel level;
    private final UUID catId;
    private WCatEntity cat;
    private final List<Stop> stops;
    private final Ended onEnd;
    /** Accompanying: the distance to keep, and the game time it ends (0 while leading). */
    private final double keep;
    private final long until;
    private Consumer<Lead> onWait, onBlocked;
    private int next;
    private boolean waiting, ended;
    /** The cat is as near this stop as it can walk (the place itself is down a gorge, or out in water). */
    private boolean asNear;
    private int stuck, sinceOrder, catchUps, fails, fightSteps, lastNode, lastStopPuts;
    /** The planned way to the current stop ({@link Route}): its waypoints, the next one, and the stop it is for. */
    private List<double[]> way;
    private int wayAt, wayStop = -1;
    private java.util.concurrent.CompletableFuture<List<double[]>> planning;
    /** The stretch being walked, and the stop it leads toward. */
    private net.minecraft.world.level.pathfinder.Path route;
    private int routeStop = -1;
    private double best = Double.MAX_VALUE, lastCatchUp = -1;
    private long lastWaitSay = Long.MIN_VALUE / 2;

    private Lead(ServerPlayer player, WCatEntity cat, List<Stop> stops, Ended onEnd, double keep, long until) {
        this.player = player;
        this.level = player.serverLevel();
        this.cat = cat;
        this.catId = cat.getUUID();
        this.stops = stops;
        this.onEnd = onEnd;
        this.keep = keep;
        this.until = until;
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % STEP == 0) tick(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) stop(p);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> releaseAll());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            BY_PLAYER.clear();
            BY_CAT.clear();
        });
        NeoForge.EVENT_BUS.addListener(Lead::onJoin);
        NeoForge.EVENT_BUS.addListener(Lead::onHurt);
    }

    /**
     * Starts {@code cat} leading {@code p} through {@code stops} in order; null if the cat is already leading someone
     * else. The player's own lead, if they had one, ends first.
     */
    public static Lead start(ServerPlayer p, WCatEntity cat, List<Stop> stops, Ended onEnd) {
        if (stops.isEmpty()) return null;
        return begin(new Lead(p, cat, List.copyOf(stops), onEnd, 0, 0));
    }

    /** Keeps {@code cat} about {@code keep} blocks from {@code p} for {@code ticks} (it watches, it does not lead). */
    public static Lead accompany(ServerPlayer p, WCatEntity cat, double keep, int ticks, Ended onEnd) {
        return begin(new Lead(p, cat, List.of(), onEnd, keep, p.serverLevel().getGameTime() + Math.max(1, ticks)));
    }

    private static Lead begin(Lead l) {
        Lead other = BY_CAT.get(l.catId);
        if (other != null && other.player != l.player) return null;
        stop(l.player);
        hold(l.cat);
        BY_PLAYER.put(l.player.getUUID(), l);
        BY_CAT.put(l.catId, l);
        return l;
    }

    /** Runs whenever the cat stops to wait for its lagging player (at most every 25 seconds). */
    public Lead whenWaiting(Consumer<Lead> say) {
        this.onWait = say;
        return this;
    }

    /**
     * Runs when a stop cannot be walked to from where the cat stands (the walk goes on to the next stop). Not for the
     * last stop, which is always reached.
     */
    public Lead whenBlocked(Consumer<Lead> say) {
        this.onBlocked = say;
        return this;
    }

    /** The player's lead, or null. */
    public static Lead of(ServerPlayer p) {
        return BY_PLAYER.get(p.getUUID());
    }

    /** Whether this cat is leading (or keeping near) someone right now. */
    public static boolean leading(Entity cat) {
        return BY_CAT.containsKey(cat.getUUID());
    }

    /** Ends the player's lead, if any, as given up. */
    public static void stop(ServerPlayer p) {
        Lead l = BY_PLAYER.get(p.getUUID());
        if (l != null) l.end(End.ABANDONED);
    }

    public ServerPlayer player() {
        return player;
    }

    /** The cat, as last seen loaded. */
    public WCatEntity cat() {
        return cat;
    }

    /** How many stops have been reached so far. */
    public int reached() {
        return next;
    }

    /** Whether the cat is standing still, looking back for its player. */
    public boolean waiting() {
        return waiting;
    }

    public boolean accompanying() {
        return stops.isEmpty();
    }

    static void tick(MinecraftServer server) {
        if (BY_PLAYER.isEmpty()) return;
        for (Lead l : List.copyOf(BY_PLAYER.values())) l.step();
    }

    private void step() {
        if (ended) return;
        if (player.isRemoved() || player.hasDisconnected() || !player.isAlive() || player.serverLevel() != level) {
            end(End.ABANDONED);
            return;
        }
        if (!(level.getEntity(catId) instanceof WCatEntity c) || !c.isAlive()) {
            end(End.ABANDONED);                                   // killed, or its chunk unloaded
            return;
        }
        cat = c;
        if (until > 0 && level.getGameTime() >= until) {
            end(End.DONE);
            return;
        }
        // the player is reading a scene (the Moonpool, a word at a place): the cat stays and looks at them, rather
        // than walking on out of sight while they cannot move
        if (Dialog.busy(player) && cat.distanceTo(player) <= CATCH_UP) {
            cat.getNavigation().stop();
            cat.getLookControl().setLookAt(player, 30, 30);
            return;
        }
        // a cat that dozed off, or was shift-clicked to sit mid-walk, gets back on its paws
        if (cat.isResting()) cat.setResting(false, 0);
        if (cat.isChilling()) cat.setChilling(false, 0);
        if (cat.mode != WCatEntity.CatMode.WANDER) cat.mode = WCatEntity.CatMode.WANDER;
        if (cat.isOrderedToSit()) cat.setOrderedToSit(false);
        // attacked on the way (a fox in the woods): the cat fights it off first, with Warrior Cats Events' own attack
        // (the playtest's first guide stood still under a fox's teeth until it died, and the fox then took the kittypet)
        if (fighting(cat)) {
            // a foe it cannot reach (an eagle circling overhead, a fox behind a fence) does not hold the walk for good
            if (++fightSteps > FIGHT_STEPS) {
                cat.setTarget(null);
                fightSteps = 0;
            }
            return;
        }
        fightSteps = 0;
        double toPlayer = cat.distanceTo(player);
        if (toPlayer > CATCH_UP) {
            catchUp();
            return;
        }
        if (stops.isEmpty()) {
            keepNear(toPlayer);
            return;
        }
        Stop s = stops.get(next);
        double catTo = flat(cat.getX(), cat.getZ(), s), playerTo = flat(player.getX(), player.getZ(), s);
        double catNeed = Math.min(s.radius(), 5), playerNeed = Math.max(s.radius(), TOGETHER);
        // there together: or the player ran on ahead to the place and the cat is close behind them
        boolean there = catTo <= catNeed || asNear || (playerTo <= s.radius() && catTo <= s.radius() + AHEAD);
        // shown from where the cat could get: the player at its side counts as there too (the place is out in water)
        boolean together = playerTo <= playerNeed || (asNear && (toPlayer <= AHEAD || playerTo <= catTo + AHEAD));
        // or the player has gone on past it, nearer the next place than the cat (ran ahead, or dropped into a camp's
        // hollow over its wall): the walk goes on
        boolean past = next + 1 < stops.size()
                && flat(player.getX(), player.getZ(), stops.get(next + 1)) + AHEAD < flat(cat.getX(), cat.getZ(), stops.get(next + 1));
        if (there) {
            if (together || past) {
                next++;
                best = Double.MAX_VALUE;
                stuck = 0;
                fails = 0;
                route = null;
                asNear = false;
                waiting = false;
                wayStop = -1;
                if (s.reached() != null) s.reached().accept(this);
                if (!ended && next >= stops.size()) end(End.DONE);
            } else {
                standAndWait(toPlayer);
            }
            return;
        }
        double[] aim = aim(s);
        double aimTo = Math.hypot(cat.getX() - aim[0], cat.getZ() - aim[1]), playerAim = Math.hypot(player.getX() - aim[0], player.getZ() - aim[1]);
        // it waits only for a player who has fallen behind: one ahead of it is caught up with (the playtest's kittypet
        // fell into the stone hollow ahead of its guide, who then stood on the rim calling "Keep up!" for fifteen minutes).
        // Ahead means nearer the next waypoint and still close: on a way round a lake the straight line to the camp
        // says nothing of who is behind, and the guide walked on while its kittypet fell far back
        boolean ahead = playerAim + AHEAD < aimTo && toPlayer < WALK_BACK;
        if (toPlayer > (waiting ? AHEAD : WAIT) && !ahead) {
            standAndWait(toPlayer);
            return;
        }
        waiting = false;
        if (aimTo < best - 0.5) {
            best = aimTo;
            stuck = 0;
            fails = 0;
        } else if (++stuck >= STUCK_STEPS) {
            // walking the stretch got it no nearer (a door shut, a block placed, a fight that pushed it off): another way
            stuck = 0;
            route = null;
            if (++fails >= BLOCKED_AFTER) {
                blocked(s, catTo);
                return;
            }
        }
        // hurry when the player is right behind or has overtaken, amble when they are a few blocks back
        double speed = ahead || toPlayer < 3.5 ? 1.35 : 1.1;
        var nav = cat.getNavigation();
        boolean walking = route != null && routeStop == next && nav.getPath() == route && !route.isDone();
        if (walking) {
            // a way round heads off from the place for a while (round a lake, along a ravine): still walking it is no stall
            if (route.getNextNodeIndex() > lastNode) {
                lastNode = route.getNextNodeIndex();
                stuck = 0;
            }
            nav.setSpeedModifier(speed);
            return;
        }
        if (++sinceOrder < 2) return;                    // a new stretch is worked out at most twice a second
        sinceOrder = 0;
        route = plan(aim, aimTo);
        routeStop = next;
        lastNode = 0;
        if (route != null && nav.moveTo(route, speed)) return;
        route = null;
        if (++fails >= BLOCKED_AFTER) blocked(s, catTo);
    }

    /**
     * The next stretch toward {@code s}: a path the cat (and so the player) can walk from where the cat stands, toward
     * a point up to {@link #PLAN_REACH} blocks on. When the point itself can't be reached the path finder gives the
     * way that gets nearest it, round whatever is in the way. Null if that gets no nearer the stop.
     */
    private net.minecraft.world.level.pathfinder.Path plan(double[] aim, double catTo) {
        Stop s = new Stop(aim[0], aim[1], 0, null);
        double len = Math.max(0.001, catTo), reach = Math.min(PLAN_REACH, len);
        double tx = cat.getX() + (s.x() - cat.getX()) / len * reach, tz = cat.getZ() + (s.z() - cat.getZ()) / len * reach;
        BlockPos target = BlockPos.containing(tx, groundAt(tx, tz), tz);
        net.minecraft.world.level.pathfinder.Path path = walkable(level, cat, target, (float) (reach + 8));
        if (path == null || path.getNodeCount() == 0) return null;
        var end = path.getEndNode();
        return end != null && flat(end.x + 0.5, end.z + 0.5, s) <= catTo - Math.min(PROGRESS, catTo * 0.5) ? path : null;
    }

    /**
     * Where the cat heads now: the next waypoint of the planned way to the stop, or the stop itself while the way is
     * being worked out, when it is near, or when there is no plan to go by.
     */
    private double[] aim(Stop s) {
        if (wayStop != next) {
            wayStop = next;
            way = null;
            wayAt = 0;
            planning = null;
            var w = com.ninjacat.greatjourney.clanlands.world.ClanWorld.current();
            if (w != null && flat(cat.getX(), cat.getZ(), s) > PLANNED) planning = Route.planAsync(w, cat.getX(), cat.getZ(), s.x(), s.z());
        }
        if (way == null && planning != null && planning.isDone()) {
            way = planning.getNow(null);
            planning = null;
            if (way != null) {
                // start from the waypoint nearest the cat: it has walked on while the way was planned
                double bestD = Double.MAX_VALUE;
                for (int i = 0; i < way.size(); i++) {
                    double d = Math.hypot(cat.getX() - way.get(i)[0], cat.getZ() - way.get(i)[1]);
                    if (d < bestD) {
                        bestD = d;
                        wayAt = i;
                    }
                }
                best = Double.MAX_VALUE;
                route = null;
            }
        }
        if (way == null || way.isEmpty()) return new double[] {s.x(), s.z()};
        while (wayAt < way.size() - 1 && Math.hypot(cat.getX() - way.get(wayAt)[0], cat.getZ() - way.get(wayAt)[1]) < WAYPOINT) {
            wayAt++;
            best = Double.MAX_VALUE;
            stuck = 0;
            fails = 0;
            route = null;
        }
        return way.get(wayAt);
    }

    private static final net.minecraft.world.level.pathfinder.PathFinder FINDER = finder();

    private static net.minecraft.world.level.pathfinder.PathFinder finder() {
        var walk = new net.minecraft.world.level.pathfinder.WalkNodeEvaluator();
        walk.setCanPassDoors(false);
        walk.setCanOpenDoors(false);
        walk.setCanFloat(true);                         // a swim only when there is no dry way: water costs the cat's malus
        return new net.minecraft.world.level.pathfinder.PathFinder(walk, MAX_NODES);
    }

    /**
     * A path for {@code cat} to {@code target} (or as near it as can be walked), over the land loaded around it,
     * searching up to {@code range} blocks out. The path keeps to what a mob can walk: one block up at a time, falls
     * no longer than a few blocks, no fire, lava, cactus or berry bushes, and water only when it must.
     */
    static net.minecraft.world.level.pathfinder.Path walkable(ServerLevel level, WCatEntity cat, BlockPos target, float range) {
        BlockPos from = cat.blockPosition();
        int r = (int) range + 8;
        var region = new net.minecraft.world.level.PathNavigationRegion(level, from.offset(-r, -r, -r), from.offset(r, r, r));
        return FINDER.findPath(region, cat, java.util.Set.of(target), range, 1, 1.0F);
    }

    /**
     * No walkable way on toward this stop from here. Within its reach already, the place is shown from where the cat
     * stands (it lies down a gorge or out in the water). Further off, a stop on the way is passed by (the walk goes on
     * to the next);
     * the last one is still reached: the cat finds its own way round and turns up ahead of the player, further on
     * where the near side has no firm ground nearer the place (a marsh pool, a river: the playtest's RiverClan guide
     * hopped back onto the same bank for minutes, five blocks on always landing where it stood).
     */
    private void blocked(Stop s, double catTo) {
        fails = 0;
        stuck = 0;
        route = null;
        if (way != null && wayAt < way.size() - 1) {
            wayAt++;                                     // a waypoint it cannot reach: on to the next one
            best = Double.MAX_VALUE;
            return;
        }
        if (catTo <= Math.max(s.radius(), TOGETHER)) {
            asNear = true;                               // within sight of it: the place is shown from here
            return;
        }
        if (next + 1 < stops.size()) {
            next++;
            best = Double.MAX_VALUE;
            if (onBlocked != null) onBlocked.accept(this);
            return;
        }
        if (++lastStopPuts > LAST_STOP_PUTS) {
            asNear = true;                               // no nearer: it is shown from here rather than hopping back and forth
            return;
        }
        BlockPos at = null;
        for (double d : new double[] {5, 10, 16, 24}) {
            BlockPos on = spotToward(player.getX(), player.getZ(), s.x(), s.z(), d);
            if (flat(on.getX() + 0.5, on.getZ() + 0.5, s) < catTo - 2) {
                at = on;
                break;
            }
        }
        if (at == null) at = spotToward(player.getX(), player.getZ(), s.x(), s.z(), 5);
        if (!put(at)) {
            asNear = true;                               // the player would see it vanish: it shows the place from here
            return;
        }
        best = Double.MAX_VALUE;
    }

    private void standAndWait(double toPlayer) {
        if (toPlayer > WALK_BACK) {
            // too far to stand and call: it comes back for them, on its own paws
            if (cat.getNavigation().isDone() || ++sinceOrder >= 4) {
                sinceOrder = 0;
                cat.getNavigation().moveTo(player, 1.3);
            }
            route = null;
        } else if (!waiting || !cat.getNavigation().isDone()) {
            cat.getNavigation().stop();
        }
        waiting = true;
        long now = level.getGameTime();
        if (onWait != null && toPlayer > WAIT + 3 && now - lastWaitSay > WAIT_SAY_EVERY) {
            lastWaitSay = now;
            onWait.accept(this);
        }
    }

    private void keepNear(double toPlayer) {
        waiting = toPlayer <= keep + 3;
        if (waiting) {
            if (toPlayer < keep - 2 || !cat.getNavigation().isDone()) cat.getNavigation().stop();
            return;
        }
        double len = Math.max(0.001, toPlayer);
        double tx = player.getX() + (cat.getX() - player.getX()) / len * keep, tz = player.getZ() + (cat.getZ() - player.getZ()) / len * keep;
        if (cat.getNavigation().isDone() || ++sinceOrder >= 4) {
            sinceOrder = 0;
            cat.getNavigation().moveTo(tx, groundAt(tx, tz), tz, 1.25);
        }
    }

    /** The player is far away: the cat is brought to them, unless they keep going away from where it leads. */
    private void catchUp() {
        double away = stops.isEmpty() ? 0 : flat(player.getX(), player.getZ(), stops.get(next));
        catchUps = lastCatchUp >= 0 && away > lastCatchUp + 16 ? catchUps + 1 : 0;
        if (catchUps >= GIVE_UP) {
            end(End.ABANDONED);
            return;
        }
        lastCatchUp = away;
        double tx = stops.isEmpty() ? cat.getX() : stops.get(next).x(), tz = stops.isEmpty() ? cat.getZ() : stops.get(next).z();
        BlockPos at = besidePlayer(tx, tz, stops.isEmpty() ? Math.min(keep, 6) : 4);
        route = null;
        if (!put(at)) cat.getNavigation().moveTo(player, 1.35);  // in sight: it runs to them instead
        best = stops.isEmpty() ? best : flat(at.getX() + 0.5, at.getZ() + 0.5, stops.get(next));
        stuck = 0;
    }

    /**
     * Whether the cat is in a fight: its target, still alive and close, attacked it or is going for it (its attack goal
     * needs the cat's legs: our steering waits). Prey it has merely spotted is no fight: a RiverClan guide eyeing a
     * fish it could not reach across the marsh stood frozen for fifteen minutes in the playtest.
     */
    public static boolean fighting(WCatEntity cat) {
        var t = cat.getTarget();
        return t != null && t.isAlive() && cat.distanceToSqr(t) < 16 * 16
                && (t == cat.getLastHurtByMob() || t instanceof net.minecraft.world.entity.Mob m && m.getTarget() == cat);
    }

    /**
     * Firm ground about {@code d} blocks from the player, on their own level (no higher than a step up, no lower than
     * a short drop): toward tx, tz if that side has such ground, else round them, else where they stand. The column
     * toward the place alone could be the top of a wall or a cliff beside them: the cat turned up up there, waiting.
     */
    private BlockPos besidePlayer(double tx, double tz, double d) {
        double base = Math.atan2(tz - player.getZ(), tx - player.getX());
        int feet = player.getBlockY();
        for (int i = 0; i < 12; i++) {
            double a = base + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * (Math.PI / 6);
            int x = (int) Math.floor(player.getX() + Math.cos(a) * d), z = (int) Math.floor(player.getZ() + Math.sin(a) * d);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos on = BookClans.safeSpot(level, x, z, 1);
            if (on.getY() <= feet + 1 && on.getY() >= feet - 3) return on;
        }
        return player.blockPosition();
    }

    /** Firm ground {@code d} blocks from x, z toward tx, tz. */
    private BlockPos spotToward(double x, double z, double tx, double tz, double d) {
        double len = Math.hypot(tx - x, tz - z);
        double sx = len < 0.001 ? x : x + (tx - x) / len * Math.min(d, len), sz = len < 0.001 ? z : z + (tz - z) / len * Math.min(d, len);
        return BookClans.safeSpot(level, (int) Math.floor(sx), (int) Math.floor(sz), 3);
    }

    /**
     * Moves the cat to {@code at}, but only where the player cannot see it go or arrive: a cat that vanishes and
     * reappears in plain view is no Clan cat (Kiki watched her guide do it, 2026-10-18). Returns whether it moved.
     */
    private boolean put(BlockPos at) {
        net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (inSight(cat.position()) || inSight(to)) return false;
        asNear = false;
        cat.getNavigation().stop();
        cat.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cat.fallDistance = 0;
        return true;
    }

    private boolean inSight(net.minecraft.world.phys.Vec3 at) {
        return inSight(player, at);
    }

    /**
     * Whether {@code player} could see a cat at {@code at}: near enough, with nothing solid between. Every cat that
     * walks with a player (a guide, a mentor, patrol warriors) is only ever moved where this is false.
     */
    public static boolean inSight(ServerPlayer player, net.minecraft.world.phys.Vec3 at) {
        if (player.level() != null && player.distanceToSqr(at) > 96 * 96) return false;
        ServerLevel level = player.serverLevel();
        var hit = level.clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(), at.add(0, 0.4, 0),
                net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** The walking height at a column near the cat: the ground if that land is loaded, else the cat's own height. */
    private double groundAt(double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!level.hasChunkAt(new BlockPos(bx, 0, bz))) return cat.getY();
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    private static double flat(double x, double z, Stop s) {
        return Math.hypot(x - s.x(), z - s.z());
    }

    private void end(End how) {
        if (ended) return;
        ended = true;
        BY_PLAYER.remove(player.getUUID(), this);
        BY_CAT.remove(catId, this);
        Entity e = level.getEntity(catId);
        if (e instanceof WCatEntity c) release(c);
        if (onEnd != null) onEnd.ended(this, how);
    }

    /** The server is stopping: every led cat is put back as it was, quietly. */
    private static void releaseAll() {
        for (Lead l : List.copyOf(BY_PLAYER.values())) {
            l.ended = true;
            if (l.level.getEntity(l.catId) instanceof WCatEntity c) release(c);
        }
        BY_PLAYER.clear();
        BY_CAT.clear();
    }

    /** Takes the cat out of its Clan routine: keeps its mode and home on it, marks it busy and gives it our steering. */
    private static void hold(WCatEntity cat) {
        if (!cat.getPersistentData().contains(SAVED)) {
            CompoundTag s = new CompoundTag();
            // the camp routine's FOLLOW or SIT is the routine's, not the cat's: a cat it had goes back to wandering
            boolean routine = cat.getTags().contains(com.ninjacat.greatjourney.clanlands.life.events.CampRoutine.TAG);
            s.putString("mode", cat.mode == null || routine ? WCatEntity.CatMode.WANDER.name() : cat.mode.name());
            if (cat.wanderCenter != null) s.putLong("wander", cat.wanderCenter.asLong());
            if (cat.hasHomePosition()) s.putLong("home", cat.getHomePosition().asLong());
            s.putBoolean("busy", cat.getTags().contains(BookClans.BUSY_TAG));   // someone else's busy: theirs to remove
            cat.getPersistentData().put(SAVED, s);
        }
        cat.addTag(BookClans.BUSY_TAG);
        cat.addTag(LED_TAG);
        cat.mode = WCatEntity.CatMode.WANDER;
        cat.setOrderedToSit(false);
        cat.setInSittingPose(false);
        if (cat.isResting()) cat.setResting(false, 0);
        cat.getNavigation().stop();
        boolean steered = false;
        for (var g : cat.goalSelector.getAvailableGoals()) steered |= g.getGoal() instanceof Steer;
        // ahead of every Warrior Cats Events goal that moves or turns the cat (wander, follow, patrols, hunting)
        if (!steered) cat.goalSelector.addGoal(-1, new Steer(cat));
    }

    /** Puts a cat back as it was before it led: its mode, wander centre and home, without our tags or steering. */
    static void release(WCatEntity cat) {
        CompoundTag s = cat.getPersistentData().getCompound(SAVED);
        if (!s.isEmpty()) {
            try {
                cat.mode = WCatEntity.CatMode.valueOf(s.getString("mode"));
            } catch (IllegalArgumentException ex) {
                cat.mode = WCatEntity.CatMode.WANDER;
            }
            if (s.contains("wander")) cat.wanderCenter = BlockPos.of(s.getLong("wander"));
            if (s.contains("home")) cat.setHomePosition(BlockPos.of(s.getLong("home")));
            if (!s.getBoolean("busy")) cat.removeTag(BookClans.BUSY_TAG);
        } else {
            cat.removeTag(BookClans.BUSY_TAG);
        }
        cat.getPersistentData().remove(SAVED);
        cat.removeTag(LED_TAG);
        cat.goalSelector.removeAllGoals(g -> g instanceof Steer);
        cat.getNavigation().stop();
    }

    /** A cat still marked as led when it loads (the server stopped or crashed mid-walk) is put back; a lost guide goes home. */
    private static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof WCatEntity cat) || BY_CAT.containsKey(cat.getUUID())) return;
        if (cat.getTags().contains(GUIDE_TAG) && e.getLevel() instanceof ServerLevel sl) {
            net.snowteb.warriorcats_events.clan.ClanData.get(sl.getServer().overworld()).removeClanCatFromAnyClan(cat);
            e.setCanceled(true);
            return;
        }
        if (cat.getTags().contains(LED_TAG)) release(cat);
    }

    /**
     * A guide can't be killed on the way to its camp: it still fights a fox that jumps it, but nothing hurts it while it
     * leads (only the void and /kill do). A lost guide would leave a kittypet alone in the woods with no way to camp.
     */
    private static void onHurt(LivingIncomingDamageEvent e) {
        if (e.getEntity() instanceof WCatEntity cat && cat.getTags().contains(GUIDE_TAG) && BY_CAT.containsKey(cat.getUUID())
                && !e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) e.setCanceled(true);
    }

    /** Our steering: while the cat leads, nothing else moves or turns it; it looks back at a player it waits for. */
    private static final class Steer extends Goal {
        private final WCatEntity cat;

        Steer(WCatEntity cat) {
            this.cat = cat;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return BY_CAT.containsKey(cat.getUUID()) && !fighting(cat);       // a fight is Warrior Cats Events' to steer
        }

        @Override
        public boolean isInterruptable() {
            return false;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            Lead l = BY_CAT.get(cat.getUUID());
            if (l != null && (l.waiting || cat.getNavigation().isDone())) cat.getLookControl().setLookAt(l.player, 30, 30);
        }
    }
}
