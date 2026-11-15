package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Who is already a mentor, so apprentices are shared out: a cat that teaches one of the Clan's own apprentices (Clan
 * Lore's pairing) or a player is busy, and a new apprentice (a player joining, a player whose mentor died, a Clan
 * apprentice at its ceremony) gets a free warrior when the Clan has one. Only when every fit cat already teaches does
 * one take a second apprentice. Asked for after play (2026-11-15): a dead mentor's apprentice was given a warrior who
 * already had one, while other warriors had none.
 *
 * <p>A player's mentor carries the player's UUID in its persistent data ({@link #KEY}). The mark is checked against
 * the player whenever they are online (their mentor is still this cat and they are not yet a warrior), and dropped
 * when it no longer holds, so a mark left on a cat that was not loaded when its pupil moved on never sticks.
 */
public final class Mentors {
    private Mentors() {}

    static final String KEY = "greatjourney_mentors_player";

    /** Whether this cat teaches a player now. */
    public static boolean mentorsPlayer(WCatEntity cat) {
        CompoundTag d = cat.getPersistentData();
        if (!d.hasUUID(KEY)) return false;
        MinecraftServer server = cat.level().getServer();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(d.getUUID(KEY));
        if (p == null) return true;                      // an offline player keeps their mentor for when they come back
        boolean still = cat.getUUID().equals(QuestState.mentorId(p)) && QuestState.storyStep(p) < QuestState.WARRIOR;
        if (!still) d.remove(KEY);
        return still;
    }

    /** Whether this cat already has an apprentice: one of the Clan's cats, or a player. */
    public static boolean busy(WCatEntity cat) {
        return Roles.apprenticeOf(cat) != null || mentorsPlayer(cat);
    }

    /**
     * The mentor for a new apprentice from {@code fit} (the cats who could teach them): a free one if there is any,
     * the nearest to {@code x}, {@code z} first; else the nearest busy one. Null for none.
     */
    public static WCatEntity choose(List<WCatEntity> fit, double x, double z) {
        Comparator<WCatEntity> near = Comparator.comparingDouble(c -> c.distanceToSqr(x, c.getY(), z));
        return fit.stream().sorted(Comparator.comparing(Mentors::busy).thenComparing(near)).findFirst().orElse(null);
    }

    /** Moves the player's mark from their old mentor (if loaded) to the new one (if loaded; otherwise the next check). */
    static void mark(ServerPlayer p, UUID oldMentor, UUID newMentor) {
        ServerLevel ow = p.getServer().overworld();
        if (oldMentor != null && !oldMentor.equals(newMentor) && ow.getEntity(oldMentor) instanceof WCatEntity old
                && old.getPersistentData().hasUUID(KEY) && p.getUUID().equals(old.getPersistentData().getUUID(KEY))) {
            old.getPersistentData().remove(KEY);
        }
        if (newMentor != null && ow.getEntity(newMentor) instanceof WCatEntity cat) cat.getPersistentData().putUUID(KEY, p.getUUID());
    }

    /** The player's mentor, if loaded, carries the mark (a mentor chosen while unloaded, or before marks existed). */
    static void keepMark(ServerPlayer p) {
        UUID id = QuestState.mentorId(p);
        if (id == null || QuestState.storyStep(p) >= QuestState.WARRIOR) return;
        if (p.getServer().overworld().getEntity(id) instanceof WCatEntity cat && !cat.getPersistentData().hasUUID(KEY)) {
            cat.getPersistentData().putUUID(KEY, p.getUUID());
        }
    }
}
