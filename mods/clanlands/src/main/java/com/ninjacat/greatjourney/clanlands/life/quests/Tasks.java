package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.quests.Kin.Role;
import com.ninjacat.greatjourney.clanlands.life.quests.Task.Kind;
import com.ninjacat.greatjourney.clanlands.life.quests.Task.Objective;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.RandomSource;

/**
 * The duties Clan cats ask for, as in the books, and the steps of the apprentice path. Which duty a cat offers
 * depends on who it is (a medicine cat wants herbs, the deputy sends hunting and border patrols, elders want fresh
 * moss, queens want food for the nursery, warriors train apprentices) and changes from day to day.
 */
public final class Tasks {
    private Tasks() {}

    /**
     * Warrior Cats Events herbs a medicine cat may send you for, with what they ease. The picked herb counts, and so
     * does the whole plant, so a cat who digs one up instead of picking its leaves is not turned away.
     */
    static final String[][] HERBS = {
            {"warriorcats_events:yarrow_leaves|warriorcats_events:yarrow", "yarrow", "to help a sick cat bring up poison"},
            {"warriorcats_events:comfrey_root|warriorcats_events:comfrey_leaves|warriorcats_events:comfrey", "comfrey root", "for broken bones and wrenched paws"},
            {"warriorcats_events:catmint_leaves|warriorcats_events:catmint", "catmint", "the best cure for greencough"},
            {"warriorcats_events:feverfew_leaves|warriorcats_events:feverfew", "feverfew", "to cool a fever"},
            {"warriorcats_events:dock_leaves|warriorcats_events:dock", "dock leaves", "to soothe sore pads"},
            {"warriorcats_events:juniper_berries|warriorcats_events:juniper", "juniper berries", "for bellyache and to give strength"},
            {"warriorcats_events:burnet_leaves|warriorcats_events:burnet", "burnet", "to keep up a cat's strength"},
            {"warriorcats_events:chamomile_stems|warriorcats_events:chamomile", "chamomile", "to calm and strengthen the heart"},
            {"warriorcats_events:daisy_leaves|warriorcats_events:daisy", "daisy leaves", "for aching joints"},
            {"warriorcats_events:sorrel_leaves|warriorcats_events:sorrel", "sorrel", "to give strength for a long journey"},
    };

    static final String PREY_TAG = "#warriorcats_events:prey";
    static final String FISH_TAG = "#minecraft:fishes";
    static final String MOSS = "minecraft:moss_block|minecraft:moss_carpet";
    static final String MOSS_BALL = "warriorcats_events:moss_ball";

    /** The duty this cat asks for today (or null if it has none, like kits and the leader). */
    public static Task dutyFor(Role role, Clan clan, String giver, UUID giverId, long day) {
        RandomSource r = RandomSource.create(giverId.getLeastSignificantBits() ^ day * 31L);
        return switch (role) {
            case MEDICINE -> {
                String[] herb = HERBS[r.nextInt(HERBS.length)];
                int n = 3 + r.nextInt(3);
                yield new Task(Kind.HERBS, "Herbs for " + giver,
                        giver + " needs " + herb[1] + ", " + herb[2] + ". Find the plants and bring the leaves back to the medicine den.",
                        giver, giverId, false, list(Objective.bring("Bring " + n + " " + herb[1] + " to " + giver, herb[0], n)),
                        4, 20, "warriorcats_events:traveling_herbs", 1);
            }
            case DEPUTY -> {
                if (clan != null && r.nextBoolean()) {
                    yield borderPatrol(clan, giver, giverId, r);
                }
                yield hunt(clan, giver, giverId, r);
            }
            case ELDER -> r.nextBoolean()
                    ? new Task(Kind.MOSS, "Fresh bedding for " + giver,
                    giver + "'s old bones ache on stale bedding. Gather fresh moss for the elders' den.",
                    giver, giverId, false, list(Objective.bring("Bring 4 moss to " + giver, MOSS, 4)), 3, 15, "", 0)
                    : new Task(Kind.FEED_ELDER, "Fresh-kill for " + giver,
                    "Elders eat first after the queens. Bring " + giver + " something tasty from the fresh-kill pile or your own hunting.",
                    giver, giverId, false, list(Objective.bring("Bring 2 prey to " + giver, PREY_TAG, 2)), 3, 15, "", 0);
            case QUEEN -> r.nextBoolean()
                    ? new Task(Kind.FEED_QUEEN, "Food for the nursery",
                    giver + " is nursing kits and can't hunt. Bring her prey so the kits grow strong.",
                    giver, giverId, false, list(Objective.bring("Bring 2 prey to " + giver, PREY_TAG, 2)), 3, 15, "", 0)
                    : new Task(Kind.MOSS_BALL, "A moss-ball for the kits",
                    "The kits are bouncing off the nursery walls. Make them a moss-ball to play with and bring it to " + giver + ".",
                    giver, giverId, false, list(Objective.bring("Bring a moss-ball to " + giver, MOSS_BALL, 1)), 2, 10, "", 0);
            case WARRIOR -> r.nextBoolean()
                    ? climb(clan, giver, giverId)
                    : new Task(Kind.STALK, "Stalking practice",
                    giver + " wants to see your hunter's crouch. Creep up on prey and pounce before it hears you.",
                    giver, giverId, false, list(Objective.stalk("Catch prey while crouching (sneak)", Kin.preySet(clan), 2)), 3, 25, "", 0);
            default -> null;
        };
    }

    static Task hunt(Clan clan, String giver, UUID giverId, RandomSource r) {
        int n = 3 + r.nextInt(3);
        if (clan == Clan.RIVER) {
            return new Task(Kind.FISH, "A fishing patrol",
                    "The fresh-kill pile is low. " + giver + " sends you to the streams: RiverClan cats catch fish with a quick paw.",
                    giver, giverId, false, list(
                    Objective.catchPrey("Catch " + n + " fish", Kin.preySet(clan), n),
                    Objective.bring("Bring 2 fish to " + giver, FISH_TAG, 2)), 5, 30, "", 0);
        }
        return new Task(Kind.HUNT, "A hunting patrol",
                "The fresh-kill pile is low. " + giver + " sends you out to hunt " + Kin.preyWords(clan) + " for " + clanName(clan) + ".",
                giver, giverId, false, list(
                Objective.catchPrey("Catch " + n + " " + Kin.preyWords(clan), Kin.preySet(clan), n),
                Objective.bring("Bring 2 prey to " + giver, PREY_TAG, 2)), 5, 30, "warriorcats_events:shredded_meat", 1);
    }

    static Task borderPatrol(Clan clan, String giver, UUID giverId, RandomSource r) {
        List<double[]> pts = Kin.borderPoints(clan);
        List<Objective> obj = new ArrayList<>();
        java.util.Collections.shuffle(pts, new java.util.Random(r.nextLong()));
        for (int i = 0; i < Math.min(3, pts.size()); i++) {
            double[] p = pts.get(i);
            obj.add(Objective.visit("Mark the border " + Kin.direction(Homeland.camp(ClanWorld.current(), clan).x(),
                    Homeland.camp(ClanWorld.current(), clan).z(), p[0], p[1]) + " of camp", p[0], p[1], 14));
        }
        if (obj.isEmpty()) return hunt(clan, giver, giverId, r);
        return new Task(Kind.BORDER_PATROL, "A border patrol",
                giver + " sends you to walk " + clanName(clan) + "'s borders and renew the scent markers, and to watch for intruders.",
                giver, giverId, false, obj, 5, 30, "", 0);
    }

    static Task climb(Clan clan, String giver, UUID giverId) {
        Landmark camp = ClanWorld.current() == null || clan == null ? null : Homeland.camp(ClanWorld.current(), clan);
        double x = camp == null ? 0 : camp.x(), z = camp == null ? 0 : camp.z();
        return new Task(Kind.CLIMB, "Climbing practice",
                giver + " says a warrior must be at home in the trees. Climb up high, at least sixteen blocks above the camp floor, somewhere near camp.",
                giver, giverId, false, list(Objective.climb("Climb high near camp", x, z, 160, 16)), 3, 25, "", 0);
    }

    // --- the apprentice path

    static final String[] STORY_TITLES = {"", "Learn your territory", "Hunt for the Clan", "Help the medicine cat",
            "Your first border patrol", "Places every apprentice knows", "Your assessment"};

    /** Step {@code step} (1..6) of the apprentice path, given by the player's mentor. */
    public static Task storyStep(int step, Clan clan, String mentor, UUID mentorId, long seed) {
        ClanWorld w = ClanWorld.current();
        RandomSource r = RandomSource.create(seed + step);
        String c = clanName(clan);
        return switch (step) {
            case 1 -> {
                Landmark camp = Homeland.camp(w, clan);
                double[] shore = Kin.shorePoint(clan), edge = Kin.farEdgePoint(clan);
                boolean forest = Homeland.forest();
                yield new Task(Kind.STORY_TERRITORY, STORY_TITLES[1],
                        mentor + " leads you round " + c + "'s land. Learn its smells: " + (forest ? "the edge of Fourtrees" : "the lake shore")
                                + ", the far edge of the territory, and back to camp.",
                        mentor, mentorId, true, list(
                        Objective.visit(forest ? "Go to the edge of Fourtrees" : "Go down to the lake shore", shore[0], shore[1], 24),
                        Objective.visit("Find the far edge of the territory", edge[0], edge[1], 30),
                        Objective.visit("Come back to camp", camp.x(), camp.z(), camp.radius())), 6, 40, "", 0);
            }
            case 2 -> new Task(Kind.STORY_HUNT, STORY_TITLES[2],
                    "\"Feed the Clan before yourself.\" " + mentor + " wants to see you hunt " + Kin.preyWords(clan) + " and bring it home.",
                    mentor, mentorId, true, list(
                    Objective.catchPrey("Catch 3 prey", Kin.preySet(clan), 3),
                    Objective.bring("Bring 3 prey to " + mentor, clan == Clan.RIVER ? FISH_TAG : PREY_TAG, 3)), 6, 50, "", 0);
            case 3 -> {
                String[] herb = HERBS[r.nextInt(HERBS.length)];
                yield new Task(Kind.STORY_HERBS, STORY_TITLES[3],
                        "Every apprentice helps the medicine cat. Find " + herb[1] + " (" + herb[2] + ") and bring it to " + mentor + " for the medicine den.",
                        mentor, mentorId, true, list(Objective.bring("Bring 3 " + herb[1] + " to " + mentor, herb[0], 3)), 6, 40,
                        "warriorcats_events:traveling_herbs", 1);
            }
            case 4 -> {
                Task patrol = borderPatrol(clan, mentor, mentorId, r);
                yield new Task(Kind.STORY_PATROL, STORY_TITLES[4], patrol.description, mentor, mentorId, true,
                        patrol.objectives, 6, 50, "", 0);
            }
            case 5 -> {
                if (Homeland.forest()) yield forestPlaces(w, clan, mentor, mentorId);
                List<Objective> obj = new ArrayList<>();
                for (Landmark l : w.lake.landmarks()) {
                    if (l.kind() == Landmark.Kind.ISLAND_TREE) obj.add(Objective.visit("Visit the Gathering island", l.x(), l.z(), 40));
                }
                if ((clan == Clan.THUNDER || clan == Clan.WIND) && w.lake.tunnels() != null) {
                    obj.add(Objective.visit("Find the cavern in the tunnels", w.lake.tunnels().cx, w.lake.tunnels().cz, 14));
                } else {
                    for (Landmark l : w.lake.landmarks()) {
                        if (l.kind() == Landmark.Kind.SKY_OAK) obj.add(Objective.visit("Find the Sky Oak", l.x(), l.z(), 30));
                    }
                }
                yield new Task(Kind.STORY_PLACES, STORY_TITLES[5],
                        "There are places every apprentice should know. " + mentor + " tells you of the Gathering island, where the Clans meet in peace at the full moon, and of "
                                + ((clan == Clan.THUNDER || clan == Clan.WIND) ? "the old tunnels under the border." : "the great Sky Oak in ThunderClan's woods (look, but don't hunt there!)."),
                        mentor, mentorId, true, obj, 8, 60, "", 0);
            }
            case 6 -> new Task(Kind.STORY_ASSESSMENT, STORY_TITLES[6],
                    "Your assessment. " + mentor + " will watch from the shadows: catch two pieces of prey on your own, one quickly after the other.",
                    mentor, mentorId, true, list(Objective.catchInTime("Catch 2 prey within three minutes", Kin.preySet(clan), 2, 180)), 10, 80, "", 0);
            default -> null;
        };
    }

    /** In the old forest: Fourtrees, where the Clans meet in peace, and Highstones, where the Moonstone shines. */
    private static Task forestPlaces(ClanWorld w, Clan clan, String mentor, UUID mentorId) {
        List<Objective> obj = new ArrayList<>();
        obj.add(Objective.visit("Stand at the edge of Fourtrees", w.forest.centreX(), w.forest.centreZ(), 60));
        for (Landmark l : w.forest.landmarks()) {
            if (l.kind() == Landmark.Kind.MOTHERMOUTH) obj.add(Objective.visit("Find Mothermouth at Highstones", l.x(), l.z(), 30));
        }
        return new Task(Kind.STORY_PLACES, STORY_TITLES[5],
                "There are places every apprentice should know. " + mentor + " tells you of Fourtrees, where the four Clans meet in peace "
                        + "at the full moon, and of Highstones beyond WindClan's moor, where Mothermouth leads down to the Moonstone.",
                mentor, mentorId, true, obj, 8, 60, "", 0);
    }

    static String clanName(Clan c) {
        return c == null ? "the Clan" : com.ninjacat.greatjourney.clanlands.life.BookClans.Info.of(c).title;
    }

    private static List<Objective> list(Objective... o) {
        return new ArrayList<>(List.of(o));
    }
}
