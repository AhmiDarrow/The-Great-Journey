package com.ninjacat.greatjourney.clanlore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two fixes to Warrior Cats Events' border patrol.
 *
 * <p>Its "not reachable" counter ({@code territoryNotReachable}) is meant to give up on a waypoint the cat can't get
 * to: it counts every tick spent walking, and at 3000 the patrol is ended. But it is a field of the goal, which each
 * cat keeps for life, and nothing ever sets it back to 0, so it counts every patrol's walking added together. Once a
 * cat has walked 3000 ticks of patrols in all (two and a half minutes) every patrol it is sent on ends the moment it
 * starts, until the cat reloads. The goal starts afresh for each leg (it stops while the cat waits at a waypoint, and
 * starts again for the next), so the counter is reset there: it now measures one leg, as was meant.
 *
 * <p>At each waypoint the cat waits 1200 ticks, a whole minute, before the next leg; a patrol of four points stood
 * about for four minutes. The wait is cut to 15 seconds: long enough to sniff the border and re-mark it.
 */
@Mixin(targets = "net.snowteb.warriorcats_events.entity.custom.wcat.WCGoals$WCatBorderPatrolGoal", remap = false)
abstract class BorderPatrolGoalMixin {
    @Shadow
    private int territoryNotReachable;

    @Inject(method = "start()V", at = @At("HEAD"), require = 0)
    private void clanlore$freshLeg(CallbackInfo ci) {
        this.territoryNotReachable = 0;
    }

    @ModifyConstant(method = "tick()V", constant = @Constant(intValue = 1200), require = 0)
    private int clanlore$shorterWait(int ticks) {
        return 20 * 15;
    }
}
