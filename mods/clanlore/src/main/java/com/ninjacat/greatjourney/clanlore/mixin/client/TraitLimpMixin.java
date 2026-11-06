package com.ninjacat.greatjourney.clanlore.mixin.client;

import com.ninjacat.greatjourney.clanlore.client.TraitCoat;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A lame cat walks with WCE's broken-paw limp (Cinderpelt's walk). Only the walking choice in WCE's movement animation
 * picker is changed (the first {@code isBrokenPaw()} there); standing, sitting and running are left alone, so a lame cat
 * does not hold its paw up as if it were still broken.
 */
@Mixin(WCatEntity.class)
public abstract class TraitLimpMixin {
    @Redirect(method = "predicate", at = @At(value = "INVOKE",
            target = "Lnet/snowteb/warriorcats_events/entity/custom/wcat/WCatEntity;isBrokenPaw()Z", ordinal = 0))
    private boolean clanlore$limp(WCatEntity cat) {
        return TraitCoat.limps(cat, cat.isBrokenPaw());
    }
}
