package com.ninjacat.greatjourney.clanlands.life.campaign;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Cats who are only there for a scene, like the four leaders on the Great Rock at the Campaign's first Gathering.
 * They belong to no Clan, sit still, cannot be hurt, and leave a few minutes later (and never come back after a
 * reload).
 */
final class SceneCats {
    private SceneCats() {}

    private static final long LIFETIME = 20L * 60 * 3;
    private static final List<Object[]> LIVE = new ArrayList<>();      // {ServerLevel, UUID, expiry gameTime}

    /** The server stopped: forget its levels (scene cats saved with the world are turned away when they load). */
    static void reset() {
        LIVE.clear();
    }

    static WCatEntity spawn(ServerLevel level, String name, double x, double z, String tag) {
        return spawn(level, name, x, z, tag, Double.NaN, Double.NaN, false);
    }

    /**
     * A scene cat on the ground at x, z (never up in a tree: the oaks of Fourtrees reach over the hollow, and the
     * heightmap put cats on their limbs, sitting in mid-air), turned toward fx, fz (NaN: any way), sitting if asked.
     */
    static WCatEntity spawn(ServerLevel level, String name, double x, double z, String tag, double fx, double fz, boolean sit) {
        WCatEntity cat = ModEntities.WCAT.get().create(level);
        if (cat == null) return null;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        level.getChunk(bx >> 4, bz >> 4);
        BlockPos at = com.ninjacat.greatjourney.clanlands.life.BookClans.safeSpot(level, bx, bz, 3);
        float yaw = Double.isNaN(fx) ? level.getRandom().nextFloat() * 360
                : (float) Math.toDegrees(Math.atan2(-(fx - (at.getX() + 0.5)), fz - (at.getZ() + 0.5)));
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
        com.ninjacat.greatjourney.clanlands.life.BookClans.rest(level, cat);
        cat.setYHeadRot(yaw);
        cat.setYBodyRot(yaw);
        cat.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        Clan clan = clanOf(level, name);
        if (clan != null) com.ninjacat.greatjourney.clanlands.life.BookLooks.clanCat(cat, clan);
        cat.setCustomName(Component.literal(name));
        cat.setNoAi(true);
        cat.setAgeInMoons(12);                    // with no AI the age never updates from 0, which draws a kit-sized cat
        cat.setInvulnerable(true);
        cat.setPersistenceRequired();
        if (sit) cat.setInSittingPose(true);
        cat.addTag(tag);
        level.addFreshEntity(cat);
        LIVE.add(new Object[] {level, cat.getUUID(), level.getGameTime() + LIFETIME});
        return cat;
    }

    /**
     * The Clan a scene cat is from, found by name: a leader's, or the warrior name a Clan's Gathering cats are given.
     * Null for anyone else, who keeps Warrior Cats Events' own random coat.
     */
    private static Clan clanOf(ServerLevel level, String name) {
        com.ninjacat.greatjourney.clanlands.life.LifeData life = com.ninjacat.greatjourney.clanlands.life.LifeData.get(level.getServer().overworld());
        com.ninjacat.greatjourney.clanlands.world.ClanWorld w = com.ninjacat.greatjourney.clanlands.world.ClanWorld.current();
        for (Clan c : Clan.values()) {
            if (name.equals(life.leader(c))) return c;
            if (w != null && name.equals(ForestClans.names(w, c).warrior().full())) return c;
        }
        return null;
    }

    static void tidy(MinecraftServer server) {
        LIVE.removeIf(o -> {
            ServerLevel level = (ServerLevel) o[0];
            if (level.getGameTime() < (long) o[2]) return false;
            Entity e = level.getEntity((UUID) o[1]);
            if (e != null) e.discard();
            return true;
        });
    }
}
