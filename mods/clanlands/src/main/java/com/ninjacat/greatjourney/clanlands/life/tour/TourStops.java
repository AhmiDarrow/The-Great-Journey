package com.ninjacat.greatjourney.clanlands.life.tour;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Landmark.Kind;
import com.ninjacat.greatjourney.clanlands.layout.River;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.layout.Tunnels;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.TourStop;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;

/**
 * The guided tour's stops, in the order of the books: the old forest, the Great Journey, then the lake. Every place
 * comes from the running world's own layout, so each world's tour shows that world; a place a world lacks is left out.
 */
final class TourStops {
    private TourStops() {}

    /** A stop, plus (for the roadside stops) the road and the spot a monster should drive past. */
    record Planned(TourStop stop, Road road, double along) {}

    static List<Planned> build(ClanWorld w) {
        List<Planned> out = new ArrayList<>();
        ClanMap forest = w.forest, lake = w.lake, journey = w.journey;

        // the old forest
        // Fourtrees: from above and beyond the gap between two oaks (they stand on the diagonals of the landmark's
        // facing), looking down at about 50 degrees onto the Great Rock on the open grass. The trunks stand 30 to 35 out
        // and the crowns spread about 15 further and rise about 36 over the floor, so the camera stands 64 out and 84
        // up: the whole ring of four crowns stays inside the picture (between the cinema bars) for the whole push in,
        // at the widest spacing too. Higher and steeper would frame it as well, but would bring the camera up near the
        // clouds (y 192) on a high floor. The base is the layout's ground, not the canopy.
        Landmark ft = find(forest, Kind.FOURTREES);
        if (ft != null) {
            double base = forest.sample(ft.x(), ft.z()).height();
            add(out, new TourStop("Fourtrees",
                    "Four great oaks stand at the heart of the forest, one for each Clan. Here, under the full moon, the Clans meet in peace.",
                    TourStop.DOWN, ft.x(), base + 8, ft.z(), 0, 20, ft.facing(), 11, new double[] {64, 84, 14}), null, 0);
        }
        // the ravine: from just past its rim, steeply down, so the stone walls and the clearing floor fill the view
        Landmark rav = find(forest, Kind.THUNDERCLAN_RAVINE);
        if (rav != null) {
            double floor = forest.sample(rav.x(), rav.z()).height();
            add(out, new TourStop("The ThunderClan Camp",
                    "Deep in the oak woods, a rocky ravine hides ThunderClan's camp, safe behind its stone walls and thick gorse.",
                    TourStop.DOWN, rav.x(), floor + 1, rav.z(), 0, 22, rav.facing() + 0.8, 11, new double[] {26, 42, 60}), null, 0);
        }
        Landmark sun = find(forest, Kind.SUNNINGROCKS);
        orbitPair(out, false, forest, sun, nearest(forest, Kind.STEPPING_STONES, sun, 140), "Sunningrocks",
                "Warm grey rocks beside the river, where cats love to bask in the sun. Just upstream, stepping stones cross the water.");
        orbit(out, find(forest, Kind.RIVERCLAN_CAMP), 0, "The RiverClan Camp",
                "Among the reeds by the river, RiverClan's cats swim and fish as easily as other cats hunt mice.");
        roadside(out, road(forest, Road.Kind.THUNDERPATH), null, "The Thunderpath",
                "A hard black path that smells of smoke. Monsters roar along it, so a wise cat waits, listens, and crosses only when all is quiet.");
        Landmark shadow = find(forest, Kind.SHADOWCLAN_CAMP);
        orbitPair(out, true, forest, shadow, nearest(forest, Kind.CARRIONPLACE, shadow, 170), "ShadowClan and Carrionplace",
                "Among the dark pines, ShadowClan keeps its camp. Beyond it lies Carrionplace, where the Twolegs leave their rubbish and rats gather.");
        hollow(out, find(forest, Kind.WINDCLAN_HOLLOW), 26, "The WindClan Moor",
                "Open moorland under a wide sky. WindClan's cats are the fastest runners of all, chasing rabbits through the heather.");
        gorge(out, forest);
        Landmark mm = find(forest, Kind.MOTHERMOUTH);
        if (mm != null) {
            add(out, new TourStop("Mothermouth",
                    "Far beyond the forest, the high stones hide Mothermouth. Deep inside, the Moonstone shines, and cats share dreams with StarClan.",
                    TourStop.PUSH, mm.x(), Double.NaN, mm.z(), 1, 3, mm.facing() - Math.PI / 2, 10, new double[0]), null, 0);
        }
        // the barn (a cottage, door on its south side) stands 12 east and 10 north of the farm's centre, inside the
        // fence ring: from the south, close and a little above its roof, so the barn is the picture and the farm's fence
        // posts run off to the side
        Landmark farm = find(forest, Kind.BARLEY_FARM);
        if (farm != null) {
            double bx = farm.x() + 12, bz = farm.z() - 10, bg = forest.sample(bx, bz).height();
            // the barn is built into a dip (playtest 2026-10-12: the land round it stands as high as its walls, and the
            // barley on the rise hides what is left), so it is looked at from the side where the land lies lowest
            double bestHead = -Math.PI / 2, lowest = 1e9;
            for (int k = 0; k < 12; k++) {
                double head = k * Math.PI / 6, sum = 0;
                for (double off = -32; off <= 32; off += 32) {
                    double a = head + Math.toRadians(off);
                    for (double r = 6; r <= 20; r += 4) sum += forest.sample(bx + Math.cos(a) * r, bz + Math.sin(a) * r).height();
                }
                if (sum < lowest) {
                    lowest = sum;
                    bestHead = head;
                }
            }
            add(out, new TourStop("Barley's Farm",
                    "A kindly loner named Barley lives here among the barns, where travelling cats can always find a mouse and a place to rest.",
                    TourStop.ORBIT, bx, bg + 3, bz, 0, 12, bestHead, 11, new double[] {19, 12, 1, 56}), null, 0);
        }

        // the Great Journey
        if (w.journey.highway() != null) {
            double[] cross = w.journey.highwayCrossing();
            roadside(out, w.journey.highway(), cross, "The Great Thunderpath",
                    "The widest Thunderpath of all. On the Great Journey, the Clans had to cross it together, one brave dash at a time.");
        }
        Landmark tribe = find(journey, Kind.TRIBE_CAVE);
        if (tribe != null) {
            double fx = Math.cos(tribe.facing()), fz = Math.sin(tribe.facing());
            add(out, new TourStop("The Tribe of Rushing Water",
                    "High in the mountains, a roaring waterfall hides the cave of the Tribe of Rushing Water, cats who live among the rocks and clouds.",
                    TourStop.PUSH, tribe.x() + fx * 8, Double.NaN, tribe.z() + fz * 8, 6, 20, tribe.facing(), 11, new double[0]), null, 0);
        }
        Landmark sett = find(journey, Kind.MIDNIGHT_SETT);
        if (sett != null) {
            // the entrance faces the sea, which lies far off across the beach (a hundred blocks and more), so it is out of
            // the picture from here. The camera circles the entrance from the sea side, a little round to one side, further
            // out and well above the palms: the mouth in the foot of the bank, with the beach spread out in front of it
            double sx = Math.cos(sett.facing()), sz = Math.sin(sett.facing());
            add(out, new TourStop("Midnight's Sett",
                    "By the sea, where the sun drowns, the wise badger Midnight waited to tell the Clans that they must find a new home.",
                    TourStop.ORBIT, sett.x() + sx * 4, journey.sample(sett.x() + sx * 6, sett.z() + sz * 6).height() + 1, sett.z() + sz * 4, 0, 18,
                    sett.facing() + Math.toRadians(40), 10, new double[] {38, 26, 0, 36}), null, 0);
        }
        Landmark ridge = find(journey, Kind.LAKE_RIDGE);
        if (ridge != null) {
            double h = Math.atan2(w.lake.centreZ() - ridge.z(), w.lake.centreX() - ridge.x());
            // look at the near shore, not past it: the land only draws about 190 blocks out
            double reach = Math.min(150, Math.hypot(w.lake.centreX() - ridge.x(), w.lake.centreZ() - ridge.z()) * 0.6);
            // (the lake is usually far beyond that: then the eyes rest a little below the ridge, on the land falling away)
            // the eyes rest just above the ridge's own height, so the camera looks level out over the land with the horizon
            // in the picture (looking down onto the grass showed nothing else)
            double fy = journey.sample(ridge.x(), ridge.z()).height() - 2;
            for (double d = 16; d <= 300; d += 8) {
                var smp = w.sample(ridge.x() + Math.cos(h) * d, ridge.z() + Math.sin(h) * d);
                if (smp.zone() == com.ninjacat.greatjourney.clanlands.layout.Zone.LAKE) {
                    reach = Math.min(190, d + 50);
                    fy = 62;
                    break;
                }
            }
            double[] towardLake = {ridge.x() + Math.cos(h) * reach, fy, ridge.z() + Math.sin(h) * reach};
            add(out, new TourStop("The First Sight of the Lake",
                    "From the top of the ridge, the Clans first glimpsed shining water far away, still a day and a half's journey off: the lake, and a new home for all four Clans.",
                    TourStop.VISTA, ridge.x(), Double.NaN, ridge.z(), 0, 20, h, 12, towardLake), null, 0);
        }

        // the lake
        Landmark island = find(lake, Kind.ISLAND_TREE), bridge = find(lake, Kind.TREE_BRIDGE);
        if (island != null) {
            double h = bridge != null ? Math.atan2(bridge.z() - island.z(), bridge.x() - island.x()) : 0.7;
            // aimed at the island's ground and the oak's trunk, not the oak's crown (which the ground there reads as)
            double ig = lake.sample(island.x(), island.z()).height();
            add(out, new TourStop("The Island",
                    "Out in the lake lies a little island. A fallen tree makes a bridge from the shore, and here the Clans hold their Gatherings now.",
                    TourStop.ORBIT, island.x(), ig + 8, island.z(), 0, 55, h, 11, new double[] {66, 34, 4, 80}), null, 0);
        }
        Landmark marsh = find(lake, Kind.RIVERCLAN_CAMP);
        orbitPair(out, false, lake, marsh, nearest(lake, Kind.STEPPING_STONES, marsh, 160), "RiverClan's Marsh",
                "RiverClan made its new camp among streams and reeds, with stepping stones for any paws that would rather stay dry.");
        orbit(out, find(lake, Kind.STONE_HOLLOW), 0, "The Stone Hollow",
                "ThunderClan's new home: an old stone hollow ringed by cliffs, with dens in the rock and a high ledge where the leader speaks.");
        hollow(out, find(lake, Kind.WINDCLAN_HOLLOW), 26, "WindClan's Hills and Tallrock",
                "WindClan found open hills again, with the wind in their fur and Tallrock standing guard over the moor.");
        Landmark pines = find(lake, Kind.SHADOWCLAN_CAMP);
        roadside(out, road(lake, Road.Kind.THUNDERPATH), pines == null ? null : new double[] {pines.x(), pines.z()}, "ShadowClan's Pines",
                "ShadowClan settled in the quiet pine forest, where soft needles hush every pawstep. A Thunderpath runs along its border.");
        tunnels(out, w.lake.tunnels());
        double[] moon = w.lake.moonpool();
        if (moon != null) {
            double h = Math.atan2(moon[1] - w.lake.centreZ(), moon[0] - w.lake.centreX());
            add(out, new TourStop("The Moonpool",
                    "High above the lake, a stream tumbles down the rocks into a pool that shines with starlight. Here, medicine cats share dreams with StarClan.",
                    TourStop.DOWN, moon[0], Double.NaN, moon[1], 0, 12, h, 11, new double[0]), null, 0);
        }
        Landmark home = w.lake.kittypetHome();
        if (home != null) {
            add(out, new TourStop("The Twoleg Nest",
                    "Near ThunderClan's border stands a Twoleg nest with a garden. A kittypet lives here, and wonders what lies beyond the fence.",
                    TourStop.DOWN, home.x(), Double.NaN, home.z(), 0, 16, home.facing(), 10, new double[0]), null, 0);
        }
        return out;
    }

    private static void add(List<Planned> out, TourStop s, Road road, double along) {
        out.add(new Planned(s, road, along));
    }

    private static Landmark find(ClanMap m, Kind kind) {
        for (Landmark l : m.landmarks()) if (l.kind() == kind) return l;
        return null;
    }

    /** The landmark of that kind nearest to {@code from}, if one lies within {@code within} blocks. */
    private static Landmark nearest(ClanMap m, Kind kind, Landmark from, double within) {
        if (from == null) return null;
        Landmark best = null;
        double bestD = within;
        for (Landmark l : m.landmarks()) {
            if (l.kind() != kind) continue;
            double d = Math.hypot(l.x() - from.x(), l.z() - from.z());
            if (d <= bestD) {
                bestD = d;
                best = l;
            }
        }
        return best;
    }

    private static Road road(ClanMap m, Road.Kind kind) {
        for (Road r : m.roads()) if (r.kind() == kind) return r;
        return null;
    }

    private static void orbit(List<Planned> out, Landmark l, double extra, String title, String caption) {
        if (l == null) return;
        add(out, new TourStop(title, caption, TourStop.ORBIT, l.x(), Double.NaN, l.z(), 0, Math.min(70, l.radius()) + extra,
                l.facing() + 0.8, 11, new double[0]), null, 0);
    }

    /** A camp in a hollow, seen from close above its rim so the hollow fills a good part of the view. */
    private static void hollow(List<Planned> out, Landmark l, double radius, String title, String caption) {
        if (l == null) return;
        add(out, new TourStop(title, caption, TourStop.DOWN, l.x(), Double.NaN, l.z(), 0, radius, l.facing() + 0.8, 11, new double[0]), null, 0);
    }

    /** A circle that takes in two places at once (a camp and its neighbour), or just the first if the second is missing. */
    private static void orbitPair(List<Planned> out, boolean high, ClanMap m, Landmark a, Landmark b, String title, String caption) {
        if (a == null) return;
        if (high && b == null) {                                       // no neighbour near enough: just the camp, from above the pines
            add(out, new TourStop(title, caption, TourStop.ORBIT, a.x(), m.sample(a.x(), a.z()).height() + 2, a.z(), 0, 40,
                    a.facing() + 0.8, 12, new double[] {40, 64, 2, 60}), null, 0);
            return;
        }
        if (b == null) {
            orbit(out, a, 0, title, caption);
            return;
        }
        double mx = (a.x() + b.x()) / 2, mz = (a.z() + b.z()) / 2, half = Math.hypot(a.x() - b.x(), a.z() - b.z()) / 2;
        double h = Math.atan2(b.z() - a.z(), b.x() - a.x()) + Math.PI / 2;     // side-on, so both are in view
        double d = Math.min(96, Math.min(70, half + 18) * 1.25 + 18);
        if (!high) {
            add(out, new TourStop(title, caption, TourStop.ORBIT, mx, Double.NaN, mz, 0, Math.min(70, half + 18), h, 12,
                    new double[] {d * 0.75, d * 0.45 + 4, 2, 100}), null, 0);
            return;
        }
        // tall pines: far above them, steeply down onto the camp's own ground (not the treetops), from the side away
        // from the neighbour so it lies beyond the camp
        double base = m.sample(a.x(), a.z()).height();
        double away = Math.atan2(b.z() - a.z(), b.x() - a.x()) + Math.PI;
        add(out, new TourStop(title, caption, TourStop.ORBIT, a.x(), base + 2, a.z(), 0, 40, away, 12,
                new double[] {40, 64, 2, 60}), null, 0);
    }

    /** Along a road near {@code near} (or its middle), with a monster sent past while the camera watches. */
    private static void roadside(List<Planned> out, Road road, double[] near, String title, String caption) {
        if (road == null) return;
        double len = road.path().length();
        double mid = near == null ? len / 2 : road.path().nearest(near[0], near[1], 1e6)[1];
        double from = Math.max(0, Math.min(len - 130, mid - 50)), to = Math.min(len, from + 130);
        List<Double> pts = new ArrayList<>();
        for (double s = from; s <= to; s += 6) {
            double[] p = road.path().pointAt(s);
            pts.add(p[0]);
            pts.add(p[1]);
        }
        double[] path = pts.stream().mapToDouble(Double::doubleValue).toArray();
        double[] c = road.path().pointAt((from + to) / 2);
        // the monster comes past the stretch the camera looks at, two thirds of the way along
        add(out, new TourStop(title, caption, TourStop.SWEEP, c[0], Double.NaN, c[1], 0, road.half(), 0, 14, path), road, from + (to - from) * 0.66);
    }

    /** The tallest waterfall in the old forest's rivers: the gorge. */
    private static void gorge(List<Planned> out, ClanMap forest) {
        if (!(forest instanceof com.ninjacat.greatjourney.clanlands.layout.OldForest of)) return;
        River best = null;
        double[] fall = null;
        for (River r : of.rivers()) {
            for (double[] f : r.falls()) {
                if (fall == null || f[1] - f[2] > fall[1] - fall[2]) {
                    fall = f;
                    best = r;
                }
            }
        }
        if (best == null) return;
        double[] at = best.path.pointAt(fall[0]), down = best.path.pointAt(Math.min(best.path.length(), fall[0] + 6));
        double h = Math.atan2(down[1] - at[1], down[0] - at[0]);         // the camera waits downstream, looking up the falls
        add(out, new TourStop("The Gorge",
                "Here the river thunders down into a deep gorge. Even the bravest cats keep well back from the edge.",
                TourStop.PUSH, at[0], (fall[1] + fall[2]) / 2, at[1], 0, best.half + 6, h, 11, new double[0]), null, 0);
    }

    /** A tunnel mouth in the hillside, seen from outside: the camera drifts in toward the dark opening. */
    private static void tunnels(List<Planned> out, Tunnels t) {
        if (t == null || t.bores.isEmpty() || t.entrances.isEmpty()) return;
        Tunnels.Bore b = t.bores.get(0);
        int n = b.xs().length;
        if (n < 8) return;
        double[] mouth = t.entrances.get(0);
        double ix = b.xs()[Math.min(n - 1, 8)] - b.xs()[0], iz = b.zs()[Math.min(n - 1, 8)] - b.zs()[0];
        double out0 = Math.atan2(-iz, -ix);                     // from the mouth back out, away from the hill
        add(out, new TourStop("The Tunnels",
                "Under the hills, old tunnels wind deep into the dark. A cat could easily lose its way down there, so never explore them alone.",
                TourStop.PUSH, mouth[0], mouth[1] - 1, mouth[2], 0, -4, out0, 11, new double[0]), null, 0);
    }
}
