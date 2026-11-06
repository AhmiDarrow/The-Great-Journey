package com.ninjacat.greatjourney.clanlands.life.dream;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * StarClan's hunting grounds: a small starlit clearing in the dream dimension, built once, with a pool, silver-leaved
 * trees, a rock to climb, a herb patch and the four StarClan cats who teach a new cat.
 */
public final class DreamClearing {
    private DreamClearing() {}

    public static final ResourceKey<Level> DREAM =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("clanlands", "starclan"));
    /** Entity tag on the StarClan cats (and on a player's tutorial mouse, with the player's id in a second tag). */
    public static final String TAG = "greatjourney_starclan";
    public static final String MOUSE_TAG = "greatjourney_dream_mouse";

    static final int GROUND = 4;                                     // top of the flat world's grass layer
    public static final BlockPos ARRIVE = new BlockPos(0, GROUND + 1, 6);
    static final BlockPos ROCK = new BlockPos(-8, GROUND, -8);
    static final BlockPos POOL = new BlockPos(9, GROUND, -5);
    static final BlockPos HERBS = new BlockPos(7, GROUND, 8);        // corner of the herb patch
    static final BlockPos HUNT = new BlockPos(-6, GROUND + 1, 7);    // where Lionheart waits and the mouse runs

    /** Herb blocks of the patch (all fully grown), by Warrior Cats Events id. */
    public static final List<String> HERB_BLOCKS = List.of("catmint", "yarrow", "comfrey", "feverfew", "juniper",
            "dock", "sorrel", "burnet", "chamomile_stems", "daisy");

    /** Who teaches, where they sit, and their gender (0 tom, 1 she-cat). */
    record Teacher(String name, int gender, BlockPos pos) {}

    static final List<Teacher> TEACHERS = List.of(
            new Teacher("Bluestar", 1, ROCK.above(6)),
            new Teacher("Lionheart", 0, HUNT.offset(-2, 0, 0)),
            new Teacher("Spottedleaf", 1, HERBS.offset(-2, 1, 1)),
            new Teacher("Yellowfang", 1, POOL.offset(-4, 1, 0)));

    private static final BlockPos MARKER = new BlockPos(0, GROUND, 0);

    public static boolean built(ServerLevel level) {
        return level.getBlockState(MARKER).is(Blocks.MOSS_BLOCK);
    }

    /**
     * Builds the clearing if it is not there yet, and makes sure each of the four StarClan cats is there exactly once.
     * A teacher is only judged missing once the entities of the chunk they sit in have loaded (they load a moment
     * after the blocks, so a player arriving or logging in would otherwise see the teachers doubled); call again a
     * little later to fill in any still waiting.
     */
    public static void ensure(ServerLevel level) {
        if (!built(level)) {
            build(level);
        }
        for (Teacher t : TEACHERS) {
            if (!level.areEntitiesLoaded(ChunkPos.asLong(t.pos()))) continue;
            List<Entity> found = findAll(level, t.name());
            if (found.isEmpty()) {
                spawnTeacher(level, t);
            } else {
                if (found.get(0) instanceof WCatEntity cat) {
                    if (!cat.getTags().contains(com.ninjacat.greatjourney.clanlands.life.BookLooks.TAG)) {
                        com.ninjacat.greatjourney.clanlands.life.BookLooks.teacher(cat, t.name());   // an older world's teacher: their book coat, once
                    }
                    if (cat.hasEffect(MobEffects.GLOWING)) {
                        // an older world's teacher: no glowing outline (it drew them as white shapes floating in the
                        // dark), sat down where they belong
                        cat.removeEffect(MobEffects.GLOWING);
                        cat.moveTo(t.pos().getX() + 0.5, t.pos().getY(), t.pos().getZ() + 0.5, cat.getYRot(), 0);
                        com.ninjacat.greatjourney.clanlands.life.BookClans.rest(level, cat);
                        cat.setInSittingPose(true);
                    }
                }
                for (int i = 1; i < found.size(); i++) found.get(i).discard();
            }
        }
    }

    static void build(ServerLevel level) {
        for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) level.getChunk(cx, cz);   // load the area
        // ground: moss and grass in a soft disc, glow hidden under it so the clearing is gently lit
        for (int x = -22; x <= 22; x++) {
            for (int z = -22; z <= 22; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > 22) continue;
                boolean moss = d < 18 && ((x * 7 + z * 13) & 3) != 0;
                level.setBlock(new BlockPos(x, GROUND, z), moss || d < 2 ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                if (((x & 3) == 0) && ((z & 3) == 0)) level.setBlock(new BlockPos(x, GROUND - 1, z), Blocks.GLOWSTONE.defaultBlockState(), 2);
                if (d > 4 && d < 21 && ((x * 31 + z * 17) % 11) == 0) level.setBlock(new BlockPos(x, GROUND + 1, z), Blocks.SHORT_GRASS.defaultBlockState(), 2);
            }
        }
        level.setBlock(MARKER, Blocks.MOSS_BLOCK.defaultBlockState(), 2);
        // the pool
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            if (x * x + z * z > 9) continue;
            BlockPos p = POOL.offset(x, 0, z);
            level.setBlock(p.below(), x * x + z * z < 4 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.SAND.defaultBlockState(), 2);
            level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
        }
        // a rock to climb, with steps round its side
        for (int y = 0; y <= 5; y++) {
            int r = 3 - y / 2;
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (x * x + z * z <= r * r + 1) level.setBlock(ROCK.offset(x, y, z), y == 5 ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
            }
        }
        for (int y = 1; y <= 5; y++) level.setBlock(ROCK.offset(3 - y / 3, y, 4 - y / 2), Blocks.STONE_SLAB.defaultBlockState(), 2);
        // silver trees round the edge
        int[][] trees = {{-15, 2}, {13, 12}, {16, -11}, {-12, -16}, {2, -17}, {-17, 11}};
        for (int[] t : trees) tree(level, new BlockPos(t[0], GROUND + 1, t[1]));
        // the herb patch: two rows of fully grown herbs on grass
        for (int i = 0; i < HERB_BLOCKS.size(); i++) {
            BlockPos p = HERBS.offset(i % 5, 0, i / 5);
            level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            level.setBlock(p.above(), herb(HERB_BLOCKS.get(i)), 2);
        }
    }

    static BlockState herb(String id) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("warriorcats_events", id));
        BlockState s = b == Blocks.AIR ? Blocks.FERN.defaultBlockState() : b.defaultBlockState();
        return s.hasProperty(BlockStateProperties.AGE_3) ? s.setValue(BlockStateProperties.AGE_3, 3) : s;
    }

    private static void tree(ServerLevel level, BlockPos base) {
        BlockState log = Blocks.BIRCH_LOG.defaultBlockState();
        BlockState leaves = Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int y = 0; y < 7; y++) level.setBlock(base.above(y), log, 2);
        for (int x = -3; x <= 3; x++) for (int y = 4; y <= 9; y++) for (int z = -3; z <= 3; z++) {
            double d = x * x + (y - 7) * (y - 7) * 1.4 + z * z;
            BlockPos p = base.offset(x, y, z);
            if (d <= 10 && level.getBlockState(p).isAir()) level.setBlock(p, leaves, 2);
        }
    }

    static void spawnTeacher(ServerLevel level, Teacher t) {
        WCatEntity cat = ModEntities.WCAT.get().create(level);
        if (cat == null) return;
        // facing the spot where a dreamer arrives (they turn to whoever is near: Dream.teachersLook)
        float yaw = (float) Math.toDegrees(Math.atan2(-(ARRIVE.getX() - t.pos().getX()), ARRIVE.getZ() - t.pos().getZ()));
        cat.moveTo(t.pos().getX() + 0.5, t.pos().getY(), t.pos().getZ() + 0.5, yaw, 0);
        com.ninjacat.greatjourney.clanlands.life.BookClans.rest(level, cat);    // no AI means no gravity: right on the ground
        cat.setYHeadRot(yaw);
        cat.setYBodyRot(yaw);
        cat.finalizeSpawn(level, level.getCurrentDifficultyAt(t.pos()), MobSpawnType.COMMAND, null);
        cat.setGender(t.gender());
        com.ninjacat.greatjourney.clanlands.life.BookLooks.teacher(cat, t.name());
        cat.setCustomName(Component.literal(t.name()));
        cat.setCustomNameVisible(true);
        cat.mode = WCatEntity.CatMode.SIT;
        cat.setNoAi(true);
        cat.setInSittingPose(true);               // the SIT mode's goal never runs without AI: sat down here
        cat.setAgeInMoons(12);                    // with no AI the age never updates from 0, which draws a kit-sized cat
        cat.setInvulnerable(true);
        cat.setPersistenceRequired();
        cat.setSilent(false);
        cat.addTag(TAG);
        // no Glowing: its outline showed through everything, so in the dark clearing the teachers were white shapes
        // floating against the night; StarClan's frost of stars is drawn round them instead (Dream.teachersLook)
        level.addFreshEntity(cat);
    }

    public static Entity find(ServerLevel level, String name) {
        List<Entity> all = findAll(level, name);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<Entity> findAll(ServerLevel level, String name) {
        AABB box = new AABB(-40, 0, -40, 40, 40, 40);
        return level.getEntities((Entity) null, box, e -> e.getTags().contains(TAG)
                && e.getCustomName() != null && e.getCustomName().getString().equals(name));
    }

    /** Spawns a mouse for one player's hunting lesson near Lionheart; returns false if WCE has no mouse. */
    static boolean spawnMouse(ServerLevel level, java.util.UUID player) {
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("warriorcats_events", "mouse"));
        if (type.isEmpty()) return false;
        Entity mouse = type.get().create(level);
        if (mouse == null) return false;
        mouse.moveTo(HUNT.getX() + 3.5, HUNT.getY(), HUNT.getZ() + 1.5, 0, 0);
        mouse.addTag(MOUSE_TAG);
        mouse.addTag(MOUSE_TAG + ":" + player);
        if (mouse instanceof net.minecraft.world.entity.Mob m) m.setPersistenceRequired();
        level.addFreshEntity(mouse);
        return true;
    }
}
