package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * What a cat always carries once their story has begun: StarClan's Guidance, the Clan journal, their claws and their
 * whiskers. They are given when the story begins and stay with the player from then on. A death keeps each one in its
 * own slot (it never drops, so there is nothing to lose and nothing to pick up again on the way back), and one that
 * goes missing anyway (lost over a restart while its owner lay dead) is back in the player's inventory within a few
 * seconds. Kiki lost her books and claws to a death and had no leather to make new claws.
 *
 * <p>Because a missing one comes back, none may leave the player any other way: a tossed one stays in the inventory,
 * and one put in a chest (or any other container) comes back out when the screen closes. Tossed claws used to lie on
 * the ground while new ones appeared in the inventory, two pairs for one (playtest 2026-10-19).
 */
public final class Keepsakes {
    private Keepsakes() {}

    /** One thing a cat always carries: how to recognise it, and how to make a new one. */
    private record Keepsake(String what, Predicate<ItemStack> is, java.util.function.Supplier<ItemStack> make) {}

    private static final ResourceLocation GUIDE_BOOK = ResourceLocation.fromNamespaceAndPath("clanlands", "starclans_guidance");

    private static final List<Keepsake> ALL = List.of(
            new Keepsake("StarClan's Guidance", Keepsakes::isGuide, Keepsakes::guide),
            new Keepsake("journal", s -> s.is(item("clanlands", "journal")), () -> new ItemStack(item("clanlands", "journal"))),
            new Keepsake("claws", s -> s.is(item("warriorcats_events", "claws")), () -> new ItemStack(item("warriorcats_events", "claws"))),
            new Keepsake("whiskers", s -> s.is(item("warriorcats_events", "whiskers")), () -> new ItemStack(item("warriorcats_events", "whiskers"))));

    /** A dead player's keepsakes, each with the slot it was in, until they respawn. */
    private record Kept(int slot, ItemStack stack) {}

    private static final Map<UUID, List<Kept>> KEPT = new HashMap<>();

    /** How often a player's keepsakes are counted, in ticks. */
    static final int CHECK_EVERY = 40;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, Keepsakes::onDeath);
        NeoForge.EVENT_BUS.addListener(Keepsakes::onClone);
        NeoForge.EVENT_BUS.addListener(Keepsakes::onTick);
        NeoForge.EVENT_BUS.addListener(Keepsakes::onToss);
        NeoForge.EVENT_BUS.addListener(Keepsakes::onClose);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> KEPT.clear());
    }

    /** Whether a stack is one of the things a cat always carries. */
    public static boolean isKeepsake(ItemStack s) {
        if (s.isEmpty()) return false;
        for (Keepsake k : ALL) if (k.is().test(s)) return true;
        return false;
    }

    /** Whether the player's story has begun, so they carry their keepsakes from now on. */
    static boolean owed(ServerPlayer p) {
        return !(p instanceof FakePlayer) && Journey.stage(p) == Journey.Stage.PLAYING;
    }

    /**
     * Gives the player whichever keepsakes they do not have. The first time, they are told these stay with them.
     * Returns how many were given.
     */
    public static int ensure(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || KEPT.containsKey(p.getUUID())) return 0;     // dead: they come back on respawn
        int given = 0;
        for (Keepsake k : ALL) {
            if (has(p, k.is())) continue;
            ItemStack s = k.make().get();
            if (s.isEmpty()) continue;                                         // its mod is missing
            // a full inventory waits for a free slot: dropping it would drop another every check, a pile on the ground
            if (!p.getInventory().add(s)) continue;
            given++;
        }
        if (given > 0 && !(p instanceof FakePlayer) && Journey.once(p, "keepsakes.told")) {
            Dialog.subtitle(p, Line.narrate("StarClan's Guidance, your journal, your claws and your whiskers stay with you always, even in death."));
        }
        return given;
    }

    /**
     * Whether the player has it: in the inventory, on the cursor, or in any slot of the screen they have open (a
     * crafting grid, an anvil). Only the inventory and the cursor were looked at first, so one left a moment in the
     * crafting grid was given again, and taking it out made two.
     */
    private static boolean has(ServerPlayer p, Predicate<ItemStack> is) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (is.test(inv.getItem(i))) return true;
        if (is.test(p.containerMenu.getCarried()) || is.test(p.inventoryMenu.getCarried())) return true;
        for (var slot : p.containerMenu.slots) if (is.test(slot.getItem())) return true;
        for (var slot : p.inventoryMenu.slots) if (is.test(slot.getItem())) return true;
        return false;
    }

    /** Last of all, once the death is certain: the keepsakes leave the inventory before anything drops. */
    private static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        if (p.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;     // everything stays anyway
        keep(p);
    }

    /** Takes the player's keepsakes out of their inventory and holds them, each with its slot, until they respawn. */
    static void keep(ServerPlayer p) {
        Inventory inv = p.getInventory();
        List<Kept> kept = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!isKeepsake(s)) continue;
            kept.add(new Kept(i, s.copy()));
            inv.setItem(i, ItemStack.EMPTY);
        }
        // one on the cursor or in the crafting grid would drop when the screen closes on the dead player
        for (var menu : List.of(p.containerMenu, p.inventoryMenu)) {
            if (isKeepsake(menu.getCarried())) {
                kept.add(new Kept(-1, menu.getCarried().copy()));
                menu.setCarried(ItemStack.EMPTY);
            }
        }
        var grid = p.inventoryMenu.getCraftSlots();
        for (int i = 0; i < grid.getContainerSize(); i++) {
            if (!isKeepsake(grid.getItem(i))) continue;
            kept.add(new Kept(-1, grid.getItem(i).copy()));
            grid.setItem(i, ItemStack.EMPTY);
        }
        if (!kept.isEmpty()) KEPT.put(p.getUUID(), kept);
    }

    /** The respawned cat has them back, each in the slot it was in. */
    private static void onClone(PlayerEvent.Clone e) {
        if (e.isWasDeath() && e.getEntity() instanceof ServerPlayer p) restore(e.getOriginal().getUUID(), p);
    }

    /** Puts back what {@link #keep} held for {@code was} into {@code p}'s inventory (the same slots where they are free). */
    static void restore(UUID was, ServerPlayer p) {
        List<Kept> kept = KEPT.remove(was);
        if (kept == null) return;
        Inventory inv = p.getInventory();
        for (Kept k : kept) {
            if (k.slot() >= 0 && k.slot() < inv.getContainerSize() && inv.getItem(k.slot()).isEmpty()) inv.setItem(k.slot(), k.stack());
            else if (!inv.add(k.stack())) p.drop(k.stack(), false);
        }
    }

    /** A keepsake tossed out (Q, or dropped outside the inventory screen) is not thrown: it stays with the cat. */
    private static void onToss(ItemTossEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p && owed(p) && keepBack(p, e.getEntity().getItem())) {
            e.setCanceled(true);                                              // the item entity is never added to the world
        }
    }

    /** If {@code s} is a keepsake on its way out of the player's paws, puts it back (the hand first) and returns true. */
    static boolean keepBack(ServerPlayer p, ItemStack s) {
        if (!isKeepsake(s)) return false;
        Inventory inv = p.getInventory();
        ItemStack back = s.copy();
        if (inv.getSelected().isEmpty()) inv.setItem(inv.selected, back);
        else if (!inv.add(back)) return true;                                 // no room: ensure gives it back once there is
        p.inventoryMenu.broadcastChanges();
        if (!(p instanceof FakePlayer) && Journey.once(p, "keepsakes.toss")) {
            Dialog.subtitle(p, Line.narrate("You keep hold of your " + what(back) + ". What a cat always carries stays with them."));
        }
        return true;
    }

    /**
     * A screen closing: a keepsake left in the container's own slots (a chest, a barrel) comes back to the player, or
     * the tick would make a new one and leave the old one in the chest. Crafting grids have handed theirs back already.
     */
    private static void onClose(PlayerContainerEvent.Close e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !owed(p)) return;
        Inventory inv = p.getInventory();
        for (var slot : e.getContainer().slots) {
            if (slot.container == inv || !isKeepsake(slot.getItem())) continue;
            ItemStack s = slot.getItem().copy();
            if (!inv.add(s)) continue;                                        // a full inventory: it stays where it was put
            slot.set(ItemStack.EMPTY);
            slot.setChanged();
        }
    }

    /** What a keepsake is called, for a line to the player. */
    private static String what(ItemStack s) {
        for (Keepsake k : ALL) if (k.is().test(s)) return k.what();
        return s.getHoverName().getString();
    }

    private static void onTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || (p.tickCount + p.getId()) % CHECK_EVERY != 0) return;
        if (p.isAlive() && KEPT.containsKey(p.getUUID())) restore(p.getUUID(), p);    // a death undone after we took them
        if (owed(p)) ensure(p);
    }

    private static boolean isGuide(ItemStack s) {
        if (!s.is(item("patchouli", "guide_book"))) return s.is(Items.BOOK) && s.has(DataComponents.CUSTOM_NAME)
                && "StarClan's Guidance".equals(s.getHoverName().getString());          // the stand-in without Patchouli
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse("patchouli:book"));
        return type != null && GUIDE_BOOK.equals(s.get(type));
    }

    /** StarClan's Guidance: the pack's Patchouli book (a plain named book without Patchouli). */
    static ItemStack guide() {
        Item book = item("patchouli", "guide_book");
        ItemStack s = new ItemStack(book == Items.AIR ? Items.BOOK : book);
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse("patchouli:book"));
        if (type != null && book != Items.AIR) set(s, type, GUIDE_BOOK);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("StarClan's Guidance"));
        return s;
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(ItemStack stack, DataComponentType<T> type, Object value) {
        stack.set(type, (T) value);
    }

    private static Item item(String ns, String path) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(ns, path));
    }
}
