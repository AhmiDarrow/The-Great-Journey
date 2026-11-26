package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;

/**
 * Progress on quests as it happens: prey caught (and fish landed), places reached, heights climbed, things carried,
 * and the places of the books found. Once a second per player, plus kill and fishing events.
 */
public final class Tracking {
    private Tracking() {}

    static void onDeath(LivingDeathEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p)) return;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString();
        caught(p, id, 1);
    }

    static void onFished(ItemFishedEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        int fish = 0;
        for (ItemStack s : e.getDrops()) if (Bag.matches(s, Tasks.FISH_TAG)) fish += s.getCount();
        if (fish > 0) caught(p, "minecraft:cod", fish);
    }

    /** {@code n} prey of entity type {@code id} caught by the player. */
    public static void caught(ServerPlayer p, String id, int n) {
        boolean changed = false;
        long now = p.level().getGameTime();
        for (Task t : QuestState.active(p)) {
            boolean tc = false;
            if (!Pupils.counts(p, t)) continue;                             // a lesson, and the apprentice is not along
            for (Task.Objective o : t.objectives) {
                if (o.done() || !Kin.anyPrey(id)) continue;                 // any prey, not only the Clan's favourite
                switch (o.type) {
                    case CATCH -> {
                        o.progress = Math.min(o.goal, o.progress + n);
                        tc = true;
                    }
                    case STALK -> {
                        if (p.isCrouching()) {
                            o.progress = Math.min(o.goal, o.progress + n);
                            tc = true;
                        }
                    }
                    case CATCH_IN_TIME -> {
                        if (o.started == 0 || now - o.started > o.y * 20L) {
                            o.started = now;
                            o.progress = Math.min(o.goal, n);
                        } else {
                            o.progress = Math.min(o.goal, o.progress + n);
                        }
                        tc = true;
                    }
                    default -> { }
                }
                if (tc && o.done()) announce(p, t, o);
            }
            if (tc) {
                QuestState.save(p, t);
                changed = true;
            }
        }
        if (changed) Ui.push(p);
    }

    /** Once a second for every player. */
    static void tick(MinecraftServer server) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() != Level.OVERWORLD) continue;
            boolean changed = false;
            for (Task t : QuestState.active(p)) {
                boolean tc = false;
                if (!Pupils.counts(p, t)) continue;
                for (Task.Objective o : t.objectives) {
                    int before = o.progress;
                    switch (o.type) {
                        case VISIT -> {
                            if (!o.done() && Math.hypot(p.getX() - o.x, p.getZ() - o.z) <= o.radius) o.progress = 1;
                        }
                        case CLIMB -> {
                            if (!o.done() && Math.hypot(p.getX() - o.x, p.getZ() - o.z) <= o.radius) {
                                int floor = p.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) o.x, (int) o.z);
                                if (p.getY() >= floor + o.y && p.onGround()) o.progress = 1;
                            }
                        }
                        case BRING -> o.progress = Math.min(o.goal, Bag.count(p, o.key));
                        case CURE -> {
                            if (!o.done() && Patients.cured(p.serverLevel(), o, p.level().getGameTime())) o.progress = o.goal;
                        }
                        case CATCH_IN_TIME -> {
                            if (!o.done() && o.started != 0 && p.level().getGameTime() - o.started > o.y * 20L && o.progress > 0) {
                                o.progress = 0;                           // too slow: the assessment starts over
                                o.started = 0;
                                Ui.subtitle(p, List.of(Line.narrate("Too slow! Your prey got away. Try again.")));
                            }
                        }
                        default -> { }
                    }
                    if (o.progress != before) {
                        tc = true;
                        if (o.done() && o.type != Task.Objective.Type.BRING) announce(p, t, o);
                    }
                }
                if (tc) {
                    QuestState.save(p, t);
                    changed = true;
                }
            }
            for (Places.Place place : Places.all()) {
                if (Math.hypot(p.getX() - place.x(), p.getZ() - place.z()) <= place.reach() && QuestState.addPlace(p, place.id())) {
                    Ui.subtitle(p, List.of(Line.narrate("You have found " + place.name() + ".")));
                    changed = true;
                }
            }
            if (changed) Ui.push(p);
            com.ninjacat.greatjourney.clanlands.life.Kits.tick(p);    // a kit grows old enough to train (Kits)
            Story.tick(p);
            Founding.tick(p);                                      // a loner gathering a Clan of their own (Founding)
            Errands.tick(p);                                       // a Campaign errand done (Errands)
            CodeMoments.tick(p);                                   // the warrior code put to the test (CodeMoments)
            Friendship.tick(p);                                    // a cat waiting across the border (Friendship)
            Homestead.tick(p);                                     // something happening in a founded Clan (Homestead)
        }
    }

    private static void announce(ServerPlayer p, Task t, Task.Objective o) {
        List<Line> lines = new java.util.ArrayList<>();
        lines.add(Line.narrate("Done: " + o.text + "."));
        if (t.readyToHandIn()) lines.add(Line.narrate("Go back to " + t.giverName + "."));
        Ui.subtitle(p, lines);
    }
}
