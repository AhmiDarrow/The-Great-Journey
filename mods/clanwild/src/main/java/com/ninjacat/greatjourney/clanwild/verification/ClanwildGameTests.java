package com.ninjacat.greatjourney.clanwild.verification;

import com.ninjacat.greatjourney.clanwild.NoMonsters;
import com.ninjacat.greatjourney.clanwild.SpeedStrike;
import com.ninjacat.greatjourney.clanwild.Wildlife;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Clanwild in a real server: the speed strike, the spawn removals, no monsters, and the Warrior Cats Events tag entries. */
@GameTestHolder("clanwild")
@PrefixGameTestTemplate(false)
public final class ClanwildGameTests {
    private static final Set<String> REMOVED = Set.of("naturalist:bear", "naturalist:rhino", "naturalist:elephant",
            "naturalist:catfish", "alexsmobs:komodo_dragon", "alexsmobs:tiger", "alexsmobs:blobfish",
            // big game that doesn't belong in the Clans' woods
            "naturalist:tiger", "naturalist:lion", "naturalist:zebra", "naturalist:giraffe", "naturalist:hippo",
            "naturalist:mammoth", "naturalist:ostrich");

    private ClanwildGameTests() {}

    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    @SuppressWarnings({"removal", "deprecation"})
    private static ServerPlayer player(GameTestHelper h) {
        return h.makeMockServerPlayerInLevel();
    }

    @GameTest(template = "empty")
    public static void speedStrikeMath(GameTestHelper h) {
        h.assertTrue(SpeedStrike.bonusFraction(0.20, 0.22, 2.0, 0.5) == 0.0, "Walking pace gets no bonus");
        double sprint = SpeedStrike.bonusFraction(0.28, 0.22, 2.0, 0.5);
        h.assertTrue(Math.abs(sprint - 0.12) < 1e-9, "A sprint (0.28 b/t) adds 12%, got " + sprint);
        double leap = SpeedStrike.bonusFraction(0.40, 0.22, 2.0, 0.5);
        h.assertTrue(Math.abs(leap - 0.36) < 1e-9, "A leap (0.40 b/t) adds 36%, got " + leap);
        h.assertTrue(SpeedStrike.bonusFraction(1.5, 0.22, 2.0, 0.5) == 0.5, "The bonus stops at the cap");
        h.assertTrue(SpeedStrike.bonusFraction(10.0, 0.22, 2.0, 0.5) == 0.0, "A teleport is not a charge");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aRunningHitLandsHarder(GameTestHelper h) {
        ServerPlayer player = player(h);
        try {
            BlockPos base = h.absolutePos(new BlockPos(1, 2, 1));
            player.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            SpeedStrike.forget(player);
            SpeedStrike.record(player);
            SpeedStrike.record(player);
            Pig still = h.spawn(EntityType.PIG, new BlockPos(1, 2, 1));
            float before = still.getHealth();
            still.hurt(player.damageSources().playerAttack(player), 2.0f);
            h.assertTrue(Math.abs(before - still.getHealth() - 2.0f) < 1e-4, "Standing still, a 2-damage hit does 2, did " + (before - still.getHealth()));

            player.setPos(player.getX() + 0.47, player.getY(), player.getZ());   // one tick of a leap
            SpeedStrike.record(player);
            player.setPos(player.getX() + 50, player.getY(), player.getZ());     // a teleport right after
            SpeedStrike.record(player);
            h.assertTrue(Math.abs(SpeedStrike.recentSpeed(player) - 0.47) < 1e-6, "The leap is remembered and the teleport ignored, got " + SpeedStrike.recentSpeed(player));
            Pig struck = h.spawn(EntityType.PIG, new BlockPos(2, 2, 2));
            before = struck.getHealth();
            struck.hurt(player.damageSources().playerAttack(player), 2.0f);
            float dealt = before - struck.getHealth();
            h.assertTrue(Math.abs(dealt - 3.0f) < 1e-4, "After a leap the same hit does +50% (3), did " + dealt);
            h.succeed();
        } finally {
            SpeedStrike.forget(player);
            h.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    @GameTest(template = "empty")
    public static void onlyACatsOwnPawsCount(GameTestHelper h) {
        ServerPlayer player = player(h);
        try {
            BlockPos base = h.absolutePos(new BlockPos(1, 2, 1));
            player.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            SpeedStrike.forget(player);
            SpeedStrike.record(player);
            var boat = EntityType.BOAT.create(h.getLevel());
            boat.moveTo(player.getX(), player.getY(), player.getZ());
            h.getLevel().addFreshEntity(boat);
            h.assertTrue(player.startRiding(boat, true), "the player sits in the boat");
            player.setPos(player.getX() + 0.6, player.getY(), player.getZ());   // carried along at speed
            SpeedStrike.record(player);
            h.assertTrue(SpeedStrike.recentSpeed(player) == 0.0, "riding gives no speed strike, got " + SpeedStrike.recentSpeed(player));
            player.stopRiding();                                                // stepping out of the boat moves it a little
            boat.discard();
            SpeedStrike.forget(player);
            SpeedStrike.record(player);
            player.setPos(player.getX() + 0.4, player.getY(), player.getZ());   // a leap on its own paws
            SpeedStrike.record(player);
            h.assertTrue(Math.abs(SpeedStrike.recentSpeed(player) - 0.4) < 1e-6, "its own leap counts, got " + SpeedStrike.recentSpeed(player));
            h.succeed();
        } finally {
            SpeedStrike.forget(player);
            h.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    @GameTest(template = "empty")
    public static void duplicateAnimalsSpawnOnce(GameTestHelper h) {
        var access = h.getLevel().registryAccess();
        boolean both = ModList.get().isLoaded("naturalist") && ModList.get().isLoaded("alexsmobs");
        var modifiers = access.registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS);
        for (String m : List.of("clanwild:remove_naturalist_duplicates", "clanwild:remove_alexsmobs_duplicates")) {
            h.assertTrue(modifiers.containsKey(id(m)) == both, m + " is loaded exactly when both mods are (both=" + both + ")");
        }
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        boolean grizzly = false;
        boolean blackBear = false;
        for (Biome biome : biomes) {
            for (MobCategory cat : MobCategory.values()) {
                for (var data : biome.getMobSettings().getMobs(cat).unwrap()) {
                    String type = BuiltInRegistries.ENTITY_TYPE.getKey(data.type).toString();
                    h.assertFalse(REMOVED.contains(type), type + " still spawns in " + biomes.getKey(biome));
                    grizzly |= type.equals("alexsmobs:grizzly_bear");
                    blackBear |= type.equals("naturalist:black_bear");
                }
            }
        }
        if (both) {
            // the kept side still spawns, so the removals were not vacuous
            h.assertTrue(grizzly, "Alex's Mobs grizzly bears still spawn somewhere");
        }
        // black bears were the kept side until Kiki met too many dangerous animals (2026-10-18): now out of place
        if (ModList.get().isLoaded("naturalist")) h.assertFalse(blackBear, "Naturalist black bears no longer spawn");
        // too few rabbits on the moor (playtest 2026-10-19): the Clans' open land and woods get more
        for (String b : List.of("minecraft:meadow", "minecraft:plains", "minecraft:forest")) {
            int weight = 0;
            for (var data : biomes.get(id(b)).getMobSettings().getMobs(MobCategory.CREATURE).unwrap()) {
                if (data.type == net.minecraft.world.entity.EntityType.RABBIT) weight += data.getWeight().asInt();
            }
            h.assertTrue(weight >= 6, "rabbits spawn often in " + b + " (weight " + weight + ")");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyWarriorCatsCreaturesSpawn(GameTestHelper h) {
        var level = h.getLevel();
        for (EntityType<?> monster : List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER, EntityType.PHANTOM, EntityType.PILLAGER)) {
            for (MobSpawnType how : List.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION, MobSpawnType.SPAWNER, MobSpawnType.PATROL, MobSpawnType.REINFORCEMENT)) {
                h.assertTrue(NoMonsters.blocked(monster, level, how), monster.getDescriptionId() + " is turned away (" + how + ")");
            }
            h.assertFalse(NoMonsters.blocked(monster, level, MobSpawnType.COMMAND), "a command still summons " + monster.getDescriptionId());
            h.assertFalse(NoMonsters.blocked(monster, level, MobSpawnType.SPAWN_EGG), "a spawn egg still hatches " + monster.getDescriptionId());
        }
        for (EntityType<?> kept : List.of(EntityType.FOX, EntityType.WOLF, EntityType.RABBIT, EntityType.COD)) {
            h.assertFalse(NoMonsters.blocked(kept, level, MobSpawnType.NATURAL), kept.getDescriptionId() + " still spawns");
        }
        var badger = BuiltInRegistries.ENTITY_TYPE.getOptional(id("warriorcats_events:badger"));
        badger.ifPresent(t -> h.assertFalse(NoMonsters.blocked(t, level, MobSpawnType.NATURAL), "badgers still spawn"));
        if (ModList.get().isLoaded("naturalist")) {
            h.assertTrue(BuiltInRegistries.ENTITY_TYPE.get(id("naturalist:tiger")).is(NoMonsters.OUT_OF_PLACE), "a saved tiger is removed on load");
            h.assertFalse(BuiltInRegistries.ENTITY_TYPE.get(id("naturalist:deer")).is(NoMonsters.OUT_OF_PLACE), "deer stay");
        }
        var nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        if (nether != null) h.assertFalse(NoMonsters.blocked(EntityType.ZOMBIFIED_PIGLIN, nether, MobSpawnType.NATURAL), "the Nether keeps its own");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dangerousAnimalsKeepTheirDistance(GameTestHelper h) {
        h.assertTrue(EntityType.WOLF.is(Wildlife.DANGEROUS) && EntityType.FOX.is(Wildlife.DANGEROUS), "wolves and foxes are dangerous");
        h.assertFalse(EntityType.RABBIT.is(Wildlife.DANGEROUS), "rabbits are prey, not a danger");
        if (ModList.get().isLoaded("naturalist")) {
            for (String big : List.of("naturalist:bear", "naturalist:black_bear", "naturalist:alligator", "naturalist:komodo_dragon")) {
                h.assertTrue(BuiltInRegistries.ENTITY_TYPE.get(id(big)).is(NoMonsters.OUT_OF_PLACE), big + " does not belong in the Clans' woods");
            }
        }
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        h.assertFalse(Wildlife.crowded(h.getLevel(), at.getX() + 0.5, at.getZ() + 0.5 + 4000), "open woods far from any danger");
        h.spawn(EntityType.WOLF, new BlockPos(1, 2, 1));
        h.assertTrue(Wildlife.crowded(h.getLevel(), at.getX() + 20.5, at.getZ() + 0.5), "no second danger beside a wolf");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void warriorCatsTagsTakeOurEntries(GameTestHelper h) {
        TagKey<EntityType<?>> predators = TagKey.create(Registries.ENTITY_TYPE, id("warriorcats_events:predator_mobs"));
        h.assertTrue(EntityType.FOX.is(predators) && EntityType.WOLF.is(predators), "Foxes and wolves count as predators");
        if (ModList.get().isLoaded("naturalist")) {
            TagKey<Item> prey = TagKey.create(Registries.ITEM, id("warriorcats_events:prey"));
            Item venison = BuiltInRegistries.ITEM.get(id("naturalist:venison"));
            h.assertTrue(venison.builtInRegistryHolder().is(prey), "Naturalist venison is Warrior Cats prey");
            TagKey<EntityType<?>> preyMobs = TagKey.create(Registries.ENTITY_TYPE, id("warriorcats_events:additional_prey"));
            h.assertTrue(BuiltInRegistries.ENTITY_TYPE.get(id("naturalist:rat")).is(preyMobs), "Naturalist rats are prey animals");
        }
        if (ModList.get().isLoaded("alexsmobs")) {
            TagKey<Item> rawFish = TagKey.create(Registries.ITEM, id("c:foods/raw_fish"));
            h.assertTrue(BuiltInRegistries.ITEM.get(id("alexsmobs:raw_catfish")).builtInRegistryHolder().is(rawFish), "Alex's Mobs catfish is c:foods/raw_fish");
        }
        h.succeed();
    }
}
