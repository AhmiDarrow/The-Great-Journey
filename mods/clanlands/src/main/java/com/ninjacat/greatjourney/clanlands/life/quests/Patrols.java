package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A border patrol is not walked alone: the deputy sends one or two warriors along, and they walk it with the player,
 * a few paces ahead and to either side, toward the next border point still to mark and then home. Warrior Cats
 * Events' own patrol goal is not used for this: it walks off on its own, waits a minute at every point, and gives up
 * for good once a cat has spent two and a half minutes walking patrols, far less than one of ours.
 *
 * <p>At night, or when it rains, the warriors turn for home with the player and say so; once it is light (or dry)
 * again they set out for the border again. Kiki's patrol kept her out all night and through rainstorms (2026-10-19).
 *
 * <p>While it walks with a player a warrior wears {@link BookClans#BUSY_TAG} (and {@link #TAG}); both come off when
 * the patrol is handed in or dropped, the player leaves, or the server stops, and the warrior goes home. A warrior that
 * loads again still tagged (a crash mid-patrol) is let go then.
 */
final class Patrols {
    private Patrols() {}

    static final String TAG = "greatjourney_patrol";
    /** Further than this from the player, a warrior is brought to them ("it found its own way round"). */
    static final double CATCH_UP = 28;
    /** A patrol is let go after this long even if the player never hands it in (two Minecraft days). */
    static final long LONGEST = 48000;

    /** One player's patrol: who sent it, the warriors walking it, and where they head next. */
    private static final class Walk {
        final UUID player, giver;
        final ServerLevel level;
        final List<UUID> cats = new ArrayList<>();
        final long started;
        double nextX, nextZ;
        /** Turned for home for the night or the rain. */
        boolean homeward;

        Walk(ServerPlayer p, UUID giver) {
            this.player = p.getUUID();
            this.giver = giver;
            this.level = p.serverLevel();
            this.started = level.getGameTime();
            this.nextX = p.getX();
            this.nextZ = p.getZ();
        }
    }

    private static final Map<UUID, Walk> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Walk> BY_CAT = new HashMap<>();

    static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % 20 == 11 && !BY_PLAYER.isEmpty()) tick(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> end(BY_PLAYER.get(e.getEntity().getUUID())));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> List.copyOf(BY_PLAYER.values()).forEach(Patrols::end));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            BY_PLAYER.clear();
            BY_CAT.clear();
        });
        NeoForge.EVENT_BUS.addListener(Patrols::onJoin);
    }

    static void sendAlong(ServerPlayer p, WCatEntity deputy, Task patrol) {
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        end(BY_PLAYER.get(p.getUUID()));
        List<WCatEntity> warriors = p.serverLevel().getEntitiesOfClass(WCatEntity.class, deputy.getBoundingBox().inflate(32),
                c -> java.util.Objects.equals(c.getClanUUID(), deputy.getClanUUID()) && Kin.role(c, life) == Kin.Role.WARRIOR
                        && !c.getTags().contains(BookClans.BUSY_TAG) && !c.isBaby());
        warriors.sort(java.util.Comparator.comparingDouble(c -> c.distanceToSqr(p)));
        Walk w = new Walk(p, deputy.getUUID());
        for (WCatEntity c : warriors) {
            if (w.cats.size() >= 2) break;
            hold(c);
            w.cats.add(c.getUUID());
            BY_CAT.put(c.getUUID(), w);
        }
        if (w.cats.isEmpty()) return;
        BY_PLAYER.put(p.getUUID(), w);
        aim(w, p, patrol);
    }

    /** Once a second: the patrol still on, the warriors still there, and the next place to head for. */
    static void tick(MinecraftServer server) {
        for (Walk w : List.copyOf(BY_PLAYER.values())) {
            ServerPlayer p = server.getPlayerList().getPlayer(w.player);
            Task patrol = null;
            if (p != null && p.isAlive() && p.serverLevel() == w.level && w.level.getGameTime() - w.started < LONGEST) {
                for (Task t : QuestState.duties(p)) {
                    if (t.kind == Task.Kind.BORDER_PATROL && w.giver.equals(t.giverId)) patrol = t;
                }
            }
            if (patrol == null) {
                end(w);
                continue;
            }
            w.cats.removeIf(id -> {
                if (w.level.getEntity(id) instanceof WCatEntity c && c.isAlive()) return false;
                BY_CAT.remove(id);                                // killed, or left behind in an unloaded chunk
                return true;
            });
            if (w.cats.isEmpty()) {
                BY_PLAYER.remove(w.player);
                continue;
            }
            var ow = server.overworld();
            boolean home = Tasks.night(ow) || Tasks.wet(ow);
            if (home != w.homeward) {
                w.homeward = home;
                if (w.level.getEntity(w.cats.get(0)) instanceof WCatEntity c) {
                    com.ninjacat.greatjourney.clanlands.life.Dialog.subtitle(p, com.ninjacat.greatjourney.clanlands.life.Dialog.Line.say(Kin.name(c), Kin.colour(p),
                            !home ? "That's better. Come on, the border's waiting."
                                    : Tasks.night(ow) ? "It's too dark to see the markers. Back to camp: we'll finish the patrol at first light."
                                    : "This rain washes every scent away. Back to camp till it clears.").speakerEntity(c));
                }
            }
            aim(w, p, patrol);
        }
    }

    /** The next border point still to mark, or the camp once every one is marked (or for the night, or the rain). */
    private static void aim(Walk w, ServerPlayer p, Task patrol) {
        if (w.homeward) {
            aimHome(w, p);
            return;
        }
        for (Task.Objective o : patrol.objectives) {
            if (o.type == Task.Objective.Type.VISIT && !o.done()) {
                w.nextX = o.x;
                w.nextZ = o.z;
                return;
            }
        }
        aimHome(w, p);
    }

    /** Back to whoever sent the patrol, or to the camp if they are not about. */
    private static void aimHome(Walk w, ServerPlayer p) {
        if (w.level.getEntity(w.giver) instanceof WCatEntity deputy) {
            w.nextX = deputy.getX();
            w.nextZ = deputy.getZ();
            return;
        }
        var clan = Kin.bookClan(p);
        var world = com.ninjacat.greatjourney.clanlands.world.ClanWorld.current();
        if (clan != null && world != null) {
            var camp = com.ninjacat.greatjourney.clanlands.life.Homeland.camp(world, clan);
            w.nextX = camp.x();
            w.nextZ = camp.z();
        }
    }

    private static void end(Walk w) {
        if (w == null) return;
        BY_PLAYER.remove(w.player, w);
        for (UUID id : w.cats) {
            BY_CAT.remove(id, w);
            if (w.level.getEntity(id) instanceof WCatEntity c) release(c);
        }
    }

    /** Takes the warrior out of its Clan routine for the walk: busy, and steered by us ahead of every other goal. */
    private static void hold(WCatEntity cat) {
        cat.addTag(BookClans.BUSY_TAG);
        cat.addTag(TAG);
        // a warrior the camp routine had sitting on guard or walking (SIT, FOLLOW) walks as a free cat; camp cats
        // are WANDER at rest, so it is left that way after
        cat.mode = net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity.CatMode.WANDER;
        cat.setOrderedToSit(false);
        cat.setInSittingPose(false);
        if (cat.isResting()) cat.setResting(false, 0);
        if (cat.isChilling()) cat.setChilling(false, 0);
        cat.returnHomeFlag = false;
        cat.getNavigation().stop();
        boolean steered = false;
        for (var g : cat.goalSelector.getAvailableGoals()) steered |= g.getGoal() instanceof Escort;
        if (!steered) cat.goalSelector.addGoal(-1, new Escort(cat));
    }

    /** The walk is over: the warrior goes home (Warrior Cats Events walks it back) and is free again. */
    private static void release(WCatEntity cat) {
        cat.removeTag(TAG);
        cat.removeTag(BookClans.BUSY_TAG);
        cat.goalSelector.removeAllGoals(g -> g instanceof Escort);
        cat.getNavigation().stop();
        if (cat.hasHomePosition() && cat.getHomePosition().distSqr(cat.blockPosition()) > 48 * 48) cat.returnHomeFlag = true;
    }

    private static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof WCatEntity cat) || BY_CAT.containsKey(cat.getUUID())) return;
        if (cat.getTags().contains(TAG)) release(cat);
    }

    /**
     * Our steering: the warrior keeps a few paces ahead of its player and to one side (the first on the left, the
     * second on the right), toward where the patrol goes next, hurrying when it has dropped behind.
     */
    private static final class Escort extends Goal {
        private final WCatEntity cat;
        private int sinceOrder;

        Escort(WCatEntity cat) {
            this.cat = cat;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return BY_CAT.containsKey(cat.getUUID()) && !com.ninjacat.greatjourney.clanlands.life.Lead.fighting(cat);   // fights first
        }

        @Override
        public boolean isInterruptable() {
            return false;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            Walk w = BY_CAT.get(cat.getUUID());
            ServerPlayer p = w == null ? null : w.level.getServer().getPlayerList().getPlayer(w.player);
            if (p == null || p.serverLevel() != cat.level()) return;
            cat.returnHomeFlag = false;                            // a Warrior Cats Events patrol it was on ended
            double dx = w.nextX - p.getX(), dz = w.nextZ - p.getZ(), len = Math.hypot(dx, dz);
            if (len < 1) {
                dx = -Math.sin(Math.toRadians(p.getYRot()));
                dz = Math.cos(Math.toRadians(p.getYRot()));
                len = 1;
            }
            dx /= len;
            dz /= len;
            double side = w.cats.indexOf(cat.getUUID()) == 0 ? 2.5 : -2.5;
            double tx = p.getX() + dx * 3 - dz * side, tz = p.getZ() + dz * 3 + dx * side;
            double toPlayer = cat.distanceTo(p), toSpot = Math.hypot(cat.getX() - tx, cat.getZ() - tz);
            if (toPlayer > CATCH_UP) {
                BlockPos at = BookClans.safeSpot(w.level, (int) Math.floor(tx), (int) Math.floor(tz), 3);
                var to = new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                // only where the player can't see it go or arrive; in view it runs to catch up
                if (!com.ninjacat.greatjourney.clanlands.life.Lead.inSight(p, cat.position())
                        && !com.ninjacat.greatjourney.clanlands.life.Lead.inSight(p, to)) {
                    cat.getNavigation().stop();
                    cat.teleportTo(to.x, to.y, to.z);
                    cat.fallDistance = 0;
                    return;
                }
                if (cat.getNavigation().isDone() || ++sinceOrder >= 10) {
                    sinceOrder = 0;
                    cat.getNavigation().moveTo(p, 1.45);
                }
                return;
            }
            if (toSpot < 1.5) {
                if (!cat.getNavigation().isDone()) cat.getNavigation().stop();
                cat.getLookControl().setLookAt(p, 30, 30);
                return;
            }
            if (cat.getNavigation().isDone() || ++sinceOrder >= 10) {
                sinceOrder = 0;
                cat.getNavigation().moveTo(tx, p.getY(), tz, toPlayer > 8 ? 1.4 : 1.1);
            }
        }
    }
}
