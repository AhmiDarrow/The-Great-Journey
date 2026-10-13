package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.LifeData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** A border patrol is not walked alone: the deputy sends one or two warriors along the same route. */
final class Patrols {
    private Patrols() {}

    static void sendAlong(ServerPlayer p, WCatEntity deputy, Task patrol) {
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        Map<Integer, BlockPos> route = new HashMap<>();
        int i = 0;
        for (Task.Objective o : patrol.objectives) {
            if (o.type != Task.Objective.Type.VISIT) continue;
            int x = (int) Math.floor(o.x), z = (int) Math.floor(o.z);
            route.put(i++, com.ninjacat.greatjourney.clanlands.life.BookClans.surface(p.serverLevel(), x, z));
        }
        if (route.isEmpty()) return;
        List<WCatEntity> warriors = p.serverLevel().getEntitiesOfClass(WCatEntity.class, deputy.getBoundingBox().inflate(32),
                c -> java.util.Objects.equals(c.getClanUUID(), deputy.getClanUUID()) && Kin.role(c, life) == Kin.Role.WARRIOR);
        int sent = 0;
        for (WCatEntity w : warriors) {
            if (sent >= 2) break;
            try {
                w.setOnBorderPatrol(new HashMap<>(route));
                sent++;
            } catch (RuntimeException ignored) {
                // a cat that can't patrol right now (no home, mid-task) just stays in camp
            }
        }
    }
}
