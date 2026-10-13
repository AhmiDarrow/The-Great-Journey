package com.ninjacat.greatjourney.clanlands.life.quests;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Every duty can be finished: the herbs, prey and gifts it names are real items, and a herb's plant counts too. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class DutyItemsGameTests {
    private DutyItemsGameTests() {}

    @GameTest(template = "empty")
    public static void everyHerbAndGiftIsARealItem(GameTestHelper h) {
        List<String> unknown = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String[] herb : Tasks.HERBS) ids.addAll(List.of(herb[0].split("\\|")));
        ids.addAll(List.of(Tasks.MOSS.split("\\|")));
        ids.addAll(List.of(Tasks.MOSS_BALL, "warriorcats_events:traveling_herbs", "warriorcats_events:shredded_meat"));
        for (String id : ids) {
            if (BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)) == Items.AIR) unknown.add(id);
        }
        h.assertTrue(unknown.isEmpty(), "duties ask for items that don't exist: " + unknown);
        for (String tag : List.of(Tasks.PREY_TAG, Tasks.FISH_TAG)) {
            var key = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, ResourceLocation.parse(tag.substring(1)));
            h.assertTrue(BuiltInRegistries.ITEM.getTag(key).map(t -> t.size() > 0).orElse(false), tag + " names some items");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aHerbsPlantCountsAndOldDaysAreForgotten(GameTestHelper h) {
        ServerPlayer p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes("duty_items_test".getBytes()), "duty_items_test"));
        QuestState.reset(p);
        try {
            var plant = BuiltInRegistries.ITEM.get(ResourceLocation.parse("warriorcats_events:yarrow"));
            var leaves = BuiltInRegistries.ITEM.get(ResourceLocation.parse("warriorcats_events:yarrow_leaves"));
            p.getInventory().add(new ItemStack(plant, 2));
            p.getInventory().add(new ItemStack(leaves, 1));
            h.assertTrue(Bag.count(p, Tasks.HERBS[0][0]) == 3, "yarrow leaves and the yarrow plant both count, got " + Bag.count(p, Tasks.HERBS[0][0]));

            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            QuestState.markCompletedToday(p, a, 10);
            QuestState.markCompletedToday(p, b, 11);
            h.assertTrue(QuestState.completedToday(p, b, 11) && !QuestState.completedToday(p, a, 11), "only today's help counts");
            h.assertTrue(QuestState.root(p).getCompound("doneDay").size() == 1, "yesterday's entries are dropped");
            h.succeed();
        } finally {
            QuestState.reset(p);
            p.getInventory().clearContent();
        }
    }
}
