package com.ninjacat.greatjourney.clanwild.verification;

import com.ninjacat.greatjourney.clanwild.SpeedStrike;
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
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Clanwild in a real server: the speed strike, the spawn removals, and the Warrior Cats Events tag entries. */
@GameTestHolder("clanwild")
@PrefixGameTestTemplate(false)
public final class ClanwildGameTests {
    private static final Set<String> REMOVED = Set.of("naturalist:bear", "naturalist:rhino", "naturalist:elephant",
            "naturalist:catfish", "alexsmobs:komodo_dragon", "alexsmobs:tiger", "alexsmobs:blobfish");

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
            h.assertTrue(blackBear, "Naturalist black bears still spawn somewhere");
        }
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
