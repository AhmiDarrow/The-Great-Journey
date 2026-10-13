package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;

/**
 * Where the Clans live right now. In Free Mode, and in the Campaign once the Clans have reached the lake, that is
 * the lake territories; in the Campaign before then it is the old forest. Camps, territory borders and "toward the
 * heart of the Clans' land" all come from here, so duties, the apprentice path and Clan events follow the Clans
 * wherever they are.
 */
public final class Homeland {
    private Homeland() {}

    private static volatile boolean forest;

    /** Set by the Campaign whenever the Clans' home changes (and on server start). */
    public static void setForest(boolean inForest) {
        forest = inForest;
    }

    /** Whether the Clans live in the old forest at the moment. */
    public static boolean forest() {
        return forest;
    }

    public static ClanMap map(ClanWorld w) {
        return forest ? w.forest : w.lake;
    }

    /** The Clan's camp where the Clans live now. */
    public static Landmark camp(ClanWorld w, Clan c) {
        return forest ? forestCamp(w, c) : w.lake.camp(c);
    }

    public static Landmark forestCamp(ClanWorld w, Clan c) {
        Landmark.Kind kind = switch (c) {
            case THUNDER -> Landmark.Kind.THUNDERCLAN_RAVINE;
            case WIND -> Landmark.Kind.WINDCLAN_HOLLOW;
            case RIVER -> Landmark.Kind.RIVERCLAN_CAMP;
            case SHADOW -> Landmark.Kind.SHADOWCLAN_CAMP;
        };
        return w.forest.landmarks().stream().filter(l -> l.kind() == kind).findFirst().orElseThrow();
    }

    /** The Clan whose territory holds this column where the Clans live now, or null. */
    public static Clan territoryAt(ClanWorld w, double x, double z) {
        return forest ? forestTerritoryAt(w, x, z) : w.lake.territoryAt(x, z);
    }

    public static Clan forestTerritoryAt(ClanWorld w, double x, double z) {
        Sample s = w.forest.sample(x, z);
        if (s.weight() < 0.5 || s.zone() == null) return null;
        return switch (s.zone()) {
            case THUNDERCLAN_FOREST -> Clan.THUNDER;
            case WINDCLAN_MOOR -> Clan.WIND;
            case RIVERCLAN_PLAINS, RIVERCLAN_MARSH, RIVERCLAN_WETLAND -> Clan.RIVER;
            case SHADOWCLAN_PINES, SHADOWCLAN_MARSH -> Clan.SHADOW;
            default -> null;
        };
    }

    /** The heart of the Clans' land: the lake's centre, or Fourtrees. */
    public static double[] centre(ClanWorld w) {
        ClanMap m = map(w);
        return new double[] {m.centreX(), m.centreZ()};
    }

    /** Whether a column is the shared heart itself (the lake's water, or the Fourtrees hollow), not anyone's land. */
    public static boolean heart(ClanWorld w, double x, double z) {
        Sample s = map(w).sample(x, z);
        return s.zone() == com.ninjacat.greatjourney.clanlands.layout.Zone.LAKE
                || s.zone() == com.ninjacat.greatjourney.clanlands.layout.Zone.FOURTREES;
    }
}
