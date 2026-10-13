package com.ninjacat.greatjourney.clanlands.verification;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.layout.Path;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** The Twoleg roads each world lays out: where they run, their culverts, and the ground graded under them. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class RoadsGameTests {
    private RoadsGameTests() {}

    private static final Logger LOG = LogUtils.getLogger();

    @GameTest(template = "empty")
    public static void bothMapsHaveTheirRoads(GameTestHelper h) {
        ClanWorld w = ClanWorld.layoutOnly(20261011L);
        h.assertTrue(w.lake.roads().stream().anyMatch(r -> r.kind() == Road.Kind.THUNDERPATH), "the lake has its Thunderpath past ShadowClan");
        h.assertTrue(w.lake.roads().stream().anyMatch(r -> r.kind() == Road.Kind.OLD_THUNDERPATH), "the lake has the old Thunderpath");
        Road tp = w.forest.roads().get(0);
        h.assertTrue(tp.kind() == Road.Kind.THUNDERPATH && tp.graded(), "the old forest's Thunderpath is a graded road");
        h.assertTrue(tp.culverts().length == 2, "two tunnels run under the old forest's Thunderpath");
        for (double a : tp.culverts()) {
            double[] at = tp.path().pointAt(a);
            LOG.info("Roads: culvert at {} {}, bed {} over floor {}", (int) at[0], (int) at[1], (int) tp.bedAt(a), (int) tp.floorAt(a));
            h.assertTrue(tp.bedAt(a) - tp.floorAt(a) >= Road.CULVERT_RISE - 0.01, "the road rises over each culvert");
            double planned = w.forest.sample(at[0], at[1]).height();
            h.assertTrue(Math.abs(planned - tp.bedAt(a)) < 1.5, "the ground is graded to the road at the culvert: " + planned + " vs " + tp.bedAt(a));
        }
        for (Road r : w.lake.roads()) {
            double[] mid = r.path().pointAt(r.path().length() / 2);
            LOG.info("Roads: lake {} midpoint {} {}, length {}", r.kind(), (int) mid[0], (int) mid[1], (int) r.path().length());
        }
        h.succeed();
    }

    /**
     * A road is banked up over a shallow dip but bridges a deep one: the land under it is never built up into a dam
     * (seed 4242's forest Thunderpath stood twenty blocks over the land beside its river bridge).
     */
    @GameTest(template = "empty")
    public static void deepDipsAreBridgedNotDammed(GameTestHelper h) {
        // land at 70 with a valley 20 deep and 40 wide, and further on a dip of 4
        Road r = Road.graded(Path.straight(new double[][] {{0, 0}, {0, 600}}), 4, Road.Kind.THUNDERPATH,
                (x, z) -> z > 180 && z < 220 ? 50 : z > 400 && z < 420 ? 66 : 70);
        h.assertTrue(r.bedAt(200) - 50 > Road.BANK * 2, "the valley is deep under the road's bed: " + r.bedAt(200));
        h.assertTrue(Math.abs(r.grade(50, 0, 200) - 50) < 0.01, "the valley floor is left as it lies, not dammed up: "
                + r.grade(50, 0, 200));
        h.assertTrue(Math.abs(r.grade(66, 0, 410) - r.bedAt(410)) < 0.01, "the shallow dip is banked up to the road");
        double last = r.grade(40, 0, 200);
        for (double g = 40.5; g <= 75; g += 0.5) {
            double now = r.grade(g, 0, 200);
            h.assertTrue(now - g <= Road.BANK + 0.01, "the land is never built up by more than " + Road.BANK + ": " + g + " -> " + now);
            h.assertTrue(Math.abs(now - last) <= 1.01, "no cliff where the bank gives way to the bridge, at " + g);
            last = now;
        }
        h.succeed();
    }
}
