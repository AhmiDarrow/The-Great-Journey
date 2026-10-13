package com.ninjacat.greatjourney.clanlands.landmark;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds each landmark. A builder draws all of its random choices up front from the landmark's own seed (never
 * depending on which chunk is painting), then describes the shape in world coordinates.
 */
final class Builders {
    private Builders() {}

    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final BlockState WATER = Blocks.WATER.defaultBlockState();
    static final BlockState STONE = Blocks.STONE.defaultBlockState();
    static final BlockState OAK_LOG = Blocks.OAK_LOG.defaultBlockState();
    static final BlockState OAK_LEAVES = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

    static void build(Landmark l, ChunkPainter p) {
        RandomSource r = RandomSource.create(l.seed());
        switch (l.kind()) {
            case SKY_OAK, ANCIENT_OAK -> Trees.giant(p, l.x(), l.z(), r, 3, 30, 15, OAK_LOG, OAK_LEAVES, true);
            case ISLAND_TREE -> Trees.giant(p, l.x(), l.z(), r, 2, 24, 12, OAK_LOG, OAK_LEAVES, false);
            case OWL_TREE -> Trees.giant(p, l.x(), l.z(), r, 2, 20, 9, OAK_LOG, OAK_LEAVES, true);
            case GREAT_SYCAMORE -> Trees.giant(p, l.x(), l.z(), r, 2, 32, 13, Blocks.BIRCH_LOG.defaultBlockState(),
                    Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), false);
            case FOURTREES -> Places.fourtrees(p, l, r);
            case STONE_HOLLOW -> Camps.build(p, l);
            case MOONPOOL -> Places.moonpool(p, l, r);
            case MOTHERMOUTH -> Places.mothermouth(p, l, r);
            case SUNNINGROCKS -> Places.sunningrocks(p, l, r);
            case SNAKEROCKS -> Places.snakerocks(p, l, r);
            case TREE_BRIDGE -> Places.treeBridge(p, l, r);
            case HALF_BRIDGE -> Places.halfBridge(p, l, r);
            case SHADOWCLAN_CAMP, WINDCLAN_HOLLOW, RIVERCLAN_CAMP, THUNDERCLAN_RAVINE -> Camps.build(p, l);
            case ABANDONED_NEST, TWOLEG_NEST -> Places.cottage(p, l, r, true);
            case HORSEPLACE, BARLEY_FARM -> Places.farm(p, l, r);
            case CARRIONPLACE -> Places.carrionplace(p, l, r);
            case KITTYPET_HOME -> Places.kittypetHome(p, l, r);
            case TUNNELS -> Places.tunnels(p, l, r);
            case STEPPING_STONES -> Places.steppingStones(p, l, r);
            case HIGHWAY_STRETCH -> JourneyPlaces.highway(p, l);
            case TWOLEG_FARMS -> JourneyPlaces.farms(p, l, r);
            case TRIBE_CAVE -> JourneyPlaces.tribeCave(p, l, r);
            case MIDNIGHT_SETT -> JourneyPlaces.midnightSett(p, l, r);
            case LAKE_RIDGE -> JourneyPlaces.lookout(p, l, r);
            default -> { }
        }
    }

    static BlockState log(BlockState log, Direction.Axis axis) {
        return log.hasProperty(RotatedPillarBlock.AXIS) ? log.setValue(RotatedPillarBlock.AXIS, axis) : log;
    }

    /** A block from another mod if it is installed, else the fallback. */
    static BlockState optional(String id, BlockState fallback) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return b == Blocks.AIR ? fallback : b.defaultBlockState();
    }
}
