package com.ninjacat.greatjourney.qa.mixin;

import com.ninjacat.greatjourney.qa.Qa;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEST ONLY. A background playtest client never captures the owner's mouse cursor. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void greatjourney_qa$neverGrab(CallbackInfo ci) {
        if (Qa.driving() && Qa.BACKGROUND) ci.cancel();
    }
}
