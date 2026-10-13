package com.ninjacat.greatjourney.clanlands.mixin;

import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No vanilla canyons in the Clan lands: the books' ravines and gorges are laid out by the maps, and a random canyon
 * cracked straight through a lake stream (its water pouring into the crack) and through a WindClan camp's hollow.
 * Caves stay. An overworld chunk is recognised by its shape, as in {@link ChunkAccessMixin}.
 */
@Mixin(CanyonWorldCarver.class)
abstract class CanyonWorldCarverMixin {
    @Inject(method = "carve(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;Lnet/minecraft/world/level/levelgen/carver/CanyonCarverConfiguration;Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/function/Function;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/Aquifer;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/CarvingMask;)Z",
            at = @At("HEAD"), cancellable = true)
    private void clanlands$noCanyons(CarvingContext context, CanyonCarverConfiguration config, ChunkAccess chunk,
            Function<BlockPos, Holder<Biome>> biomes, RandomSource random, Aquifer aquifer, ChunkPos start, CarvingMask mask,
            CallbackInfoReturnable<Boolean> cir) {
        ClanWorld w = ClanWorld.current();
        if (w == null || ClanWorld.overworldSource() == null || chunk.getMinBuildHeight() != -64 || chunk.getHeight() != 384) return;
        ChunkPos at = chunk.getPos();
        if (w.sample(at.getMiddleBlockX(), at.getMiddleBlockZ()).weight() > 0.2
                || w.sample(start.getMiddleBlockX(), start.getMiddleBlockZ()).weight() > 0.2) {
            cir.setReturnValue(false);
        }
    }
}
