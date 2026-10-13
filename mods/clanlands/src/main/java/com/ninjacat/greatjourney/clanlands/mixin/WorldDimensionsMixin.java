package com.ninjacat.greatjourney.clanlands.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Lifecycle;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Vanilla calls any world with a dimension beyond the overworld, Nether and End "experimental", and greets it with a
 * warning on creation and a backup prompt on every load. The StarClan dream ({@code clanlands:starclan}) is part of
 * The Great Journey, not an experiment: Clanlands' own dimensions count as stable. Other mods' dimensions still warn.
 */
@Mixin(WorldDimensions.class)
abstract class WorldDimensionsMixin {
    @ModifyReturnValue(method = "checkStability", at = @At("RETURN"))
    private static Lifecycle clanlands$stableDream(Lifecycle original, ResourceKey<LevelStem> key, LevelStem stem) {
        return "clanlands".equals(key.location().getNamespace()) ? Lifecycle.stable() : original;
    }

    @ModifyVariable(method = "bake", at = @At("STORE"), ordinal = 0)
    private Lifecycle clanlands$stableList(Lifecycle lifecycle, @Local(argsOnly = true) Registry<LevelStem> stems) {
        if (lifecycle == Lifecycle.stable()) return lifecycle;
        Set<ResourceKey<LevelStem>> keys = new HashSet<>(stems.registryKeySet());
        keys.addAll(((WorldDimensions) (Object) this).dimensions().keySet());
        for (ResourceKey<LevelStem> k : keys) {
            boolean vanilla = k == LevelStem.OVERWORLD || k == LevelStem.NETHER || k == LevelStem.END;
            if (!vanilla && !"clanlands".equals(k.location().getNamespace())) return lifecycle;
        }
        return Lifecycle.stable();
    }
}
