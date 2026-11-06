package com.ninjacat.greatjourney.clanlore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hunting patrol has the border patrol's bug ({@link BorderPatrolGoalMixin}): its "not reachable" counter is
 * never reset, so after two and a half minutes of hunting patrols in all, every hunting patrol a cat is sent on
 * ends at once. It is reset whenever the goal starts, which is once per leg.
 *
 * <p>Hunters wait 2400 ticks (two minutes) at each waypoint, wandering and catching prey there. That is the hunt
 * itself, so it stays longer than the border patrol's wait, but two minutes at each of four points kept a Clan
 * event's hunters out for well over ten minutes: it is cut to 40 seconds.
 */
@Mixin(targets = "net.snowteb.warriorcats_events.entity.custom.wcat.WCGoals$WCatHuntingPatrolGoal", remap = false)
abstract class HuntingPatrolGoalMixin {
    @Shadow
    private int territoryNotReachable;

    @Inject(method = "start()V", at = @At("HEAD"), require = 0)
    private void clanlore$freshLeg(CallbackInfo ci) {
        this.territoryNotReachable = 0;
    }

    @ModifyConstant(method = "tick()V", constant = @Constant(intValue = 2400), require = 0)
    private int clanlore$shorterWait(int ticks) {
        return 20 * 40;
    }
}
