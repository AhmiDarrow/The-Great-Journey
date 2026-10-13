package com.ninjacat.greatjourney.clanlands.layout;

import static com.ninjacat.greatjourney.clanlands.layout.Geo.lerp;
import static com.ninjacat.greatjourney.clanlands.layout.Geo.smoothstep;

import com.ninjacat.greatjourney.clanlands.layout.Landmark.Kind;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import java.util.ArrayList;
import java.util.List;

/**
 * The old forest territories (docs/book-geography.md section 2), far to the south-east of spawn, at the books' grand
 * scale: Fourtrees in a deep bowl where all four Clans meet, ShadowClan's marshes and pines north of the Thunderpath,
 * ThunderClan's woods to the south-east, RiverClan's plains along the river to the south-west, and WindClan's high
 * moor to the west. The river comes off the moor in a great waterfall into the gorge, runs between sheer rock walls,
 * comes out onto the plains and winds past Sunningrocks. Beyond lie Tallpines, the Twolegplace, Barley's farm and
 * Highstones.
 */
public final class OldForest implements ClanMap {
    private static final double FADE = 280;
    /**
     * The moor river's surface, and the gorge's and the plains' (sea level): the moor river drops thirty blocks into
     * the gorge in one great fall, then runs at the foot of its walls out onto the plains.
     */
    private static final double MOOR_WATER = 93, GORGE_WATER = 63, PLAINS_WATER = 63;

    private final double cx, cz, rf, reach;
    private final Sectors sectors;
    private final Noise outer, hills, broad, wet;
    private final Path thunderpath, riverPath;
    private final River moorRiver, gorgeRiver, plainsRiver;
    private final List<River> rivers;
    private final double roadNorth, highstonesBearing, twolegBearing, roadX0;
    private final double[] roadZs;
    private final double[][] peaks;              // x, z, radius, height
    private final double[] farm, carrion, riverCamp, lonerSpot;
    private final Landmark kittypetHome;
    /** The Fourtrees hollow's radius (its floor's edge; the wall runs 5 either side of it) and the landmark's facing. */
    private final double fourtreesR, fourtreesTurn;
    private final List<double[]> hollows = new ArrayList<>();
    private final List<Landmark> landmarks = new ArrayList<>();
    /** The Thunderpath as a graded road; null while the constructor plans its bed from the land. */
    private Road thunderRoad;
    private List<Road> roads = List.of();

    public OldForest(long seed) {
        Geo.Rand r = new Geo.Rand(seed, "forest");
        double[] c = Geo.toward(0, 0, r.range("bearing", 125, 145), r.range("distance", 10000, 12500));
        cx = c[0];
        cz = c[1];
        rf = r.range("radius", 1300, 1700);
        outer = new Noise(seed, "forest.outer", 0.9, 2);
        hills = new Noise(seed, "forest.hills", 150, 3);
        broad = new Noise(seed, "forest.broad", 520, 2);
        wet = new Noise(seed, "forest.wet", 100, 2);
        Clan[] order = {Clan.SHADOW, Clan.THUNDER, Clan.RIVER, Clan.WIND};
        double[] centres = {r.jitter("sc", 45, 8), r.jitter("tc", 135, 8), r.jitter("rc", 215, 8), r.jitter("wc", 300, 8)};
        double[] widths = {r.jitter("scw", 90, 10), r.jitter("tcw", 95, 10), r.jitter("rcw", 80, 10), r.jitter("wcw", 95, 10)};
        sectors = new Sectors(order, centres, widths, seed, "forest.sectors", 90);

        // The Thunderpath runs east-west a little north of Fourtrees, then bends toward Highstones in the west.
        roadNorth = r.range("road.north", 120, 220);
        highstonesBearing = r.range("highstones.bearing", 285, 300);
        twolegBearing = r.range("twoleg.bearing", 130, 145);
        double[] hs = Geo.toward(cx, cz, highstonesBearing, rf + r.range("highstones.out", 900, 1300));
        thunderpath = Path.meander(new double[][] {
                {cx + rf + FADE, cz - roadNorth + r.jitter("road.e", 0, 80)},
                {cx + 500, cz - roadNorth + r.jitter("road.m1", 0, 30)},
                {cx - 500, cz - roadNorth + r.jitter("road.m2", 0, 30)},
                {cx - rf * 0.85, cz - roadNorth - r.range("road.w", 0, 120)},
                {hs[0] + 120, hs[1] - 140},
        }, 20, 18, seed, "forest.road");
        roadX0 = cx - rf - FADE - 1600;
        roadZs = roadTable();

        // The river: down off the high moor in the north-west, over a great fall into the gorge, out of the gorge in
        // a second fall, then across the plains west and south of Fourtrees and away to the south-east.
        riverPath = Path.meander(new double[][] {
                {cx - r.range("river.in", 400, 520), cz - rf - FADE},
                {cx - r.range("river.g1", 380, 460), cz - 620},
                {cx - r.range("river.g2", 340, 420), cz - 160},
                {cx - r.range("river.p1", 240, 440), cz + 240},
                {cx - r.range("river.p2", 80, 200), cz + 560},
                {cx + r.range("river.out", 160, 400), cz + rf + FADE},
        }, 14, 40, seed, "forest.river");
        double gorgeStart = riverPath.alongAt(cx - 420, cz - rf + 160);
        double gorgeEnd = Math.min(riverPath.alongAt(cx - 380, cz - 160), gorgeStart + r.range("gorge.length", 420, 640));
        moorRiver = River.profiled(riverPath.slice(0, gorgeStart), 5, 1.8, 8, 1.2, new double[][] {{0, MOOR_WATER}}).fallsAtEnd(GORGE_WATER);
        gorgeRiver = River.profiled(riverPath.slice(gorgeStart, gorgeEnd), 5, 2.5, 7, 7.0, new double[][] {{0, GORGE_WATER}}).continuing();
        plainsRiver = River.profiled(riverPath.slice(gorgeEnd, riverPath.length()), 9, 3.5, 10, 0.6, new double[][] {{0, PLAINS_WATER}}).continuing();
        rivers = List.of(moorRiver, gorgeRiver, plainsRiver);

        // Fourtrees: a steep round bowl with four great oaks and the Great Rock.
        double ftR = r.range("fourtrees.radius", 40, 55);
        fourtreesR = ftR;
        hollows.add(new double[] {cx, cz, ftR, r.range("fourtrees.depth", 12, 16), 5});
        fourtreesTurn = r.range("fourtrees.turn", 0, Geo.TAU);
        landmarks.add(new Landmark(Kind.FOURTREES, cx, cz, ftR + 24, fourtreesTurn, r.seed("fourtrees")));

        // ThunderClan: the ravine camp, the owl tree, Snakerocks and the Great Sycamore.
        double tb = r.jitter("tc.camp.bearing", centres[1], 15);
        double[] tcCamp = clearOfRivers(Geo.toward(cx, cz, tb, r.range("tc.camp.out", 560, 760)), 54);
        landmarks.add(new Landmark(Kind.THUNDERCLAN_RAVINE, tcCamp[0], tcCamp[1], 50, r.range("ravine.turn", 0, Math.PI), r.seed("ravine")));
        // a deep ravine: the real ground comes out shallower than the plan (5-10 blocks where 12 were planned)
        hollows.add(new double[] {tcCamp[0], tcCamp[1], 22, 18, 3});
        double[] owl = {tcCamp[0] - r.range("owl.west", 160, 280), tcCamp[1] + r.jitter("owl.z", 0, 60)};
        landmarks.add(new Landmark(Kind.OWL_TREE, owl[0], owl[1], 22, 0, r.seed("owl")));
        double[] snake = {tcCamp[0] + r.range("snake.east", 360, 520), cz - roadNorth + r.range("snake.south", 80, 180)};
        landmarks.add(new Landmark(Kind.SNAKEROCKS, snake[0], snake[1], 30, r.range("snake.turn", 0, Geo.TAU), r.seed("snakerocks")));
        double sa = r.range("sycamore.angle", 0, Geo.TAU), sd = r.range("sycamore.dist", 50, 90);
        landmarks.add(new Landmark(Kind.GREAT_SYCAMORE, snake[0] + Math.cos(sa) * sd, snake[1] + Math.sin(sa) * sd, 30, 0, r.seed("sycamore")));

        // Sunningrocks: on the river's east bank where ThunderClan meets RiverClan.
        double[] sun = null;
        for (double s = gorgeEnd; s < riverPath.length(); s += 6) {
            double[] p = riverPath.pointAt(s);
            if (p[1] > cz + 300) {
                sun = new double[] {p[0] + 26, p[1]};
                break;
            }
        }
        if (sun == null) sun = new double[] {cx - 200, cz + 400};
        landmarks.add(new Landmark(Kind.SUNNINGROCKS, sun[0], sun[1], 34, 0, r.seed("sunningrocks")));
        // the stepping stones over the river, a little upstream of Sunningrocks
        double sunAlong = plainsRiver.path.alongAt(sun[0] - 26, sun[1]);
        landmarks.add(plainsRiver.steppingStones(Math.max(0, sunAlong - r.range("stones.up", 20, 40)), r.seed("stones.sun")));

        // ShadowClan: camp, and Carrionplace further out to the north-west.
        double sb = r.jitter("sc.camp.bearing", centres[0], 15);
        double[] scCamp = clearOfRivers(Geo.toward(cx, cz, sb, r.range("sc.camp.out", 600, 840)), 40);
        hollows.add(new double[] {scCamp[0], scCamp[1], 24, 4, 9});
        landmarks.add(new Landmark(Kind.SHADOWCLAN_CAMP, scCamp[0], scCamp[1], 36, 0, r.seed("sc.camp")));
        carrion = Geo.toward(scCamp[0], scCamp[1], r.range("carrion.bearing", 300, 330), r.range("carrion.dist", 300, 500));
        landmarks.add(new Landmark(Kind.CARRIONPLACE, carrion[0], carrion[1], 44, 0, r.seed("carrion")));

        // RiverClan: an island camp in a widened stretch of the plains river, south-south-west of Fourtrees.
        double[] rc = null;
        for (double s = gorgeEnd + 200; s < riverPath.length(); s += 6) {
            double[] p = riverPath.pointAt(s);
            if (p[1] > cz + 80 && Math.hypot(p[0] - cx, p[1] - cz) > 500) {
                rc = p;
                break;
            }
        }
        riverCamp = rc == null ? riverPath.pointAt(riverPath.length() * 0.6) : rc;
        landmarks.add(new Landmark(Kind.RIVERCLAN_CAMP, riverCamp[0], riverCamp[1], 34, 0, r.seed("rc.camp")));

        // WindClan: a sandy hollow on the moor top.
        double wb = r.jitter("wc.camp.bearing", centres[3], 15);
        double[] wcCamp = clearOfRivers(Geo.toward(cx, cz, wb, r.range("wc.camp.out", 600, 840)), 40);
        hollows.add(new double[] {wcCamp[0], wcCamp[1], 24, 6, 8});
        landmarks.add(new Landmark(Kind.WINDCLAN_HOLLOW, wcCamp[0], wcCamp[1], 36, 0, r.seed("wc.camp")));

        // Beyond WindClan: Barley's farm, then Highstones, three craggy peaks with Mothermouth in the middle one.
        // (within 4 degrees of the Highstones strip, which is all the map covers out there)
        farm = Geo.toward(cx, cz, highstonesBearing + (r.range("farm.bearing", 285, 295) - 290) * 0.8, rf + r.range("farm.out", 300, 600));
        landmarks.add(new Landmark(Kind.BARLEY_FARM, farm[0], farm[1], 60, r.range("farm.face", 0, Geo.TAU), r.seed("farm")));

        // Where a cat can begin outside the Clans (the Campaign's starts): a kittypet's Twoleg garden just past
        // ThunderClan's woods beside the Twolegplace, as in the first story, and a loner's spot on the way to the farm.
        double kb = twolegBearing - r.range("kittypet.turn", 18, 26);
        double[] kp = Geo.toward(cx, cz, kb, outerEdge(kb) + 40);
        kittypetHome = new Landmark(Kind.KITTYPET_HOME, kp[0], kp[1], 26, Math.toRadians(kb + 180), r.seed("kittypet"));
        landmarks.add(kittypetHome);
        // (on the Highstones side, where the map runs out past the moor; the farm sits a little to one side of it)
        lonerSpot = Geo.toward(cx, cz, highstonesBearing, Math.hypot(farm[0] - cx, farm[1] - cz) - 110);
        peaks = new double[3][];
        double across = Math.toRadians(highstonesBearing + 90);
        for (int i = 0; i < 3; i++) {
            double off = (i - 1) * r.range("peak.spread." + i, 45, 70);
            peaks[i] = new double[] {hs[0] + Math.sin(across) * off, hs[1] - Math.cos(across) * off,
                    r.range("peak.radius." + i, 36, 52), r.range("peak.height." + i, 150, 172)};
        }
        double[] mm = Geo.toward(peaks[1][0], peaks[1][1], highstonesBearing + 180, peaks[1][2] * 0.55);
        landmarks.add(new Landmark(Kind.MOTHERMOUTH, mm[0], mm[1], 56, Math.toRadians(highstonesBearing + 180), r.seed("mothermouth")));

        // The Thunderpath as a road, its bed planned over the land (rivers bridged), with the two tunnels the books
        // tell of running under it: one on the way to Fourtrees, one some way to the west.
        double nearFourtrees = thunderpath.alongAt(cx, cz);
        double westward = -1;
        double limit = Math.min(thunderpath.length() - 80, nearFourtrees + 700);
        for (double a = nearFourtrees + r.range("culvert.west", 270, 330); a < limit && westward < 0; a += 10) {
            double[] p = thunderpath.pointAt(a);
            boolean dry = true;
            for (River river : rivers) if (river.path.distance(p[0], p[1], 60) < 40) dry = false;
            if (dry) westward = a;                                   // on dry land, clear of the river's bridge
        }
        thunderRoad = westward < 0
                ? Road.graded(thunderpath, 4.2, Road.Kind.THUNDERPATH, (x, z) -> sample(x, z, false).height(), nearFourtrees)
                : Road.graded(thunderpath, 4.2, Road.Kind.THUNDERPATH, (x, z) -> sample(x, z, false).height(), nearFourtrees, westward);
        roads = List.of(thunderRoad);

        double maxW = 0;
        for (int b = 0; b < 360; b += 5) maxW = Math.max(maxW, weightRadius(b));
        reach = maxW + FADE + 20;
    }

    private double outerEdge(double b) {
        double a = Math.toRadians(b);
        return rf + 120 * outer.at(Math.cos(a) * 0.9, Math.sin(a) * 0.9);
    }

    /** The map's extent by bearing: the territories, plus lobes out to Highstones and the Twolegplace. */
    private double weightRadius(double b) {
        double toHs = 1 - smoothstep(5, 13, Math.abs(Geo.angleDiff(b, highstonesBearing)));
        double toTwoleg = 1 - smoothstep(7, 15, Math.abs(Geo.angleDiff(b, twolegBearing)));
        return Math.max(outerEdge(b) + 60, Math.max(lerp(toHs, 0, rf + 1440), lerp(toTwoleg, 0, rf + 900)));
    }

    @Override
    public Sample sample(double x, double z) {
        return sample(x, z, true);
    }

    /** The plan at a column; without {@code water} the rivers are left uncut (for planning the road's bed). */
    private Sample sample(double x, double z, boolean water) {
        double dx = x - cx, dz = z - cz, d = Math.hypot(dx, dz), b = Geo.bearing(dx, dz);
        double wr = weightRadius(b);
        double weight = 1 - smoothstep(wr, wr + FADE, d);
        if (weight <= 0) {
            return Sample.OUTSIDE;
        }
        double edge = outerEdge(b);
        Zone zone;
        double h;
        if (d > edge) {
            double toHs = Math.abs(Geo.angleDiff(b, highstonesBearing)), toTl = Math.abs(Geo.angleDiff(b, twolegBearing));
            if (toHs < 12) {
                zone = Math.hypot(x - farm[0], z - farm[1]) < 100 ? Zone.TWOLEG_FARMLAND : Zone.HIGHSTONES_APPROACH;
                h = 90 + 18 * smoothstep(edge, edge + 1200, d) + 7 * hills.at(x, z) + 5 * broad.at(x, z);
            } else if (toTl < 14 && d < rf + 400) {
                zone = Zone.TALLPINES;
                h = 70 + 2 * hills.at(x, z);
            } else if (toTl < 14) {
                zone = Zone.TWOLEGPLACE;
                h = 68 + 1.5 * hills.at(x, z);
            } else {
                Clan clan = sectors.clanAt(b, d);
                zone = clanZone(clan, x, z, d);
                h = clanHeight(clan, x, z, d);
            }
        } else {
            Clan clan = clanAt(x, z, b, d);
            zone = clanZone(clan, x, z, d);
            h = clanHeight(clan, x, z, d);
            double[] nb = sectors.nearestBorder(b, d);
            if (nb[0] < 80) {
                h = lerp(0.5 * (1 - smoothstep(0, 80, nb[0])), h, clanHeight(sectors.clan((int) nb[1]), x, z, d));
            }
            if (Math.abs(Geo.angleDiff(b, twolegBearing)) < 14 && d > rf - 160) zone = Zone.TALLPINES;
        }
        if (Math.hypot(x - carrion[0], z - carrion[1]) < 60) zone = Zone.CARRIONPLACE;
        if (d < 90) zone = Zone.FOURTREES;

        // Highstones: three narrow craggy peaks, not mountains.
        for (double[] p : peaks) {
            double pr = Math.hypot(x - p[0], z - p[1]);
            if (pr < p[2] * 2.2) {
                double t = Math.max(0, 1 - pr / p[2]);
                double crag = 5 * hills.at(x * 2, z * 2);
                double spire = h + (p[3] - h) * Math.pow(t, 1.6) + crag * t;
                double skirt = lerp(smoothstep(p[2] * 2.2, p[2], pr), h, h + 22);
                h = Math.max(h, Math.max(spire, skirt));
                if (pr < p[2] * 1.3) zone = Zone.HIGHSTONES;
            }
        }
        double calm = 0;
        for (double[] hw : hollows) {
            h = LakeLands.hollow(h, x, z, hw);
            calm = Math.max(calm, LakeLands.hollowCalm(x, z, hw));
        }

        // The Thunderpath: level across, gently following the land along its length.
        if (thunderRoad != null) {
            double[] rn = thunderpath.nearest(x, z, thunderRoad.half() + 14);
            if (rn[0] < thunderRoad.half() + 8) h = thunderRoad.grade(h, rn[0], rn[1]);
            calm = Math.max(calm, 1 - smoothstep(thunderRoad.half() + 4, thunderRoad.half() + 14, rn[0]));
        }

        // The river: the moor river and the gorge stand on high ground; the plains river runs low.
        double[] gn = gorgeRiver.path.nearest(x, z, 60);
        if (gn[0] < 60) h = Math.max(h, lerp(smoothstep(60, 20, gn[0]), h, 104 + 6 * hills.at(x, z)));   // the gorge's rim
        double[] mn = moorRiver.path.nearest(x, z, 50);
        if (mn[0] < 50) h = Math.max(h, lerp(smoothstep(50, 10, mn[0]), h, MOOR_WATER + 4 + 3 * hills.at(x, z)));
        if (!water) {
            return new Sample(zone, h, weight, lerp(calm, zone.factor, LakeLands.CALM));
        }
        for (River river : rivers) {
            double[] n = river.path.nearest(x, z, river.reach() + 16);
            calm = Math.max(calm, 1 - smoothstep(river.reach(), river.reach() + 14, n[0]));
            if (n[0] > river.reach()) continue;
            h = river.carve(h, n[0], n[1]);
            if (n[0] < river.half + 1) zone = Zone.RIVER;
        }
        double ic = Math.hypot(x - riverCamp[0], z - riverCamp[1]);
        if (ic < 22) {
            double[] pn = plainsRiver.path.nearest(x, z, 40);
            if (pn[0] < 40) {
                h = Math.max(h, 64.5 + 1.5 * (1 - ic / 22));                        // the camp island
                zone = Zone.RIVERCLAN_PLAINS;
            }
        } else if (ic < 44) {
            double[] pn = plainsRiver.path.nearest(x, z, 40);
            if (pn[0] < 40) {
                h = Math.min(h, 59.5);                                              // the river spreads round the island
                zone = Zone.RIVER;
            }
        }
        if (ic < 50) calm = 1;
        return new Sample(zone, h, weight, lerp(calm, zone.factor, LakeLands.CALM));
    }

    /**
     * Moves a camp off the river and the Thunderpath: the water or the road may run near a camp (its wall or hollow),
     * never through it (seed -84596914's Thunderpath ran across the WindClan camp's wall, a second way in).
     */
    private double[] clearOfRivers(double[] at, double clear) {
        double x = at[0], z = at[1];
        for (int k = 0; k < 12; k++) {
            boolean moved = false;
            for (int i = 0; i <= rivers.size(); i++) {
                Path path = i < rivers.size() ? rivers.get(i).path : thunderpath;
                double need = clear + (i < rivers.size() ? rivers.get(i).half : 4.2 + 6);
                double[] n = path.nearest(x, z, need + 1);
                if (n[0] >= need) continue;
                double[] on = path.pointAt(n[1]);
                double dx = x - on[0], dz = z - on[1], l = Math.max(1e-6, Math.hypot(dx, dz));
                x = on[0] + dx / l * (need + 2);
                z = on[1] + dz / l * (need + 2);
                moved = true;
            }
            if (!moved) break;
        }
        return new double[] {x, z};
    }

    /** East of Fourtrees the Thunderpath, not the sector line, divides ShadowClan (north) from ThunderClan. */
    private Clan clanAt(double x, double z, double b, double d) {
        Clan clan = sectors.clanAt(b, d);
        if (x - cx > 220 && (clan == Clan.SHADOW || clan == Clan.THUNDER)) {
            clan = z < roadZ(x) ? Clan.SHADOW : Clan.THUNDER;
        }
        return clan;
    }

    /** The Thunderpath's z at a given x, from a table built once (the road runs roughly east-west). */
    private double roadZ(double x) {
        int i = (int) Math.round((x - roadX0) / 4.0);
        return roadZs[Math.max(0, Math.min(roadZs.length - 1, i))];
    }

    private double[] roadTable() {
        int n = (int) ((2 * (rf + FADE + 1600)) / 4) + 1;
        double[] t = new double[n];
        java.util.Arrays.fill(t, Double.NaN);
        for (double s = 0; s <= thunderpath.length(); s += 2) {
            double[] p = thunderpath.pointAt(s);
            int i = (int) Math.round((p[0] - roadX0) / 4.0);
            if (i >= 0 && i < n && Double.isNaN(t[i])) t[i] = p[1];
        }
        double last = cz - roadNorth;
        for (int i = 0; i < n; i++) if (Double.isNaN(t[i])) t[i] = last; else last = t[i];
        return t;
    }

    private double clanHeight(Clan clan, double x, double z, double d) {
        double n = hills.at(x, z), m = broad.at(x, z);
        double centre = 1 - smoothstep(80, 320, d);
        double h = switch (clan) {
            case THUNDER -> 72 + 10 * smoothstep(0, 800, d) + 7 * n + 5 * m;
            case SHADOW -> wet.at(x, z) > 0.2 ? lerp(smoothstep(0.2, 0.38, wet.at(x, z)), 66 + 2 * n, 62.6) : 67 + 5 * n + 2 * m;
            case RIVER -> 64.5 + 3 * n + 2 * m;
            case WIND -> 84 + 16 * smoothstep(200, 900, d) + 5 * n + 6 * m;
        };
        return lerp(centre, h, 82 + 2 * m + 2 * n);                                   // Fourtrees sits on a low rise
    }

    private Zone clanZone(Clan clan, double x, double z, double d) {
        return switch (clan) {
            case THUNDER -> Zone.THUNDERCLAN_FOREST;
            case SHADOW -> wet.at(x, z) > 0.26 ? Zone.SHADOWCLAN_MARSH : Zone.SHADOWCLAN_PINES;
            case RIVER -> Zone.RIVERCLAN_PLAINS;
            case WIND -> Zone.WINDCLAN_MOOR;
        };
    }

    /** The kittypet's Twoleg garden at the edge of ThunderClan's woods. */
    public Landmark kittypetHome() {
        return kittypetHome;
    }

    /** Where a loner begins: on the farm side of the moor, a little way in from the farm. */
    public double[] lonerSpot() {
        return lonerSpot.clone();
    }

    public Path thunderpath() {
        return thunderpath;
    }

    /** The Twoleg roads of the old forest: the Thunderpath, running on west toward Highstones. */
    public List<Road> roads() {
        return roads;
    }

    /** The moor river, the gorge river and the plains river, for the builders that lay their water and falls. */
    public List<River> rivers() {
        return rivers;
    }

    @Override public String name() { return "old forest"; }
    @Override public double centreX() { return cx; }
    @Override public double centreZ() { return cz; }
    @Override public double reach() { return reach; }
    @Override public List<Landmark> landmarks() { return landmarks; }
    /**
     * At Fourtrees, high on the hollow's inner wall (kept to ferns: the bushes start past the crest), in a gap between
     * two oaks (they stand on the diagonals of the landmark's facing), looking down on the Great Rock. (It was a fixed
     * 85 blocks out, past the rim in the woods: the Gathering's leaders on the Great Rock were then too far off for
     * the player's game to know them, so their lines showed medallions instead of the leaders, and trees hid the hollow.)
     */
    @Override public double[] spawn() {
        double d = fourtreesR - 2;
        return new double[] {cx + Math.cos(fourtreesTurn) * d, cz + Math.sin(fourtreesTurn) * d};
    }

    /** The spawn's facing in degrees: toward the Great Rock at the hollow's centre (the world's spawn angle). */
    public float spawnYaw() {
        return (float) Math.toDegrees(Math.atan2(Math.cos(fourtreesTurn), -Math.sin(fourtreesTurn)));
    }
}
