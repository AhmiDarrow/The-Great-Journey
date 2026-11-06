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
    /** Any prey to hand in: the fresh-kill tag and every fish (tropical fish are not in the prey tag). */
    static final String ANY_PREY = PREY_TAG + "|" + FISH_TAG;
    static final String MOSS = "minecraft:moss_block|minecraft:moss_carpet";
    static final String MOSS_BALL = "warriorcats_events:moss_ball";

    /** The duty this cat asks for today (or null if it has none, like kits and the leader). */
    public static Task dutyFor(Role role, Clan clan, String giver, UUID giverId, long day) {
        // mixed: the legacy random's first draw hardly changes between seeds 31 apart, so a deputy sent the same duty
        // (patrol, or hunt) for a hundred days running (the playtest found a deputy with no patrol in forty days)
        RandomSource r = RandomSource.create(it.unimi.dsi.fastutil.HashCommon.mix(giverId.getLeastSignificantBits() ^ day * 31L));
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
                    giver, giverId, false, list(Objective.bring("Bring 2 prey to " + giver, ANY_PREY, 2)), 3, 15, "", 0);
            case QUEEN -> r.nextBoolean()
                    ? new Task(Kind.FEED_QUEEN, "Food for the nursery",
                    giver + " is nursing kits and can't hunt. Bring her prey so the kits grow strong.",
                    giver, giverId, false, list(Objective.bring("Bring 2 prey to " + giver, ANY_PREY, 2)), 3, 15, "", 0)
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

    // --- when cats go out: not at night, and not on hunts and patrols in the rain

    /**
     * From a little after sunset until the dawn patrol, the hours the camp sleeps ({@code CampRoutine}'s night). Kiki's
     * mentor kept her out on the border patrol all through the night and through rainstorms (playtest 2026-10-19).
     */
    public static boolean night(net.minecraft.world.level.Level overworld) {
        return night(overworld.getDayTime());
    }

    public static boolean night(long dayTime) {
        long t = dayTime % 24000L;
        return t >= 12600 && t < 23200;
    }

    /** Rain (or a storm, or snow on the high ground): the scents wash out and the prey goes to ground. */
    public static boolean wet(net.minecraft.world.level.Level overworld) {
        return overworld.isRaining();
    }

    /** A duty or a step that takes a cat out of camp to hunt, patrol or roam, which nobody is sent on in the rain. */
    static boolean outing(Kind kind) {
        return switch (kind) {
            case HUNT, FISH, BORDER_PATROL, CLIMB, STALK, STORY_HUNT, STORY_PATROL, STORY_PLACES, STORY_ASSESSMENT -> true;
            default -> false;
        };
    }

    /** Whether a cat is kept in camp from this duty or step now: at night from any, in the rain from an outing. */
    static boolean keptIn(net.minecraft.world.level.Level overworld, Kind kind) {
        return keptIn(night(overworld), wet(overworld), kind);
    }

    public static boolean keptIn(boolean night, boolean wet, Kind kind) {
        return night || (wet && outing(kind));
    }

    /**
     * The duty this cat asks of a medicine cat (or a medicine cat apprentice) today, where a warrior would be sent to
     * hunt and patrol: the Clan's own medicine cat wants the herb store filled or the sick seen to, the deputy sends
     * them to the footsore patrol, a warrior or an apprentice has cracked pads, an elder's joints ache or they cannot
     * sleep, a queen's kit is feverish. Cures name their patients only when the duty is taken on ({@link Patients}).
     * The rewards match the warrior's duties, so neither path falls behind.
     */
    public static Task healerDutyFor(Role role, Clan clan, String giver, UUID giverId, long day) {
        RandomSource r = RandomSource.create(it.unimi.dsi.fastutil.HashCommon.mix(giverId.getLeastSignificantBits() ^ day * 37L));
        return switch (role) {
            case MEDICINE -> {
                if (r.nextBoolean()) {
                    String[][] two = herbs(r, 2);
                    yield new Task(Kind.HERB_STORE, "Fill the herb store",
                            "The herb store is running low, and leaf-bare always comes sooner than you think. " + giver + " needs "
                                    + two[0][1] + " (" + two[0][2] + ") and " + two[1][1] + " (" + two[1][2] + ").",
                            giver, giverId, false, list(
                            Objective.bring("Bring 4 " + two[0][1] + " to " + giver, two[0][0], 4),
                            Objective.bring("Bring 3 " + two[1][1] + " to " + giver, two[1][0], 3)), 4, 20, "warriorcats_events:traveling_herbs", 1);
                }
                boolean cough = r.nextBoolean();
                yield new Task(Kind.TEND, "Sickness in camp",
                        giver + " has been up half the night with " + (cough ? "coughing" : "feverish") + " cats. Go round the camp and see to the sick.",
                        giver, giverId, false, list(Patients.plan("Cure two sick Clanmates", Patients.Who.ANY,
                        cough ? net.snowteb.warriorcats_events.diseases.DiseaseTypes.WHITECOUGH : net.snowteb.warriorcats_events.diseases.DiseaseTypes.FEVER, 2)), 4, 20, "", 0);
            }
            case DEPUTY -> new Task(Kind.TEND, "The patrol's sore paws",
                    "The dawn patrol came back footsore and shivering. " + giver + " wants them fit to go out again: look them over and treat them.",
                    giver, giverId, false, list(Patients.plan("Treat two warriors", Patients.Who.WARRIOR,
                    r.nextBoolean() ? net.snowteb.warriorcats_events.diseases.DiseaseTypes.SORE_PADS : net.snowteb.warriorcats_events.diseases.DiseaseTypes.CHILLS, 2)), 5, 30, "warriorcats_events:shredded_meat", 1);
            case WARRIOR, APPRENTICE -> new Task(Kind.TEND, "Cracked pads",
                    giver + "'s pads are cracked and sore from the stony ground. "
                            + (role == Role.APPRENTICE ? "Training will have to wait until they're seen to." : "A warrior who can't walk can't patrol."),
                    giver, giverId, false, list(Patients.plan("Treat " + giver + "'s sore pads", Patients.Who.SELF, net.snowteb.warriorcats_events.diseases.DiseaseTypes.SORE_PADS, 1)), 3, 25, "", 0);
            case ELDER -> r.nextBoolean()
                    ? new Task(Kind.ELDER_HERBS, "Aching joints",
                    giver + "'s old joints ache with the cold. Daisy leaves, chewed to a pulp, will ease them.",
                    giver, giverId, false, list(Objective.bring("Bring 2 daisy leaves to " + giver, "warriorcats_events:daisy_leaves|warriorcats_events:daisy", 2)), 3, 15, "", 0)
                    : new Task(Kind.ELDER_HERBS, "A sleepless elder",
                    giver + " hasn't slept for the ache in their bones. A few poppy seeds will let them rest (craft them from a poppy).",
                    giver, giverId, false, list(Objective.bring("Bring 2 poppy seeds to " + giver, "warriorcats_events:poppy_seeds", 2)), 3, 15, "", 0);
            case QUEEN -> new Task(Kind.TEND, "A feverish kit",
                    "One of " + giver + "'s kits is hot and won't stop mewling. " + giver + " is frightened: see to the kit.",
                    giver, giverId, false, list(Patients.plan("Cure the sick kit", Patients.Who.KIT, net.snowteb.warriorcats_events.diseases.DiseaseTypes.FEVER, 1)), 3, 15, "", 0);
            default -> null;
        };
    }

    static Task hunt(Clan clan, String giver, UUID giverId, RandomSource r) {
        int n = 3 + r.nextInt(3);
        if (clan == Clan.RIVER) {
            return new Task(Kind.FISH, "A fishing patrol",
                    "The fresh-kill pile is low. " + giver + " sends you to the streams: RiverClan cats catch fish with a quick paw.",
                    giver, giverId, false, list(
                    Objective.catchPrey("Catch " + n + " prey (fish are best)", Kin.preySet(clan), n),
                    Objective.bring("Bring 2 prey to " + giver, ANY_PREY, 2)), 5, 30, "", 0);
        }
        return new Task(Kind.HUNT, "A hunting patrol",
                "The fresh-kill pile is low. " + giver + " sends you out to hunt " + Kin.preyWords(clan) + " for " + clanName(clan) + ".",
                giver, giverId, false, list(
                Objective.catchPrey("Catch " + n + " prey (" + Kin.preyWords(clan) + " are best)", Kin.preySet(clan), n),
                Objective.bring("Bring 2 prey to " + giver, ANY_PREY, 2)), 5, 30, "warriorcats_events:shredded_meat", 1);
    }

    /**
     * The longest walk, in blocks, a duty or an apprentice step asks for: out of camp, to each place in turn and home
     * again, in straight lines. At a cat's pace over real ground (about 4 blocks a second) that is seven and a half
     * minutes, nine or so with the detours round trees, hills and streams: the five to ten minutes a duty should take.
     */
    public static final double ROUTE_CAP = 1800;
    /** The shortest a border patrol should be (about four minutes, five over real ground), where the borders allow. */
    public static final double ROUTE_MIN = 1000;

    static Task borderPatrol(Clan clan, String giver, UUID giverId, RandomSource r) {
        return borderPatrol(ClanWorld.current(), clan, giver, giverId, r);
    }

    /**
     * A border patrol: a loop out of camp to the two or three nearest points on the borders, the nearest first, then
     * round and home, kept within {@link #ROUTE_CAP}. Which stretch of border it sets out for changes from day to day.
     */
    public static Task borderPatrol(ClanWorld w, Clan clan, String giver, UUID giverId, RandomSource r) {
        if (w == null || clan == null) return hunt(clan, giver, giverId, r);
        Landmark camp = Homeland.camp(w, clan);
        List<Objective> obj = new ArrayList<>();
        for (double[] p : borderRoute(w, clan, camp, r)) {
            obj.add(Objective.visit("Mark the border " + Kin.direction(camp.x(), camp.z(), p[0], p[1]) + " of camp", p[0], p[1], 14));
        }
        if (obj.isEmpty()) return hunt(clan, giver, giverId, r);
        return new Task(Kind.BORDER_PATROL, "A border patrol",
                giver + " sends you to walk " + clanName(clan) + "'s borders and renew the scent markers, and to watch for intruders.",
                giver, giverId, false, obj, 5, 30, "", 0);
    }

    /** The border points a patrol visits, in order (see {@link #borderPatrol(ClanWorld, Clan, String, UUID, RandomSource)}). */
    static List<double[]> borderRoute(ClanWorld w, Clan clan, Landmark camp, RandomSource r) {
        List<double[]> pts = Kin.borderPoints(w, clan);
        pts.sort(java.util.Comparator.comparingDouble(p -> Math.hypot(p[0] - camp.x(), p[1] - camp.z())));
        List<double[]> route = new ArrayList<>();
        if (pts.isEmpty()) return route;
        // set out for one of the two nearest stretches of border, so the patrol differs from day to day; the nearest
        // of all when the other is too far to walk out and back (a Clan with few border points still gets that one)
        int first = pts.size() > 1 && r.nextBoolean() ? 1 : 0;
        if (first == 1 && walk(camp, List.of(stop(pts.get(1), 14))) > ROUTE_CAP) first = 0;
        double[] start = pts.remove(first);
        // points well apart along the borders; close together only when nothing else is within reach
        route = loopFrom(camp, start, pts, 200);
        if (route.size() < 2) route = loopFrom(camp, start, pts, 60);
        return route;
    }

    /**
     * From the first point, the next nearest points at least {@code apart} from those already chosen, up to three in
     * all, while the loop stays within {@link #ROUTE_CAP}. A loop shorter than {@link #ROUTE_MIN} (a stroll round the
     * camp's doorstep) takes a farther last point instead, if one fits.
     */
    private static List<double[]> loopFrom(Landmark camp, double[] first, List<double[]> pts, double apart) {
        List<double[]> route = new ArrayList<>(List.of(first));
        for (double[] p : pts) {
            if (route.size() >= 3) break;
            if (!apart(route, p, apart)) continue;
            List<double[]> t = withPoint(camp, route, p);
            if (walk(camp, stops(t, 14)) <= ROUTE_CAP) route = t;
        }
        if (route.size() > 1 && walk(camp, stops(route, 14)) < ROUTE_MIN) {
            List<double[]> head = new ArrayList<>(route.subList(0, route.size() - 1));
            for (double[] p : pts) {
                if (!apart(head, p, apart)) continue;
                List<double[]> t = withPoint(camp, head, p);
                double len = walk(camp, stops(t, 14));
                if (len >= ROUTE_MIN && len <= ROUTE_CAP) return t;
            }
        }
        return route;
    }

    private static boolean apart(List<double[]> route, double[] p, double apart) {
        for (double[] q : route) if (Math.hypot(p[0] - q[0], p[1] - q[1]) < apart) return false;
        return true;
    }

    /** The route with {@code p} put in wherever it makes the shorter loop, the first point staying first. */
    private static List<double[]> withPoint(Landmark camp, List<double[]> route, double[] p) {
        List<double[]> best = null;
        for (int at = 1; at <= route.size(); at++) {
            List<double[]> t = new ArrayList<>(route);
            t.add(at, p);
            if (best == null || walk(camp, stops(t, 14)) < walk(camp, stops(best, 14))) best = t;
        }
        return best;
    }

    /** The walk a task's visits ask for, in blocks: out of camp to each in turn (its radius counts as there) and home. */
    public static double routeLength(Landmark camp, List<Objective> objectives) {
        List<double[]> s = new ArrayList<>();
        for (Objective o : objectives) {
            if (o.type == Objective.Type.VISIT) s.add(new double[] {o.x, o.z, o.radius});
        }
        return walk(camp, s);
    }

    /** Straight-line walk from camp through each {x, z, radius} stop and back to the camp's edge. */
    private static double walk(Landmark camp, List<double[]> stops) {
        double d = 0, x = camp.x(), z = camp.z();
        for (double[] s : stops) {
            d += Math.max(0, Math.hypot(s[0] - x, s[1] - z) - s[2]);
            x = s[0];
            z = s[1];
        }
        return d + Math.max(0, Math.hypot(camp.x() - x, camp.z() - z) - camp.radius());
    }

    private static double[] stop(double[] p, double radius) {
        return new double[] {p[0], p[1], radius};
    }

    private static List<double[]> stops(List<double[]> pts, double radius) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : pts) out.add(stop(p, radius));
        return out;
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

    static final String[] MED_TITLES = {"", "Learn the medicine den", "Gather herbs", "Poultices and poppy seeds",
            "Tend the elders", "Places a medicine cat knows", "Your test"};

    /** The step's title on either path. */
    static String title(QuestState.Path path, int step) {
        return (path == QuestState.Path.MEDICINE ? MED_TITLES : STORY_TITLES)[step];
    }

    /** Step {@code step} (1..6) of the player's path, given by their mentor. */
    public static Task storyStep(QuestState.Path path, int step, Clan clan, String mentor, UUID mentorId, long seed) {
        return path == QuestState.Path.MEDICINE ? medicineStep(step, clan, mentor, mentorId, seed) : storyStep(step, clan, mentor, mentorId, seed);
    }

    /** Step {@code step} (1..6) of the apprentice path, given by the player's mentor. */
    public static Task storyStep(int step, Clan clan, String mentor, UUID mentorId, long seed) {
        ClanWorld w = ClanWorld.current();
        RandomSource r = RandomSource.create(seed + step);
        String c = clanName(clan);
        return switch (step) {
            case 1 -> {
                // the camp, not the whole territory (the owner asked, 2026-10-18): the borders come on the patrol step
                Landmark camp = Homeland.camp(w, clan);
                double[] gate = com.ninjacat.greatjourney.clanlands.life.Starts.wayIn(w, camp).get(0);
                yield new Task(Kind.STORY_TERRITORY, STORY_TITLES[1],
                        mentor + " shows you round " + c + "'s camp: the way in, every den and whose it is, and the rock the leader speaks from. "
                                + "Stay close: " + mentor + " waits if you fall behind.",
                        mentor, mentorId, true, list(
                        Objective.visit("Learn the way in and out of camp", gate[0], gate[1], 8),
                        Objective.visit("See the dens round the camp", camp.x(), camp.z(), Math.max(8, camp.radius() * 0.7)),
                        Objective.visit("Come back to the clearing", camp.x(), camp.z(), 8)), 6, 40, "", 0);
            }
            case 2 -> new Task(Kind.STORY_HUNT, STORY_TITLES[2],
                    "\"Feed the Clan before yourself.\" " + mentor + " comes along to watch you hunt " + Kin.preyWords(clan) + ", and you bring it home.",
                    mentor, mentorId, true, list(
                    Objective.catchPrey("Catch 3 prey", Kin.preySet(clan), 3),
                    Objective.bring("Bring 3 prey to " + mentor, ANY_PREY, 3)), 6, 50, "", 0);
            case 3 -> {
                String[] herb = HERBS[r.nextInt(HERBS.length)];
                yield new Task(Kind.STORY_HERBS, STORY_TITLES[3],
                        "Every apprentice helps the medicine cat. Find " + herb[1] + " (" + herb[2] + ") and bring it to " + mentor + " for the medicine den. "
                                + mentor + " will look in on you in camp.",
                        mentor, mentorId, true, list(Objective.bring("Bring 3 " + herb[1] + " to " + mentor, herb[0], 3)), 6, 40,
                        "warriorcats_events:traveling_herbs", 1);
            }
            case 4 -> {
                Task patrol = borderPatrol(clan, mentor, mentorId, r);
                yield new Task(Kind.STORY_PATROL, STORY_TITLES[4], patrol.description, mentor, mentorId, true,
                        patrol.objectives, 6, 50, "", 0);
            }
            case 5 -> Homeland.forest() ? forestPlaces(w, clan, mentor, mentorId) : lakePlaces(w, clan, mentor, mentorId);
            case 6 -> new Task(Kind.STORY_ASSESSMENT, STORY_TITLES[6],
                    "Your assessment. " + mentor + " will watch from the shadows nearby: catch two pieces of prey on your own, one quickly after the other.",
                    mentor, mentorId, true, list(Objective.catchInTime("Catch 2 prey within three minutes", Kin.preySet(clan), 2, 180)), 10, 80, "", 0);
            default -> null;
        };
    }

    /**
     * Step {@code step} (1..6) of the medicine cat's path, given by the Clan's medicine cat: the camp and the medicine
     * den, herbs, the remedies made from them (WCE's dock poultice and poppy seeds, both made in the crafting grid),
     * the elders' aches, the places a medicine cat must know (the Moonpool, by the lake), and a test of three herbs.
     */
    public static Task medicineStep(int step, Clan clan, String mentor, UUID mentorId, long seed) {
        ClanWorld w = ClanWorld.current();
        RandomSource r = RandomSource.create(seed + step * 7919L);
        String c = clanName(clan);
        return switch (step) {
            case 1 -> {
                Landmark camp = Homeland.camp(w, clan);
                double[] gate = com.ninjacat.greatjourney.clanlands.life.Starts.wayIn(w, camp).get(0);
                yield new Task(Kind.STORY_TERRITORY, MED_TITLES[1],
                        mentor + " shows you round " + c + "'s camp: the way in, every den and whose it is, and the medicine den where you "
                                + "will sleep and work from now on. Stay close: " + mentor + " waits if you fall behind.",
                        mentor, mentorId, true, list(
                        Objective.visit("Learn the way in and out of camp", gate[0], gate[1], 8),
                        Objective.visit("See the dens round the camp", camp.x(), camp.z(), Math.max(8, camp.radius() * 0.7)),
                        Objective.visit("Come back to the clearing", camp.x(), camp.z(), 8)), 6, 40, "", 0);
            }
            case 2 -> {
                String[][] two = herbs(r, 2);
                yield new Task(Kind.STORY_MED_HERBS, MED_TITLES[2],
                        "\"Every herb has its place and its season.\" " + mentor + " sends you out for " + two[0][1] + " (" + two[0][2] + ") and "
                                + two[1][1] + " (" + two[1][2] + "). Use your claws to pick the leaves without tearing up the plant.",
                        mentor, mentorId, true, list(
                        Objective.bring("Bring 3 " + two[0][1] + " to " + mentor, two[0][0], 3),
                        Objective.bring("Bring 3 " + two[1][1] + " to " + mentor, two[1][0], 3)), 6, 40, "", 0);
            }
            case 3 -> new Task(Kind.STORY_MED_REMEDIES, MED_TITLES[3],
                    mentor + " shows you how herbs become remedies. Chew dock leaves into a poultice for sore pads (put dock leaves in the "
                            + "crafting grid), and shake the seeds from a poppy head to help a cat sleep through pain (put a poppy flower in the "
                            + "crafting grid, or on a stone table for three seeds).",
                    mentor, mentorId, true, list(
                    Objective.bring("Bring 2 dock poultices to " + mentor, "warriorcats_events:dock_poultice", 2),
                    Objective.bring("Bring 3 poppy seeds to " + mentor, "warriorcats_events:poppy_seeds", 3)), 6, 50, "", 0);
            case 4 -> {
                List<Objective> obj = new ArrayList<>();
                com.ninjacat.greatjourney.clanlands.landmark.CampPlan plan = w == null ? null
                        : com.ninjacat.greatjourney.clanlands.landmark.CampPlan.of(w, Homeland.camp(w, clan));
                if (plan != null) {
                    for (var d : plan.dens) {
                        if (d.kind() == com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind.ELDERS) {
                            obj.add(Objective.visit("Look in on the elders in their den", d.x(), d.z(), Math.max(4, d.radius() + 3)));
                            break;
                        }
                    }
                }
                obj.add(Objective.bring("Bring 4 moss for the elders' bedding", MOSS, 4));
                obj.add(Objective.bring("Bring 2 daisy leaves for their aching joints", "warriorcats_events:daisy_leaves|warriorcats_events:daisy", 2));
                yield new Task(Kind.STORY_MED_ELDERS, MED_TITLES[4],
                        "The elders' joints ache and their bedding is stale. Look in on them, gather fresh moss, and find daisy leaves "
                                + "to ease their joints. Then tell " + mentor + " how they are.",
                        mentor, mentorId, true, obj, 6, 50, "", 0);
            }
            case 5 -> Homeland.forest() ? forestPlaces(w, clan, mentor, mentorId, true) : lakePlaces(w, clan, mentor, mentorId, true);
            case 6 -> {
                String[][] three = herbs(r, 3);
                List<Objective> obj = new ArrayList<>();
                for (String[] h : three) obj.add(Objective.bring("Bring 2 " + h[1] + " (" + h[2] + ")", h[0], 2));
                yield new Task(Kind.STORY_MED_TEST, MED_TITLES[6],
                        "Your test. " + mentor + " names three herbs and what each one is for, and you must find them on your own: "
                                + three[0][1] + ", " + three[1][1] + " and " + three[2][1] + ", two of each.",
                        mentor, mentorId, true, obj, 10, 80, "", 0);
            }
            default -> null;
        };
    }

    /** {@code n} different herbs from {@link #HERBS}. */
    static String[][] herbs(RandomSource r, int n) {
        List<String[]> pool = new ArrayList<>(List.of(HERBS));
        String[][] out = new String[n][];
        for (int i = 0; i < n; i++) out[i] = pool.remove(r.nextInt(pool.size()));
        return out;
    }

    /** A place an apprentice is shown: where it is, and how the mentor speaks of it. */
    private record Sight(Objective visit, String words) {}

    /**
     * By the lake: the Gathering island, which stays however far it lies (across the lake from some camps), and the
     * one of the Clan's nearer places that adds least to the walk: the tunnels, the Sky Oak, the abandoned Twoleg nest
     * or the horseplace.
     */
    public static Task lakePlaces(ClanWorld w, Clan clan, String mentor, UUID mentorId) {
        return lakePlaces(w, clan, mentor, mentorId, false);
    }

    /**
     * For a medicine cat apprentice ({@code healer}) the Moonpool takes the island's place: the medicine cats meet there
     * at the half moon. The abandoned Twoleg nest's catmint and the rest are as for any apprentice.
     */
    static Task lakePlaces(ClanWorld w, Clan clan, String mentor, UUID mentorId, boolean healer) {
        Sight island = null, pool = null;
        List<Sight> near = new ArrayList<>();
        for (Landmark l : w.lake.landmarks()) {
            switch (l.kind()) {
                case ISLAND_TREE -> island = new Sight(Objective.visit("Visit the Gathering island", l.x(), l.z(), 40),
                        "the Gathering island, where the Clans meet in peace at the full moon");
                case MOONPOOL -> pool = new Sight(Objective.visit("Find the Moonpool", l.x(), l.z(), 16),
                        "the Moonpool in the hills, where the medicine cats share tongues with StarClan at every half moon");
                case SKY_OAK -> near.add(new Sight(Objective.visit("Find the Sky Oak", l.x(), l.z(), 30), clan == Clan.THUNDER
                        ? "the great Sky Oak by the lake, the tallest tree in our woods"
                        : "the great Sky Oak in ThunderClan's woods (look, but don't hunt there!)"));
                case ABANDONED_NEST -> {
                    if (clan == Clan.THUNDER || clan == Clan.SHADOW) near.add(new Sight(Objective.visit("Find the abandoned Twoleg nest", l.x(), l.z(), 20),
                            "the abandoned Twoleg nest by the ThunderClan border, where catmint grows in the old garden"));
                }
                case HORSEPLACE -> {
                    if (clan == Clan.WIND || clan == Clan.RIVER) near.add(new Sight(Objective.visit("Look over the horseplace", l.x(), l.z(), 40),
                            "the horseplace, where Twolegs keep their horses and a few loners live in the barn"));
                }
                default -> { }
            }
        }
        if ((clan == Clan.THUNDER || clan == Clan.WIND) && w.lake.tunnels() != null) {
            near.add(new Sight(Objective.visit("Find the cavern in the tunnels", w.lake.tunnels().cx, w.lake.tunnels().cz, 14),
                    "the old tunnels under the ThunderClan-WindClan border"));
        }
        if (healer && pool != null) return placesTask(w.lake.camp(clan), pool, near, mentor, mentorId, " " + mentor + " will walk you up to the Moonpool and home again.", MED_TITLES[5]);
        return placesTask(w.lake.camp(clan), island, near, mentor, mentorId, "", healer ? MED_TITLES[5] : STORY_TITLES[5]);
    }

    /**
     * In the old forest: Fourtrees, where the Clans meet in peace, and the nearest of the Clan's own places (Sunningrocks,
     * Snakerocks, the Owl Tree, the Great Sycamore, the gorge, Carrionplace). Highstones lies a whole journey beyond
     * WindClan's moor (twenty minutes and more there and back), so the mentor only tells of it: an apprentice goes
     * there with the leader.
     */
    public static Task forestPlaces(ClanWorld w, Clan clan, String mentor, UUID mentorId) {
        return forestPlaces(w, clan, mentor, mentorId, false);
    }

    /** For a medicine cat apprentice ({@code healer}) the same places, and the Moonstone told of as theirs to come. */
    static Task forestPlaces(ClanWorld w, Clan clan, String mentor, UUID mentorId, boolean healer) {
        Sight fourtrees = new Sight(Objective.visit("Stand at the edge of Fourtrees", w.forest.centreX(), w.forest.centreZ(), 60),
                "Fourtrees, where the four Clans meet in peace at the full moon");
        boolean thunder = clan == Clan.THUNDER;
        List<Sight> near = new ArrayList<>();
        for (Landmark l : w.forest.landmarks()) {
            switch (l.kind()) {
                case SUNNINGROCKS -> {
                    if (thunder || clan == Clan.RIVER) near.add(new Sight(Objective.visit("Climb Sunningrocks", l.x(), l.z(), 30),
                            "Sunningrocks, the warm stones ThunderClan and RiverClan have fought over for moons"));
                }
                case SNAKEROCKS -> {
                    if (thunder) near.add(new Sight(Objective.visit("Find Snakerocks", l.x(), l.z(), 25), "Snakerocks, where adders hide among the stones"));
                }
                case OWL_TREE -> {
                    if (thunder) near.add(new Sight(Objective.visit("Find the Owl Tree", l.x(), l.z(), 20), "the Owl Tree, where an owl nests high in the trunk"));
                }
                case GREAT_SYCAMORE -> {
                    if (thunder) near.add(new Sight(Objective.visit("Find the Great Sycamore", l.x(), l.z(), 20), "the Great Sycamore, where apprentices learn to climb"));
                }
                case CARRIONPLACE -> {
                    if (clan == Clan.SHADOW) near.add(new Sight(Objective.visit("Find Carrionplace (keep your distance)", l.x(), l.z(), 40),
                            "Carrionplace, the Twoleg dump where the rats swarm"));
                }
                default -> { }
            }
        }
        List<com.ninjacat.greatjourney.clanlands.layout.River> rivers = w.forest.rivers();
        if ((clan == Clan.WIND || clan == Clan.RIVER) && rivers.size() >= 2) {
            var gorge = rivers.get(1).path;
            double[] mid = gorge.pointAt(gorge.length() / 2);       // where the journal's places put the gorge
            near.add(new Sight(Objective.visit("Look down into the gorge", mid[0], mid[1], 30),
                    "the gorge, where the river roars between rock walls (never try to cross it!)"));
        }
        return placesTask(Homeland.forestCamp(w, clan), fourtrees, near, mentor, mentorId, healer
                ? " Highstones lies far beyond WindClan's moor, where Mothermouth leads down to the Moonstone: at the half moon you will walk there with the other medicine cats and share tongues with StarClan."
                : " Highstones lies far beyond WindClan's moor, where Mothermouth leads down to the Moonstone: one day you will walk there with your leader.",
                healer ? MED_TITLES[5] : STORY_TITLES[5]);
    }

    /**
     * The Gathering place (always, however far) and the one nearer place that adds least to the walk, the nearer of
     * the two first. A second place is left out when it would push the walk past {@link #ROUTE_CAP} by more than a
     * couple of minutes: across the lake from the island the walk is long enough already.
     */
    private static Task placesTask(Landmark camp, Sight gathering, List<Sight> near, String mentor, UUID mentorId, String more, String title) {
        List<Sight> best = gathering == null ? List.of() : List.of(gathering);
        double alone = gathering == null ? Double.MAX_VALUE : walk(camp, sights(best)), bestLen = Double.MAX_VALUE;
        for (Sight s : near) {
            for (List<Sight> t : gathering == null ? List.of(List.of(s)) : List.of(List.of(gathering, s), List.of(s, gathering))) {
                double len = walk(camp, sights(t));
                if (len < bestLen) {
                    bestLen = len;
                    if (len <= ROUTE_CAP || len - alone <= 480) best = t;
                }
            }
        }
        List<Objective> obj = new ArrayList<>();
        StringBuilder words = new StringBuilder();
        for (Sight s : best) {
            obj.add(s.visit());
            words.append(words.isEmpty() ? "" : ", and of ").append(s.words());
        }
        return new Task(Kind.STORY_PLACES, title,
                "There are places every apprentice should know. " + mentor + " tells you of " + words + "." + more,
                mentor, mentorId, true, obj, 8, 60, "", 0);
    }

    private static List<double[]> sights(List<Sight> s) {
        List<double[]> out = new ArrayList<>();
        for (Sight x : s) out.add(new double[] {x.visit().x, x.visit().z, x.visit().radius});
        return out;
    }

    static String clanName(Clan c) {
        return c == null ? "the Clan" : com.ninjacat.greatjourney.clanlands.life.BookClans.Info.of(c).title;
    }

    private static List<Objective> list(Objective... o) {
        return new ArrayList<>(List.of(o));
    }
}
