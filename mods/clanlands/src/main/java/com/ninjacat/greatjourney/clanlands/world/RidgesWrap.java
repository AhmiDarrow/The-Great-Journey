package com.ninjacat.greatjourney.clanlands.world;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Added to every {@code range_choice}: reads it as a possible Dynamic Waters ridges wrap (Clanlands NoiseRouterMixin). */
public interface RidgesWrap {
    DensityFunction clanlands$input();

    /** Whether this is Dynamic Waters' ridges wrap: a range choice over exactly [-0.15, 0.15). */
    boolean clanlands$isWrap();
}
