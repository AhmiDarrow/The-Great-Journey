package com.ninjacat.greatjourney.clanlands.life.campaign;

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
        WCatEntity cat = ModEntities.WCAT.get().create(level);
        if (cat == null) return null;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        level.getChunk(bx >> 4, bz >> 4);
        BlockPos at = com.ninjacat.greatjourney.clanlands.life.BookClans.surface(level, bx, bz);
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
        cat.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setCustomName(Component.literal(name));
        cat.setNoAi(true);
        cat.setAgeInMoons(12);                    // with no AI the age never updates from 0, which draws a kit-sized cat
        cat.setInvulnerable(true);
        cat.setPersistenceRequired();
        cat.addTag(tag);
        level.addFreshEntity(cat);
        LIVE.add(new Object[] {level, cat.getUUID(), level.getGameTime() + LIFETIME});
        return cat;
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
