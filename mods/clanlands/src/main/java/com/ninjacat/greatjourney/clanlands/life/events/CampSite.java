package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where things are in one camp, for {@link CampRoutine}: the middle, the fresh-kill pile, the front of every den,
 * a sunny spot, a training ground, the kits' playing place, the guards' places, and the way in as a line of points
 * from just inside it to clear of it outside. Worked out from the camp's {@link CampPlan} and the blocks as they
 * stand, reading only chunks that are already loaded.
 */
final class CampSite {
    final Clan clan;
    final Landmark landmark;
    final BlockPos centre;
    final double cx, cz;
    /** The barrier's inner and outer reach from the middle, and the way in's bearing. */
    double inner, outer, gate;
    BlockPos pile, eat, sun, train, play;
    /** The way in, inside to outside: a cat only ever crosses the barrier along it. Empty when it is not known. */
    final List<BlockPos> way = new ArrayList<>();
    final Map<DenKind, BlockPos> fronts = new EnumMap<>(DenKind.class);
    /** Each den {x, z, reach}: a cat whose nest lies within reach sleeps there. */
    final Map<DenKind, double[]> dens = new EnumMap<>(DenKind.class);
    final BlockPos[] guards = new BlockPos[2];
    /** The Clan leader's name, to know the leader among the cats. */
    String leader = "";
    /** A spot out in the territory for an outing (far: a border patrol's), or null: given a random source and far. */
    BiFunction<RandomSource, Boolean, BlockPos> spot = (r, far) -> null;

    CampSite(Clan clan, Landmark landmark, BlockPos centre, double cx, double cz) {
        this.clan = clan;
        this.landmark = landmark;
        this.centre = centre;
        this.cx = cx;
        this.cz = cz;
    }

    /**
     * Whether a point is out past the middle of the barrier: outside the camp, or on the outer half of the way in. A den
     * dug into the barrier (the stone hollow's leader cave behind Highledge) is inside: the playtest's leader, judged
     * outside in its own den, was sent home over the quarry's rim and back down the cut.
     */
    boolean outside(double x, double z) {
        if (Math.hypot(x - cx, z - cz) <= (inner + outer) / 2) return false;
        for (double[] d : dens.values()) if (Math.hypot(x - d[0], z - d[1]) <= d[2]) return false;
        return true;
    }

    boolean outside(BlockPos p) {
        return outside(p.getX() + 0.5, p.getZ() + 0.5);
    }

    /** The den whose nests hold this spot, or null. */
    DenKind denAt(BlockPos p) {
        if (p == null) return null;
        DenKind best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<DenKind, double[]> e : dens.entrySet()) {
            double[] d = e.getValue();
            double dist = Math.hypot(p.getX() + 0.5 - d[0], p.getZ() + 0.5 - d[1]);
            if (dist <= d[2] && dist < bestD) {
                best = e.getKey();
                bestD = dist;
            }
        }
        return best;
    }

    /** The camp as it stands now; null while the middle of the camp is not loaded. */
    static CampSite of(ServerLevel ow, Clan c, Landmark l) {
        ClanWorld w = ClanWorld.current();
        int mx = (int) Math.floor(l.x()), mz = (int) Math.floor(l.z());
        if (w == null || !ow.hasChunk(mx >> 4, mz >> 4)) return null;
        CampPlan plan = CampPlan.of(l);
        int hint = plan != null ? plan.floorY + 1 : Cats.surface(ow, mx, mz).getY();
        BlockPos centre = stand(ow, mx, mz, hint);
        CampSite s = new CampSite(c, l, centre != null ? centre : Cats.surface(ow, mx, mz), l.x(), l.z());
        s.pile = findPile(ow, s.centre);
        s.eat = s.pile != null ? beside(ow, s.pile) : s.centre;
        s.inner = s.outer = l.radius();
        if (plan == null) return s;                                  // no plan, no known way in: the cats keep to camp
        double[] bar = plan.barrier();
        s.inner = bar[0];
        s.outer = bar[1];
        s.gate = plan.gate;
        double ux = Math.cos(plan.gate), uz = Math.sin(plan.gate);
        for (CampPlan.Den d : plan.dens) {
            s.dens.put(d.kind(), new double[] {d.x(), d.z(), d.radius() + 1.0});
            // in front of the den's door, out on the camp floor (a burrow's or cleft's door is its length away)
            double out = (d.shape() == CampPlan.Den.Shape.DOME ? d.radius() : Math.max(d.radius(), d.length())) + 1.6;
            BlockPos f = stand(ow, (int) Math.floor(d.x() + Math.cos(d.door()) * out), (int) Math.floor(d.z() + Math.sin(d.door()) * out), hint);
            if (f != null && !s.outside(f)) s.fronts.put(d.kind(), f);
        }
        double clearing = Math.max(4, plan.clearing);
        if (plan.rock != null) {
            // the foot of the great rock on the clearing's side, where it takes the sun
            double dx = l.x() - plan.rock[0], dz = l.z() - plan.rock[1], len = Math.max(0.1, Math.hypot(dx, dz));
            s.sun = stand(ow, (int) Math.floor(plan.rock[0] + dx / len * (plan.rock[2] + 1.5)), (int) Math.floor(plan.rock[1] + dz / len * (plan.rock[2] + 1.5)), hint);
        }
        if (s.sun == null) s.sun = stand(ow, (int) Math.floor(l.x() - ux * clearing * 0.5), (int) Math.floor(l.z() - uz * clearing * 0.5), hint);
        s.train = stand(ow, (int) Math.floor(l.x() - uz * clearing * 0.4), (int) Math.floor(l.z() + ux * clearing * 0.4), hint);
        BlockPos nursery = s.fronts.get(DenKind.NURSERY);
        if (nursery != null) {
            double dx = l.x() - nursery.getX(), dz = l.z() - nursery.getZ(), len = Math.max(0.1, Math.hypot(dx, dz));
            s.play = stand(ow, (int) Math.floor(nursery.getX() + dx / len * 2.5), (int) Math.floor(nursery.getZ() + dz / len * 2.5), hint);
        }
        way(ow, w, plan, s, hint);
        if (!s.way.isEmpty()) {
            double t = Math.hypot(s.way.get(0).getX() + 0.5 - l.x(), s.way.get(0).getZ() + 0.5 - l.z()) - 1;
            for (int i = 0; i < 2; i++) {
                double side = i == 0 ? 2 : -2;
                s.guards[i] = stand(ow, (int) Math.floor(l.x() + ux * t - uz * side), (int) Math.floor(l.z() + uz * t + ux * side), hint);
                if (s.guards[i] == null) s.guards[i] = s.way.get(0);
            }
            double tOut = Math.hypot(s.way.get(s.way.size() - 1).getX() + 0.5 - l.x(), s.way.get(s.way.size() - 1).getZ() + 0.5 - l.z());
            s.spot = (r, far) -> outing(ow, w, s, tOut, r, far);
        }
        return s;
    }

    /** Lays the way in out on the blocks, or leaves it empty if any of it is not loaded. */
    private static void way(ServerLevel ow, ClanWorld w, CampPlan plan, CampSite s, int hint) {
        List<BlockPos> pts = new ArrayList<>();
        for (double[] q : line(w, plan)) {
            int x = (int) Math.floor(q[0]), z = (int) Math.floor(q[1]);
            if (!ow.hasChunk(x >> 4, z >> 4)) return;
            BlockPos p = stand(ow, x, z, Double.isNaN(q[2]) ? hint : (int) q[2]);
            pts.add(p != null ? p : Cats.surface(ow, x, z));             // over the ford's water: its surface
        }
        s.way.addAll(pts);
    }

    /**
     * The way in as points {x, z, standing height or NaN for the camp floor} along its middle line, at most five
     * blocks apart: from three blocks inside the hedge (the stone hollow's: before its thorn tunnel) to clear of it
     * outside, up the ravine's or the quarry's cut to where it meets the land, or over the island's ford to the dry
     * bank. From the plan alone, the same for every camp of every world, lake or old forest.
     */
    static List<double[]> line(ClanWorld w, CampPlan plan) {
        double ux = Math.cos(plan.gate), uz = Math.sin(plan.gate);
        double tIn = plan.wallIn - 3.5, tOut = plan.wallOut + 5, cutStart = Double.NaN, slope = 0;
        switch (plan.style) {
            case STONE_HOLLOW -> {
                tIn = plan.cliff - 10;
                cutStart = plan.cliff - 3;
                slope = 0.8;
            }
            case RAVINE -> {
                cutStart = plan.wallOut + 0.5;
                slope = 1.0;
            }
            default -> { }
        }
        double[] prof = null;
        if (!Double.isNaN(cutStart)) {
            // the cut climbs no steeper than its slope until it has caught up with the land above
            prof = plan.cutProfile(cutStart, slope, 48);
            int top = 40;
            for (int i = 3; i < 46; i++) {
                double gx = plan.cx + ux * (cutStart + i), gz = plan.cz + uz * (cutStart + i);
                if (Math.abs(prof[i] - w.sample(gx, gz).height()) < 0.6) {
                    top = i;
                    break;
                }
            }
            tOut = cutStart + Math.min(46, top + 4);
        }
        if (plan.style == CampPlan.Style.ISLAND) {
            // over the ford to the first dry bank, as far as the painter lays the ford's stones (80 blocks out)
            // (past the island's own shore: the first dry ground after the water, or just beyond the reeds if no water)
            double bank = Double.NaN;
            boolean crossed = false;
            for (double t = plan.wallOut + 1; t < 80; t += 1) {
                var q = w.sample(plan.cx + ux * t, plan.cz + uz * t);
                boolean dry = q.height() >= 63.2 && q.zone() != com.ninjacat.greatjourney.clanlands.layout.Zone.RIVER;
                if (!dry) crossed = true;
                else if (crossed || t >= plan.wallOut + 3) {
                    bank = t;
                    break;
                }
            }
            if (Double.isNaN(bank)) return List.of();                      // no bank within reach: the cats keep to the island
            tOut = Math.max(plan.wallOut + 3, bank + 1.5);
        }
        int n = Math.max(1, (int) Math.ceil((tOut - tIn) / 5));
        List<double[]> pts = new ArrayList<>();
        for (int k = 0; k <= n; k++) {
            double t = tIn + (tOut - tIn) * k / n;
            double h = Double.NaN;
            if (prof != null && t >= cutStart) h = Math.round(prof[Math.min(prof.length - 1, (int) Math.floor(t - cutStart))]) + 1;
            pts.add(new double[] {plan.cx + ux * t, plan.cz + uz * t, h});
        }
        return pts;
    }

    /** A spot in the Clan's own land out past the way in, loaded and ticking, with dry ground to stand on; or null. */
    private static BlockPos outing(ServerLevel ow, ClanWorld w, CampSite s, double tOut, RandomSource r, boolean far) {
        for (int i = 0; i < 24; i++) {
            // keep to the gate's side, so the walk out never runs round the camp's wall
            double a = s.gate + (r.nextDouble() - 0.5) * 2.2;
            double d = Math.min(66, tOut + (far ? 22 + r.nextDouble() * 24 : 10 + r.nextDouble() * 20));
            int x = (int) Math.floor(s.cx + Math.cos(a) * d), z = (int) Math.floor(s.cz + Math.sin(a) * d);
            if (!ow.hasChunk(x >> 4, z >> 4) || !ow.isPositionEntityTicking(new BlockPos(x, s.centre.getY(), z))) continue;
            Clan here = Homeland.territoryAt(w, x, z);
            if (here != null && here != s.clan) continue;
            BlockPos top = Cats.surface(ow, x, z);
            if (Math.hypot(x - s.cx, z - s.cz) < s.outer + 6 || !standable(ow, top)) continue;
            return top;
        }
        return null;
    }

    private static BlockPos findPile(ServerLevel ow, BlockPos centre) {
        Block pile = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("warriorcats_events:fresh_kill_pile"));
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-6, -3, -6), centre.offset(6, 3, 6))) {
            if (ow.hasChunk(p.getX() >> 4, p.getZ() >> 4) && ow.getBlockState(p).is(pile)) return p.immutable();
        }
        return null;
    }

    /** A place to stand beside a block (the pile), else the block's own column. */
    private static BlockPos beside(ServerLevel ow, BlockPos b) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos at = stand(ow, b.getX() + d.getStepX(), b.getZ() + d.getStepZ(), b.getY());
            if (at != null) return at;
        }
        return b.above();
    }

    /**
     * Where a cat stands in a column nearest the height {@code hint} (within seven blocks): firm footing and two
     * blocks of room. Nearest, not highest, so a tunnel's floor wins over its roof. Null if the chunk is not loaded.
     */
    static BlockPos stand(ServerLevel level, int x, int z, int hint) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        for (int k = 0; k <= 14; k++) {
            int y = hint + ((k & 1) == 0 ? k / 2 : -(k + 1) / 2);
            BlockPos p = new BlockPos(x, y, z);
            if (standable(level, p)) return p;
        }
        return null;
    }

    static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.hasChunk(feet.getX() >> 4, feet.getZ() >> 4)) return false;
        BlockState under = level.getBlockState(feet.below());
        if (under.getCollisionShape(level, feet.below()).isEmpty() || !under.getFluidState().isEmpty()) return false;
        for (BlockPos p : new BlockPos[] {feet, feet.above()}) {
            BlockState s = level.getBlockState(p);
            if (!s.getCollisionShape(level, p).isEmpty() || !s.getFluidState().isEmpty()) return false;
        }
        return true;
    }
}
