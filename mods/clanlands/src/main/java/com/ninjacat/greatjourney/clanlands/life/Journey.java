package com.ninjacat.greatjourney.clanlands.life;

import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where a player is in The Great Journey, kept in the player's persistent data so it survives deaths and restarts.
 * <ol>
 *   <li>NEW: has not finished Warrior Cats Events' cat setup yet.</li>
 *   <li>DREAM: in the StarClan dream (the tutorial).</li>
 *   <li>CHOOSING: woke from the dream and is choosing how to begin.</li>
 *   <li>PLAYING: began as a kittypet, a Clan apprentice or a loner.</li>
 * </ol>
 */
public final class Journey {
    private Journey() {}

    public enum Stage { NEW, DREAM, CHOOSING, PLAYING }

    public enum Start { NONE, KITTYPET, CLAN, LONER }

    private static final String ROOT = "greatjourney";

    private static CompoundTag data(ServerPlayer p) {
        CompoundTag persisted = p.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        if (!p.getPersistentData().contains(ServerPlayer.PERSISTED_NBT_TAG)) {
            p.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        }
        CompoundTag mine = persisted.getCompound(ROOT);
        if (!persisted.contains(ROOT)) persisted.put(ROOT, mine);
        return mine;
    }

    public static Stage stage(ServerPlayer p) {
        String s = data(p).getString("stage");
        return s.isEmpty() ? Stage.NEW : Stage.valueOf(s);
    }

    public static void setStage(ServerPlayer p, Stage s) {
        data(p).putString("stage", s.name());
    }

    public static Start start(ServerPlayer p) {
        String s = data(p).getString("start");
        return s.isEmpty() ? Start.NONE : Start.valueOf(s);
    }

    public static void setStart(ServerPlayer p, Start s) {
        data(p).putString("start", s.name());
    }

    /** The Clan that invited the player, until {@code untilTick} (game time). */
    public static void offer(ServerPlayer p, String clanKey, long untilTick) {
        data(p).putString("offer", clanKey.toLowerCase(Locale.ROOT));
        data(p).putLong("offerUntil", untilTick);
    }

    public static boolean hasOffer(ServerPlayer p, String clanKey) {
        CompoundTag d = data(p);
        return d.getString("offer").equals(clanKey.toLowerCase(Locale.ROOT)) && p.level().getGameTime() <= d.getLong("offerUntil");
    }

    public static void clearOffer(ServerPlayer p) {
        data(p).remove("offer");
        data(p).remove("offerUntil");
    }

    /** Small per-player flags (e.g. "met.patrol.thunder"), so a scene plays once. */
    public static boolean once(ServerPlayer p, String flag) {
        CompoundTag d = data(p).getCompound("flags");
        if (d.getBoolean(flag)) return false;
        d.putBoolean(flag, true);
        data(p).put("flags", d);
        return true;
    }

    /** The game time a per-player moment (e.g. "visit.THUNDER") was last stamped, or 0 if never. */
    public static long stamped(ServerPlayer p, String key) {
        return data(p).getCompound("times").getLong(key);
    }

    public static void stamp(ServerPlayer p, String key, long gameTime) {
        CompoundTag t = data(p).getCompound("times");
        t.putLong(key, gameTime);
        data(p).put("times", t);
    }

    public static int tutorialStep(ServerPlayer p) {
        return data(p).getInt("tutorial");
    }

    public static void setTutorialStep(ServerPlayer p, int step) {
        data(p).putInt("tutorial", step);
    }
}
