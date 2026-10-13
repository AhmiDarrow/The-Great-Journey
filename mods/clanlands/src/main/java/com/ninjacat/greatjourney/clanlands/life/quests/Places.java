package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.River;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;

/**
 * The places of the books a player can find, for the journal: each with where it is and a hint until found. Found
 * when the player comes within reach of it.
 */
final class Places {
    private Places() {}

    record Place(String id, String name, double x, double z, double reach, String hint) {}

    private static long cachedSeed = Long.MIN_VALUE;
    private static List<Place> cached = List.of();

    static synchronized List<Place> all() {
        ClanWorld w = ClanWorld.current();
        if (w == null) return List.of();
        if (w.seed == cachedSeed) return cached;
        List<Place> out = new ArrayList<>();
        for (Landmark l : w.lake.landmarks()) {
            switch (l.kind()) {
                case STONE_HOLLOW -> out.add(new Place("stone_hollow", "The stone hollow", l.x(), l.z(), 60, "ThunderClan's camp, an old quarry deep in the woods"));
                case SKY_OAK -> out.add(new Place("sky_oak", "The Sky Oak", l.x(), l.z(), 40, "A great oak in ThunderClan's woods, near the lake"));
                case ISLAND_TREE -> out.add(new Place("island", "The Gathering island", l.x(), l.z(), 60, "In the lake, off RiverClan's shore, reached by a fallen tree"));
                case MOONPOOL -> out.add(new Place("moonpool", "The Moonpool", l.x(), l.z(), 40, "Up the border stream between ThunderClan and WindClan, high in the hills"));
                case HORSEPLACE -> out.add(new Place("horseplace", "The horseplace", l.x(), l.z(), 60, "Twoleg fields between RiverClan and WindClan"));
                case WINDCLAN_HOLLOW -> out.add(new Place("windclan_camp", "WindClan's camp", l.x(), l.z(), 45, "A hollow on the open moor"));
                case RIVERCLAN_CAMP -> out.add(new Place("riverclan_camp", "RiverClan's camp", l.x(), l.z(), 45, "Where a stream meets the river, in the marsh"));
                case SHADOWCLAN_CAMP -> out.add(new Place("shadowclan_camp", "ShadowClan's camp", l.x(), l.z(), 45, "Deep among the pines"));
                case ABANDONED_NEST -> out.add(new Place("abandoned_nest", "The abandoned Twoleg nest", l.x(), l.z(), 35, "In ThunderClan's woods, near the ShadowClan border"));
                default -> { }
            }
        }
        if (w.lake.tunnels() != null) {
            out.add(new Place("tunnels", "The tunnels", w.lake.tunnels().cx, w.lake.tunnels().cz, 20, "Under the border between ThunderClan and WindClan"));
        }
        for (Landmark l : w.forest.landmarks()) {
            switch (l.kind()) {
                case FOURTREES -> out.add(new Place("fourtrees", "Fourtrees", l.x(), l.z(), 70, "In the old forest, far to the south-east, where the four Clans once met"));
                case MOTHERMOUTH -> out.add(new Place("mothermouth", "Highstones and Mothermouth", l.x(), l.z(), 80, "Beyond the old WindClan moor, three crags with a cave to the Moonstone"));
                case SUNNINGROCKS -> out.add(new Place("sunningrocks", "Sunningrocks", l.x(), l.z(), 40, "Warm rocks by the old forest's river"));
                case THUNDERCLAN_RAVINE -> out.add(new Place("old_camp", "The old ThunderClan camp", l.x(), l.z(), 50, "A sandy ravine in the old forest"));
                case BARLEY_FARM -> out.add(new Place("barley_farm", "The farm near Highstones", l.x(), l.z(), 60, "Between the old moor and Highstones"));
                case SNAKEROCKS -> out.add(new Place("snakerocks", "Snakerocks", l.x(), l.z(), 35, "A rock pile in the old ThunderClan woods; beware of adders"));
                default -> { }
            }
        }
        List<River> rivers = w.forest.rivers();
        if (rivers.size() >= 2) {
            River gorge = rivers.get(1);
            double[] mid = gorge.path.pointAt(gorge.path.length() / 2);
            out.add(new Place("gorge", "The gorge", mid[0], mid[1], 50, "Where the old forest's river roars between rock walls"));
        }
        cachedSeed = w.seed;
        cached = List.copyOf(out);
        return cached;
    }
}
