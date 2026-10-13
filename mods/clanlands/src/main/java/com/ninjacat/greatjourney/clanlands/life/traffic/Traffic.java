package com.ninjacat.greatjourney.clanlands.life.traffic;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.life.ChunkWait;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Twoleg monsters on the Thunderpaths. Once a second, each stretch of road with a cat (a player or a Clan cat) near
 * it has a chance of a monster: it starts far up the road out of sight, growls closer, roars past and is gone. Each
 * stretch rolls and cools down on its own, so two players by the same stretch share its monsters rather than
 * doubling them, and players far apart along a long road each get their own. The great highway is busy; the forest
 * Thunderpaths now and then; the old Thunderpath never. Nothing is saved: on load, any monster left over from a
 * crash is cleared away.
 */
public final class Traffic {
    private Traffic() {}

    /** How close to a road's edge a cat must be for monsters to come. */
    static final double NEAR = 28;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final List<Monster> ACTIVE = new ArrayList<>();
    private static final Set<UUID> PARTS = new HashSet<>();
    /** Per road, per stretch: the game time before which that stretch sends no new monster. */
    private static final Map<Road, Map<Integer, Long>> QUIET_UNTIL = new IdentityHashMap<>();
    /** Per player: the game time of the last reek line, so it is not said every time a monster passes. */
    private static final Map<UUID, Long> LAST_REEK = new HashMap<>();
    /** Close behind a slower monster in its lane, a monster slows to its pace. */
    static final double FOLLOW_GAP = 4;

    public static void init(IEventBus modBus) {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, TrafficConfig.SPEC, "clanlands-traffic-server.toml");
        NeoForge.EVENT_BUS.addListener(Traffic::tick);
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            clear();
            for (ServerLevel l : e.getServer().getAllLevels()) sweep(l);
        });
        NeoForge.EVENT_BUS.addListener(Traffic::joined);
        NeoForge.EVENT_BUS.addListener(Traffic::left);
        NeoForge.EVENT_BUS.addListener(Traffic::sweepTick);
    }

    /**
     * A part leaving its level without being discarded (its chunk unloaded mid-pass, so it is saved with the chunk)
     * is forgotten here: its monster is over on the next tick, and the saved copy is dropped when it loads again.
     */
    private static void left(EntityLeaveLevelEvent e) {
        Entity en = e.getEntity();
        // a part a live monster still owns is not forgotten here (a section losing tracking is not a part being saved
        // away): if it really is gone its monster sees that on its next tick and ends, which forgets it
        if (!e.getLevel().isClientSide() && en instanceof Display.BlockDisplay && en.getTags().contains(Monster.TAG) && !owns(en.getUUID())) {
            PARTS.remove(en.getUUID());
        }
    }

    private static boolean owns(UUID part) {
        for (Monster m : ACTIVE) for (var d : m.parts()) if (d.getUUID().equals(part)) return true;
        return false;
    }

    /** How often (ticks) the loaded world is swept for monster parts with no monster driving them. */
    static final int SWEEP_EVERY = 40;

    /** Runs whether or not traffic is on or the world is a Clan world, so no part is ever left frozen on a road. */
    private static void sweepTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % SWEEP_EVERY != 0) return;
        for (ServerLevel l : e.getServer().getAllLevels()) sweep(l);
    }

    /** Discards every monster part in the loaded level that no active monster owns; returns how many. */
    public static int sweep(ServerLevel level) {
        Set<UUID> owned = new HashSet<>();
        for (Monster m : ACTIVE) for (var d : m.parts()) owned.add(d.getUUID());
        List<Entity> stray = new ArrayList<>();
        for (Entity en : level.getAllEntities()) {
            if (en instanceof Display.BlockDisplay && en.getTags().contains(Monster.TAG) && !owned.contains(en.getUUID())) stray.add(en);
        }
        for (Entity en : stray) {
            PARTS.remove(en.getUUID());
            en.discard();
        }
        return stray.size();
    }

    /** A monster's display coming back from a save (after a crash) is not one of ours any more: drop it. */
    private static void joined(EntityJoinLevelEvent e) {
        Entity en = e.getEntity();
        if (!e.getLevel().isClientSide() && en instanceof Display.BlockDisplay && en.getTags().contains(Monster.TAG)
                && !PARTS.contains(en.getUUID())) {
            e.setCanceled(true);
        }
    }

    public static void clear() {
        for (Monster m : ACTIVE) m.remove();
        ACTIVE.clear();
        PARTS.clear();
        QUIET_UNTIL.clear();
        LAST_REEK.clear();
    }

    private static void tick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        ServerLevel level = server.overworld();
        ClanWorld w = ClanWorld.current();
        if (w == null || !ClanWorld.isClanWorld(level)) return;
        if (!TrafficConfig.ENABLED.get()) {
            if (!ACTIVE.isEmpty()) clear();
            return;
        }
        drive(level);
        if (server.getTickCount() % 20 == 0) spawnNearCats(level, w);
    }

    /** One tick for every monster on the roads: keep their distance, drive, and clear away those that are gone. */
    private static void drive(ServerLevel level) {
        float damage = TrafficConfig.DAMAGE.get().floatValue();
        keepDistance();
        for (Iterator<Monster> it = ACTIVE.iterator(); it.hasNext(); ) {
            Monster m = it.next();
            boolean alive;
            try {
                alive = m.tick(level, damage);
            } catch (RuntimeException ex) {
                // a monster that throws is over: its parts must not be left standing on the road
                LOGGER.warn("A monster failed to drive and was removed", ex);
                alive = false;
            }
            if (!alive) {
                for (var d : m.parts()) PARTS.remove(d.getUUID());
                m.remove();
                it.remove();
            }
        }
    }

    /** Monsters never drive through each other: one close behind a slower one in its lane takes its pace. */
    private static void keepDistance() {
        for (Monster m : ACTIVE) {
            m.pace = m.speed;
            for (Monster o : ACTIVE) {
                if (o == m || o.road != m.road || o.dir != m.dir || o.lane != m.lane) continue;
                double gap = TrafficMath.ahead(m.along, m.dir, o.along) - (m.length + o.length) / 2;
                if (gap > -2 && gap < FOLLOW_GAP + 6) m.pace = Math.min(m.pace, gap < FOLLOW_GAP ? o.speed * 0.9 : o.speed);
            }
        }
    }

    private static void spawnNearCats(ServerLevel level, ClanWorld w) {
        if (ACTIVE.size() >= TrafficConfig.MAX_MONSTERS.get()) return;
        // every cat once, though two players' surroundings overlap
        Map<UUID, Entity> cats = new HashMap<>();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            cats.put(p.getUUID(), p);
            for (WCatEntity c : level.getEntitiesOfClass(WCatEntity.class, new AABB(p.blockPosition()).inflate(64))) cats.put(c.getUUID(), c);
        }
        if (cats.isEmpty()) return;
        // the stretches of road with a cat by them, and where along each the cats are
        Map<Road, Map<Integer, List<Double>>> near = new IdentityHashMap<>();
        for (ClanMap map : w.maps()) {
            for (Road road : map.roads()) {
                if (chance(road.kind()) <= 0) continue;
                for (Entity c : cats.values()) {
                    double[] n = road.path().nearest(c.getX(), c.getZ(), road.half() + NEAR);
                    if (n[0] > road.half() + NEAR) continue;
                    near.computeIfAbsent(road, k -> new HashMap<>()).computeIfAbsent(TrafficMath.segment(n[1]), k -> new ArrayList<>()).add(n[1]);
                }
            }
        }
        RandomSource r = level.getRandom();
        long now = level.getGameTime();
        for (Map.Entry<Road, Map<Integer, List<Double>>> entry : near.entrySet()) {
            Road road = entry.getKey();
            for (Map.Entry<Integer, List<Double>> seg : entry.getValue().entrySet()) {
                if (ACTIVE.size() >= TrafficConfig.MAX_MONSTERS.get()) return;
                if (!mayRoll(road, seg.getKey(), now) || r.nextDouble() >= chance(road.kind())) continue;
                List<Double> at = seg.getValue();
                double along = at.get(r.nextInt(at.size()));
                if (nearOnRoad(road, along, TrafficMath.SEGMENT) >= TrafficConfig.MAX_PER_ROAD.get()) continue;
                if (spawn(level, road, along, r)) quiet(road, seg.getKey(), now, road.kind() == Road.Kind.HIGHWAY ? 30 + r.nextInt(40) : 80 + r.nextInt(120));
            }
        }
    }

    /** Whether that stretch of road may roll for a monster now (it is not still quiet after the last one). */
    public static boolean mayRoll(Road road, int segment, long now) {
        Map<Integer, Long> m = QUIET_UNTIL.get(road);
        Long until = m == null ? null : m.get(segment);
        return until == null || now >= until;
    }

    /** That stretch of road sends nothing more for {@code ticks}. */
    public static void quiet(Road road, int segment, long now, int ticks) {
        QUIET_UNTIL.computeIfAbsent(road, k -> new HashMap<>()).put(segment, now + ticks);
    }

    /** The chance each second that a stretch of this kind of road with a cat by it sends a monster. */
    public static double chance(Road.Kind kind) {
        return switch (kind) {
            case HIGHWAY -> TrafficConfig.HIGHWAY_CHANCE.get();
            case THUNDERPATH -> TrafficConfig.THUNDERPATH_CHANCE.get();
            case OLD_THUNDERPATH -> 0;
        };
    }

    /** Monsters on this road within {@code within} blocks (along it) of a spot. */
    private static int nearOnRoad(Road road, double along, double within) {
        int n = 0;
        for (Monster m : ACTIVE) if (m.road == road && Math.abs(m.along - along) <= within) n++;
        return n;
    }

    /** Whether a monster starting {@code start} along a road in that direction and lane would land on another. */
    private static boolean laneBusy(Road road, int dir, int lane, double start) {
        for (Monster m : ACTIVE) {
            if (m.road == road && m.dir == dir && m.lane == lane && Math.abs(m.along - start) < 30) return true;
        }
        return false;
    }

    /** The smell a monster leaves as it passes a player, once in a while. */
    static void reek(ServerPlayer p) {
        long now = p.serverLevel().getGameTime();
        Long last = LAST_REEK.get(p.getUUID());
        if (last != null && now - last < 20 * 60 * 8) return;
        LAST_REEK.put(p.getUUID(), now);
        String[] says = {
                "The monster's reek hangs in the air, sour and choking. Your eyes water.",
                "Hot wind and a foul Twoleg stench wash over you as the monster roars away.",
                "The ground stops shaking. Only the stink of the monster is left, burning your nose.",
                "Your fur is still on end. The monster's sharp, bitter smell clings to the Thunderpath."};
        Dialog.subtitle(p, Dialog.Line.narrate(says[p.getRandom().nextInt(says.length)]));
    }

    /**
     * Sends a monster at a cat {@code watchAlong} blocks along the road: it starts 110–150 blocks up the road (or at
     * the road's end if that is nearer, but never closer than 40 blocks) and drives past.
     */
    static boolean spawn(ServerLevel level, Road road, double watchAlong, RandomSource r) {
        double[] plan = plan(road, watchAlong, r);
        return plan != null && begin(level, road, watchAlong, (int) plan[0], -1, plan[1], r);
    }

    /** {dir, start}: which way a monster passing {@code watchAlong} drives and where along the road it starts; null if no run-up fits. */
    private static double[] plan(Road road, double watchAlong, RandomSource r) {
        double len = road.path().length();
        int dir = r.nextBoolean() ? 1 : -1;
        double start = watchAlong - dir * (110 + r.nextDouble() * 40);
        if (start < 0 || start > len) {
            start = dir > 0 ? 0 : len;
            if (Math.abs(start - watchAlong) < 40) {
                dir = -dir;
                start = watchAlong - dir * (110 + r.nextDouble() * 40);
                if (start < 0 || start > len) return null;
            }
        }
        return new double[] {dir, start};
    }

    /** Puts a monster on the road at {@code start} (in {@code lane}, or a random one if negative); false if that spot is not loaded or its lane is busy. */
    private static boolean begin(ServerLevel level, Road road, double watchAlong, int dir, int lane, double start, RandomSource r) {
        if (lane < 0) lane = road.kind() == Road.Kind.HIGHWAY ? r.nextInt(2) : 0;
        if (laneBusy(road, dir, lane, start)) return false;
        boolean truck = road.kind() == Road.Kind.HIGHWAY && r.nextDouble() < 0.3;
        Monster m = new Monster(road, dir, lane, truck, start, watchAlong, r);
        boolean ok = false;
        try {
            ok = m.spawn(level, r, PARTS);
        } finally {
            if (ok) ACTIVE.add(m);
            else {
                // a failed or interrupted build must not leave half a monster standing on the road
                for (var d : m.parts()) PARTS.remove(d.getUUID());
                m.remove();
            }
        }
        return ok;
    }

    /** Sends a monster down the Thunderpath nearest to (x, z), past that spot. */
    private static boolean sendAt(CommandSourceStack src, ServerLevel level, double x, double z) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return false;
        Road best = null;
        double bestDist = Double.MAX_VALUE, bestAlong = 0;
        for (ClanMap map : w.maps()) {
            for (Road road : map.roads()) {
                if (road.kind() == Road.Kind.OLD_THUNDERPATH) continue;
                double[] n = road.path().nearest(x, z, 400);
                if (n[0] < bestDist) {
                    bestDist = n[0];
                    bestAlong = n[1];
                    best = road;
                }
            }
        }
        if (best == null || bestDist > 400) {
            src.sendFailure(Component.literal("No Thunderpath within 400 blocks."));
            return false;
        }
        boolean ok = sendAtSpot(level, best, x, z) || sendPast(level, best, bestAlong);
        Road r = best;
        double along = bestAlong;
        src.sendSuccess(() -> Component.literal(ok ? "A monster is coming along the " + r.kind() + " at " + (int) along + ". Listen!"
                : "Couldn't send a monster here (the road up ahead isn't loaded)."), false);
        return ok;
    }

    /** Sends a monster down {@code road} past the spot {@code along} blocks along it (the guided tour's roadside stops). */
    public static boolean sendPast(ServerLevel level, Road road, double along) {
        RandomSource r = level.getRandom();
        double[] plan = plan(road, along, r);
        if (plan == null) return false;
        return run(level, road, along, (int) plan[0], -1, plan[1], r);
    }

    /**
     * Sends a monster at whatever stands at (x, z) on the road: it picks the direction and lane whose line of travel
     * runs through that spot, so what stands in the lane is struck (sendPast picks them at random, and half the time
     * a monster passes in the other lane). False if no run-up in that direction fits or it cannot begin.
     */
    public static boolean sendAtSpot(ServerLevel level, Road road, double x, double z) {
        double[] aim = aim(road, x, z);
        RandomSource r = level.getRandom();
        int dir = (int) aim[0];
        double along = aim[2];
        double start = along - dir * (110 + r.nextDouble() * 40);
        double len = road.path().length();
        if (start < 0 || start > len) start = dir > 0 ? 0 : len;
        if (Math.abs(start - along) < 40) return false;
        return run(level, road, along, dir, (int) aim[1], start, r);
    }

    /** {dir, lane, along}: the direction and lane of a monster that passes right through (x, z), and the spot along the road nearest it. */
    public static double[] aim(Road road, double x, double z) {
        double along = road.path().nearest(x, z, 1e9)[1];
        int lanes = road.kind() == Road.Kind.HIGHWAY ? 2 : 1;
        double[] best = {1, 0, along};
        double bestDist = Double.MAX_VALUE;
        for (int dir = -1; dir <= 1; dir += 2) {
            double[] p = road.path().pointAt(along), q = road.path().pointAt(along + dir * 2.0);
            double fx = q[0] - p[0], fz = q[1] - p[1], fl = Math.hypot(fx, fz);
            if (fl < 1e-6) continue;
            double[] l = TrafficMath.left(fx / fl, fz / fl);
            for (int lane = 0; lane < lanes; lane++) {
                double off = TrafficMath.laneOffset(road.kind(), road.half(), lane);
                double d = Math.hypot(p[0] + l[0] * off - x, p[1] + l[1] * off - z);
                if (d < bestDist) {
                    bestDist = d;
                    best = new double[] {dir, lane, along};
                }
            }
        }
        return best;
    }

    /** Begins the run now if its start is loaded; else once it is (a few seconds at most), nearer the cat if it never is. */
    private static boolean run(ServerLevel level, Road road, double along, int dir, int lane, double start, RandomSource r) {
        double[] p = road.path().pointAt(start);
        if (level.isLoaded(BlockPos.containing(p[0], 0, p[1]))) return begin(level, road, along, dir, lane, start, r);
        // the start of the run-up is not loaded: load it first (the tickets are released as the job runs, and the monster
        // then holds the land it drives over itself), and if it still is not, start at the nearest loaded point toward the cat
        ChunkWait.whenLoaded(level, p[0], p[1], 16, 100, () -> {
            double s = start;
            while (Math.abs(s - along) > 40 && !level.isLoaded(BlockPos.containing(road.path().pointAt(s)[0], 0, road.path().pointAt(s)[1]))) {
                s += dir * 16;
            }
            begin(level, road, along, dir, lane, s, r);
        });
        return true;
    }

    /** How many monsters are on the roads (for the commands and GameTests). */
    public static int active() {
        return ACTIVE.size();
    }

    /**
     * Puts a monster on {@code road} right at {@code along}, driving {@code dir}, with no run-up (for GameTests, whose
     * world has no Clan roads and only a little land loaded).
     */
    public static boolean placeForTest(ServerLevel level, Road road, double along, int dir) {
        return placeForTest(level, road, along, dir, 0);
    }

    /** As {@link #placeForTest(ServerLevel, Road, double, int)}, in a chosen lane. */
    public static boolean placeForTest(ServerLevel level, Road road, double along, int dir, int lane) {
        Monster m = new Monster(road, dir, lane, false, along, along, level.getRandom());
        if (!m.spawn(level, level.getRandom(), PARTS)) return false;
        ACTIVE.add(m);
        return true;
    }

    /** While set, monsters do not move (for GameTests of the lifetime limit). */
    static boolean frozenForTest;

    /** Freezes or releases every monster in place (for GameTests). */
    public static void freezeForTest(boolean frozen) {
        frozenForTest = frozen;
    }

    /** Forgets every monster without removing its parts, as if their controller were lost (for GameTests of the sweep). */
    public static void loseControllersForTest() {
        ACTIVE.clear();
    }

    /** Drives every monster one tick, as the server does each tick in a Clan world (for GameTests). */
    public static void driveForTest(ServerLevel level) {
        drive(level);
    }

    /** Whether a block display entity is one of the monsters now on the roads (for GameTests). */
    public static boolean isLivePart(Entity e) {
        return PARTS.contains(e.getUUID());
    }

    /** The entity tag every monster part carries. */
    public static final String PART_TAG = Monster.TAG;

    /** /greatjourney monster spawn|clear (operators): send a monster down the nearest road now, or clear them all. */
    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("monster").requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (p.level().dimension() != Level.OVERWORLD) return 0;
                    return sendAt(c.getSource(), p.serverLevel(), p.getX(), p.getZ()) ? 1 : 0;
                }))
                .then(Commands.literal("spawnat").then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(c -> {
                            int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "x");
                            int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "z");
                            return sendAt(c.getSource(), c.getSource().getServer().overworld(), x, z) ? 1 : 0;
                        }))))
                .then(Commands.literal("count").executes(c -> {
                    int n = ACTIVE.size();
                    c.getSource().sendSuccess(() -> Component.literal(n + " monsters on the roads"), false);
                    return n;
                }))
                .then(Commands.literal("clear").executes(c -> {
                    int n = ACTIVE.size();
                    clear();
                    c.getSource().sendSuccess(() -> Component.literal("Cleared " + n + " monsters."), false);
                    return n;
                })));
    }
}
