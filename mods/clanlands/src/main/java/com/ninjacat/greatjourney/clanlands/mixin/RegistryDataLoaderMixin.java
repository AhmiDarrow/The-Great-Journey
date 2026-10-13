package com.ninjacat.greatjourney.clanlands.mixin;

import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.ninjacat.greatjourney.clanlands.world.WorldgenPatcher;
import java.io.Reader;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hands each registry entry's JSON to the Clanlands worldgen patcher between parsing and decoding, and marks
 * Clanlands' own entries (the StarClan dream's dimension and its type) as stable: entries a mod's data adds count as
 * experimental, and that would greet every new world with vanilla's "experimental settings" warning.
 */
@Mixin(RegistryDataLoader.class)
abstract class RegistryDataLoaderMixin {
    @WrapOperation(method = "loadElementFromResource",
            at = @At(value = "INVOKE", target = "Lcom/google/gson/JsonParser;parseReader(Ljava/io/Reader;)Lcom/google/gson/JsonElement;"))
    private static JsonElement clanlands$patch(Reader reader, Operation<JsonElement> original,
                                              @Local(argsOnly = true) ResourceKey<?> key) {
        return WorldgenPatcher.patch(key, original.call(reader));
    }

    @WrapOperation(method = "loadContentsFromManager",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/RegistryDataLoader;loadElementFromResource(Lnet/minecraft/core/WritableRegistry;Lcom/mojang/serialization/Decoder;Lnet/minecraft/resources/RegistryOps;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/server/packs/resources/Resource;Lnet/minecraft/core/RegistrationInfo;)V"))
    private static void clanlands$stable(WritableRegistry<?> registry, Decoder<?> decoder, RegistryOps<JsonElement> ops,
                                         ResourceKey<?> key, Resource resource, RegistrationInfo info, Operation<Void> original) {
        if ("clanlands".equals(key.location().getNamespace()) && info.lifecycle() != Lifecycle.stable()) {
            info = new RegistrationInfo(info.knownPackInfo(), Lifecycle.stable());
        }
        original.call(registry, decoder, ops, key, resource, info);
    }
}
