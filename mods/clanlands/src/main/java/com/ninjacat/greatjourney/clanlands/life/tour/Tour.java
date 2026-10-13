package com.ninjacat.greatjourney.clanlands.life.tour;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.traffic.Traffic;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.TourStop;
import com.ninjacat.greatjourney.clanlands.world.CampSanctuary;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleBinaryOperator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /greatjourney tour}: a guided tour of the world, played by itself. Anyone may take it. For the length of
 * the tour the player is a spectator (nothing can hurt them and they touch nothing); the server carries them from
 * stop to stop so the land loads, and their own client flies the camera and shows the captions. Where they were,
 * their game mode and which way they faced are written into their saved data first, and put back when the tour ends,
 * when they stop it, or (if they left in the middle) the next time they join.
 */
public final class Tour {
    private Tour() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String TAG = "greatjourney.tour";
    /** A tour that hears nothing from its client for this long ends by itself (the client is gone or stuck). */
    private static final int SILENCE_TICKS = 20 * 75;
    /**
     * While a stop shows, the land round the next one or two stops is made in the background, so the wait behind the
     * black screen is short. The tickets are the tour's own (not ChunkWait's, which lets go of its chunks the moment
     * they have loaded): they never time out, and are released as the tour moves on, ends, or the player leaves.
     */
    private static final TicketType<ChunkPos> TICKET = TicketType.create("greatjourney_tour", Comparator.comparingLong(ChunkPos::toLong));
    private static final int AHEAD = 2, PREFETCH_RADIUS = 144;

    private static final class Run {
        final List<TourStops.Planned> stops;
        int index = -1;
        int lastHeard;
        int arrivedAt;
        int monsterTries;
        /** The highest stop whose land has been asked for, and the chunks held for each stop still held. */
        int requested = -1;
        final Map<Integer, List<ChunkPos>> held = new HashMap<>();
        final ServerLevel level;

        Run(List<TourStops.Planned> stops, int now, ServerLevel level) {
            this.stops = stops;
            this.level = level;
            this.lastHeard = now;
        }
    }

    private static final Map<UUID, Run> RUNS = new HashMap<>();

    public static void init() {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && p.getPersistentData().contains(TAG)) restore(p);
        });
        // a player who leaves mid-tour keeps their saved place; they are put back when they next join
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> drop(e.getEntity().getUUID()));
        // the world closing lets the tour's chunks go first, so the save can finish
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> {
            RUNS.values().forEach(Tour::releaseAll);
            RUNS.clear();
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> RUNS.clear());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
    }

    /** Whether the player is on the tour now (their dialogue waits until it ends). */
    public static boolean touring(ServerPlayer p) {
        return RUNS.containsKey(p.getUUID());
    }

    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("tour")
                .executes(c -> start(c.getSource().getPlayerOrException()) ? 1 : 0)
                .then(Commands.literal("stop").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!p.getPersistentData().contains(TAG)) return 0;
                    restore(p);
                    return 1;
                })));
    }

    static boolean start(ServerPlayer p) {
        ClanWorld w = ClanWorld.current();
        if (w == null || !ClanWorld.isClanWorld(p.serverLevel())) {
            p.sendSystemMessage(Component.literal("There is no Clan world here to show you."));
            return false;
        }
        if (touring(p) || p.getPersistentData().contains(TAG)) {
            p.sendSystemMessage(Component.literal("You are already on the tour. /greatjourney tour stop ends it."));
            return false;
        }
        if (p.connection == null || !p.connection.hasChannel(ClanPayloads.TourStart.TYPE)) {
            p.sendSystemMessage(Component.literal("The tour needs The Great Journey installed on your side too."));
            return false;
        }
        if (p.isSleeping() || !p.isAlive()) return false;
        List<TourStops.Planned> stops = TourStops.build(w);
        if (stops.isEmpty()) return false;
        p.stopRiding();

        CompoundTag t = new CompoundTag();
        t.putString("dim", p.level().dimension().location().toString());
        t.putDouble("x", p.getX());
        t.putDouble("y", p.getY());
        t.putDouble("z", p.getZ());
        t.putFloat("yRot", p.getYRot());
        t.putFloat("xRot", p.getXRot());
        t.putInt("mode", p.gameMode.getGameModeForPlayer().getId());
        boolean nightVision = !p.hasEffect(MobEffects.NIGHT_VISION);
        t.putBoolean("nv", nightVision);
        p.getPersistentData().put(TAG, t);

        p.setGameMode(GameType.SPECTATOR);
        // night vision, so the tunnels and the stops that fall in the night are still a pleasure to see
        if (nightVision) p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
        Run run = new Run(stops, p.server.getTickCount(), p.server.overworld());
        RUNS.put(p.getUUID(), run);
        List<TourStop> view = new ArrayList<>();
        for (TourStops.Planned s : stops) view.add(s.stop());
        PacketDistributor.sendToPlayer(p, new ClanPayloads.TourStart(view));
        goTo(p, run, 0);
        return true;
    }

    /** Carries the player to where the camera starts at stop {@code i}, so the land there loads, and tells the client. */
    private static void goTo(ServerPlayer p, Run run, int i) {
        ServerLevel ow = p.server.overworld();
        TourStop s = run.stops.get(i).stop();
        DoubleBinaryOperator ground = (x, z) -> {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            ow.getChunk(bx >> 4, bz >> 4);
            return ow.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        };
        if (run.requested < i) prefetch(run, i);
        for (Integer k : List.copyOf(run.held.keySet())) if (k < i) release(run, k);       // the stops behind us
        double[][][] c = TourCamera.controls(s, ground);
        double[] cam = c[0][0], look = c[1][0];
        double y = s.style() == TourStop.TRACK ? cam[1] : Math.max(cam[1], ground.applyAsDouble(cam[0], cam[2]) + 3);
        double dx = look[0] - cam[0], dy = look[1] - y, dz = look[2] - cam[2];
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        p.teleportTo(ow, cam[0], y, cam[2], yaw, pitch);
        run.index = i;
        run.arrivedAt = p.server.getTickCount();
        run.lastHeard = run.arrivedAt;
        run.monsterTries = 0;
        PacketDistributor.sendToPlayer(p, new ClanPayloads.TourGo(i));
    }

    /** The client has shown stop {@code index}: on to the next, or (after the last) wait for the closing card. */
    public static void onNext(ServerPlayer p, int index) {
        Run run = RUNS.get(p.getUUID());
        if (run == null || index != run.index) return;
        run.lastHeard = p.server.getTickCount();
        if (index + 1 < run.stops.size()) goTo(p, run, index + 1);
        else run.index = run.stops.size();
    }

    /** The client has shown the closing card: the tour is over. */
    public static void onDone(ServerPlayer p) {
        if (RUNS.containsKey(p.getUUID())) restore(p);
    }

    private static void tick(MinecraftServer server) {
        if (RUNS.isEmpty()) return;
        int now = server.getTickCount();
        for (var e : List.copyOf(RUNS.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Run run = e.getValue();
            if (p == null) {
                drop(e.getKey());
                continue;
            }
            if (now - run.lastHeard > SILENCE_TICKS) {
                restore(p);
                continue;
            }
            prefetchAhead(run, now);
            // the roadside stops: send a monster past while the camera watches (the road ahead may still be loading)
            if (run.index >= 0 && run.index < run.stops.size()) {
                TourStops.Planned s = run.stops.get(run.index);
                if (s.road() != null && run.monsterTries >= 0 && run.monsterTries < 12 && now - run.arrivedAt >= 40 + run.monsterTries * 20) {
                    run.monsterTries++;
                    if (Traffic.sendPast(server.overworld(), s.road(), s.along())) {
                        run.monsterTries = -1;
                        LOG.info("Tour: a monster drives past {} for {}", s.stop().title(), p.getGameProfile().getName());
                    }
                }
            }
        }
    }

    /** Ends a run and lets go of every chunk it holds. */
    private static void drop(UUID id) {
        Run run = RUNS.remove(id);
        if (run != null) releaseAll(run);
    }

    /** Asks for the land round the camera's start at stop {@code i} (a ticket on each chunk, made off the server thread). */
    private static void prefetch(Run run, int i) {
        double[] st = TourCamera.startXZ(run.stops.get(i).stop());
        int x0 = (int) Math.floor(st[0] - PREFETCH_RADIUS) >> 4, x1 = (int) Math.floor(st[0] + PREFETCH_RADIUS) >> 4;
        int z0 = (int) Math.floor(st[1] - PREFETCH_RADIUS) >> 4, z1 = (int) Math.floor(st[1] + PREFETCH_RADIUS) >> 4;
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                ChunkPos c = new ChunkPos(cx, cz);
                run.level.getChunkSource().addRegionTicket(TICKET, c, 0, c);
                chunks.add(c);
            }
        }
        List<ChunkPos> old = run.held.put(i, chunks);
        if (old != null) for (ChunkPos c : old) run.level.getChunkSource().removeRegionTicket(TICKET, c, 0, c);
        run.requested = Math.max(run.requested, i);
    }

    /** One stop at a time, nearest first, and never more than {@link #AHEAD} past the one on show. */
    private static void prefetchAhead(Run run, int now) {
        int i = run.index;
        if (i < 0 || i >= run.stops.size() || now % 10 != 0) return;
        int want = Math.min(run.stops.size() - 1, i + AHEAD), f = run.requested;
        if (f >= want) return;
        if (f >= i) {
            List<ChunkPos> last = run.held.get(f);
            if (last != null) for (ChunkPos c : last) if (run.level.getChunkSource().getChunkNow(c.x, c.z) == null) return;   // still making it
        }
        prefetch(run, Math.max(f + 1, i));
    }

    private static void release(Run run, int i) {
        List<ChunkPos> chunks = run.held.remove(i);
        if (chunks != null) for (ChunkPos c : chunks) run.level.getChunkSource().removeRegionTicket(TICKET, c, 0, c);
    }

    private static void releaseAll(Run run) {
        for (Integer k : List.copyOf(run.held.keySet())) release(run, k);
    }

    /** Seconds of Resistance V a restored player gets, and how far mobs are cleared and pushed back. */
    static final int PROTECT_TICKS = 20 * 5;
    static final double FORGET_RADIUS = 24, PUSH_RADIUS = 12, PUSH_TO = 15;

    /** The saved spot if a player fits and stands on something firm there, else the nearest safe spot to it. */
    static Vec3 safeRestorePoint(ServerLevel level, double x, double y, double z) {
        BlockPos feet = BlockPos.containing(x, y, z);
        boolean fits = level.hasChunkAt(feet) && level.noCollision(EntityType.PLAYER.getDimensions().makeBoundingBox(x, y, z))
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                && level.getFluidState(feet).isEmpty() && !level.getBlockState(feet.below()).is(Blocks.MAGMA_BLOCK);
        if (fits) return new Vec3(x, y, z);
        level.getChunk(feet.getX() >> 4, feet.getZ() >> 4);
        BlockPos s = BookClans.safeSpot(level, feet.getX(), feet.getZ(), 6);
        return new Vec3(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
    }

    /**
     * Makes the moment after a restore safe: a few seconds of Resistance V, every mob within {@link #FORGET_RADIUS}
     * forgets the player, and hostile or predatory mobs within {@link #PUSH_RADIUS} are moved a little way out (not
     * killed). A bear had gathered at the saved spot during a tour and killed a child a second after the restore.
     */
    public static void protect(net.minecraft.world.entity.player.Player p) {
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, PROTECT_TICKS, 4, false, false, true));
        p.clearFire();
        ServerLevel level = (ServerLevel) p.level();
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(FORGET_RADIUS), m -> m.getTarget() == p)) {
            m.setTarget(null);
            m.getNavigation().stop();
        }
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(PUSH_RADIUS),
                m -> m.distanceToSqr(p) <= PUSH_RADIUS * PUSH_RADIUS && (CampSanctuary.unwelcome(m) || (m instanceof Enemy && !m.hasCustomName() && !m.isPassenger())))) {
            double dx = m.getX() - p.getX(), dz = m.getZ() - p.getZ(), d = Math.hypot(dx, dz);
            if (d < 1e-3) {
                dx = 1;
                dz = 0;
                d = 1;
            }
            int x = (int) Math.floor(p.getX() + dx / d * PUSH_TO), z = (int) Math.floor(p.getZ() + dz / d * PUSH_TO);
            m.setTarget(null);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - p.getY()) > 24) continue;                       // a cliff or a cave: leave it be
            m.getNavigation().stop();
            m.setDeltaMovement(Vec3.ZERO);
            m.moveTo(x + 0.5, y, z + 0.5, m.getYRot(), m.getXRot());
        }
    }

    /** Puts the player back as they were before the tour: place, facing, world and game mode. */
    static void restore(ServerPlayer p) {
        drop(p.getUUID());
        CompoundTag t = p.getPersistentData().getCompound(TAG);
        p.getPersistentData().remove(TAG);
        if (t.isEmpty()) return;
        ResourceLocation dimId = ResourceLocation.tryParse(t.getString("dim"));
        ServerLevel level = dimId == null ? null : p.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (level == null) level = p.server.getLevel(Level.OVERWORLD);
        p.setDeltaMovement(Vec3.ZERO);
        Vec3 at = safeRestorePoint(level, t.getDouble("x"), t.getDouble("y"), t.getDouble("z"));
        p.teleportTo(level, at.x, at.y, at.z, t.getFloat("yRot"), t.getFloat("xRot"));
        p.setGameMode(GameType.byId(t.getInt("mode")));
        p.fallDistance = 0;
        protect(p);
        if (t.getBoolean("nv")) p.removeEffect(MobEffects.NIGHT_VISION);
        if (p.connection != null && p.connection.hasChannel(ClanPayloads.TourEnd.TYPE)) {
            PacketDistributor.sendToPlayer(p, new ClanPayloads.TourEnd());
        }
    }
}
