package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan;
import com.ninjacat.greatjourney.clanlands.layout.LakeLands;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.world.CampSanctuary;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Every Clan camp as planned: one way in, an open middle to arrive in, and dens that fit and keep clear. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class CampGameTests {
    private CampGameTests() {}

    private static final long[] SEEDS = {4242L, 777001L, 20261011L, -42L, -77L, 3776135913213891029L};

    static List<Landmark> camps(ClanWorld w) {
        List<Landmark> out = new ArrayList<>();
        for (Clan c : Clan.values()) out.add(w.lake.camp(c));
        for (Landmark l : w.forest.landmarks()) {
            switch (l.kind()) {
                case THUNDERCLAN_RAVINE, SHADOWCLAN_CAMP, RIVERCLAN_CAMP, WINDCLAN_HOLLOW -> out.add(l);
                default -> { }
            }
        }
        return out;
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void everyCampHasOneWayInAndAnOpenMiddle(GameTestHelper h) {
        for (long seed : SEEDS) {
            ClanWorld w = ClanWorld.layoutOnly(seed);
            List<Landmark> camps = camps(w);
            if (camps.size() != 8) throw new IllegalStateException("seed " + seed + ": " + camps.size() + " camps, not 8");
            for (Landmark l : camps) {
                CampPlan plan = CampPlan.of(w, l);
                String at = "seed " + seed + " " + plan.style + " at " + (int) l.x() + " " + (int) l.z();
                if (plan.dens.size() != 6) throw new IllegalStateException(at + ": " + plan.dens.size() + " dens");
                // one way in: round the barrier's middle line, the gate is a single unbroken arc
                double r = plan.wallIn > 0 ? (plan.wallIn + plan.wallOut) / 2 : plan.cliff;
                int gaps = 0;
                boolean prev = plan.inGate(plan.cx + r, plan.cz);
                for (int i = 1; i <= 720; i++) {
                    double a = Math.toRadians(i / 2.0);
                    boolean now = plan.inGate(plan.cx + Math.cos(a) * r, plan.cz + Math.sin(a) * r);
                    if (now && !prev) gaps++;
                    prev = now;
                }
                if (gaps != 1) throw new IllegalStateException(at + ": " + gaps + " ways in");
                if (plan.style == CampPlan.Style.STONE_HOLLOW
                        && Math.abs(Math.atan2(Math.sin(plan.gate - plan.ledge), Math.cos(plan.gate - plan.ledge))) < Math.toRadians(100)) {
                    throw new IllegalStateException(at + ": the way in comes down beside Highledge");
                }
                // the middle stays open: where a joining cat arrives and the fresh-kill pile sits
                for (double[] spot : new double[][] {{plan.cx + 1.5, plan.cz + 1.5}, {plan.cx + 2.5, plan.cz + 0.5}, {plan.cx, plan.cz}}) {
                    for (CampPlan.Den d : plan.dens) {
                        if (Math.hypot(spot[0] - d.x(), spot[1] - d.z()) < d.radius() + 1) throw new IllegalStateException(at + ": " + d.kind() + " den in the middle");
                    }
                    for (double[] rock : new double[][] {plan.rock, plan.medicineRock}) {
                        if (rock != null && Math.hypot(spot[0] - rock[0], spot[1] - rock[1]) < rock[2] + 1) throw new IllegalStateException(at + ": a rock in the middle");
                    }
                }
                // dens keep apart, out of the way in, and inside the barrier; nests lie inside their dens
                for (int i = 0; i < plan.dens.size(); i++) {
                    CampPlan.Den a = plan.dens.get(i);
                    for (int j = i + 1; j < plan.dens.size(); j++) {
                        CampPlan.Den b = plan.dens.get(j);
                        if (Math.hypot(a.x() - b.x(), a.z() - b.z()) < a.radius() + b.radius() - 0.5) {
                            throw new IllegalStateException(at + ": " + a.kind() + " and " + b.kind() + " dens overlap");
                        }
                    }
                    for (int k = 0; k < 16; k++) {
                        double ang = k * Math.PI / 8;
                        if (plan.inGate(a.x() + Math.cos(ang) * a.radius(), a.z() + Math.sin(ang) * a.radius())
                                && Math.hypot(a.x() - plan.cx, a.z() - plan.cz) < (plan.wallIn > 0 ? plan.wallOut : plan.cliff) + 2) {
                            throw new IllegalStateException(at + ": the " + a.kind() + " den blocks the way in");
                        }
                    }
                    if (a.shape() == CampPlan.Den.Shape.DOME && Math.hypot(a.x() - plan.cx, a.z() - plan.cz) + a.radius() > plan.clearing + 0.6) {
                        throw new IllegalStateException(at + ": the " + a.kind() + " den pokes into the barrier");
                    }
                    for (double[] nest : plan.nests(a.kind())) {
                        // (under a dome's roof, or in a dug den's chamber, which is cut out to its full radius)
                        if (Math.hypot(nest[0] - a.x(), nest[1] - a.z()) > (a.shape() == CampPlan.Den.Shape.DOME ? Math.max(1.0, a.radius() - 0.9) : a.radius())) {
                            throw new IllegalStateException(at + ": a nest outside the " + a.kind() + " den");
                        }
                    }
                }
            }
        }
        h.succeed();
    }

    /**
     * Lake ThunderClan's stone hollow is a deep quarry on every seed, from the planned layout alone: all round, the rim
     * (the planned ground just past the sheer wall, where the world inspector measures it) stands at least 20 over the
     * planned floor, which keeps above the water table; and the rise the quarry stands in is a knoll, not a mountain,
     * no steeper than three blocks in four on any line out from it and at most 32 over the land beyond. (Dug into the
     * land as it lay, the walls planned 12-20 high and came out 13 at seed 777001.)
     */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void theStoneHollowIsADeepQuarryOnEverySeed(GameTestHelper h) {
        java.util.Random rnd = new java.util.Random(20261012L);
        for (int k = 0; k < SEEDS.length + 58; k++) {
            long seed = k < SEEDS.length ? SEEDS[k] : rnd.nextLong();
            LakeLands lake = new LakeLands(seed);
            Landmark l = lake.camp(Clan.THUNDER);
            double cliff = l.radius() - 16;                                                  // CampPlan's cliff foot
            int floor = (int) Math.round(lake.sample(l.x(), l.z()).height());               // CampPlan.floorY
            String at = "seed " + seed + " stone hollow at " + (int) l.x() + " " + (int) l.z();
            if (floor < 66) throw new IllegalStateException(at + ": floor planned at " + floor + ", by the water table");
            for (int b = 0; b < 72; b++) {
                double a = Math.toRadians(b * 5), ux = Math.cos(a), uz = Math.sin(a);
                double[] ring = new double[8];
                for (int i = 0; i < 8; i++) ring[i] = lake.sample(l.x() + ux * (cliff + 2.5 + i), l.z() + uz * (cliff + 2.5 + i)).height();
                java.util.Arrays.sort(ring);
                double rim = ring[4];
                if (rim - floor < 20) throw new IllegalStateException(at + ": walls " + Math.round(rim - floor) + " high at " + b * 5 + " degrees");
                double prev = lake.sample(l.x() + ux * (cliff + 4), l.z() + uz * (cliff + 4)).height();
                for (double t = cliff + 5; t <= cliff + 130; t += 1) {
                    double ht = lake.sample(l.x() + ux * t, l.z() + uz * t).height();
                    if (Math.abs(ht - prev) > 0.75) {
                        throw new IllegalStateException(at + ": the land round it steps " + String.format(java.util.Locale.ROOT, "%.2f", Math.abs(ht - prev))
                                + " in a block at " + b * 5 + " degrees, " + Math.round(t - cliff) + " out from the wall");
                    }
                    prev = ht;
                }
                if (rim - prev > 32) throw new IllegalStateException(at + ": the rim stands " + Math.round(rim - prev) + " over the land at " + b * 5 + " degrees");
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void campPlansAreTheSameEveryTime(GameTestHelper h) {
        ClanWorld a = ClanWorld.layoutOnly(4242L), b = ClanWorld.layoutOnly(4242L);
        List<Landmark> ca = camps(a), cb = camps(b);
        for (int i = 0; i < ca.size(); i++) {
            CampPlan pa = CampPlan.of(a, ca.get(i)), pb = CampPlan.of(b, cb.get(i));
            if (pa.gate != pb.gate || !pa.dens.equals(pb.dens)) throw new IllegalStateException("camp " + i + " planned differently");
        }
        h.succeed();
    }

    /**
     * A fox inside a camp's footprint does not stay there (it is moved out past the camp's edge, or removed where the
     * ground out there is not loaded) and cannot take a cat or player standing in a camp as its target.
     */
    @GameTest(template = "empty")
    public static void aFoxInACampIsMovedOutAndCannotHuntThere(GameTestHelper h) {
        Fox fox = h.spawn(EntityType.FOX, new BlockPos(1, 2, 1));
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        player.moveTo(fox.getX() + 1, fox.getY(), fox.getZ());
        Landmark round = new Landmark(Landmark.Kind.SHADOWCLAN_CAMP, player.getX(), player.getZ(), 4, 0, 0);
        if (!CampSanctuary.unwelcome(fox)) throw new IllegalStateException("a wild fox is not taken for a predator");
        if (CampSanctuary.mayTarget(fox, player, List.of(round))) throw new IllegalStateException("a fox may hunt a player in camp");
        if (!CampSanctuary.mayTarget(fox, player, List.of())) throw new IllegalStateException("a fox may not hunt outside camps");
        fox.setTarget(player);
        Landmark camp = new Landmark(Landmark.Kind.SHADOWCLAN_CAMP, fox.getX(), fox.getZ(), 3, 0, 0);
        if (!CampSanctuary.evict(fox, List.of(camp))) throw new IllegalStateException("the fox was not found in the camp");
        double d = Math.hypot(fox.getX() - camp.x(), fox.getZ() - camp.z());
        if (!fox.isRemoved() && d <= camp.radius()) throw new IllegalStateException("the fox is still in the camp, " + d + " from its middle");
        if (fox.getTarget() != null) throw new IllegalStateException("the fox still has a target");
        if (!fox.isRemoved() && CampSanctuary.evict(fox, List.of(camp))) throw new IllegalStateException("the fox was still in the camp after it was moved");
        h.succeed();
    }
}
