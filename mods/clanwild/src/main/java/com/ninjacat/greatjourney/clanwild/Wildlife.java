package com.ninjacat.greatjourney.clanwild;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Fewer dangerous animals, and none that hounds a cat without end. Kiki met too many in her playtest, and a golden
 * eagle struck at her again and again.
 * <ul>
 *   <li>Dangerous animals ({@link #DANGEROUS}: badgers, golden eagles, wolves, foxes, snakes...) spawn on their own
 *       only now and then ({@link ClanwildConfig#DANGEROUS_SHARE}), and never close to another one, so a stretch of
 *       woods holds one threat at a time instead of a pack of them.</li>
 *   <li>A wild bird of prey ({@link #RAPTORS}: Warrior Cats Events' golden eagle) never carries a player off, and
 *       after it has struck a player once it leaves players alone for a while ({@link ClanwildConfig#RAPTOR_CALM})
 *       and climbs away. Tamed ones are the owner's business.</li>
 * </ul>
 * Spawns made on purpose (commands, eggs, the Clans' own events) are never thinned.
 */
public final class Wildlife {
    private Wildlife() {}

    public static final TagKey<EntityType<?>> DANGEROUS =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(Clanwild.MOD_ID, "dangerous"));
    public static final TagKey<EntityType<?>> RAPTORS =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(Clanwild.MOD_ID, "raptors"));

    /** On a raptor that has struck: the game time until which it leaves players alone. */
    static final String CALM_UNTIL = "clanwild.calm_until";

    @SubscribeEvent
    public static void placement(MobSpawnEvent.SpawnPlacementCheck e) {
        MobSpawnType how = e.getSpawnType();
        if (how != MobSpawnType.NATURAL && how != MobSpawnType.CHUNK_GENERATION) return;
        if (!e.getEntityType().is(DANGEROUS) || e.getLevel().getLevel().dimension() != Level.OVERWORLD) return;
        if (e.getRandom().nextDouble() >= ClanwildConfig.DANGEROUS_SHARE.get()) {
            e.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
            return;
        }
        // with the land around it (chunk generation runs off the main thread on a region, which holds no mobs yet)
        if (e.getLevel() instanceof ServerLevel level && crowded(level, e.getPos().getX() + 0.5, e.getPos().getZ() + 0.5)) {
            e.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** Whether another dangerous animal is already about within the spacing. */
    public static boolean crowded(ServerLevel level, double x, double z) {
        double r = ClanwildConfig.DANGEROUS_SPACING.get();
        if (r <= 0) return false;
        AABB box = new AABB(x - r, level.getMinBuildHeight(), z - r, x + r, level.getMaxBuildHeight(), z + r);
        return !level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && m.getType().is(DANGEROUS)).isEmpty();
    }

    /** A wild raptor, not a pet. */
    static boolean wildRaptor(Entity e) {
        return e instanceof Mob && e.getType().is(RAPTORS) && !(e instanceof OwnableEntity o && o.getOwnerUUID() != null);
    }

    static boolean calm(Entity raptor) {
        return raptor.getPersistentData().getLong(CALM_UNTIL) > raptor.level().getGameTime();
    }

    /** Never off with a player in its talons: it would only drop them from the sky. */
    @SubscribeEvent
    public static void mount(EntityMountEvent e) {
        if (e.isMounting() && e.getEntityMounting() instanceof Player && wildRaptor(e.getEntityBeingMounted())) e.setCanceled(true);
    }

    /** One strike at a player, then it loses interest in players and climbs away. */
    @SubscribeEvent
    public static void struck(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof Player) || e.getEntity().level().isClientSide()) return;
        Entity by = e.getSource().getEntity();
        if (!wildRaptor(by)) return;
        Mob raptor = (Mob) by;
        raptor.getPersistentData().putLong(CALM_UNTIL, raptor.level().getGameTime() + 20L * ClanwildConfig.RAPTOR_CALM.get());
        letGo(raptor);
        Vec3 away = raptor.position().subtract(e.getEntity().position()).multiply(1, 0, 1);
        away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
        raptor.setDeltaMovement(away.x * 0.8, 0.9, away.z * 0.8);
        raptor.hurtMarked = true;
    }

    /** A calm raptor goes for no player. */
    @SubscribeEvent
    public static void targeted(LivingChangeTargetEvent e) {
        LivingEntity t = e.getNewAboutToBeSetTarget();
        if (t instanceof Player && wildRaptor(e.getEntity()) && calm(e.getEntity())) e.setCanceled(true);
    }

    /** And keeps none it already had (a target set by other means, or one held from before the strike). */
    @SubscribeEvent
    public static void ticked(EntityTickEvent.Post e) {
        Entity ent = e.getEntity();
        if ((ent.tickCount + ent.getId()) % 10 != 0 || ent.level().isClientSide() || !wildRaptor(ent) || !calm(ent)) return;
        Mob raptor = (Mob) ent;
        if (raptor.getTarget() instanceof Player) letGo(raptor);
    }

    /** Drops its target and any player it holds; a player let go floats down. */
    static void letGo(Mob raptor) {
        if (raptor.getTarget() instanceof Player) raptor.setTarget(null);
        if (raptor.getLastHurtByMob() instanceof Player) raptor.setLastHurtByMob(null);
        for (Entity held : java.util.List.copyOf(raptor.getPassengers())) {
            if (!(held instanceof Player p)) continue;
            p.stopRiding();
            p.resetFallDistance();
            p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 8, 0, false, false));
        }
    }
}
