package com.ninjacat.greatjourney.clanlands.life.traffic;

import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * One Twoleg monster roaring along a road: a car or a long truck built from block displays (a painted body, glass,
 * black wheels and lit lamps), all standing at one point and rotated together, so the whole thing moves by moving
 * that point. The client smooths each step. It hurts whatever it hits, scares Clan cats off the road, and growls
 * as it comes, so a careful cat hears it in time.
 */
final class Monster {
    static final String TAG = "greatjourney_monster";
    static final ResourceKey<DamageType> HIT = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath("clanlands", "monster"));

    /** One box of the model in local space: x to the driver's left, y up from the wheels, z forward. */
    private record Part(String block, float x, float y, float z, float sx, float sy, float sz, boolean lit) {}

    private static final String[] PAINT = {"red", "blue", "white", "yellow", "black", "light_gray", "green", "orange", "cyan"};

    final Road road;
    final int dir, lane;
    final boolean truck;
    final double speed, length, width, height, offset, passAlong;
    double along, y = Double.NaN;
    /** This tick's speed: its own, or slower while it is stuck behind a slower monster in its lane. */
    double pace;
    private final List<Display.BlockDisplay> parts = new ArrayList<>();
    private final Set<UUID> hit = new HashSet<>();
    /** Players who have heard it coming, felt it roar past, and smelled it go. */
    private final Set<UUID> roaredAt = new HashSet<>(), reeked = new HashSet<>();
    private boolean honked;
    private int age;
    /** Game time of its first tick (-1 before it). */
    private long born = -1;

    Monster(Road road, int dir, int lane, boolean truck, double along, double passAlong, RandomSource r) {
        this.road = road;
        this.dir = dir;
        this.lane = lane;
        this.truck = truck;
        this.along = along;
        this.passAlong = passAlong;
        this.speed = truck ? 0.7 + r.nextDouble() * 0.2 : 0.9 + r.nextDouble() * 0.4;
        this.pace = speed;
        this.length = truck ? 7.6 : 4.1;
        this.width = truck ? 2.4 : 1.9;
        this.height = truck ? 3.2 : 1.8;
        this.offset = TrafficMath.laneOffset(road.kind(), road.half(), lane);
    }

    /** Builds the model's displays at the monster's starting point. Returns false if the spot is not loaded. */
    boolean spawn(ServerLevel level, RandomSource r, Set<UUID> registry) {
        keepLoaded(level);
        double[] pose = pose(level);
        if (pose == null) return false;
        String paint = "minecraft:" + PAINT[r.nextInt(PAINT.length)] + "_concrete";
        for (Part p : truck ? truckParts(paint, r) : carParts(paint)) {
            Display.BlockDisplay d = EntityType.BLOCK_DISPLAY.create(level);
            if (d == null) continue;
            d.load(partTag(p));
            d.moveTo(pose[0], pose[1], pose[2], (float) pose[3], 0);
            d.addTag(TAG);
            registry.add(d.getUUID());
            level.addFreshEntity(d);
            parts.add(d);
        }
        return !parts.isEmpty();
    }

    private static List<Part> carParts(String paint) {
        List<Part> out = new ArrayList<>();
        out.add(new Part(paint, 0, 0.32f, 0, 1.9f, 0.75f, 4.0f, false));
        out.add(new Part("minecraft:gray_stained_glass", 0, 1.07f, -0.2f, 1.6f, 0.6f, 2.0f, false));
        out.add(new Part(paint, 0, 1.67f, -0.2f, 1.66f, 0.12f, 2.06f, false));
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
            out.add(new Part("minecraft:black_concrete", sx * 0.82f, 0, sz * 1.3f, 0.32f, 0.56f, 0.62f, false));
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            out.add(new Part("minecraft:white_concrete", sx * 0.6f, 0.62f, 2.0f, 0.42f, 0.22f, 0.07f, true));
            out.add(new Part("minecraft:red_concrete", sx * 0.66f, 0.66f, -2.0f, 0.36f, 0.18f, 0.07f, true));
        }
        return out;
    }

    private static List<Part> truckParts(String paint, RandomSource r) {
        List<Part> out = new ArrayList<>();
        out.add(new Part(paint, 0, 0.4f, 2.6f, 2.3f, 1.9f, 2.3f, false));
        out.add(new Part("minecraft:gray_stained_glass", 0, 1.55f, 3.76f, 2.1f, 0.7f, 0.1f, false));
        String box = r.nextBoolean() ? "minecraft:white_concrete" : "minecraft:light_gray_concrete";
        out.add(new Part(box, 0, 0.55f, -1.1f, 2.4f, 2.6f, 5.3f, false));
        for (int sx = -1; sx <= 1; sx += 2) for (float z : new float[] {2.9f, -0.2f, -2.9f}) {
            out.add(new Part("minecraft:black_concrete", sx * 1.05f, 0, z, 0.34f, 0.7f, 0.8f, false));
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            out.add(new Part("minecraft:white_concrete", sx * 0.8f, 0.7f, 3.78f, 0.45f, 0.25f, 0.07f, true));
            out.add(new Part("minecraft:red_concrete", sx * 1.0f, 0.75f, -3.78f, 0.35f, 0.2f, 0.07f, true));
        }
        return out;
    }

    private static CompoundTag partTag(Part p) {
        CompoundTag t = new CompoundTag();
        CompoundTag state = new CompoundTag();
        state.putString("Name", p.block());
        t.put("block_state", state);
        CompoundTag tf = new CompoundTag();
        tf.put("left_rotation", floats(0, 0, 0, 1));
        tf.put("right_rotation", floats(0, 0, 0, 1));
        tf.put("translation", floats(p.x() - p.sx() / 2, p.y(), p.z() - p.sz() / 2));
        tf.put("scale", floats(p.sx(), p.sy(), p.sz()));
        t.put("transformation", tf);
        t.putInt("teleport_duration", 2);
        t.putFloat("view_range", 1.5f);
        t.putFloat("shadow_radius", 0);
        if (p.lit()) {
            CompoundTag b = new CompoundTag();
            b.putInt("sky", 15);
            b.putInt("block", 15);
            t.put("brightness", b);
        }
        return t;
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) l.add(FloatTag.valueOf(f));
        return l;
    }

    /** Ticks a monster may live, however far it has got. */
    static final int LIFETIME = 20 * 60;

    private static final TicketType<ChunkPos> TICKET = TicketType.create("greatjourney_traffic", Comparator.comparingLong(ChunkPos::toLong), 40);

    /**
     * Holds the land under the monster and 40 blocks ahead of it loaded while it drives (the tickets lapse by
     * themselves a couple of seconds after it is gone), so the run-up from far off never drives into land that has
     * been let go and freezes, or ends, before it reaches the cat it was sent for.
     */
    private void keepLoaded(ServerLevel level) {
        for (double s : new double[] {along, along + dir * 40.0}) {
            double[] p = road.path().pointAt(s);
            ChunkPos c = new ChunkPos(BlockPos.containing(p[0], 0, p[1]));
            level.getChunkSource().addRegionTicket(TICKET, c, 1, c);
        }
    }

    /** {x, y, z, yaw, fx, fz} at the current distance along the road, or null where the world is not loaded. */
    double[] pose(ServerLevel level) {
        double[] p = road.path().pointAt(along), q = road.path().pointAt(along + dir * 2.0);
        double fx = q[0] - p[0], fz = q[1] - p[1], fl = Math.hypot(fx, fz);
        if (fl < 1e-6) return null;
        fx /= fl;
        fz /= fl;
        double[] l = TrafficMath.left(fx, fz);
        double x = p[0] + l[0] * offset, z = p[1] + l[1] * offset;
        BlockPos at = BlockPos.containing(x, 0, z);
        if (!level.isLoaded(at)) return null;
        double half = length / 2 - 0.4;
        double centre = top(level, x, z), front = top(level, x + fx * half, z + fz * half), back = top(level, x - fx * half, z - fz * half);
        y = TrafficMath.rideHeight(planned(x, z), centre, front, back, y);
        return new double[] {x, y, z, TrafficMath.yaw(fx, fz), fx, fz};
    }

    private static double top(ServerLevel level, double x, double z) {
        BlockPos p = BlockPos.containing(x, 0, z);
        return level.isLoaded(p) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) : Double.NaN;
    }

    /**
     * The top of the road's planned bed here, where the road is laid on it (graded, and well inside the Clan lands);
     * NaN out in the wider world, where the road lies on the land as it is.
     */
    private double planned(double x, double z) {
        if (!road.graded()) return Double.NaN;
        ClanWorld w = ClanWorld.current();
        if (w == null || w.sample(x, z).weight() < 0.95) return Double.NaN;
        return Math.round(road.bedAt(along)) + 1;
    }

    /** One tick of driving. Returns false when the monster is gone (passed by, off its road, or out of loaded land). */
    boolean tick(ServerLevel level, float damage) {
        if (!Traffic.frozenForTest) along += dir * pace;
        age++;
        if (born < 0) born = level.getGameTime();
        // whatever happens to it (stalled, starved of ticks, never moving) a monster is over within its lifetime
        if (TrafficMath.done(along, passAlong, dir, road.path().length(), 140) || age > LIFETIME || level.getGameTime() - born > LIFETIME) return false;
        keepLoaded(level);
        double[] pose = pose(level);
        if (pose == null) return false;
        for (Display.BlockDisplay d : parts) {
            if (d.isRemoved()) return false;
            d.moveTo(pose[0], pose[1], pose[2], (float) pose[3], 0);
        }
        double x = pose[0], yy = pose[1], z = pose[2], fx = pose[4], fz = pose[5];
        RandomSource r = level.getRandom();
        // its growl, its tread on the hard black stone, and the stinking smoke it leaves behind
        if (age % 8 == 0) {
            level.playSound(null, x, yy, z, SoundEvents.MINECART_RIDING, SoundSource.NEUTRAL, 2.6f, 0.45f + r.nextFloat() * 0.1f);
        }
        if (age % 23 == 0) {
            level.playSound(null, x, yy, z, SoundEvents.RAVAGER_STEP, SoundSource.NEUTRAL, 1.4f, 0.5f);
        }
        if (age % 3 == 0) {
            double bx = x - fx * (length / 2 + 0.3), bz = z - fz * (length / 2 + 0.3);
            level.sendParticles(truck ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE, bx, yy + 0.35, bz, truck ? 2 : 1, 0.15, 0.05, 0.15, 0.01);
        }
        if (age % 4 == 0) {
            BlockState under = level.getBlockState(BlockPos.containing(x, yy - 0.5, z));
            if (!under.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, under), x, yy + 0.05, z, 3,
                        width / 2, 0.02, length / 3, 0.0);
            }
        }
        double reach = length + 6;
        AABB box = new AABB(x - reach, yy - 2, z - reach, x + reach, yy + height + 2, z + reach);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e instanceof ServerPlayer sp && (sp.isCreative() || sp.isSpectator())) continue;
            double dx = e.getX() - x, dy = e.getY() - yy, dz = e.getZ() - z;
            if (TrafficMath.inFootprint(dx, dy, dz, fx, fz, length, width, height)) {
                if (hit.add(e.getUUID())) strike(level, e, damage, dx, dz, fx, fz, r);
            } else if (Math.hypot(dx, dz) < 5 && age % 6 == 0) {
                level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 0.6f, 0.5f);
            }
        }
        if (age % 10 == 0) {
            warn(level, x, yy, z, fx, fz);
            heralds(level, x, yy, z, fx, fz);
        }
        return true;
    }

    /**
     * A monster strikes a creature in its path. Clan cats who live with a player (tamed, or in a Clan) nearly always
     * leap clear at the last moment, as warriors learn to; anything else is struck hard and flung aside.
     */
    private void strike(ServerLevel level, LivingEntity e, float damage, double dx, double dz, double fx, double fz, RandomSource r) {
        double side = Math.signum(TrafficMath.sideOf(dx, dz, fx, fz));
        if (side == 0) side = 1;
        double[] l = TrafficMath.left(fx, fz);
        if (e instanceof WCatEntity cat && clanmate(cat) && r.nextFloat() < CLANMATE_LEAPS_CLEAR) {
            cat.push(l[0] * side * 1.1, 0.5, l[1] * side * 1.1);
            cat.hurtMarked = true;
            level.playSound(null, cat.getX(), cat.getY(), cat.getZ(), SoundEvents.CAT_HISS, SoundSource.NEUTRAL, 1.0f, 1.0f);
            return;
        }
        e.hurt(level.damageSources().source(HIT), damage);
        e.push(l[0] * side * 1.3 + fx * 0.6, 0.55, l[1] * side * 1.3 + fz * 0.6);
        e.hurtMarked = true;
        level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 1.2f, 0.6f);
    }

    /** How often a player's own cat or Clanmate leaps clear of a monster that would have hit it. */
    static final float CLANMATE_LEAPS_CLEAR = 0.75f;

    static boolean clanmate(WCatEntity cat) {
        if (cat.isTame()) return true;
        UUID clan = cat.getClanUUID();
        return clan != null && !clan.equals(WCatEntity.emptyUUID);
    }

    /**
     * What a player near the road notices as it comes: a low growl swelling out of the distance (sent to that player
     * only, from the monster's direction), the ground trembling under their paws as it nears, its roar as it passes,
     * and the reek it leaves.
     */
    private void heralds(ServerLevel level, double x, double yy, double z, double fx, double fz) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = x - p.getX(), dz = z - p.getZ(), d = Math.hypot(dx, dz);
            if (d > 150 || d < 1e-3 || Math.abs(p.getY() - yy) > 40) continue;
            boolean coming = (p.getX() - x) * fx + (p.getZ() - z) * fz > 0;
            if (coming && d > 24) {
                float vol = (float) (0.25 + 0.75 * (1 - d / 150));
                double ux = dx / d, uz = dz / d;
                p.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.MINECART_RIDING), SoundSource.NEUTRAL,
                        p.getX() + ux * 10, p.getEyeY(), p.getZ() + uz * 10, vol, 0.35f + (float) (0.15 * (1 - d / 150)),
                        level.getRandom().nextLong()));
            }
            if (d < 20) {
                BlockState under = level.getBlockState(p.blockPosition().below());
                if (!under.isAir()) {
                    level.sendParticles(p, new BlockParticleOption(ParticleTypes.BLOCK, under), false,
                            p.getX(), p.getY() + 0.05, p.getZ(), 6, 0.4, 0.02, 0.4, 0.0);
                }
            }
            if (d < 14 && roaredAt.add(p.getUUID())) {
                level.playSound(null, x, yy, z, SoundEvents.RAVAGER_ROAR, SoundSource.NEUTRAL, 1.8f, truck ? 0.5f : 0.65f);
            }
            if (d < 8 && !coming && reeked.add(p.getUUID())) Traffic.reek(p);
        }
    }

    /** Clan cats on or beside the road ahead bolt for the verge; a player standing in its lane gets a blast of horn. */
    private void warn(ServerLevel level, double x, double yy, double z, double fx, double fz) {
        AABB ahead = new AABB(x - 40, yy - 4, z - 40, x + 40, yy + 6, z + 40);
        for (WCatEntity cat : level.getEntitiesOfClass(WCatEntity.class, ahead)) {
            double dx = cat.getX() - x, dz = cat.getZ() - z;
            double forward = dx * fx + dz * fz, side = TrafficMath.sideOf(dx, dz, fx, fz);
            // a player's Clanmates know the Thunderpath and look up sooner; a cat in a culvert below is safe
            double sees = clanmate(cat) ? 48 : 36;
            if (forward > -4 && forward < sees && Math.abs(side) < road.half() + 6 && Math.abs(cat.getY() - yy) < 3) {
                double away = side >= 0 ? 1 : -1;
                double[] l = TrafficMath.left(fx, fz);
                double tx = cat.getX() + l[0] * away * (road.half() + 9), tz = cat.getZ() + l[1] * away * (road.half() + 9);
                cat.getNavigation().moveTo(tx, cat.getY(), tz, 1.8);
            }
        }
        if (!honked) {
            for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, ahead)) {
                double dx = p.getX() - x, dz = p.getZ() - z;
                double forward = dx * fx + dz * fz, side = TrafficMath.sideOf(dx, dz, fx, fz);
                if (forward > 4 && forward < 32 && Math.abs(side) < width) {
                    var horns = SoundEvents.GOAT_HORN_SOUND_VARIANTS;
                    level.playSound(null, x, yy, z, horns.get(level.getRandom().nextInt(Math.min(2, horns.size()))), SoundSource.NEUTRAL, 3.0f, 1.35f);
                    honked = true;
                    break;
                }
            }
        }
    }

    void remove() {
        for (Display.BlockDisplay d : parts) d.discard();
        parts.clear();
    }

    List<Display.BlockDisplay> parts() {
        return parts;
    }
}
