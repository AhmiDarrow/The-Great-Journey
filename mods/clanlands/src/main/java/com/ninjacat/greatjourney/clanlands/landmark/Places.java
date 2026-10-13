package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.layout.Geo;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Noise;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/** The built landmarks: hollows, rocks, pools, bridges, camps and Twoleg places. */
final class Places {
    private Places() {}

    /** Ground height of a column as the terrain map planned it: the same answer in every chunk. */
    static int groundAt(double x, double z) {
        ClanWorld w = ClanWorld.current();
        return w == null ? 64 : (int) Math.round(w.sample(x, z).height());
    }

    private static double dist(double x, double z, Landmark l) {
        return Math.hypot(x - l.x(), z - l.z());
    }

    /** Runs {@code each} for every column of this chunk within {@code radius} of the landmark. */
    private static void columns(ChunkPainter p, Landmark l, double radius, ColumnOp each) {
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = p.minX + dx, z = p.minZ + dz;
                double d = dist(x + 0.5, z + 0.5, l);
                if (d <= radius) each.at(x, z, d);
            }
        }
    }

    interface ColumnOp {
        void at(int x, int z, double d);
    }

    /** How far past the crest of the Fourtrees hollow the woods are kept back to ferns and low bushes. */
    private static final double FOURTREES_RIM = 12;

    /**
     * Fourtrees: four great oaks, one for each Clan, standing round an open grassy hollow, and the Great Rock in the
     * middle. The oaks stand far enough out that their crowns never meet over the rock; the floor is cleared, kept dry
     * and grassed; the walls and a band past the crest are kept to ferns and low bushes, so the hollow reads as a
     * hollow from the rim and the oaks rise out of it over the woods.
     */
    static void fourtrees(ChunkPainter p, Landmark l, RandomSource r) {
        // the landmark reaches 24 past the hollow's radius (OldForest); its wall runs 5 either side of that radius
        double hollowR = l.radius() - 24, turn = l.facing();
        // a crown spreads up to about 15 from its trunk: this far out the rock and a ring round it stay open to the sky,
        // and each trunk still stands on the floor, clear of the foot of the wall
        double spread = Math.min(hollowR - 10, 30 + (hollowR - 40) * 0.3) - r.nextDouble();
        long[] seeds = {r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong()};
        double rockAng = r.nextDouble() * Math.PI * 2;
        int rockH = 7 + r.nextInt(3);
        double rockR = 4.5 + r.nextDouble();
        double floor = hollowR - 4, rim = hollowR + 5 + FOURTREES_RIM;
        Noise bushes = new Noise(l.seed(), "fourtrees.bushes", 7, 2);
        columns(p, l, rim, (x, z, d) -> {
            int g = p.ground(x, z);
            p.clearGrowth(x, g, z, 40);
            if (d < floor) {
                // an open, dry, grassy floor: a flooded pit or a cave mouth in it is filled back up to the plan
                if (!p.get(x, g, z).getFluidState().isEmpty()) g = Camps.drain(p, x, z, g);
                int top = Camps.level(p, x, z, g);
                // and where the real floor came out above the plan (by about four blocks on seed 20261012, which left
                // the Great Rock, built from the plan, only three blocks high) it is cut down to it, easing back to the
                // real ground toward the foot of the wall
                int planned = groundAt(x + 0.5, z + 0.5);
                int want = (int) Math.round(planned + (top - planned) * Geo.smoothstep(floor - 8, floor, d));
                if (top > want) {
                    for (int y = want + 1; y <= top; y++) p.set(x, y, z, Builders.AIR);
                    top = want;
                }
                p.set(x, top, z, Blocks.GRASS_BLOCK.defaultBlockState());
                if (Camps.hash(x, top, z) < 0.25) p.set(x, top + 1, z, Blocks.SHORT_GRASS.defaultBlockState());
            } else if (p.get(x, g, z).is(BlockTags.DIRT)) {
                double b = bushes.at(x, z);
                if (d > hollowR && b > 0.3) {
                    p.set(x, g + 1, z, Builders.OAK_LEAVES);
                    if (b > 0.5) p.set(x, g + 2, z, Builders.OAK_LEAVES);
                } else if (Camps.hash(x, g, z) < 0.2) {
                    p.set(x, g + 1, z, Blocks.FERN.defaultBlockState());
                }
            }
        });
        // the woods round about grow after this chunk is painted, and their crowns reach in over the band: sweep their
        // leaves (never the oaks' or the bushes', which are persistent) off it in the chunks next door
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                ChunkPainter q = new ChunkPainter(p.level, p.minX + ox * 16, p.minZ + oz * 16);
                columns(q, l, rim, (x, z, d) -> {
                    int g = q.ground(x, z);
                    for (int y = g + 1; y <= g + 30; y++) {
                        BlockState s = q.get(x, y, z);
                        if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) {
                            q.set(x, y, z, Builders.AIR);
                        }
                    }
                });
            }
        }
        for (int i = 0; i < 4; i++) {
            double a = turn + Math.PI / 4 + i * Math.PI / 2;
            Trees.giant(p, l.x() + Math.cos(a) * spread, l.z() + Math.sin(a) * spread,
                    RandomSource.create(seeds[i]), 2, 26, 11, Builders.OAK_LOG, Builders.OAK_LEAVES, false);
        }
        double rx = l.x() + Math.cos(rockAng) * 3, rz = l.z() + Math.sin(rockAng) * 3;
        int base = groundAt(rx, rz);
        Landmark rock = new Landmark(Landmark.Kind.GREAT_ROCK, rx, rz, rockR + 2, 0, 0);
        columns(p, rock, rockR + 1.5, (x, z, d) -> {
            int top = base + (int) Math.round(rockH * Math.sqrt(Math.max(0, 1 - Math.pow(d / (rockR + 1.5), 2.2))));
            for (int y = base - 3; y <= top; y++) p.set(x, y, z, y > top - 1 ? Blocks.SMOOTH_STONE.defaultBlockState() : Builders.STONE);
            if (d > rockR && top - base > 2) p.set(x, base + 2, z, Blocks.STONE_SLAB.defaultBlockState());   // a ledge to climb by
        });
    }

    private static final BlockState FALLING_WATER = Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 8);

    /** The Moonpool: a pool at the floor of the steep hollow, a waterfall from a cleft, a spiral of pawprints. */
    static void moonpool(ChunkPainter p, Landmark l, RandomSource r) {
        double pool = 5 + r.nextDouble() * 1.5;
        double fall = l.facing();
        int floor = groundAt(l.x(), l.z());
        columns(p, l, 20, (x, z, d) -> {
            int g = p.ground(x, z);
            if (d < pool) {
                for (int y = floor - 2; y <= floor; y++) p.set(x, y, z, Builders.WATER);
                p.set(x, floor - 3, z, Blocks.STONE.defaultBlockState());
                p.clearAbove(x, floor, z, 6);
            } else if (d < 16) {
                p.set(x, g, z, d < pool + 2 ? Blocks.SMOOTH_STONE.defaultBlockState() : Builders.STONE);
                double ang = Math.atan2(z + 0.5 - l.z(), x + 0.5 - l.x());
                double spiral = (ang + Math.PI) / (Math.PI * 2) * 7 + pool;                // the path winds down
                if (Math.abs(d - spiral) < 0.6 && ((x + z) & 1) == 0) p.set(x, g, z, Blocks.COARSE_DIRT.defaultBlockState());
            } else if (d < 19) {
                p.set(x, g, z, Builders.STONE);
            }
            double ang = Math.atan2(z + 0.5 - l.z(), x + 0.5 - l.x());
            double off = Math.abs(Math.atan2(Math.sin(ang - fall), Math.cos(ang - fall)));
            if (off < 0.09 && d >= pool - 1 && d < 18) {                                  // the waterfall
                int top = floor + 12;
                // a spring in the cleft at the top and a falling curtain under it, not a standing pillar of still
                // water down the hollow's wall; then a run along the floor to the pool
                for (int y = floor; y <= top; y++) {
                    if (d > 14) p.set(x, y, z, y == top ? Builders.WATER : FALLING_WATER);
                    else if (y <= floor + 1) p.set(x, y, z, Builders.WATER);
                }
                if (d > 14 && p.inside(x, z)) p.level.scheduleTick(new net.minecraft.core.BlockPos(x, top, z), net.minecraft.world.level.material.Fluids.WATER, 2);
            }
        });
    }

    /** Mothermouth: a square black opening under a stone arch, a winding tunnel down to the Moonstone cavern. */
    static void mothermouth(ChunkPainter p, Landmark l, RandomSource r) {
        double dirX = Math.cos(l.facing() - Math.PI / 2), dirZ = Math.sin(l.facing() - Math.PI / 2);
        int entry = groundAt(l.x(), l.z());
        double len = 30 + r.nextDouble() * 10;
        double wiggle = r.nextDouble() * Math.PI * 2;
        double cavX = l.x() - dirX * len, cavZ = l.z() - dirZ * len;
        int cavY = entry - 14;
        BlockState moonstone = Builders.optional("warriorcats_events:moonstone", Blocks.CALCITE.defaultBlockState());
        for (double t = 0; t <= len; t += 0.5) {
            double side = Math.sin(t / 6 + wiggle) * 3;
            double x = l.x() - dirX * t - dirZ * side, z = l.z() - dirZ * t + dirX * side;
            int y = entry - (int) Math.round(14 * t / len);
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++)
                for (int h = 0; h < 3; h++) p.set((int) Math.floor(x) + a, y + h, (int) Math.floor(z) + b, Builders.AIR);
        }
        Landmark cav = new Landmark(Landmark.Kind.MOTHERMOUTH, cavX, cavZ, 9, 0, 0);
        columns(p, cav, 8, (x, z, d) -> {
            int h = (int) Math.round(6 * Math.sqrt(1 - Math.pow(d / 8, 2)));
            for (int y = cavY; y < cavY + h; y++) p.set(x, y, z, Builders.AIR);
            p.set(x, cavY - 1, z, Blocks.SMOOTH_BASALT.defaultBlockState());
            if (d < 1.2) for (int y = cavY; y < cavY + 3; y++) p.set(x, y, z, moonstone);
            if (d < 1.5) for (int y = cavY + h; y < entry + 30; y++) p.set(x, y, z, Builders.AIR);   // the roof hole
        });
        // the arch over the opening
        for (int s = -3; s <= 3; s++) {
            int ax = (int) Math.floor(l.x() - dirZ * s), az = (int) Math.floor(l.z() + dirX * s);
            for (int y = entry; y <= entry + 4; y++) {
                boolean opening = Math.abs(s) <= 1 && y < entry + 3;
                p.set(ax, y, az, opening ? Builders.AIR : Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
    }

    /** Sunningrocks: big smooth pale slabs stacked by the river, warm in the sun. */
    static void sunningrocks(ChunkPainter p, Landmark l, RandomSource r) {
        int n = 4 + r.nextInt(3);
        double[][] slab = new double[n][];
        for (int i = 0; i < n; i++)
            slab[i] = new double[] {l.x() + (r.nextDouble() - 0.5) * 22, l.z() + (r.nextDouble() - 0.5) * 22, 4 + r.nextDouble() * 5, 1 + r.nextInt(4)};
        for (double[] s : slab) {
            int base = groundAt(s[0], s[1]);
            Landmark one = new Landmark(Landmark.Kind.SUNNINGROCKS, s[0], s[1], s[2], 0, 0);
            columns(p, one, s[2], (x, z, d) -> {
                int top = base + (int) s[3];
                for (int y = base - 2; y <= top; y++) p.set(x, y, z, Blocks.SMOOTH_STONE.defaultBlockState());
                if (d > s[2] - 1.2) p.set(x, top, z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                p.clearAbove(x, top, z, 4);
            });
        }
    }

    /** Snakerocks: a tumbled pile of rounded rocks with cracks and a cave beneath. */
    static void snakerocks(ChunkPainter p, Landmark l, RandomSource r) {
        int n = 7 + r.nextInt(4);
        double[][] rocks = new double[n][];
        for (int i = 0; i < n; i++) {
            double bx = l.x() + (r.nextDouble() - 0.5) * 18, bz = l.z() + (r.nextDouble() - 0.5) * 18, br = 2.5 + r.nextDouble() * 2.5;
            boolean sandy = r.nextBoolean();
            rocks[i] = new double[] {bx, bz, br, sandy ? 1 : 0, r.nextInt(3)};
        }
        // heaped column by column on the real ground (rocks set on the planned ground sank out of sight where the
        // land came out higher than the plan), with the trees cleared off them and a crevice for the adders
        int cx = (int) Math.floor(l.x()), cz = (int) Math.floor(l.z());
        columns(p, l, 16, (x, z, d) -> {
            double best = 0;
            boolean sandy = false;
            for (double[] k : rocks) {
                double dd = Math.hypot(x + 0.5 - k[0], z + 0.5 - k[1]);
                if (dd >= k[2]) continue;
                double h = Math.sqrt(k[2] * k[2] - dd * dd) + k[4] - 1;
                if (h > best) {
                    best = h;
                    sandy = k[3] > 0;
                }
            }
            if (best < 0.5) return;
            int g = p.ground(x, z);
            if (!p.get(x, g, z).getFluidState().isEmpty()) return;
            p.clearGrowth(x, g, z, 14);
            int top = g + (int) Math.round(best);
            for (int y = g + 1; y <= top; y++) p.set(x, y, z, sandy ? Blocks.SANDSTONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState());
            if (z == cz && Math.abs(x - cx) <= 4 && top >= g + 3) {
                p.set(x, g + 1, z, Builders.AIR);
                p.set(x, g + 2, z, Builders.AIR);
            }
        });
    }

    /** The fallen tree that links the Gathering island to the RiverClan shore. */
    /**
     * The tree-bridge: a great oak that fell from the RiverClan shore across to the Gathering island. A trunk two
     * blocks thick, wide enough for a cat to cross, its torn-up root plate standing on the shore, broken limbs and a
     * few last leaves where its crown came down on the island.
     */
    static void treeBridge(ChunkPainter p, Landmark l, RandomSource r) {
        // facing is the island's compass bearing from the lake centre, in radians: +t runs toward the shore
        double bx = Math.sin(l.facing()), bz = -Math.cos(l.facing());
        double half = l.radius() - 4;
        double sag = 0.6 + r.nextDouble() * 0.8;
        int limbs = 5 + r.nextInt(4);
        double[][] limb = new double[limbs][];
        for (int i = 0; i < limbs; i++) {
            limb[i] = new double[] {-half * (0.2 + r.nextDouble() * 0.75), r.nextBoolean() ? 1 : -1, 2 + r.nextInt(4), r.nextInt(3)};
        }
        Direction.Axis along = Math.abs(bx) > Math.abs(bz) ? Direction.Axis.X : Direction.Axis.Z;
        double sx = -bz, sz = bx;                                              // sideways
        // the walk along the top never steps more than a block: the trunk's top is one smooth line from end to end,
        // two logs thick over the water and wherever the land under it lies low, one log where it rests on ground
        // just under that line, buried where the land stands as high (on seed 4242 the trunk sat one log high on the
        // beach and two over the water beside it: a two-block step no cat could climb, so no Gathering)
        java.util.List<int[]> walk = new java.util.ArrayList<>(), ashore = new java.util.ArrayList<>();
        double islandMiddle = -(l.radius() - 11);                         // where the Gathering's great tree stands
        for (double t = -half; t <= half + 1; t += 0.35) {
            double u = t / half;
            int y = 64 + (int) Math.round(1.2 + sag * (1 - u * u) - (u < -0.6 ? (-0.6 - u) * 3 : 0));
            for (int w = 0; w <= 1; w++) {
                int x = (int) Math.floor(l.x() + bx * t + sx * w), z = (int) Math.floor(l.z() + bz * t + sz * w);
                if (!p.inside(x, z)) continue;
                int g = p.ground(x, z);
                if (p.get(x, g, z).getFluidState().isEmpty() && g >= y) {
                    // buried in the island: its line on up to the bridge is kept clear of trees (on seed 777001 one
                    // stood on it where the trunk came out of the ground), clear of the island's own great tree
                    if (t > islandMiddle + 10) ashore.add(new int[] {x, g, z});
                    continue;
                }
                p.set(x, y, z, Builders.log(Builders.OAK_LOG, along));
                if (g < y - 1) p.set(x, y - 1, z, Builders.log(Builders.OAK_LOG, along));
                walk.add(new int[] {x, y, z, ((int) Math.floor(t * 0.7) & 3) == 0 && w == 0 ? 1 : 0});
            }
        }
        // the root plate, torn up and standing on end at the shore
        double rt = half + 1.5;
        int ry = 64;
        for (int a2 = -4; a2 <= 4; a2++) for (int h = -1; h <= 6; h++) {
            if (a2 * a2 + (h - 2) * (h - 2) > 18) continue;
            int x = (int) Math.floor(l.x() + bx * rt + sx * a2), z = (int) Math.floor(l.z() + bz * rt + sz * a2);
            boolean rim = a2 * a2 + (h - 2) * (h - 2) > 11;
            p.set(x, ry + h, z, rim ? Blocks.MANGROVE_ROOTS.defaultBlockState() : Blocks.ROOTED_DIRT.defaultBlockState());
        }
        // broken limbs and a few last leaves toward the island end
        for (double[] lb : limb) {
            double t = lb[0];
            int y0 = 64 + (int) Math.round(1.2 + sag * (1 - (t / half) * (t / half)));
            for (int k = 1; k <= (int) lb[2]; k++) {
                int x = (int) Math.floor(l.x() + bx * t + sx * lb[1] * (k + 1)), z = (int) Math.floor(l.z() + bz * t + sz * lb[1] * (k + 1));
                p.set(x, y0 + k / 2, z, Builders.log(Builders.OAK_LOG, along == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X));
                if (k == (int) lb[2] && lb[3] == 0) Trees.ball(p, x, y0 + k / 2 + 1, z, 1.8, Builders.OAK_LEAVES);
            }
        }
        // headroom over the walk (no tree, leaves or plants a cat would have to push through), and moss here and there
        for (int[] c : ashore) p.clearGrowth(c[0], c[1], c[2], 12);
        for (int[] c : walk) {
            p.clearGrowth(c[0], c[1], c[2], 3);
            if (c[3] == 1) p.soft(c[0], c[1] + 1, c[2], Blocks.MOSS_CARPET.defaultBlockState());
        }
    }

    /**
     * Stepping stones: flat-topped rocks a hop apart across the water, each standing just clear of the surface on a
     * pillar down to the bed, mossy on top, a little out of line so they look placed by water, not by Twolegs.
     */
    static void steppingStones(ChunkPainter p, Landmark l, RandomSource r) {
        // facing is the river's compass bearing at this spot (radians): the stones run across it
        double fx = Math.sin(l.facing()), fz = -Math.cos(l.facing());
        double cxr = -fz, czr = fx;
        int count = (int) Math.ceil((l.radius() - 6) * 2 / 2.0) + 2;
        double[] wob = new double[count];
        boolean[] big = new boolean[count];
        for (int i = 0; i < count; i++) {
            wob[i] = (r.nextDouble() - 0.5) * 1.2;
            big[i] = r.nextInt(3) == 0;
        }
        int water = 63;
        for (int i = 0; i < count; i++) {
            double t = (i - (count - 1) / 2.0) * 2.0;
            int x = (int) Math.floor(l.x() + cxr * t + fx * wob[i]), z = (int) Math.floor(l.z() + czr * t + fz * wob[i]);
            if (!p.inside(x, z)) continue;
            // the stream's real surface, read from the blocks (the worldgen heightmap still has the ground from before
            // the stream was laid: on a stream above sea level the stones came out a block under its water)
            int top = water;
            for (int y = 160; y > 40; y--) {
                BlockState s = p.get(x, y, z);
                if (!s.getFluidState().isEmpty()) {
                    top = y + 1;                                                // a stone stands clear of the water
                    break;
                }
                if (!(s.isAir() || s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS))) {
                    top = Math.max(water, y);                                   // on the bank: set into the ground
                    break;
                }
            }
            for (int dx = 0; dx <= (big[i] ? 1 : 0); dx++) {
                for (int y = top - 5; y <= top; y++) {
                    if (!p.inside(x + dx, z)) continue;
                    var at = p.get(x + dx, y, z);
                    if (at.isAir() || !at.getFluidState().isEmpty() || y == top) {
                        p.set(x + dx, y, z, y == top ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
    }

    /**
     * A half-bridge: a wooden jetty from the shore out over the deep berth the lake keeps for it, on posts that stand
     * on the lake floor.
     */
    static void halfBridge(ChunkPainter p, Landmark l, RandomSource r) {
        double bx = Math.sin(l.facing() - Math.PI), bz = -Math.cos(l.facing() - Math.PI);
        int len = 13 + r.nextInt(5);
        for (int t = -3; t <= len; t++) {
            for (int w = -1; w <= 1; w++) {
                int x = (int) Math.floor(l.x() - bx * t + bz * w), z = (int) Math.floor(l.z() - bz * t - bx * w);
                if (!p.inside(x, z)) continue;
                p.set(x, 64, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                p.clearAbove(x, 64, z, 3);
                if (Math.abs(w) == 1 && t % 3 == 0) {
                    for (int y = 63; y > 48; y--) {
                        BlockState at = p.get(x, y, z);
                        if (!(at.isAir() || !at.getFluidState().isEmpty() || at.canBeReplaced())) break;
                        p.set(x, y, z, Blocks.SPRUCE_FENCE.defaultBlockState().setValue(FenceBlock.WATERLOGGED, !at.getFluidState().isEmpty()));
                    }
                }
            }
        }
    }

    /** A Twoleg cottage (the abandoned nest has a broken roof and an overgrown herb garden). */
    static void cottage(ChunkPainter p, Landmark l, RandomSource r, boolean abandoned) {
        int w = 4 + r.nextInt(2), dpt = 3 + r.nextInt(2), wallH = 4;
        int base = groundAt(l.x(), l.z());
        int x0 = (int) l.x(), z0 = (int) l.z();
        for (int dx = -w; dx <= w; dx++) {
            for (int dz = -dpt; dz <= dpt; dz++) {
                boolean edge = Math.abs(dx) == w || Math.abs(dz) == dpt;
                for (int y = base - 2; y < base; y++) p.set(x0 + dx, y, z0 + dz, Blocks.COBBLESTONE.defaultBlockState());
                p.set(x0 + dx, base, z0 + dz, Blocks.SPRUCE_PLANKS.defaultBlockState());
                for (int y = 1; y <= wallH; y++) {
                    boolean door = dz == dpt && Math.abs(dx) <= 0 && y <= 2;
                    boolean window = edge && y == 2 && (dx + dz) % 3 == 0;
                    boolean ruined = abandoned && y >= 3 && ((dx * 7 + dz * 13) & 3) == 0;
                    BlockState s = edge && !door && !window && !ruined ? Blocks.STONE_BRICKS.defaultBlockState()
                            : (window && !ruined ? Blocks.GLASS_PANE.defaultBlockState() : Builders.AIR);
                    p.set(x0 + dx, base + y, z0 + dz, s);
                }
                if (!abandoned || ((dx + dz) & 1) == 0) p.set(x0 + dx, base + wallH + 1, z0 + dz, Blocks.SPRUCE_SLAB.defaultBlockState());
            }
        }
        if (abandoned) {
            // the herb garden in front: catmint in the books; flowers and bushes here
            for (int dx = -w; dx <= w; dx++) for (int dz = dpt + 2; dz <= dpt + 6; dz++) {
                if (((dx * 31 + dz * 17) & 3) == 0) p.soft(x0 + dx, base + 1, z0 + dz, Blocks.ALLIUM.defaultBlockState());
                else if (((dx * 11 + dz * 5) & 7) == 0) p.soft(x0 + dx, base + 1, z0 + dz, Blocks.SWEET_BERRY_BUSH.defaultBlockState());
            }
        }
    }

    /** A kittypet's home: a tidy Twoleg nest with a fenced garden, a kittypet bed and a food bowl by the door. */
    static void kittypetHome(ChunkPainter p, Landmark l, RandomSource r) {
        cottage(p, l, r, false);
        int base = groundAt(l.x(), l.z());
        double gr = 13;
        columns(p, l, gr + 0.8, (x, z, d) -> {
            int g = p.ground(x, z);
            if (d > gr - 0.8) {
                p.clearAbove(x, g, z, 3);
                boolean gate = Math.abs(x - (int) l.x()) <= 0 && z > l.z();
                if (!gate) p.set(x, g + 1, z, Blocks.BIRCH_FENCE.defaultBlockState());
            } else if (d > 7 && ((x * 13 + z * 7) & 7) == 0) {
                p.soft(x, g + 1, z, Blocks.POPPY.defaultBlockState());
            }
        });
        int bx = (int) l.x() + 2, bz = (int) l.z() + 6;
        p.set(bx, base + 1, bz, Builders.optional("warriorcats_events:kittypet_bed", Blocks.RED_CARPET.defaultBlockState()));
        p.set(bx + 2, base + 1, bz, Builders.optional("warriorcats_events:red_kittypet_bowl", Blocks.FLOWER_POT.defaultBlockState()));
    }

    /**
     * The tunnels: winding bores from three entrances down to a cavern with a dark stream, a glowrock here and there
     * so an apprentice can find the way. Never cuts into water, so the border stream and the lake stay sealed.
     */
    static void tunnels(ChunkPainter p, Landmark l, RandomSource r) {
        ClanWorld w = ClanWorld.current();
        if (w == null || w.lake.tunnels() == null) return;
        var t = w.lake.tunnels();
        int deep = (int) Math.ceil(t.cy + t.cavernRy + 3);         // the cavern's roof and a little more
        BlockState glow = Builders.optional("warriorcats_events:glowrocks", Blocks.GLOW_LICHEN.defaultBlockState());
        for (var bore : t.bores) carveBore(p, bore, glow, false, deep);
        carveBore(p, t.stream, glow, true, deep);
        // the cavern: a wide low dome, its floor left level
        int x0 = (int) Math.floor(t.cx), z0 = (int) Math.floor(t.cz), y0 = (int) Math.floor(t.cy);
        int rx = (int) Math.ceil(t.cavernRx), ry = (int) Math.ceil(t.cavernRy);
        for (int x = x0 - rx; x <= x0 + rx; x++) {
            for (int z = z0 - rx; z <= z0 + rx; z++) {
                if (!p.inside(x, z)) continue;
                double dxz = Math.pow((x - t.cx) / t.cavernRx, 2) + Math.pow((z - t.cz) / t.cavernRx, 2);
                if (dxz > 1) continue;
                int top = y0 + (int) Math.round(t.cavernRy * Math.sqrt(1 - dxz));
                for (int y = y0 - ry + 2; y <= top; y++) safeAir(p, deep, x, y, z);
                if (dxz < 0.25 && ((x * 7 + z * 13) & 31) == 0) p.set(x, y0 - ry + 2, z, glow);
            }
        }
        // each mouth is a portal in the real ground with a short open approach: over a tunnel's first stretch the
        // roof is opened only where it would be too thin to stand (a cut a few blocks deep where the bore runs just
        // under the ground); where the land stands thick over it, the bore stays a tunnel (opened all the way up, a
        // mouth on a hillside was an open trench 20 to 60 blocks long on seed -84596914)
        for (var bore : t.bores) {
            for (int i = 0; i < Math.min(14, bore.xs().length); i++) {
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    if (dx * dx + dz * dz > 4) continue;
                    int x = (int) Math.floor(bore.xs()[i]) + dx, z = (int) Math.floor(bore.zs()[i]) + dz;
                    if (!p.inside(x, z)) continue;
                    int g = p.ground(x, z), by = (int) Math.floor(bore.ys()[i]);
                    if (g > by + PORTAL_ROOF) continue;
                    for (int y = by; y <= g; y++) safeAir(p, deep, x, y, z);
                }
            }
        }
        // and where the real ground stands over a mouth, the passage climbs on out of it a block a step until it
        // comes out on the hillside: inside the hill a covered passage, out at its face the same short open cut
        for (var bore : t.bores) {
            int n = bore.xs().length;
            if (n < 4) continue;
            double ux = bore.xs()[0] - bore.xs()[3], uz = bore.zs()[0] - bore.zs()[3], ul = Math.max(1e-6, Math.hypot(ux, uz));
            ux /= ul;
            uz /= ul;
            for (int j = 1; j <= 24; j++) {
                int floor = (int) Math.floor(bore.ys()[0]) - 2 + j;              // the bore's floor, then a block a step
                int mx = (int) Math.floor(bore.xs()[0] + ux * j), mz = (int) Math.floor(bore.zs()[0] + uz * j);
                if (p.readable(mx, mz) && p.solidTop(mx, mz, floor + 40, floor - 8) < floor) break;   // out on the hillside
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    int x = (int) Math.floor(bore.xs()[0] + ux * j) + dx, z = (int) Math.floor(bore.zs()[0] + uz * j) + dz;
                    if (!p.inside(x, z)) continue;
                    int g = p.ground(x, z);
                    if (g < floor) continue;                                   // out in the open already
                    int roof = g > floor + 2 + PORTAL_ROOF ? floor + 3 : g;
                    for (int y = floor; y <= roof; y++) safeAir(p, deep, x, y, z);
                }
            }
        }
        // each entrance: a dark mouth in the ground, rimmed with moss
        for (double[] e : t.entrances) {
            int ex = (int) Math.floor(e[0]), ey = (int) Math.round(e[1]), ez = (int) Math.floor(e[2]);
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 <= 4) for (int y = ey - 2; y <= ey + 1; y++) safeAir(p, deep, ex + dx, y, ez + dz);
                else if (d2 <= 10 && p.inside(ex + dx, ez + dz) && Math.abs(p.ground(ex + dx, ez + dz) - ey) <= 2)
                    p.set(ex + dx, p.ground(ex + dx, ez + dz), ez + dz, Blocks.MOSS_BLOCK.defaultBlockState());
            }
        }
    }

    private static void carveBore(ChunkPainter p, com.ninjacat.greatjourney.clanlands.layout.Tunnels.Bore b, BlockState glow, boolean water, int deep) {
        double rr = b.radius();
        if (b.maxX() + rr < p.minX || b.minX() - rr > p.minX + 15 || b.maxZ() + rr < p.minZ || b.minZ() - rr > p.minZ + 15) return;
        int ri = (int) Math.ceil(rr);
        for (int i = 0; i < b.xs().length; i++) {
            double bx = b.xs()[i], by = b.ys()[i], bz = b.zs()[i];
            if (bx + rr < p.minX || bx - rr > p.minX + 15 || bz + rr < p.minZ || bz - rr > p.minZ + 15) continue;
            for (int dx = -ri; dx <= ri; dx++) for (int dy = -ri; dy <= ri + 1; dy++) for (int dz = -ri; dz <= ri; dz++) {
                if (dx * dx + dz * dz + dy * dy * 0.7 > rr * rr) continue;
                int x = (int) Math.floor(bx) + dx, y = (int) Math.floor(by) + dy, z = (int) Math.floor(bz) + dz;
                if (water && dy <= 0) {
                    if (p.inside(x, z) && p.get(x, y, z).getFluidState().isEmpty() && !p.get(x, y, z).isAir()) p.set(x, y, z, Builders.WATER);
                } else {
                    safeAir(p, deep, x, y, z);
                }
            }
            if (!water && i % 17 == 8) p.set((int) Math.floor(bx), (int) Math.floor(by) - ri + 1, (int) Math.floor(bz), glow);
        }
    }

    /** A tunnel mouth's roof is opened up only where it would be at most this many blocks over the bore's middle. */
    private static final int PORTAL_ROOF = 4;

    /** Air, unless that would open the tunnels to water; at or below {@code tunnelDeep} only water above counts. */
    private static void safeAir(ChunkPainter p, int tunnelDeep, int x, int y, int z) {
        if (!p.inside(x, z)) return;
        // water stops a tunnel (lava does not: the lava sweep afterwards turns whatever lava is left to stone)
        if (p.get(x, y, z).getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                || p.get(x, y + 1, z).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return;
        // nor, up near the surface, beside water: a tunnel cut next to a stream's bed would leave the water
        // standing against air (down in the cavern the dark stream's own banks are meant to be open)
        if (y <= tunnelDeep) {
            p.set(x, y, z, Builders.AIR);
            return;
        }
        for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (p.readable(x + o[0], z + o[1]) && p.get(x + o[0], y, z + o[1]).is(Blocks.WATER)) return;
        }
        p.set(x, y, z, Builders.AIR);
    }

    /** A farm: barn, paddock fence and a field; the horses come from a spawn rule on the pasture biome. */
    static void farm(ChunkPainter p, Landmark l, RandomSource r) {
        Landmark barn = new Landmark(Landmark.Kind.TWOLEG_NEST, l.x() + 12, l.z() - 10, 8, 0, r.nextLong());
        cottage(p, barn, RandomSource.create(barn.seed()), false);
        double pr = 18 + r.nextDouble() * 6;
        columns(p, l, pr + 0.8, (x, z, d) -> {
            if (d > pr - 0.8) {
                int g = p.ground(x, z);
                p.clearAbove(x, g, z, 3);
                p.set(x, g + 1, z, Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.NORTH, false));
            }
        });
    }

    /** Carrionplace: heaps of junk behind an iron fence. */
    static void carrionplace(ChunkPainter p, Landmark l, RandomSource r) {
        double fr = l.radius() - 6;
        columns(p, l, fr + 0.8, (x, z, d) -> {
            int g = p.ground(x, z);
            if (d > fr - 0.8) {
                p.clearAbove(x, g, z, 4);
                for (int y = 1; y <= 3; y++) p.set(x, g + y, z, Blocks.IRON_BARS.defaultBlockState());
            } else {
                p.set(x, g, z, Blocks.COARSE_DIRT.defaultBlockState());
                int heap = (int) Math.max(0, 3 * Math.sin(x * 0.4) * Math.cos(z * 0.35));
                for (int y = 1; y <= heap; y++)
                    p.set(x, g + y, z, ((x + z + y) % 3 == 0) ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
            }
        });
    }
}
