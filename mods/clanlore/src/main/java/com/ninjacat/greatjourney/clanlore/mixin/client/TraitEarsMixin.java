package com.ninjacat.greatjourney.clanlore.mixin.client;

import com.ninjacat.greatjourney.clanlore.client.TraitCoat;
import net.snowteb.warriorcats_events.entity.client.WCModel;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.animation.AnimationState;

/** Torn and nicked ears are drawn short: set after WCE has posed the cat's bones for the frame. */
@Mixin(WCModel.class)
public abstract class TraitEarsMixin {
    @Inject(method = "setCustomAnimations(Lnet/snowteb/warriorcats_events/entity/custom/wcat/WCatEntity;JLsoftware/bernie/geckolib/animation/AnimationState;)V",
            at = @At("TAIL"))
    private void clanlore$ears(WCatEntity cat, long instanceId, AnimationState<WCatEntity> state, CallbackInfo ci) {
        TraitCoat.ears((WCModel) (Object) this, cat);
    }
}
