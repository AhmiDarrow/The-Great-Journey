package com.ninjacat.greatjourney.clanlands.landmark;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.Den;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The moss beds every den gets, counted from the plan and the painter's rules alone (no world is generated): a bed
 * lands only on a block of the den's open floor that the painter left bare, and one block holds one bed.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class NestGameTests {
    private NestGameTests() {}

    private static final long[] SEEDS = {4242L, 777001L, 20261011L, -42L, -77L, 3776135913213891029L};

    /** Each camp with its Clan: the four lake camps, then the four old-forest ones. */
    private static List<Object[]> camps(ClanWorld w) {
        List<Object[]> out = new ArrayList<>();
        for (Clan c : Clan.values()) out.add(new Object[] {w.lake.camp(c), c, false});
        for (Landmark l : w.forest.landmarks()) {
            Clan c = switch (l.kind()) {
                case THUNDERCLAN_RAVINE -> Clan.THUNDER;
                case WINDCLAN_HOLLOW -> Clan.WIND;
                case RIVERCLAN_CAMP -> Clan.RIVER;
                case SHADOWCLAN_CAMP -> Clan.SHADOW;
                default -> null;
            };
            if (c != null) out.add(new Object[] {l, c, true});
        }
        return out;
    }

    /** How many cats a newly drawn Clan puts in each den (BookClans / ForestClans roll the warriors the same way). */
    private static int[] cats(ClanWorld w, Clan c, boolean forest) {
        RandomSource r = RandomSource.create(forest ? w.seed ^ (c.ordinal() * 0x51F15E5L) : w.seed ^ (c.ordinal() * 0x9E3779B9L));
        int[] n = new int[DenKind.values().length];
        n[DenKind.LEADER.ordinal()] = 1;
        n[DenKind.MEDICINE.ordinal()] = 1;
        n[DenKind.NURSERY.ordinal()] = 3;                                                // the queen and two kits
        n[DenKind.APPRENTICES.ordinal()] = 2;
        n[DenKind.WARRIORS.ordinal()] = 1 + 4 + r.nextInt(3);                            // the deputy and the warriors
        return n;
    }

    /** Whether the painter leaves the den's floor at x, z open (cleared to stand in) and bare (no moss carpet). */
    static boolean bare(CampPlan plan, Den den, int x, int z, double[][] nests) {
        double px = x + 0.5, pz = z + 0.5, d = Math.hypot(px - den.x(), pz - den.z());
        boolean kept = Camps.nestAt(nests, x, z);
        switch (den.shape()) {
            case DOME -> {
                boolean inside = d < den.radius() - 1.0;
                double ang = Math.atan2(pz - den.z(), px - den.x());
                boolean door = Math.abs(CampPlan.diff(ang, den.door())) < 0.55 && d > den.radius() - 2.2 && d <= den.radius() + 0.5;
                if (!inside && !door) return false;
                return !inside || kept || Camps.hash(x, 7, z) >= 0.55;
            }
            case BURROW -> {
                double mid = Math.hypot(den.x() - plan.cx, den.z() - plan.cz), mouth = mid - (den.length() - 2);
                double[] a = Camps.axis(px, pz, plan.cx, plan.cz, den.door() + Math.PI);
                boolean chamber = d <= den.radius();
                if (a[0] < mouth - 1.5 || a[0] > mid + 2.6 || (!chamber && Math.abs(a[1]) > 1.05)) return false;
                return !chamber || kept || Camps.hash(x, 3, z) >= 0.5;
            }
            case CLEFT -> {
                double[] a = Camps.axis(px, pz, den.x(), den.z(), den.door());
                boolean chamber = d <= den.radius();
                boolean slot = a[0] >= 0 && a[0] <= den.length() + 1 && Math.abs(a[1]) <= 0.75;
                boolean pool = plan.style == CampPlan.Style.STONE_HOLLOW && chamber && a[0] < -den.radius() + 1.6 && Math.abs(a[1]) < 1.2;
                return (chamber || slot) && !pool;
            }
            default -> {
                if (plan.style == CampPlan.Style.STONE_HOLLOW) {
                    // the cave behind Highledge is cut along the ledge's bearing from the camp's middle
                    double[] a = Camps.axis(px, pz, plan.cx, plan.cz, plan.ledge);
                    if (a[0] < plan.cliff - 1.5 || a[0] > plan.cliff + 5.2 || Math.abs(a[1]) > (a[0] < plan.cliff + 1 ? 1.2 : 2.4)) return false;
                    return a[0] <= plan.cliff + 1.5 || kept;
                }
                double[] a = Camps.axis(px, pz, den.x(), den.z(), den.door());
                if (!(d <= den.radius() || (a[0] > 0 && Math.abs(a[1]) <= 1.0))) return false;
                return d > den.radius() || kept;
            }
        }
    }

    /**
     * Every den gets its full set of beds on every seed (the warriors' den eight, the nursery and the apprentices'
     * four, the elders' three, the medicine den two, the leader's one), each on its own bare block of open floor, and
     * every cat of a newly drawn Clan sleeps on one of its own den's.
     */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void everyDenHasItsNestsOnBareFloor(GameTestHelper h) {
        StringBuilder sb = new StringBuilder("Nest count (beds landing / cats) per den:");
        for (long seed : SEEDS) {
            ClanWorld w = ClanWorld.layoutOnly(seed);
            for (Object[] camp : camps(w)) {
                CampPlan plan = CampPlan.of(w, (Landmark) camp[0]);
                int[] cats = cats(w, (Clan) camp[1], (boolean) camp[2]);
                String at = "seed " + seed + " " + plan.style;
                sb.append("\n  ").append(at).append(':');
                for (Den den : plan.dens) {
                    double[][] kept = Camps.nests(plan, den);
                    Set<Long> beds = new HashSet<>();
                    for (double[] n : plan.nests(den.kind())) {
                        int x = (int) Math.floor(n[0]), z = (int) Math.floor(n[1]);
                        if (!bare(plan, den, x, z, kept)) throw new IllegalStateException(at + ": a " + den.kind() + " nest at " + x + " " + z + " is not on bare open floor");
                        if (!beds.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) throw new IllegalStateException(at + ": two " + den.kind() + " nests share a block");
                    }
                    if (beds.size() != CampPlan.nestCount(den.kind())) {
                        throw new IllegalStateException(at + ": the " + den.kind() + " den has " + beds.size() + " nests, not " + CampPlan.nestCount(den.kind()));
                    }
                    int n = cats[den.kind().ordinal()];
                    for (int i = 0; i < n; i++) {
                        double[] c = plan.nest(den.kind(), i);
                        if (!beds.contains(((long) Math.floor(c[0]) << 32) ^ ((int) Math.floor(c[1]) & 0xFFFFFFFFL))) {
                            throw new IllegalStateException(at + ": a " + den.kind() + " cat sleeps off the den's nests");
                        }
                    }
                    sb.append(' ').append(den.kind()).append(' ').append(beds.size()).append('/').append(n);
                }
            }
        }
        LogUtils.getLogger().info(sb.toString());
        h.succeed();
    }
}
