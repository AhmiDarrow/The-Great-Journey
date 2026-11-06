package com.ninjacat.greatjourney.clanlore;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where a pack built on Clan Lore plugs in. Without anything installed, Clan Lore tells its news in chat to the Clan's
 * players and to anyone nearby; The Great Journey installs its own dialogue window instead.
 */
public final class LoreHooks {
    private LoreHooks() {}

    /** One line of a scene: who says it (null for narration), the colour of their name, and the words. */
    public record Speech(String speaker, int colour, String text, UUID speakerEntity) {
        public static Speech say(String speaker, int colour, String text) {
            return new Speech(speaker, colour, text, null);
        }

        public static Speech narrate(String text) {
            return new Speech(null, 0xAAAAAA, text, null);
        }
    }

    /** Tells a Clan (its players anywhere, and anyone near {@code where}) a scene. */
    @FunctionalInterface
    public interface Announcer {
        void tell(ServerLevel level, UUID clanId, BlockPos where, List<Speech> lines);
    }

    private static volatile Announcer announcer = LoreHooks::chat;

    public static void announcer(Announcer a) {
        announcer = a == null ? LoreHooks::chat : a;
    }

    public static void tell(ServerLevel level, UUID clanId, BlockPos where, List<Speech> lines) {
        if (!lines.isEmpty()) announcer.tell(level, clanId, where, lines);
    }

    /** The plain fallback: chat lines to the Clan's online players and to players within 48 blocks. */
    static void chat(ServerLevel level, UUID clanId, BlockPos where, List<Speech> lines) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            UUID mine = p.getData(net.snowteb.warriorcats_events.attachments.ModAttachments.PLAYER_WCE_DATA.get()).getCurrentClanUUID();
            boolean member = clanId != null && clanId.equals(mine);
            boolean near = where != null && p.level() == level && p.blockPosition().distSqr(where) <= 48 * 48;
            if (!member && !near) continue;
            for (Speech s : lines) {
                p.sendSystemMessage(s.speaker() == null ? Component.literal(s.text()).withStyle(net.minecraft.ChatFormatting.ITALIC, net.minecraft.ChatFormatting.GRAY)
                        : Component.literal(s.speaker() + ": ").withColor(s.colour()).append(Component.literal(s.text()).withStyle(net.minecraft.ChatFormatting.WHITE)));
            }
        }
    }
}
