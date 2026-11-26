package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.snowteb.warriorcats_events.attachments.ModAttachments;

/**
 * Who hears about a Clan event: every online player of that Clan (wherever they are), plus anyone close to where
 * it happens. Each listener gets the lines through their own dialog queue, so several players can follow along.
 */
final class Announce {
    private Announce() {}

    static List<ServerPlayer> audience(ServerLevel level, Clan c, BlockPos where) {
        UUID id = LifeData.get(level).clanId(c);
        Set<ServerPlayer> out = new LinkedHashSet<>();
        int radius = EventsConfig.ANNOUNCE_RADIUS.get();
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            UUID mine = p.getData(ModAttachments.PLAYER_WCE_DATA.get()).getCurrentClanUUID();
            boolean member = id != null && id.equals(mine);
            boolean near = where != null && p.level() == level && p.blockPosition().distSqr(where) <= (double) radius * radius;
            if (member || near) out.add(p);
        }
        return new ArrayList<>(out);
    }

    /** Within this many blocks a player is a witness and sees the scene in full; others hear it as news. */
    private static final int WITNESS = 24;

    /**
     * Witnesses get the full dialogue window; Clanmates elsewhere get the same lines as a passing subtitle, so news
     * from camp never interrupts what they are doing.
     */
    static void tell(ServerLevel level, Clan c, BlockPos where, List<Line> lines) {
        if (!lines.isEmpty()) ClanEvents.noticed(LifeData.get(level).clanId(c), where, lines.get(0).text(), level.getServer().overworld().getGameTime());
        for (ServerPlayer p : audience(level, c, where)) {
            boolean witness = where != null && p.level() == level && p.blockPosition().distSqr(where) <= WITNESS * WITNESS;
            if (witness) Dialog.play(p, lines);
            else Dialog.subtitle(p, lines);
        }
    }

    static void tell(ServerLevel level, Clan c, BlockPos where, Line... lines) {
        tell(level, c, where, List.of(lines));
    }

    /** A scene only for those there to see it (an elder's story): played to witnesses, and no news to anyone else. */
    static void witnesses(ServerLevel level, BlockPos where, List<Line> lines) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(where) <= WITNESS * WITNESS) Dialog.play(p, lines);
        }
    }
}
