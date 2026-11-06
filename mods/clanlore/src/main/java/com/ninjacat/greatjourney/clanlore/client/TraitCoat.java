package com.ninjacat.greatjourney.clanlore.client;

import com.ninjacat.greatjourney.clanlore.traits.Trait;
import com.ninjacat.greatjourney.clanlore.traits.Traits;
import com.ninjacat.greatjourney.clanlore.traits.TraitsConfig;
import java.util.Set;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/** What the client mixins ask: which synced values draw a cat's coat, and how its ears and step look. */
public final class TraitCoat {
    private TraitCoat() {}

    /** Every synced value WCE builds a genetic coat's texture from (its layer list and cache key). */
    private static final Set<EntityDataAccessor<?>> COAT = Set.of(
            WCatEntity.BASE, WCatEntity.ORANGE_BASE, WCatEntity.WHITE_RATIO, WCatEntity.ALBINO, WCatEntity.DILUTE,
            WCatEntity.AGOUTI, WCatEntity.TABBY_STRIPES, WCatEntity.EYES_ANOMALY, WCatEntity.SILVER,
            WCatEntity.EYE_COLOR_LEFT, WCatEntity.EYE_COLOR_RIGHT, WCatEntity.RUFOUSING_VARIANT,
            WCatEntity.BLUE_RUFOUSING_VARIANT, WCatEntity.ORANGE_BASE_VARIANT, WCatEntity.WHITE_RATIO_VARIANT,
            WCatEntity.ALBINO_VARIANT, WCatEntity.TABBY_STRIPES_VARIANT, WCatEntity.EYE_COLOR_VARIANT_LEFT,
            WCatEntity.EYE_COLOR_VARIANT_RIGHT, WCatEntity.NOISE, WCatEntity.SILVER_VARIANT, WCatEntity.SCARS,
            WCatEntity.SKIN_COLOR, WCatEntity.CHIMERA_GENE, WCatEntity.CHIMERA_VARIANT, WCatEntity.BASE_CHIMERA,
            WCatEntity.ORANGE_BASE_CHIMERA, WCatEntity.WHITE_RATIO_CHIMERA, WCatEntity.ALBINO_CHIMERA,
            WCatEntity.DILUTE_CHIMERA, WCatEntity.AGOUTI_CHIMERA, WCatEntity.TABBY_STRIPES_CHIMERA,
            WCatEntity.SILVER_CHIMERA, WCatEntity.RUFOUSING_VARIANT_CHIMERA, WCatEntity.BLUE_RUFOUSING_VARIANT_CHIMERA,
            WCatEntity.ORANGE_BASE_VARIANT_CHIMERA, WCatEntity.WHITE_RATIO_VARIANT_CHIMERA,
            WCatEntity.ALBINO_VARIANT_CHIMERA, WCatEntity.TABBY_STRIPES_VARIANT_CHIMERA, WCatEntity.NOISE_CHIMERA,
            WCatEntity.SILVER_VARIANT_CHIMERA);

    public static boolean drawsCoat(EntityDataAccessor<?> key) {
        return COAT.contains(key);
    }

    /**
     * Shortens a torn or nicked ear. The ears are tiny cubes whose faces are one or two texture pixels, too few to cut
     * a notch in, so a torn ear is drawn at half height (with scar tissue along its top) and a nicked one a little
     * short. WCE's model is shared by every cat, so both ears are set for every cat, every frame.
     */
    public static void ears(GeoModel<WCatEntity> model, WCatEntity cat) {
        int mask = TraitsConfig.DRAW.get() ? Traits.shown(cat) : 0;
        ear(model, "leftear", Trait.TORN_EAR.in(mask) ? 0.5f : 1f);
        ear(model, "rightear", Trait.NICKED_EAR.in(mask) ? 0.8f : 1f);
    }

    private static void ear(GeoModel<WCatEntity> model, String name, float height) {
        GeoBone bone = model.getAnimationProcessor().getBone(name);
        if (bone != null) bone.setScaleY(height);
    }

    /** Whether a cat walks with WCE's limp: a broken paw, or a lame leg. */
    public static boolean limps(WCatEntity cat, boolean brokenPaw) {
        return brokenPaw || (TraitsConfig.LIMP.get() && Trait.LAME_LEG.in(Traits.shown(cat)));
    }
}
