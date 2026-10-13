package com.ninjacat.greatjourney.clanlands.layout;

import static com.ninjacat.greatjourney.clanlands.layout.Geo.lerp;
import static com.ninjacat.greatjourney.clanlands.layout.Geo.smoothstep;

import com.ninjacat.greatjourney.clanlands.layout.Landmark.Kind;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import java.util.ArrayList;
import java.util.List;

/**
 * The lake territories around spawn (docs/book-geography.md section 1), at the books' grand scale: a great irregular
 * lake with the Gathering island, ShadowClan pines to the north-west, ThunderClan woods to the north-east, RiverClan
 * marsh and streams to the south-west, WindClan's rolling moor to the south-east, the horseplace between RiverClan
 * and WindClan, and the Moonpool deep in the hills, at the head of the ThunderClan/WindClan border stream, which
 * comes down to the lake through a steep wooded ravine in a run of waterfalls.
 *
 * <p>Distances are measured in "lake space": x is divided by the seed's east-west stretch so the lake is an oval.
 */
public final class LakeLands implements ClanMap {
    private static final double FADE = 260;

    private final double cx, cz, stretch, radius, depth, territoryDepth, reach;
    private final Noise shoreA, shoreB, outer, hills, broad, wet;
    final Sectors sectors;
    private final River borderStreamEast, borderStreamNorth, outflow, tributary;
    private final List<River> rivers;
    private final double[] island;          // x, z (world), radius, rise
    private final double[] moonpool;        // x, z (world)
    private final double moonpoolBearing, moonpoolDist;
    private final double[] horseplace;
    private final List<Landmark> landmarks = new ArrayList<>();
    private final List<double[]> hollows = new ArrayList<>();   // x, z, radius, depth, wall width[, rim height, rise width]
    /** The deep water out from each half-bridge: x0, z0 (by the shore), x1, z1 (out in the lake). */
    private final List<double[]> berths = new ArrayList<>();
    /** The lake floor along a half-bridge's berth, deep enough that the real ground comes out as open water. */
    private static final double BERTH_FLOOR = 56, BERTH_HALF = 5;
    /** How far round the shore (degrees) a half-bridge keeps from the island's bearing. */
    private static final double HALF_BRIDGE_CLEAR = 16;
    private final double[] spawn, lonerSpot;
    private final Landmark kittypetHome;
    private final Tunnels tunnels;
    private List<Road> roads = List.of();

    public LakeLands(long seed) {
        Geo.Rand r = new Geo.Rand(seed, "lake");
        cx = r.jitter("cx", 0, 96);
        cz = r.jitter("cz", 0, 96);
        stretch = r.range("stretch", 1.0, 1.25);
        radius = r.range("radius", 600, 800);
        depth = r.range("depth", 10, 18);
        territoryDepth = r.range("territory", 800, 1050);
        shoreA = new Noise(seed, "lake.shoreA", 1.0, 2);
        shoreB = new Noise(seed, "lake.shoreB", 1.0, 1);
        outer = new Noise(seed, "lake.outer", 0.9, 2);
        hills = new Noise(seed, "lake.hills", 150, 3);
        broad = new Noise(seed, "lake.broad", 560, 2);
        wet = new Noise(seed, "lake.wet", 110, 2);

        Clan[] order = {Clan.THUNDER, Clan.WIND, Clan.RIVER, Clan.SHADOW};
        double[] centres = {r.jitter("tc", 45, 12), r.jitter("wc", 135, 12), r.jitter("rc", 225, 12), r.jitter("sc", 315, 12)};
        double[] widths = {r.jitter("tcw", 95, 10), r.jitter("wcw", 85, 10), r.jitter("rcw", 90, 10), r.jitter("scw", 90, 10)};
        sectors = new Sectors(order, centres, widths, seed, "lake.sectors", 80);

        // The Moonpool: up the ThunderClan/WindClan border stream, past the territories, deep in the north-east hills.
        moonpoolBearing = r.range("moonpool.bearing", 60, 100);
        moonpoolDist = shore(moonpoolBearing) + territoryDepth + r.range("moonpool.beyond", 450, 700);
        moonpool = world(moonpoolBearing, moonpoolDist);

        // The Gathering island just off the RiverClan shore, toward ShadowClan, with a fallen-tree bridge.
        double ib = r.range("island.bearing", 240, 280);
        // never where the outflow leaves the lake (its bearing is a named draw, the same value the river takes below):
        // on seed 44 the tree-bridge came ashore in the river's mouth
        ib = Math.max(ib, r.range("outflow.bearing", 200, 250) + 16);
        double ir = r.range("island.radius", 40, 60);
        double gap = r.range("island.gap", 14, 24);
        // the shore wobbles, so where it bulges toward the island the channel could close up (seed 4242 joined the
        // island to the mainland): move the island out into the lake until it and its channel clear the shore all round
        double id = shore(ib) - ir - gap;
        for (int k = 0; k < 40 && !clearOfShore(world(ib, id), ir + gap); k++) id -= 3;
        double[] ic = world(ib, id);
        island = new double[] {ic[0], ic[1], ir, r.range("island.rise", 6, 11), ir + gap};
        double[] bridgeShore = world(ib, shore(ib) + 2);
        landmarks.add(new Landmark(Kind.ISLAND_TREE, ic[0], ic[1], ir + 14, 0, r.seed("island.tree")));
        // laid along the real line from the island to the shore point (the lake's east-west stretch bends a lake-space
        // bearing), as a compass facing: +t toward the shore
        double bdx = bridgeShore[0] - ic[0], bdz = bridgeShore[1] - ic[1];
        landmarks.add(new Landmark(Kind.TREE_BRIDGE, (ic[0] + bridgeShore[0]) / 2, (ic[1] + bridgeShore[1]) / 2,
                Math.hypot(bdx, bdz) / 2 + 11, Math.atan2(bdx, -bdz), r.seed("island.bridge")));

        // ThunderClan: the stone hollow (an old quarry), the Sky Oak toward the lake, the abandoned nest near ShadowClan.
        double hb = r.jitter("hollow.bearing", centres[0], 20);
        double hs = r.range("hollow.out", 320, 520);
        double[] hollow = world(hb, shore(hb) + hs);
        double hollowR = r.range("hollow.radius", 35, 48);
        // the quarry's floor stays above the water table (66, see hollow()), and ThunderClan's land round it is mostly
        // 78-86 high, so dug into the land as it lay the walls came out 12-20 high (seed 777001: 13). It stands in a
        // broad wooded rise of its own instead, its rim at least the planned depth over the lowest floor.
        double hollowDepth = r.range("hollow.depth", 22, 32);
        hollows.add(new double[] {hollow[0], hollow[1], hollowR, hollowDepth, 2, 66 + hollowDepth, RISE_WIDTH});
        landmarks.add(new Landmark(Kind.STONE_HOLLOW, hollow[0], hollow[1], hollowR + 16, r.range("hollow.face", 0, Geo.TAU), r.seed("hollow")));
        double ob = hb + r.range("skyoak.turn", 12, 24);
        double[] skyOak = world(ob, shore(ob) + r.range("skyoak.out", 80, 180));
        landmarks.add(new Landmark(Kind.SKY_OAK, skyOak[0], skyOak[1], 34, 0, r.seed("skyoak")));
        double nb = sectors.border(3, shore(hb) + hs + 120) + r.range("nest.inside", 5, 8);
        double[] nest = world(nb, shore(nb) + hs + r.range("nest.out", 80, 180));
        landmarks.add(new Landmark(Kind.ABANDONED_NEST, nest[0], nest[1], 22, r.range("nest.face", 0, Geo.TAU), r.seed("nest")));

        // The other camps.
        double sb = r.jitter("sc.camp.bearing", centres[3], 20);
        double[] scCamp = world(sb, shore(sb) + r.range("sc.camp.out", 300, 500));
        hollows.add(new double[] {scCamp[0], scCamp[1], 26, 4, 10});
        landmarks.add(new Landmark(Kind.SHADOWCLAN_CAMP, scCamp[0], scCamp[1], 38, 0, r.seed("sc.camp")));
        double wb = r.jitter("wc.camp.bearing", centres[1], 20);
        double[] wcCamp = world(wb, shore(wb) + r.range("wc.camp.out", 280, 480));
        hollows.add(new double[] {wcCamp[0], wcCamp[1], 24, 6, 9});
        landmarks.add(new Landmark(Kind.WINDCLAN_HOLLOW, wcCamp[0], wcCamp[1], 36, 0, r.seed("wc.camp")));

        // The horseplace on the RiverClan/WindClan border, and half-bridges along the RiverClan shore.
        double hpb = sectors.border(1, shore(180) + 340);
        horseplace = world(hpb, shore(hpb) + r.range("horseplace.out", 240, 440));
        landmarks.add(new Landmark(Kind.HORSEPLACE, horseplace[0], horseplace[1], 60, r.range("horseplace.face", 0, Geo.TAU), r.seed("horseplace")));
        for (int i = 0; i < 2; i++) {
            double bb = r.jitter("halfbridge." + i, centres[2] + (i == 0 ? -25 : 20), 8);
            // never out into the island, its channel or the tree-bridge (seed -39 ran one into the island)
            double offIsland = Geo.angleDiff(bb, ib);
            if (Math.abs(offIsland) < HALF_BRIDGE_CLEAR) bb = ib + (offIsland < 0 ? -HALF_BRIDGE_CLEAR : HALF_BRIDGE_CLEAR);
            // the real water's edge lies anywhere up to twenty blocks inside the planned shore (the lake's first
            // stretch is shallow and comes out as land: moved 14 blocks out the jetties still stood on dry meadow on
            // most seeds), so the half-bridge starts at the shore and the lake is planned deep in a berth out from it,
            // the way the island's channel is, for the jetty to run out over open water
            double[] p = world(bb, shore(bb) - 4);
            double[] berthIn = world(bb, shore(bb) - 7), berthOut = world(bb, shore(bb) - 70);
            berths.add(new double[] {berthIn[0], berthIn[1], berthOut[0], berthOut[1]});
            // facing: the compass bearing (radians) of the real line out along the berth (the lake's stretch bends it)
            landmarks.add(new Landmark(Kind.HALF_BRIDGE, p[0], p[1], 20, Math.atan2(berthOut[0] - berthIn[0], -(berthOut[1] - berthIn[1])),
                    r.seed("halfbridge." + i)));
        }

        // The Moonpool hollow: a sheer stone bowl at the head of the border stream.
        hollows.add(new double[] {moonpool[0], moonpool[1], 24, 26, 2.5});
        landmarks.add(new Landmark(Kind.MOONPOOL, moonpool[0], moonpool[1], 34, Math.toRadians(moonpoolBearing + 180), r.seed("moonpool")));

        // Where new cats can begin: a kittypet's Twoleg garden just past the ThunderClan woods, or the open lake shore.
        double kb = r.jitter("kittypet.bearing", centres[0], 12);
        double[] kp = world(kb, outerEdge(kb) + 40);
        kittypetHome = new Landmark(Kind.KITTYPET_HOME, kp[0], kp[1], 26, Math.toRadians(kb + 180), r.seed("kittypet"));
        landmarks.add(kittypetHome);
        double lb = sectors.border(1, shore(180) + 10) + r.jitter("loner.turn", 0, 4);
        lonerSpot = world(lb, shore(lb) + 10);
        spawn = world(ob, shore(ob) + 40);

        // The waters, planned over the ground they cross. The border stream falls from the Moonpool through a deep
        // wooded ravine in a run of waterfalls; the outflow river runs wide and low through RiverClan's marsh.
        borderStreamEast = River.follow(streamAlong(0, seed, "lake.stream.east", world(moonpoolBearing, moonpoolDist - 30)), this::ground, 3.5, 1.6, 16, 2.4, 7, 3, 63);
        borderStreamNorth = River.follow(streamAlong(3, seed, "lake.stream.north", null), this::ground, 2.5, 1.3, 9, 1.6, 3, 3, 63);
        double outBearing = r.range("outflow.bearing", 200, 250);
        double width = r.range("outflow.width", 14, 22);
        double sOut = shore(outBearing);
        Path outPath = Path.meander(new double[][] {
                world(outBearing, sOut - 8),
                world(outBearing + r.jitter("o1", 0, 8), sOut + 300),
                world(outBearing + r.jitter("o2", 0, 12), sOut + 700),
                world(outBearing + r.jitter("o3", 0, 15), sOut + territoryDepth + FADE + 80)}, 14, 34, seed, "lake.outflow");
        outflow = River.profiled(outPath, width / 2, 3.5, 8, 0.7, new double[][] {{0, 63}});
        double junctionAt = r.range("camp.junction", 160, 300);
        double[] junction = outPath.pointAt(junctionAt);
        double tribSide = r.range("trib.side", 0, 1) < 0.5 ? -1 : 1;
        double[] t0 = world(outBearing + tribSide * r.range("trib.angle", 12, 18), sOut + junctionAt + 420);
        tributary = River.follow(Path.meander(new double[][] {t0, junction}, 12, 18, seed, "lake.tributary"), this::ground, 3, 1.4, 7, 1.2, 2, 3, 63);
        rivers = List.of(borderStreamEast, borderStreamNorth, outflow, tributary);

        // Stepping stones: over the stream by RiverClan's camp, and where each border stream meets the lake, so the
        // Clans can walk the shore to the Gatherings with dry paws.
        landmarks.add(tributary.steppingStones(Math.max(0, tributary.path.length() - 30), r.seed("stones.rc")));
        landmarks.add(borderStreamEast.steppingStones(Math.max(0, borderStreamEast.path.length() - 22), r.seed("stones.east")));
        landmarks.add(borderStreamNorth.steppingStones(Math.max(0, borderStreamNorth.path.length() - 22), r.seed("stones.north")));

        // RiverClan's camp sits on the dry point between the river and the stream that joins it.
        // Halfway between the two waters' upstream directions from where they meet, clear of both banks.
        double[] riverUp = outPath.pointAt(Math.max(0, junctionAt - 60));
        double[] streamUp = tributary.path.pointAt(Math.max(0, tributary.path.length() - 60));
        double ux = (riverUp[0] - junction[0]) + (streamUp[0] - junction[0]), uz = (riverUp[1] - junction[1]) + (streamUp[1] - junction[1]);
        double ul = Math.max(1e-6, Math.hypot(ux, uz));
        double campOut = width / 2 + 30;
        double[] rc = {junction[0] + ux / ul * campOut, junction[1] + uz / ul * campOut};
        hollows.add(new double[] {rc[0], rc[1], 18, -2.5, 8});                     // a gentle rise, above the wet
        landmarks.add(new Landmark(Kind.RIVERCLAN_CAMP, rc[0], rc[1], 30, 0, r.seed("rc.camp")));

        // The tunnels under the ThunderClan/WindClan border, between the stone hollow and the WindClan camp.
        double tb = sectors.border(0, shore(sectors.border(0, radius)) + hs);
        double[] cavern = world(tb, shore(tb) + Math.min(hs, 420) * r.range("tunnels.out", 0.7, 0.95));
        double[] streamMouth = borderStreamEast.path.pointAt(borderStreamEast.path.length() * r.range("tunnels.stream", 0.55, 0.75));
        double toStream = Geo.bearing(cavern[0] - streamMouth[0], cavern[1] - streamMouth[1]);
        // on the bank just above the stream's ravine, clear of every stream's cut (a meander can swing back toward
        // the first spot tried), so the mouth opens on the real ground rather than under the water
        // (the stream runs along the border the cavern lies under, so step straight out from it, on the cavern's side)
        double[] byStream = Geo.toward(streamMouth[0], streamMouth[1], toStream, 28);
        double streamAt = borderStreamEast.path.alongAt(streamMouth[0], streamMouth[1]);
        double[] ahead = borderStreamEast.path.pointAt(streamAt + 4), behind = borderStreamEast.path.pointAt(streamAt - 4);
        double sx = -(ahead[1] - behind[1]), sz = ahead[0] - behind[0], sl = Math.max(1e-6, Math.hypot(sx, sz));
        sx /= sl;
        sz /= sl;
        if (sx * (cavern[0] - streamMouth[0]) + sz * (cavern[1] - streamMouth[1]) < 0) {
            sx = -sx;
            sz = -sz;
        }
        for (double d = borderStreamEast.reach() + 2; d < borderStreamEast.reach() + 60; d += 2) {
            double[] at = {streamMouth[0] + sx * d, streamMouth[1] + sz * d};
            boolean clear = true;
            for (River river : rivers) if (river.path.distance(at[0], at[1], river.reach() + 4) <= river.reach() + 2) clear = false;
            if (clear) {
                byStream = at;
                break;
            }
        }
        // the ThunderClan and WindClan entrances lie toward each Clan's camp, a long walk underground but not a trek
        double[] aboveCamp = Geo.toward(cavern[0], cavern[1], Geo.bearing(hollow[0] - cavern[0], hollow[1] - cavern[1]),
                Math.min(Math.hypot(hollow[0] - cavern[0], hollow[1] - cavern[1]) - hollowR - 10, r.range("tunnels.tc", 220, 300)));
        double[] onMoor = Geo.toward(cavern[0], cavern[1], Geo.bearing(wcCamp[0] - cavern[0], wcCamp[1] - cavern[1]),
                Math.min(Math.hypot(wcCamp[0] - cavern[0], wcCamp[1] - cavern[1]) - 40, r.range("tunnels.wc", 220, 300)));
        tunnels = new Tunnels(cavern, List.of(byStream, aboveCamp, onMoor), new double[] {cx, cz}, this::ground, r.seed("tunnels"));
        landmarks.add(new Landmark(Kind.TUNNELS, tunnels.cx, tunnels.cz, tunnels.reach(), 0, r.seed("tunnels.build")));

        // The Thunderpath along ShadowClan's outer edge, coming out of the wider world and going back into it, and
        // the old Thunderpath: an overgrown track from the stone hollow north to the edge of the ThunderClan woods.
        double bStart = sectors.border(2, shore(sectors.border(2, radius)) + territoryDepth * 0.9);
        double bEnd = sectors.border(3, shore(sectors.border(3, radius)) + territoryDepth * 0.9);
        if (bEnd < bStart) bEnd += 360;
        double inset = r.range("road.inset", 40, 80);
        double b0 = bStart - 6, b1 = bEnd + 6;
        List<double[]> scPts = new ArrayList<>();
        scPts.add(world(b0 - 3, weightRadius(b0 - 3) + FADE + 80));
        for (double b = b0; b <= b1; b += 4) scPts.add(world(b, outerEdge(b) - inset));
        scPts.add(world(b1 + 3, weightRadius(b1 + 3) + FADE + 80));
        Road scRoad = Road.graded(Path.meander(scPts.toArray(double[][]::new), 16, 12, seed, "lake.road.sc"),
                4.2, Road.Kind.THUNDERPATH, this::ground);
        List<double[]> oldPts = new ArrayList<>();
        double ox = hollow[0], oz = hollow[1] - hollowR - 8;
        oldPts.add(new double[] {ox, oz});
        for (int i = 0; i < 200; i++) {
            oz -= 20;
            oldPts.add(new double[] {ox, oz});
            double opx = (ox - cx) / stretch, opz = oz - cz;
            if (Math.hypot(opx, opz) > outerEdge(Geo.bearing(opx, opz)) + 40) break;
        }
        Road oldRoad = new Road(Path.meander(oldPts.toArray(double[][]::new), 12, 22, seed, "lake.road.old"), 2.4, Road.Kind.OLD_THUNDERPATH);
        roads = List.of(scRoad, oldRoad);

        double maxW = 0;
        for (int b = 0; b < 360; b += 5) maxW = Math.max(maxW, weightRadius(b));
        reach = (maxW + FADE + 20) * stretch;
    }

    /** The tunnels under the ThunderClan/WindClan border. */
    public Tunnels tunnels() {
        return tunnels;
    }

    /** A border stream's course: from the outer edge (or from {@code head}) down along border i to the lake. */
    private Path streamAlong(int border, long seed, String salt, double[] head) {
        List<double[]> pts = new ArrayList<>();
        if (head != null) pts.add(head);
        double start = shore(sectors.border(border, radius)) + territoryDepth + (head == null ? 60 : 0);
        for (double d = start; d > radius * 0.6; d -= 60) {
            double b = sectors.border(border, d);
            double s = shore(b);
            if (d < s - 6) {
                pts.add(world(b, s - 6));
                break;
            }
            pts.add(world(b, d));
        }
        return Path.meander(pts.toArray(double[][]::new), 12, 14, seed, salt);
    }

    /**
     * How fully (0 to 1) a point lies in a half-bridge's berth, the strip of deep water out from its shore end: full
     * within {@link #BERTH_HALF} of its line, fading over {@code edge} blocks beyond that, and over the first few
     * blocks out from the shore end, so the jetty's own shore end keeps its ground.
     */
    private double berth(double x, double z, double edge) {
        double best = 0;
        for (double[] b : berths) {
            double vx = b[2] - b[0], vz = b[3] - b[1], len = Math.max(1e-6, Math.hypot(vx, vz));
            double along = ((x - b[0]) * vx + (z - b[1]) * vz) / len;
            if (along < -1 || along > len) continue;
            double across = Math.abs((x - b[0]) * vz - (z - b[1]) * vx) / len;
            best = Math.max(best, (1 - smoothstep(BERTH_HALF, BERTH_HALF + edge, across)) * smoothstep(-1, 4, along));
        }
        return best;
    }

    /** Whether a circle (world x, z, radius r) lies wholly inside the lake, a couple of blocks clear of the shore. */
    private boolean clearOfShore(double[] c, double r) {
        for (int k = 0; k < 36; k++) {
            double a = Math.toRadians(k * 10);
            double px = (c[0] + Math.cos(a) * r - cx) / stretch, pz = c[1] + Math.sin(a) * r - cz;
            if (Math.hypot(px, pz) > shore(Geo.bearing(px, pz)) - 2) return false;
        }
        return true;
    }

    private double shore(double bearing) {
        double a = Math.toRadians(bearing);
        double c = Math.cos(a), s = Math.sin(a);
        return radius * (1 + 0.22 * shoreA.at(c * 0.45, s * 0.45) + 0.08 * shoreB.at(c * 1.1, s * 1.1));
    }

    private double outerEdge(double bearing) {
        double a = Math.toRadians(bearing);
        return shore(bearing) + territoryDepth + 110 * outer.at(Math.cos(a) * 0.9, Math.sin(a) * 0.9);
    }

    /** Where the map stops overriding the normal world (before the fade), with a lobe out to the Moonpool. */
    private double weightRadius(double bearing) {
        double lobe = 1 - smoothstep(8, 22, Math.abs(Geo.angleDiff(bearing, moonpoolBearing)));
        return Math.max(outerEdge(bearing), lerp(lobe, 0, moonpoolDist + 180));
    }

    /** World position of a lake-space polar point. */
    private double[] world(double bearing, double dist) {
        double[] p = Geo.toward(0, 0, bearing, dist);
        return new double[] {cx + p[0] * stretch, cz + p[1]};
    }

    @Override
    public Sample sample(double x, double z) {
        double px = (x - cx) / stretch, pz = z - cz;
        double d = Math.hypot(px, pz), b = Geo.bearing(px, pz);
        double wr = weightRadius(b);
        double weight = 1 - smoothstep(wr, wr + FADE, d);
        if (weight <= 0) {
            return Sample.OUTSIDE;
        }
        Sample base = baseSample(x, z, d, b, weight);
        if (base.zone() == Zone.LAKE || base.zone() == Zone.ISLAND) {
            return base;
        }
        double h = base.height();
        Zone zone = base.zone();
        double calm = 0;
        for (Road road : roads) {
            double[] rn = road.path().nearest(x, z, road.half() + 14);
            if (rn[0] < road.half() + 8) h = road.grade(h, rn[0], rn[1]);
            calm = Math.max(calm, 1 - smoothstep(road.half() + 4, road.half() + 14, rn[0]));
        }
        for (River river : rivers) {
            double[] n = river.path.nearest(x, z, river.reach() + 16);
            calm = Math.max(calm, 1 - smoothstep(river.reach(), river.reach() + 14, n[0]));
            if (n[0] > river.reach()) continue;
            h = river.carve(h, n[0], n[1]);
            if (river == outflow && n[0] < river.half + 1) zone = Zone.RIVER;
        }
        return new Sample(zone, h, weight, lerp(calm, base.factor(), CALM));
    }

    /** The ground before any river cuts it (also what the rivers plan their water against). */
    private double ground(double x, double z) {
        double px = (x - cx) / stretch, pz = z - cz;
        return baseSample(x, z, Math.hypot(px, pz), Geo.bearing(px, pz), 1).height();
    }

    private Sample baseSample(double x, double z, double d, double b, double weight) {
        double rs = shore(b);
        double idist = Math.hypot(x - island[0], z - island[1]);
        if (idist < island[2] + 6) {
            double t = Math.max(0, 1 - idist / island[2]);
            double h = 62.5 + island[3] * Math.pow(t, 0.6) + 1.6 * hills.at(x, z) * t;
            return new Sample(Zone.ISLAND, h, weight, Zone.ISLAND.factor);
        }
        if (d < rs) {
            double floor = 63 - depth * smoothstep(0, 80, rs - d) - 1.2 + 1.0 * hills.at(x, z);
            // the island's channel is open water all round: planned deep, because the real ground comes out a few
            // blocks above a shallow plan (seed 4242's 3-deep channel generated as grass at 62)
            if (idist < island[4]) floor = Math.min(floor, 55.0);
            double f = idist < island[4] + 10 ? CALM : Zone.LAKE.factor;
            // and so is each half-bridge's berth, for the same reason
            double berth = berth(x, z, 4);
            if (berth > 0) floor = Math.min(floor, lerp(berth, floor, BERTH_FLOOR));
            if (berth(x, z, 12) > 0) f = CALM;
            return new Sample(Zone.LAKE, floor, weight, f);
        }
        double s = d - rs;
        int i = sectors.indexAt(b, d);
        Clan clan = sectors.clan(i);
        double h = clanHeight(clan, x, z, s);
        double[] nb = sectors.nearestBorder(b, d);
        if (nb[0] < 70) {
            double h2 = clanHeight(sectors.clan((int) nb[1]), x, z, s);
            h = lerp(0.5 * (1 - smoothstep(0, 70, nb[0])), h, h2);
        }
        h = lerp(smoothstep(0, 40, s), 64.0, h);                 // a gentle shore all round the lake
        Zone zone = clanZone(clan, x, z, s);

        // Hills rise toward the Moonpool beyond ThunderClan and WindClan.
        double mp = Math.hypot(x - moonpool[0], z - moonpool[1]);
        double upland = smoothstep(480 + 100 * outer.at(x * 0.002, z * 0.002), 120, mp);
        if (upland > 0) {
            h = lerp(upland, h, 124 + 18 * hills.at(x * 0.7, z * 0.7));
            if (upland > 0.35 && d > outerEdge(b) - 40) zone = Zone.MOONPOOL_HILLS;
        }
        double hp = Math.hypot(x - horseplace[0], z - horseplace[1]);
        if (hp < 90) {
            zone = Zone.TWOLEG_FARMLAND;
            h = lerp(smoothstep(90, 55, hp), h, 68 + hills.at(x, z));
        }
        double calm = 0;
        for (double[] hw : hollows) {
            h = hollow(h, x, z, hw);
            calm = Math.max(calm, hollowCalm(x, z, hw));
        }
        return new Sample(zone, h, weight, lerp(calm, zone.factor, CALM));
    }

    private double clanHeight(Clan clan, double x, double z, double s) {
        double n = hills.at(x, z), m = broad.at(x, z);
        return switch (clan) {
            case THUNDER -> 70 + 14 * smoothstep(0, 520, s) + 7 * n + 5 * m;
            case WIND -> 72 + 26 * smoothstep(0, 640, s) + 6 * n + 8 * m;
            case RIVER -> s < 220 ? 63.4 + 1.4 * n : 64.5 + 3 * n + 2 * m;
            case SHADOW -> {
                double base = 65 + 7 * smoothstep(0, 520, s) + 4 * n + 2 * m;
                yield wet.at(x, z) > 0.46 ? lerp(smoothstep(0.46, 0.56, wet.at(x, z)), base, 62.6) : base;
            }
        };
    }

    private Zone clanZone(Clan clan, double x, double z, double s) {
        return switch (clan) {
            case THUNDER -> Zone.THUNDERCLAN_FOREST;
            case WIND -> Zone.WINDCLAN_MOOR;
            case RIVER -> s < 220 ? Zone.RIVERCLAN_MARSH : Zone.RIVERCLAN_WETLAND;
            case SHADOW -> wet.at(x, z) > 0.5 ? Zone.SHADOWCLAN_MARSH : Zone.SHADOWCLAN_PINES;
        };
    }

    /** A bowl: floor {@code depth} below its rim (negative: a rise), walls {@code wall} blocks wide (small = cliffs). */
    /**
     * The terrain smoothness (vanilla factor) at most this calm: the planned rivers, roads, camp hollows and the
     * island's channel only come out true where vanilla's own lumps are held down (with a forest's factor a stream's
     * valley came out up to ten blocks above its plan and ran dry, and quarry walls came out as ramps).
     */
    static final double CALM = 12;

    /** How near (1) to (0) a hollow's rim a column is, for {@link #CALM}. */
    static double hollowCalm(double x, double z, double[] hw) {
        double r = Math.hypot(x - hw[0], z - hw[1]);
        return 1 - smoothstep(hw[2] + hw[4], hw[2] + hw[4] + 12, r);
    }

    /**
     * The least width of the rise round a hollow given a rim height (hollows entry [5]); it widens by two blocks for
     * every block the land is lifted, so its slope stays under one block in two however low the land lay.
     */
    static final double RISE_WIDTH = 24;

    static double hollow(double h, double x, double z, double[] hw) {
        double r = Math.hypot(x - hw[0], z - hw[1]);
        if (hw.length > 6 && h < hw[5]) {
            // a rise round the hollow: the land lifted to the rim height a few blocks past the wall's top, easing back
            // down to the land as it lay over a width that grows with the lift (a knoll, never a mountain)
            double lip = hw[2] + hw[4] + 4, lift = hw[5] - h;
            h += lift * smoothstep(lip + hw[6] + 2 * lift, lip, r);
        }
        if (r > hw[2] + hw[4]) return h;
        double t = smoothstep(hw[2] + hw[4], hw[2] - hw[4], r);
        // a hollow in dry land keeps its floor above the water table, or the sea level would flood it into a pond
        return hw[3] > 0 ? Math.max(h - hw[3] * t, Math.min(h, 66)) : h - hw[3] * t;
    }

    /** The Clan whose territory holds this column, or null (the lake, the island, the horseplace, the hills, outside). */
    public Clan territoryAt(double x, double z) {
        Sample s = sample(x, z);
        if (s.weight() < 0.5 || s.zone() == null) return null;
        return switch (s.zone()) {
            case THUNDERCLAN_FOREST -> Clan.THUNDER;
            case WINDCLAN_MOOR -> Clan.WIND;
            case RIVERCLAN_MARSH, RIVERCLAN_WETLAND, RIVER -> Clan.RIVER;
            case SHADOWCLAN_PINES, SHADOWCLAN_MARSH -> Clan.SHADOW;
            default -> null;
        };
    }

    /** The camp landmark of a Clan. */
    public Landmark camp(Clan clan) {
        Kind kind = switch (clan) {
            case THUNDER -> Kind.STONE_HOLLOW;
            case WIND -> Kind.WINDCLAN_HOLLOW;
            case RIVER -> Kind.RIVERCLAN_CAMP;
            case SHADOW -> Kind.SHADOWCLAN_CAMP;
        };
        return landmarks.stream().filter(l -> l.kind() == kind).findFirst().orElseThrow();
    }

    /** The Twoleg garden where a kittypet begins, at the edge of the ThunderClan woods. */
    public Landmark kittypetHome() {
        return kittypetHome;
    }

    /** Where a loner begins: the lake shore between RiverClan and WindClan, near the horseplace. */
    public double[] lonerSpot() {
        return lonerSpot;
    }

    /** The Twoleg roads: the Thunderpath past ShadowClan and the old Thunderpath through the ThunderClan woods. */
    public List<Road> roads() {
        return roads;
    }

    /** The streams and the river, for the builders that lay their water and waterfalls. */
    public List<River> rivers() {
        return rivers;
    }

    public double[] moonpool() {
        return moonpool;
    }

    @Override public String name() { return "lake"; }
    @Override public double centreX() { return cx; }
    @Override public double centreZ() { return cz; }
    @Override public double reach() { return reach; }
    @Override public List<Landmark> landmarks() { return landmarks; }
    @Override public double[] spawn() { return spawn; }
}
