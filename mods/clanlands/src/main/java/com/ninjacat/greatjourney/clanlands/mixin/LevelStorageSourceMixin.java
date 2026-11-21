package com.ninjacat.greatjourney.clanlands.mixin;

import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets a world whose level.dat grew past Minecraft's 100 MB read limit open again. Before {@link NoiseRouterMixin},
 * Dynamic Waters and BF Biomes tripled the saved overworld settings on every open, and a world that crossed the limit
 * showed "Tried to read NBT tag that was too big" and could not be played. Reading it whole lets the router collapse
 * as it decodes, and the next save writes a level.dat of normal size. Only the world's own level.dat is read here;
 * network and other NBT keep their limits.
 */
@Mixin(LevelStorageSource.class)
abstract class LevelStorageSourceMixin {
    @ModifyArg(method = {"readLevelDataTagRaw", "readLightweightData"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtAccounter;create(J)Lnet/minecraft/nbt/NbtAccounter;"))
    private static long clanlands$readWholeLevelDat(long quota) {
        return Long.MAX_VALUE;
    }
}
