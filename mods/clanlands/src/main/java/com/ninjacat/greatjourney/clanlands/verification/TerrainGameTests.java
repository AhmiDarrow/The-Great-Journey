package com.ninjacat.greatjourney.clanlands.verification;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import com.ninjacat.greatjourney.clanlands.world.MapDensity;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** The overworld's terrain noise as a seed's Clan lands shape it, evaluated straight from the patched router. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class TerrainGameTests {
    private TerrainGameTests() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** {@code clanlands:map} bound to one laid-out world instead of the running one. */
    private record Bound(ClanWorld w, MapDensity.Channel channel) implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext ctx) {
            Sample s = w.sample(ctx.blockX(), ctx.blockZ());
            return switch (channel) {
                case WEIGHT -> s.weight();
                case OFFSET -> s.weight() > 0 ? MapDensity.offsetFor(s.height()) : 0.0;
                case FACTOR -> s.weight() > 0 ? s.factor() : 5.0;
            };
        }

        @Override
        public double minValue() {
            return -2;
        }

        @Override
        public double maxValue() {
            return 12;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return MapDensity.KEY_CODEC;
        }
    }

    /** The overworld router for a seed: final density, plus named overworld functions in spare slots. */
    static NoiseRouter router(RegistryAccess ra, long seed, ClanWorld w, String... extras) {
        NoiseGeneratorSettings s = ra.registryOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var dfs = ra.registryOrThrow(Registries.DENSITY_FUNCTION);
        DensityFunction[] x = new DensityFunction[3];
        for (int i = 0; i < 3; i++) {
            x[i] = i < extras.length ? dfs.get(ResourceLocation.withDefaultNamespace(extras[i])) : s.noiseRouter().veinGap();
        }
        NoiseRouter r = s.noiseRouter();
        NoiseRouter diag = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(),
                r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                r.initialDensityWithoutJaggedness(), r.finalDensity(), x[0], x[1], x[2]);
        NoiseGeneratorSettings s2 = new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), diag,
                s.surfaceRule(), s.spawnTarget(), s.seaLevel(), s.disableMobGeneration(), s.aquifersEnabled(), s.oreVeinsEnabled(),
                s.useLegacyRandomSource());
        RandomState rs = RandomState.create(s2, ra.lookupOrThrow(Registries.NOISE), seed);
        return rs.router().mapAll(f -> f instanceof MapDensity m ? new Bound(w, m.channel()) : f);
    }

    /**
     * Vanilla's cave entrances cut a dry stone bowl 30 blocks deep into the WindClan moor beside the tunnel mouth on seed
     * -84596914 (x 1305-1335, z 60-95; the plan's ground about y 91, the floor y 57-62). Wherever the terrain itself is
     * solid in the ground's first blocks inside a map, no noise cave opens it; deeper down the caves stay, the bowl's
     * own entrance among them.
     */
    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void noiseCavesStayUnderThePlannedGround(GameTestHelper h) {
        RegistryAccess ra = h.getLevel().registryAccess();
        long[] seeds = {-84596914L, 4242L, 777001L};
        int deepCaves = 0;
        for (long seed : seeds) {
            ClanWorld w = ClanWorld.layoutOnly(seed);
            NoiseRouter r = router(ra, seed, w, "overworld/sloped_cheese");
            DensityFunction fd = r.finalDensity(), sc = r.veinToggle();
            if (seed == -84596914L) {
                // the bowl itself: solid from the plan down, the cave from about 28 blocks under it as before
                for (int[] c : new int[][] {{1316, 80}, {1308, 76}, {1324, 84}, {1312, 88}}) {
                    Sample s = w.sample(c[0], c[1]);
                    for (int y = (int) Math.floor(s.height() - 2); y >= s.height() - 10; y--) {
                        double d = fd.compute(new DensityFunction.SinglePointContext(c[0], y, c[1]));
                        h.assertTrue(d > 0, "the moor at " + c[0] + " " + y + " " + c[1] + " is solid ground "
                                + String.format("%.0f", s.height() - y) + " blocks under the plan (density " + String.format("%.3f", d) + ")");
                    }
                }
                double deep = fd.compute(new DensityFunction.SinglePointContext(1316, 62, 80));
                h.assertTrue(deep < 0, "the cave entrance under the moor at 1316 62 80 is still a cave deep down (density "
                        + String.format("%.3f", deep) + ")");
            }
            // the whole lake lands: wherever the terrain is solid within 8 blocks of the plan, so is the ground
            int opened = 0, checked = 0;
            String first = null;
            for (int x = (int) w.lake.centreX() - 1400; x <= w.lake.centreX() + 1400; x += 48) {
                for (int z = (int) w.lake.centreZ() - 1400; z <= w.lake.centreZ() + 1400; z += 48) {
                    Sample s = w.sample(x, z);
                    if (s.weight() < 0.999 || s.zone() == null) continue;
                    for (int y = (int) Math.floor(s.height() + 6); y >= s.height() - 8; y--) {
                        var ctx = new DensityFunction.SinglePointContext(x, y, z);
                        if (sc.compute(ctx) <= 0.05) continue;
                        checked++;
                        if (fd.compute(ctx) <= 0) {
                            opened++;
                            if (first == null) first = x + " " + y + " " + z + " (plan " + String.format("%.1f", s.height()) + ")";
                        }
                    }
                    for (int y = (int) Math.floor(s.height() - 24); y >= s.height() - 60; y -= 4) {
                        var ctx = new DensityFunction.SinglePointContext(x, y, z);
                        if (sc.compute(ctx) > 0 && fd.compute(ctx) < 0) deepCaves++;
                    }
                }
            }
            LOG.info("Clanlands terrain: seed {}: {} of {} solid points near the planned ground opened by a noise cave", seed, opened, checked);
            h.assertTrue(checked > 1000, "seed " + seed + ": the lake lands were sampled (" + checked + " points)");
            h.assertTrue(opened == 0, "seed " + seed + ": " + opened + " solid points near the planned ground opened by a noise cave, first at " + first);
        }
        LOG.info("Clanlands terrain: {} sampled points deep under the lake lands are noise caves", deepCaves);
        h.assertTrue(deepCaves > 50, "the deep noise caves under the lake lands stay (" + deepCaves + " points)");
        h.succeed();
    }
}
