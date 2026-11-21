package com.ninjacat.greatjourney.clanlands.mixin;

import com.ninjacat.greatjourney.clanlands.world.RidgesWrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps Dynamic Waters' river flattening of the overworld ridges to one layer. Its {@code noiseRouter()} hook wraps
 * whatever ridges the settings hold in a ±0.15 {@code range_choice}, on every call and with no check for its own
 * wrap. BF Biomes rebuilds the overworld settings on each server start from that wrapped router and stores them
 * inline in level.dat, so every load nested one more wrap, each holding the old ridges three times: after eleven
 * loads level.dat passed Minecraft's 100 MB read limit and the world would not open.
 *
 * <p>Every router is built here (Dynamic Waters' own, BF Biomes' copy, one decoded from level.dat), so a wrap whose
 * input is already a wrap is replaced by that inner wrap. A first wrap is left alone, and an over-nested router
 * saved before this fix collapses as it loads.
 */
@Mixin(NoiseRouter.class)
abstract class NoiseRouterMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 9)
    private static DensityFunction clanlands$oneRidgesWrap(DensityFunction ridges) {
        while (clanlands$inline(ridges) instanceof RidgesWrap outer && outer.clanlands$isWrap()
                && clanlands$inline(outer.clanlands$input()) instanceof RidgesWrap inner && inner.clanlands$isWrap()) {
            ridges = outer.clanlands$input();
        }
        return ridges;
    }

    /** A function read back from level.dat comes inside a key-less holder; looks through it, never through a named one. */
    @Unique
    private static DensityFunction clanlands$inline(DensityFunction f) {
        while (f instanceof DensityFunctions.HolderHolder h && h.function().unwrapKey().isEmpty()) {
            f = h.function().value();
        }
        return f;
    }
}
