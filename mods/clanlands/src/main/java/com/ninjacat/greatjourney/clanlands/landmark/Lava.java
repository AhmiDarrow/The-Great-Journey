package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.layout.Tunnels;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Puts out the lava of the Clan lands: surface lava pools, lava springs pouring down a ravine or gorge wall, and the
 * lava of caves the Clans' own tunnels pass through. The books' forest and lake have none, and a kit can wander into
 * any of it. Lava deep in ordinary caves, below the reach of the tunnels, is left alone.
 */
final class Lava {
    private Lava() {}

    /** Lava from here up, anywhere in the Clan lands, turns to stone. */
    private static final int SURFACE_FROM = 50;
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    static boolean seal(ClanWorld w, ChunkPainter p) {
        int from = Integer.MAX_VALUE;
        if (w.sample(p.minX + 8, p.minZ + 8).weight() > 0 || w.sample(p.minX, p.minZ).weight() > 0
                || w.sample(p.minX + 15, p.minZ + 15).weight() > 0) {
            from = SURFACE_FROM;
        }
        Tunnels t = w.lake.tunnels();
        if (t != null) {
            double cx = Math.max(p.minX, Math.min(t.cx, p.minX + 15)), cz = Math.max(p.minZ, Math.min(t.cz, p.minZ + 15));
            if (Math.hypot(cx - t.cx, cz - t.cz) <= t.reach()) from = Math.min(from, (int) Math.floor(t.cy - t.cavernRy - 6));
        }
        if (from == Integer.MAX_VALUE) return false;
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = p.minX + dx, z = p.minZ + dz;
                int top = p.level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
                for (int y = Math.max(from, p.level.getMinBuildHeight() + 1); y <= top; y++) {
                    if (p.get(x, y, z).getFluidState().is(FluidTags.LAVA)) {
                        p.set(x, y, z, STONE);
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
