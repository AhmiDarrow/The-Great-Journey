package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Cats that lead players ({@link Lead}): the stops come in order, the cat waits for a player who falls behind, and
 * it is given back to its Clan routine afterwards. And a kittypet's choice of Clan: the choices, the journal's "Find
 * the camp" with the way to it, and a guide's route that goes round the lake, not across it.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class LeadGameTests {
    private LeadGameTests() {}

    private static ServerPlayer player(GameTestHelper h, String name) {
        return net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
    }

    /** A stone floor at y 1 across the test's 16 x 16, so the cat walks on y 2. */
    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(x, 1, z, Blocks.STONE);
    }

    private static WCatEntity cat(GameTestHelper h, int x, int z) {
        WCatEntity cat = ModEntities.WCAT.get().create(h.getLevel());
        BlockPos at = h.absolutePos(new BlockPos(x, 2, z));
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        cat.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setRank(WCatEntity.Rank.WARRIOR);
        cat.setAgeInMoons(BookClans.ADULT_MOONS);
        h.getLevel().addFreshEntity(cat);
        cat.setHomePosition(at);
        cat.wanderCenter = at;
        cat.mode = WCatEntity.CatMode.SIT;                     // told to stay: leading must not lose that
        return cat;
    }

    private static void put(GameTestHelper h, ServerPlayer p, int x, int z) {
        BlockPos at = h.absolutePos(new BlockPos(x, 2, z));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private static Lead.Stop stop(GameTestHelper h, int x, int z, int index, List<Integer> reached) {
        BlockPos at = h.absolutePos(new BlockPos(x, 2, z));
        return new Lead.Stop(at.getX() + 0.5, at.getZ() + 0.5, 2, l -> reached.add(index));
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void aCatLeadsThroughItsStopsWaitingForItsPlayer(GameTestHelper h) {
        floor(h);
        WCatEntity cat = cat(h, 1, 1);
        BlockPos home = cat.getHomePosition();
        ServerPlayer p = player(h, "lead_test_a");
        put(h, p, 1, 14);                                      // thirteen blocks back: lagging from the start
        List<Integer> reached = new ArrayList<>();
        AtomicReference<Lead.End> ended = new AtomicReference<>();
        Lead lead = Lead.start(p, cat, List.of(stop(h, 8, 1, 0, reached), stop(h, 14, 7, 1, reached), stop(h, 14, 14, 2, reached)),
                (l, how) -> ended.set(how));
        h.assertTrue(lead != null, "a free cat can lead");
        h.assertTrue(cat.getTags().contains(BookClans.BUSY_TAG) && Lead.leading(cat), "a leading cat is busy");
        double startX = cat.getX(), startZ = cat.getZ();
        h.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    h.assertTrue(reached.isEmpty() && lead.waiting(), "the cat waits for a player who is far behind");
                    h.assertTrue(Math.hypot(cat.getX() - startX, cat.getZ() - startZ) < 2, "and does not walk on without them");
                    put(h, p, 2, 2);
                })
                .thenWaitUntil(() -> h.assertTrue(reached.equals(List.of(0)), "the first stop is reached with the player close, got " + reached))
                .thenIdle(80)
                .thenExecute(() -> {
                    h.assertTrue(reached.equals(List.of(0)), "the second stop waits for the player, got " + reached);
                    h.assertTrue(cat.distanceTo(p) <= Lead.WAIT + 2, "the cat stays a few blocks ahead, not " + cat.distanceTo(p));
                    put(h, p, 11, 5);
                })
                .thenWaitUntil(() -> h.assertTrue(reached.equals(List.of(0, 1)), "then the second, got " + reached))
                .thenExecute(() -> put(h, p, 13, 12))
                .thenWaitUntil(() -> h.assertTrue(ended.get() == Lead.End.DONE, "the lead ends once every stop is reached"))
                .thenExecute(() -> {
                    h.assertTrue(reached.equals(List.of(0, 1, 2)), "the stops come in order, got " + reached);
                    h.assertTrue(!cat.getTags().contains(BookClans.BUSY_TAG) && !cat.getTags().contains(Lead.LED_TAG), "the cat is no longer busy");
                    h.assertTrue(cat.mode == WCatEntity.CatMode.SIT && home.equals(cat.getHomePosition()), "its own mode and home are back");
                    h.assertTrue(Lead.of(p) == null && !Lead.leading(cat), "nothing is left of the lead");
                    cat.discard();
                })
                .thenSucceed();
    }

    /**
     * A wall three blocks high across the way, with a gap at one end: the cat leads round through the gap, walking all
     * the way, and never turns up on top of the wall or across it by itself (Kiki's mentor did, and waited for her to
     * climb).
     */
    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void aCatLeadsRoundAWallNotOverIt(GameTestHelper h) {
        floor(h);
        for (int z = 0; z < 12; z++) for (int y = 2; y <= 4; y++) h.setBlock(8, y, z, Blocks.STONE);
        WCatEntity cat = cat(h, 3, 2);
        ServerPlayer p = player(h, "lead_test_wall");
        put(h, p, 2, 2);
        List<Integer> reached = new ArrayList<>();
        AtomicReference<Lead.End> ended = new AtomicReference<>();
        h.assertTrue(Lead.start(p, cat, List.of(stop(h, 13, 2, 0, reached)), (l, how) -> ended.set(how)) != null, "the cat leads");
        double floorY = h.absolutePos(new BlockPos(0, 2, 0)).getY();
        double[] last = {cat.getX(), cat.getZ()};
        boolean[] jumped = {false}, onTop = {false};
        h.onEachTick(() -> {
            if (Math.hypot(cat.getX() - last[0], cat.getZ() - last[1]) > 2.5) jumped[0] = true;
            if (cat.getY() > floorY + 1.5) onTop[0] = true;
            last[0] = cat.getX();
            last[1] = cat.getZ();
            // the player follows two blocks behind, on the floor
            double dx = p.getX() - cat.getX(), dz = p.getZ() - cat.getZ(), d = Math.hypot(dx, dz);
            if (d > 3) p.setPos(cat.getX() + dx / d * 2, floorY, cat.getZ() + dz / d * 2);
        });
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(ended.get() != null, "the lead ends"))
                .thenExecute(() -> {
                    h.assertTrue(ended.get() == Lead.End.DONE && reached.equals(List.of(0)), "the place behind the wall is reached, got " + ended.get() + " " + reached);
                    h.assertFalse(jumped[0], "the cat walked every step, never put ahead");
                    h.assertFalse(onTop[0], "and never stood on the wall");
                    cat.discard();
                })
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void aCatLeadsOnePlayerAndAStoppedLeadGivesItBack(GameTestHelper h) {
        floor(h);
        WCatEntity cat = cat(h, 2, 2);
        ServerPlayer a = player(h, "lead_test_b"), b = player(h, "lead_test_c");
        put(h, a, 3, 3);
        put(h, b, 4, 4);
        List<Integer> reached = new ArrayList<>();
        AtomicReference<Lead.End> ended = new AtomicReference<>();
        Lead lead = Lead.start(a, cat, List.of(stop(h, 12, 12, 0, reached)), (l, how) -> ended.set(how));
        h.assertTrue(lead != null && Lead.of(a) == lead, "the first player is led");
        h.assertTrue(Lead.start(b, cat, List.of(stop(h, 12, 2, 0, reached)), null) == null && Lead.of(b) == null,
                "a cat leading one player is not taken by another");
        Lead.stop(a);
        h.assertTrue(ended.get() == Lead.End.ABANDONED, "a stopped lead ends as given up");
        h.assertTrue(!cat.getTags().contains(BookClans.BUSY_TAG) && cat.mode == WCatEntity.CatMode.SIT, "and gives the cat back as it was");
        h.assertTrue(Lead.accompany(b, cat, 5, 40, null) != null && Lead.leading(cat), "then it is free for the other player");
        Lead.stop(b);
        cat.discard();
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aNestBedStaysOnLooseOrWetGround(GameTestHelper h) {
        h.setBlock(2, 1, 2, Blocks.SAND);                         // sand over a hollow (y 0 is air)
        h.setBlock(2, 0, 2, Blocks.AIR);
        h.setBlock(5, 1, 5, Blocks.WATER);                        // a flooded den floor
        h.setBlock(5, 0, 5, Blocks.STONE);
        BlockPos sand = h.absolutePos(new BlockPos(2, 2, 2)), wet = h.absolutePos(new BlockPos(5, 2, 5));
        h.assertTrue(BookClans.bed(h.getLevel(), sand) && BookClans.bed(h.getLevel(), wet), "both nests take a bed");
        h.runAfterDelay(10, () -> {
            var moss = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("warriorcats_events:moss_bed"));
            h.assertTrue(h.getLevel().getBlockState(sand).is(moss), "the bed over loose sand is still there");
            h.assertTrue(h.getLevel().getBlockState(wet).is(moss), "the bed over water is still there");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void aGuideCannotBeKilledOnTheWay(GameTestHelper h) {
        floor(h);
        WCatEntity cat = cat(h, 2, 2);
        cat.addTag(Lead.GUIDE_TAG);
        ServerPlayer p = player(h, "lead_test_d");
        put(h, p, 3, 3);
        float full = cat.getHealth();
        h.assertTrue(Lead.start(p, cat, List.of(stop(h, 12, 12, 0, new ArrayList<>())), null) != null, "the guide leads");
        cat.hurt(h.getLevel().damageSources().generic(), 4);
        h.assertTrue(cat.getHealth() == full, "a guide on its way to camp takes no harm (a fox's bite)");
        Lead.stop(p);
        cat.invulnerableTime = 0;
        cat.hurt(h.getLevel().damageSources().generic(), 4);
        h.assertTrue(cat.getHealth() < full, "once it no longer leads, it can be hurt again");
        cat.discard();
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aKittypetChoosesAClanAndIsShownTheWay(GameTestHelper h) {
        ServerPlayer p = player(h, "seek_test_a");
        // the start offers each Clan and going alone; the patrol's question offers another Clan
        Dialog.Line start = Starts.seekLine("Which Clan's cats will you seek?", "None yet", null);
        List<String> commands = start.choices().stream().map(Dialog.Choice::command).toList();
        h.assertTrue(commands.equals(List.of("greatjourney seek thunderclan", "greatjourney seek windclan", "greatjourney seek riverclan",
                "greatjourney seek shadowclan", "greatjourney seek none")), "one choice per Clan, and none yet, got " + commands);
        List<String> instead = Starts.seekLine("Which instead?", "None yet", Info.THUNDER).choices().stream().map(Dialog.Choice::command).toList();
        h.assertTrue(!instead.contains("greatjourney seek thunderclan") && instead.size() == 4, "seeking another Clan leaves out the patrol's own");
        List<String> patrol = Starts.joinOrSeek(Dialog.Line.say("Testpaw", 0, "Will you join?"), Info.THUNDER).choices().stream()
                .map(Dialog.Choice::command).toList();
        h.assertTrue(patrol.equals(List.of("greatjourney join thunderclan", "greatjourney seek other thunderclan", "greatjourney notyet")),
                "the patrol asks: join, seek another Clan, or not yet, got " + patrol);

        // the choice is remembered per player, and cleared
        Starts.setSeeking(p, Info.RIVER);
        h.assertTrue(Starts.seeking(p) == Info.RIVER, "the chosen Clan is remembered");
        Starts.setSeeking(p, null);
        h.assertTrue(Starts.seeking(p) == null, "and forgotten when the camp is found");

        // the journal's task, with the way to the camp from where the player stands
        ClanWorld w = ClanWorld.layoutOnly(20261011);
        Landmark camp = w.lake.camp(Clan.RIVER);
        JournalView.Quest q = Starts.seekQuest(Info.RIVER, camp, camp.x() - 300, camp.z(), null);
        h.assertTrue(q.title().equals("Find the RiverClan camp"), "the task names the camp, got " + q.title());
        h.assertTrue(q.description().contains("300 blocks to the east") && q.objectives().get(0).text().contains("to the east"),
                "and says which way it lies, got " + q.description());
        h.assertTrue(Starts.seekQuest(Info.RIVER, camp, camp.x(), camp.z() + camp.radius() + 200, "Mosswhisker").description().contains("Mosswhisker is leading you")
                && Starts.seekQuest(Info.RIVER, camp, camp.x(), camp.z() + camp.radius() + 200, null).description().contains("to the north"),
                "and who leads the way");

        // a guide goes round the lake to each camp, and in by its one way in
        Landmark garden = w.lake.kittypetHome();
        for (Clan c : Clan.values()) {
            Landmark to = w.lake.camp(c);
            List<double[]> way = new ArrayList<>();
            way.add(new double[] {garden.x(), garden.z()});
            way.addAll(Starts.wayRound(w, garden.x(), garden.z(), to.x(), to.z()));
            way.addAll(Starts.wayIn(w, to));
            for (int i = 1; i < way.size(); i++) {
                double[] a = way.get(i - 1), b = way.get(i);
                double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                for (double s = 0; s <= len; s += 6) {
                    double x = a[0] + (b[0] - a[0]) / Math.max(1, len) * s, z = a[1] + (b[1] - a[1]) / Math.max(1, len) * s;
                    h.assertTrue(w.lake.sample(x, z).zone() != com.ninjacat.greatjourney.clanlands.layout.Zone.LAKE,
                            "the way from the garden to " + c + "'s camp stays out of the lake, at " + (int) x + " " + (int) z);
                }
            }
            double[] last = way.get(way.size() - 1);
            h.assertTrue(Math.hypot(last[0] - to.x(), last[1] - to.z()) < to.radius(), "and ends inside " + c + "'s camp");
        }
        h.succeed();
    }
}
