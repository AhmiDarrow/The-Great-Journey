package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Lays the Twoleg roads, one chunk at a time like every builder here. Thunderpaths are black, a little patched, with
 * a dashed pale centre line and gravel verges; highways are wider with two lanes each way; the old Thunderpath is a
 * forgotten track of gravel and dirt the grass and moss are taking back. Water is never paved: a stone deck with
 * low walls carries the road over it. Culverts, cat-sized stone tunnels, run under the road where the books say.
 */
public final class Roads {
    private Roads() {}

    private static final BlockState ASPHALT = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState PATCH = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState LINE = Blocks.WHITE_CONCRETE.defaultBlockState();
    private static final BlockState VERGE = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState BED = Blocks.STONE.defaultBlockState();
    private static final BlockState FILL = Blocks.DIRT.defaultBlockState();
    private static final BlockState DECK = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState PARAPET = Blocks.STONE_BRICK_WALL.defaultBlockState();
    /** The deepest gap a road is banked up across with earth; deeper, it goes over on a bridge. */
    private static final int BANK_MAX = (int) Road.BANK;
    /** How far out beside a banked-up road the land is built up to slope away from it (a block down every two out). */
    private static final int TAPER = 2 * BANK_MAX;
    /** Blocks along a bridge between its piers. */
    private static final int PIER_EVERY = 12;

    /** Every road of the Clan lands that reaches this chunk. */
    static boolean paint(ClanWorld w, ChunkPainter p) {
        boolean any = false;
        for (ClanMap m : w.maps()) {
            if (m == w.lake) for (Road road : w.lake.roads()) any |= paintRoad(p, road);
            if (m == w.forest) for (Road road : w.forest.roads()) any |= paintRoad(p, road);
        }
        return any;
    }

    /** Lays the part of one road that falls in this chunk (also used for the Great Journey's highways). */
    public static boolean paintRoad(ChunkPainter p, Road road) {
        double reach = road.half() + 2 + TAPER;
        // a culvert's open cut runs on well past the road, into chunks the road itself never reaches: those chunks
        // paint their stretch of it too (seed 777001's cut at 7462 9055 stopped dead at the edge of the last chunk
        // near the road, in a 13-block wall)
        boolean any = false;
        if (road.path().distance(p.minX + 8, p.minZ + 8, reach + 16) > reach + 16) {
            for (double a : road.culverts()) any |= culvert(p, road, a);
            return any;
        }
        if (road.kind() != Road.Kind.OLD_THUNDERPATH) tidy(p, road);
        if (road.path().distance(p.minX + 8, p.minZ + 8, reach + 12) > reach + 12) {
            for (double a : road.culverts()) any |= culvert(p, road, a);
            if (road.kind() != Road.Kind.OLD_THUNDERPATH) dropLooseLeaves(p, road);
            return any;
        }
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = p.minX + dx, z = p.minZ + dz;
                double[] n = road.path().nearest(x + 0.5, z + 0.5, road.half() + 2 + TAPER);
                if (n[0] > road.half() + 1) {
                    if (road.kind() != Road.Kind.OLD_THUNDERPATH && n[0] <= road.half() + 1 + TAPER) any |= shoulder(p, road, x, z, n[0], n[1]);
                    continue;
                }
                if (road.kind() == Road.Kind.OLD_THUNDERPATH) {
                    any |= oldTrack(p, road, x, z, n[0]);
                } else {
                    any |= hardRoad(p, road, x, z, n[0], n[1]);
                }
            }
        }
        for (double a : road.culverts()) any |= culvert(p, road, a);
        if (road.kind() != Road.Kind.OLD_THUNDERPATH) dropLooseLeaves(p, road);
        return any;
    }

    /**
     * For a road laid by its own builder (the highway): clears what this chunk's decoration grew onto it next door,
     * and sweeps up the leaves its clearing cut loose.
     */
    static void tidyAfter(ChunkPainter p, Road road) {
        tidy(p, road);
        dropLooseLeaves(p, road);
    }

    /** How far round its chunk a road's pass sweeps up the leaves its clearing cut loose from their trees. */
    private static final int LOOSE = 8;

    /**
     * Clearing the road takes the trunk out from under a tree standing on it, and a chunk next door can cut a crown
     * back to the road's edge, which left clumps of leaves hanging in the air beside the road (seed 777001: 92 in
     * its road boxes, three times the wild woods' share). Leaves die more than six blocks from a log, so the wild
     * leaves near the road that no log holds within six steps are swept up, in this chunk and the {@link #LOOSE}
     * blocks round it.
     */
    private static void dropLooseLeaves(ChunkPainter p, Road road) {
        int n = 48, x0 = p.minX - 16, z0 = p.minZ - 16;
        int[] top = new int[n * n];
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < n * n; i++) {
            top[i] = p.level.getHeight(Heightmap.Types.WORLD_SURFACE, x0 + i / n, z0 + i % n) - 1;
            lo = Math.min(lo, top[i] - 40);
            hi = Math.max(hi, top[i]);
        }
        lo = Math.max(lo, hi - 127);
        int h = hi - lo + 1;
        byte[] dist = new byte[n * n * h];                 // 1 a log, 2..7 leaves that many steps from one plus one, -1 leaves
        int[] queue = new int[n * n * h];
        int head = 0, tail = 0;
        for (int i = 0; i < n * n; i++) {
            for (int y = Math.max(lo, top[i] - 40); y <= top[i]; y++) {
                BlockState s = p.get(x0 + i / n, y, z0 + i % n);
                int c = i * h + (y - lo);
                if (s.is(BlockTags.LOGS)) {
                    dist[c] = 1;
                    queue[tail++] = c;
                } else if (s.is(BlockTags.LEAVES)) {
                    dist[c] = -1;
                }
            }
        }
        while (head < tail) {
            int c = queue[head++], d = dist[c];
            if (d >= 7) continue;
            int col = c / h, y = c % h, cx = col / n, cz = col % n;
            for (int k = 0; k < 6; k++) {
                int nx = cx + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = cz + (k == 2 ? 1 : k == 3 ? -1 : 0), ny = y + (k == 4 ? 1 : k == 5 ? -1 : 0);
                if (nx < 0 || nx >= n || nz < 0 || nz >= n || ny < 0 || ny >= h) continue;
                int nc = (nx * n + nz) * h + ny;
                if (dist[nc] == -1) {
                    dist[nc] = (byte) (d + 1);
                    queue[tail++] = nc;
                }
            }
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int cx = 16 - LOOSE; cx < 32 + LOOSE; cx++) {
            for (int cz = 16 - LOOSE; cz < 32 + LOOSE; cz++) {
                int x = x0 + cx, z = z0 + cz;
                if (road.path().nearest(x + 0.5, z + 0.5, road.half() + 14)[0] > road.half() + 12) continue;
                for (int y = 0; y < h; y++) {
                    if (dist[(cx * n + cz) * h + y] != -1) continue;
                    BlockState s = p.get(x, lo + y, z);
                    if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) {
                        p.level.setBlock(at.set(x, lo + y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** How far into the chunks round it this chunk's own trees, rocks and plant patches can have spilled. */
    private static final int SPILL = 12;

    /**
     * This chunk's decoration ran just before this, and its trees, mossy rocks and patches of plants spill a few
     * blocks into the chunks round it, onto any road already laid there: clear them off the road again, down to the
     * road itself (a road not laid yet clears its own when its chunk paints it).
     */
    private static void tidy(ChunkPainter p, Road road) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = p.minX - SPILL; x < p.minX + 16 + SPILL; x++) {
            for (int z = p.minZ - SPILL; z < p.minZ + 16 + SPILL; z++) {
                if (p.inside(x, z)) continue;
                double[] n = road.path().nearest(x + 0.5, z + 0.5, road.half() + 2);
                if (n[0] > road.half() + 1) continue;
                // from the full heightmap: the worldgen one stops following a chunk once it is carved, so it sat at the
                // road and never saw the trees grown over it since (seed 4242: leaves over 425 road columns)
                int top = Math.max(p.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z),
                        p.level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z)) - 1;
                int y = top;
                while (y > top - 48 && litter(p.get(x, y, z))) y--;
                if (y == top || !laid(p.get(x, y, z))) continue;
                for (int cy = y + 1; cy <= top; cy++) {
                    BlockState s = p.get(x, cy, z);
                    // never the persistent leaves a builder placed (a camp's hedge, a den), only what grew wild
                    boolean placed = s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT);
                    if (!s.isAir() && litter(s) && !placed) p.level.setBlock(at.set(x, cy, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /** What decoration leaves lying on a road: trees, plants, pebbles and the mossy rocks of a forest floor. */
    private static boolean litter(BlockState s) {
        return s.isAir() || ChunkPainter.growth(s) || s.is(Blocks.MOSSY_COBBLESTONE)
                || BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().contains("pebble");
    }

    /** The top of a road as Roads lays it: asphalt, its patches and lines, the gravel verge, a bridge's deck. */
    private static boolean laid(BlockState s) {
        return s.is(ASPHALT.getBlock()) || s.is(PATCH.getBlock()) || s.is(LINE.getBlock()) || s.is(VERGE.getBlock()) || s.is(DECK.getBlock());
    }

    private static int hash(int x, int z) {
        int h = x * 73428767 ^ z * 912931;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return (h ^ (h >>> 15)) & 0x7fffffff;
    }

    /** Where the road's surface lies: its planned bed inside the Clan lands, the land itself out in the wider world. */
    private static int surfaceY(ChunkPainter p, Road road, int x, int z, double along, int ground) {
        if (!road.graded()) return ground;
        ClanWorld w = ClanWorld.current();
        double weight = w == null ? 0 : w.sample(x, z).weight();
        return weight >= 0.95 ? (int) Math.round(road.bedAt(along)) : ground;
    }

    private static boolean hardRoad(ChunkPainter p, Road road, int x, int z, double dist, double along) {
        int g = p.ground(x, z);
        boolean wet = !p.get(x, g, z).getFluidState().isEmpty();
        int y = surfaceY(p, road, x, z, along, g);
        if (wet) y = Math.max(Math.max(y, 64), g + 1);
        // over water, or a gap too deep to bank up (a ravine, a stream's gully): a bridge rather than a dam of earth;
        // and where the plan runs a river under the road but the real rock overhangs it, a bridge too, the arch of rock
        // under it opened down to the water (seed 4242's forest Thunderpath at 8006 8999 lay on such an arch)
        int riverBed = riverUnder(road, x, z, y);
        boolean bridge = wet || y - g > BANK_MAX || riverBed != Integer.MIN_VALUE;
        boolean verge = dist > road.half();
        if (verge) {
            if (bridge) return false;
            p.set(x, y, z, VERGE);
            clear(p, x, y, z, Math.max(g, y) + 6);
            return true;
        }
        if (bridge) {
            // a stone deck, low walls along its edges, and every so often a pier down to the ground or the bed
            p.set(x, y, z, DECK);
            clear(p, x, y, z, y + 8);
            if (riverBed != Integer.MIN_VALUE) {
                for (int cy = y - 1; cy > riverBed; cy--) {
                    BlockState s = p.get(x, cy, z);
                    if (!s.getFluidState().isEmpty()) break;
                    if (!s.isAir()) p.set(x, cy, z, Blocks.AIR.defaultBlockState());
                }
            }
            if (dist > road.half() - 1) p.set(x, y + 1, z, PARAPET.setValue(WallBlock.UP, true));
            if (Math.floorMod((int) Math.floor(along), PIER_EVERY) < 2) {
                for (int py = y - 1; py > p.level.getMinBuildHeight() + 1 && py > y - 64; py--) {
                    BlockState under = p.get(x, py, z);
                    if (!under.isAir() && under.getFluidState().isEmpty() && !ChunkPainter.growth(under)) break;
                    p.set(x, py, z, DECK);
                }
            }
            return true;
        }
        for (int fy = g + 1; fy < y; fy++) p.set(x, fy, z, fy == y - 1 ? BED : FILL);
        p.set(x, y, z, surface(road, x, z, dist, along));
        if (p.get(x, y - 1, z).isAir()) p.set(x, y - 1, z, BED);
        clear(p, x, y, z, Math.max(g, y) + 10);
        return true;
    }

    /**
     * Where the plan runs a river under a graded road far below its bed: the level the rock under the deck is opened
     * down to (a few blocks over the planned bed, about the water's surface, and never under the sea's), else
     * {@link Integer#MIN_VALUE}.
     */
    private static int riverUnder(Road road, int x, int z, int y) {
        if (!road.graded()) return Integer.MIN_VALUE;
        ClanWorld w = ClanWorld.current();
        if (w == null) return Integer.MIN_VALUE;
        var s = w.sample(x + 0.5, z + 0.5);
        if (s.weight() < 0.95 || s.zone() != com.ninjacat.greatjourney.clanlands.layout.Zone.RIVER || s.height() >= y - BANK_MAX) {
            return Integer.MIN_VALUE;
        }
        return Math.max(62, (int) Math.round(s.height()) + 3);
    }

    /**
     * Beside a road banked up over low ground the land is built up to meet it, a block lower for every two out, so
     * the bank slopes away instead of standing sheer (seed 777001's forest Thunderpath at 8821 9043 stood on a
     * ten-block wall). Never over water, a culvert's mouth or a gap the road bridges.
     */
    private static boolean shoulder(ChunkPainter p, Road road, int x, int z, double dist, double along) {
        if (!road.graded()) return false;
        ClanWorld w = ClanWorld.current();
        if (w == null || w.sample(x + 0.5, z + 0.5).weight() < 0.95) return false;
        for (double c : road.culverts()) if (Math.abs(along - c) < 4) return false;
        int target = (int) Math.round(road.bedAt(along)) - (int) Math.ceil((dist - road.half() - 1) / 2);
        int g = p.ground(x, z);
        if (g >= target || target - g > BANK_MAX || !p.get(x, g, z).getFluidState().isEmpty()) return false;
        for (int fy = g + 1; fy <= target; fy++) p.set(x, fy, z, fy == target ? Blocks.GRASS_BLOCK.defaultBlockState() : FILL);
        return true;
    }

    /** Asphalt, a patch here and there, and the painted lines. */
    private static BlockState surface(Road road, int x, int z, double dist, double along) {
        boolean dash = ((int) Math.floor(along / 3)) % 2 == 0;
        if (road.kind() == Road.Kind.HIGHWAY) {
            double s = signedSide(road, x + 0.5, z + 0.5, along) * dist;
            if (Math.abs(s) < 0.45) return LINE;                                         // the solid middle line
            double lane = road.half() / 2;
            if (dash && (Math.abs(s - lane) < 0.5 || Math.abs(s + lane) < 0.5)) return LINE;
        } else if (dist < 0.5 && dash) {
            return LINE;
        }
        return hash(x, z) % 13 == 0 ? PATCH : ASPHALT;
    }

    /** Which side of the road a point is on: +1 to the left of the direction of travel, -1 to the right. */
    private static double signedSide(Road road, double x, double z, double along) {
        double[] a = road.path().pointAt(along), b = road.path().pointAt(along + 1);
        double cross = (b[0] - a[0]) * (z - a[1]) - (b[1] - a[1]) * (x - a[0]);
        return cross >= 0 ? 1 : -1;
    }

    /** The old Thunderpath: gravel and dirt half lost under grass and moss; trees are left standing in it. */
    private static boolean oldTrack(ChunkPainter p, Road road, int x, int z, double dist) {
        if (dist > road.half()) return false;
        int g = p.ground(x, z);
        if (!p.get(x, g, z).getFluidState().isEmpty()) return false;
        int k = hash(x, z) % 100;
        BlockState s = k < 40 ? Blocks.GRAVEL.defaultBlockState()
                : k < 70 ? Blocks.COARSE_DIRT.defaultBlockState()
                : k < 90 ? Blocks.GRASS_BLOCK.defaultBlockState()
                : Blocks.MOSS_BLOCK.defaultBlockState();
        p.set(x, g, z, s);
        for (int i = 1; i <= 3; i++) {
            BlockState above = p.get(x, g + i, z);
            if (!above.isAir() && above.getFluidState().isEmpty() && above.canBeReplaced()) p.set(x, g + i, z, Blocks.AIR.defaultBlockState());
        }
        return true;
    }

    /**
     * Clears plants, trees, snow and earth above the road surface up to {@code top}, leaving water alone; and above
     * that, the rest of any tree standing there, so no cut-off trunk or crown is left floating over the road.
     */
    private static void clear(ChunkPainter p, int x, int y, int z, int top) {
        for (int cy = y + 1; cy <= y + 30; cy++) {
            BlockState s = p.get(x, cy, z);
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            if (cy > top && !ChunkPainter.growth(s)) break;                    // earth or rock over the road: a cutting
            if (!holdsWater(p, x, cy, z)) p.set(x, cy, z, Blocks.AIR.defaultBlockState());
        }
    }

    /** Whether a block is the bank of a stream or pool beside it: taking it away would leave the water standing in air. */
    private static boolean holdsWater(ChunkPainter p, int x, int y, int z) {
        if (p.get(x, y + 1, z).is(Blocks.WATER)) return true;                   // the bed under a stream
        for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (p.readable(x + o[0], z + o[1]) && p.get(x + o[0], y, z + o[1]).is(Blocks.WATER)) return true;
        }
        return false;
    }

    /**
     * A culvert: a three-wide stone-brick tunnel at the land's own level, passing under the road where it rises over
     * it. Roofed only where earth or road lies over it, open to the sky where it comes out of the embankment.
     */
    private static boolean culvert(ChunkPainter p, Road road, double along) {
        double[] at = road.path().pointAt(along), ahead = road.path().pointAt(along + 1);
        double dx = ahead[0] - at[0], dz = ahead[1] - at[1], len = Math.max(1e-6, Math.hypot(dx, dz));
        dx /= len;
        dz /= len;
        double px = -dz, pz = dx;                                                             // across the road
        int floor = (int) Math.round(road.floorAt(along));
        // past the embankment the culvert runs on as an open cut until it comes out where the land is low, so
        // neither mouth ends blind in a hillside
        double runP = runOut(at, px, pz, road.half() + 9, floor, 1), runN = runOut(at, px, pz, road.half() + 9, floor, -1);
        double run = Math.max(runP, runN) + CLIMB_OUT;
        if (at[0] + run + 3 < p.minX || at[0] - run - 3 > p.minX + 15 || at[1] + run + 3 < p.minZ || at[1] - run - 3 > p.minZ + 15) {
            return false;
        }
        boolean any = false;
        for (double s = -runN; s <= runP; s += 0.5) {
            boolean cut = Math.abs(s) > road.half() + 9;
            for (int w = -2; w <= 2; w++) {
                int x = (int) Math.floor(at[0] + px * s + dx * w), z = (int) Math.floor(at[1] + pz * s + dz * w);
                if (!p.inside(x, z)) continue;
                // where the real land drops away below the culvert's level (a dip the road bridges) there is nothing
                // to tunnel through: no gravel floor hanging in the air (seed 4242's forest Thunderpath)
                // (but a cave opening under the hill it runs through gets a stone footing, not a pit in the floor: seed
                // 4242's culvert at 6983 9004 crossed a nine-deep hollow under an overhang)
                if (!supported(p, x, floor, z) && (p.ground(x, z) <= floor || !footing(p, x, floor, z))) continue;
                if (cut) {
                    // where the real land stands high over it the culvert runs on as a tunnel under the hill, walled
                    // and roofed like under the road, rather than as a gash a dozen blocks deep (seed 777001, 7462 9055)
                    boolean hill = p.ground(x, z) >= floor + 6;
                    if (Math.abs(w) == 2) {
                        if (hill) for (int y = floor + 1; y <= floor + 3; y++) p.set(x, y, z, DECK);
                        continue;
                    }
                    p.set(x, floor, z, Blocks.GRAVEL.defaultBlockState());
                    if (hill) {
                        for (int y = floor + 1; y <= floor + 3; y++) {
                            if (p.get(x, y, z).getFluidState().isEmpty()) p.set(x, y, z, Blocks.AIR.defaultBlockState());
                        }
                        p.set(x, floor + 4, z, DECK);
                        any = true;
                        continue;
                    }
                    int top = Math.max(floor + 3, p.ground(x, z));
                    for (int y = floor + 1; y <= top; y++) {
                        if (p.get(x, y, z).getFluidState().isEmpty()) p.set(x, y, z, Blocks.AIR.defaultBlockState());
                    }
                    p.clearGrowth(x, top, z, 12);                             // no grass left hanging over the cut
                    any = true;
                    continue;
                }
                int over = Places.groundAt(x + 0.5, z + 0.5);
                boolean covered = Math.abs(s) <= road.half() + 1 || over >= floor + 5;
                if (Math.abs(w) == 2) {
                    if (covered) for (int y = floor + 1; y <= floor + 3; y++) p.set(x, y, z, DECK);
                    continue;
                }
                p.set(x, floor, z, Blocks.GRAVEL.defaultBlockState());
                for (int y = floor + 1; y <= floor + 3; y++) {
                    if (p.get(x, y, z).getFluidState().isEmpty()) p.set(x, y, z, Blocks.AIR.defaultBlockState());
                }
                if (covered) p.set(x, floor + 4, z, DECK);
                any = true;
            }
        }
        // the real land stands a few blocks off the plan, so where the cut ends it may still be below the ground: from
        // there the floor climbs a block a block until it comes out on the land, never a wall to jump at the end
        for (int dir = -1; dir <= 1; dir += 2) {
            double end = dir > 0 ? runP : runN;
            for (double s = end + 0.5; s <= end + CLIMB_OUT; s += 0.5) {
                int ry = floor + (int) Math.ceil(s - end);
                for (int w = -1; w <= 1; w++) {
                    int x = (int) Math.floor(at[0] + px * s * dir + dx * w), z = (int) Math.floor(at[1] + pz * s * dir + dz * w);
                    if (!p.inside(x, z)) continue;
                    int g = p.ground(x, z);
                    if (g <= ry || !p.get(x, g, z).getFluidState().isEmpty()) continue;    // out on the land
                    p.set(x, ry, z, Blocks.GRAVEL.defaultBlockState());
                    for (int y = ry + 1; y <= g; y++) {
                        if (p.get(x, y, z).getFluidState().isEmpty()) p.set(x, y, z, Blocks.AIR.defaultBlockState());
                    }
                    p.clearGrowth(x, g, z, 12);
                    any = true;
                }
            }
        }
        return any;
    }

    /** How far past its open cut a culvert's floor may climb to come out on the real land. */
    private static final int CLIMB_OUT = 16;

    /** Fills a hollow under y with stone down to the ground, when ground stands within 16 blocks; else false. */
    private static boolean footing(ChunkPainter p, int x, int y, int z) {
        int d = 1;
        while (d <= 16 && p.get(x, y - d, z).isAir()) d++;
        BlockState s = p.get(x, y - d, z);
        if (d > 16 || !s.getFluidState().isEmpty()) return false;
        for (int f = 1; f < d; f++) p.set(x, y - f, z, Builders.STONE);
        return true;
    }

    /** Whether ground stands within three blocks under y; a gap of a block or two above it is filled with stone. */
    private static boolean supported(ChunkPainter p, int x, int y, int z) {
        for (int d = 1; d <= 3; d++) {
            BlockState s = p.get(x, y - d, z);
            if (!s.isAir() && s.getFluidState().isEmpty()) {
                for (int f = 1; f < d; f++) p.set(x, y - f, z, Builders.STONE);
                return true;
            }
        }
        return false;
    }

    /**
     * How far out from the road's middle a culvert must run on one side ({@code dir} +1 or -1) to come out into the
     * open: past where the planned land first stays low (no higher than its floor plus one) for four blocks running,
     * plus a margin. The open cut there goes down through whatever real ground stands over it.
     */
    private static double runOut(double[] at, double px, double pz, double from, int floor, int dir) {
        double s = from;
        int low = 0;
        while (s < from + 30) {
            if (Places.groundAt(at[0] + px * s * dir, at[1] + pz * s * dir) <= floor + 1) {
                if (++low >= 4) break;
            } else {
                low = 0;
            }
            s += 1;
        }
        return s + 5;                     // and a little more: the real ground rises a few blocks off the plan
    }
}
