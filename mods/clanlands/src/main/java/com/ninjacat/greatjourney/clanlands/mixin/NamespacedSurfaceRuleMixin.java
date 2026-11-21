package com.ninjacat.greatjourney.clanlands.mixin;

import com.ninjacat.greatjourney.clanlands.world.NamespacedSurface;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps TerraBlender's overworld surface rules to one layer. On every server start it wraps the overworld settings'
 * surface rule as the {@code base} of a new namespaced rule; BF Biomes stores those settings inline in level.dat, so
 * each open nested the previous wrap inside the new one (about 170 KB more level.dat per open, and every layer ran
 * all the rules again for each generated column). The sources are rebuilt from TerraBlender's registrations each
 * time, so an inner namespaced rule is a stale copy: a new one takes that rule's own base instead.
 *
 * <p>TerraBlender is optional: the target is named by string under {@code @Pseudo}, so without it this is skipped.
 */
@Pseudo
@Mixin(targets = "terrablender.worldgen.surface.NamespacedSurfaceRuleSource", remap = false)
abstract class NamespacedSurfaceRuleMixin implements NamespacedSurface {
    @Shadow(remap = false) @Final private SurfaceRules.RuleSource base;

    @Override
    public SurfaceRules.RuleSource clanlands$base() {
        return base;
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false)
    private static SurfaceRules.RuleSource clanlands$oneLayer(SurfaceRules.RuleSource base) {
        while (base instanceof NamespacedSurface inner) base = inner.clanlands$base();
        return base;
    }
}
