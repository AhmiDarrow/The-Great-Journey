package com.ninjacat.greatjourney.clanlore.mixin.client;

import com.ninjacat.greatjourney.clanlore.client.TraitCoat;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes WCE's stale coats: the client works a genetic cat's texture key out once ({@code textureKey}) and never again,
 * so a coat changed on a cat already in view (a dye, a book look, a ceremony) only showed after a relog. When one of
 * the synced values the coat is built from changes, the key is dropped and WCE works it out afresh on the next frame.
 * WCE's own {@code GeneticsModule.invalidate()} does this too but also evicts the shared texture, which every cat with
 * the same coat is drawn with; clearing the key alone is enough, since a texture only ever matches its key.
 */
@Mixin(WCatEntity.class)
public abstract class TraitCoatRedrawMixin {
    @Inject(method = "onSyncedDataUpdated", at = @At("HEAD"))
    private void clanlore$redrawCoat(EntityDataAccessor<?> key, CallbackInfo ci) {
        WCatEntity self = (WCatEntity) (Object) this;
        if (self.textureKey != null && self.level() != null && self.level().isClientSide() && TraitCoat.drawsCoat(key)) {
            self.textureKey = null;
        }
    }
}
