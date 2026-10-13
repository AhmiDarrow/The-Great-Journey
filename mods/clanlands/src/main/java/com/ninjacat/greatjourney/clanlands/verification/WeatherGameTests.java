package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.life.events.WeatherData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Weather events put back what they change: across a save, and never over a block a player has placed since. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class WeatherGameTests {
    private WeatherGameTests() {}

    @GameTest(template = "empty")
    public static void weatherChangesSurviveASaveAndAreUndone(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos edge = h.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(edge, Blocks.WATER.defaultBlockState(), 3);
        WeatherData d = WeatherData.detached();
        d.change(level, edge, Blocks.ICE.defaultBlockState());
        h.assertTrue(level.getBlockState(edge).is(Blocks.ICE), "the lake edge froze");
        CompoundTag saved = d.save(new CompoundTag(), level.registryAccess());
        WeatherData back = WeatherData.fromTag(saved, level.registryAccess());
        h.assertTrue(back.pending() == 1, "the change survives a save, got " + back.pending());
        h.assertTrue(back.restoreLoaded(level) == 0, "nothing is left waiting once the chunk is loaded");
        h.assertTrue(level.getBlockState(edge).is(Blocks.WATER), "the ice thawed back to water");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayersBlockIsKept(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos marsh = h.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(marsh, Blocks.AIR.defaultBlockState(), 3);
        WeatherData d = WeatherData.detached();
        d.change(level, marsh, Blocks.WATER.defaultBlockState());
        d.change(level, marsh, Blocks.WATER.defaultBlockState());                  // the same block twice keeps the first original
        level.setBlock(marsh, Blocks.OAK_PLANKS.defaultBlockState(), 3);          // a player builds over the flood
        d.restoreLoaded(level);
        h.assertTrue(level.getBlockState(marsh).is(Blocks.OAK_PLANKS), "the flood going down leaves the player's block alone");
        h.assertTrue(d.pending() == 0, "the change is settled either way");
        h.succeed();
    }
}
