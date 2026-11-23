package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.life.Lead;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.snowteb.warriorcats_events.clan.ClanData;
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

    /** Why a mentor can teach their apprentice no longer. */
    enum Loss { DIED, RETIRED, GONE }

    /**
     * Whether this cat can teach an apprentice now: alive and grown, about its own business (not brought in to guide
     * someone or standing in a scene), and of the right place in the Clan. A warrior's apprentice learns from a
     * warrior, or from the deputy or the leader as in the books; a medicine cat's only from a medicine cat.
     */
    public static boolean fit(WCatEntity cat, LifeData life, boolean healer) {
        if (!cat.isAlive() || cat.isBaby() || cat.isNoAi() || cat.getCustomName() == null) return false;
        if (cat.getTags().contains(Lead.GUIDE_TAG) || cat.getTags().contains("greatjourney_gathering")
                || cat.getTags().contains("greatjourney_escort")
                || cat.getTags().contains(com.ninjacat.greatjourney.clanlands.life.TunnelKeeper.TAG)) return false;
        Kin.Role r = Kin.role(cat, life);
        return healer ? r == Kin.Role.MEDICINE : r == Kin.Role.WARRIOR || r == Kin.Role.DEPUTY || r == Kin.Role.LEADER;
    }

    /**
     * The cats in or near camp who could take a new apprentice: the warriors (the medicine cats, for a medicine cat's
     * apprentice); in a Clan with no warrior in camp, the deputy; with no deputy either, the leader.
     */
    static List<WCatEntity> candidates(ServerLevel level, UUID clanId, Landmark camp, LifeData life, boolean healer) {
        List<WCatEntity> near = Kin.catsNear(level, clanId, camp.x(), camp.z(), camp.radius() + 60);
        for (Kin.Role tier : healer ? new Kin.Role[] {Kin.Role.MEDICINE} : new Kin.Role[] {Kin.Role.WARRIOR, Kin.Role.DEPUTY, Kin.Role.LEADER}) {
            List<WCatEntity> out = new ArrayList<>();
            for (WCatEntity c : near) if (fit(c, life, healer) && Kin.role(c, life) == tier) out.add(c);
            if (!out.isEmpty()) return out;
        }
        return new ArrayList<>();
    }

    /**
     * Why the player's mentor can teach them no longer, or null while it still can. The mentor is known by its own id,
     * not only its name: one that is loaded is looked at (dead, out of the Clan, retired to the elders' den); one that
     * is not is gone if the Clan's own list has dropped it, or if StarClan has taken a cat of its name since it became
     * this player's mentor. It used to be the name alone, looked for in StarClan's list and only inside camp: a mentor
     * renamed before it died, or gone any other way, left its apprentice with nobody for good (reported 2026-11-23).
     */
    static Loss lost(ServerPlayer p, ClanData.Clan wc, LifeData life) {
        UUID id = QuestState.mentorId(p);
        String name = QuestState.mentorName(p);
        if (id == null && name.isEmpty()) return null;
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        if (id != null && p.getServer().overworld().getEntity(id) instanceof WCatEntity cat) {
            if (!cat.isAlive()) return Loss.DIED;
            if (!wc.clanUUID.equals(cat.getClanUUID())) return Loss.GONE;
            // its place in the Clan only: a mentor standing in a scene or at a Gathering for a while is still the mentor
            Kin.Role r = Kin.role(cat, life);
            boolean teaches = healer ? r == Kin.Role.MEDICINE : r == Kin.Role.WARRIOR || r == Kin.Role.DEPUTY || r == Kin.Role.LEADER;
            return teaches ? null : Loss.RETIRED;
        }
        long since = QuestState.root(p).getLong("mentorSince");
        for (LifeData.Fallen f : life.starClan()) {
            if (f.gameTime() >= since && f.name().equalsIgnoreCase(name)) return Loss.DIED;
        }
        return id != null && !wc.clanCats.containsKey(id) ? Loss.GONE : null;
    }

    /**
     * The mentor itself is not about, but a cat of its name who can teach is in camp: that cat is the mentor from now
     * on. The Campaign makes the Clans' cats anew when they settle at the lake (the old ones stay behind in the empty
     * forest, still on the Clan's list), so a mentor known only by its id never came to its apprentice again.
     */
    static void follow(ServerPlayer p, UUID clanId, Landmark camp, LifeData life) {
        UUID id = QuestState.mentorId(p);
        String name = QuestState.mentorName(p);
        ServerLevel ow = p.getServer().overworld();
        if (name.isEmpty() || p.level() != ow || id != null && ow.getEntity(id) != null) return;
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        for (WCatEntity c : Kin.catsNear(ow, clanId, camp.x(), camp.z(), camp.radius() + 60)) {
            if (!fit(c, life, healer) || !Kin.name(c).equalsIgnoreCase(name)) continue;
            QuestState.setMentor(p, name, c.getUUID());
            return;
        }
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
        if (!(p.getServer().overworld().getEntity(id) instanceof WCatEntity cat)) return;
        if (!cat.getPersistentData().hasUUID(KEY)) cat.getPersistentData().putUUID(KEY, p.getUUID());
        // a mentor who took a new name (the deputy who became leader) is known by it from then on
        String name = Kin.name(cat);
        if (cat.getCustomName() != null && !name.equals(QuestState.mentorName(p))) QuestState.root(p).putString("mentor", name);
    }
}
