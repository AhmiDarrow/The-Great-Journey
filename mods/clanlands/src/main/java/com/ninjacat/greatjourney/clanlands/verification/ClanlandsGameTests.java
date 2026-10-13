package com.ninjacat.greatjourney.clanlands.verification;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** The Clan lands as each seed lays them out. Plans are drawn to build/verification/clanlands-maps. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class ClanlandsGameTests {
    private ClanlandsGameTests() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final long[] SEEDS = {20261011L, 3776135913213891029L, -42L, 4242L};

    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void everySeedDrawsTheBookMaps(GameTestHelper h) throws Exception {
        for (long seed : SEEDS) {
            ClanWorld w = ClanWorld.layoutOnly(seed);
            for (ClanMap m : w.maps()) {
                if (m.reach() <= 0) continue;                          // a map still being laid out (no area yet)
                MapRender.render(m, 4, Path.of("clanlands-maps", m.name().replace(' ', '_') + "-" + seed + ".png"));
                LOG.info("Clanlands seed {} {}: centre ({}, {}), reach {}, {} landmarks", seed, m.name(),
                        (int) m.centreX(), (int) m.centreZ(), (int) m.reach(), m.landmarks().size());
            }
            // the four lake Clans all hold ground, and the island and Moonpool exist
            Set<Zone> seen = new HashSet<>();
            for (int a = 0; a < 360; a += 3) {
                for (int d = 0; d < 3300; d += 25) {
                    double[] p = com.ninjacat.greatjourney.clanlands.layout.Geo.toward(w.lake.centreX(), w.lake.centreZ(), a, d);
                    Sample s = w.lake.sample(p[0], p[1]);
                    if (s.zone() != null) seen.add(s.zone());
                }
            }
            for (Zone z : new Zone[] {Zone.LAKE, Zone.THUNDERCLAN_FOREST, Zone.WINDCLAN_MOOR, Zone.SHADOWCLAN_PINES, Zone.MOONPOOL_HILLS}) {
                h.assertTrue(seen.contains(z), "seed " + seed + ": the lake map has " + z);
            }
            h.assertTrue(seen.contains(Zone.RIVERCLAN_MARSH) || seen.contains(Zone.RIVERCLAN_WETLAND), "seed " + seed + ": RiverClan holds ground");
            for (Landmark l : w.lake.landmarks()) {
                Sample s = w.lake.sample(l.x(), l.z());
                h.assertTrue(s.weight() > 0.5, "seed " + seed + ": " + l.kind() + " lies inside the lake map");
            }
            double fd = Math.hypot(w.forest.centreX(), w.forest.centreZ());
            h.assertTrue(fd > 9900 && fd < 12600, "seed " + seed + ": the old forest is 10-12.5 km away, got " + (int) fd);
            h.assertTrue(w.forest.sample(w.forest.centreX(), w.forest.centreZ()).zone() == Zone.FOURTREES, "Fourtrees is the forest's centre");
            h.assertTrue(w.lake.sample(w.forest.centreX(), w.forest.centreZ()).weight() == 0, "the two maps do not overlap");
        }
        h.succeed();
    }

    /** Every old-forest landmark, Barley's farm and Mothermouth most of all, lies inside the map on any seed. */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void forestLandmarksStayInsideTheMap(GameTestHelper h) {
        for (long seed = -80; seed <= 80; seed++) {
            com.ninjacat.greatjourney.clanlands.layout.OldForest forest = new com.ninjacat.greatjourney.clanlands.layout.OldForest(seed);
            for (Landmark l : forest.landmarks()) {
                Sample s = forest.sample(l.x(), l.z());
                h.assertTrue(s.weight() > 0.9, "seed " + seed + ": " + l.kind() + " lies inside the old forest map (weight " + s.weight() + ")");
                if (l.kind() == Landmark.Kind.BARLEY_FARM) {
                    h.assertTrue(s.zone() == Zone.TWOLEG_FARMLAND, "seed " + seed + ": Barley's farm stands on farmland, got " + s.zone());
                }
            }
        }
        h.succeed();
    }

    /**
     * The Gathering island is an island on every seed: open water all round it (seed 4242 once joined it to the
     * shore where the shore bulged), and the tree-bridge reaches from the island to dry land.
     */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void theIslandIsCutOffButForTheTreeBridge(GameTestHelper h) {
        for (long seed = -60; seed <= 60; seed++) {
            for (long sd : new long[] {seed, 4242L}) {
                com.ninjacat.greatjourney.clanlands.layout.LakeLands lake = ClanWorld.layoutOnly(sd).lake;
                Landmark isl = null, bridge = null;
                for (Landmark l : lake.landmarks()) {
                    if (l.kind() == Landmark.Kind.ISLAND_TREE) isl = l;
                    if (l.kind() == Landmark.Kind.TREE_BRIDGE) bridge = l;
                }
                h.assertTrue(isl != null && bridge != null, "seed " + sd + ": the island and its tree-bridge exist");
                double ring = isl.radius() - 14 + 8;
                for (int a = 0; a < 360; a += 5) {
                    double x = isl.x() + Math.cos(Math.toRadians(a)) * ring, z = isl.z() + Math.sin(Math.toRadians(a)) * ring;
                    Sample s = lake.sample(x, z);
                    h.assertTrue(s.height() <= 56, "seed " + sd + ": deep water all round the island (at " + a + " deg the ground is "
                            + String.format("%.1f", s.height()) + ")");
                }
                double half = bridge.radius() - 4, bx = Math.sin(bridge.facing()), bz = -Math.cos(bridge.facing());
                Sample shoreEnd = lake.sample(bridge.x() + bx * (half + 2), bridge.z() + bz * (half + 2));
                Sample islandEnd = lake.sample(bridge.x() - bx * half, bridge.z() - bz * half);
                StringBuilder along = new StringBuilder();
                for (double t = -half; t <= half + 12; t += 4) {
                    Sample q = lake.sample(bridge.x() + bx * t, bridge.z() + bz * t);
                    along.append(String.format(" %.0f:%s/%.1f", t, q.zone(), q.height()));
                }
                h.assertTrue(shoreEnd.height() >= 63, "seed " + sd + ": the tree-bridge reaches the shore;" + along);
                h.assertTrue(islandEnd.zone() == Zone.ISLAND, "seed " + sd + ": the tree-bridge starts on the island");
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void twoSeedsDiffer(GameTestHelper h) {
        ClanWorld a = ClanWorld.layoutOnly(1L), b = ClanWorld.layoutOnly(2L);
        h.assertTrue(a.lake.landmarks().get(0).x() != b.lake.landmarks().get(0).x(), "the island moves between seeds");
        h.assertTrue(a.forest.centreX() != b.forest.centreX(), "the old forest moves between seeds");
        ClanWorld again = ClanWorld.layoutOnly(1L);
        h.assertTrue(again.lake.landmarks().get(0).x() == a.lake.landmarks().get(0).x(), "the same seed rebuilds the same map");
        h.succeed();
    }
}
