package com.ninjacat.greatjourney.clanlands.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Fills a new overworld chunk's biomes from the Clan lands where they cover it. In the full pack neither the resolver
 * nor the sampler handed to this method is the overworld's own instance (TerraBlender clones the biome source, and a
 * fresh sampler arrives per chunk), so an overworld chunk is recognised by its shape instead: a multi-noise biome
 * source on a chunk from y -64, 384 tall. The Nether is shorter and the End and the dream use other sources.
 */
@Mixin(ChunkAccess.class)
abstract class ChunkAccessMixin {
    @WrapMethod(method = "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V")
    private void clanlands$territories(BiomeResolver resolver, Climate.Sampler sampler, Operation<Void> original) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        if (ClanWorld.overworldSource() != null && resolver instanceof MultiNoiseBiomeSource
                && self.getMinBuildHeight() == -64 && self.getHeight() == 384) {
            BiomeResolver inner = resolver;
            resolver = (x, y, z, s) -> {
                Holder<Biome> h = ClanWorld.biomeAt(x, y, z);
                return h != null ? h : ClanWorld.biomeBelow(x, y, z, inner.getNoiseBiome(x, y, z, s));
            };
        }
        original.call(resolver, sampler);
    }
}
