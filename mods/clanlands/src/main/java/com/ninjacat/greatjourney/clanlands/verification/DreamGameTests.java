package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.life.dream.DreamClearing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The StarClan dream: the dimension exists, and the clearing has its herb patch and its four teachers. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class DreamGameTests {
    private DreamGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theDreamClearingIsBuilt(GameTestHelper h) {
        // the GameTest server only creates its flat test world, so check the dimension is registered, and build the
        // clearing in the test world instead (a normal world loads clanlands:starclan from the data pack)
        var reg = h.getLevel().registryAccess();
        h.assertTrue(reg.registryOrThrow(Registries.DIMENSION_TYPE).containsKey(DreamClearing.DREAM.location()), "the dream's dimension type is registered");
        var res = h.getLevel().getServer().getResourceManager().getResource(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("clanlands", "dimension/starclan.json"));
        h.assertTrue(res.isPresent(), "the dream dimension file is in the data pack");
        try (var reader = res.get().openAsReader()) {
            var json = com.google.gson.JsonParser.parseReader(reader);
            var stem = net.minecraft.world.level.dimension.LevelStem.CODEC.parse(
                    net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, reg), json);
            h.assertTrue(stem.result().isPresent(), "the dream dimension decodes: " + stem.error().map(Object::toString).orElse(""));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        ServerLevel dream = h.getLevel();
        DreamClearing.ensure(dream);
        h.assertTrue(DreamClearing.built(dream), "the clearing is built");
        int herbs = 0;
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            var id = BuiltInRegistries.BLOCK.getKey(dream.getBlockState(new BlockPos(x, 5, z)).getBlock());
            if (id.getNamespace().equals("warriorcats_events") && DreamClearing.HERB_BLOCKS.contains(id.getPath())) herbs++;
        }
        h.assertTrue(herbs == DreamClearing.HERB_BLOCKS.size(), "all " + DreamClearing.HERB_BLOCKS.size() + " herbs grow in the patch, found " + herbs);
        // the teachers come once their chunks' entities have loaded (a moment after the blocks), as on a real visit
        h.succeedWhen(() -> {
            DreamClearing.ensure(dream);
            for (String name : new String[] {"Bluestar", "Lionheart", "Spottedleaf", "Yellowfang"}) {
                h.assertTrue(DreamClearing.find(dream, name) != null, name + " is in the clearing");
            }
            DreamClearing.ensure(dream);                                     // a second visit adds nobody
            int cats = starClanCats(dream);
            h.assertTrue(cats == 4, "exactly four StarClan cats, found " + cats);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aDoubledTeacherIsSentBack(GameTestHelper h) {
        ServerLevel dream = h.getLevel();
        h.succeedWhen(() -> {
            DreamClearing.ensure(dream);
            h.assertTrue(starClanCats(dream) == 4, "the four teachers are there first");
            // a teacher counted twice (as a login before the entities loaded once could cause)
            Entity blue = DreamClearing.find(dream, "Bluestar");
            WCatEntity twin = ModEntities.WCAT.get().create(dream);
            twin.moveTo(blue.getX(), blue.getY(), blue.getZ());
            twin.setCustomName(blue.getCustomName());
            twin.addTag(DreamClearing.TAG);
            twin.setNoAi(true);
            dream.addFreshEntity(twin);
            h.assertTrue(starClanCats(dream) == 5, "the twin is there");
            DreamClearing.ensure(dream);
            h.assertTrue(starClanCats(dream) == 4, "ensure sends the twin back, leaving " + starClanCats(dream));
        });
    }

    private static int starClanCats(ServerLevel dream) {
        return dream.getEntities((Entity) null, new AABB(-40, 0, -40, 40, 40, 40),
                e -> e.getTags().contains(DreamClearing.TAG) && !e.isRemoved()).size();
    }
}
