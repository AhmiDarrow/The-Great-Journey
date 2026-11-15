package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Apprentices are shared out (2026-11-15: a dead mentor's apprentice was given a warrior who already had one while
 * others had none): a free warrior is chosen over a nearer one who teaches, whether its apprentice is a Clan cat or a
 * player; a cat teaches a second apprentice only when every fit cat already teaches.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class MentorsGameTests {
    private MentorsGameTests() {}

    private static WCatEntity cat(GameTestHelper h, String name, WCatEntity.Rank rank, BlockPos rel) {
        WCatEntity cat = ModEntities.WCAT.get().create(h.getLevel());
        h.assertTrue(cat != null, "a Warrior Cats Events cat can be made");
        BlockPos at = h.absolutePos(rel);
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cat.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setCustomName(Component.literal(name));
        cat.setRank(rank);
        cat.setNoAi(true);
        h.getLevel().addFreshEntity(cat);
        return cat;
    }

    @GameTest(template = "empty")
    public static void aNewApprenticeGetsAFreeWarriorFirst(GameTestHelper h) {
        List<WCatEntity> made = new ArrayList<>();
        try {
            BlockPos here = new BlockPos(1, 2, 1);
            double x = h.absolutePos(here).getX(), z = h.absolutePos(here).getZ();
            WCatEntity near = cat(h, "Nearclaw", WCatEntity.Rank.WARRIOR, new BlockPos(2, 2, 1));
            WCatEntity middle = cat(h, "Midfur", WCatEntity.Rank.WARRIOR, new BlockPos(5, 2, 1));
            WCatEntity far = cat(h, "Farstripe", WCatEntity.Rank.WARRIOR, new BlockPos(9, 2, 1));
            WCatEntity paw = cat(h, "Pebblepaw", WCatEntity.Rank.APPRENTICE, new BlockPos(1, 2, 5));
            made.addAll(List.of(near, middle, far, paw));
            List<WCatEntity> fit = List.of(near, middle, far);

            h.assertTrue(Mentors.choose(fit, x, z) == near, "with every warrior free, the nearest teaches");

            Roles.link(near, paw);                                              // the nearest teaches a Clan apprentice
            h.assertTrue(Mentors.busy(near) && !Mentors.busy(middle), "a warrior with a Clan apprentice is busy");
            h.assertTrue(Mentors.choose(fit, x, z) == middle, "a free warrior is chosen over a nearer one who teaches");

            // the middle one teaches a player who is not online: still busy, so the far one is chosen
            middle.getPersistentData().putUUID(Mentors.KEY, UUID.randomUUID());
            h.assertTrue(Mentors.mentorsPlayer(middle), "a cat teaching an offline player stays their mentor");
            h.assertTrue(Mentors.choose(fit, x, z) == far, "the only free warrior teaches, though furthest");

            far.getPersistentData().putUUID(Mentors.KEY, UUID.randomUUID());
            h.assertTrue(Mentors.choose(fit, x, z) == near, "when every warrior teaches, the nearest takes a second apprentice");
            h.assertTrue(Mentors.choose(List.of(), x, z) == null, "no fit cat, no mentor");
        } finally {
            made.forEach(WCatEntity::discard);
        }
        h.succeed();
    }
}
