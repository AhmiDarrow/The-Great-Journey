package com.ninjacat.greatjourney.clanlands.mixin;

import com.ninjacat.greatjourney.clanlands.world.RidgesWrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** {@code DensityFunctions.RangeChoice} is package-private; this exposes what {@link NoiseRouterMixin} checks. */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice")
abstract class RangeChoiceMixin implements RidgesWrap {
    @Shadow @Final private DensityFunction input;
    @Shadow @Final private double minInclusive;
    @Shadow @Final private double maxExclusive;

    @Override
    public DensityFunction clanlands$input() {
        return input;
    }

    @Override
    public boolean clanlands$isWrap() {
        return minInclusive == -0.15 && maxExclusive == 0.15;
    }
}
