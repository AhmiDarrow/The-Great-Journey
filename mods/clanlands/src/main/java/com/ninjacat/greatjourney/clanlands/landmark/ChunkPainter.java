package com.ninjacat.greatjourney.clanlands.landmark;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Writes blocks for one chunk only. Landmark builders describe whole shapes in world coordinates; the painter
 * drops everything outside its chunk, so the same shape drawn by each chunk in turn joins up seamlessly.
 */
public final class ChunkPainter {
    public final WorldGenLevel level;
    public final int minX, minZ;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    ChunkPainter(WorldGenLevel level, int minX, int minZ) {
        this.level = level;
        this.minX = minX;
        this.minZ = minZ;
    }

    public boolean inside(int x, int z) {
        return x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16;
    }

    public void set(int x, int y, int z, BlockState state) {
        if (inside(x, z) && y > level.getMinBuildHeight() && y < level.getMaxBuildHeight()) {
            level.setBlock(pos.set(x, y, z), state, 2);
        }
    }

    public BlockState get(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
    }

    /** Sets only where the block is air or replaceable plants (for leaves, so they never cut into terrain). */
    public void soft(int x, int y, int z, BlockState state) {
        if (!inside(x, z)) return;
        BlockState old = get(x, y, z);
        if (old.isAir() || old.canBeReplaced() || old.is(BlockTags.LEAVES)) set(x, y, z, state);
    }

    /** Top solid ground at a column, looking through trees, plants and snow. Valid only for columns in the chunk. */
    public int ground(int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        // the worldgen heightmap is not kept up as the feature step sets blocks: where the ground was built up after it
        // was taken (the RiverClan island's fill over a dry channel bed), climb past its stale top first, or the hedge
        // was planted inside the fill and never showed (seed 777001's forest RiverClan camp)
        while (y + 1 < level.getMaxBuildHeight()) {
            BlockState up = get(x, y + 1, z);
            if ((up.isAir() || up.is(BlockTags.LEAVES) || up.is(BlockTags.LOGS) || up.canBeReplaced() || up.is(Blocks.SNOW))
                    && up.getFluidState().isEmpty()) break;
            y++;
        }
        while (y > level.getMinBuildHeight()) {
            BlockState s = get(x, y, z);
            if (!(s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.canBeReplaced() || s.is(Blocks.SNOW))
                    || !s.getFluidState().isEmpty()) {
                return y;
            }
            y--;
        }
        return y;
    }

    /** Whether a column can be read from this chunk's feature step (this chunk and the ring around it). */
    public boolean readable(int x, int z) {
        return x >= minX - 16 && x < minX + 32 && z >= minZ - 16 && z < minZ + 32;
    }

    /**
     * The top solid block (not air, water, plants or trees) at a column between {@code from} and {@code to} (scanning
     * down), or {@code to - 1} when there is none. Works for neighbouring columns too, unlike {@link #ground}.
     */
    public int solidTop(int x, int z, int from, int to) {
        for (int y = from; y >= to; y--) {
            BlockState s = get(x, y, z);
            if (!(s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.canBeReplaced() || s.is(Blocks.SNOW))) return y;
        }
        return to - 1;
    }

    /** Like {@link #solidTop}, but water counts too: the level a pool beside this column would have to match. */
    public int wetTop(int x, int z, int from, int to) {
        for (int y = from; y >= to; y--) {
            BlockState s = get(x, y, z);
            if (!s.getFluidState().isEmpty()) return y;
            if (!(s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.canBeReplaced() || s.is(Blocks.SNOW))) return y;
        }
        return to - 1;
    }

    /** Logs, leaves, plants, vines and snow: what grows on the land, as against the land itself. */
    public static boolean growth(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
                || s.is(Blocks.SNOW) || s.is(Blocks.VINE) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)
                || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BEE_NEST) || s.is(Blocks.SWEET_BERRY_BUSH)
                || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    /**
     * Clears every tree, bush and plant standing on a column, up to {@code height} blocks above y, leaving earth,
     * rock and water alone: a clearing, rather than a cut through the hillside.
     */
    public void clearGrowth(int x, int y, int z, int height) {
        for (int i = 1; i <= height; i++) {
            BlockState s = get(x, y + i, z);
            if (!s.isAir() && growth(s)) set(x, y + i, z, Blocks.AIR.defaultBlockState());
        }
    }

    /** Clears plants, trees and snow above y at a column (up to {@code height} blocks). */
    public void clearAbove(int x, int y, int z, int height) {
        for (int i = 1; i <= height; i++) {
            BlockState s = get(x, y + i, z);
            if (!s.isAir() && s.getFluidState().isEmpty()) set(x, y + i, z, Blocks.AIR.defaultBlockState());
        }
    }
}
