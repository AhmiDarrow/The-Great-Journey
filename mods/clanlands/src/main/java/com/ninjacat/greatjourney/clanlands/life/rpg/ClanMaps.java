package com.ninjacat.greatjourney.clanlands.life.rpg;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapData;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapLegend;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapPoi;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The Clan map in the journal: each of the three regions (the lake, the old forest, the Great Journey) drawn from the
 * world's own layout as a grid of coloured cells with a legend, the books' places and the camps as named markers,
 * the big areas (Twolegplace, Tallpines, the mountains...) labelled, and the player and their current goal marked.
 * The grid comes from the layout, not from explored chunks, so it shows the whole region from the start, like a
 * Clan's knowledge of its own lands. Grids are built once per world seed and kept.
 */
public final class ClanMaps {
    private ClanMaps() {}

    /** Cells along each side of a region's map. */
    public static final int SIZE = 160;
    public static final int LAKE = 0, FOREST = 1, JOURNEY = 2;
    static final List<String> REGIONS = List.of("The lake", "The old forest", "The Great Journey");

    /** How one kind of ground shows on the map: its legend entry and, for a big named area, a label on the map. */
    private record Look(String legend, int colour, String label) {}

    private static final Map<Zone, Look> LOOKS = new EnumMap<>(Zone.class);
    private static final Look WILD = new Look("Wild lands", 0x8E9C78, null);
    private static final Look ROAD = new Look("Thunderpath", 0x505056, null);
    private static final Look ROUTE = new Look("The Clans' journey", 0xA0302A, null);
    /** On the Great Journey's overview only the trip is named; the Clans' own lands each get one label. */
    private static final java.util.Set<String> JOURNEY_LABELS = java.util.Set.of("The sea", "The mountains", "The great Thunderpath");
    /** Under sea level outside the lake and rivers: the normal world's water. */
    private static final Look WATER = new Look("Water", 0x3E6FB8, null);

    static {
        LOOKS.put(Zone.LAKE, new Look("Water", 0x3E6FB8, "The lake"));
        LOOKS.put(Zone.RIVER, WATER);
        LOOKS.put(Zone.SUN_DROWN_SEA, new Look("Water", 0x3E6FB8, "The sea"));
        LOOKS.put(Zone.ISLAND, new Look("The island", 0x6FA352, null));
        LOOKS.put(Zone.THUNDERCLAN_FOREST, new Look("ThunderClan", 0x4C8A32, "ThunderClan"));
        LOOKS.put(Zone.WINDCLAN_MOOR, new Look("WindClan", 0xC4B066, "WindClan"));
        LOOKS.put(Zone.RIVERCLAN_MARSH, new Look("RiverClan", 0x6BB0A0, "RiverClan"));
        LOOKS.put(Zone.RIVERCLAN_WETLAND, new Look("RiverClan", 0x6BB0A0, "RiverClan"));
        LOOKS.put(Zone.RIVERCLAN_PLAINS, new Look("RiverClan", 0x6BB0A0, "RiverClan"));
        LOOKS.put(Zone.SHADOWCLAN_PINES, new Look("ShadowClan", 0x3E5A52, "ShadowClan"));
        LOOKS.put(Zone.SHADOWCLAN_MARSH, new Look("ShadowClan", 0x3E5A52, "ShadowClan"));
        LOOKS.put(Zone.MOONPOOL_HILLS, new Look("Hills", 0x9C9884, null));
        LOOKS.put(Zone.TWOLEG_FARMLAND, new Look("Twoleg farms", 0xDCC686, null));
        LOOKS.put(Zone.FOURTREES, new Look("Fourtrees", 0x8BCB52, null));
        LOOKS.put(Zone.HIGHSTONES_APPROACH, new Look("Open moor", 0xB0A67C, null));
        LOOKS.put(Zone.HIGHSTONES, new Look("Highstones", 0xB4B4B4, "Highstones"));
        LOOKS.put(Zone.TALLPINES, new Look("Tallpines", 0x55704A, "Tallpines"));
        LOOKS.put(Zone.TWOLEGPLACE, new Look("Twolegplace", 0xB8604C, "Twolegplace"));
        LOOKS.put(Zone.CARRIONPLACE, new Look("Carrionplace", 0x7E6C4A, "Carrionplace"));
        LOOKS.put(Zone.HIGHWAY_LAND, new Look("Thunderpath", 0x505056, "The great Thunderpath"));
        LOOKS.put(Zone.MOUNTAINS, new Look("Mountains", 0x9A9A90, "The mountains"));
        LOOKS.put(Zone.MOUNTAIN_PEAKS, new Look("Mountains", 0x9A9A90, "The mountains"));
        LOOKS.put(Zone.TRIBE_LANDS, new Look("The Tribe's land", 0x7F9DB8, null));
        LOOKS.put(Zone.SUN_DROWN_SHORE, new Look("Beach and dunes", 0xE6D8A0, null));
        LOOKS.put(Zone.JOURNEY_RIDGE, new Look("Open country", 0x95B86C, null));
    }

    /** The places the books name, as they show on the map (null: not marked). */
    static String placeName(Landmark.Kind k, int region) {
        return switch (k) {
            case FOURTREES -> "Fourtrees";
            case STONE_HOLLOW -> "ThunderClan camp";
            case THUNDERCLAN_RAVINE -> "ThunderClan camp";
            case WINDCLAN_HOLLOW -> "WindClan camp";
            case RIVERCLAN_CAMP -> "RiverClan camp";
            case SHADOWCLAN_CAMP -> "ShadowClan camp";
            case ISLAND_TREE -> "The Gathering island";
            case MOONPOOL -> "The Moonpool";
            case MOTHERMOUTH -> "Mothermouth";
            case SUNNINGROCKS -> "Sunningrocks";
            case SNAKEROCKS -> "Snakerocks";
            case OWL_TREE -> "The Owl Tree";
            case GREAT_SYCAMORE -> "The great sycamore";
            case SKY_OAK -> "The Sky Oak";
            case ANCIENT_OAK -> "The ancient oak";
            case ABANDONED_NEST -> "The abandoned Twoleg nest";
            case HORSEPLACE -> "The horseplace";
            case BARLEY_FARM -> "Barley's farm";
            case HALF_BRIDGE -> "The half-bridge";
            case TREE_BRIDGE -> "The tree-bridge";
            case CARRIONPLACE -> "Carrionplace";
            case TUNNELS -> "The tunnels";
            case KITTYPET_HOME -> region == FOREST ? "Twoleg garden" : "Purdy's garden";
            case STEPPING_STONES -> "The stepping stones";
            case TWOLEG_FARMS -> "Twoleg farms";
            case TRIBE_CAVE -> "The Tribe's cave";
            case MIDNIGHT_SETT -> "Midnight's sett";
            case LAKE_RIDGE -> "First sight of the lake";
            default -> null;                               // many small things (single oaks, nests, road stretches)
        };
    }

    private static Clan campClan(Landmark.Kind k) {
        return switch (k) {
            case STONE_HOLLOW, THUNDERCLAN_RAVINE -> Clan.THUNDER;
            case WINDCLAN_HOLLOW -> Clan.WIND;
            case RIVERCLAN_CAMP -> Clan.RIVER;
            case SHADOWCLAN_CAMP -> Clan.SHADOW;
            default -> null;
        };
    }

    /** A region's map without the player: built once per seed. */
    private record Base(int minX, int minZ, int cell, byte[] cells, byte[] shade, List<MapLegend> legend, List<MapPoi> pois) {}

    private static long cachedSeed = Long.MIN_VALUE;
    private static final Base[] CACHE = new Base[3];

    private static synchronized Base base(ClanWorld w, int region) {
        if (w.seed != cachedSeed) {
            java.util.Arrays.fill(CACHE, null);
            cachedSeed = w.seed;
        }
        if (CACHE[region] == null) CACHE[region] = build(w, region);
        return CACHE[region];
    }

    static ClanMap mapOf(ClanWorld w, int region) {
        return switch (region) {
            case FOREST -> w.forest;
            case JOURNEY -> w.journey;
            default -> w.lake;
        };
    }

    /** The maps a region's view is fitted to: its own, or for the Great Journey all three (the whole trip). */
    private static List<ClanMap> framed(ClanWorld w, int region) {
        return region == JOURNEY ? List.of(w.forest, w.journey, w.lake) : List.of(mapOf(w, region));
    }

    /** minX, minZ, maxX, maxZ of the land the region's maps really shape (not their empty padding). */
    private static double[] extent(ClanWorld w, int region) {
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (ClanMap m : framed(w, region)) {
            double half = m.reach(), step = 2 * half / 128;
            for (int j = 0; j <= 128; j++) {
                for (int i = 0; i <= 128; i++) {
                    double x = m.centreX() - half + i * step, z = m.centreZ() - half + j * step;
                    if (m.sample(x, z).weight() < 0.5) continue;
                    box[0] = Math.min(box[0], x);
                    box[1] = Math.min(box[1], z);
                    box[2] = Math.max(box[2], x);
                    box[3] = Math.max(box[3], z);
                }
            }
        }
        if (box[0] > box[2]) {                                   // nothing shaped: the whole box
            ClanMap m = mapOf(w, region);
            return new double[] {m.centreX() - m.reach(), m.centreZ() - m.reach(), m.centreX() + m.reach(), m.centreZ() + m.reach()};
        }
        return box;
    }

    private static Base build(ClanWorld w, int region) {
        double[] box = extent(w, region);
        double side = Math.max(box[2] - box[0], box[3] - box[1]) * 1.08 + 96;
        int cell = Math.max(1, (int) Math.ceil(side / SIZE));
        int minX = (int) Math.floor((box[0] + box[2]) / 2) - SIZE * cell / 2;
        int minZ = (int) Math.floor((box[1] + box[3]) / 2) - SIZE * cell / 2;
        double maxX = minX + (double) SIZE * cell, maxZ = minZ + (double) SIZE * cell;
        Map<String, Integer> legendIndex = new LinkedHashMap<>();
        List<MapLegend> legend = new ArrayList<>();
        Function<Look, Integer> index = look -> legendIndex.computeIfAbsent(look.legend(), k -> {
            legend.add(new MapLegend(look.legend(), look.colour()));
            return legend.size() - 1;
        });
        byte[] cells = new byte[SIZE * SIZE], shade = new byte[SIZE * SIZE];
        double[] heights = new double[SIZE * SIZE];
        Zone[] zones = new Zone[SIZE * SIZE];
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                double x = minX + (i + 0.5) * cell, z = minZ + (j + 0.5) * cell;
                Sample s = w.sample(x, z);                       // the whole world: neighbouring lands show too
                Look look;
                if (s.zone() == null || s.weight() < 0.5) {
                    look = WILD;
                } else {
                    look = LOOKS.getOrDefault(s.zone(), WILD);
                    zones[j * SIZE + i] = s.zone();
                    if (s.height() < 62.5 && look.colour() != WATER.colour()) look = WATER;
                }
                heights[j * SIZE + i] = s.weight() > 0 ? s.height() : 66;
                cells[j * SIZE + i] = (byte) (int) index.apply(look);
            }
        }
        // a little relief, lit from the north-west, so hills and valleys read
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                double h = heights[j * SIZE + i];
                double wv = i > 0 ? heights[j * SIZE + i - 1] : h, nv = j > 0 ? heights[(j - 1) * SIZE + i] : h;
                double f = Math.max(0.7, Math.min(1.25, 1 + ((h - wv) + (h - nv)) / (5.0 * Math.max(2, cell))));
                shade[j * SIZE + i] = (byte) Math.round((f - 0.7) / 0.55 * 255);
            }
        }
        // roads over everything else
        List<ClanMap> all = List.of(w.lake, w.forest, w.journey);
        for (ClanMap m : all) {
            for (Road road : m.roads()) {
                int ri = index.apply(ROAD);
                for (double s = 0; s <= road.path().length(); s += Math.max(1, cell / 2.0)) {
                    double[] pt = road.path().pointAt(s);
                    int i = (int) Math.floor((pt[0] - minX) / cell), j = (int) Math.floor((pt[1] - minZ) / cell);
                    if (i >= 0 && j >= 0 && i < SIZE && j < SIZE) cells[j * SIZE + i] = (byte) ri;
                }
            }
        }

        // the way the Clans walked, dotted across the overview
        if (region == JOURNEY) {
            int ri = index.apply(ROUTE);
            var route = w.journey.route();
            for (double s = 0; s <= route.length(); s += Math.max(1, cell / 2.0)) {
                if ((int) (s / (cell * 2.0)) % 2 == 1) continue;
                double[] pt = route.pointAt(s);
                int i = (int) Math.floor((pt[0] - minX) / cell), j = (int) Math.floor((pt[1] - minZ) / cell);
                if (i >= 0 && j >= 0 && i < SIZE && j < SIZE) cells[j * SIZE + i] = (byte) ri;
            }
        }

        List<MapPoi> pois = new ArrayList<>();
        // the big areas, labelled at the cell of each nearest its middle
        Map<Zone, double[]> sums = new EnumMap<>(Zone.class);
        for (int k = 0; k < zones.length; k++) {
            if (zones[k] == null) continue;
            double[] acc = sums.computeIfAbsent(zones[k], z -> new double[3]);
            acc[0] += k % SIZE;
            acc[1] += k / SIZE;
            acc[2]++;
        }
        // one label per text (RiverClan has three zones); the lake and the forest each have a ThunderClan, so a
        // label goes on each separate patch big enough to read
        Map<String, double[]> labels = new LinkedHashMap<>();
        for (var e : sums.entrySet()) {
            Look look = LOOKS.get(e.getKey());
            if (look == null || look.label() == null || e.getValue()[2] < 12) continue;
            if (region == JOURNEY && !JOURNEY_LABELS.contains(look.label())) continue;
            double[] acc = labels.computeIfAbsent(look.label(), t -> new double[3]);
            for (int q = 0; q < 3; q++) acc[q] += e.getValue()[q];
        }
        for (var e : labels.entrySet()) {
            for (int[] patch : patches(zones, e.getKey())) {
                if (patch[2] < 12) continue;
                pois.add(new MapPoi(e.getKey(), MapPoi.AREA, 0, minX + (patch[0] + 0.5) * cell, minZ + (patch[1] + 0.5) * cell));
            }
        }
        if (region == JOURNEY) {
            pois.add(new MapPoi("The old forest", MapPoi.AREA, 0, w.forest.centreX(), w.forest.centreZ()));
            pois.add(new MapPoi("The lake", MapPoi.AREA, 0, w.lake.centreX(), w.lake.centreZ()));
        }
        // the books' places and the camps, of every map, where they fall on this one (the overview: the trip's own)
        for (int r = 0; r < 3; r++) {
            if (region == JOURNEY && r != JOURNEY) continue;
            ClanMap m = mapOf(w, r);
            for (Landmark l : m.landmarks()) {
                if (l.x() < minX || l.z() < minZ || l.x() >= maxX || l.z() >= maxZ) continue;
                String name = placeName(l.kind(), r);
                if (name == null) continue;
                Clan c = campClan(l.kind());
                boolean camp = c != null && (r == LAKE ? l.equals(w.lake.camp(c)) : r == FOREST && l.equals(Homeland.forestCamp(w, c)));
                if (c != null && !camp) continue;
                boolean dup = false;
                for (MapPoi p : pois) if (p.name().equals(name) && Math.hypot(p.x() - l.x(), p.z() - l.z()) < 3 * cell) dup = true;
                if (dup) continue;
                pois.add(new MapPoi(name, camp ? MapPoi.CAMP : MapPoi.PLACE, camp ? Info.of(c).colour : 0x3B2A1A, l.x(), l.z()));
            }
        }
        if (w.lake.tunnels() != null && region != JOURNEY) {
            double tx = w.lake.tunnels().cx, tz = w.lake.tunnels().cz;
            if (tx >= minX && tz >= minZ && tx < maxX && tz < maxZ) pois.add(new MapPoi("The tunnels", MapPoi.PLACE, 0x3B2A1A, tx, tz));
        }
        return new Base(minX, minZ, cell, cells, shade, List.copyOf(legend), List.copyOf(pois));
    }

    /**
     * The separate patches of ground that carry {@code label}: for each, the cell nearest its middle and its size,
     * as {i, j, cells}. Neighbouring cells (8 ways) belong to one patch.
     */
    private static List<int[]> patches(Zone[] zones, String label) {
        boolean[] mine = new boolean[zones.length], seen = new boolean[zones.length];
        for (int k = 0; k < zones.length; k++) {
            Look look = zones[k] == null ? null : LOOKS.get(zones[k]);
            mine[k] = look != null && label.equals(look.label());
        }
        List<int[]> out = new ArrayList<>();
        int[] stack = new int[zones.length];
        for (int start = 0; start < zones.length; start++) {
            if (!mine[start] || seen[start]) continue;
            List<Integer> members = new ArrayList<>();
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            double sx = 0, sz = 0;
            while (top > 0) {
                int k = stack[--top];
                members.add(k);
                sx += k % SIZE;
                sz += k / SIZE;
                int i = k % SIZE, j = k / SIZE;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int ni = i + di, nj = j + dj;
                        if (ni < 0 || nj < 0 || ni >= SIZE || nj >= SIZE) continue;
                        int nk = nj * SIZE + ni;
                        if (mine[nk] && !seen[nk]) {
                            seen[nk] = true;
                            stack[top++] = nk;
                        }
                    }
                }
            }
            double cx = sx / members.size(), cz = sz / members.size();
            int best = members.get(0);
            double bestD = Double.MAX_VALUE;
            for (int k : members) {
                double d = Math.hypot(k % SIZE - cx, k / SIZE - cz);
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            out.add(new int[] {best % SIZE, best / SIZE, members.size()});
        }
        return out;
    }

    /** A region's map with nobody on it, for tests and previews. */
    public static MapData preview(ClanWorld w, int region) {
        Base b = build(w, region);
        return new MapData(region, REGIONS, b.minX, b.minZ, b.cell, SIZE, b.cells, b.shade, b.legend, b.pois,
                false, 0, 0, 0, false, "", 0, 0);
    }

    /** Which region the player is in (where the layout weighs most), else the one whose middle is nearest. */
    static int regionAt(ClanWorld w, double x, double z) {
        int best = LAKE;
        double bestW = 0;
        for (int r = 0; r < 3; r++) {
            ClanMap m = mapOf(w, r);
            if (!m.covers(x, z)) continue;
            double wt = m.sample(x, z).weight();
            if (wt > bestW) {
                bestW = wt;
                best = r;
            }
        }
        if (bestW > 0) return best;
        double bestD = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            ClanMap m = mapOf(w, r);
            double d = Math.hypot(x - m.centreX(), z - m.centreZ()) / Math.max(1, m.reach());
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    /** Where the player is going: set by the Campaign (its chapter's place) or a quest (a place to visit). */
    public record Goal(String name, double x, double z) {}

    private static volatile Function<ServerPlayer, Goal> goals = p -> null;

    /** Installs who decides the player's goal on the map (the Campaign, then quests). */
    public static void goals(Function<ServerPlayer, Goal> f) {
        goals = f;
    }

    /** The map of {@code region} (or, for -1, the region the player is in) for this player. */
    public static MapData view(ServerPlayer p, int region) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return null;
        boolean here = p.level().dimension() == Level.OVERWORLD;
        if (region < 0 || region > 2) region = here ? regionAt(w, p.getX(), p.getZ()) : LAKE;
        Base b = base(w, region);
        Goal g = null;
        try {
            g = goals.apply(p);
        } catch (RuntimeException ignored) {
            // a goal that cannot be worked out just isn't drawn
        }
        return new MapData(region, REGIONS, b.minX, b.minZ, b.cell, SIZE, b.cells, b.shade, b.legend, b.pois,
                here, p.getX(), p.getZ(), p.getYRot(),
                g != null, g == null ? "" : g.name(), g == null ? 0 : g.x(), g == null ? 0 : g.z());
    }
}
