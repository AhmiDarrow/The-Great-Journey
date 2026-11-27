package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Lead;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.events.Cats;
import com.ninjacat.greatjourney.clanlands.life.events.EventNews;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.clan.ClanData.ClanPlayerRank;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A grown cat's own apprentice. A player who is a warrior (the deputy, the leader) or a full medicine cat is given one
 * of the Clan's apprentices in camp by the leader, and teaches them in four lessons, the apprentice at their side:
 * for a warrior the borders, hunting, the hunter's crouch and an assessment; for a medicine cat herbs, remedies, the
 * elders and a test. Then the leader gives the apprentice their warrior name in camp (a medicine cat names their own
 * apprentice), and after a day's rest the next apprentice comes. Asked for after play (2026-11-23): a warrior or a
 * full medicine cat was never given an apprentice.
 *
 * <p>The pairing is kept on both sides: the player remembers the apprentice by id, and the cat carries the player's id
 * ({@link #KEY}) and shows them as its mentor under its name. It is checked every second the player is online and put
 * right from what is true: an apprentice that died or left the Clan is mourned and replaced; one named a warrior some
 * other way counts as trained; a mentor who retires gives the apprentice up; a mentor away for {@link #AWAY} loses
 * the mark, so the Clan trains the apprentice on without them.
 */
public final class Pupils {
    private Pupils() {}

    /** On the apprentice: the player who teaches it, and when that player was last seen. */
    static final String KEY = "greatjourney_pupil_of", SEEN = "greatjourney_pupil_seen";
    static final int LESSONS = 4;
    /** A day between one apprentice's naming and the next apprentice. */
    static final long REST = 24000L;
    /** A mentor not seen for this long (game time) no longer holds the apprentice. */
    static final long AWAY = 24000L * 8;
    /** A lesson counts only with the apprentice this near. */
    static final double NEAR = 48;
    /** Holds the land a stray apprentice was last seen on for ten seconds, so it loads and can come home. */
    private static final TicketType<ChunkPos> FETCH = TicketType.create("greatjourney_pupil", Comparator.comparingLong(ChunkPos::toLong), 200);

    static UUID pupilId(ServerPlayer p) {
        CompoundTag root = QuestState.root(p);
        return root.hasUUID("pupilId") ? root.getUUID("pupilId") : null;
    }

    static String pupilName(ServerPlayer p) {
        return QuestState.root(p).getString("pupilName");
    }

    /** How many apprentices the player has trained to their names. */
    public static int trained(ServerPlayer p) {
        return QuestState.root(p).getInt("pupilsTrained");
    }

    /** Whether a player teaches this apprentice now (their mark is on it and still holds). */
    public static boolean isPupil(WCatEntity cat) {
        CompoundTag d = cat.getPersistentData();
        if (!d.hasUUID(KEY)) return false;
        MinecraftServer server = cat.level().getServer();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(d.getUUID(KEY));
        boolean still = p != null ? cat.getUUID().equals(pupilId(p)) : cat.level().getGameTime() - d.getLong(SEEN) < AWAY;
        if (!still) unmark(cat);
        return still;
    }

    private static void unmark(WCatEntity cat) {
        UUID was = cat.getPersistentData().hasUUID(KEY) ? cat.getPersistentData().getUUID(KEY) : null;
        cat.getPersistentData().remove(KEY);
        cat.getPersistentData().remove(SEEN);
        if (was != null && was.equals(Roles.mentorOf(cat))) Roles.setPlayerMentor(cat, null, "");
    }

    private static WCatEntity loaded(ServerPlayer p) {
        UUID id = pupilId(p);
        return id != null && p.getServer().overworld().getEntity(id) instanceof WCatEntity c ? c : null;
    }

    /** Whether the player's apprentice is at their side (near enough for a lesson to count). */
    static boolean near(ServerPlayer p) {
        WCatEntity cat = loaded(p);
        return cat != null && cat.isAlive() && cat.level() == p.level() && cat.distanceTo(p) <= NEAR;
    }

    /** Whether progress on this task counts now: a lesson only with the apprentice along. */
    static boolean counts(ServerPlayer p, Task t) {
        return t.kind != Task.Kind.LESSON || near(p);
    }

    private static boolean teaches(ClanPlayerRank rank) {
        return rank == ClanPlayerRank.WARRIOR || rank == ClanPlayerRank.DEPUTY || rank == ClanPlayerRank.LEADER || rank == ClanPlayerRank.MEDICINE;
    }

    /** Once a second from {@link Story#tick} for a named cat (a warrior or a full medicine cat) of a book Clan. */
    static void tick(ServerPlayer p, Clan clan, ClanData.Clan wc, Landmark camp, double toCamp) {
        ClanPlayerRank rank = wc.members.get(p.getUUID());
        CompoundTag root = QuestState.root(p);
        ServerLevel ow = p.getServer().overworld();
        long now = ow.getGameTime();
        UUID id = pupilId(p);
        String paw = pupilName(p);
        if (!teaches(rank)) {
            // an elder or a queen has given up their duties: the apprentice goes to another mentor
            if (id != null) end(p, paw + " will finish their training with another mentor.", 0);
            return;
        }
        boolean healer = rank == ClanPlayerRank.MEDICINE;
        if (id == null) {
            if (now >= root.getLong("pupilNext") && toCamp < camp.radius() + 20 && p.level() == ow && !Tasks.night(ow)) assign(p, clan, wc, healer);
            return;
        }
        WCatEntity cat = loaded(p);
        if (cat == null) {
            // left out in the territory (its mentor died out there, or outran it) while its mentor is home: the land it
            // was last seen on is loaded for a moment, and it comes home below. It used to stay out there for good, and
            // the naming in camp never came (reported 2026-11-23)
            if (root.contains("pupilAt") && toCamp < camp.radius() + 100 && p.level() == ow) {
                BlockPos seen = BlockPos.of(root.getLong("pupilAt"));
                ChunkPos at = new ChunkPos(seen);
                // (in this Clan's lands only: not the forest the Clans left behind)
                if (Math.hypot(seen.getX() - camp.x(), seen.getZ() - camp.z()) < 3000) ow.getChunkSource().addRegionTicket(FETCH, at, 2, at);
            } else if (!root.contains("pupilAt") && !root.getBoolean("pupilStray") && wc.clanCats.containsKey(id)
                    && toCamp < camp.radius() + 40 && Cats.campCatsLoaded(ow, clan)) {
                // left out there before its place was kept (0.1.5): it cannot be fetched, so its mentor is told where to look
                root.putBoolean("pupilStray", true);
                Ui.push(p);
            }
            // not loaded: still theirs while the Clan's own list has it (it drops a cat that dies or leaves)
            if (wc.clanCats.containsKey(id) || toCamp > camp.radius() + 100 || !Cats.campCatsLoaded(ow, clan)) return;
            // looked for in camp first: the Campaign makes the Clan's cats anew when it settles, under the same names
            for (WCatEntity c : Cats.atCamp(ow, clan)) {
                if (c.getRank() != WCatEntity.Rank.APPRENTICE || !Kin.name(c).equalsIgnoreCase(paw) || isPupil(c)) continue;
                root.putUUID("pupilId", c.getUUID());
                QuestState.setLesson(p, null);                     // given again by the cat as it is now
                return;
            }
            end(p, paw + " is gone from the Clan. Your apprentice will not be forgotten.", REST);
            return;
        }
        if (!cat.isAlive()) {
            end(p, paw + " hunts with StarClan now. No mentor is ever ready for that.", REST);
            return;
        }
        if (!wc.clanUUID.equals(cat.getClanUUID())) {
            end(p, paw + " is gone from the Clan.", REST);
            return;
        }
        int step = root.getInt("pupilStep");
        if (cat.getRank() != WCatEntity.Rank.APPRENTICE) {
            // named some other way (grown up, or the Clan held the ceremony while the mentor was away): the training
            // still counts, in full if every lesson was given
            boolean done = step > LESSONS;
            root.putInt("pupilsTrained", trained(p) + 1);
            QuestState.addStanding(p, done ? 15 : 10);
            if (done) p.giveExperiencePoints(100);
            end(p, "Your apprentice has their name: " + Kin.name(cat) + ". You taught them well" + (done ? ", and the Clan honours you for it (+15 standing)." : "."), REST);
            return;
        }
        // the mark, both ways, the name as it is now, and where it was last seen
        cat.getPersistentData().putUUID(KEY, p.getUUID());
        cat.getPersistentData().putLong(SEEN, now);
        Roles.setPlayerMentor(cat, p.getUUID(), Joining.catName(p));
        if (!Kin.name(cat).equals(paw)) root.putString("pupilName", Kin.name(cat));
        root.putLong("pupilAt", cat.blockPosition().asLong());
        root.remove("pupilStray");
        // asked along, and parted since (the mentor died, slept, logged out, crossed to another world): it falls in
        // again by itself once they are near each other, and need not be asked twice
        if (root.getBoolean("pupilAlong") && Lead.of(p) == null && !Lead.leading(cat) && cat.level() == p.level() && cat.distanceTo(p) < 64) {
            Lead.accompany(p, cat, 4, Integer.MAX_VALUE, (l, how) -> { });
        }
        boolean home = comeHome(p, cat, camp);

        if (step > LESSONS) {
            // in camp together: the naming (the apprentice anywhere in camp, not only at its mentor's heels)
            if (toCamp < camp.radius() + 6 && home) graduate(p, clan, wc, cat, healer);
            return;
        }
        Task lesson = QuestState.lesson(p);
        if (lesson == null) {
            QuestState.setLesson(p, lessonFor(p, clan, cat, Math.max(1, step), healer));
            root.putInt("pupilStep", Math.max(1, step));
            Ui.push(p);
            return;
        }
        // out of camp with a lesson to give and no apprentice: said now and then, so the lesson is not walked for nothing
        if (!lesson.readyToHandIn() && toCamp > camp.radius() + 40 && !near(p) && now - root.getLong("pupilHint") > 20L * 120) {
            root.putLong("pupilHint", now);
            Ui.subtitle(p, List.of(Line.narrate(paw + " is not with you. A lesson only counts with your apprentice at your side: "
                    + "talk to them in camp and ask them to come along.")));
        }
    }

    /**
     * An apprentice not at its mentor's side belongs in camp, where its mentor looks for it: one left out in the
     * territory (its mentor died, logged out, or went on without it) is brought home, out of its mentor's sight, or
     * walks home while they watch. One the Clan itself has sent out (a patrol, a Gathering, an event) is left to it.
     * Returns whether the apprentice is in camp now.
     */
    static boolean comeHome(ServerPlayer p, WCatEntity cat, Landmark camp) {
        ServerLevel ow = p.getServer().overworld();
        double away = Math.hypot(cat.getX() - camp.x(), cat.getZ() - camp.z());
        if (away <= camp.radius() + 40) return true;
        if (Lead.leading(cat) || cat.isNoAi() || cat.isPassenger() || cat.getTags().contains(BookClans.BUSY_TAG)
                || cat.getTags().contains(com.ninjacat.greatjourney.clanlands.life.events.CampRoutine.TAG) || cat.getTags().contains("greatjourney_gathering")
                || cat.onBorderPatrolFlag || cat.onHuntingPatrolFlag || cat.returnHomeFlag || cat.returningFromPatrol) return false;
        BlockPos to = BookClans.safeSpot(ow, (int) Math.floor(camp.x()) + cat.getRandom().nextInt(7) - 3, (int) Math.floor(camp.z()) + cat.getRandom().nextInt(7) - 3, 4);
        net.minecraft.world.phys.Vec3 spot = new net.minecraft.world.phys.Vec3(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        boolean seen = p.level() == ow && (Lead.inSight(p, cat.position()) || Lead.inSight(p, spot));
        if (!seen) {
            cat.getNavigation().stop();
            cat.teleportTo(spot.x, spot.y, spot.z);
            cat.fallDistance = 0;
            return true;
        }
        // watched: it walks, unless its mentor has come up to it (to ask it along)
        if (cat.distanceTo(p) > 16 && cat.getNavigation().isDone()) cat.getNavigation().moveTo(camp.x(), to.getY(), camp.z(), 1.2);
        return false;
    }

    /** Ends the pairing (the apprentice named, lost, or given up), with a word to the player and a rest before the next. */
    private static void end(ServerPlayer p, String words, long rest) {
        WCatEntity cat = loaded(p);
        if (cat != null) {
            Lead lead = Lead.of(p);
            if (lead != null && lead.cat() == cat) Lead.stop(p);
            unmark(cat);
        }
        CompoundTag root = QuestState.root(p);
        root.remove("pupilId");
        root.remove("pupilName");
        root.remove("pupilStep");
        root.remove("pupilAt");
        root.remove("pupilStray");
        root.remove("pupilAlong");
        root.putLong("pupilNext", p.getServer().overworld().getGameTime() + rest);
        QuestState.setLesson(p, null);
        Ui.subtitle(p, List.of(Line.narrate(words)));
        Ui.push(p);
    }

    /**
     * The leader gives the player one of the Clan's apprentices in camp: one with no mentor first, else the nearest
     * (its mentor goes back to the patrols). False when the Clan has no apprentice in camp yet.
     */
    static boolean assign(ServerPlayer p, Clan clan, ClanData.Clan wc, boolean healer) {
        ServerLevel ow = p.getServer().overworld();
        LifeData life = LifeData.get(ow);
        List<WCatEntity> free = new ArrayList<>();
        for (WCatEntity c : Cats.atCamp(ow, clan)) {
            if (c.getRank() != WCatEntity.Rank.APPRENTICE || c.getCustomName() == null || c.isNoAi() || isPupil(c)) continue;
            if (Lead.leading(c) || c.getTags().contains(BookClans.BUSY_TAG) || c.getTags().contains(Lead.GUIDE_TAG)) continue;
            free.add(c);
        }
        if (free.isEmpty()) return false;
        WCatEntity cat = free.stream().min(Comparator.comparing((WCatEntity c) -> Roles.mentorOf(c) != null)
                .thenComparingDouble(c -> c.distanceToSqr(p))).orElseThrow();
        UUID before = Roles.mentorOf(cat);
        String beforeName = before != null && ow.getEntity(before) instanceof WCatEntity m ? Kin.name(m) : null;
        String paw = Kin.name(cat), you = Joining.catName(p);
        Roles.setPlayerMentor(cat, p.getUUID(), you);
        cat.getPersistentData().putUUID(KEY, p.getUUID());
        cat.getPersistentData().putLong(SEEN, ow.getGameTime());
        CompoundTag root = QuestState.root(p);
        root.putUUID("pupilId", cat.getUUID());
        root.putString("pupilName", paw);
        root.putInt("pupilStep", 1);
        Task lesson = lessonFor(p, clan, cat, 1, healer);
        QuestState.setLesson(p, lesson);

        int colour = BookClans.Info.of(clan).colour;
        String leader = life.leader(clan);
        WCatEntity leaderCat = Story.catNamed(p, clan, leader);
        List<Line> lines = new ArrayList<>();
        if (Ranks.leads(p)) {
            // the leader takes an apprentice of their own, as leaders do
            lines.add(Line.say(you, colour, "Cats of " + BookClans.Info.of(clan).title + ", I will train " + paw + " myself."));
        } else if (healer) {
            lines.add(Line.say(leader, colour, you + ", " + paw + " has shown a gentle paw with the sick, and StarClan has spoken. "
                    + "From this day " + paw + " will learn the ways of a medicine cat from you.").speakerEntity(leaderCat));
        } else {
            lines.add(Line.say(leader, colour, you + ", you are ready for an apprentice. "
                    + (beforeName != null ? beforeName + " is needed on the patrols: you will finish " + paw + "'s training."
                    : "You will be mentor to " + paw + ".") + " I trust you will pass on all you have learned.").speakerEntity(leaderCat));
        }
        lines.add(Line.narrate(paw + " touches noses with you. You have an apprentice of your own."));
        lines.add(Line.say(paw, colour, greeting(cat, healer)).speakerEntity(cat));
        lines.add(Line.narrate(lesson.title + ": " + lesson.description));
        lines.add(Line.narrate("Talk to " + paw + " to take them out with you: a lesson only counts with your apprentice at your side."));
        Ui.play(p, lines);
        Ui.push(p);
        return true;
    }

    private static String greeting(WCatEntity cat, boolean healer) {
        return healer ? "I'll learn every herb there is, I promise! Where do we start?"
                : switch (cat.getRandom().nextInt(3)) {
                    case 0 -> "I won't let you down! What do we do first?";
                    case 1 -> "Can we go out now? I've been waiting all moon!";
                    default -> "I'll work harder than any apprentice in the Clan. You'll see.";
                };
    }

    static final String[] TITLES = {"", "Walk the borders", "Teach the hunt", "The hunter's crouch", "The assessment"};
    static final String[] MED_TITLES = {"", "Where the herbs grow", "Poultices and poppy seeds", "The elders' aches", "The test"};

    /** Lesson {@code step} (1..4) for this apprentice: the same lesson each time it is asked for (seeded by the pair). */
    static Task lessonFor(ServerPlayer p, Clan clan, WCatEntity cat, int step, boolean healer) {
        String paw = Kin.name(cat);
        UUID id = cat.getUUID();
        long seed = p.getUUID().getMostSignificantBits() ^ id.getLeastSignificantBits();
        RandomSource r = RandomSource.create(seed + step);
        String title = (healer ? MED_TITLES : TITLES)[step] + " with " + paw;
        List<Task.Objective> obj;
        String text;
        if (healer) {
            // the same work the player learned by, done now with the apprentice watching
            obj = Tasks.medicineStep(step == 1 ? 2 : step == 2 ? 3 : step == 3 ? 4 : 6, clan, paw, id, seed).objectives;
            text = switch (step) {
                case 1 -> "Take " + paw + " out and show them where the herbs grow, and how to pick the leaves without tearing up the plant.";
                case 2 -> "Show " + paw + " how herbs become remedies: chew dock leaves into a poultice, and shake the seeds from a poppy head.";
                case 3 -> "Take " + paw + " to the elders' den, and show them how to ease old joints and make a soft nest.";
                default -> "Name three herbs for " + paw + " and find them together, two of each: their test, and yours as a mentor.";
            };
        } else {
            switch (step) {
                case 1 -> {
                    obj = Tasks.borderPatrol(ClanWorld.current(), clan, paw, id, r).objectives;
                    text = "Walk " + Tasks.clanName(clan) + "'s borders with " + paw + ", and show them where the scent markers are and what lies beyond.";
                }
                case 2 -> {
                    obj = List.of(Task.Objective.catchPrey("Catch 3 prey with " + paw + " watching", Kin.preySet(clan), 3));
                    text = "Take " + paw + " hunting. Show them how it is done: " + Kin.preyWords(clan) + ", caught cleanly, for the Clan.";
                }
                case 3 -> {
                    obj = List.of(Task.Objective.stalk("Catch 2 prey from the hunter's crouch (sneak)", Kin.preySet(clan), 2));
                    text = "Show " + paw + " the hunter's crouch: creep up on prey and pounce before it hears you.";
                }
                default -> {
                    obj = List.of(Task.Objective.catchInTime("Catch 2 prey within three minutes", Kin.preySet(clan), 2, 180));
                    text = paw + "'s assessment. Hunt side by side, swift and silent: two catches, one quickly after the other.";
                }
            }
        }
        return new Task(Task.Kind.LESSON, title, text, paw, id, false, new ArrayList<>(obj), step == LESSONS ? 10 : 6, step == LESSONS ? 80 : 40, "", 0);
    }

    // --- talking to the apprentice

    /** The player talks to their own apprentice; false for any other cat. */
    static boolean greet(ServerPlayer p, WCatEntity cat) {
        if (!cat.getUUID().equals(pupilId(p))) return false;
        String paw = Kin.name(cat), you = Joining.catName(p);
        int colour = Kin.colour(p);
        Task lesson = QuestState.lesson(p);
        Lead lead = Lead.of(p);
        boolean along = lead != null && lead.cat() == cat;
        Choice bye = Choice.of("Goodbye", "greatjourney quest bye");
        Choice walk = along ? Choice.of("Wait for me in camp", "greatjourney quest pupilstay") : Choice.of("Come with me", "greatjourney quest pupilcome " + cat.getId());
        Line line;
        if (lesson == null) {
            line = Line.say(paw, colour, "Is it time, " + you + "? Will I get my name today? Take me back to camp!").withChoices(walk, bye);
        } else if (lesson.readyToHandIn() && Talk.hasDeliveries(p, lesson)) {
            line = Line.say(paw, colour, "Did I do it right, " + you + "?").withChoices(
                    Choice.of("You did well. That's the lesson", "greatjourney quest give " + cat.getId()), walk, bye);
        } else {
            String next = "";
            for (Task.Objective o : lesson.objectives) {
                if (!o.done()) {
                    next = o.text;
                    break;
                }
            }
            // a duty this cat set before becoming an apprentice of the player's can still be handed in
            boolean owed = QuestState.duties(p).stream().anyMatch(d -> cat.getUUID().equals(d.giverId) && d.readyToHandIn() && Talk.hasDeliveries(p, d));
            line = Line.say(paw, colour, "What are we learning today, " + you + "?").withChoices(owed
                    ? new Choice[] {Choice.of("I did what you asked", "greatjourney quest give " + cat.getId()), walk, bye}
                    : new Choice[] {walk, bye});
            Ui.play(p, List.of(line.speakerEntity(cat), Line.narrate(lesson.title + ". " + (next.isEmpty() ? "" : next + "."))));
            return true;
        }
        Ui.play(p, List.of(line.speakerEntity(cat)));
        return true;
    }

    /**
     * "Come with me": the apprentice keeps at its mentor's side until told to wait in camp. It used to leave after
     * twenty minutes without a word, the lessons stopped counting, and it was left wherever it stood.
     */
    static void come(ServerPlayer p, WCatEntity cat) {
        if (!cat.getUUID().equals(pupilId(p))) return;
        Lead lead = Lead.accompany(p, cat, 4, Integer.MAX_VALUE, (l, how) -> { });
        if (lead != null) QuestState.root(p).putBoolean("pupilAlong", true);
        Ui.subtitle(p, List.of(Line.say(Kin.name(cat), Kin.colour(p), lead != null ? "Right behind you!" : "I can't just now. Ask me again in a moment.").speakerEntity(cat)));
    }

    static void stay(ServerPlayer p) {
        WCatEntity cat = loaded(p);
        Lead lead = Lead.of(p);
        QuestState.root(p).remove("pupilAlong");
        if (cat == null || lead == null || lead.cat() != cat) return;
        Lead.stop(p);
        Ui.subtitle(p, List.of(Line.say(Kin.name(cat), Kin.colour(p), "I'll be in camp.").speakerEntity(cat)));
    }

    /** A lesson handed in ({@link Rewards}): on to the next, or ready for the naming in camp after the last. */
    static void lessonDone(ServerPlayer p, WCatEntity cat) {
        CompoundTag root = QuestState.root(p);
        int step = Math.max(1, root.getInt("pupilStep")) + 1;
        root.putInt("pupilStep", step);
        QuestState.setLesson(p, null);
        Clan clan = Kin.bookClan(p);
        ClanData.Clan wc = Joining.clanOf(p);
        String paw = cat != null ? Kin.name(cat) : pupilName(p);
        int colour = Kin.colour(p);
        boolean healer = wc != null && wc.members.get(p.getUUID()) == ClanPlayerRank.MEDICINE;
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(paw, colour, switch (step) {
            case 2 -> healer ? "I never knew there were so many! I'll remember every one." : "I'll know those borders in my sleep now.";
            case 3 -> healer ? "It smells awful. But it works!" : "Did you see? I nearly had that one myself!";
            case 4 -> healer ? "They told me three stories and I didn't mind a bit." : "Low, slow, and not a whisker moving. I've got it.";
            default -> "Was that it? Was that my assessment? Did I pass?";
        }).speakerEntity(cat));
        if (step <= LESSONS && clan != null && cat != null) {
            Task next = lessonFor(p, clan, cat, step, healer);
            QuestState.setLesson(p, next);
            lines.add(Line.narrate("Next: " + next.title + ". " + next.description));
        } else {
            String leader = clan == null ? "the leader" : LifeData.get(p.getServer().overworld()).leader(clan);
            lines.add(Line.narrate(healer ? paw + " is ready. Take them back to camp, and give them their full name before the Clan."
                    : paw + " is ready. Take them back to camp: " + (Ranks.leads(p) ? "you" : leader) + " will give them their warrior name."));
        }
        Ui.play(p, lines);
    }

    /** The naming, in camp: the apprentice takes a full name, the mentor is honoured, and the pairing ends. */
    private static void graduate(ServerPlayer p, Clan clan, ClanData.Clan wc, WCatEntity cat, boolean healer) {
        ServerLevel ow = p.getServer().overworld();
        LifeData life = LifeData.get(ow);
        BookClans.Info info = BookClans.Info.of(clan);
        if (cat.isBaby()) {
            // born in the nursery and not full-grown yet: Warrior Cats Events names such a cat itself the moment it
            // grows up (and would name it over again later, whatever we called it), so it grows up now and the Clan's
            // own ceremony for it follows; its training is counted in full the next second (tick)
            cat.setAge(0);
            return;
        }
        String before = Kin.name(cat), you = Joining.catName(p);
        String prefix = Cats.prefixOf(cat);
        String after = Cats.warriorName(prefix, cat.getRandom(), Cats.namesIn(Cats.atCamp(ow, clan)));
        Cats.rename(cat, prefix, after);
        cat.setRank(healer ? WCatEntity.Rank.MEDICINE : WCatEntity.Rank.WARRIOR);
        cat.setAppScale(false);
        CompoundTag root = QuestState.root(p);
        root.putInt("pupilsTrained", trained(p) + 1);
        QuestState.addStanding(p, 15);
        p.giveExperiencePoints(100);

        // the leader names a warrior (a player who leads speaks the words themselves); a medicine cat names their own
        boolean own = healer || Ranks.leads(p);
        String by = own ? you : life.leader(clan);
        WCatEntity byCat = own ? null : Story.catNamed(p, clan, by);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(by, info.colour, "I, " + by + ", " + (healer ? "medicine cat" : "leader") + " of " + info.title
                + ", call upon my warrior ancestors to look down on this apprentice. " + (own ? "They have" : you + " has taught them well, and they have")
                + " trained hard to understand " + (healer ? "the ways of a medicine cat." : "the ways of your noble code.")).speakerEntity(byCat));
        lines.add(Line.say(by, info.colour, before + ", do you promise to " + (healer ? "stand apart from rivalry between Clan and Clan, and to protect all cats equally"
                : "uphold the warrior code, and to protect and defend your Clan") + ", even at the cost of your life?").speakerEntity(byCat));
        lines.add(Line.say(before, info.colour, "I do.").speakerEntity(cat));
        lines.add(Line.say(by, info.colour, "Then by the powers of StarClan, I give you your " + (healer ? "true name as a medicine cat. " : "warrior name. ")
                + before + ", from this moment you will be known as " + after + ".").speakerEntity(byCat));
        lines.add(Line.narrate("The Clan calls the new name: " + after + "! " + after + "! Your apprentice is "
                + (healer ? "a medicine cat" : "a warrior") + " now, and the Clan honours you for it (+15 standing)."));
        EventNews.record(clan, before + " of " + info.title + " has earned the " + (healer ? "medicine cat" : "warrior") + " name " + after + ".");
        end(p, after + " pads away to " + (healer ? "the medicine den" : "the warriors' den") + ", tail high.", REST);
        Ui.play(p, lines);
    }

    /** One line for the journal on the player's apprentice, or null with none. */
    static String goal(ServerPlayer p) {
        if (pupilId(p) == null) {
            // why none yet, for a cat who could teach: the journal said nothing, and the wait looked like a fault
            ClanData.Clan wc = Joining.clanOf(p);
            if (wc == null || !teaches(wc.members.get(p.getUUID()))) return null;
            return p.getServer().overworld().getGameTime() < QuestState.root(p).getLong("pupilNext") ? "Your next apprentice will be named in a day or so."
                    : "When the Clan has an apprentice to spare, your leader will give you one in camp, by day.";
        }
        Task lesson = QuestState.lesson(p);
        String lost = QuestState.root(p).getBoolean("pupilStray")
                ? " " + pupilName(p) + " is not in camp: go back to where you last hunted together, and they will come home." : "";
        Lead lead = Lead.of(p);
        // asked along once is enough: parted for a while, they fall in again by themselves (tick)
        boolean along = lead != null && lead.cat().getUUID().equals(pupilId(p)) || QuestState.root(p).getBoolean("pupilAlong");
        return (lesson != null ? "Train your apprentice, " + pupilName(p) + ": " + lesson.title + "." + (along ? "" : " Ask them to come with you first.")
                : "Go back to camp with " + pupilName(p) + " for their naming ceremony.") + lost;
    }
}
