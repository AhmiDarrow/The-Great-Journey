package com.ninjacat.greatjourney.clanlands.world;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Which structures may start where in a Clan world. The books' lands have no villages, mansions, outposts, temples
 * or ruined portals: in the lake territories and the old forest, and around the Great Journey's landmarks and highway,
 * surface structures (vanilla's and other mods') do not start. One overlapped ThunderClan's stone hollow (found by the
 * playtest harness). Underground ones (mineshafts, strongholds, ancient cities) still generate.
 */
public final class StructureRules {
    private StructureRules() {}

    public static boolean blocked(ChunkGenerator generator, Structure structure, ChunkPos pos) {
        ClanWorld w = ClanWorld.current();
        if (w == null || structure.step() != GenerationStep.Decoration.SURFACE_STRUCTURES || !ClanWorld.isClanGenerator(generator)) return false;
        double x = pos.getMiddleBlockX(), z = pos.getMiddleBlockZ();
        if (w.lake.covers(x, z) || w.forest.covers(x, z)) return true;
        for (Landmark l : w.journey.landmarks()) {
            double dx = x - l.x(), dz = z - l.z(), r = l.radius() + 96;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        for (Road road : w.journey.roads()) if (road.path().distance(x, z, 96) < 64) return true;
        return false;
    }
}
