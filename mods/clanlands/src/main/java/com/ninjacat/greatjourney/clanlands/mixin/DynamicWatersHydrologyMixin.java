package com.ninjacat.greatjourney.clanlands.mixin;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Dynamic Waters rivers in the Clan lands. Its sea-level river channels were carved straight through the planned
 * land (a pit under a waterfall, a pond against the ThunderClan ravine camp's wall, a dry stone bowl by a tunnel
 * mouth); the maps already plan every stream and lake there. Where the map weight is {@link #OFF} or more (the lake
 * lands, the old forest, the journey corridor) every river query answers "no river here"; between {@link #FADE_FROM}
 * and {@link #OFF} the channel's bed and banks rise smoothly back to the untouched ground, so a river that runs into
 * the Clan lands ends in a shallow beach instead of against a wall. Everywhere else Dynamic Waters is unchanged.
 *
 * <p>Dynamic Waters asks the same three questions for every step of its work, and each is answered consistently:
 * <ul>
 * <li>{@code calculateRiverCarving}: the per-column carve, used when it cuts the channel (before surface rules), when
 *     it repairs the channel after the cave carvers, and for its "true terrain height";</li>
 * <li>{@code evaluateRiverContext}: whether a column is beside a river, which gates that repair (it otherwise fills
 *     water up to sea level and stones in caves along the path), keeps trees and structures off the banks and drives
 *     the current that pushes swimmers;</li>
 * <li>{@code isRiverOrWallBlock}: keeps cave carvers away from a river; with no river there, caves carve as they do
 *     everywhere else in the Clan lands.</li>
 * </ul>
 *
 * <p>Dynamic Waters is optional: the target is named by string under {@code @Pseudo}, so without the mod this mixin
 * is skipped, and every injection has {@code require = 0}. Its result classes are not compiled against; their public
 * fields are written by reflection, and if a later version renames them the hook logs once and leaves the mod alone.
 */
@Pseudo
@Mixin(targets = "com.AdamNew.dynamicwaters.HydrologyManager", remap = false)
abstract class DynamicWatersHydrologyMixin {
    /** Map weight from which Dynamic Waters carves nothing. */
    @Unique
    private static final double OFF = 0.2;
    /** Map weight below which Dynamic Waters carves as usual; between this and {@link #OFF} its channels fade out. */
    @Unique
    private static final double FADE_FROM = 0.1;
    @Unique
    private static final Logger clanlands$LOG = LogUtils.getLogger();
    /** RiverCarving fields, looked up on first use; an empty array once the lookup has failed. */
    @Unique
    private static volatile Field[] clanlands$carving;
    /** RiverContext fields, looked up on first use; an empty array once the lookup has failed. */
    @Unique
    private static volatile Field[] clanlands$context;
    @Unique
    private static volatile boolean clanlands$announced;

    @Inject(method = "calculateRiverCarving(DDIILnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)Lcom/AdamNew/dynamicwaters/HydrologyManager$RiverCarving;",
            at = @At("RETURN"), require = 0, remap = false)
    private static void clanlands$noRiverCarving(double x, double z, int originalY, int seaLevel,
            net.minecraft.world.level.chunk.ChunkGenerator generator, net.minecraft.world.level.LevelHeightAccessor level,
            net.minecraft.world.level.levelgen.RandomState randomState, CallbackInfoReturnable<Object> cir) {
        double w = clanlands$weight(x, z);
        if (w <= FADE_FROM) return;
        Object r = cir.getReturnValue();
        Field[] f = clanlands$carvingFields(r);
        if (r == null || f.length == 0) return;
        try {
            // targetY waterLevel cavernRoofY modifies isPillar isMountainRiver isMountainRiverWall isMainRiverWater
            // wallTopY distanceFromCenter channelWidth
            if (w >= OFF) {
                if (!f[3].getBoolean(r)) return;
                // Exactly what calculateRiverCarving returns for a column no river reaches.
                f[0].setInt(r, originalY);
                f[1].setInt(r, seaLevel - 1);
                f[2].setInt(r, -999);
                f[3].setBoolean(r, false);
                f[4].setBoolean(r, false);
                f[5].setBoolean(r, false);
                f[6].setBoolean(r, false);
                f[7].setBoolean(r, false);
                f[8].setInt(r, -999);
                f[9].setDouble(r, Double.MAX_VALUE);
                f[10].setDouble(r, 0.0);
                clanlands$announce();
            } else if (f[3].getBoolean(r)) {
                double t = (w - FADE_FROM) / (OFF - FADE_FROM);
                t = t * t * (3 - 2 * t);
                int target = f[0].getInt(r);
                if (target < originalY) f[0].setInt(r, target + (int) Math.round((originalY - target) * t));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            clanlands$carving = new Field[0];
            clanlands$LOG.error("Clanlands could not keep Dynamic Waters rivers out of the Clan lands; leaving them as they are", e);
        }
    }

    @Inject(method = "evaluateRiverContext(DD)Lcom/AdamNew/dynamicwaters/HydrologyManager$RiverContext;",
            at = @At("RETURN"), require = 0, remap = false)
    private static void clanlands$noRiverNearby(double x, double z, CallbackInfoReturnable<Object> cir) {
        if (clanlands$weight(x, z) < OFF) return;
        Object ctx = cir.getReturnValue();
        Field[] f = clanlands$contextFields(ctx);
        if (ctx == null || f.length == 0) return;
        try {
            // distance width isTributary isCaveRiver bestFractal: the values it starts from before any path is found
            f[0].setDouble(ctx, Double.MAX_VALUE);
            f[1].setDouble(ctx, 10.0);
            f[2].setBoolean(ctx, false);
            f[3].setBoolean(ctx, false);
            f[4].set(ctx, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            clanlands$context = new Field[0];
            clanlands$LOG.error("Clanlands could not hide Dynamic Waters rivers in the Clan lands; leaving them as they are", e);
        }
    }

    @Inject(method = "isRiverOrWallBlock(DID)Z", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void clanlands$cavesAsUsual(double x, int y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && clanlands$weight(x, z) >= OFF) cir.setReturnValue(false);
    }

    /** The Clan map's weight at a column; 0 outside the maps, or when this overworld is not a Clan world. */
    @Unique
    private static double clanlands$weight(double x, double z) {
        ClanWorld w = ClanWorld.current();
        if (w == null || ClanWorld.overworldSource() == null) return 0;
        return w.sample(x, z).weight();
    }

    @Unique
    private static Field[] clanlands$carvingFields(Object r) {
        Field[] f = clanlands$carving;
        if (f == null && r != null) {
            f = clanlands$fields(r.getClass(), "targetY", "waterLevel", "cavernRoofY", "modifies", "isPillar",
                    "isMountainRiver", "isMountainRiverWall", "isMainRiverWater", "wallTopY", "distanceFromCenter",
                    "channelWidth");
            clanlands$carving = f;
        }
        return f == null ? new Field[0] : f;
    }

    @Unique
    private static Field[] clanlands$contextFields(Object ctx) {
        Field[] f = clanlands$context;
        if (f == null && ctx != null) {
            f = clanlands$fields(ctx.getClass(), "distance", "width", "isTributary", "isCaveRiver", "bestFractal");
            clanlands$context = f;
        }
        return f == null ? new Field[0] : f;
    }

    @Unique
    private static Field[] clanlands$fields(Class<?> type, String... names) {
        try {
            Field[] out = new Field[names.length];
            for (int i = 0; i < names.length; i++) out[i] = type.getField(names[i]);
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            clanlands$LOG.error("Clanlands does not recognise this Dynamic Waters version ({}); its rivers may cut the Clan lands",
                    type.getName(), e);
            return new Field[0];
        }
    }

    @Unique
    private static void clanlands$announce() {
        if (clanlands$announced) return;
        clanlands$announced = true;
        clanlands$LOG.info("Clanlands: Dynamic Waters rivers are off inside the Clan lands (map weight {} and up)", OFF);
    }
}
