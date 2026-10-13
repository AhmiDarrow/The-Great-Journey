package com.ninjacat.greatjourney.clanlands;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import com.ninjacat.greatjourney.clanlands.world.MapDensity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * Clanlands: every world is built around the Clan territories from the books. The lake territories surround spawn
 * and the old forest lies a long journey to the south-east. Each world lays them out from its own seed.
 */
@Mod(Clanlands.MOD_ID)
public final class Clanlands {
    public static final String MOD_ID = "clanlands";
    private static final Logger LOG = LogUtils.getLogger();

    private static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MOD_ID);

    static {
        DENSITY_TYPES.register("map", () -> MapDensity.CODEC);
    }

    private final Map<UUID, String> lastTitle = new HashMap<>();

    public Clanlands(IEventBus modBus) {
        DENSITY_TYPES.register(modBus);
        com.ninjacat.greatjourney.clanlands.landmark.Landmarks.register(modBus);
        com.ninjacat.greatjourney.clanlands.world.CampSanctuary.register();
        com.ninjacat.greatjourney.clanlands.life.Clanlife.init(modBus);
        com.ninjacat.greatjourney.clanlands.life.rpg.Journals.init(modBus);
        com.ninjacat.greatjourney.clanlands.life.tour.Tour.init();
        NeoForge.EVENT_BUS.addListener(this::serverStarting);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> ClanWorld.stop());
        NeoForge.EVENT_BUS.addListener(this::createSpawn);
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) ClanWorld.overworldLoaded(level);
        });
        NeoForge.EVENT_BUS.addListener(this::playerTick);
    }

    private void serverStarting(ServerAboutToStartEvent event) {
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        ClanWorld.start(seed, event.getServer().registryAccess());
        ClanWorld w = ClanWorld.current();
        LOG.info("Clanlands: the lake territories centre on ({}, {}); the old forest's Fourtrees is at ({}, {})",
                (int) w.lake.centreX(), (int) w.lake.centreZ(), (int) w.forest.centreX(), (int) w.forest.centreZ());
        for (ClanMap m : w.maps()) {
            for (var l : m.landmarks()) LOG.info("Clanlands landmark {} {} at {} {}", m.name(), l.kind(), (int) l.x(), (int) l.z());
        }
        for (ClanMap m : w.maps()) {
            for (var road : m.roads()) {
                StringBuilder pts = new StringBuilder();
                for (double a = 0; a < road.path().length(); a += 200) {
                    double[] pt = road.path().pointAt(a);
                    pts.append(' ').append((int) pt[0]).append(',').append((int) pt[1]);
                }
                double[] end = road.path().pointAt(road.path().length());
                LOG.info("Clanlands road {} {} length {}:{} {},{}", m.name(), road.kind(), (int) road.path().length(), pts, (int) end[0], (int) end[1]);
                for (double c : road.culverts()) {
                    double[] at = road.path().pointAt(c);
                    LOG.info("Clanlands culvert {} at {} {} floor {}", m.name(), (int) at[0], (int) at[1], Math.round(road.floorAt(c)));
                }
            }
        }
        if (w.lake.tunnels() != null) {
            for (double[] e : w.lake.tunnels().entrances) LOG.info("Clanlands tunnel entrance at {} {} {}", (int) e[0], (int) e[1], (int) e[2]);
            for (var bore : w.lake.tunnels().bores) {
                StringBuilder pts = new StringBuilder();
                for (int i = 0; i < bore.xs().length; i += 10) {
                    pts.append(' ').append((int) bore.xs()[i]).append(',').append((int) bore.ys()[i]).append(',').append((int) bore.zs()[i]);
                }
                LOG.debug("Clanlands tunnel bore:{}", pts);
            }
        }
        for (var river : java.util.stream.Stream.concat(w.lake.rivers().stream(), w.forest.rivers().stream()).toList()) {
            for (double[] f : river.falls()) {
                double[] at = river.path.pointAt(f[0]);
                LOG.info("Clanlands waterfall at {} {} from y {} to {}", (int) at[0], (int) at[1], (int) f[1], (int) f[2]);
            }
        }
    }

    /** A new world starts on the ThunderClan shore, by the Sky Oak, looking out over the lake. */
    private void createSpawn(LevelEvent.CreateSpawnPosition event) {
        ClanWorld w = ClanWorld.current();
        if (w == null || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !ClanWorld.isClanWorld(level)) {
            return;                                     // superflat, amplified and other world types keep their spawn
        }
        // the Campaign begins at Fourtrees, on the hollow's rim; Free Mode by the Sky Oak above the lake
        var mode = com.ninjacat.greatjourney.clanlands.life.Mode.assignNew(level);
        double[] s = mode == com.ninjacat.greatjourney.clanlands.life.Mode.CAMPAIGN ? w.forest.spawn() : w.lake.spawn();
        int x = (int) Math.floor(s[0]), z = (int) Math.floor(s[1]);
        level.getChunk(x >> 4, z >> 4);                 // generate it first, or the height reads as the world bottom
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        event.getSettings().setSpawn(new BlockPos(x, y, z), mode == com.ninjacat.greatjourney.clanlands.life.Mode.CAMPAIGN ? w.forest.spawnYaw() : 0.0F);
        event.setCanceled(true);
        LOG.info("Clanlands: {} world, spawn set at {} {} {}", mode, x, y, z);
    }

    /** "ThunderClan territory" on the action bar when a player crosses into a different part of the Clan lands. */
    private void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 20 != 0
                || player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        ClanWorld w = ClanWorld.current();
        if (w == null || ClanWorld.overworldSource() == null) {
            return;                                     // not a Clan lands world: no territory titles
        }
        Sample s = w.sample(player.getBlockX(), player.getBlockZ());
        String title = s.weight() > 0.5 && s.zone() != null ? s.zone().title : null;
        String before = lastTitle.put(player.getUUID(), title);
        if (title != null && !title.equals(before)) {
            player.displayClientMessage(Component.literal(title), true);
        }
    }

    /** For tools and tests: the map holding a position, or null. */
    public static ClanMap mapAt(double x, double z) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return null;
        for (ClanMap m : w.maps()) if (m.covers(x, z)) return m;
        return null;
    }
}
