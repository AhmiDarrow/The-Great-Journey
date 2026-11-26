package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.block.entity.FreshkillPileBlockEntity;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Every quest's place on the Clan map and the one followed ({@link QuestMarks}), what the journal says of a task, and
 * prey handed in landing on the fresh-kill pile ({@code Bag.handOver}).
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class MapJournalGameTests {
    private MapJournalGameTests() {}

    private static ServerPlayer player(GameTestHelper h, String name) {
        ServerPlayer p = BookGameTests.player(h, name);
        p.getInventory().clearContent();
        return p;
    }

    private static Task patrol(UUID giver) {
        return new Task(Task.Kind.BORDER_PATROL, "Border patrol", "Walk the borders.", "Sandpelt", giver, false,
                new ArrayList<>(List.of(Task.Objective.visit("First point", 100, 100, 8), Task.Objective.visit("Second point", 200, 100, 8),
                        Task.Objective.visit("Third point", 200, 200, 8))), 4, 25, "warriorcats_events:traveling_herbs", 2);
    }

    @GameTest(template = "empty")
    public static void everyQuestIsOnTheMapAndOneIsFollowed(GameTestHelper h) {
        ServerPlayer p = player(h, "map_marks");
        WCatEntity giver = ModEntities.WCAT.get().create(h.getLevel());
        try {
            BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
            giver.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            giver.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
            giver.setCustomName(Component.literal("Sandpelt"));
            giver.setNoAi(true);
            h.getLevel().addFreshEntity(giver);
            ClanWorld w = ClanWorld.layoutOnly(20261012L);
            Task walk = patrol(giver.getUUID());
            walk.objectives.get(0).progress = 1;
            List<MapMark> marks = QuestMarks.of(p, w, walk);
            h.assertTrue(marks.size() == 2 && marks.get(0).number() == 2 && marks.get(1).number() == 3,
                    "each patrol point still to reach is marked, keeping its number: " + marks);
            h.assertTrue(marks.stream().allMatch(m -> m.quest().equals(QuestMarks.id(walk))), "under the journal's id for the task");
            for (var o : walk.objectives) o.progress = o.goal;
            marks = QuestMarks.of(p, w, walk);
            h.assertTrue(marks.size() == 1 && marks.get(0).label().equals("Take it back to Sandpelt")
                    && Math.abs(marks.get(0).x() - giver.getX()) < 0.01, "then the cat it goes back to is marked, where they are");
            Task hunt = new Task(Task.Kind.HUNT, "A hunting patrol", "Hunt.", "Sandpelt", giver.getUUID(), false,
                    new ArrayList<>(List.of(Task.Objective.catchPrey("Catch 3 prey", "any", 3))), 5, 30, "", 0);
            marks = QuestMarks.of(p, w, hunt);
            h.assertTrue(marks.size() == 1 && marks.get(0).label().contains("back to Sandpelt when done"), "a task with no place marks its giver");

            var two = List.of(MapMark.at("story_hunt", "Story", 1, 1), MapMark.at("gathering", "The Gathering", 2, 2));
            h.assertTrue(QuestMarks.followed(p, two).equals("story_hunt"), "the story's step is followed unless another is chosen");
            h.assertTrue(QuestMarks.follow(p, "gathering") && QuestMarks.followed(p, two).equals("gathering"), "Show on map chooses");
            h.assertTrue(QuestMarks.followed(p, two.subList(0, 1)).equals("story_hunt"), "once the chosen one is gone, the first again");
            h.assertFalse(QuestMarks.follow(p, " "), "nothing is no choice");

            double[] site = Founding.siteNear(w, w.lake.spawn()[0], w.lake.spawn()[1]);
            h.assertTrue(site != null && Founding.siteProblem(w, site[0], site[1]) == null, "land for a new Clan is found and can be built on");

            var data = new ClanPayloads.MapData(0, List.of("The lake"), 0, 0, 4, 1, new byte[1], new byte[1], List.of(), List.of(), true, 1, 2, 3,
                    List.of(new MapMark("q:1", "x".repeat(400), 5, 6, 3, true)),
                    List.of(new ClanPayloads.MapSpot(ClanPayloads.MapSpot.EVENT, "Latest news", 7, 8)));
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            ClanPayloads.MapData.STREAM_CODEC.encode(buf, data);
            var back = ClanPayloads.MapData.STREAM_CODEC.decode(buf);
            h.assertTrue(back.marks().size() == 1 && back.marks().get(0).followed() && back.marks().get(0).number() == 3
                    && back.marks().get(0).label().length() == 256 && back.spots().equals(data.spots()) && buf.readableBytes() == 0,
                    "the marks and spots cross to the client, long words clipped");
        } finally {
            giver.discard();
            QuestState.reset(p);
            Dialog.forget(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theJournalTellsRewardsReadinessAndTheMarksOfStanding(GameTestHelper h) {
        ServerPlayer p = player(h, "map_journal");
        try {
            Task walk = patrol(UUID.randomUUID());
            String reward = QuestMarks.reward(walk);
            h.assertTrue(reward.contains("+4 standing") && reward.contains("25 experience") && reward.contains("2 × "), "the whole reward: " + reward);
            h.assertTrue(QuestMarks.ready(p, walk).isEmpty(), "not ready while there is walking to do");
            for (var o : walk.objectives) o.progress = o.goal;
            h.assertTrue(QuestMarks.ready(p, walk).equals("Ready: take it back to Sandpelt"), "then it says where to take it");
            Task bring = new Task(Task.Kind.HERBS, "Herbs", "Bring.", "Mossheart", UUID.randomUUID(), false,
                    new ArrayList<>(List.of(Task.Objective.bring("Bring 2 cod", "minecraft:cod", 2))), 4, 0, "", 0);
            h.assertTrue(QuestMarks.ready(p, bring).isEmpty(), "a hand-in is not ready with empty paws");
            p.getInventory().add(new ItemStack(Items.COD, 2));
            h.assertTrue(!QuestMarks.ready(p, bring).isEmpty(), "and is once they are carried");
            h.assertTrue(JournalView.LADDER.stream().map(JournalView.Rung::standing).toList().equals(List.of(40, 60, 120, 200)),
                    "the marks of standing are the game's own numbers");
        } finally {
            p.getInventory().clearContent();
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void questCatsAndTheLatestEventAreOnTheMap(GameTestHelper h) {
        ServerPlayer p = player(h, "map_spots");
        WCatEntity mentor = ModEntities.WCAT.get().create(h.getLevel());
        var at = h.absolutePos(new BlockPos(4, 2, 4));
        Friendship.reset();
        try {
            mentor.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            mentor.setCustomName(Component.literal("Sandpelt"));
            mentor.setNoAi(true);
            h.getLevel().addFreshEntity(mentor);
            h.assertTrue(QuestMarks.spots(p).isEmpty(), "a cat with no quest sees no cats on the map");
            QuestState.setStoryStep(p, 2);
            QuestState.setMentor(p, "Sandpelt", mentor.getUUID());
            var spots = QuestMarks.spots(p);
            h.assertTrue(spots.size() == 1 && spots.get(0).label().equals("Your mentor, Sandpelt") && Math.abs(spots.get(0).x() - mentor.getX()) < 0.01,
                    "the mentor is on it while they train the player: " + spots);
            QuestState.setStoryStep(p, QuestState.WARRIOR);
            h.assertTrue(QuestMarks.spots(p).isEmpty(), "and not once the training is done");
            h.assertTrue(Friendship.meet(p, com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan.RIVER, at), "a cat waits at the border");
            h.assertTrue(Friendship.met(p) == null && QuestMarks.spots(p).isEmpty(), "not on the map before they have been spoken to");
            Friendship.greeting(p, p);
            h.assertTrue(QuestMarks.spots(p).stream().anyMatch(s -> s.kind() == ClanPayloads.MapSpot.FRIEND), "then they are");
            UUID clan = UUID.randomUUID();
            var events = com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.class;
            long now = h.getLevel().getGameTime();
            com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.noticed(clan, at, "A badger!", now);
            var news = com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.latest(clan, now + 100);
            h.assertTrue(news != null && news.words().equals("A badger!") && news.x() == at.getX() + 0.5, "the latest event is kept with its place");
            h.assertTrue(com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.latest(clan, now + 20L * 60 * 6) == null,
                    "and is no longer news after a few minutes: " + events.getSimpleName());
        } finally {
            h.getLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(16), c -> c.getTags().contains(Friendship.TAG))
                    .forEach(net.minecraft.world.entity.Entity::discard);
            Friendship.reset();
            mentor.discard();
            QuestState.reset(p);
            Dialog.forget(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theBigMapZoomsAndMovesWithoutLosingItsEdges(GameTestHelper h) {
        var full = com.ninjacat.greatjourney.clanlands.life.rpg.MapView.FULL;
        h.assertTrue(java.util.Arrays.equals(full.whole(10, 20, 200), new int[] {10, 20, 200}), "unzoomed, the map fills its frame");
        var in = full.zoomAt(2, 0.25, 0.75);
        int[] w = in.whole(0, 0, 200);
        h.assertTrue(in.zoom() == 2 && w[2] == 400 && w[0] == -50 && w[1] == -150, "the point under the mouse stays under it: " + w[0] + "," + w[1]);
        h.assertTrue(in.zoomAt(0.1, 0.5, 0.5).equals(full), "zoomed all the way out, it is the whole map again");
        var far = in.drag(10, 10).whole(0, 0, 200);
        h.assertTrue(far[0] == 0 && far[1] == 0, "dragged far, it stops at the map's edge");
        h.assertTrue(full.zoomAt(1000, 0.5, 0.5).zoom() == com.ninjacat.greatjourney.clanlands.life.rpg.MapView.MAX_ZOOM, "and zooms only so far");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void preyHandedInGoesOnTheFreshKillPile(GameTestHelper h) {
        ServerPlayer p = player(h, "map_pantry");
        BlockPos rel = new BlockPos(3, 2, 2);
        h.setBlock(rel, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("warriorcats_events", "fresh_kill_pile")));
        try {
            h.assertTrue(h.getLevel().getBlockEntity(h.absolutePos(rel)) instanceof FreshkillPileBlockEntity, "a fresh-kill pile stands by the cat");
            var pile = ((FreshkillPileBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(rel))).getItemHandler();
            p.getInventory().add(new ItemStack(Items.COD, 3));
            p.getInventory().add(new ItemStack(Items.STICK, 2));
            Bag.handOver(p, Task.Objective.bring("Bring 2 prey", Tasks.ANY_PREY, 2));
            h.assertTrue(Bag.count(p, "minecraft:cod") == 1, "the prey asked for leaves the cat's paws");
            int onPile = 0;
            for (int i = 0; i < pile.getSlots(); i++) if (pile.getStackInSlot(i).is(Items.COD)) onPile += pile.getStackInSlot(i).getCount();
            h.assertTrue(onPile == 2, "and lies on the pile: " + onPile);
            Bag.handOver(p, Task.Objective.bring("Bring 2 sticks", "minecraft:stick", 2));
            for (int i = 0; i < pile.getSlots(); i++) h.assertFalse(pile.getStackInSlot(i).is(Items.STICK), "what is not prey does not go on the pile");
            for (int i = 0; i < pile.getSlots(); i++) pile.insertItem(i, new ItemStack(Items.RABBIT, 64), false);
            for (int i = 0; i < pile.getSlots(); i++) if (pile.getStackInSlot(i).is(Items.COD)) pile.getStackInSlot(i).grow(64 - pile.getStackInSlot(i).getCount());
            p.getInventory().add(new ItemStack(Items.COD, 1));
            Bag.handOver(p, Task.Objective.bring("Bring 2 prey", Tasks.ANY_PREY, 2));
            h.assertTrue(Bag.count(p, "minecraft:cod") == 2, "what a full pile cannot hold stays with the cat");
        } finally {
            h.setBlock(rel, net.minecraft.world.level.block.Blocks.AIR);
            p.getInventory().clearContent();
            QuestState.reset(p);
        }
        h.succeed();
    }
}
