package com.ninjacat.greatjourney.clanlands.life.quests;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Counting and handing over what the player carries. An item key is an item id, a tag ("#namespace:tag") or several
 * of either separated by "|".
 */
public final class Bag {
    private Bag() {}

    static boolean matches(ItemStack stack, String key) {
        if (stack.isEmpty()) return false;
        for (String k : key.split("\\|")) {
            if (k.startsWith("#")) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(k.substring(1)));
                if (stack.is(tag)) return true;
            } else if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(k)) {
                return true;
            }
        }
        return false;
    }

    public static int count(ServerPlayer p, String key) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (matches(s, key)) n += s.getCount();
        }
        return n;
    }

    /** Takes {@code n} matching items from the player; false (and nothing taken) if they have too few. */
    public static boolean take(ServerPlayer p, String key, int n) {
        if (count(p, key) < n) return false;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (matches(s, key)) {
                int t = Math.min(n, s.getCount());
                s.shrink(t);
                n -= t;
            }
        }
        inv.setChanged();
        return true;
    }

    public static void give(ServerPlayer p, String id, int n) {
        if (id == null || id.isEmpty() || n <= 0) return;
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == net.minecraft.world.item.Items.AIR) return;
        ItemStack stack = new ItemStack(item, n);
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }
}
