package com.ninjacat.greatjourney.clanlands.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Inside the Clan lands the overworld's biome comes from the map: each territory's own biome above ground, the
 * normal caves below. Everything else (and every other dimension) is left to the normal biome source.
 *
 * <p>This wraps the whole method: TerraBlender answers this call at its head and returns early, so anything injected
 * inside the method never runs, but a wrapper decides before any of it.
 */
@Mixin(value = MultiNoiseBiomeSource.class, priority = 1500)
abstract class MultiNoiseBiomeSourceMixin {

    @WrapMethod(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")
    private Holder<Biome> clanlands$territory(int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        if (ClanWorld.overworldSource() != null && ((Object) this == ClanWorld.overworldSource() || clanlands$overworldLike())) {
            Holder<Biome> h = ClanWorld.biomeAt(x, y, z);
            if (h != null) return h;
            return ClanWorld.biomeBelow(x, y, z, original.call(x, y, z, sampler));
        }
        return original.call(x, y, z, sampler);
    }

    @org.spongepowered.asm.mixin.Unique
    private Boolean clanlands$overworld;

    /** TerraBlender's clones of the overworld source are overworld too: they can produce plains (the Nether can't). */
    @org.spongepowered.asm.mixin.Unique
    private boolean clanlands$overworldLike() {
        if (clanlands$overworld == null) {
            clanlands$overworld = ((MultiNoiseBiomeSource) (Object) this).possibleBiomes().stream()
                    .anyMatch(h -> h.is(net.minecraft.world.level.biome.Biomes.PLAINS));
        }
        return clanlands$overworld;
    }

    @ModifyReturnValue(method = "collectPossibleBiomes", at = @At("RETURN"))
    private Stream<Holder<Biome>> clanlands$possible(Stream<Holder<Biome>> original) {
        ClanWorld w = ClanWorld.current();
        if (w == null || ClanWorld.overworldSource() == null) return original;
        java.util.List<Holder<Biome>> own = original.toList();
        // only overworld sources (they can produce plains) gain the Clan lands' biomes; the Nether keeps its own
        if (own.stream().noneMatch(h -> h.is(net.minecraft.world.level.biome.Biomes.PLAINS))) return own.stream();
        return Stream.concat(own.stream(), w.allBiomes().stream()).distinct();
    }
}
