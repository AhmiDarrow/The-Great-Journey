package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.layout.Path;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.life.traffic.Traffic;
import com.ninjacat.greatjourney.clanlands.life.traffic.TrafficMath;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Thunderpath monsters: which side they drive on, what they hit, when they are gone, how each stretch of road rolls
 * for them on its own, how they ride the road, and that nothing of them is left behind.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class TrafficGameTests {
    private TrafficGameTests() {}

    @GameTest(template = "empty")
    public static void monstersKeepLeftAndFaceTheirWay(GameTestHelper h) {
        double[] left = TrafficMath.left(0, 1);                       // facing south, the left is east
        h.assertTrue(left[0] == 1 && left[1] == 0, "facing south, left is +x, got " + left[0] + "," + left[1]);
        double[] l2 = TrafficMath.left(1, 0);                         // facing east, the left is north
        h.assertTrue(l2[0] == 0 && l2[1] == -1, "facing east, left is -z");
        h.assertTrue(Math.abs(TrafficMath.yaw(0, 1)) < 1e-4, "yaw 0 faces south");
        h.assertTrue(Math.abs(TrafficMath.yaw(-1, 0) - 90) < 1e-4, "yaw 90 faces west");
        double tp = TrafficMath.laneOffset(Road.Kind.THUNDERPATH, 4, 0);
        h.assertTrue(tp > 0 && tp < 4, "a Thunderpath lane sits between the centre line and the edge, got " + tp);
        double inner = TrafficMath.laneOffset(Road.Kind.HIGHWAY, 10, 0), outer = TrafficMath.laneOffset(Road.Kind.HIGHWAY, 10, 1);
        h.assertTrue(0 < inner && inner < outer && outer < 10, "the highway's two lanes each way, got " + inner + " and " + outer);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aMonsterHitsWhatIsInItsWay(GameTestHelper h) {
        // a car 4.1 long and 1.9 wide, facing south
        h.assertTrue(TrafficMath.inFootprint(0, 0.5, 1.8, 0, 1, 4.1, 1.9, 1.8), "a cat at its bumper is hit");
        h.assertTrue(TrafficMath.inFootprint(0.9, 0.2, -1.5, 0, 1, 4.1, 1.9, 1.8), "a cat by its rear wheel is hit");
        h.assertTrue(!TrafficMath.inFootprint(2.0, 0.5, 0, 0, 1, 4.1, 1.9, 1.8), "a cat two blocks to the side is missed");
        h.assertTrue(!TrafficMath.inFootprint(0, 0.5, 3.5, 0, 1, 4.1, 1.9, 1.8), "a cat well ahead is not hit yet");
        h.assertTrue(!TrafficMath.inFootprint(0, 3.0, 0, 0, 1, 4.1, 1.9, 1.8), "a bird above the roof is missed");
        // the same car facing east: its length now runs along x
        h.assertTrue(TrafficMath.inFootprint(1.8, 0.5, 0, 1, 0, 4.1, 1.9, 1.8), "facing east, a cat at its bumper is hit");
        h.assertTrue(TrafficMath.sideOf(1, 0, 0, 1) > 0 && TrafficMath.sideOf(-1, 0, 0, 1) < 0, "east is the left of a south-facing car");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aMonsterIsGoneOncePastOrOffItsRoad(GameTestHelper h) {
        h.assertTrue(!TrafficMath.done(500, 600, 1, 2000, 140), "still coming toward the cat");
        h.assertTrue(!TrafficMath.done(700, 600, 1, 2000, 140), "just past the cat, still in sight");
        h.assertTrue(TrafficMath.done(741, 600, 1, 2000, 140), "140 past the cat, gone");
        h.assertTrue(TrafficMath.done(459, 600, -1, 2000, 140), "going the other way, 141 past, gone");
        h.assertTrue(!TrafficMath.done(461, 600, -1, 2000, 140), "going the other way, not yet far enough");
        h.assertTrue(TrafficMath.done(-1, 100, -1, 2000, 140) && TrafficMath.done(2001, 1900, 1, 2000, 140), "off either end of the road");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theMonsterDamageTypeIsRegistered(GameTestHelper h) {
        var types = h.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        h.assertTrue(types.containsKey(ResourceLocation.fromNamespaceAndPath("clanlands", "monster")), "clanlands:monster damage type loads");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void eachStretchOfRoadRollsOnItsOwn(GameTestHelper h) {
        h.assertTrue(Traffic.chance(Road.Kind.OLD_THUNDERPATH) == 0, "nothing drives the old Thunderpath");
        h.assertTrue(Traffic.chance(Road.Kind.HIGHWAY) > Traffic.chance(Road.Kind.THUNDERPATH) && Traffic.chance(Road.Kind.THUNDERPATH) > 0,
                "the highway is busier than a forest Thunderpath, and both see monsters");
        h.assertTrue(TrafficMath.segment(0) == 0 && TrafficMath.segment(191.9) == 0 && TrafficMath.segment(192) == 1
                && TrafficMath.segment(-0.5) == -1, "stretches are " + TrafficMath.SEGMENT + " blocks long");
        Road a = new Road(Path.straight(new double[][] {{0, 0}, {0, 2000}}), 4, Road.Kind.THUNDERPATH);
        Road b = new Road(Path.straight(new double[][] {{0, 0}, {0, 2000}}), 4, Road.Kind.THUNDERPATH);
        long now = 1000;
        h.assertTrue(Traffic.mayRoll(a, 3, now), "a fresh stretch may roll");
        Traffic.quiet(a, 3, now, 100);
        h.assertTrue(!Traffic.mayRoll(a, 3, now + 50), "just after a monster, that stretch is quiet");
        h.assertTrue(Traffic.mayRoll(a, 4, now + 50), "the next stretch of the same road is not");
        h.assertTrue(Traffic.mayRoll(b, 3, now + 50), "the same stretch of another road is not");
        h.assertTrue(Traffic.mayRoll(a, 3, now + 100), "the quiet ends");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void monstersRideOnTheRoadNotThroughIt(GameTestHelper h) {
        h.assertTrue(TrafficMath.rideHeight(Double.NaN, 70, 70, 70, Double.NaN) == 70, "flat ground: on it");
        h.assertTrue(TrafficMath.rideHeight(Double.NaN, 70, 71, 70, 70) == 71, "a rise under the front wheels lifts it at once");
        h.assertTrue(TrafficMath.rideHeight(Double.NaN, 70, 78, 70, 70) == 70, "a tree trunk by the front wheels is ignored");
        h.assertTrue(TrafficMath.rideHeight(Double.NaN, 68, 68, 68, 70) == 69, "the road dropping away: it settles over two ticks");
        h.assertTrue(TrafficMath.rideHeight(72, 79, 79, 79, 72) == 72, "a block left on a planned road does not throw it into the air");
        h.assertTrue(TrafficMath.rideHeight(72, 73, 73, 72, Double.NaN) == 73, "within two blocks of the plan, the ground as it is wins");
        // over a culvert the planned bed rises, so the road (and the monster) climb over the tunnel
        Road r = Road.graded(Path.straight(new double[][] {{0, 0}, {0, 400}}), 4, Road.Kind.THUNDERPATH, (x, z) -> 70, 200);
        h.assertTrue(r.bedAt(200) > r.bedAt(100) + Road.CULVERT_RISE - 0.5, "the road is lifted over its culvert, bed "
                + r.bedAt(200) + " vs " + r.bedAt(100));
        h.assertTrue(Math.abs(r.floorAt(200) - 70) < 0.5, "the culvert floor stays at the land's level");
        double last = r.bedAt(120);
        for (double s = 121; s <= 200; s++) {
            h.assertTrue(r.bedAt(s) >= last - 1e-9, "the ramp up to the culvert never dips, at " + s);
            last = r.bedAt(s);
        }
        h.assertTrue(TrafficMath.ahead(100, 1, 110) == 10 && TrafficMath.ahead(100, -1, 110) == -10, "ahead follows the direction of travel");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aMonsterStrikesWhatIsInItsLaneAndLeavesNothingBehind(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        // a short north-south road through the pig, its lane right on the pig's column
        Road road = new Road(Path.straight(new double[][] {{at.getX() + 0.5 - 0.2, at.getZ() - 30}, {at.getX() + 0.5 - 0.2, at.getZ() + 30}}),
                0.4, Road.Kind.THUNDERPATH);
        Pig pig = EntityType.PIG.create(level);
        pig.moveTo(at.getX() + 0.5, top, at.getZ() + 0.5, 0, 0);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        float before = pig.getHealth();
        double pigAlong = road.path().alongAt(pig.getX(), pig.getZ());
        h.assertTrue(Traffic.placeForTest(level, road, pigAlong - 3, 1), "a monster is put on the road");
        AABB around = new AABB(at).inflate(40);
        List<Display.BlockDisplay> parts = level.getEntitiesOfClass(Display.BlockDisplay.class, around, e -> e.getTags().contains(Traffic.PART_TAG));
        h.assertTrue(!parts.isEmpty() && parts.stream().allMatch(Traffic::isLivePart), "its parts are out and known, " + parts.size());
        for (int i = 0; i < 3; i++) Traffic.driveForTest(level);
        h.assertTrue(pig.isDeadOrDying() || pig.getHealth() < before, "the pig in its lane was struck, health " + pig.getHealth());
        h.assertTrue(pig.getLastDamageSource() != null && pig.getLastDamageSource().is(
                net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("clanlands", "monster"))),
                "struck by a monster, not something else");
        Traffic.clear();
        h.assertTrue(parts.stream().allMatch(Entity::isRemoved), "clearing the roads removes every part");
        // a part saved by a crash and loaded again is not one of ours any more: it never comes back into the world
        Display.BlockDisplay stray = EntityType.BLOCK_DISPLAY.create(level);
        stray.moveTo(at.getX() + 0.5, top + 1, at.getZ() + 0.5);
        stray.addTag(Traffic.PART_TAG);
        h.assertTrue(!level.addFreshEntity(stray) && !stray.isAddedToLevel(), "a leftover monster part is dropped on load");
        if (!pig.isRemoved()) pig.discard();
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aMonsterSentAtAMobInItsLaneReachesAndHitsIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        // a road through the pig whose centre line is off to one side, so the pig stands in just one direction's lane
        Road road = new Road(Path.straight(new double[][] {{at.getX() + 0.5 - 1.0, at.getZ() - 30}, {at.getX() + 0.5 - 1.0, at.getZ() + 30}}),
                2.0, Road.Kind.THUNDERPATH);
        Pig pig = EntityType.PIG.create(level);
        pig.moveTo(at.getX() + 0.5, top, at.getZ() + 0.5, 0, 0);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        float before = pig.getHealth();
        double[] aim = Traffic.aim(road, pig.getX(), pig.getZ());
        int dir = (int) aim[0];
        h.assertTrue(dir == 1, "the pig, east of the centre line, is in the lane of monsters driving south, aim says " + dir);
        h.assertTrue(Traffic.placeForTest(level, road, aim[2] + dir * -20, dir, (int) aim[1]), "a monster is put on the road 20 blocks off");
        h.succeedWhen(() -> {
            Traffic.driveForTest(level);
            h.assertTrue(pig.isDeadOrDying() || pig.getHealth() < before, "the pig in its lane was struck, health " + pig.getHealth());
            Traffic.clear();
            if (!pig.isRemoved()) pig.discard();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aimPicksTheLaneThroughTheSpotInBothDirections(GameTestHelper h) {
        Road road = new Road(Path.straight(new double[][] {{0, 0}, {0, 400}}), 4, Road.Kind.THUNDERPATH);
        double off = TrafficMath.laneOffset(Road.Kind.THUNDERPATH, 4, 0);
        // driving south (dir +1) the left is east (+x); driving north the left is west
        double[] south = Traffic.aim(road, off, 200);
        double[] north = Traffic.aim(road, -off, 200);
        h.assertTrue(south[0] == 1 && north[0] == -1, "east side is the southbound lane, west the northbound: " + south[0] + "," + north[0]);
        h.assertTrue(Math.abs(south[2] - 200) < 1 && Math.abs(north[2] - 200) < 1, "aimed at the spot along the road");
        Road hw = new Road(Path.straight(new double[][] {{0, 0}, {0, 400}}), 10, Road.Kind.HIGHWAY);
        double outer = TrafficMath.laneOffset(Road.Kind.HIGHWAY, 10, 1);
        double[] a = Traffic.aim(hw, -outer, 200);
        h.assertTrue(a[0] == -1 && a[1] == 1, "the highway's outer northbound lane, got dir " + a[0] + " lane " + a[1]);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void everyMonsterEndsWithinItsLifetime(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        Road road = new Road(Path.straight(new double[][] {{at.getX() + 0.5, at.getZ() - 30}, {at.getX() + 0.5, at.getZ() + 30}}),
                0.4, Road.Kind.THUNDERPATH);
        // one that drives normally, one that is stalled and never moves: both are gone, with their parts, by the end of their lifetime
        h.assertTrue(Traffic.placeForTest(level, road, 10, 1), "a driving monster is put on the road");
        Traffic.freezeForTest(true);
        try {
            h.assertTrue(Traffic.placeForTest(level, road, 40, 1), "a stalled monster is put on the road");
            AABB around = new AABB(at).inflate(40);
            List<Display.BlockDisplay> parts = level.getEntitiesOfClass(Display.BlockDisplay.class, around, e -> e.getTags().contains(Traffic.PART_TAG));
            h.assertTrue(!parts.isEmpty(), "parts are out, " + parts.size());
            int ticks = 0;
            while (Traffic.active() > 0 && ticks < 20 * 60 + 10) {
                Traffic.driveForTest(level);
                ticks++;
            }
            h.assertTrue(Traffic.active() == 0, "every monster ended, " + Traffic.active() + " left after " + ticks + " ticks");
            h.assertTrue(ticks <= 20 * 60 + 1, "within its lifetime, took " + ticks);
            h.assertTrue(parts.stream().allMatch(Entity::isRemoved), "and no part was left standing");
        } finally {
            Traffic.freezeForTest(false);
            Traffic.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void partsWithNoControllerAreSweptAway(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        Road road = new Road(Path.straight(new double[][] {{at.getX() + 0.5, at.getZ() - 30}, {at.getX() + 0.5, at.getZ() + 30}}),
                0.4, Road.Kind.THUNDERPATH);
        h.assertTrue(Traffic.placeForTest(level, road, 30, 1), "a monster is put on the road");
        AABB around = new AABB(at).inflate(40);
        List<Display.BlockDisplay> parts = level.getEntitiesOfClass(Display.BlockDisplay.class, around, e -> e.getTags().contains(Traffic.PART_TAG));
        h.assertTrue(!parts.isEmpty(), "its parts are out, " + parts.size());
        // its controller is lost (as after an unload, an exception or a lost reference): the parts must not stay frozen there
        Traffic.loseControllersForTest();
        h.assertTrue(Traffic.active() == 0, "no monster is driving");
        h.succeedWhen(() -> h.assertTrue(parts.stream().allMatch(Entity::isRemoved), "parts with no controller are swept away"));
    }
}
