package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.River;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Lays the water of the streams and rivers that run above sea level (worldgen fills water only below it) and makes
 * their waterfalls fall. The terrain only roughly follows a narrow channel, so each channel column is dug to its
 * planned bed (deepest mid-stream) and filled with still water up to the planned surface, and the banks beside it
 * are raised to that surface wherever the land came out lower, so no stream runs dry or spills out sideways. Where
 * the surface steps down, a curtain of falling water hangs from the real brink, with the lip's water told to start
 * flowing when the chunk loads.
 */
final class Waters {
    private Waters() {}

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState FALLING = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
    /** How far either side of the planned fall to look for the real brink, in blocks along the stream. */
    private static final int EDGE_SEARCH = 8;
    /** Ground standing this far over the planned water is a knoll the stream goes round, not through. */
    private static final int MAX_DIG = 6;
    /**
     * A hole this far under the bed of the stretch below a fall is no gorge but a cave or a pit (a sea-level river
     * crossing under the stream, seed -84596914 at 2184 -431): the stream is carried over it, not poured into it.
     */
    private static final int CAVE = 6;

    static boolean paint(ClanWorld w, ChunkPainter p) {
        double cx = p.minX + 8, cz = p.minZ + 8;
        List<River> rivers = new ArrayList<>();
        for (ClanMap m : w.maps()) {
            if (!m.covers(cx, cz)) continue;
            if (m == w.lake) rivers.addAll(w.lake.rivers());
            if (m == w.forest) rivers.addAll(w.forest.rivers());
        }
        boolean any = false;
        for (River river : rivers) {
            if (river.path.distance(cx, cz, river.half + 16) > river.half + 14) continue;
            any |= fill(river, p);
            any |= banks(river, rivers, p);
            any |= falls(river, p);
        }
        return any;
    }

    /** The water a planned surface stands at: a stream at sea level lies at the lake's own level, y 62. */
    private static int water(int surface) {
        return surface == 63 ? 62 : surface;
    }

    /**
     * The water a column's stretch pours down to: the next stretch's below a fall just downstream of it, else its
     * own. A hole down near that level is the gorge under the brink; one far below it is a cave or a pit.
     */
    private static int footWater(River river, double along, int surface) {
        for (double[] f : river.falls()) {
            if (f[0] >= along && f[0] - along <= EDGE_SEARCH + 2) return water((int) Math.floor(f[2]));
        }
        return water(surface);
    }

    /**
     * The planned surface of the river at a column, or {@link Integer#MIN_VALUE} when the column is not in its
     * channel; {@code out[0]} gets the planned bed there.
     */
    private static int planned(River river, int x, int z, int[] out) {
        double[] n = river.path.nearest(x + 0.5, z + 0.5, river.half + 1);
        if (n[0] > river.half || !river.owns(n[1])) return Integer.MIN_VALUE;
        int surface = (int) Math.floor(river.surfaceAt(n[1]));
        double t = n[0] / river.half;
        // the bed sits under the water's real top: a sea-level stream's water stands at 62, so its bed is planned from
        // there (planned from 63 it lay at 62, level with the water, and the stream's last stretch ran dry)
        int water = surface == 63 ? 62 : surface;
        out[0] = water - Math.max(1, (int) Math.round(river.depth * (1 - t * t)));
        if (out.length > 1) out[1] = river.besideFall(n[1], EDGE_SEARCH + 2);
        if (out.length > 2) out[2] = footWater(river, n[1], surface);
        return surface;
    }

    /**
     * Whether a channel column whose ground {@code g} lies far under its water is the gorge just below a brink that
     * the land reached early (left open: the stream pours over into it), rather than a cave or a pit under the stream.
     */
    private static boolean gorge(River river, int g, int surface, int fall, int foot) {
        return fall < 0 && g < water(surface) - river.depth - 3 && g >= foot - river.depth - CAVE;
    }

    private static boolean fill(River river, ChunkPainter p) {
        // the channel's plan over this chunk and a one-column ring, so the banks can be told from the stream
        int[] surf = new int[18 * 18], bed = new int[18 * 18], fall = new int[18 * 18], foot = new int[18 * 18], tmp = new int[3];
        boolean inChunk = false;
        for (int i = 0; i < 18; i++) {
            for (int j = 0; j < 18; j++) {
                int s = planned(river, p.minX - 1 + i, p.minZ - 1 + j, tmp);
                surf[i + 18 * j] = s;
                bed[i + 18 * j] = tmp[0];
                fall[i + 18 * j] = tmp[1];
                foot[i + 18 * j] = tmp[2];
                if (s != Integer.MIN_VALUE && i >= 1 && i <= 16 && j >= 1 && j <= 16) inChunk = true;
            }
        }
        if (!inChunk) return false;
        // each column's level is its planned surface: the banks are raised to it wherever the land beside lies lower
        // ({@link #banks}), so nothing holds the water under its plan (held under the real banks, one low bank let a
        // whole stretch down to it: sea-level streams stood a block deep in a bed dug for three, or ran dry, and just
        // above a fall each column kept its own bank's level, leaving strips of water open along the sides)
        final int none = Integer.MIN_VALUE;
        int[] level = new int[18 * 18], ground = new int[18 * 18];
        boolean[] hole = new boolean[18 * 18];
        java.util.Arrays.fill(level, none);
        java.util.Arrays.fill(ground, none);
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int k = (dx + 1) + 18 * (dz + 1);
                int surface = surf[k];
                if (surface == none || surface < 63) continue;                 // below sea level the sea fills it
                // a river at sea level lies at the lake's own level (water up to y 62), not one block proud of it
                surface = water(surface);
                int x = p.minX + dx, z = p.minZ + dz;
                int g = p.ground(x, z);
                ground[k] = g;
                // ground far above the water is a knoll the stream goes round: leave it
                if (g > surface + MAX_DIG) continue;
                hole[k] = g < surface - river.depth - 3;
                // ground far below the bed just above a fall, down near the foot of the fall, is where the land has
                // already dropped away (the gorge below the brink): leave it open
                if (gorge(river, g, surf[k], fall[k], foot[k])) continue;
                level[k] = surface;
            }
        }
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int k = (dx + 1) + 18 * (dz + 1);
                int top = level[k];
                if (top == none) continue;
                int x = p.minX + dx, z = p.minZ + dz;
                int g = ground[k];
                int floor = Math.min(g, bed[k]);
                if (top <= floor) continue;
                // elsewhere a hole under the bed is a cave mouth, a pit or a dip: lay the bed back over it, or the
                // stream would stand round an empty shaft
                // (just below a fall a shallow one is the plunge pool: just fill it, deep)
                boolean cave = hole[k] && g < foot[k] - river.depth - CAVE;
                if (hole[k] && (fall[k] == 0 || cave)) {
                    for (int y = g + 1; y <= bed[k]; y++) p.set(x, y, z, y == bed[k] ? GRAVEL : Builders.STONE);
                    floor = bed[k];
                }
                for (int y = floor + 1; y <= top; y++) p.set(x, y, z, WATER);
                // digging can break into the roof of a cave just under the bed: close it, so the stream keeps a floor
                for (int y = floor; y > floor - 24; y--) {
                    BlockState under = p.get(x, y, z);
                    if (!(under.isAir() || ChunkPainter.growth(under))) break;
                    p.set(x, y, z, Builders.STONE);
                }
                for (int y = top + 1; y <= Math.max(g, top) + 3; y++) {
                    BlockState s = p.get(x, y, z);
                    if (!s.isAir() && s.getFluidState().isEmpty()) p.set(x, y, z, AIR);
                }
                any = true;
            }
        }
        return any;
    }

    /**
     * Raises the banks of a stream above sea level: wherever the land right beside its water lies lower than the
     * water (the real ground came out a few blocks under the plan, or a cave or pit opens beside it), the bank is
     * built up to the water, so the stream stands full in its bed instead of draining away or spilling out sideways;
     * the column beyond, where it lies lower still, a block under that. Decided column by column from the plan and
     * the ground, so neighbouring chunks agree on every bank whichever is painted first. Never beside the open gorge
     * under a brink (the fall's own face), across another stream's channel, or past the ends of a stretch.
     */
    private static boolean banks(River river, List<River> rivers, ChunkPainter p) {
        final int none = Integer.MIN_VALUE;
        int[] surf = new int[18 * 18], fall = new int[18 * 18], foot = new int[18 * 18], tmp = new int[3];
        boolean near = false;
        for (int i = 0; i < 18; i++) {
            for (int j = 0; j < 18; j++) {
                int s = planned(river, p.minX - 1 + i, p.minZ - 1 + j, tmp);
                surf[i + 18 * j] = s;
                fall[i + 18 * j] = tmp[1];
                foot[i + 18 * j] = tmp[2];
                near |= s != none;
            }
        }
        if (!near) return false;
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int k = (dx + 1) + 18 * (dz + 1);
                if (surf[k] != none) continue;                                 // the channel itself
                int x = p.minX + dx, z = p.minZ + dz;
                double[] n = river.path.nearest(x + 0.5, z + 0.5, river.half + 3);
                // inside the channel's width but not this stretch's (past the brink of its end falls): the fall's face
                if (n[0] <= river.half || !river.owns(n[1])) continue;
                int target = none;
                for (int[] o : SIDES) {
                    int ni = dx + 1 + o[0], nj = dz + 1 + o[1], s = surf[ni + 18 * nj];
                    if (s == none || s < 63) continue;
                    int w = water(s), nx = x + o[0], nz = z + o[1];
                    int g = p.inside(nx, nz) ? p.ground(nx, nz) : p.solidTop(nx, nz, w + MAX_DIG + 2, w - 40);
                    // no water beside it: a knoll in the channel, or the open gorge under a brink
                    if (g > w + MAX_DIG || gorge(river, g, s, fall[ni + 18 * nj], foot[ni + 18 * nj])) continue;
                    target = Math.max(target, w);
                }
                if (target == none && n[0] <= river.half + 2.2) {
                    int s = (int) Math.floor(river.surfaceAt(n[1]));
                    if (s >= 63) target = water(s) - 1;
                }
                if (target == none) continue;
                int top = p.wetTop(x, z, target, target - 40);
                if (top >= target || inOtherChannel(rivers, river, x, z)) continue;
                BlockState was = p.get(x, top, z);
                BlockState cap = was.is(BlockTags.DIRT) || was.is(BlockTags.SAND) || was.is(Blocks.GRAVEL)
                        || was.is(BlockTags.BASE_STONE_OVERWORLD) ? was : GRASS;
                for (int y = top + 1; y <= target; y++) {
                    p.set(x, y, z, y == target ? cap : target - y >= 3 ? Builders.STONE : DIRT);
                }
                if (was.is(Blocks.GRASS_BLOCK)) p.set(x, top, z, DIRT);
                any = true;
            }
        }
        return any;
    }

    /** Whether a column lies in the channel of any stream but this one (a confluence: its water is laid there). */
    private static boolean inOtherChannel(List<River> rivers, River river, int x, int z) {
        for (River other : rivers) {
            if (other == river) continue;
            double[] n = other.path.nearest(x + 0.5, z + 0.5, other.half + 1);
            if (n[0] <= other.half + 1) return true;
        }
        return false;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static boolean falls(River river, ChunkPainter p) {
        boolean any = false;
        int[] tmp = new int[3];
        for (double[] fall : river.falls()) {
            double[] at = river.path.pointAt(fall[0]);
            double margin = river.half + EDGE_SEARCH + 5;
            if (at[0] < p.minX - margin || at[0] > p.minX + 16 + margin
                    || at[1] < p.minZ - margin || at[1] > p.minZ + 16 + margin) continue;
            double[] ahead = river.path.pointAt(fall[0] + 3), back = river.path.pointAt(fall[0] - 3);
            double ux = ahead[0] - back[0], uz = ahead[1] - back[1], ul = Math.max(1e-6, Math.hypot(ux, uz));
            ux /= ul;
            uz /= ul;
            int top = (int) Math.floor(fall[1]), bottom = (int) Math.floor(fall[2]);
            int brink = (int) Math.floor(top - river.depth - 2);       // a bed at or above this is the upper stretch
            // across the river's width: walk down the stream to where the ground really drops away (the noise
            // moves the brink a few blocks from the plan), hang the curtain in the first open column there, and
            // wake the lip's water so it pours over
            for (double s = -river.half + 0.5; s <= river.half - 0.5; s += 0.5) {
                double ox = at[0] - uz * s, oz = at[1] + ux * s;
                int lipX = Integer.MIN_VALUE, lipZ = 0;
                // (from a little further up than the fill leaves open above a fall, so a stream whose real land sank
                // below the plan just above the fall still finds its lip where the laid-back bed ends)
                for (int k = -EDGE_SEARCH - 3; k <= EDGE_SEARCH; k++) {
                    int x = (int) Math.floor(ox + ux * k), z = (int) Math.floor(oz + uz * k);
                    if (!p.readable(x, z)) continue;
                    // the bed as it will be once the channel is dug, whichever chunk is painted first: a hole under the
                    // bed away from the fall is laid back to the bed (as fill does), else the bed is dug no deeper
                    int floor = p.solidTop(x, z, top + 4, bottom - 4);
                    int surface = planned(river, x, z, tmp);
                    if (surface != Integer.MIN_VALUE) {
                        int water = surface == 63 ? 62 : surface;
                        boolean hole = floor < water - river.depth - 3;
                        boolean cave = floor < tmp[2] - river.depth - CAVE;
                        floor = hole && (tmp[1] == 0 || cave) ? tmp[0] : Math.min(floor, tmp[0]);
                    }
                    if (floor >= brink) {
                        lipX = x;
                        lipZ = z;
                        continue;
                    }
                    if (lipX == Integer.MIN_VALUE) continue;           // still below the brink, upstream
                    if (p.inside(x, z)) {
                        // down to the pool at its foot, wherever the water there really stands (a cave or pit
                        // under the fall can hold it well below the planned foot)
                        for (int y = top; y > bottom - 24; y--) {
                            BlockState b = p.get(x, y, z);
                            if (b.isAir()) p.set(x, y, z, FALLING);
                            else if (!b.getFluidState().isEmpty()) {
                                if (y < top) break;
                            } else break;
                        }
                        any = true;
                    }
                    if (p.inside(lipX, lipZ)) p.level.scheduleTick(new BlockPos(lipX, top, lipZ), Fluids.WATER, 2);
                    break;
                }
            }
        }
        return any;
    }
}
