package com.ninjacat.greatjourney.clanlands.life.campaign;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Act 2: the Twolegs tear down the old forest. Every so often, near a player in the old forest, a stretch of
 * woodland is felled: the trees are gone, leaving stumps and churned, rutted earth, with the growl of Twoleg
 * monsters in the distance. Camps and Fourtrees are spared until the Clans have gone. The scars stay.
 */
final class Felling {
    private Felling() {}

    /** Stretches felled before StarClan sends its sign. */
    static final int BEFORE_SIGN = 6;
    private static final int SIZE = 22;

    /** Fells one stretch near one of the given players (in loaded chunks only). Returns whether a stretch fell. */
    static boolean fellNear(ServerLevel level, List<ServerPlayer> inForest, RandomSource r) {
        ClanWorld w = ClanWorld.current();
        if (w == null || inForest.isEmpty()) return false;
        ServerPlayer p = inForest.get(r.nextInt(inForest.size()));
        for (int tries = 0; tries < 8; tries++) {
            double ang = r.nextDouble() * Math.PI * 2, dist = 90 + r.nextDouble() * 80;
            int cx = (int) Math.floor(p.getX() + Math.cos(ang) * dist), cz = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
            if (!spared(w, cx, cz) && level.isLoaded(new BlockPos(cx - SIZE, 0, cz - SIZE)) && level.isLoaded(new BlockPos(cx + SIZE, 0, cz + SIZE))
                    && Homeland.forestTerritoryAt(w, cx, cz) != null) {
                fell(level, cx, cz, r);
                level.playSound(null, new BlockPos(cx, p.getBlockY(), cz), SoundEvents.RAVAGER_ROAR, SoundSource.AMBIENT, 2.5F, 0.55F);
                return true;
            }
        }
        return false;
    }

    /** Camps and Fourtrees are left standing while the Clans live here. */
    private static boolean spared(ClanWorld w, double x, double z) {
        if (Math.hypot(x - w.forest.centreX(), z - w.forest.centreZ()) < 140) return true;
        for (Clan c : Clan.values()) {
            var camp = Homeland.forestCamp(w, c);
            if (Math.hypot(x - camp.x(), z - camp.z()) < camp.radius() + 50) return true;
        }
        return false;
    }

    private static void fell(ServerLevel level, int cx, int cz, RandomSource r) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double ruts = r.nextDouble() * Math.PI;
        double rx = Math.cos(ruts), rz = Math.sin(ruts);
        for (int dx = -SIZE; dx <= SIZE; dx++) {
            for (int dz = -SIZE; dz <= SIZE; dz++) {
                if (dx * dx + dz * dz > SIZE * SIZE * (0.75 + 0.25 * r.nextDouble())) continue;
                int x = cx + dx, z = cz + dz;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int ground = -1;
                for (int y = top; y > top - 40; y--) {
                    BlockState s = level.getBlockState(pos.set(x, y, z));
                    if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.VINE)) continue;
                    if (!s.isAir() && s.getFluidState().isEmpty()) {
                        ground = y;
                        break;
                    }
                }
                if (ground < 0) continue;
                boolean stump = level.getBlockState(pos.set(x, ground + 1, z)).is(BlockTags.LOGS);
                for (int y = top; y > ground + (stump ? 1 : 0); y--) {
                    BlockState s = level.getBlockState(pos.set(x, y, z));
                    if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.VINE) || s.canBeReplaced() && !s.isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                // the monsters' tracks: two ruts of churned earth across the clearing
                double across = Math.abs(dx * -rz + dz * rx);
                BlockState earth = Math.abs(across - 3) < 1.2
                        ? Blocks.MUD.defaultBlockState() : (r.nextInt(3) == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : null);
                if (earth != null && !stump && level.getBlockState(pos.set(x, ground, z)).is(BlockTags.DIRT)) {
                    level.setBlock(pos, earth, 2);
                }
            }
        }
    }
}
