package com.ninjacat.greatjourney.clanlore.traits;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What Clan Lore keeps on a cat: its traits as a bit mask, and the game time it was first seen grown, since Warrior
 * Cats Events stops counting a cat's moons at 12 (its moons are worked out from the vanilla baby age, which stops at
 * adulthood). The client copy only ever holds the mask.
 */
public record TraitRecord(int mask, long adultSince) {
    public static final TraitRecord EMPTY = new TraitRecord(0, -1L);

    public static final Codec<TraitRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("mask", 0).forGetter(TraitRecord::mask),
            Codec.LONG.optionalFieldOf("adult_since", -1L).forGetter(TraitRecord::adultSince)
    ).apply(i, TraitRecord::new));

    public TraitRecord with(Trait t) {
        return new TraitRecord(mask | t.bit(), adultSince);
    }

    public TraitRecord without(Trait t) {
        return new TraitRecord(mask & ~t.bit(), adultSince);
    }

    public TraitRecord grownAt(long time) {
        return new TraitRecord(mask, time);
    }

    /** Whether there is anything worth saving. */
    public boolean worthKeeping() {
        return mask != 0 || adultSince >= 0;
    }
}
