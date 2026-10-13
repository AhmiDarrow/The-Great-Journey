package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.events.Cats;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** Clan events' bookkeeping: leaders' nine lives, StarClan's memory, and warrior names at a ceremony. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class ClanEventsGameTests {
    private ClanEventsGameTests() {}

    @GameTest(template = "empty")
    public static void leadersHaveNineLivesAndStarClanRemembers(GameTestHelper h) {
        LifeData d = LifeData.get(h.getLevel());
        d.setLeader(Clan.THUNDER, "Mossstar");
        h.assertTrue(d.lives(Clan.THUNDER) == LifeData.LEADER_LIVES, "a new leader has nine lives");
        h.assertTrue(d.loseLife(Clan.THUNDER) == 8 && d.loseLife(Clan.THUNDER) == 7, "each lost life counts down");
        d.rememberInStarClan("Fernheart", Clan.RIVER, "old age", 1234L);
        CompoundTag saved = d.save(new CompoundTag(), h.getLevel().registryAccess());
        LifeData back = LifeData.fromTag(saved, h.getLevel().registryAccess());
        h.assertTrue(back.lives(Clan.THUNDER) == 7, "lives survive a save, got " + back.lives(Clan.THUNDER));
        h.assertTrue(back.leader(Clan.THUNDER).equals("Mossstar"), "the leader's name survives a save");
        h.assertTrue(back.starClan().stream().anyMatch(f -> f.name().equals("Fernheart") && f.clan() == Clan.RIVER && f.cause().equals("old age")),
                "StarClan remembers the fallen across a save");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void anApprenticeKeepsItsPrefixAsAWarrior(GameTestHelper h) {
        WCatEntity cat = ModEntities.WCAT.get().create(h.getLevel());
        h.assertTrue(cat != null, "a Warrior Cats Events cat can be made");
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        cat.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setGender(1);
        Cats.rename(cat, "Fern", "Fernpaw");
        cat.setRank(WCatEntity.Rank.APPRENTICE);
        h.getLevel().addFreshEntity(cat);
        h.assertTrue(Cats.plainName(cat).equals("Fernpaw"), "the gender sign is not part of the name, got " + Cats.plainName(cat));
        h.assertTrue(Cats.prefixOf(cat).equals("Fern"), "the prefix is kept, got " + Cats.prefixOf(cat));
        Set<String> used = Set.of("fernheart");
        String name = Cats.warriorName(Cats.prefixOf(cat), RandomSource.create(7), used);
        h.assertTrue(name.startsWith("Fern") && !name.endsWith("paw") && !used.contains(name.toLowerCase()),
                "a warrior name keeps the prefix and is new to the Clan, got " + name);
        Cats.rename(cat, "Fern", name);
        cat.setRank(WCatEntity.Rank.WARRIOR);
        h.assertTrue(cat.getCustomName() != null && cat.getCustomName().getString().startsWith(name), "the cat carries its new name");
        h.assertTrue(cat.getRank() == WCatEntity.Rank.WARRIOR, "the cat is a warrior now");
        cat.discard();
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void neighboursFollowTheLake(GameTestHelper h) {
        h.assertTrue(Cats.neighbours(Clan.THUNDER).containsAll(java.util.List.of(Clan.WIND, Clan.SHADOW)), "ThunderClan borders WindClan and ShadowClan");
        h.assertTrue(Cats.neighbours(Clan.RIVER).containsAll(java.util.List.of(Clan.WIND, Clan.SHADOW)), "RiverClan borders WindClan and ShadowClan");
        h.assertTrue(Cats.direction(0, 0, 0, -10).equals("north") && Cats.direction(0, 0, 10, 10).equals("south-east"), "compass hints");
        h.assertTrue(Component.literal("x") != null, "components available");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void generatedCatsNeverGetBookNames(GameTestHelper h) {
        for (long seed = 0; seed < 40; seed++) {
            var names = new com.ninjacat.greatjourney.clanlands.life.ClanNames(seed * 7919L + 3);
            for (int i = 0; i < 150; i++) {
                for (var n : java.util.List.of(names.leader(), names.warrior(), names.apprentice(), names.kit())) {
                    String full = n.full().toLowerCase(java.util.Locale.ROOT);
                    h.assertTrue(!com.ninjacat.greatjourney.clanlands.life.ClanNames.BOOK_NAMES.contains(full)
                            && !com.ninjacat.greatjourney.clanlands.life.ClanNames.BOOK_NAMES.contains(n.prefix().toLowerCase(java.util.Locale.ROOT) + "star")
                            && !com.ninjacat.greatjourney.clanlands.life.ClanNames.BOOK_NAMES.contains(n.prefix().toLowerCase(java.util.Locale.ROOT) + "paw"),
                            "a generated cat took a book name: " + n.full());
                }
            }
        }
        h.succeed();
    }
}
