package com.ninjacat.greatjourney.clanwild;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * A melee hit landed at speed does more damage: sprinting a little, a sprint-jump or a Warrior Cats leap more.
 * The server cannot trust a player's delta movement, so each player's horizontal travel is measured from tick to
 * tick, and a hit uses the fastest of the last few ticks (the leap is often over by the time the swing lands). Only a
 * cat's own paws count: riding something, gliding on wings, or being carried by a teleport or a change of dimension
 * gives no bonus.
 */
public final class SpeedStrike {
    /** Ticks of movement a strike looks back over. */
    static final int WINDOW = 4;
    /** Anything faster than this in one tick is a teleport, not a run. */
    static final double TELEPORT = 3.0;

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private SpeedStrike() {}

    /** The bonus damage fraction for a strike at {@code speed} blocks per tick. */
    public static double bonusFraction(double speed, double threshold, double perSpeed, double cap) {
        if (!(speed > threshold) || speed > TELEPORT) {
            return 0.0;
        }
        return Math.min(cap, (speed - threshold) * perSpeed);
    }

    /** Fastest horizontal speed this player has had over the last few ticks, in blocks per tick. */
    public static double recentSpeed(Player player) {
        Track t = TRACKS.get(player.getUUID());
        return t == null ? 0.0 : t.max();
    }

    /** Records a tick of horizontal travel; exposed so GameTests can drive it without a real client. */
    public static void record(Player player) {
        Track t = TRACKS.computeIfAbsent(player.getUUID(), id -> new Track(player.getX(), player.getZ()));
        boolean ownPaws = !player.isPassenger() && !player.isFallFlying();
        t.step(player.getX(), player.getZ(), ownPaws);
    }

    public static void forget(Player player) {
        TRACKS.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide()) {
            record(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        forget(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        forget(event.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        forget(event.getEntity());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TRACKS.clear();
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!ClanwildConfig.SPEED_STRIKE.get()) {
            return;
        }
        DamageSource source = event.getSource();
        // a melee swing only: the player is both the cause and the thing that struck (no arrows, no thorns)
        if (!source.is(DamageTypes.PLAYER_ATTACK) || !(source.getDirectEntity() instanceof Player player)
                || source.getEntity() != player) {
            return;
        }
        double bonus = bonusFraction(recentSpeed(player), ClanwildConfig.SPEED_THRESHOLD.get(),
                ClanwildConfig.BONUS_PER_SPEED.get(), ClanwildConfig.MAX_BONUS.get());
        if (bonus > 0.0) {
            event.setAmount((float) (event.getAmount() * (1.0 + bonus)));
        }
    }

    private static final class Track {
        private final double[] speeds = new double[WINDOW];
        private int next;
        private double x;
        private double z;

        Track(double x, double z) {
            this.x = x;
            this.z = z;
        }

        void step(double nx, double nz, boolean ownPaws) {
            double dx = nx - x;
            double dz = nz - z;
            double s = Math.sqrt(dx * dx + dz * dz);
            speeds[next] = !ownPaws || s > TELEPORT ? 0.0 : s;
            next = (next + 1) % WINDOW;
            x = nx;
            z = nz;
        }

        double max() {
            double m = 0.0;
            for (double s : speeds) {
                m = Math.max(m, s);
            }
            return m;
        }
    }
}
