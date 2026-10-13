package com.ninjacat.greatjourney.clanlands.life.campaign;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.life.ChunkWait;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Campaign's beginning: a new cat is offered the three starts, each start lands on dry ground in the old
 * forest, kittypets and loners are found by patrols only once they wander into a Clan's land, and act 1 begins.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class CampaignStartGameTests {
    private CampaignStartGameTests() {}

    private static ServerPlayer player(GameTestHelper h, String name) {
        return net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
    }

    @GameTest(template = "empty")
    public static void aCampaignCatIsOfferedThreeStartsAndActOneBegins(GameTestHelper h) {
        ServerPlayer p = player(h, "campaign_start_a");
        Dialog.Line line = Campaign.startLine(p, "How will your story begin?");
        List<String> commands = line.choices().stream().map(Dialog.Choice::command).toList();
        h.assertTrue(commands.equals(List.of("greatjourney start kittypet", "greatjourney start clan", "greatjourney start loner")),
                "the same three starts as Free Mode, got " + commands);
        h.assertTrue(line.choices().get(0).label().contains("forest") && line.choices().get(2).label().contains("farm"),
                "in the old forest: a kittypet at its edge, a loner by the farm");
        CampaignData fresh = CampaignData.fromTag(new CompoundTag(), h.getLevel().registryAccess());
        h.assertTrue(fresh.act() == 1, "a new Campaign world is in act 1");
        h.assertTrue(Chapter.startFor(1, false, false, false) == Chapter.APPRENTICE && Chapter.APPRENTICE.act == 1,
                "a cat who joins a Clan begins act 1 as an apprentice");
        h.assertTrue(Chapter.OUTSIDER.act == 1 && Chapter.catchUp(Chapter.OUTSIDER, 3, true, true, true, false) == Chapter.OUTSIDER,
                "a kittypet or loner waits in act 1 for a patrol, whatever the world's act");
        h.assertTrue(Chapter.parse("OUTSIDER") == Chapter.OUTSIDER, "the outsider's chapter reads back from a save");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyLeaderLineAtTheGatheringNamesItsCat(GameTestHelper h) {
        ServerPlayer p = player(h, "campaign_start_gathering");
        var life = com.ninjacat.greatjourney.clanlands.life.LifeData.get(h.getLevel().getServer().overworld());
        java.util.Map<Clan, net.minecraft.world.entity.Entity> leaders = new java.util.EnumMap<>(Clan.class);
        for (Clan c : Clan.values()) leaders.put(c, h.spawn(net.minecraft.world.entity.EntityType.PIG, 1 + c.ordinal(), 2, 1));
        for (Clan host : Clan.values()) {
            List<Dialog.Line> lines = Campaign.gatheringLines(p, life, leaders, host);
            int spoken = 0;
            for (Dialog.Line l : lines) {
                if (l.speaker() == null) continue;
                spoken++;
                Clan who = null;
                for (Clan c : Clan.values()) if (life.leader(c).equals(l.speaker())) who = c;
                h.assertTrue(who != null, "the Gathering's speaker " + l.speaker() + " is a Clan's leader");
                h.assertTrue(l.entityId() == leaders.get(who).getId(), l.speaker() + "'s line shows their own cat, not the medallion");
            }
            h.assertTrue(spoken == Clan.values().length + 1, "four leaders speak, and the host welcomes the stranger, got " + spoken);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theJoiningCeremonyLinesNameTheirCats(GameTestHelper h) {
        var leader = h.spawn(net.minecraft.world.entity.EntityType.PIG, 1, 2, 1);
        var mentor = h.spawn(net.minecraft.world.entity.EntityType.PIG, 2, 2, 1);
        var info = com.ninjacat.greatjourney.clanlands.life.BookClans.Info.THUNDER;
        List<Dialog.Line> lines = com.ninjacat.greatjourney.clanlands.life.Joining.ceremonyLines("Pip", info, "Bluestar", leader, "Mousefur", mentor);
        int spoken = 0;
        for (Dialog.Line l : lines) {
            if (l.speaker() == null) continue;
            spoken++;
            int want = l.speaker().equals("Bluestar") ? leader.getId() : mentor.getId();
            h.assertTrue(l.entityId() == want, l.speaker() + "'s ceremony line shows their own cat, not the medallion");
        }
        h.assertTrue(spoken == 4, "the leader speaks three times and the mentor once, got " + spoken);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void eachStartLandsInTheOldForestOnDryGround(GameTestHelper h) {
        for (long seed : new long[] {1, 20261011, -77, 424242, 9001, 123456789}) {
            ClanWorld w = ClanWorld.layoutOnly(seed);
            Landmark home = w.forest.kittypetHome();
            h.assertTrue(home != null && home.kind() == Landmark.Kind.KITTYPET_HOME && w.forest.landmarks().contains(home),
                    "the old forest has a kittypet's garden to build, seed " + seed);
            dry(h, w, home.x() + 2, home.z() + 7, "the kittypet start", seed);
            h.assertTrue(w.forest.thunderpath().distance(home.x(), home.z(), 60) > 30, "the garden is clear of the Thunderpath, seed " + seed);
            boolean byThunderClan = false;
            double dx = w.forest.centreX() - home.x(), dz = w.forest.centreZ() - home.z(), len = Math.hypot(dx, dz);
            for (double s = 0; s < 220 && !byThunderClan; s += 10) {
                byThunderClan = Homeland.forestTerritoryAt(w, home.x() + dx / len * s, home.z() + dz / len * s) == Clan.THUNDER;
            }
            h.assertTrue(byThunderClan, "the garden backs onto ThunderClan's woods, seed " + seed);

            double[] loner = w.forest.lonerSpot();
            dry(h, w, loner[0], loner[1], "the loner start", seed);
            h.assertTrue(Homeland.forestTerritoryAt(w, loner[0], loner[1]) == null, "a loner begins outside every Clan's land, seed " + seed);
            for (Clan c : Clan.values()) {
                Landmark camp = Homeland.forestCamp(w, c);
                h.assertTrue(w.forest.covers(camp.x(), camp.z()), "a Clan start goes to " + c + "'s forest camp, seed " + seed);
            }
        }
        h.succeed();
    }

    private static void dry(GameTestHelper h, ClanWorld w, double x, double z, String what, long seed) {
        Sample s = w.forest.sample(x, z);
        h.assertTrue(w.forest.covers(x, z) && s.weight() > 0.5, what + " is in the old forest's land, seed " + seed);
        h.assertTrue(s.height() >= 64 && s.zone() != Zone.RIVER && s.zone() != Zone.LAKE,
                what + " is on dry ground above the water, got " + s.zone() + " at " + s.height() + ", seed " + seed);
    }

    @GameTest(template = "empty")
    public static void aKittypetIsFoundOnlyInClanLand(GameTestHelper h) {
        ServerPlayer p = player(h, "campaign_start_b");
        boolean wasForest = Homeland.forest();
        Homeland.setForest(true);
        try {
            ClanWorld w = ClanWorld.layoutOnly(20261011);
            Landmark home = w.forest.kittypetHome();
            CompoundTag d = new CompoundTag();
            d.putDouble("startX", home.x() + 2);
            d.putDouble("startZ", home.z() + 7);
            p.setPos(home.x() + 2, 100, home.z() + 7);
            h.assertTrue(Outsiders.patrolFor(p, w, d, 1000) == null, "no patrol comes to the garden itself");
            double dx = w.forest.centreX() - home.x(), dz = w.forest.centreZ() - home.z(), len = Math.hypot(dx, dz);
            double[] woods = null;
            for (double s = Outsiders.WANDER + 10; s < 600 && woods == null; s += 10) {
                double x = home.x() + dx / len * s, z = home.z() + dz / len * s;
                if (Homeland.territoryAt(w, x, z) == Clan.THUNDER) woods = new double[] {x, z};
            }
            h.assertTrue(woods != null, "ThunderClan's woods lie beyond the garden");
            p.setPos(woods[0], 100, woods[1]);
            h.assertTrue(Outsiders.patrolFor(p, w, d, 1000) == Clan.THUNDER, "a ThunderClan patrol finds the kittypet in its woods");
            h.assertTrue(Outsiders.patrolFor(p, w, d, 1200) == null, "and does not ask again the same day");
            h.assertTrue(Outsiders.patrolFor(p, w, d, 1000 + Outsiders.AGAIN + 1) == Clan.THUNDER, "but asks again another day");
            h.succeed();
        } finally {
            Homeland.setForest(wasForest);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void farLandLoadsWithoutStallingTheServer(GameTestHelper h) {
        AtomicBoolean ran = new AtomicBoolean();
        var far = h.absolutePos(new net.minecraft.core.BlockPos(0, 0, 0)).offset(4000, 0, 4000);
        ChunkWait.whenLoaded(h.getLevel(), far.getX(), far.getZ(), 16, () -> ran.set(true));
        h.assertTrue(!ran.get(), "land not yet loaded is waited for, not generated mid-tick");
        h.succeedWhen(() -> h.assertTrue(ran.get(), "the job runs once the land has loaded"));
    }
}
