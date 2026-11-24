package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.events.Cats;
import com.ninjacat.greatjourney.clanlore.role.Role;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A cat's rise through their Clan (reported 2026-11-23): a mentor who can teach no longer is known as lost however it
 * went ({@link Mentors#lost}) and the step's progress carries over to the new one; a grown cat's own apprentice
 * ({@link Pupils}) and its lessons; what stands between a warrior and deputy, a deputy and leader ({@link Ranks});
 * a player who leads is the Clan's leader in the world's records; and the cats who follow a loner ({@link Founding}).
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class RisingGameTests {
    private RisingGameTests() {}

    private static ServerPlayer player(GameTestHelper h, String name) {
        ServerPlayer p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        QuestState.reset(p);
        Dialog.forget(p);
        return p;
    }

    private static WCatEntity cat(GameTestHelper h, String name, WCatEntity.Rank rank, BlockPos rel, UUID clan) {
        WCatEntity cat = ModEntities.WCAT.get().create(h.getLevel());
        h.assertTrue(cat != null, "a Warrior Cats Events cat can be made");
        BlockPos at = h.absolutePos(rel);
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cat.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setCustomName(Component.literal(name));
        cat.setRank(rank);
        cat.setNoAi(true);
        h.getLevel().addFreshEntity(cat);
        if (clan != null) cat.setClanUUID(clan);
        return cat;
    }

    @GameTest(template = "empty")
    public static void aLostMentorIsKnownHoweverItWent(GameTestHelper h) {
        ServerPlayer p = player(h, "rising_mentor");
        ClanData data = ClanData.get(h.getLevel());
        ClanData.Clan wc = data.createClan("RisingTestClanA", 0x808080, UUID.randomUUID(), "Teststar", "", 0);
        List<WCatEntity> made = new ArrayList<>();
        try {
            LifeData life = new LifeData();
            long now = h.getLevel().getGameTime();
            h.assertTrue(Mentors.lost(p, wc, life) == null, "no mentor yet, none lost");

            WCatEntity mentor = cat(h, "Oakfur", WCatEntity.Rank.WARRIOR, new BlockPos(2, 2, 2), wc.clanUUID);
            made.add(mentor);
            // a cat of the mentor's name who died long ago is not this mentor
            life.rememberInStarClan("Oakfur", Clan.THUNDER, "old age", now - 100);
            QuestState.setMentor(p, "Oakfur", mentor.getUUID());
            h.assertTrue(Mentors.lost(p, wc, life) == null, "a living warrior of the Clan teaches");
            h.assertTrue(Mentors.fit(mentor, life, false) == false && Mentors.lost(p, wc, life) == null,
                    "a mentor standing still in a scene cannot be chosen anew, but is still the mentor");

            QuestState.setPath(p, QuestState.Path.MEDICINE);
            h.assertTrue(Mentors.lost(p, wc, life) == Mentors.Loss.RETIRED, "a warrior cannot teach a medicine cat's apprentice");
            QuestState.setPath(p, QuestState.Path.WARRIOR);

            Roles.setRole(mentor, Role.ELDER);
            h.assertTrue(Mentors.lost(p, wc, life) == Mentors.Loss.RETIRED, "a mentor gone to the elders' den teaches no longer");
            Roles.setRole(mentor, Role.NONE);
            h.assertTrue(Mentors.lost(p, wc, life) == null, "back among the warriors, they teach again");

            mentor.setClanUUID(ClanData.EMPTY_UUID);
            h.assertTrue(Mentors.lost(p, wc, life) == Mentors.Loss.GONE, "a mentor put out of the Clan is gone");
            mentor.setClanUUID(wc.clanUUID);

            // away (not loaded): still the mentor while the Clan's list has it; gone once the list drops it
            UUID id = mentor.getUUID();
            mentor.discard();
            h.assertTrue(wc.clanCats.containsKey(id), "the Clan's list keeps a cat that is only out of sight");
            h.assertTrue(Mentors.lost(p, wc, life) == null, "an unloaded mentor is not lost");
            wc.clanCats.remove(id);
            h.assertTrue(Mentors.lost(p, wc, life) == Mentors.Loss.GONE, "a mentor the Clan's list has dropped is gone, whatever its name");
            life.rememberInStarClan("Oakfur", Clan.THUNDER, "battle", h.getLevel().getGameTime());
            h.assertTrue(Mentors.lost(p, wc, life) == Mentors.Loss.DIED, "one StarClan has taken since is known to have died");
            h.assertTrue(Story.lossWords("Oakfur", Mentors.Loss.DIED).contains("StarClan"), "and the apprentice is told so");
        } finally {
            made.forEach(WCatEntity::discard);
            data.deleteClan(h.getLevel(), wc.clanUUID);
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aMentorMadeAnewIsFollowed(GameTestHelper h) {
        ServerPlayer p = player(h, "rising_follow");
        ClanData data = ClanData.get(h.getLevel());
        ClanData.Clan wc = data.createClan("RisingTestClanB", 0x808080, UUID.randomUUID(), "Teststar", "", 0);
        List<WCatEntity> made = new ArrayList<>();
        try {
            LifeData life = new LifeData();
            BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
            var camp = new com.ninjacat.greatjourney.clanlands.layout.Landmark(
                    com.ninjacat.greatjourney.clanlands.layout.Landmark.Kind.STONE_HOLLOW, at.getX(), at.getZ(), 8, 0, 0);
            p.setPos(at.getX(), at.getY(), at.getZ());
            // the old mentor stayed behind in the empty forest: still on the Clan's list, never loaded again
            UUID old = UUID.randomUUID();
            QuestState.setMentor(p, "Oakfur", old);
            wc.clanCats.put(old, new ClanData.ClanCat());
            h.assertTrue(Mentors.lost(p, wc, life) == null, "a mentor far away is not lost");
            WCatEntity other = cat(h, "Birchtail", WCatEntity.Rank.WARRIOR, new BlockPos(2, 2, 2), wc.clanUUID);
            WCatEntity again = cat(h, "Oakfur", WCatEntity.Rank.WARRIOR, new BlockPos(4, 2, 4), wc.clanUUID);
            made.addAll(List.of(other, again));
            other.setNoAi(false);
            again.setNoAi(false);
            Mentors.follow(p, wc.clanUUID, camp, life);
            h.assertTrue(again.getUUID().equals(QuestState.mentorId(p)), "the cat of the mentor's name in camp is the mentor from now on");
            h.assertTrue(Mentors.mentorsPlayer(again), "and knows it has a pupil");
            Mentors.follow(p, wc.clanUUID, camp, life);
            h.assertTrue(again.getUUID().equals(QuestState.mentorId(p)), "a mentor who is here is left as it is");
        } finally {
            made.forEach(WCatEntity::discard);
            data.deleteClan(h.getLevel(), wc.clanUUID);
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aStepsProgressCarriesOverToTheNewMentor(GameTestHelper h) {
        UUID oldId = UUID.randomUUID(), newId = UUID.randomUUID();
        Task before = Tasks.storyStep(QuestState.Path.WARRIOR, 2, Clan.THUNDER, "Oakfur", oldId, 42);
        before.objectives.get(0).progress = 2;
        Task after = Story.carryOver(before, Tasks.storyStep(QuestState.Path.WARRIOR, 2, Clan.THUNDER, "Birchtail", newId, 42));
        h.assertTrue(newId.equals(after.giverId) && after.giverName.equals("Birchtail"), "the step is the new mentor's");
        h.assertTrue(after.objectives.get(0).progress == 2, "two of three prey already caught stay caught");
        h.assertTrue(after.description.contains("Birchtail") && !after.description.contains("Oakfur"), "and its words name the new mentor");
        Task other = Story.carryOver(before, Tasks.storyStep(QuestState.Path.WARRIOR, 6, Clan.THUNDER, "Birchtail", newId, 42));
        h.assertTrue(other.objectives.get(0).progress == 0, "another step takes nothing over");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aGrownCatsApprenticeAndItsLessons(GameTestHelper h) {
        ServerPlayer p = player(h, "rising_pupil");
        List<WCatEntity> made = new ArrayList<>();
        try {
            WCatEntity paw = cat(h, "Pebblepaw", WCatEntity.Rank.APPRENTICE, new BlockPos(2, 2, 2), null);
            made.add(paw);
            h.assertFalse(Pupils.isPupil(paw), "an apprentice nobody has marked is nobody's");

            for (boolean healer : new boolean[] {false, true}) {
                for (int step = 1; step <= Pupils.LESSONS; step++) {
                    Task t = Pupils.lessonFor(p, Clan.THUNDER, paw, step, healer);
                    h.assertTrue(t.kind == Task.Kind.LESSON && !t.objectives.isEmpty() && paw.getUUID().equals(t.giverId),
                            "lesson " + step + (healer ? " (medicine)" : "") + " has work, and is the apprentice's");
                    h.assertTrue(Task.load(t.save()) != null && t.title.contains("Pebblepaw"), "it saves, and names the apprentice");
                }
            }

            // the lesson is one of the player's tasks, kept in its own place, and counts only with the apprentice along
            Task lesson = Pupils.lessonFor(p, Clan.THUNDER, paw, 2, false);
            QuestState.setLesson(p, lesson);
            h.assertTrue(QuestState.active(p).stream().anyMatch(t -> t.kind == Task.Kind.LESSON), "the lesson is in the journal");
            h.assertFalse(Pupils.counts(p, lesson), "with no apprentice at their side, a lesson does not count");
            QuestState.root(p).putUUID("pupilId", paw.getUUID());
            p.setPos(paw.getX() + 3, paw.getY(), paw.getZ());
            h.assertTrue(Pupils.counts(p, lesson), "with the apprentice near, it does");
            Tracking.caught(p, "warriorcats_events:mouse", 1);
            h.assertTrue(QuestState.lesson(p).objectives.get(0).progress == 1, "and a catch is counted and saved to the lesson");
            p.setPos(paw.getX() + Pupils.NEAR + 20, paw.getY(), paw.getZ());
            Tracking.caught(p, "warriorcats_events:mouse", 1);
            h.assertTrue(QuestState.lesson(p).objectives.get(0).progress == 1, "a catch far from the apprentice teaches them nothing");

            // the mark holds for a mentor who is away a while, then lets go
            paw.getPersistentData().putUUID(Pupils.KEY, UUID.randomUUID());
            paw.getPersistentData().putLong(Pupils.SEEN, h.getLevel().getGameTime());
            h.assertTrue(Pupils.isPupil(paw), "an apprentice keeps a mentor who is away");
            paw.getPersistentData().putLong(Pupils.SEEN, h.getLevel().getGameTime() - Pupils.AWAY - 1);
            h.assertFalse(Pupils.isPupil(paw), "but not for ever");
            h.assertFalse(paw.getPersistentData().hasUUID(Pupils.KEY), "and the mark is taken off");

            // Clan Lore shows the player as the mentor, and gives the name up again
            UUID me = p.getUUID();
            Roles.setPlayerMentor(paw, me, "Testheart");
            h.assertTrue(me.equals(Roles.mentorOf(paw)) && Roles.line(paw).contains("Apprentice of Testheart"), "the apprentice's name tag names its mentor");
            Roles.setPlayerMentor(paw, null, "");
            h.assertTrue(Roles.mentorOf(paw) == null, "until the pairing ends");
        } finally {
            made.forEach(WCatEntity::discard);
            QuestState.reset(p);
        }
        h.succeed();
    }

    /**
     * A named cat is greeted and set to work as one (reported 2026-11-23: a warrior and a deputy were still treated as
     * apprentices): no elder calls them "young one" or sends them for moss, no warrior sets them to practise, every
     * Clanmate has something to ask, and the deputy, with no deputy above them, gets the patrols from the leader.
     */
    @GameTest(template = "empty")
    public static void aNamedCatIsTreatedAsOne(GameTestHelper h) {
        UUID cat = UUID.randomUUID();
        for (Kin.Role you : new Kin.Role[] {Kin.Role.WARRIOR, Kin.Role.DEPUTY, Kin.Role.LEADER, Kin.Role.MEDICINE, Kin.Role.ELDER, Kin.Role.QUEEN}) {
            for (Kin.Role giver : Kin.Role.values()) {
                String hello = Talk.greeting(giver, "Testpelt", "Brackenfur", you);
                h.assertFalse(hello.contains("young one") || hello.contains("apprentices fetched") || hello.contains("Are you a warrior"),
                        "a " + giver + " greets a " + you + " as a named cat: " + hello);
                for (long day = 0; day < 12; day++) {
                    Task t = Tasks.dutyFor(you, giver, Clan.THUNDER, "Testpelt", cat, day);
                    h.assertTrue(t != null && !t.objectives.isEmpty() && Task.load(t.save()) != null, "a " + giver + " has work for a " + you + " on day " + day);
                    h.assertFalse(t.kind == Task.Kind.MOSS || t.kind == Task.Kind.STALK || t.kind == Task.Kind.DARE, "no apprentice's chore for a " + you + ": " + t.title);
                    if (you == Kin.Role.LEADER) h.assertFalse(t.description.contains("sends you"), "nobody sends their leader: " + t.description);
                    if (giver == Kin.Role.LEADER && you == Kin.Role.DEPUTY) {
                        h.assertTrue(t.kind == Task.Kind.BORDER_PATROL || t.kind == Task.Kind.HUNT, "the leader gives their deputy the patrols: " + t.title);
                        h.assertTrue(t.title.startsWith("Lead the"), "to lead: " + t.title);
                    }
                }
            }
        }
        // an apprentice is still an apprentice, and a kit a kit
        boolean moss = false, practice = false;
        for (long day = 0; day < 40; day++) {
            moss |= Tasks.dutyFor(Kin.Role.APPRENTICE, Kin.Role.ELDER, Clan.THUNDER, "Testpelt", cat, day).kind == Task.Kind.MOSS;
            Task.Kind k = Tasks.dutyFor(Kin.Role.APPRENTICE, Kin.Role.WARRIOR, Clan.THUNDER, "Testpelt", cat, day).kind;
            h.assertTrue(k == Task.Kind.CLIMB || k == Task.Kind.STALK, "a warrior sets an apprentice to practise");
            practice |= k == Task.Kind.STALK;
        }
        h.assertTrue(moss && practice, "an apprentice still fetches the elders' moss and practises the crouch");
        h.assertTrue(Talk.greeting(Kin.Role.ELDER, "Testpelt", "Brackenpaw", Kin.Role.APPRENTICE).contains("young one"), "and is a young one to the elders");
        h.assertTrue(Tasks.dutyFor(Kin.Role.KIT, Kin.Role.LEADER, Clan.THUNDER, "Teststar", cat, 0) == null, "the leader asks nothing of a kit");
        h.succeed();
    }

    /**
     * An apprentice left out in the territory (its mentor died out there, or went on without it) comes home, where its
     * mentor looks for it and where it is named (reported 2026-11-23: some never got their warrior name). One the Clan
     * itself has sent out is left alone.
     */
    @GameTest(template = "empty")
    public static void anApprenticeLeftBehindComesHome(GameTestHelper h) {
        ServerPlayer p = player(h, "rising_stray");
        WCatEntity paw = cat(h, "Straypaw", WCatEntity.Rank.APPRENTICE, new BlockPos(1, 2, 1), null);
        try {
            double x = paw.getX(), z = paw.getZ();
            Landmark camp = new Landmark(Landmark.Kind.SHADOWCLAN_CAMP, x + 50, z, 5, 0, 0);
            p.setPos(x, paw.getY(), z + 400);                       // far off: it is not watched as it goes
            paw.setNoAi(false);
            paw.addTag(BookClans.BUSY_TAG);
            h.assertFalse(Pupils.comeHome(p, paw, camp), "an apprentice the Clan has sent out is not in camp");
            h.assertTrue(Math.abs(paw.getX() - x) < 1 && Math.abs(paw.getZ() - z) < 1, "and is left where it is");
            paw.removeTag(BookClans.BUSY_TAG);
            h.assertTrue(Pupils.comeHome(p, paw, camp), "one left out in the territory is brought home");
            h.assertTrue(Math.hypot(paw.getX() - camp.x(), paw.getZ() - camp.z()) <= 8, "to its camp");
            h.assertTrue(Pupils.comeHome(p, paw, camp), "where it stays");
        } finally {
            paw.discard();
            QuestState.reset(p);
        }
        h.succeed();
    }

    /**
     * A Clan that needs a medicine cat takes one of its own apprentices and gives them a full name, never the one a
     * player is training (seen in a world played 2026-11-23: apprentices made medicine cats and left "-paw" for good,
     * a player's own among them, counted as trained with no name).
     */
    @GameTest(template = "empty")
    public static void theClansMedicineCatIsNeverAPlayersApprentice(GameTestHelper h) {
        LifeData life = LifeData.get(h.getLevel());
        ServerPlayer p = player(h, "rising_healer");
        WCatEntity mine = cat(h, "Minepaw", WCatEntity.Rank.APPRENTICE, new BlockPos(1, 2, 1), null);
        WCatEntity other = cat(h, "Otherpaw", WCatEntity.Rank.APPRENTICE, new BlockPos(2, 2, 1), null);
        WCatEntity warrior = cat(h, "Testpelt", WCatEntity.Rank.WARRIOR, new BlockPos(3, 2, 1), null);
        try {
            other.setPrefix(Component.literal("Other"));          // as every Clan cat has (a cat made bare here has "None")
            mine.getPersistentData().putUUID(Pupils.KEY, p.getUUID());
            mine.getPersistentData().putLong(Pupils.SEEN, h.getLevel().getGameTime());
            h.assertTrue(Pupils.isPupil(mine), "the apprentice is a player's");
            h.assertTrue(com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.chooseMedicineCat(life, Clan.THUNDER, List.of(mine, other, warrior)) != null,
                    "the Clan finds a medicine cat");
            h.assertTrue(mine.getRank() == WCatEntity.Rank.APPRENTICE && Kin.name(mine).equals("Minepaw"), "not the player's apprentice");
            h.assertTrue(other.getRank() == WCatEntity.Rank.MEDICINE, "but one of its own");
            h.assertTrue(!Kin.name(other).endsWith("paw") && Kin.name(other).startsWith("Other"), "who takes a full name: " + Kin.name(other));
            h.assertTrue(warrior.getRank() == WCatEntity.Rank.WARRIOR, "and no warrior is taken while an apprentice will do");
            other.setRank(WCatEntity.Rank.WARRIOR);
            h.assertTrue(com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.chooseMedicineCat(life, Clan.THUNDER, List.of(mine, warrior)) != null,
                    "with no apprentice of its own, the Clan still finds one");
            h.assertTrue(mine.getRank() == WCatEntity.Rank.APPRENTICE && warrior.getRank() == WCatEntity.Rank.MEDICINE && Kin.name(warrior).equals("Testpelt"),
                    "a warrior, who keeps their name");
        } finally {
            mine.discard();
            other.discard();
            warrior.discard();
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void whatStandsBetweenAWarriorAndLeader(GameTestHelper h) {
        ServerPlayer p = player(h, "rising_rank");
        try {
            h.assertTrue(Ranks.deputyNeeds(p).size() == 2, "a new warrior has an apprentice to train and trust to earn");
            QuestState.root(p).putInt("pupilsTrained", 1);
            QuestState.addStanding(p, Ranks.DEPUTY_STANDING - 1);
            h.assertTrue(Ranks.deputyNeeds(p).size() == 1, "an apprentice trained: only the Clan's trust is wanting");
            QuestState.addStanding(p, 1);
            h.assertTrue(Ranks.deputyNeeds(p).isEmpty(), "then nothing stands between them and deputy");

            long now = h.getLevel().getGameTime();
            QuestState.root(p).putLong("deputySince", now);
            h.assertTrue(Ranks.leaderNeeds(p).size() == 2, "a new deputy must serve a while, and earn more trust");
            QuestState.root(p).putLong("deputySince", now - Ranks.DEPUTY_SERVICE);
            QuestState.addStanding(p, Ranks.LEADER_STANDING - Ranks.DEPUTY_STANDING);
            h.assertTrue(Ranks.leaderNeeds(p).isEmpty(), "then the old leader will step down for them");

            LifeData life = new LifeData();
            life.rememberInStarClan("Oldfur", Clan.THUNDER, "old age", 1);
            life.rememberInStarClan("Riverpelt", Clan.RIVER, "battle", 2);
            life.rememberInStarClan("Newfur", Clan.THUNDER, "battle", 3);
            h.assertTrue(Ranks.fallen(life, Clan.THUNDER).equals(List.of("Newfur", "Oldfur")), "the Clan's own fallen give the lives, the latest first");
            var scene = Ranks.nineLivesScene("Testheart", "Teststar", "ThunderClan", Ranks.fallen(life, Clan.THUNDER));
            String all = scene.stream().map(l -> l.text()).reduce("", String::concat);
            h.assertTrue(all.split("a life for", -1).length - 1 == 9, "nine lives are given");
            h.assertTrue(all.contains("Newfur: a life for courage") && all.contains("Teststar"), "by name, to the new name");
        } finally {
            QuestState.reset(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayerWhoLeadsIsTheClansLeader(GameTestHelper h) {
        List<WCatEntity> made = new ArrayList<>();
        try {
            LifeData life = new LifeData();
            UUID clanId = UUID.randomUUID(), player = UUID.randomUUID();
            life.adopt(Clan.WIND, clanId, "Tallstar");
            WCatEntity old = cat(h, "Tallstar", WCatEntity.Rank.WARRIOR, new BlockPos(2, 2, 2), null);
            made.add(old);
            h.assertTrue(Cats.isLeader(life, Clan.WIND, old), "the Clan's cat of the leader's name leads");

            life.loseLife(Clan.WIND);
            life.setLeaderPlayer(Clan.WIND, player, "Gorsepelt");
            h.assertTrue(player.equals(life.leaderPlayer(Clan.WIND)) && life.leader(Clan.WIND).equals("Gorsepelt"), "a player leads, by their cat's name");
            h.assertTrue(life.lives(Clan.WIND) == LifeData.LEADER_LIVES, "with nine lives");
            h.assertFalse(Cats.isLeader(life, Clan.WIND, old), "and no Clan cat leads beside them");

            Roles.setRole(old, Role.ELDER);
            h.assertTrue(Kin.role(old, life) == Kin.Role.ELDER, "the old leader keeps the name and is an elder");

            LifeData back = LifeData.fromTag(life.save(new net.minecraft.nbt.CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(player.equals(back.leaderPlayer(Clan.WIND)), "who leads is saved with the world");
            back.setLeader(Clan.WIND, "Mudstar");
            h.assertTrue(back.leaderPlayer(Clan.WIND) == null, "and a Clan cat who leads after them takes the place whole");
        } finally {
            made.forEach(WCatEntity::discard);
        }
        h.succeed();
    }

    /**
     * The naming ceremony (reported 2026-11-23: only ever four names, and never the one chosen at the start): the name
     * the player chose when they made their cat is on offer beside four from the Clan, and four more for the asking.
     */
    @GameTest(template = "empty")
    public static void theNamingOffersTheNameYouChose(GameTestHelper h) {
        ServerPlayer p = player(h, "rising_naming");
        var data = p.getData(net.snowteb.warriorcats_events.attachments.ModAttachments.PLAYER_WCE_DATA.get());
        String before = data.getSufix();
        try {
            data.setSufix(" Fur ");
            h.assertTrue(Story.ownSuffix(p).equals("fur"), "the ending chosen at the start is the player's own");
            for (String none : new String[] {"", "paw", "kit", "star", "two words", "x"}) {
                data.setSufix(none);
                h.assertTrue(Story.ownSuffix(p).isEmpty(), "\"" + none + "\" is no warrior's ending");
            }
            List<String> first = Story.offers("Blue", "fur", 42, 0);
            h.assertTrue(first.size() == 4 && new java.util.HashSet<>(first).size() == 4, "the Clan offers four different names");
            h.assertTrue(first.equals(Story.offers("Blue", "fur", 42, 0)), "the same four if the ceremony is asked again");
            h.assertFalse(first.equals(Story.offers("Blue", "fur", 43, 0)), "other names in another world");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int page = 0; page < 40; page++) {
                List<String> more = Story.offers("Blue", "fur", 42, page);
                h.assertFalse(more.contains("fur"), "the Clan never offers the player's own name back");
                seen.addAll(more);
            }
            h.assertTrue(seen.size() > 60, "asking for other names keeps bringing new ones (" + seen.size() + " in forty asks)");
        } finally {
            data.setSufix(before);
            QuestState.reset(p);
        }
        h.succeed();
    }

    // --- saves from before this version

    /** The world's Clan record as 0.1.4 wrote it: no lives kept, nobody recorded as a player who leads. */
    private static net.minecraft.nbt.CompoundTag oldLifeTag(UUID thunder) {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putBoolean("created", true);
        t.putBoolean("tunnelsPopulated", true);
        t.putString("mode", "free");
        t.putString("tunnelKeeper", "Fernsmoke");
        String[] leaders = {"Sedgestar", "Rushstar", "Gorsestar", "Yewstar"};
        for (Clan c : Clan.values()) {
            net.minecraft.nbt.CompoundTag ct = new net.minecraft.nbt.CompoundTag();
            ct.putUUID("id", c == Clan.THUNDER ? thunder : UUID.randomUUID());
            ct.putString("leader", leaders[c.ordinal()]);
            ct.putBoolean("populated", c == Clan.THUNDER);
            t.put(c.name(), ct);
        }
        t.put("starclan", new net.minecraft.nbt.ListTag());
        return t;
    }

    /** An apprentice's quests as 0.1.4 saved them, on step 4 with the patrol half walked. */
    private static net.minecraft.nbt.CompoundTag oldQuestTag(UUID mentor) {
        net.minecraft.nbt.CompoundTag q = new net.minecraft.nbt.CompoundTag();
        q.putInt("storyStep", 4);
        q.putString("path", "warrior");
        q.putString("mentor", "Oakfur");
        q.putUUID("mentorId", mentor);
        q.putInt("standing", 18);
        q.putInt("mentorSeenStep", 4);
        q.putLong("mentorCheckIn", 17767);
        q.putLong("mentorNext", 18650);
        net.minecraft.nbt.CompoundTag done = new net.minecraft.nbt.CompoundTag();
        done.putInt("STORY_HUNT", 1);
        q.put("completed", done);
        net.minecraft.nbt.CompoundTag story = new net.minecraft.nbt.CompoundTag();
        story.putString("kind", "STORY_PATROL");
        story.putString("title", "Your first border patrol");
        story.putString("desc", "Oakfur sends you to walk ThunderClan's borders.");
        story.putString("giver", "Oakfur");
        story.putUUID("giverId", mentor);
        story.putBoolean("story", true);
        story.putInt("standing", 6);
        story.putInt("xp", 50);
        story.putString("reward", "");
        story.putInt("rewardCount", 0);
        net.minecraft.nbt.ListTag objectives = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 2; i++) {
            net.minecraft.nbt.CompoundTag o = new net.minecraft.nbt.CompoundTag();
            o.putString("type", "VISIT");
            o.putString("text", "Mark the border south of camp");
            o.putString("key", "");
            o.putInt("goal", 1);
            o.putDouble("x", 1376.4 + i * 300);
            o.putDouble("z", 146.0);
            o.putDouble("r", 14);
            o.putInt("y", 0);
            o.putInt("progress", i == 0 ? 1 : 0);
            o.putLong("started", 0);
            objectives.add(o);
        }
        story.put("objectives", objectives);
        q.put("story", story);
        return q;
    }

    /**
     * A player's saved quests, put on a player of this version: everything reads back as it was saved, nothing new is
     * made up, and an apprentice whose mentor is on the Clan's list still has that mentor and the same step.
     */
    private static void checkQuests(GameTestHelper h, String what, net.minecraft.nbt.CompoundTag saved, java.util.Set<UUID> clanList, LifeData life) {
        ServerPlayer p = player(h, "rising_import_" + what);
        ClanData data = ClanData.get(h.getLevel());
        ClanData.Clan wc = data.createClan("RisingImport" + Math.abs(what.hashCode()), 0x808080, UUID.randomUUID(), "Teststar", "", 0);
        try {
            QuestState.root(p).merge(saved.copy());
            int step = saved.getInt("storyStep");
            h.assertTrue(QuestState.storyStep(p) == step && QuestState.standing(p) == saved.getInt("standing"), what + ": the step and the standing are kept");
            h.assertTrue(QuestState.mentorName(p).equals(saved.getString("mentor")), what + ": the mentor's name is kept");
            UUID mentor = saved.hasUUID("mentorId") ? saved.getUUID("mentorId") : null;
            h.assertTrue(java.util.Objects.equals(QuestState.mentorId(p), mentor), what + ": and which cat it is");
            if (saved.contains("story")) {
                Task t = QuestState.story(p);
                var objectives = saved.getCompound("story").getList("objectives", net.minecraft.nbt.Tag.TAG_COMPOUND);
                h.assertTrue(t != null && t.kind.name().equals(saved.getCompound("story").getString("kind")), what + ": the step in hand loads");
                h.assertTrue(t.objectives.size() == objectives.size(), what + ": with every objective");
                for (int i = 0; i < objectives.size(); i++) {
                    h.assertTrue(t.objectives.get(i).progress == objectives.getCompound(i).getInt("progress"), what + ": and its progress");
                }
            }
            h.assertTrue(QuestState.duties(p).size() == saved.getList("duties", net.minecraft.nbt.Tag.TAG_COMPOUND).size(), what + ": every duty loads");
            h.assertTrue(QuestState.lesson(p) == null && Pupils.pupilId(p) == null && Pupils.trained(p) == 0, what + ": no apprentice is made up");
            QuestState.active(p);
            if (step >= 1 && step <= 6 && mentor != null) {
                for (UUID id : clanList) wc.clanCats.put(id, new ClanData.ClanCat());
                boolean fallen = life.starClan().stream().anyMatch(f -> f.name().equalsIgnoreCase(saved.getString("mentor")));
                if (clanList.contains(mentor) && !fallen) {
                    h.assertTrue(Mentors.lost(p, wc, life) == null, what + ": a mentor on the Clan's list is still the mentor");
                }
                Task t = QuestState.story(p);
                h.assertTrue(t == null || mentor.equals(t.giverId), what + ": and the step is still theirs to take");
            }
            if (step == QuestState.WARRIOR) {
                h.assertTrue(Ranks.deputyNeeds(p).stream().anyMatch(s -> s.contains("apprentice")), what + ": a named cat's next step is an apprentice of their own");
            }
        } finally {
            data.deleteClan(h.getLevel(), wc.clanUUID);
            QuestState.reset(p);
        }
    }

    /**
     * Saves from 0.1.4 open in this version as they were (asked for 2026-11-23). The shapes here are those of real
     * worlds; set GJ_OLD_SAVES to a folder of copied worlds (each with data/ and playerdata/) and every one of them is
     * read through the same checks, Clan record and players both.
     */
    @GameTest(template = "empty")
    public static void anOldSaveLoadsAsItWas(GameTestHelper h) {
        var reg = h.getLevel().registryAccess();
        UUID thunder = UUID.randomUUID(), mentor = UUID.randomUUID();
        LifeData life = LifeData.fromTag(oldLifeTag(thunder), reg);
        h.assertTrue(life.created() && life.mode() == com.ninjacat.greatjourney.clanlands.life.Mode.FREE, "the world's Clans and its mode are read");
        h.assertTrue(life.clanOf(thunder) == Clan.THUNDER && life.leader(Clan.THUNDER).equals("Sedgestar"), "each Clan with its id and its leader");
        for (Clan c : Clan.values()) {
            h.assertTrue(life.leaderPlayer(c) == null && life.lives(c) == LifeData.LEADER_LIVES, c + ": a Clan cat leads, with a leader's nine lives");
        }
        LifeData again = LifeData.fromTag(life.save(new net.minecraft.nbt.CompoundTag(), reg), reg);
        h.assertTrue(again.leader(Clan.WIND).equals(life.leader(Clan.WIND)) && again.leaderPlayer(Clan.WIND) == null, "and saved again it reads the same");
        checkQuests(h, "apprentice", oldQuestTag(mentor), java.util.Set.of(mentor), life);
        net.minecraft.nbt.CompoundTag named = oldQuestTag(mentor);
        named.putInt("storyStep", QuestState.WARRIOR);
        named.remove("story");
        named.putString("warriorName", "Bluefur");
        checkQuests(h, "warrior", named, java.util.Set.of(), life);

        String dir = System.getenv("GJ_OLD_SAVES");
        if (dir == null || dir.isBlank()) {
            h.succeed();
            return;
        }
        int worlds = 0, players = 0;
        try (var list = java.nio.file.Files.list(java.nio.file.Path.of(dir))) {
            for (java.nio.file.Path world : list.filter(java.nio.file.Files::isDirectory).sorted().toList()) {
                java.nio.file.Path clans = world.resolve("data/greatjourney_clans.dat");
                if (!java.nio.file.Files.exists(clans)) continue;
                worlds++;
                String name = world.getFileName().toString();
                LifeData real = LifeData.fromTag(net.minecraft.nbt.NbtIo.readCompressed(clans, net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompound("data"), reg);
                h.assertTrue(real.created() && real.mode() != null, name + ": its Clans and mode are read");
                for (Clan c : Clan.values()) {
                    h.assertTrue(real.clanId(c) != null && !real.leader(c).equals(c.name()) && real.leaderPlayer(c) == null, name + ": " + c + " has its id and its leader");
                }
                var wce = net.minecraft.nbt.NbtIo.readCompressed(world.resolve("data/wce_clans.dat"), net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompound("data");
                try (var files = java.nio.file.Files.list(world.resolve("playerdata"))) {
                    for (java.nio.file.Path f : files.filter(x -> x.toString().endsWith(".dat")).toList()) {
                        var tag = net.minecraft.nbt.NbtIo.readCompressed(f, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                        var quests = tag.getCompound("NeoForgeData").getCompound("PlayerPersisted").getCompound("greatjourney_quests");
                        if (quests.isEmpty()) continue;
                        java.util.Set<UUID> clanList = new java.util.HashSet<>();
                        for (var ct : wce.getList("Clans", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                            var clan = (net.minecraft.nbt.CompoundTag) ct;
                            boolean mine = false;
                            for (var m : clan.getList("Members", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                                mine |= ((net.minecraft.nbt.CompoundTag) m).getUUID("UUID").equals(tag.getUUID("UUID"));
                            }
                            if (!mine) continue;
                            for (var cat : clan.getList("ClanCats", net.minecraft.nbt.Tag.TAG_COMPOUND)) clanList.add(((net.minecraft.nbt.CompoundTag) cat).getUUID("UUID"));
                        }
                        checkQuests(h, name + "_" + players++, quests, clanList, real);
                    }
                }
            }
        } catch (java.io.IOException e) {
            h.fail("an old save could not be read: " + e);
        }
        h.assertTrue(worlds > 0 && players > 0, "GJ_OLD_SAVES is set, so it holds worlds to check (found " + worlds + " worlds, " + players + " players)");
        org.slf4j.LoggerFactory.getLogger(RisingGameTests.class).info("Old saves checked: {} worlds, {} players", worlds, players);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theCatsWhoFollowALoner(GameTestHelper h) {
        ServerPlayer p = player(h, "rising_loner");
        List<WCatEntity> made = new ArrayList<>();
        try {
            WCatEntity wild = cat(h, "Wild", WCatEntity.Rank.NONE, new BlockPos(2, 2, 2), null);
            WCatEntity mine = cat(h, "Mine", WCatEntity.Rank.NONE, new BlockPos(4, 2, 2), null);
            WCatEntity theirs = cat(h, "Theirs", WCatEntity.Rank.NONE, new BlockPos(6, 2, 2), null);
            made.addAll(List.of(wild, mine, theirs));
            p.setPos(wild.getX(), wild.getY(), wild.getZ());
            mine.setTame(true, false);
            mine.setOwnerUUID(p.getUUID());
            theirs.setTame(true, false);
            theirs.setOwnerUUID(UUID.randomUUID());
            h.assertTrue(Founding.followers(p) == 1, "only the cats this loner has won over follow them");
            p.setPos(wild.getX() + Founding.FOLLOW_REACH * 3, wild.getY(), wild.getZ());
            h.assertTrue(Founding.followers(p) == 0, "and only while they are near");
            h.assertFalse(Founding.founder(p, null), "a cat with no Clan has founded none");
        } finally {
            made.forEach(WCatEntity::discard);
            QuestState.reset(p);
        }
        h.succeed();
    }
}
