package com.ninjacat.greatjourney.clanlands.layout;

import static com.ninjacat.greatjourney.clanlands.layout.Geo.lerp;
import static com.ninjacat.greatjourney.clanlands.layout.Geo.smoothstep;

import com.ninjacat.greatjourney.clanlands.layout.Landmark.Kind;
import java.util.ArrayList;
import java.util.List;

/**
 * The Great Journey: the land between the old forest and the lake, as the Clans walked it in the books. Leaving the
 * forest's lands to the north-west, they cross the great Thunderpath (the highway), pass Twoleg farms, climb through
 * a mountain pass (mountains of normal size, not giants) where the Tribe of Rushing Water lives in a cave behind a
 * waterfall, and come down at last onto a ridge looking over the lake. The sun-drown-place, where Midnight the
 * badger lives by the sea, lies well off the route to the west.
 *
 * <p>Only these places (and the highway's strip of Twoleg land) are drawn; between them the pack's normal world
 * shows, each place fading into it. Distances use route space: {@code a} along the route from the forest's edge,
 * {@code b} across it (positive to the west side).
 */
public final class JourneyRoute implements ClanMap {
    private static final double FADE = 200;

    private final double fx, fz, ux, uz, vx, vz;          // forest centre, unit along (toward the lake), unit across (west)
    private final double exitA, arriveA;                    // route-space distance from the forest centre of each end
    private final double[] forestExit, highwayCrossing, twolegFarm, mountainPass, tribeCave, sunDrownPlace, lakeArrival;
    private final double passA, bandHalf, bandLength, caveSide;
    private final double seaAngle, seaRadius;
    private final double[] tribeFacing;
    private final double[] midnightSett;                     // unit vector from the cave out through the waterfall
    private final Path route;
    private final Road highway;
    private final Noise hills, crags, shore;
    private final List<Landmark> landmarks = new ArrayList<>();
    private final double minX, maxX, minZ, maxZ;

    public JourneyRoute(long seed, LakeLands lake, OldForest forest) {
        Geo.Rand r = new Geo.Rand(seed, "journey");
        hills = new Noise(seed, "journey.hills", 140, 3);
        crags = new Noise(seed, "journey.crags", 90, 4);
        shore = new Noise(seed, "journey.shore", 260, 2);
        fx = forest.centreX();
        fz = forest.centreZ();
        double dx = lake.centreX() - fx, dz = lake.centreZ() - fz, dist = Math.hypot(dx, dz);
        ux = dx / dist;
        uz = dz / dist;
        // "across" points to the west-ish side (the side with the smaller x), where the sun drowns
        double px = -uz, pz = ux;
        if (px > 0) {
            px = -px;
            pz = -pz;
        }
        vx = px;
        vz = pz;

        // Where the forest's lands end and where the lake's begin, measured along the line between them.
        double a = 0;
        while (a < dist && forest.sample(fx + ux * a, fz + uz * a).weight() > 0) a += 25;
        exitA = a + 60;
        a = 0;
        while (a < dist && lake.sample(lake.centreX() - ux * a, lake.centreZ() - uz * a).weight() > 0) a += 25;
        arriveA = dist - a - 120;
        double span = arriveA - exitA;

        forestExit = at(exitA, 0);
        double hwA = exitA + span * r.range("highway.at", 0.12, 0.2);
        highwayCrossing = at(hwA, 0);
        double farmA = exitA + span * r.range("farm.at", 0.3, 0.38);
        twolegFarm = at(farmA, r.jitter("farm.side", 0, 160));
        passA = exitA + span * r.range("pass.at", 0.58, 0.7);
        bandHalf = r.range("mountains.depth", 260, 340);
        bandLength = r.range("mountains.length", 1500, 2100);
        mountainPass = at(passA, 0);
        caveSide = (r.range("cave.side", 0, 1) < 0.5 ? -1 : 1) * r.range("cave.off", 220, 360);
        tribeCave = at(passA - bandHalf * 0.35, caveSide);
        // the waterfall and the cave mouth face back down the way the Clans come up
        tribeFacing = new double[] {-ux, -uz};
        double sdA = exitA + span * r.range("sea.at", 0.22, 0.35);
        double seaOff = r.range("sea.off", 1900, 2500);
        seaRadius = r.range("sea.radius", 700, 900);
        double[] seaCentre = at(sdA, seaOff + seaRadius * 0.6);
        seaAngle = Math.atan2(vz, vx);
        sunDrownPlace = at(sdA, seaOff);                   // the shore by Midnight's sett, facing the sea to the west
        lakeArrival = at(arriveA - 40, r.jitter("arrive.side", 0, 80));

        route = Path.meander(new double[][] {forestExit, highwayCrossing, twolegFarm, mountainPass, lakeArrival}, 16, 60, seed, "journey.route");

        // The highway: a long double road across the route, reaching far into the normal world on both sides.
        double hwLen = r.range("highway.length", 5000, 7000), tilt = Math.toRadians(r.jitter("highway.tilt", 0, 22));
        double hx = vx * Math.cos(tilt) - vz * Math.sin(tilt), hz = vx * Math.sin(tilt) + vz * Math.cos(tilt);
        // it runs mostly to the east of the route, away from the sun-drown-place's shore
        double west = r.range("highway.west", 900, 1300);
        Path hwPath = Path.meander(new double[][] {
                {highwayCrossing[0] + hx * west, highwayCrossing[1] + hz * west},
                {highwayCrossing[0], highwayCrossing[1]},
                {highwayCrossing[0] - hx * (hwLen - west), highwayCrossing[1] - hz * (hwLen - west)}}, 24, 70, seed, "journey.highway");
        highway = Road.graded(hwPath, 10, Road.Kind.HIGHWAY, (x, z) -> 69 + 2 * hills.at(x, z));
        for (double s = 0; s < hwPath.length(); s += 90) {
            double[] p = hwPath.pointAt(s);
            landmarks.add(new Landmark(Kind.HIGHWAY_STRETCH, p[0], p[1], 70, 0, r.seed("highway." + (int) s)));
        }

        landmarks.add(new Landmark(Kind.TWOLEG_FARMS, twolegFarm[0], twolegFarm[1], 70, r.range("farm.face", 0, Geo.TAU), r.seed("farm")));
        landmarks.add(new Landmark(Kind.TRIBE_CAVE, tribeCave[0], tribeCave[1], 60, Math.atan2(tribeFacing[1], tribeFacing[0]), r.seed("tribe")));

        landmarks.add(new Landmark(Kind.LAKE_RIDGE, lakeArrival[0], lakeArrival[1], 20, 0, r.seed("ridge")));

        double lx = Double.MAX_VALUE, hx2 = -Double.MAX_VALUE, lz = Double.MAX_VALUE, hz2 = -Double.MAX_VALUE;
        for (double[] p : new double[][] {forestExit, lakeArrival, seaCentre, highwayCrossing,
                hwPath.pointAt(0), hwPath.pointAt(hwPath.length()), at(passA, bandLength / 2), at(passA, -bandLength / 2)}) {
            lx = Math.min(lx, p[0]);
            hx2 = Math.max(hx2, p[0]);
            lz = Math.min(lz, p[1]);
            hz2 = Math.max(hz2, p[1]);
        }
        double pad = seaRadius + FADE + 400;
        minX = lx - pad;
        maxX = hx2 + pad;
        minZ = lz - pad;
        maxZ = hz2 + pad;

        // Midnight's sett: walk inland from the shore until the sandy bank tops out, and dig in just below its crest.
        double[] sett = sunDrownPlace;
        for (double t = 0; t < 160; t += 2) {
            double[] q = {sunDrownPlace[0] - vx * t, sunDrownPlace[1] - vz * t};
            if (sea(q[0], q[1]).height() >= 73) {
                sett = new double[] {q[0] + vx * 3, q[1] + vz * 3};
                break;
            }
        }
        midnightSett = sett;
        landmarks.add(new Landmark(Kind.MIDNIGHT_SETT, sett[0], sett[1], 16, Math.atan2(vz, vx), r.seed("midnight")));
    }

    /** The entrance of Midnight's sett, dug into the sandy bank above the beach; its facing points to the sea. */
    public double[] midnightSett() {
        return midnightSett;
    }

    /** Unit vector across the route toward the sun-drown side (west-ish). */
    public double[] seaward() {
        return new double[] {vx, vz};
    }

    /** World position of route-space (a along from the forest centre, b across, west positive). */
    private double[] at(double a, double b) {
        return new double[] {fx + ux * a + vx * b, fz + uz * a + vz * b};
    }

    @Override
    public boolean covers(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    @Override
    public Sample sample(double x, double z) {
        double ox = x - fx, oz = z - fz;
        double a = ox * ux + oz * uz, b = ox * vx + oz * vz;

        Sample best = Sample.OUTSIDE;
        best = pick(best, highwayStrip(x, z));
        best = pick(best, mountains(x, z, a, b));
        best = pick(best, sea(x, z));
        best = pick(best, around(x, z, twolegFarm, 110, Zone.TWOLEG_FARMLAND, 68 + 1.5 * hills.at(x, z)));
        best = pick(best, around(x, z, lakeArrival, 160, Zone.JOURNEY_RIDGE, ridgeHeight(x, z)));
        if (best.weight() <= 0) return Sample.OUTSIDE;

        // Wherever the highway runs, the ground is graded to its bed.
        double[] n = highway.path().nearest(x, z, highway.half() + 10);
        if (n[0] < highway.half() + 9) {
            double h = highway.grade(best.height(), n[0], n[1]);
            best = new Sample(n[0] < highway.half() + 2 ? Zone.HIGHWAY_LAND : best.zone(), h, Math.max(best.weight(), 1), best.factor());
        }
        return best;
    }

    private static Sample pick(Sample a, Sample b) {
        return b.weight() > a.weight() ? b : a;
    }

    /** A round place fading into the normal world. */
    private Sample around(double x, double z, double[] c, double radius, Zone zone, double h) {
        double d = Math.hypot(x - c[0], z - c[1]);
        double w = 1 - smoothstep(radius, radius + FADE, d);
        return w <= 0 ? Sample.OUTSIDE : new Sample(zone, h, w, zone.factor);
    }

    /** A low ridge looking down over the lake: gentle grass and a lookout rock. */
    private double ridgeHeight(double x, double z) {
        double d = Math.hypot(x - lakeArrival[0], z - lakeArrival[1]);
        return 84 + 10 * smoothstep(160, 20, d) + 2 * hills.at(x, z);
    }

    /** A strip of flat Twoleg land along the whole highway, so it runs level through the normal world. */
    private Sample highwayStrip(double x, double z) {
        double d = highway.path().distance(x, z, 60 + FADE);
        double w = 1 - smoothstep(60, 60 + FADE, d);
        if (w <= 0) return Sample.OUTSIDE;
        Zone zone = d < 70 ? Zone.HIGHWAY_LAND : Zone.TWOLEG_FARMLAND;
        return new Sample(zone, 69 + 2 * hills.at(x, z), w, zone.factor);
    }

    /**
     * The mountains: a band of rocky peaks (120-170, Highstones' scale) across the route, with a pass at about
     * 100-115 where the route crosses, and the Tribe's cliff and waterfall a little way off the pass.
     */
    private Sample mountains(double x, double z, double a, double b) {
        double da = Math.abs(a - passA), db = Math.abs(b);
        double ea = Math.max(0, da - bandHalf), eb = Math.max(0, db - bandLength / 2);
        double outside = Math.max(0, Math.hypot(ea, eb) + 90 * hills.at(x * 0.6, z * 0.6));   // ragged edges
        double w = 1 - smoothstep(0, FADE + 120, outside);
        if (w <= 0) return Sample.OUTSIDE;
        double inBand = smoothstep(bandHalf, bandHalf * 0.35, da) * smoothstep(bandLength / 2, bandLength / 2 - 300, db);
        double ridge = 1 - Math.abs(crags.at(x, z));                              // ridged: sharp crests, cut valleys
        double peaks = 104 + 64 * Math.pow(ridge, 2.2) * inBand + 6 * hills.at(x, z);
        double notch = smoothstep(60, 240, Math.abs(b));                         // the pass where the route crosses
        double h = lerp(inBand, 88 + 6 * hills.at(x, z), lerp(notch, 104 + 6 * hills.at(x * 1.5, z * 1.5), Math.min(170, peaks)));
        Zone zone = h > 125 ? Zone.MOUNTAIN_PEAKS : Zone.MOUNTAINS;

        // The Tribe's cliff: a high rock shelf behind, a sheer face, and a basin with a pool in front.
        double cx = x - tribeCave[0], cz = z - tribeCave[1];
        double fwd = cx * tribeFacing[0] + cz * tribeFacing[1], side = -cx * tribeFacing[1] + cz * tribeFacing[0];
        double cd = Math.hypot(fwd, side);
        if (cd < 140) {
            double behind = smoothstep(2, -3, fwd);                              // the cliff face runs across, 2 blocks thick
            double shelf = 128 + 4 * hills.at(x, z);
            double basin = 100 + 2 * smoothstep(16, 60, Math.hypot(fwd - 14, side));
            double cliff = lerp(behind, basin, shelf);
            double blend = smoothstep(140, 70, cd) * smoothstep(90, 50, Math.abs(side));
            h = lerp(blend, h, cliff);
            if (blend > 0.5) zone = Zone.TRIBE_LANDS;
        }
        return new Sample(zone, h, w, zone.factor);
    }

    /** The sun-drown-place: sea to the west, a sandy beach, dunes and a sandy bank where Midnight has her sett. */
    private Sample sea(double x, double z) {
        double ox = x - sunDrownPlace[0], oz = z - sunDrownPlace[1];
        double west = ox * vx + oz * vz, along = ox * ux + oz * uz;
        // a round bay: everything within a wobbly circle about the sea's middle, the beach line running through it
        double bayX = west - seaRadius * 0.5, bay = Math.hypot(bayX, along) - seaRadius * (1.15 + 0.12 * shore.at(x * 0.002, z * 0.002));
        double outside = Math.max(0, bay);
        double w = 1 - smoothstep(0, FADE + 100, outside);
        if (w <= 0) return Sample.OUTSIDE;
        double s = west - 70 * shore.at(along * 0.006, west * 0.001);           // + toward the sea
        double h;
        Zone zone;
        if (s > 25) {
            h = 63 - Math.min(18, 2 + (s - 25) * 0.08);                          // the sea, shelving deeper
            zone = Zone.SUN_DROWN_SEA;
        } else if (s > -20) {
            h = lerp(smoothstep(25, -20, s), 62.4, 65.5);                        // the beach
            zone = Zone.SUN_DROWN_SHORE;
        } else if (s > -60) {
            h = 66 + 4 * smoothstep(-20, -60, s) + 1.5 * hills.at(x * 2, z * 2); // dunes
            zone = Zone.SUN_DROWN_SHORE;
        } else {
            h = 70 + 7 * smoothstep(-60, -72, s) + 2 * hills.at(x, z);           // the sandy bank, then grass
            zone = s > -90 ? Zone.SUN_DROWN_SHORE : Zone.JOURNEY_RIDGE;
        }
        return new Sample(zone, h, w, zone.factor);
    }

    /** The highway, for the builders that lay its surface. */
    public Road highway() { return highway; }

    /** The highway is this map's only road (traffic drives it too). */
    @Override
    public List<Road> roads() { return List.of(highway); }

    /** Unit vector from the Tribe's cave out through its waterfall. */
    public double[] tribeFacing() { return tribeFacing; }

    /** Where the Clans leave the old forest's lands. */
    public double[] forestExit() { return forestExit; }
    /** The great Thunderpath they must cross. */
    public double[] highwayCrossing() { return highwayCrossing; }
    /** Twoleg farms along the way. */
    public double[] twolegFarm() { return twolegFarm; }
    /** The mountain pass (normal-sized mountains). */
    public double[] mountainPass() { return mountainPass; }
    /** The Tribe of Rushing Water's cave, behind the waterfall. */
    public double[] tribeCave() { return tribeCave; }
    /** The sun-drown-place by the sea, where Midnight lives. */
    public double[] sunDrownPlace() { return sunDrownPlace; }
    /** Where the Clans first look down on the lake. */
    public double[] lakeArrival() { return lakeArrival; }
    /** The walked route from the forest to the lake. */
    public Path route() { return route; }

    @Override public String name() { return "journey"; }
    @Override public double centreX() { return (minX + maxX) / 2; }
    @Override public double centreZ() { return (minZ + maxZ) / 2; }
    @Override public double reach() { return Math.max(maxX - minX, maxZ - minZ) / 2; }
    @Override public List<Landmark> landmarks() { return landmarks; }
    @Override public double[] spawn() { return forestExit; }
}
