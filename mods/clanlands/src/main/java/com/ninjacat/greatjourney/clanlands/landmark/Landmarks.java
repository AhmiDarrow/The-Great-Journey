package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The landmark builder: one feature that runs once per chunk at the end of decoration and paints the part of every
 * landmark (and stream) that falls inside that chunk. Each landmark is drawn from its own seed, so pieces built in
 * different chunks line up.
 */
public final class Landmarks {
    private Landmarks() {}

    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, "clanlands");

    static {
        FEATURES.register("landmarks", () -> new Feature<>(NoneFeatureConfiguration.CODEC) {
            @Override
            public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
                ClanWorld w = ClanWorld.current();
                // only the Clan lands' own overworld: not superflat, amplified or other world types, and not any
                // other dimension that borrows overworld biomes
                if (w == null || ClanWorld.overworldSource() == null || ctx.level().getLevel().dimension() != Level.OVERWORLD
                        || !ClanWorld.isClanGenerator(ctx.chunkGenerator())) {
                    return false;
                }
                WorldGenLevel level = ctx.level();
                int minX = ctx.origin().getX() & ~15, minZ = ctx.origin().getZ() & ~15;
                ChunkPainter painter = new ChunkPainter(level, minX, minZ);
                boolean any = Waters.paint(w, painter);
                any |= Roads.paint(w, painter);
                for (Landmark l : w.landmarksNear(minX, minZ)) {
                    Builders.build(l, painter);
                    any = true;
                }
                any |= Camps.outskirts(w, painter);
                any |= Lava.seal(w, painter);
                return any;
            }
        });
    }

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }
}
