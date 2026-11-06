package com.ninjacat.greatjourney.clanwild;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * Only Warrior Cats creatures in the Clans' world: monsters (zombies, skeletons, creepers, spiders, phantoms,
 * pillager patrols, any mod's monster) never spawn on their own outside the Nether and the End, whether naturally,
 * with new land, from a spawner or as reinforcements. Commands, spawn eggs and our own events still make them.
 * Badgers, foxes and eagles are creatures, not monsters, so they still come. Big game that doesn't belong in the
 * Clans' woods ({@link #OUT_OF_PLACE}: tigers, lions...) no longer spawns (a biome modifier on the same tag), and any
 * already saved in a world is gone when it loads.
 */
public final class NoMonsters {
    private NoMonsters() {}

    public static final TagKey<EntityType<?>> OUT_OF_PLACE =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(Clanwild.MOD_ID, "out_of_place"));

    /** Natural spawning asks this first: refusing here costs nothing, the mob is never made. */
    @SubscribeEvent
    public static void placement(MobSpawnEvent.SpawnPlacementCheck e) {
        if (blocked(e.getEntityType(), e.getLevel(), e.getSpawnType())) e.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }

    /** Everything else (spawners, patrols, phantoms, reinforcements, chunk generation) passes through here. */
    @SubscribeEvent
    public static void finalizeSpawn(FinalizeSpawnEvent e) {
        if (blocked(e.getEntity().getType(), e.getLevel(), e.getSpawnType())) e.setSpawnCancelled(true);
    }

    /** A tiger or lion saved before the removal (Kiki's first world had them) leaves as it loads. */
    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent e) {
        if (e.loadedFromDisk() && !e.getLevel().isClientSide() && e.getEntity().getType().is(OUT_OF_PLACE)) e.setCanceled(true);
    }

    /** Whether a mob of this type, spawning this way here, is turned away. */
    public static boolean blocked(EntityType<?> type, LevelAccessor level, MobSpawnType how) {
        if (ClanwildConfig.MONSTERS.get() || type.getCategory() != MobCategory.MONSTER) return false;
        if (level instanceof ServerLevelAccessor s) {
            var dim = s.getLevel().dimension();
            if (dim == Level.NETHER || dim == Level.END) return false;
        }
        return switch (how) {
            case NATURAL, CHUNK_GENERATION, SPAWNER, STRUCTURE, PATROL, REINFORCEMENT, JOCKEY, TRIAL_SPAWNER -> true;
            default -> false;
        };
    }
}
