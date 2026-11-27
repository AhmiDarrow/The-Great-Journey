package com.ninjacat.greatjourney.clanlands.mixin;

import com.ninjacat.greatjourney.clanlands.life.ClaimGround;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.snowteb.warriorcats_events.network.packet.c2s.clan.CtSClaimTerritory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears a plant underfoot before Warrior Cats Events looks at the ground of a claim ({@link ClaimGround}). */
@Mixin(value = CtSClaimTerritory.class, remap = false)
abstract class ClaimGroundMixin {
    @Inject(method = "lambda$handle$0", at = @At("HEAD"), require = 1)
    private static void clanlands$clearPlant(IPayloadContext context, CtSClaimTerritory packet, CallbackInfo ci) {
        if (context.player() instanceof ServerPlayer p) ClaimGround.clear(p);
    }
}
