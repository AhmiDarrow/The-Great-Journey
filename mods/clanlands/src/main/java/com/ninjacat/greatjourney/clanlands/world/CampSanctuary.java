package com.ninjacat.greatjourney.clanlands.world;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * No predator or monster spawns inside a Clan camp, neither when its chunks are first generated nor later in the
 * dark: a fox that spawned on the camp floor would kill the Clan's cats before anyone arrived. Predators are the
 * {@code clanlands:camp_predators} entity tag (foxes, wolves, badgers, eagles and the like). Spawns the game makes on
 * purpose (spawn eggs, commands, the Clans' own events) are left alone.
 */
public final class CampSanctuary {
    private CampSanctuary() {}

    public static final TagKey<EntityType<?>> PREDATORS =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("clanlands", "camp_predators"));

    private static final Set<Landmark.Kind> CAMPS = EnumSet.of(Landmark.Kind.STONE_HOLLOW, Landmark.Kind.WINDCLAN_HOLLOW,
            Landmark.Kind.RIVERCLAN_CAMP, Landmark.Kind.SHADOWCLAN_CAMP, Landmark.Kind.THUNDERCLAN_RAVINE);

    private record Cached(ClanWorld world, List<Landmark> camps) {}

    private static volatile Cached cached = new Cached(null, List.of());

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CampSanctuary::check);
        NeoForge.EVENT_BUS.addListener(CampSanctuary::joined);
        NeoForge.EVENT_BUS.addListener(CampSanctuary::ticked);
        NeoForge.EVENT_BUS.addListener(CampSanctuary::targeted);
        NeoForge.EVENT_BUS.addListener(CampSanctuary::arrived);
        NeoForge.EVENT_BUS.addListener(CampSanctuary::fell);
    }

    // predators already about: the spawn check above keeps new ones out, but a fox spawned with its chunk before the
    // check could see it, one loaded from disk, or one that wanders in through the way in would still be on the camp
    // floor when a cat arrives (a playtest apprentice was killed by a fox at the lake ThunderClan camp on arrival)

    /**
     * Whether the sanctuary moves this entity out of camps: wild predators and monsters, never pets, mounts or named
     * mobs. A wild one carrying something off (Warrior Cats Events' golden eagle lifts kits and players as its
     * passengers, then drops them) is not a mount: the playtest saw one take a kit and a player from the WindClan hollow.
     */
    public static boolean unwelcome(Entity e) {
        if (!(e instanceof Mob mob) || e.level().isClientSide()) return false;
        if (!e.getType().is(PREDATORS) && e.getType().getCategory() != MobCategory.MONSTER) return false;
        if (mob instanceof WCatEntity || (mob instanceof OwnableEntity o && o.getOwnerUUID() != null)) return false;   // Clan cats, pets
        return !mob.hasCustomName() && !mob.isLeashed() && !mob.isPassenger() && !(mob.getControllingPassenger() instanceof Player);
    }

    /** A kit is never hurt by a fall inside a camp (dropped by an eagle, or off a den roof or a rock): the camp is its home. */
    public static boolean sparedFall(Entity e, List<Landmark> camps) {
        return e instanceof WCatEntity cat && (cat.isBaby() || cat.getRank() == WCatEntity.Rank.KIT) && campAt(e.getX(), e.getZ(), camps) != null;
    }

    private static void fell(LivingFallEvent e) {
        if (clanOverworld(e.getEntity().level()) && sparedFall(e.getEntity(), camps())) e.setCanceled(true);
    }

    private static boolean clanOverworld(Level level) {
        return !level.isClientSide() && ClanWorld.overworldSource() != null && level.dimension() == Level.OVERWORLD;
    }

    private static void joined(EntityJoinLevelEvent e) {
        if (clanOverworld(e.getLevel()) && unwelcome(e.getEntity())) evict((Mob) e.getEntity(), camps());
    }

    private static void ticked(EntityTickEvent.Post e) {
        Entity ent = e.getEntity();
        if ((ent.tickCount + ent.getId()) % 20 != 0 || !clanOverworld(ent.level()) || !unwelcome(ent)) return;
        Mob mob = (Mob) ent;
        if (!evict(mob, camps()) && mob.getTarget() != null && !mayTarget(mob, mob.getTarget(), camps())) mob.setTarget(null);
    }

    private static void targeted(LivingChangeTargetEvent e) {
        if (clanOverworld(e.getEntity().level()) && unwelcome(e.getEntity()) && e.getNewAboutToBeSetTarget() != null
                && !mayTarget((Mob) e.getEntity(), e.getNewAboutToBeSetTarget(), camps())) {
            e.setCanceled(true);
        }
    }

    /** A player coming into a camp finds it clear: every predator or monster in it is moved out at once. */
    private static void arrived(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.tickCount % 10 != 0 || !clanOverworld(p.level())) return;
        Landmark l = campAt(p.getX(), p.getZ(), camps());
        if (l == null) return;
        AABB box = new AABB(l.x() - l.radius(), p.getY() - 32, l.z() - l.radius(), l.x() + l.radius(), p.getY() + 32, l.z() + l.radius());
        for (Mob mob : p.level().getEntitiesOfClass(Mob.class, box, CampSanctuary::unwelcome)) evict(mob, List.of(l));
    }

    /** Whether a predator may go for a target: never one standing in a camp. */
    public static boolean mayTarget(Mob mob, LivingEntity target, List<Landmark> camps) {
        return campAt(target.getX(), target.getZ(), camps) == null;
    }

    /**
     * Moves a mob standing in one of {@code camps} out past the camp's edge (straight away from its middle, onto the
     * ground there) and makes it forget its target; one whose way out is not loaded is removed instead, unless it has
     * to be kept. Returns whether it was in a camp.
     */
    public static boolean evict(Mob mob, List<Landmark> camps) {
        Landmark l = campAt(mob.getX(), mob.getZ(), camps);
        if (l == null) return false;
        double dx = mob.getX() - l.x(), dz = mob.getZ() - l.z(), d = Math.hypot(dx, dz);
        if (d < 1e-3) {
            dx = 1;
            dz = 0;
            d = 1;
        }
        double out = l.radius() + 6;
        int x = (int) Math.floor(l.x() + dx / d * out), z = (int) Math.floor(l.z() + dz / d * out);
        Level level = mob.level();
        mob.setTarget(null);
        // what it carries stays in the camp and floats down to the ground (a kit or a player an eagle has picked up)
        for (Entity carried : List.copyOf(mob.getPassengers())) {
            if (carried.getType().is(PREDATORS) || carried.getType().getCategory() == MobCategory.MONSTER) continue;   // a jockey rides out with it
            carried.stopRiding();
            carried.resetFallDistance();
            if (carried instanceof LivingEntity le) le.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 8, 0, false, false));
        }
        if (!level.hasChunk(x >> 4, z >> 4)) {
            if (!mob.isPersistenceRequired()) mob.discard();
            return true;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        mob.getNavigation().stop();
        mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        mob.moveTo(x + 0.5, y, z + 0.5, mob.getYRot(), mob.getXRot());     // (also safe while its chunk is loading)
        return true;
    }

    /** The camp a column lies in (its clearing and wall), or null. */
    public static Landmark campAt(double x, double z, List<Landmark> camps) {
        for (Landmark l : camps) {
            double dx = x - l.x(), dz = z - l.z();
            if (dx * dx + dz * dz <= l.radius() * l.radius()) return l;
        }
        return null;
    }

    private static void check(MobSpawnEvent.SpawnPlacementCheck e) {
        MobSpawnType why = e.getSpawnType();
        if (why != MobSpawnType.NATURAL && why != MobSpawnType.CHUNK_GENERATION && why != MobSpawnType.PATROL
                && why != MobSpawnType.REINFORCEMENT) {
            return;
        }
        EntityType<?> type = e.getEntityType();
        if (!type.is(PREDATORS) && type.getCategory() != MobCategory.MONSTER) return;
        if (ClanWorld.overworldSource() == null || e.getLevel().getLevel().dimension() != Level.OVERWORLD) return;
        if (inCamp(e.getPos().getX() + 0.5, e.getPos().getZ() + 0.5)) e.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }

    /** Whether a column is inside a Clan camp (its clearing and wall) in either book world. */
    public static boolean inCamp(double x, double z) {
        return campAt(x, z, camps()) != null;
    }

    /** The Clan camps of the current world. */
    public static List<Landmark> camps() {
        ClanWorld w = ClanWorld.current();
        if (w == null) return List.of();
        Cached c = cached;
        if (c.world() != w) {
            List<Landmark> out = new ArrayList<>();
            for (ClanMap m : w.maps()) {
                for (Landmark l : m.landmarks()) if (CAMPS.contains(l.kind())) out.add(l);
            }
            c = new Cached(w, List.copyOf(out));
            cached = c;
        }
        return c.camps();
    }
}
