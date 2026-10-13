package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.world.CampSanctuary;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** Kits are safe in camp: the golden eagle that carries them off is kept out, and a fall in camp never hurts a kit. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class CampSafetyGameTests {
    private CampSafetyGameTests() {}

    /**
     * The playtest saw Warrior Cats Events' golden eagle lift two kits (and a player) out of the WindClan hollow and
     * drop them: the camp predator tag named it "eagle", an id that does not exist, and an eagle with prey in its
     * talons counted as a mount. It is a camp predator now, even carrying a kit, and lets go of the kit in the camp.
     */
    @GameTest(template = "empty")
    public static void anEagleCarryingAKitIsMovedOutAndTheKitLandsSoftly(GameTestHelper h) {
        h.assertTrue(ModEntities.EAGLE.get().is(CampSanctuary.PREDATORS), "the golden eagle is a camp predator");
        BlockPos at = h.absolutePos(new BlockPos(1, 4, 1));
        Mob eagle = (Mob) ModEntities.EAGLE.get().create(h.getLevel());
        WCatEntity kit = ModEntities.WCAT.get().create(h.getLevel());
        h.assertTrue(eagle != null && kit != null, "an eagle and a cat can be made");
        eagle.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        h.getLevel().addFreshEntity(eagle);
        kit.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        kit.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        kit.setBaby(true);
        kit.setRank(WCatEntity.Rank.KIT);
        h.getLevel().addFreshEntity(kit);
        h.assertTrue(kit.startRiding(eagle, true), "the eagle takes the kit");
        h.assertTrue(CampSanctuary.unwelcome(eagle), "an eagle carrying a kit is still kept out of camps");
        Landmark camp = new Landmark(Landmark.Kind.WINDCLAN_HOLLOW, eagle.getX(), eagle.getZ(), 8, 0, 0);
        h.assertTrue(CampSanctuary.evict(eagle, List.of(camp)), "the eagle was found in the camp");
        h.assertTrue(!kit.isPassenger(), "the eagle let go of the kit");
        h.assertTrue(Math.hypot(kit.getX() - camp.x(), kit.getZ() - camp.z()) <= camp.radius(), "the kit stays in its camp");
        h.assertTrue(CampSanctuary.sparedFall(kit, List.of(camp)), "a kit is not hurt by a fall in a camp");
        h.assertTrue(!CampSanctuary.sparedFall(kit, List.of()), "outside a camp a fall still counts");
        eagle.discard();
        kit.discard();
        h.succeed();
    }
}
