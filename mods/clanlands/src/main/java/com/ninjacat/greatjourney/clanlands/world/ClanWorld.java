package com.ninjacat.greatjourney.clanlands.world;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.JourneyRoute;
import com.ninjacat.greatjourney.clanlands.layout.LakeLands;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.OldForest;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/**
 * The Clan lands of the running world: both maps, laid out from the world seed when the server starts, plus the
 * resolved territory biomes. Worldgen threads read {@link #current()}; it is replaced on every server start.
 */
public final class ClanWorld {
    private static volatile ClanWorld current;

    public final long seed;
    public final LakeLands lake;
    public final OldForest forest;
    public final JourneyRoute journey;
    private final List<ClanMap> maps;
    private final Map<Zone, Holder<Biome>> biomes;

    private ClanWorld(long seed, Map<Zone, Holder<Biome>> biomes) {
        this.seed = seed;
        this.lake = new LakeLands(seed);
        this.forest = new OldForest(seed);
        this.journey = new JourneyRoute(seed, lake, forest);
        this.maps = List.of(lake, forest, journey);
        this.biomes = biomes;
    }

    private static volatile net.minecraft.world.level.biome.BiomeSource overworldSource;

    /**
     * The overworld's own biome source, recorded at server start. The biome hook compares against this instance:
     * TerraBlender rewires the overworld's biome parameters, so asking the source "are you the overworld preset?" no
     * longer works in the full pack.
     */
    public static net.minecraft.world.level.biome.BiomeSource overworldSource() {
        return overworldSource;
    }

    /**
     * Whether the overworld is a normal world, where the Clan lands belong (not superflat, amplified or another
     * world type). Mods rebuild the overworld's noise settings, so they may no longer carry their registry name;
     * then the vanilla overworld's shape (sea level 63, height 384 from y -64) and a multi-noise biome source decide.
     */
    public static boolean isClanWorld(net.minecraft.server.level.ServerLevel level) {
        var ow = level.getServer().overworld();
        return isClanGenerator(ow.getChunkSource().getGenerator());
    }

    public static boolean isClanGenerator(net.minecraft.world.level.chunk.ChunkGenerator generator) {
        if (!(generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator gen)) return false;
        var settings = gen.generatorSettings();
        if (settings.unwrapKey().isPresent()) return settings.is(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var noise = settings.value().noiseSettings();
        return gen.getBiomeSource() instanceof net.minecraft.world.level.biome.MultiNoiseBiomeSource
                && settings.value().seaLevel() == 63 && noise.minY() == -64 && noise.height() == 384;
    }

    public static ClanWorld current() {
        return current;
    }

    /** Lays out a world without registries (layout tools and tests). */
    public static ClanWorld layoutOnly(long seed) {
        return new ClanWorld(seed, Map.of());
    }

    public static void start(long seed, RegistryAccess registries) {
        var stem = registries.registryOrThrow(Registries.LEVEL_STEM).get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
        overworldSource = stem != null && isClanGenerator(stem.generator()) ? stem.generator().getBiomeSource() : null;
        Map<Zone, Holder<Biome>> biomes = new EnumMap<>(Zone.class);
        var lookup = registries.lookupOrThrow(Registries.BIOME);
        for (Zone z : Zone.values()) {
            for (String id : z.biomes) {
                var h = lookup.get(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id)));
                if (h.isPresent()) {
                    biomes.put(z, h.get());
                    break;
                }
            }
        }
        current = new ClanWorld(seed, biomes);
    }

    private static volatile net.minecraft.world.level.biome.Climate.Sampler overworldSampler;

    /** Records the live overworld's biome source and climate sampler as the level loads, before any chunk generates. */
    public static void overworldLoaded(net.minecraft.server.level.ServerLevel level) {
        var gen = level.getChunkSource().getGenerator();
        if (isClanGenerator(gen)) {
            overworldSource = gen.getBiomeSource();
            overworldSampler = level.getChunkSource().randomState().sampler();
        }
    }

    /** The overworld's climate sampler: chunk biome filling is recognised by it, whatever resolver a mod supplies. */
    public static net.minecraft.world.level.biome.Climate.Sampler overworldSampler() {
        return overworldSampler;
    }

    /**
     * The Clan lands' biome at a quart position (x, y, z in quarter blocks), or null to leave the normal biome:
     * each zone's biome above ground inside the maps, the normal caves below.
     */
    public static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biomeAt(int qx, int qy, int qz) {
        ClanWorld w = current;
        if (w == null || qy < 0) return null;
        var s = w.sample((qx << 2) + 2, (qz << 2) + 2);
        return s.weight() > 0.5 && s.zone() != null ? w.biome(s.zone()) : null;
    }

    /**
     * Below y 0 the Clan lands keep the normal cave biomes, but not a normal ocean, river or beach biome: those are
     * what the noise picks under the lake, and an ocean's features test the biome at the chunk floor, so frozen-ocean
     * icebergs grew up through the lake. Such a quart takes the zone's biome like the ground above it.
     */
    public static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biomeBelow(int qx, int qy, int qz,
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> normal) {
        ClanWorld w = current;
        if (w == null || qy >= 0 || normal == null) return normal;
        if (!normal.is(net.minecraft.tags.BiomeTags.IS_OCEAN) && !normal.is(net.minecraft.tags.BiomeTags.IS_RIVER)
                && !normal.is(net.minecraft.tags.BiomeTags.IS_BEACH)) {
            return normal;
        }
        var s = w.sample((qx << 2) + 2, (qz << 2) + 2);
        return s.weight() > 0.5 && s.zone() != null ? w.biome(s.zone()) : normal;
    }

    public static void stop() {
        current = null;
        overworldSource = null;
        overworldSampler = null;
    }

    public Holder<Biome> biome(Zone z) {
        return biomes.get(z);
    }

    public List<Holder<Biome>> allBiomes() {
        return new ArrayList<>(biomes.values());
    }

    public List<ClanMap> maps() {
        return maps;
    }

    /**
     * Per worldgen thread, the last samples by column: 256 slots indexed by the column's low bits, so the density
     * router's cell corners, a chunk's biome quarts and the landmark painters' columns all find what they asked for a
     * moment ago. Each slot remembers which world it was worked out for, so a new world never reads an old answer.
     */
    private static final class SampleCache {
        final double[] xs = new double[256], zs = new double[256];
        final Sample[] samples = new Sample[256];
        final ClanWorld[] owners = new ClanWorld[256];
    }

    private static final ThreadLocal<SampleCache> CACHE = ThreadLocal.withInitial(SampleCache::new);

    /** The Clan lands at a column; {@link Sample#OUTSIDE} away from both maps. */
    public Sample sample(double x, double z) {
        SampleCache c = CACHE.get();
        int i = ((int) Math.floor(x) & 15) | (((int) Math.floor(z) & 15) << 4);
        if (c.owners[i] == this && c.xs[i] == x && c.zs[i] == z) {
            return c.samples[i];
        }
        Sample s = Sample.OUTSIDE;
        for (ClanMap m : maps) {
            if (m.covers(x, z)) {
                s = m.sample(x, z);
                if (s.weight() > 0) break;         // a map's reach is a rough circle: fall through to the next map
            }
        }
        c.xs[i] = x;
        c.zs[i] = z;
        c.samples[i] = s;
        c.owners[i] = this;
        return s;
    }

    public List<Landmark> landmarksNear(int chunkMinX, int chunkMinZ) {
        List<Landmark> out = new ArrayList<>();
        for (ClanMap m : maps) {
            if (m.covers(chunkMinX + 8, chunkMinZ + 8) || m.covers(chunkMinX, chunkMinZ)) {
                for (Landmark l : m.landmarks()) {
                    if (l.reaches(chunkMinX, chunkMinZ)) out.add(l);
                }
            }
        }
        return out;
    }
}
