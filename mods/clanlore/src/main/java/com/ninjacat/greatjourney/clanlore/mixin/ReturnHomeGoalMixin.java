package com.ninjacat.greatjourney.clanlore.mixin;

import net.minecraft.core.BlockPos;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Warrior Cats Events' walk home ends by clearing the cat's {@code returnHomeFlag}, but the walk only starts for a
 * cat whose nest is set and within 400 blocks. A cat told to go home (every patrol ends so) with no nest, or with
 * its nest further off, keeps the flag for good: it never patrols again (both patrol goals refuse a cat going home),
 * its wandering is off, and Clanlands' camp routine leaves it alone, so it stands about wherever it finished. Such a
 * hopeless trip is dropped instead, and the cat goes back to wandering where it is.
 */
@Mixin(targets = "net.snowteb.warriorcats_events.entity.custom.wcat.WCGoals$WCatReturnHomeGoal", remap = false)
abstract class ReturnHomeGoalMixin {
    @Shadow
    @Final
    private WCatEntity cat;

    @Inject(method = "canUse()Z", at = @At("RETURN"), require = 0)
    private void clanlore$dropHopelessTrip(CallbackInfoReturnable<Boolean> cir) {
        WCatEntity c = this.cat;
        if (cir.getReturnValueZ() || c == null || !c.returnHomeFlag) return;
        BlockPos home = c.getHomePosition();
        if (home == null || home.equals(BlockPos.ZERO) || c.distanceToSqr(home.getX(), home.getY(), home.getZ()) > 400.0 * 400.0) {
            c.returnHomeFlag = false;
            c.returningFromPatrol = false;
        }
    }
}
