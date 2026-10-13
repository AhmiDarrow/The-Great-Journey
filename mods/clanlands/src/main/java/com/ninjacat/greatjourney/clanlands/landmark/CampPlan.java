package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.RandomSource;

/**
 * Where everything in a Clan camp goes, as the books describe each camp: the open clearing, the barrier round it
 * with its one way in, every den by what it is for, and the camp's great rock. Worked out from the camp's own seed
 * and the planned terrain only, so the chunk painter ({@link Camps}) and the code that settles the Clan's cats into
 * their dens agree on every position, whichever chunk or tick asks first.
 */
public final class CampPlan {
    public enum Style {
        /** Lake ThunderClan: the old stone quarry, sheer walls, Highledge and a thorn tunnel at the foot of a cut. */
        STONE_HOLLOW,
        /** Forest ThunderClan: the camp at the bottom of the ravine, ringed with bramble and gorse; Highrock. */
        RAVINE,
        /** WindClan: a hollow on the open moor ringed with gorse; Tallrock; dens dug into the hollow's sides. */
        MOOR,
        /** Lake RiverClan: the dry point between two streams, walled with reeds, bramble and willow. */
        RIVER,
        /** Forest RiverClan: an island in a reedbed, the river all round, a ford of stones to the bank. */
        ISLAND,
        /** ShadowClan: a dip deep in the pines inside a dense bramble wall, entered by one tunnel. */
        PINES,
        /** Forest ShadowClan: the same, on wetter ground with puddles. */
        MARSH_PINES
    }

    public enum DenKind { LEADER, MEDICINE, NURSERY, WARRIORS, APPRENTICES, ELDERS }

    /**
     * One den. {@code x, z} is the middle of the space a cat sleeps in; {@code door} the bearing (radians) its
     * opening faces. A burrow is dug into the ground from a mouth {@code length} blocks short of its middle, along
     * {@code door + PI}; a {@code cleft} is cut into rock the same way. {@code y} is the level a cat stands at inside
     * a cave, cleft or burrow (they are dug to the plan), or {@link #SURFACE} for a den built on the ground.
     */
    public record Den(DenKind kind, double x, double z, double radius, int height, double door, Shape shape, double length, int y) {
        public enum Shape { DOME, BURROW, CLEFT, CAVE }
    }

    public static final int SURFACE = Integer.MIN_VALUE;

    public final Landmark landmark;
    public final Style style;
    public final long seed;
    public final double cx, cz;
    /** Planned floor height at the camp's middle. */
    public final int floorY;
    /** Radius of the open floor, and of the barrier band round it (0 where cliffs are the barrier). */
    public final double clearing, wallIn, wallOut;
    public final int wallHeight;
    /** Bearing of the one way in, and half its width in blocks. */
    public final double gate, gateHalf;
    /** Radius of the cliff foot (stone hollow, ravine), else 0. */
    public final double cliff;
    /** Highledge's bearing (stone hollow). */
    public final double ledge;
    /** Bearing of the tunnel to the dirtplace (stone hollow), else NaN. */
    public final double dirtplace;
    /** The camp's great rock {x, z, radius, height}: Highrock, Tallrock, ShadowClan's leader rock; null for none. */
    public final double[] rock;
    /** A second rock {x, z, radius, height} holding the medicine den's cleft (ravine), else null. */
    public final double[] medicineRock;
    public final List<Den> dens;

    private static final Map<Long, CampPlan> CACHE = new ConcurrentHashMap<>();

    /** The plan of a camp landmark in the current world, or null when it is not a camp. */
    public static CampPlan of(Landmark l) {
        ClanWorld w = ClanWorld.current();
        return w == null ? null : of(w, l);
    }

    public static CampPlan of(ClanWorld w, Landmark l) {
        Style style = styleOf(w, l);
        if (style == null) return null;
        return CACHE.computeIfAbsent(w.seed * 1_000_003L ^ l.seed(), k -> new CampPlan(w, l, style));
    }

    static Style styleOf(ClanWorld w, Landmark l) {
        boolean forest = w.forest.landmarks().contains(l);
        return switch (l.kind()) {
            case STONE_HOLLOW -> Style.STONE_HOLLOW;
            case THUNDERCLAN_RAVINE -> Style.RAVINE;
            case WINDCLAN_HOLLOW -> Style.MOOR;
            case RIVERCLAN_CAMP -> forest ? Style.ISLAND : Style.RIVER;
            case SHADOWCLAN_CAMP -> forest ? Style.MARSH_PINES : Style.PINES;
            default -> null;
        };
    }

    private CampPlan(ClanWorld w, Landmark l, Style style) {
        this.landmark = l;
        this.style = style;
        this.seed = l.seed() ^ 0x5CA4F00DL;
        this.cx = l.x();
        this.cz = l.z();
        RandomSource r = RandomSource.create(seed);
        this.floorY = (int) Math.round(w.sample(cx, cz).height());
        // sizes follow the terrain the layout dug for each camp (LakeLands / OldForest "hollows")
        double cl = 0, in = 0, out = 0, cliffR = 0;
        int wh = 0;
        switch (style) {
            case STONE_HOLLOW -> { cliffR = l.radius() - 16; cl = cliffR - 2.5; }
            case RAVINE -> { cliffR = l.radius() - 28; in = cliffR - 7; out = cliffR - 3; wh = 4; cl = in - 1; }
            case MOOR -> { in = l.radius() - 9; out = l.radius() - 5.5; wh = 3; cl = in - 1; cliffR = 15; }
            case PINES, MARSH_PINES -> { in = l.radius() - 10; out = l.radius() - 6; wh = 4; cl = in - 1; }
            case RIVER -> { in = l.radius() - 9; out = l.radius() - 6; wh = 3; cl = in - 1; }
            case ISLAND -> { in = 17; out = 20.5; wh = 3; cl = 16; }
        }
        this.clearing = cl;
        this.wallIn = in;
        this.wallOut = out;
        this.wallHeight = wh;
        this.cliff = cliffR;
        this.ledge = l.facing();
        this.gateHalf = style == Style.ISLAND || style == Style.RIVER ? 1.6 : 1.5;
        this.gate = chooseGate(w, r);
        // the stone hollow's dirtplace: a short tunnel into the quarry wall a little way round from the entrance, on
        // the side away from Highledge, to a small dead-end hollow in the rock
        this.dirtplace = style == Style.STONE_HOLLOW ? gate - Math.signum(diff(ledge, gate)) * 0.75 : Double.NaN;

        // which way round the camp the dens go: mirrored in half the worlds
        double side = r.nextBoolean() ? 1 : -1;
        List<Den> list = new ArrayList<>();
        double[] rk = null, med = null;
        switch (style) {
            case STONE_HOLLOW -> {
                // the leader's den is a cave behind Highledge; the elders' den in honeysuckle beneath it
                double ledgeX = cx + Math.cos(ledge) * (cliff + 3), ledgeZ = cz + Math.sin(ledge) * (cliff + 3);
                list.add(new Den(DenKind.LEADER, ledgeX, ledgeZ, 2.2, 3, ledge + Math.PI, Den.Shape.CAVE, 4, ledgeTop() + 1));
                double eldA = ledge + side * 0.30;
                list.add(dome(DenKind.ELDERS, eldA, cl - 4.5, 3.4, 3, r));
                // the others round the walls, clear of the entrance, of Highledge and of the dirtplace tunnel
                double[] free = freeArcs(new double[] {gate, 0.55}, new double[] {ledge, 0.55}, new double[] {ledge - side * 0.45, 0.25},
                        new double[] {dirtplace, 0.35});
                double medA = pick(free, 0.30, r), nurA = pick(free, 0.55, r), warA = pick(free, 0.08, r), appA = pick(free, 0.92, r);
                list.add(new Den(DenKind.MEDICINE, cx + Math.cos(medA) * (cliff + 4), cz + Math.sin(medA) * (cliff + 4),
                        2.4, 3, medA + Math.PI, Den.Shape.CLEFT, 6, floorY + 1));
                list.add(dome(DenKind.NURSERY, nurA, cl - 5, 4.2, 4, r));
                list.add(dome(DenKind.WARRIORS, warA, cl - 5.5, 4.6, 4, r));
                list.add(dome(DenKind.APPRENTICES, appA, cl - 4.5, 3.4, 3, r));
            }
            case RAVINE -> {
                double rockA = gate + Math.PI + (r.nextDouble() - 0.5) * 0.4, rockR = 3.4 + r.nextDouble() * 0.6;
                double rockD = Math.max(cl * 0.5, rockR + 4.5);
                rk = new double[] {cx + Math.cos(rockA) * rockD, cz + Math.sin(rockA) * rockD, rockR, 7 + r.nextInt(2)};
                // the leader's den: a lichen-hung hollow at Highrock's foot, facing the clearing
                double toMid = rockA + Math.PI;
                list.add(new Den(DenKind.LEADER, rk[0] + Math.cos(toMid) * (rockR - 1.2), rk[1] + Math.sin(toMid) * (rockR - 1.2),
                        1.6, 2, toMid, Den.Shape.CAVE, 0, ground(w, rk[0], rk[1]) + 1));
                // Spottedleaf's den: a fern tunnel to a cleft in a great rock set into the camp wall
                double medA = gate + side * (1.15 + r.nextDouble() * 0.2);
                double medR = 3.0 + r.nextDouble() * 0.5;
                // set against the wall's inner face, not through it: a rock reaching across the hedge is a stair out
                double medD = in - medR + 0.8;
                med = new double[] {cx + Math.cos(medA) * medD, cz + Math.sin(medA) * medD, medR, 5};
                list.add(new Den(DenKind.MEDICINE, med[0], med[1], 1.6, 2, medA + Math.PI, Den.Shape.CLEFT, 3.5,
                        ground(w, med[0], med[1]) + 1));
                double ring = cl - 3.3;
                list.add(dome(DenKind.NURSERY, rockA - side * 0.85, ring - 0.2, 3.5, 3, r));
                list.add(dome(DenKind.ELDERS, rockA + side * 0.95, ring, 3.0, 3, r));
                list.add(dome(DenKind.WARRIORS, gate + side * 0.6, ring - 0.3, 3.6, 3, r));                   // by the way in
                list.add(dome(DenKind.APPRENTICES, gate - side * 1.0, ring, 3.0, 3, r));
            }
            case MOOR -> {
                double rockA = gate + Math.PI + (r.nextDouble() - 0.5) * 0.5;
                rk = new double[] {cx + Math.cos(rockA) * 12.5, cz + Math.sin(rockA) * 12.5, 2.6 + r.nextDouble() * 0.5, 9 + r.nextInt(3)};
                // dens are burrows dug into the hollow's sides, the leader's beside Tallrock
                double mouth = cliff + 2;
                list.add(burrow(w, DenKind.LEADER, rockA + side * 0.30, mouth, 6));
                list.add(burrow(w, DenKind.NURSERY, rockA - side * 0.55, mouth, 7));
                list.add(burrow(w, DenKind.MEDICINE, gate + side * 1.25, mouth, 6));
                list.add(burrow(w, DenKind.ELDERS, rockA + side * 1.15, mouth, 6));
                list.add(burrow(w, DenKind.WARRIORS, gate - side * 0.85, mouth, 8));
                list.add(burrow(w, DenKind.APPRENTICES, gate + side * 0.75, mouth, 6));
            }
            case PINES, MARSH_PINES -> {
                double rockA = gate + Math.PI + (r.nextDouble() - 0.5) * 0.6, rockR = 3.4 + r.nextDouble() * 0.6;
                double rockD = cl - rockR - 2.5;                                          // a stride clear of the wall
                rk = new double[] {cx + Math.cos(rockA) * rockD, cz + Math.sin(rockA) * rockD, rockR, 5 + r.nextInt(2)};
                // the leader sleeps in a hollow under the great rock
                double toMid = rockA + Math.PI;
                list.add(new Den(DenKind.LEADER, rk[0] + Math.cos(toMid) * (rockR - 1.4), rk[1] + Math.sin(toMid) * (rockR - 1.4),
                        1.6, 2, toMid, Den.Shape.CAVE, 0, ground(w, rk[0], rk[1]) + 1));
                double ring = cl - 4.8;
                list.add(dome(DenKind.NURSERY, rockA - side * 0.75, ring, 4.2, 4, r));
                list.add(dome(DenKind.MEDICINE, rockA + side * 0.80, ring + 0.5, 3.3, 3, r));
                list.add(dome(DenKind.ELDERS, rockA + side * 1.55, ring, 3.6, 3, r));        // under a fallen pine's roots
                list.add(dome(DenKind.WARRIORS, gate - side * 0.85, ring - 0.5, 4.6, 4, r));
                list.add(dome(DenKind.APPRENTICES, gate + side * 0.95, ring, 3.6, 3, r));
            }
            case RIVER, ISLAND -> {
                double ring = cl - (style == Style.ISLAND ? 3.8 : 4.4);
                // round from the way in: warriors guarding it, then the elders, the nursery furthest in, the
                // leader's bush beside it, the medicine den, and the apprentices back by the entrance
                list.add(dome(DenKind.WARRIORS, gate + side * 0.95, ring - 0.3, 3.8, 3, r));
                list.add(dome(DenKind.ELDERS, gate + side * 1.95, ring, 3.2, 3, r));
                list.add(dome(DenKind.NURSERY, gate + side * 2.85, ring, 3.6, 3, r));                         // a bramble thicket
                list.add(dome(DenKind.LEADER, gate - side * 2.85, ring, 3.0, 3, r));                          // under a bush
                list.add(dome(DenKind.MEDICINE, gate - side * 1.95, ring, 3.0, 3, r));
                list.add(dome(DenKind.APPRENTICES, gate - side * 0.95, ring, 3.2, 3, r));
            }
        }
        this.rock = rk;
        this.medicineRock = med;
        this.dens = List.copyOf(list);
        {
            StringBuilder sb = new StringBuilder();
            for (Den d : dens) sb.append(' ').append(d.kind()).append('@').append((int) Math.floor(d.x())).append(',').append((int) Math.floor(d.z()));
            // (and the barrier's exact reach, for the world inspector: the quarry's sheer wall runs cliff - 1 to cliff + 2.5)
            double[] barrier = barrier();
            com.mojang.logging.LogUtils.getLogger().info("Clanlands camp (seed {}) {} at {} {}: way in at {} degrees (outside {} {}), barrier {} to {} round {} {}, dens{}",
                    w.seed, style, (int) cx, (int) cz, Math.round(Math.toDegrees(gate)), (int) (cx + Math.cos(gate) * (Math.max(wallOut, cliff) + 4)),
                    (int) (cz + Math.sin(gate) * (Math.max(wallOut, cliff) + 4)), String.format(java.util.Locale.ROOT, "%.1f", barrier[0]),
                    String.format(java.util.Locale.ROOT, "%.1f", barrier[1]), String.format(java.util.Locale.ROOT, "%.1f", cx),
                    String.format(java.util.Locale.ROOT, "%.1f", cz), sb);
        }
    }

    private Den dome(DenKind kind, double a, double d, double radius, int height, RandomSource r) {
        double jitter = (r.nextDouble() - 0.5) * 0.12;
        double x = cx + Math.cos(a + jitter) * d, z = cz + Math.sin(a + jitter) * d;
        return new Den(kind, x, z, radius, height, Math.atan2(cz - z, cx - x), Den.Shape.DOME, 0, SURFACE);
    }

    /** A burrow whose mouth opens {@code mouth} blocks out from the middle, dug {@code length} blocks outward. */
    private Den burrow(ClanWorld w, DenKind kind, double a, double mouth, double length) {
        double mid = mouth + length - 2;
        // dug level into the hollow's side from the mouth, so it runs ever deeper under the rising slope
        int y = (int) Math.round(w.sample(cx + Math.cos(a) * mouth, cz + Math.sin(a) * mouth).height()) + 1;
        return new Den(kind, cx + Math.cos(a) * mid, cz + Math.sin(a) * mid, 2.2, 2, a + Math.PI, Den.Shape.BURROW, length, y);
    }

    /**
     * The floor of the way in, climbing out of a hollow along the gate's bearing: one height per block from
     * {@code start} blocks out, starting at the camp floor and following the planned ground no steeper than
     * {@code slope} a block, so it is always walkable. Planned once, the same in every chunk.
     */
    public double[] cutProfile(double start, double slope, int length) {
        ClanWorld w = ClanWorld.current();
        double[] prof = new double[length + 1];
        double ux = Math.cos(gate), uz = Math.sin(gate), r = floorY;
        for (int i = 0; i <= length; i++) {
            double target = w == null ? r : w.sample(cx + ux * (start + i), cz + uz * (start + i)).height();
            if (i > 0) r = Math.max(r - slope, Math.min(r + slope, target));
            prof[i] = r;
        }
        return prof;
    }

    /** How far out from the middle the barrier runs {inner, outer}: the hedge band, or the quarry's sheer wall. */
    public double[] barrier() {
        return style == Style.STONE_HOLLOW ? new double[] {cliff - 1, cliff + 2.5} : new double[] {wallIn, wallOut};
    }

    /** The top of Highledge (stone hollow): six blocks above the floor. */
    public int ledgeTop() {
        return floorY + 6;
    }

    /** Arcs of the circle left after removing {@code (centre, half-width)} pairs: [start, length, ...]. */
    private static double[] freeArcs(double[]... taken) {
        // sample the circle in 1-degree steps; keep the bearings no taken arc covers
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < 360; i++) {
            double a = Math.toRadians(i);
            boolean free = true;
            for (double[] t : taken) if (Math.abs(diff(a, t[0])) < t[1]) free = false;
            if (free) out.add(a);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** The free bearing at fraction {@code f} of the way through the free ones, nudged a little. */
    private static double pick(double[] free, double f, RandomSource r) {
        if (free.length == 0) return r.nextDouble() * Math.PI * 2;
        int i = (int) Math.max(0, Math.min(free.length - 1, Math.round(f * (free.length - 1) + (r.nextDouble() - 0.5) * 6)));
        return free[i];
    }

    static double diff(double a, double b) {
        return Math.atan2(Math.sin(a - b), Math.cos(a - b));
    }

    private double chooseGate(ClanWorld w, RandomSource r) {
        double best = 0, bestScore = Double.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI / 18;
            double ux = Math.cos(a), uz = Math.sin(a);
            double jitter = r.nextDouble();
            double score;
            if (style == Style.ISLAND) {
                // the ford runs to the nearest dry bank
                double land = 80;
                for (double t = wallOut + 1; t < 80; t += 1) {
                    Sample s = w.sample(cx + ux * t, cz + uz * t);
                    if (s.height() >= 63.2 && s.zone() != Zone.RIVER) {
                        land = t;
                        break;
                    }
                }
                score = land + jitter * 3;
            } else if (style == Style.STONE_HOLLOW) {
                if (Math.abs(diff(a, ledge)) < Math.toRadians(100)) continue;
                double rim = w.sample(cx + ux * (cliff + 6), cz + uz * (cliff + 6)).height();
                score = rim + 100 * wet(w, cx + ux * (cliff + 34), cz + uz * (cliff + 34)) + road(w, cx + ux * (cliff + 30), cz + uz * (cliff + 30))
                        + jitter * 4;
            } else {
                // a way in that walks out onto easy ground: no stream, ravine or road just outside
                double far = wallOut + 14, rough = 0, prev = w.sample(cx + ux * (wallOut + 1), cz + uz * (wallOut + 1)).height(), wetness = 0;
                for (double t = wallOut + 1; t <= wallOut + 25; t += 2) {
                    double h = w.sample(cx + ux * t, cz + uz * t).height();
                    if (t > wallOut + 2) rough += Math.abs(h - prev);
                    prev = h;
                    wetness = Math.max(wetness, wet(w, cx + ux * t, cz + uz * t));
                    // land planned within a few blocks of the water table often comes out flooded (seed -84596914's
                    // ravine opened into a lake), so a way in over it is the last choice after a stream
                    if (h < 66.5) wetness = Math.max(wetness, 0.5);
                }
                score = 100 * wetness + rough * (style == Style.RAVINE ? 0.5 : 2) + road(w, cx + ux * far, cz + uz * far) + jitter * 6;
                if (style == Style.RAVINE) {
                    double rim = w.sample(cx + ux * (cliff + 5), cz + uz * (cliff + 5)).height();
                    score += rim + (rim < 68 ? 60 : 0);
                }
            }
            if (score < bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    /** Planned ground height at a column (the same number {@link Places#groundAt} gives the painter). */
    static int ground(ClanWorld w, double x, double z) {
        return (int) Math.round(w.sample(x, z).height());
    }

    private static double wet(ClanWorld w, double x, double z) {
        Sample s = w.sample(x, z);
        if (s.height() < 63.2 || s.zone() == Zone.RIVER || s.zone() == Zone.LAKE) return 1;
        // streams running in their own ravines above the water table
        for (var rivers : List.of(w.lake.rivers(), w.forest.rivers())) {
            for (var river : rivers) if (river.path.distance(x, z, river.half + 8) < river.half + 6) return 1;
        }
        return 0;
    }

    private static double road(ClanWorld w, double x, double z) {
        for (ClanMap m : w.maps()) {
            if (!m.covers(x, z)) continue;
            for (Road road : m.roads()) if (road.path().distance(x, z, 16) < 14) return 40;
        }
        return 0;
    }

    // questions the painter and the Clan's cats ask ----------------------------------------------------------------

    /** Whether a point lies in the way in: within the gate's width, on its side of the camp. */
    public boolean inGate(double x, double z) {
        double dx = x - cx, dz = z - cz;
        double along = dx * Math.cos(gate) + dz * Math.sin(gate), lateral = -dx * Math.sin(gate) + dz * Math.cos(gate);
        return along > 0 && Math.abs(lateral) <= gateHalf;
    }

    /** The den a cat of this kind sleeps in. */
    public Den den(DenKind kind) {
        for (Den d : dens) if (d.kind() == kind) return d;
        return dens.get(0);
    }

    /**
     * Where the {@code index}th cat of a den sleeps {x, z}: the den's middle, then round it a block or so in. Dens
     * hold a handful of cats each.
     */
    public double[] nest(DenKind kind, int index) {
        Den d = den(kind);
        if (index == 0) return new double[] {d.x(), d.z()};
        double room = Math.max(0.8, d.radius() - 1.6);
        double a = d.door() + Math.PI + (index - 1) * 2.1;
        double rr = room * (index % 2 == 0 ? 1 : 0.6);
        return new double[] {d.x() + Math.cos(a) * rr, d.z() + Math.sin(a) * rr};
    }
}
