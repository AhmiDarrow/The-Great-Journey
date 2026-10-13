package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** A world's mode is saved with it, and a world without one plays as Free Mode. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class ModeGameTests {
    private ModeGameTests() {}

    @GameTest(template = "empty")
    public static void aWorldKeepsItsModeAcrossASave(GameTestHelper h) {
        var registries = h.getLevel().registryAccess();
        LifeData fresh = new LifeData();
        h.assertTrue(fresh.mode() == null, "a brand-new world has no mode until it is created");
        h.assertTrue(LifeData.fromTag(fresh.save(new CompoundTag(), registries), registries).mode() == null,
                "an undecided mode stays undecided through a save");
        fresh.setMode(Mode.CAMPAIGN);
        LifeData back = LifeData.fromTag(fresh.save(new CompoundTag(), registries), registries);
        h.assertTrue(back.mode() == Mode.CAMPAIGN, "a Campaign world loads as a Campaign world, got " + back.mode());
        back.setMode(Mode.FREE);
        h.assertTrue(LifeData.fromTag(back.save(new CompoundTag(), registries), registries).mode() == Mode.FREE,
                "Free Mode round-trips too");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aWorldWithoutAModeIsFreeMode(GameTestHelper h) {
        h.assertTrue(Mode.of(h.getLevel().getServer()) == Mode.FREE, "the test world, made with no menu choice, is Free Mode");
        h.assertTrue(Mode.pending() == null, "no menu choice is pending on a dedicated or test server");
        h.succeed();
    }
}
