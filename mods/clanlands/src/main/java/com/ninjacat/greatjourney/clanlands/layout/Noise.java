package com.ninjacat.greatjourney.clanlands.layout;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/** Seeded fractal simplex noise in roughly [-1, 1]. Each use gets its own salt so the fields are independent. */
public final class Noise {
    private final SimplexNoise[] octaves;
    private final double scale;

    public Noise(long seed, String salt, double wavelength, int octaveCount) {
        this.octaves = new SimplexNoise[octaveCount];
        for (int i = 0; i < octaveCount; i++) {
            octaves[i] = new SimplexNoise(new XoroshiroRandomSource(seed ^ (salt.hashCode() * 0x9E3779B97F4A7C15L) ^ i * 0x632BE59BD9B4E019L));
        }
        this.scale = 1.0 / wavelength;
    }

    public double at(double x, double z) {
        double sum = 0, amp = 1, norm = 0, f = scale;
        for (SimplexNoise o : octaves) {
            sum += o.getValue(x * f, z * f) * amp;
            norm += amp;
            amp *= 0.5;
            f *= 2;
        }
        return sum / norm;
    }

    /** One-dimensional variant (along a coordinate such as an angle or a path length). */
    public double at(double t) {
        return at(t, 0.37);
    }
}
