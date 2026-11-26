package com.ninjacat.greatjourney.clanlands.life.quests;

import java.util.ArrayList;
import java.util.List;
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
        return !takeStacks(p, key, n).isEmpty() || n <= 0;
    }

    /** As {@link #take}, giving back what was taken; empty (and nothing taken) if the player has too few. */
    static List<ItemStack> takeStacks(ServerPlayer p, String key, int n) {
        List<ItemStack> taken = new ArrayList<>();
        if (count(p, key) < n) return taken;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (matches(s, key)) {
                int t = Math.min(n, s.getCount());
                taken.add(s.split(t));
                n -= t;
            }
        }
        inv.setChanged();
        return taken;
    }

    /** Whether an item key asks for prey ({@link Tasks#ANY_PREY}). */
    static boolean prey(String key) {
        return key.contains(Tasks.PREY_TAG) || key.contains(Tasks.FISH_TAG);
    }

    /**
     * What a task asks for changes paws. Prey goes onto the Clan's fresh-kill pile, where the Clan eats it (and in
     * leaf-bare lives on it); what a full pile cannot hold stays with the player.
     */
    static void handOver(ServerPlayer p, Task.Objective o) {
        List<ItemStack> taken = takeStacks(p, o.key, o.goal);
        if (!prey(o.key)) return;
        for (ItemStack left : com.ninjacat.greatjourney.clanlands.life.events.Hunger.stock(p, taken)) {
            if (!p.getInventory().add(left)) p.drop(left, false);
        }
    }

    public static void give(ServerPlayer p, String id, int n) {
        if (id == null || id.isEmpty() || n <= 0) return;
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == net.minecraft.world.item.Items.AIR) return;
        ItemStack stack = new ItemStack(item, n);
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }
}
