package com.ninjacat.greatjourney.clanlands.world;

import com.mojang.serialization.MapCodec;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code clanlands:map}: one channel of the Clan lands at the current column. The worldgen patch
 * blends vanilla's terrain shaping toward these values ({@link com.ninjacat.greatjourney.clanlands.world.WorldgenPatcher}).
 */
public record MapDensity(Channel channel) implements DensityFunction.SimpleFunction {
    public enum Channel implements StringRepresentable {
        /** 1 inside a map, fading to 0 at its edge. */
        WEIGHT,
        /** The map's ground height, as a vanilla terrain offset. */
        OFFSET,
        /** The map's terrain smoothness (vanilla factor). */
        FACTOR;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final MapCodec<MapDensity> CODEC = StringRepresentable.fromEnum(Channel::values)
            .fieldOf("channel").xmap(MapDensity::new, MapDensity::channel);
    public static final KeyDispatchDataCodec<MapDensity> KEY_CODEC = KeyDispatchDataCodec.of(CODEC);

    /** Vanilla's surface sits where depth = y_clamped_gradient(-64..320, 1.5..-1.5) + offset crosses zero. */
    public static double offsetFor(double height) {
        return (height + 64.0) / 128.0 - 1.5;
    }

    @Override
    public double compute(FunctionContext ctx) {
        ClanWorld w = ClanWorld.current();
        if (w == null) {
            return channel == Channel.FACTOR ? 5.0 : 0.0;
        }
        Sample s = w.sample(ctx.blockX(), ctx.blockZ());
        return switch (channel) {
            case WEIGHT -> s.weight();
            case OFFSET -> s.weight() > 0 ? offsetFor(s.height()) : 0.0;
            case FACTOR -> s.weight() > 0 ? s.factor() : 5.0;
        };
    }

    @Override
    public double minValue() {
        return channel == Channel.OFFSET ? -2.0 : 0.0;
    }

    @Override
    public double maxValue() {
        return channel == Channel.WEIGHT ? 1.0 : channel == Channel.OFFSET ? 2.0 : 12.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KEY_CODEC;
    }
}
