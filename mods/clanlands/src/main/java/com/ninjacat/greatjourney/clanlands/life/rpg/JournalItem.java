package com.ninjacat.greatjourney.clanlands.life.rpg;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Clan Journal: using it opens the journal (tasks, duties, Clan, places, StarClan). The server builds the view
 * and sends it, so the item itself carries no data and works the same in multiplayer.
 */
public final class JournalItem extends Item {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("clanlands");
    public static final DeferredItem<JournalItem> JOURNAL = ITEMS.register("journal", () -> new JournalItem(new Item.Properties().stacksTo(1)));

    public JournalItem(Properties properties) {
        super(properties);
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(JOURNAL.get());
        });
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp) Journals.open(sp);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
}
