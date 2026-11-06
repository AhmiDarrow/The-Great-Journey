package com.ninjacat.greatjourney.clanlore.traits;

import net.minecraft.resources.ResourceLocation;

/**
 * The marks a cat carries for life in the books, which Warrior Cats Events cannot show: Tigerstar's and Scourge's scars,
 * Longtail's ragged ears and Cloudtail's nicked one, an elder's grey muzzle, Brightheart's lost eye, Crookedstar's
 * jaw, and Cinderpelt's leg. Each keeps a fixed bit, since the set is saved as a bit mask: never renumber one.
 */
public enum Trait {
    SCAR_FACE(0, "Scarred face", "scar_face"),
    SCAR_FLANK(1, "Scarred flank", "scar_flank"),
    TORN_EAR(2, "Torn ear", "torn_ear"),
    NICKED_EAR(3, "Nicked ear", "nicked_ear"),
    GRIZZLED_MUZZLE(4, "Grizzled muzzle", "grizzled_muzzle"),
    MISSING_EYE(5, "Missing eye", "missing_eye"),
    CROOKED_JAW(6, "Crooked jaw", "crooked_jaw"),
    /** Shown by a limp and a slower step, not drawn on the coat. */
    LAME_LEG(7, "Lame leg", null);

    private final int bit;
    private final String label;
    private final ResourceLocation texture;

    Trait(int bit, String label, String texture) {
        this.bit = 1 << bit;
        this.label = label;
        this.texture = texture == null ? null
                : ResourceLocation.fromNamespaceAndPath("clanlore", "textures/entity/traits/" + texture + ".png");
    }

    public int bit() {
        return bit;
    }

    /** How the trait reads in a sentence or a tooltip. */
    public String label() {
        return label;
    }

    /** The overlay drawn over the cat's coat, or null when the trait is not drawn. */
    public ResourceLocation texture() {
        return texture;
    }

    public boolean in(int mask) {
        return (mask & bit) != 0;
    }
}
