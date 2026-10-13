package com.ninjacat.greatjourney.clanlands.life.rpg;

import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The journal on the server side: builds a player's {@link JournalView} and sends it. The quest system installs
 * the real builder with {@link #provider}; until then the journal shows just the player's name.
 */
public final class Journals {
    private Journals() {}

    /** Registers the dialogue and journal messages and the Clan Journal item. */
    public static void init(net.neoforged.bus.api.IEventBus modBus) {
        com.ninjacat.greatjourney.clanlands.net.ClanNetwork.register(modBus);
        JournalItem.register(modBus);
    }

    private static volatile Function<ServerPlayer, JournalView> provider = p -> JournalView.empty(Joining.catName(p));

    /** Installs the function that builds a player's journal (the quest log sets this). */
    public static void provider(Function<ServerPlayer, JournalView> builder) {
        provider = builder;
    }

    public static JournalView view(ServerPlayer player) {
        try {
            return provider.apply(player);
        } catch (RuntimeException e) {
            return JournalView.empty(player.getGameProfile().getName());
        }
    }

    /** Opens the journal on the player's screen. */
    public static void open(ServerPlayer player) {
        if (player.connection != null && player.connection.hasChannel(ClanPayloads.OpenJournal.TYPE)) {
            PacketDistributor.sendToPlayer(player, new ClanPayloads.OpenJournal(view(player)));
        }
    }

    /** Sends fresh journal contents after quests change; an open journal redraws, a closed one ignores it. */
    public static void push(ServerPlayer player) {
        if (player instanceof net.neoforged.neoforge.common.util.FakePlayer) return;     // automation and tests have no client
        if (player.connection != null && player.connection.hasChannel(ClanPayloads.JournalUpdate.TYPE)) {
            PacketDistributor.sendToPlayer(player, new ClanPayloads.JournalUpdate(view(player)));
        }
    }
}
