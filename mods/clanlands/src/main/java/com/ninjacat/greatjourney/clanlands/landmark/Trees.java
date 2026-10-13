package com.ninjacat.greatjourney.clanlands.landmark;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Giant landmark trees: a thick flared trunk, a few heavy limbs and a broad crown of leaf clusters. */
final class Trees {
    private Trees() {}

    /**
     * @param trunkR trunk radius in blocks at its base
     * @param height trunk height
     * @param crownR crown radius
     * @param hollow whether the trunk has a den-sized hollow at its foot (owl tree, Sky Oak)
     */
    static void giant(ChunkPainter p, double cx, double cz, RandomSource r, int trunkR, int height, int crownR,
                      BlockState log, BlockState leaves, boolean hollow) {
        int x0 = (int) Math.floor(cx), z0 = (int) Math.floor(cz);
        // every random draw happens before any block is placed, in the same order for every chunk
        int limbs = 4 + r.nextInt(3);
        double[][] limb = new double[limbs][];
        for (int i = 0; i < limbs; i++) {
            double ang = (i + r.nextDouble() * 0.6) / limbs * Math.PI * 2;
            double start = height * (0.55 + r.nextDouble() * 0.25);
            double len = crownR * (0.6 + r.nextDouble() * 0.4);
            double rise = len * (0.35 + r.nextDouble() * 0.3);
            limb[i] = new double[] {ang, start, len, rise};
        }
        int clusters = 10 + r.nextInt(6);
        double[][] blob = new double[clusters][];
        for (int i = 0; i < clusters; i++) {
            double ang = r.nextDouble() * Math.PI * 2, dist = crownR * Math.sqrt(r.nextDouble()) * 0.85;
            blob[i] = new double[] {Math.cos(ang) * dist, height + r.nextDouble() * crownR * 0.5 - 2, Math.sin(ang) * dist,
                    crownR * (0.35 + r.nextDouble() * 0.2)};
        }
        int doorAng = r.nextInt(4);

        int reach = crownR + trunkR + 3;
        if (x0 + reach < p.minX || x0 - reach > p.minX + 15 || z0 + reach < p.minZ || z0 - reach > p.minZ + 15) {
            return;
        }
        int base = baseHeight(p, x0, z0, trunkR);

        // trunk, flaring at the foot
        for (int y = -3; y <= height; y++) {
            double rad = trunkR + (y < 4 ? (4 - Math.max(0, y)) * 0.45 : 0) - Math.max(0, y - height * 0.7) * 0.06;
            int ri = (int) Math.ceil(rad);
            for (int dx = -ri; dx <= ri; dx++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    if (dx * dx + dz * dz > rad * rad) continue;
                    boolean door = hollow && y >= 0 && y < 3 && inDoor(dx, dz, doorAng) && dx * dx + dz * dz <= (rad - 1) * (rad - 1);
                    p.set(x0 + dx, base + y, z0 + dz, door ? Builders.AIR : Builders.log(log, Direction.Axis.Y));
                }
            }
        }
        // limbs
        for (double[] l : limb) {
            int steps = (int) Math.ceil(l[2]);
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                int x = x0 + (int) Math.round(Math.cos(l[0]) * l[2] * t);
                int z = z0 + (int) Math.round(Math.sin(l[0]) * l[2] * t);
                int y = base + (int) Math.round(l[1] + l[3] * Math.sqrt(t));
                Direction.Axis ax = Math.abs(Math.cos(l[0])) > Math.abs(Math.sin(l[0])) ? Direction.Axis.X : Direction.Axis.Z;
                p.set(x, y, z, Builders.log(log, ax));
                if (t < 0.5) p.set(x, y - 1, z, Builders.log(log, ax));
                ball(p, x, y + 2, z, 2.6 * Math.max(0.5, t), leaves);
            }
        }
        for (double[] b : blob) {
            ball(p, x0 + (int) b[0], base + (int) b[1], z0 + (int) b[2], b[3], leaves);
        }
    }

    private static boolean inDoor(int dx, int dz, int side) {
        return switch (side) {
            case 0 -> dz > 0 && Math.abs(dx) <= 1;
            case 1 -> dz < 0 && Math.abs(dx) <= 1;
            case 2 -> dx > 0 && Math.abs(dz) <= 1;
            default -> dx < 0 && Math.abs(dz) <= 1;
        };
    }

    static void ball(ChunkPainter p, int cx, int cy, int cz, double r, BlockState leaves) {
        int ri = (int) Math.ceil(r);
        if (cx + ri < p.minX || cx - ri > p.minX + 15 || cz + ri < p.minZ || cz - ri > p.minZ + 15) return;
        for (int dx = -ri; dx <= ri; dx++)
            for (int dy = -ri; dy <= ri; dy++)
                for (int dz = -ri; dz <= ri; dz++)
                    if (dx * dx + dy * dy * 1.6 + dz * dz <= r * r) p.soft(cx + dx, cy + dy, cz + dz, leaves);
    }

    /**
     * Ground under a landmark's centre. Only the chunk holding the centre can read it, so every chunk derives it
     * the same way from the terrain map instead: see {@link Places#groundAt}.
     */
    static int baseHeight(ChunkPainter p, int x, int z, int r) {
        return Places.groundAt(x, z);
    }
}
