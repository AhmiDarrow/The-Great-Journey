package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.layout.JourneyRoute;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/** The places of the Great Journey: the highway, Twoleg farms, the Tribe's cave, Midnight's sett, the lake lookout. */
final class JourneyPlaces {
    private JourneyPlaces() {}

    private static final BlockState ASPHALT = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState LINE = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState MEDIAN = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState FALLING = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);

    private static JourneyRoute journey() {
        ClanWorld w = ClanWorld.current();
        return w == null ? null : w.journey;
    }

    /**
     * One stretch of the highway: two carriageways of black road with white lane lines, a grey central reservation
     * with a low barrier, gravel verges, the strip cleared of trees and the road held up on stone where the land dips.
     */
    static void highway(ChunkPainter p, Landmark l) {
        JourneyRoute j = journey();
        if (j == null) return;
        Road road = j.highway();
        double half = road.half();
        double far = road.path().distance(p.minX + 8, p.minZ + 8, half + 30);
        if (far > half + 28) return;
        if (far > half + 14) {
            Roads.tidyAfter(p, road);                                            // its trees may reach the road
            return;
        }
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = p.minX + dx, z = p.minZ + dz;
                double[] n = road.path().nearest(x + 0.5, z + 0.5, half + 3);
                if (n[0] > half + 2) continue;
                int y = (int) Math.round(road.bedAt(n[1]));
                BlockState top;
                if (n[0] > half) {
                    top = Blocks.GRAVEL.defaultBlockState();
                } else if (n[0] < 1.3) {
                    top = MEDIAN;
                } else if (n[0] > half - 0.8) {
                    top = LINE;                                                  // solid edge line
                } else if (Math.abs(n[0] - half / 2 - 0.5) < 0.5 && ((int) Math.floor(n[1] / 6) & 1) == 0) {
                    top = LINE;                                                  // dashed lane line
                } else {
                    top = ASPHALT;
                }
                p.set(x, y, z, top);
                // an embankment where the land dips; over water a bridge deck, with stone piers every 8 blocks
                boolean pier = Math.floorMod((int) Math.floor(n[1]), 8) < 2 && n[0] < half - 1;
                boolean overWater = false;
                for (int k = 1; k <= 14; k++) {
                    var below = p.get(x, y - k, z);
                    if (!below.getFluidState().isEmpty()) {
                        overWater = true;
                        break;
                    }
                    if (!(below.isAir() || below.canBeReplaced())) break;
                }
                if (overWater && !pier) {
                    p.set(x, y - 1, z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
                } else {
                    for (int k = 1; k <= 14; k++) {
                        var below = p.get(x, y - k, z);
                        if (!(below.isAir() || below.canBeReplaced() || !below.getFluidState().isEmpty())) break;
                        p.set(x, y - k, z, Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
                p.clearAbove(x, y, z, 8);
                p.clearGrowth(x, y + 8, z, 24);                                   // and the crown of a tree cut through
                if (n[0] < 0.6) p.set(x, y + 1, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
        }
        Roads.tidyAfter(p, road);
    }

    /** Twoleg farms: the barn and paddock, and rows of crops in tilled fields beside them. */
    static void farms(ChunkPainter p, Landmark l, RandomSource r) {
        Places.farm(p, l, r);
        double ang = l.facing(), cx = Math.cos(ang), cz = Math.sin(ang);
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = p.minX + dx, z = p.minZ + dz;
                double ox = x + 0.5 - l.x(), oz = z + 0.5 - l.z();
                double f = ox * cx + oz * cz, s = -ox * cz + oz * cx;
                if (f < 28 || f > 62 || Math.abs(s) > 26) continue;              // the fields lie beyond the paddock
                int g = p.ground(x, z);
                p.clearAbove(x, g, z, 6);
                int row = (int) Math.floor(s + 26);
                if (row % 5 == 0) {
                    p.set(x, g, z, Blocks.WATER.defaultBlockState());            // an irrigation ditch
                } else {
                    p.set(x, g, z, Blocks.FARMLAND.defaultBlockState());
                    BlockState crop = (row / 10) % 2 == 0 ? Blocks.WHEAT.defaultBlockState() : Blocks.CARROTS.defaultBlockState();
                    p.set(x, g + 1, z, crop.setValue(CropBlock.AGE, Math.min(7, 4 + Math.floorMod(x * 31 + z * 17, 4))));
                }
            }
        }
    }

    /**
     * The Tribe of Rushing Water's cave: a mountain stream runs out along a rock lip that overhangs the cliff and falls
     * in a tall curtain into a pool; behind the falling water a mouth opens into a great cavern with stone ledges,
     * a small pool and nests. Facing is the direction from the cave out through the falls.
     */
    static void tribeCave(ChunkPainter p, Landmark l, RandomSource r) {
        JourneyRoute j = journey();
        if (j == null) return;
        double[] f = j.tribeFacing();
        double fx = f[0], fz = f[1], sx = -fz, sz = fx;
        int basin = Places.groundAt(l.x() + fx * 14, l.z() + fz * 14);
        int top = Places.groundAt(l.x() - fx * 10, l.z() - fz * 10);
        if (top - basin < 12) top = basin + 26;
        int nests = 8;
        double[][] nest = new double[nests][];
        for (int i = 0; i < nests; i++) {
            double a = (i + r.nextDouble() * 0.4) / nests * Math.PI * 2;
            nest[i] = new double[] {-22 + Math.cos(a) * 12, Math.sin(a) * 12};
        }
        BlockState moss = Builders.optional("warriorcats_events:moss_bed", Blocks.MOSS_CARPET.defaultBlockState());
        BlockState glow = Builders.optional("warriorcats_events:glowrocks", Blocks.GLOW_LICHEN.defaultBlockState());
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = p.minX + dx, z = p.minZ + dz;
                double ox = x + 0.5 - l.x(), oz = z + 0.5 - l.z();
                double fwd = ox * fx + oz * fz, side = ox * sx + oz * sz;
                double as = Math.abs(side);
                // the overhanging lip and the stream running out along it
                if (fwd >= -14 && fwd <= 3 && as <= 6) {
                    for (int y = top - 2; y <= top; y++) p.set(x, y, z, Blocks.STONE.defaultBlockState());
                    if (as <= 3) {
                        p.set(x, top, z, Blocks.WATER.defaultBlockState());
                        if (fwd > 2) p.level.scheduleTick(new BlockPos(x, top, z), Fluids.WATER, 2);
                    } else {
                        p.set(x, top + 1, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                    }
                    p.clearAbove(x, top + (as <= 3 ? 0 : 1), z, 4);
                }
                // the basin in front of the cliff, opened down to the cave's floor whatever the rock did
                double bowl = Math.pow((fwd - 12) / 10, 2) + Math.pow(side / 11, 2);
                if (fwd > 3 && bowl < 1) {
                    for (int y = basin + 1; y <= top + 2; y++) {
                        if (!p.get(x, y, z).isAir()) p.set(x, y, z, Builders.AIR);
                    }
                    p.set(x, basin, z, bowl > 0.7 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState());
                }
                // the curtain of falling water
                if (fwd > 3 && fwd <= 4.2 && as <= 3) {
                    for (int y = basin + 1; y < top; y++) {
                        if (p.get(x, y, z).isAir()) p.set(x, y, z, FALLING);
                    }
                }
                // the plunge pool in the basin
                double pool = Math.pow((fwd - 9) / 7, 2) + Math.pow(side / 8, 2);
                if (pool < 1) {
                    p.clearAbove(x, basin, z, 3);
                    for (int y = basin - 2; y <= basin; y++) p.set(x, y, z, Blocks.WATER.defaultBlockState());
                    p.set(x, basin - 3, z, Blocks.GRAVEL.defaultBlockState());
                }
                // the mouth, recessed behind the curtain
                if (fwd <= 3 && fwd >= -7 && as <= 2) {
                    for (int y = basin + 1; y <= basin + 5; y++) p.set(x, y, z, Builders.AIR);
                    p.set(x, basin, z, Blocks.STONE.defaultBlockState());
                }
                // the great cavern
                double cd = Math.hypot(fwd + 22, side);
                if (cd < 18) {
                    int ceiling = basin + 1 + (int) Math.round(10 * Math.sqrt(1 - Math.pow(cd / 18, 2)));
                    int floor = basin + (cd > 13 ? 2 : 0);                       // ledges round the walls
                    for (int y = floor + 1; y <= ceiling; y++) p.set(x, y, z, Builders.AIR);
                    p.set(x, floor, z, Blocks.STONE.defaultBlockState());
                    if (cd < 3.5) p.set(x, basin, z, Blocks.WATER.defaultBlockState());
                    if (cd > 16 && ((x * 7 + z * 11) & 15) == 0) p.set(x, floor + 1, z, glow);
                }
                for (double[] n : nest) {
                    if (Math.abs(fwd - n[0]) < 0.5 && Math.abs(side - n[1]) < 0.5) p.set(x, basin + 1, z, moss);
                }
            }
        }
    }

    /** Midnight's sett: a round entrance in the sandy bank, a short tunnel and a snug chamber bedded with dry grass. */
    static void midnightSett(ChunkPainter p, Landmark l, RandomSource r) {
        double sx = Math.cos(l.facing()), sz = Math.sin(l.facing());           // toward the sea
        int y0 = Places.groundAt(l.x() + sx * 6, l.z() + sz * 6) + 1;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = p.minX + dx, z = p.minZ + dz;
                double ox = x + 0.5 - l.x(), oz = z + 0.5 - l.z();
                double fwd = ox * sx + oz * sz, side = -ox * sz + oz * sx;
                if (fwd <= 4 && fwd >= -5 && Math.abs(side) <= 1.2) {            // the tunnel into the bank
                    for (int y = y0; y <= y0 + 1; y++) p.set(x, y, z, Builders.AIR);
                    p.set(x, y0 - 1, z, Blocks.COARSE_DIRT.defaultBlockState());
                }
                double cd = Math.hypot(fwd + 8, side);
                if (cd < 4) {                                                   // the chamber
                    for (int y = y0; y <= y0 + 2; y++) p.set(x, y, z, Builders.AIR);
                    p.set(x, y0 - 1, z, Blocks.COARSE_DIRT.defaultBlockState());
                    if (cd < 1.6) p.set(x, y0, z, Blocks.SHORT_GRASS.defaultBlockState());
                    if (cd > 2.6 && ((x + z) & 1) == 0) p.set(x, y0 - 1, z, Blocks.HAY_BLOCK.defaultBlockState());
                }
            }
        }
    }

    /** A lookout rock on the ridge above the lake, where the Clans first saw their new home. */
    static void lookout(ChunkPainter p, Landmark l, RandomSource r) {
        int g = Places.groundAt(l.x(), l.z());
        Trees.ball(p, (int) Math.floor(l.x()), g + 1, (int) Math.floor(l.z()), 3.2, Blocks.ANDESITE.defaultBlockState());
        Trees.ball(p, (int) Math.floor(l.x()) + 2, g + 3, (int) Math.floor(l.z()) - 1, 2.0, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
    }
}
