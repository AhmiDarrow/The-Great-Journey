package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.layout.LakeLands;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The lake's waters as each seed plans them: the half-bridges' berths and where the jetties stand. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class WatersGameTests {
    private WatersGameTests() {}

    private static final long[] NAMED = {4242L, 777001L, -84596914L};

    /**
     * Each half-bridge starts at the shore and runs out over water planned deep enough to come out as open water
     * (planned 14 blocks inside the shore on the shallow lake edge, the jetties still stood on dry meadow).
     */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void halfBridgesRunOutOverDeepWater(GameTestHelper h) {
        for (long seed = -40; seed <= 40 + NAMED.length; seed++) {
            long sd = seed > 40 ? NAMED[(int) (seed - 41)] : seed;
            LakeLands lake = ClanWorld.layoutOnly(sd).lake;
            int found = 0;
            for (Landmark l : lake.landmarks()) {
                if (l.kind() != Landmark.Kind.HALF_BRIDGE) continue;
                found++;
                // as Places.halfBridge lays it: t runs from the shore (-3) out into the lake
                double bx = Math.sin(l.facing() - Math.PI), bz = -Math.cos(l.facing() - Math.PI);
                Sample shoreEnd = lake.sample(l.x() + bx * 3, l.z() + bz * 3);
                h.assertTrue(shoreEnd.height() >= 60, "seed " + sd + ": the half-bridge at " + (int) l.x() + " " + (int) l.z()
                        + " starts at the shore (ground " + String.format("%.1f", shoreEnd.height()) + ")");
                for (int t = 6; t <= 13; t++) {
                    for (int w = -1; w <= 1; w++) {
                        Sample s = lake.sample(l.x() - bx * t + bz * w, l.z() - bz * t - bx * w);
                        h.assertTrue(s.height() <= 58, "seed " + sd + ": the half-bridge at " + (int) l.x() + " " + (int) l.z()
                                + " stands over deep water " + t + " blocks out (ground " + String.format("%.1f", s.height()) + ")");
                    }
                }
            }
            h.assertTrue(found == 2, "seed " + sd + ": two half-bridges on the RiverClan shore");
        }
        h.succeed();
    }

    /** No half-bridge runs out into the tree-bridge or the island's channel, where the Clans cross to the Gathering. */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void halfBridgesKeepClearOfTheTreeBridge(GameTestHelper h) {
        for (long seed = -40; seed <= 40 + NAMED.length; seed++) {
            long sd = seed > 40 ? NAMED[(int) (seed - 41)] : seed;
            LakeLands lake = ClanWorld.layoutOnly(sd).lake;
            Landmark bridge = null, island = null;
            for (Landmark l : lake.landmarks()) {
                if (l.kind() == Landmark.Kind.TREE_BRIDGE) bridge = l;
                if (l.kind() == Landmark.Kind.ISLAND_TREE) island = l;
            }
            h.assertTrue(bridge != null && island != null, "seed " + sd + ": the island and its tree-bridge exist");
            double half = bridge.radius() - 4, tx = Math.sin(bridge.facing()), tz = -Math.cos(bridge.facing());
            for (Landmark l : lake.landmarks()) {
                if (l.kind() != Landmark.Kind.HALF_BRIDGE) continue;
                double bx = Math.sin(l.facing() - Math.PI), bz = -Math.cos(l.facing() - Math.PI);
                for (int t = -3; t <= 17; t++) {
                    double x = l.x() - bx * t, z = l.z() - bz * t;
                    for (double u = -half; u <= half + 2; u += 1) {
                        double d = Math.hypot(x - (bridge.x() + tx * u), z - (bridge.z() + tz * u));
                        h.assertTrue(d >= 12, "seed " + sd + ": the half-bridge at " + (int) l.x() + " " + (int) l.z()
                                + " keeps clear of the tree-bridge (" + String.format("%.0f", d) + " blocks)");
                    }
                    h.assertTrue(Math.hypot(x - island.x(), z - island.z()) >= island.radius() + 10,
                            "seed " + sd + ": the half-bridge at " + (int) l.x() + " " + (int) l.z() + " keeps clear of the island");
                }
            }
        }
        h.succeed();
    }
}
