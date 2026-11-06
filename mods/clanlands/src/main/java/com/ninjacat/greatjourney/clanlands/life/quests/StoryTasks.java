package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.quests.Task.Kind;
import com.ninjacat.greatjourney.clanlands.life.quests.Task.Objective;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The Campaign's errands ({@link Errands}), a warrior's and a medicine cat's at the same four points of the story:
 * <ol>
 *   <li>the forest's end: feed a hungry Clan, or save the herbs before the monsters churn them up;</li>
 *   <li>the Great Journey sets out: hunt on the way, or gather traveling herbs for the Clan;</li>
 *   <li>the mountains: scout the pass ahead, or tend Clanmates shivering with the cold;</li>
 *   <li>the new home: mark the new borders, or stock the new medicine den.</li>
 * </ol>
 * Each pair has the same reward. Which one a player gets is their path ({@link QuestState#path}).
 */
public final class StoryTasks {
    private StoryTasks() {}

    /** An errand ready to give: its id, task, where it ends (x, z, radius, or null), and what is said at each end. */
    public record Def(String id, Task task, double[] at, String intro, String done) {
        public void give(ServerPlayer p) {
            Errands.give(p, id, task, at, intro, done);
        }
    }

    private static boolean healer(ServerPlayer p) {
        return QuestState.path(p) == QuestState.Path.MEDICINE;
    }

    private static Task task(String title, String desc, int standing, int xp, Objective... o) {
        return new Task(Kind.ERRAND, title, desc, "", null, false, new ArrayList<>(List.of(o)), standing, xp, "", 0);
    }

    private static double[] at(Landmark camp) {
        return new double[] {camp.x(), camp.z(), camp.radius() + 4};
    }

    /** Act 2, the forest's end: done back in the forest camp. */
    public static Def forestEnd(ServerPlayer p, Landmark camp) {
        if (healer(p)) {
            String[][] two = Tasks.herbs(RandomSource.create(p.getUUID().getLeastSignificantBits() ^ 0x5A7E), 2);
            return new Def("dark.herbs", task("Save the herbs",
                    "The monsters are churning up the places where herbs grow. Gather what you can before it is gone, and carry it home to the medicine den: "
                            + two[0][1] + " and " + two[1][1] + ".", 6, 40,
                    Objective.bring("Gather 4 " + two[0][1], two[0][0], 4),
                    Objective.bring("Gather 4 " + two[1][1] + ", and carry them home to the medicine den", two[1][0], 4)),
                    at(camp), "The monsters have torn up a bank where comfrey and dock grew thick. If the herbs go, the sick will have nothing.",
                    "You pack the leaves into the medicine den's store. Whatever comes, your Clan will not be without herbs.");
        }
        return new Def("dark.hunt", task("Feed a hungry Clan",
                "The monsters have driven the prey from the forest, and the Clan is hungry. Hunt whatever you can find, and bring it home to camp.", 6, 40,
                Objective.catchPrey("Catch 4 prey", "any", 4),
                Objective.bring("Bring 3 prey home to the fresh-kill pile in camp", Tasks.ANY_PREY, 3)),
                at(camp), "The fresh-kill pile is bare, and the kits are crying with hunger.",
                "You drop your catch on the fresh-kill pile. The elders and the queens eat first, and for one night nobody in the Clan goes hungry.");
    }

    /** Act 3 sets out: done anywhere on the way. */
    public static Def journeyStart(ServerPlayer p) {
        if (healer(p)) {
            return new Def("journey.herbs", task("Traveling herbs",
                    "Before a long journey every cat eats traveling herbs to keep up their strength. Find sorrel, burnet, chamomile and daisy for your Clanmates.", 6, 40,
                    Objective.bring("2 sorrel", "warriorcats_events:sorrel_leaves|warriorcats_events:sorrel", 2),
                    Objective.bring("2 burnet", "warriorcats_events:burnet_leaves|warriorcats_events:burnet", 2),
                    Objective.bring("2 chamomile", "warriorcats_events:chamomile_stems|warriorcats_events:chamomile", 2),
                    Objective.bring("2 daisy leaves", "warriorcats_events:daisy_leaves|warriorcats_events:daisy", 2)),
                    null, "The road ahead is long, and the Clans are already weak with hunger.",
                    "You share out the traveling herbs. Your Clanmates chew them and stride on, stronger for the long road.");
        }
        return new Def("journey.hunt", task("Hunt on the way",
                "There is little to eat on the journey, and the kits and elders tire fast. Hunt whatever you find along the way.", 6, 40,
                Objective.catchPrey("Catch 3 prey on the journey", "any", 3)),
                null, "The kits and the elders are tiring already, and nobody has eaten since the forest.",
                "You share your catch with the elders and the kits. They eat, and walk on with a little more strength.");
    }

    /**
     * The mountains. A medicine cat tends Clanmates walking with them ({@code companions}, made to shiver with chills),
     * or carries juniper for the cold if none is near; a warrior scouts two points of the way to the Tribe's cave.
     */
    public static Def mountains(ServerPlayer p, double[] pass, double[] cave, List<WCatEntity> companions) {
        if (healer(p)) {
            long now = p.level().getGameTime();
            List<Objective> cures = Patients.sicken(companions, DiseaseTypes.CHILLS, 2, now, p.getRandom());
            Objective[] obj = cures.isEmpty()
                    ? new Objective[] {Objective.bring("Carry 3 juniper berries for the cold", "warriorcats_events:juniper_berries|warriorcats_events:juniper", 3)}
                    : cures.toArray(Objective[]::new);
            return new Def("pass.tend", task("The cold and the weary",
                    "The wind off the snow cuts through fur, and your Clanmates are shivering. A medicine cat's work goes on, even here.", 6, 40, obj),
                    null, "Your Clanmates huddle against the rocks, shaking with cold.",
                    "Your Clanmates stop shivering. Even up here, among the rocks and the snow, the Clan has its medicine cat.");
        }
        double[] a = {pass[0] + (cave[0] - pass[0]) / 3, pass[1] + (cave[1] - pass[1]) / 3};
        double[] b = {pass[0] + (cave[0] - pass[0]) * 2 / 3, pass[1] + (cave[1] - pass[1]) * 2 / 3};
        return new Def("pass.scout", task("Scout the pass",
                "Nobody knows the way through these mountains. Go on ahead of the Clans, find the path, and watch the sky for eagles.", 6, 40,
                Objective.visit("Scout the way ahead", a[0], a[1], 30),
                Objective.visit("Scout further on", b[0], b[1], 30)),
                null, "The leaders stop where the path forks among the rocks. Somebody has to go ahead.",
                "You come back to the Clans with the way ahead clear in your mind. The leaders follow where you lead.");
    }

    /** Act 4, the new home: done in the new camp. */
    public static Def newHome(ServerPlayer p, Clan clan, Landmark camp) {
        if (healer(p)) {
            String[][] two = Tasks.herbs(RandomSource.create(p.getUUID().getMostSignificantBits() ^ 0x1A4E), 2);
            return new Def("lake.den", task("A new medicine den",
                    "The new camp has no medicine den yet. Find the herbs that grow in your new land (" + two[0][1] + " and " + two[1][1]
                            + "), and moss for the nests of the sick.", 8, 60,
                    Objective.bring("Bring 3 " + two[0][1], two[0][0], 3),
                    Objective.bring("Bring 3 " + two[1][1], two[1][0], 3),
                    Objective.bring("Bring 4 moss for the nests", Tasks.MOSS, 4)),
                    at(camp), "A new home means new herbs to find, and a den to make ready before anyone falls sick.",
                    "The new medicine den smells of herbs now, and there are soft nests ready for the sick. It feels like home.");
        }
        Task patrol = Tasks.borderPatrol(ClanWorld.current(), clan, "", null, RandomSource.create(p.getUUID().getLeastSignificantBits()));
        List<Objective> obj = new ArrayList<>(patrol.objectives);
        return new Def("lake.borders", task("Mark the new borders",
                "Your Clan's new land has no borders yet. Walk them and set the scent markers, so that every cat knows whose land this is.", 8, 60,
                obj.toArray(Objective[]::new)),
                at(camp), "The other Clans are settling all round the lake. Your Clan's land needs its borders.",
                "The new borders are marked. Any cat who crosses them now will know whose land this is.");
    }
}
