package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.life.tour.Tour;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The end of the guided tour puts the player back safely: a hostile mob at the restore point cannot hurt them at once. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class TourRestoreGameTests {
    private TourRestoreGameTests() {}

    @GameTest(template = "empty")
    public static void aMobAtTheRestorePointDoesNotHurtTheRestoredPlayer(GameTestHelper h) {
        net.minecraft.world.entity.player.Player p = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        Zombie z = EntityType.ZOMBIE.create(h.getLevel());
        h.assertTrue(z != null, "a zombie can be made");
        z.moveTo(at.getX() + 1.5, at.getY(), at.getZ() + 0.5);
        z.setTarget(p);
        h.getLevel().addFreshEntity(z);
        Tour.protect(p);
        h.assertTrue(p.hasEffect(MobEffects.DAMAGE_RESISTANCE), "the player is shielded");
        h.assertTrue(z.getTarget() == null, "the zombie forgot the player");
        h.assertTrue(z.distanceTo(p) >= 12, "the zombie was moved out, not left beside the player");
        h.assertTrue(z.isAlive(), "the zombie was not killed");
        float before = p.getHealth();
        p.hurt(h.getLevel().damageSources().mobAttack(z), 8f);
        h.assertTrue(p.getHealth() == before, "a blow in the first seconds does no harm");
        z.discard();
        h.succeed();
    }
}
