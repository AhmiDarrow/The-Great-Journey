package com.ninjacat.greatjourney.clanlands.life;

import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** What a cat always carries ({@link Keepsakes}): given once, never doubled, kept through a death in their own slots, and never thrown away. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class KeepsakesGameTests {
    private KeepsakesGameTests() {}

    private static ServerPlayer player(GameTestHelper h, String name) {
        return net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
    }

    private static boolean has(ServerPlayer p, String id) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        return p.getInventory().contains(new ItemStack(item));
    }

    @GameTest(template = "empty")
    public static void theKeepsakesAreGivenOnceAndKeptThroughADeath(GameTestHelper h) {
        ServerPlayer p = player(h, "keepsakes_a");
        p.getInventory().clearContent();
        h.assertTrue(Keepsakes.ensure(p) == 4, "the guide, the journal, claws and whiskers are given");
        h.assertTrue(Keepsakes.ensure(p) == 0, "and never doubled");
        h.assertTrue(has(p, "clanlands:journal") && has(p, "warriorcats_events:claws") && has(p, "warriorcats_events:whiskers"),
                "the journal, the claws and the whiskers are in the inventory");
        h.assertTrue(Keepsakes.isKeepsake(Keepsakes.guide()), "StarClan's Guidance is one of them");
        h.assertFalse(Keepsakes.isKeepsake(new ItemStack(Items.WRITABLE_BOOK)), "any other book is not");

        // a death: the claws sit in hotbar slot 3, beside a stack of dirt that will drop as usual
        ServerPlayer q = player(h, "keepsakes_b");
        q.getInventory().clearContent();
        ItemStack claws = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("warriorcats_events:claws")));
        q.getInventory().setItem(3, claws);
        q.getInventory().setItem(4, new ItemStack(Items.DIRT, 12));
        Keepsakes.keep(q);
        h.assertTrue(q.getInventory().getItem(3).isEmpty(), "the claws leave the inventory before anything drops");
        h.assertTrue(q.getInventory().getItem(4).is(Items.DIRT), "everything else is left to drop");
        q.getInventory().clearContent();                               // the respawned cat starts empty
        Keepsakes.restore(q.getUUID(), q);
        h.assertTrue(q.getInventory().getItem(3).is(claws.getItem()), "back in their own slot after the respawn");
        Keepsakes.restore(q.getUUID(), q);
        h.assertTrue(q.getInventory().countItem(claws.getItem()) == 1, "and only once");

        // tossed claws (Q): the stack leaves the slot, then is put back instead of thrown, and nothing doubles them
        q.getInventory().selected = 3;
        ItemStack thrown = q.getInventory().removeFromSelected(true);
        h.assertTrue(Keepsakes.keepBack(q, thrown), "tossed claws are kept back");
        h.assertTrue(q.getInventory().getItem(3).is(claws.getItem()), "back in the paw they were tossed from");
        h.assertTrue(q.getInventory().countItem(claws.getItem()) == 1, "one pair, not two");
        h.assertFalse(Keepsakes.keepBack(q, new ItemStack(Items.DIRT)), "anything else can be thrown");
        h.succeed();
    }
}
