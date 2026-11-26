package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Cat;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Free Mode's own: a loner's favour for a cat of no Clan ({@link Strangers#favourFor}), and what StarClan says at the
 * Moonpool ({@link MoonDreams#dream}).
 *
 * <p>A deputy's or leader's own patrol ({@link Patrols#gather}): who comes, that the whole patrol takes an order, and
 * that every cat is given back. And cats of no Clan ({@link Strangers}): who can be spoken to, and who is left alone.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class OrdersGameTests {
    private OrdersGameTests() {}

    private static ServerPlayer player(GameTestHelper h, String name) {
        ServerPlayer p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        QuestState.reset(p);
        Dialog.forget(p);
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    private static WCatEntity cat(GameTestHelper h, String name, WCatEntity.Rank rank, int x, UUID clan) {
        WCatEntity cat = ModEntities.WCAT.get().create(h.getLevel());
        BlockPos at = h.absolutePos(new BlockPos(x, 2, 4));
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cat.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setCustomName(Component.literal(name));
        cat.setRank(rank);
        cat.setAgeInMoons(BookClans.ADULT_MOONS);
        cat.setGender(0);                                      // a she-cat beside a kit is taken for a queen
        h.getLevel().addFreshEntity(cat);
        if (clan != null) cat.setClanUUID(clan);
        return cat;
    }

    @GameTest(template = "empty")
    public static void aDeputyTakesAPatrolOutAndOrdersIt(GameTestHelper h) {
        ServerPlayer p = player(h, "orders_deputy");
        UUID clan = UUID.randomUUID();
        WCatEntity first = cat(h, "Firstclaw", WCatEntity.Rank.WARRIOR, 1, clan), paw = cat(h, "Secondpaw", WCatEntity.Rank.APPRENTICE, 2, clan);
        WCatEntity kit = cat(h, "Thirdkit", WCatEntity.Rank.KIT, 3, clan), busy = cat(h, "Busyclaw", WCatEntity.Rank.WARRIOR, 4, clan);
        WCatEntity other = cat(h, "Otherclaw", WCatEntity.Rank.WARRIOR, 5, UUID.randomUUID());
        try {
            busy.addTag(BookClans.BUSY_TAG);
            h.assertFalse(Patrols.order(p, "stay"), "no patrol, no orders");
            int came = Patrols.gather(p, first);
            h.assertTrue(came == 2, came + " came: the warrior asked and the free apprentice come: not the kit, a busy cat or another Clan's");
            h.assertTrue(Patrols.leads(p) && Patrols.inPatrolOf(p, first) && Patrols.inPatrolOf(p, paw), "both walk in the player's patrol");
            h.assertFalse(Patrols.inPatrolOf(p, kit) || Patrols.inPatrolOf(p, busy) || Patrols.inPatrolOf(p, other), "and nobody else does");
            h.assertTrue(first.getTags().contains(BookClans.BUSY_TAG), "a cat on the patrol is kept out of its Clan routine");
            h.assertTrue(Patrols.order(p, "stay") && Patrols.order(p, "follow"), "the whole patrol takes an order");
            h.assertTrue(Patrols.order(p, "home") && !Patrols.leads(p), "sent home, the patrol is over");
            h.assertFalse(first.getTags().contains(BookClans.BUSY_TAG) || paw.getTags().contains(BookClans.BUSY_TAG), "and its cats are the Clan's again");
            h.assertTrue(busy.getTags().contains(BookClans.BUSY_TAG), "a cat busy elsewhere was never touched");
        } finally {
            Patrols.order(p, "home");
            for (WCatEntity c : new WCatEntity[] {first, paw, kit, busy, other}) c.discard();
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aLonerAsksACatOfNoClanAFavour(GameTestHelper h) {
        ServerPlayer p = player(h, "orders_favour");
        WCatEntity loner = cat(h, "Barley", WCatEntity.Rank.NONE, 1, null);
        try {
            Task a = Strangers.favourFor(loner, "Barley", 4), b = Strangers.favourFor(loner, "Barley", 5);
            h.assertTrue(a.kind != b.kind && a.kind == Strangers.favourFor(loner, "Barley", 4).kind, "a favour is the day's own, and changes with the day");
            h.assertTrue(Strangers.answer(p, loner.getId(), "favour") && QuestState.duties(p).size() == 1, "asked, the loner's favour is in the journal");
            h.assertTrue(Strangers.answer(p, loner.getId(), "favour") && QuestState.duties(p).size() == 1, "asked again, it is not given twice");
            java.util.List<Task> d = QuestState.duties(p);
            for (Task.Objective o : d.get(0).objectives) o.progress = o.goal;
            QuestState.setDuties(p, d);
            int before = QuestState.standing(p);
            Dialog.forget(p);
            Strangers.greet(p, loner, Strangers.Kind.LONER);
            h.assertTrue(QuestState.duties(p).isEmpty() && QuestState.standing(p) == before + Strangers.FAVOUR_STANDING, "done, it is handed in by talking to them");
            h.assertTrue(Strangers.answer(p, loner.getId(), "favour") && QuestState.duties(p).isEmpty(), "and the same loner asks no more that day");
        } finally {
            loner.discard();
            Dialog.forget(p);
            QuestState.reset(p);
        }
        h.succeed();
    }

    /** A real right-click, through the game's event bus and every handler on it, in the order they were registered. */
    private static void click(ServerPlayer p, net.minecraft.world.entity.Entity target) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
                p, net.minecraft.world.InteractionHand.MAIN_HAND, target));
    }

    @GameTest(template = "empty")
    public static void aRightClickReachesTheClanlessCatItIsMeantFor(GameTestHelper h) {
        ServerPlayer p = player(h, "orders_click");
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        Friendship.reset();
        try {
            p.getInventory().clearContent();
            h.assertTrue(Friendship.meet(p, com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan.RIVER, at), "a friend waits at the border");
            var friend = h.getLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(16),
                    c -> c.getTags().contains(Friendship.TAG)).get(0);
            h.assertTrue(Friendship.met(p) == null, "not spoken to yet");
            click(p, friend);
            h.assertTrue(friend.getUUID().equals(Friendship.met(p)),
                    "the click reaches the friendship: it is not taken for a cat of another Clan (Warrior Cats gives a clanless cat the empty id)");
        } finally {
            h.getLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(16), c -> c.getTags().contains(Friendship.TAG))
                    .forEach(net.minecraft.world.entity.Entity::discard);
            Friendship.reset();
            Dialog.forget(p);
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void starClanSpeaksAtTheMoonpool(GameTestHelper h) {
        ServerPlayer p = player(h, "orders_dream");
        try {
            var life = new com.ninjacat.greatjourney.clanlands.life.LifeData();
            var places = java.util.List.of(new Places.Place("sky_oak", "The Sky Oak", p.getX() + 300, p.getZ(), 40, "A great oak"));
            String none = MoonDreams.dream(p, life, places, java.util.Set.of()).stream().map(l -> l.text()).reduce("", String::concat);
            h.assertTrue(none.contains("The Sky Oak") && none.contains("Seek it to the"), "the sign is the way to a place not yet found");
            life.rememberInStarClan("Oldfur", com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan.RIVER, "old age", 1);
            var lines = MoonDreams.dream(p, life, places, java.util.Set.of("sky_oak"));
            h.assertTrue(lines.stream().anyMatch(l -> "Oldfur".equals(l.speaker())), "a cat fallen in this world is the one who comes");
            h.assertFalse(lines.stream().anyMatch(l -> l.text().contains("The Sky Oak")), "a place already found is no sign");
            var root = new net.minecraft.nbt.CompoundTag();
            h.assertTrue(MoonDreams.due(root, 100), "StarClan speaks to a cat who has never dreamed");
            root.putLong("moonDream", 100);
            h.assertFalse(MoonDreams.due(root, 100 + MoonDreams.MOON - 1), "then not again until the moon has turned");
            h.assertTrue(MoonDreams.due(root, 100 + MoonDreams.MOON), "and then once more");
        } finally {
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void kittypetsLonersAndRoguesCanBeSpokenTo(GameTestHelper h) {
        ServerPlayer p = player(h, "orders_stranger");
        Cat pet = EntityType.CAT.create(h.getLevel());
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
        pet.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        h.getLevel().addFreshEntity(pet);
        WCatEntity loner = cat(h, "Barley", WCatEntity.Rank.NONE, 1, null), rogue = cat(h, "Snag", WCatEntity.Rank.NONE, 2, null);
        WCatEntity story = cat(h, "Purdy", WCatEntity.Rank.NONE, 3, null), clan = cat(h, "Clanclaw", WCatEntity.Rank.WARRIOR, 4, UUID.randomUUID());
        try {
            rogue.addTag("greatjourney_event_foe");
            story.addTag("greatjourney_purdy");
            h.assertTrue(Strangers.kind(pet, p) == Strangers.Kind.KITTYPET, "a Twoleg's cat is a kittypet");
            h.assertTrue(Strangers.kind(loner, p) == Strangers.Kind.LONER, "a wild cat of no Clan is a loner");
            h.assertTrue(Strangers.kind(rogue, p) == Strangers.Kind.ROGUE, "one set on a camp is a rogue");
            h.assertTrue(Strangers.kind(story, p) == null && Strangers.kind(clan, p) == null, "a story cat and a Clan cat keep their own words");
            pet.setOwnerUUID(p.getUUID());
            h.assertTrue(Strangers.kind(pet, p) == null, "a player's own cat is left to its owner");
            pet.setOwnerUUID(UUID.randomUUID());
            for (String topic : new String[] {"life", "clans", "news"}) {
                h.assertTrue(Strangers.answer(p, pet.getId(), topic) && Strangers.answer(p, loner.getId(), topic), "a kittypet and a loner answer about " + topic);
            }
            h.assertFalse(Strangers.answer(p, rogue.getId(), "life"), "a rogue has nothing to tell");
            h.assertFalse(Strangers.answer(p, clan.getId(), "life"), "and a Clan cat is not asked this way");
            Dialog.forget(p);
            Strangers.greet(p, pet, Strangers.Kind.KITTYPET);
            h.assertTrue(Dialog.busy(p), "a kittypet greets whoever speaks to it");
        } finally {
            pet.discard();
            for (WCatEntity c : new WCatEntity[] {loner, rogue, story, clan}) c.discard();
            Dialog.forget(p);
            QuestState.reset(p);
        }
        h.succeed();
    }
}
