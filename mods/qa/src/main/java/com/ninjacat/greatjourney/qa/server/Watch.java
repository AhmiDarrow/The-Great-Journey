package com.ninjacat.greatjourney.qa.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Lead;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Nests;
import com.ninjacat.greatjourney.clanlands.life.quests.Kin;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestState;
import com.ninjacat.greatjourney.clanlands.life.quests.Task;
import com.ninjacat.greatjourney.clanlands.life.quests.Tasks;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * TEST ONLY. The server half of the walking scenarios: it watches a guide or mentor leading the player (Lead), the
 * warriors walking a border patrol beside them, and the camp's cats crossing its barrier, and tells the client's
 * walking bot where to go next ({@link #GOALS}: the integrated server and the client share this JVM). Everything it
 * measures goes to the log as {@code [QA] NOTE/PASS/FAIL} lines through {@code /qa watch report}.
 */
public final class Watch {
    private Watch() {}

    /** Where the bot should walk, written each server tick, read by the client's bot (singleplayer only). */
    public static final class Goal {
        public volatile double x, z;
        /** Where the walk is headed beyond the next footstep (the cat itself, or the next point): for nudges. */
        public volatile double farX, farZ;
        /** Whether there is anything to follow (a lead or a patrol is on). */
        public volatile boolean active;
        /** False when the bot should stand still (right behind its guide). */
        public volatile boolean go;
        /** Stops reached of all (a lead), or border points marked of all (a patrol). */
        public volatile int reached, stops;
        /** A patrol: every point marked and back in camp. */
        public volatile boolean home;
        public volatile String mode = "off";
    }

    public static final Map<UUID, Goal> GOALS = new ConcurrentHashMap<>();

    public static Goal goal(UUID player) {
        return GOALS.get(player);
    }

    /** One player's watch: what it watches and what it has counted so far. */
    private static final class Log {
        String mode = "off";
        long since;
        // a lead
        UUID cat;
        String catName = "?";
        long leadStart = -1, leadEnd = -1, lastStopAt;
        int stops, reached, teleports, catchUps, hops, waitTicks, leadTicks, nudges, longestStall;
        double route, walked, px = Double.NaN, pz, cx = Double.NaN, cz, cy;
        Lead.Stop lastStop;
        List<Lead.Stop> stopList = List.of();
        double stallBest = Double.MAX_VALUE;
        long stallSince;
        int stalls, rescues;
        final ArrayDeque<double[]> crumbs = new ArrayDeque<>();
        // a patrol
        long patrolStart = -1, patrolMarked = -1, patrolHome = -1;
        int samples, escortsSeen, far, maxEscorts;
        double maxGap, sumGap;
        int gapN;
        final Map<UUID, double[]> last = new HashMap<>();
        int escortTeleports;
        // the barrier
        Clan clan;
        final Map<UUID, double[]> state = new HashMap<>();
        final List<String> offGate = new ArrayList<>();
        int crossings, gateCrossings;
    }

    private static final Map<UUID, Log> LOGS = new HashMap<>();
    private static boolean listening;

    static void register(LiteralArgumentBuilder<CommandSourceStack> qa) {
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
        }
        qa.then(Commands.literal("watch").then(Commands.argument("mode", StringArgumentType.word()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            String mode = StringArgumentType.getString(c, "mode");
            if (mode.equals("report")) {
                report(p);
                return 1;
            }
            Log l = new Log();
            l.mode = mode;
            l.since = p.level().getGameTime();
            var clan = Joining.clanOf(p);
            BookClans.Info info = clan == null ? null : BookClans.Info.byKey(clan.name);
            l.clan = info == null ? null : info.clan;
            LOGS.put(p.getUUID(), l);
            Goal g = new Goal();
            g.mode = mode;
            g.x = p.getX();
            g.z = p.getZ();
            GOALS.put(p.getUUID(), g);
            QaCommands.note(p, "watch", mode);
            return 1;
        })));
        qa.then(Commands.literal("nudge").executes(c -> {
            // the bot is stuck on a trunk or a bank: a few blocks on toward where it is going, onto firm ground
            ServerPlayer p = c.getSource().getPlayerOrException();
            Goal g = GOALS.get(p.getUUID());
            Log l = LOGS.get(p.getUUID());
            if (g == null) return 0;
            double dx = g.farX - p.getX(), dz = g.farZ - p.getZ(), len = Math.max(0.001, Math.hypot(dx, dz));
            // following a cat: to just behind it; walking to a point: a few blocks on
            double d = g.mode.equals("lead") ? Math.max(0, len - 3) : Math.max(0, Math.min(5, len - 2));
            BlockPos at = BookClans.safeSpot(p.serverLevel(), (int) Math.floor(p.getX() + dx / len * d), (int) Math.floor(p.getZ() + dz / len * d), 3);
            p.teleportTo(p.serverLevel(), at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5, p.getYRot(), p.getXRot());
            p.fallDistance = 0;
            if (l != null) l.nudges++;
            QaCommands.note(p, "nudge", "bot stuck at " + p.blockPosition().toShortString() + ", moved toward " + (int) g.farX + " " + (int) g.farZ);
            return 1;
        }));
        qa.then(Commands.literal("time").then(Commands.argument("ticks", IntegerArgumentType.integer(0, 23999)).executes(c -> {
            // a time of day, forward from now, keeping the day count going (vanilla's /time set restarts it at day 0)
            ServerPlayer p = c.getSource().getPlayerOrException();
            long now = p.getServer().overworld().getDayTime(), t = now / 24000 * 24000 + IntegerArgumentType.getInteger(c, "ticks");
            if (t <= now) t += 24000;
            for (ServerLevel level : p.getServer().getAllLevels()) level.setDayTime(t);
            QaCommands.note(p, "time", "day " + t / 24000 + " time " + t % 24000);
            return 1;
        })));
        qa.then(Commands.literal("patrolday").executes(c -> {
            // the next day the deputy's duty is a border patrol (each cat's duty of the day is fixed by day and cat)
            ServerPlayer p = c.getSource().getPlayerOrException();
            WCatEntity deputy = deputy(p);
            Clan clan = clanOf(p);
            if (deputy == null || clan == null) {
                QaCommands.fail(p, "patrolday", "no deputy near or no Clan");
                return 0;
            }
            long now = p.getServer().overworld().getDayTime();
            String name = plain(deputy);
            for (long day = now / 24000 + 1; day < now / 24000 + 40; day++) {
                Task t = Tasks.dutyFor(Kin.Role.DEPUTY, clan, name, deputy.getUUID(), day);
                if (t != null && t.kind == Task.Kind.BORDER_PATROL) {
                    for (ServerLevel level : p.getServer().getAllLevels()) level.setDayTime(day * 24000 + 4600);
                    Landmark camp = Homeland.camp(ClanWorld.current(), clan);
                    double fx = camp.x(), fz = camp.z(), len = 0;
                    StringBuilder pts = new StringBuilder();
                    for (Task.Objective o : t.objectives) {
                        len += Math.hypot(o.x - fx, o.z - fz);
                        fx = o.x;
                        fz = o.z;
                        pts.append((int) o.x).append(' ').append((int) o.z).append("; ");
                    }
                    len += Math.hypot(camp.x() - fx, camp.z() - fz);
                    QaCommands.note(p, "patrolday", String.format(Locale.ROOT, "day %d: %s's patrol, %d points, %.0f blocks in straight lines: %s",
                            day, name, t.objectives.size(), len, pts));
                    return 1;
                }
            }
            QaCommands.fail(p, "patrolday", "no patrol day in 40 days");
            return 0;
        }));
        qa.then(Commands.literal("duties").then(Commands.literal("clear").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            int n = QuestState.duties(p).size();
            QuestState.setDuties(p, new ArrayList<>());
            QaCommands.note(p, "duties", n + " cleared");
            return 1;
        })));
        qa.then(Commands.literal("routine").executes(c -> {
            routine(c.getSource().getPlayerOrException());
            return 1;
        }));
        qa.then(Commands.literal("open").executes(c -> {
            // out in the open, in the player's Clan's far reaches (no camp near): for the monster watch
            ServerPlayer p = c.getSource().getPlayerOrException();
            Clan clan = clanOf(p);
            double[] at = Kin.farEdgePoint(ClanWorld.current(), clan == null ? Clan.THUNDER : clan);
            ServerLevel ow = p.getServer().overworld();
            BlockPos s = BookClans.safeSpot(ow, (int) Math.floor(at[0]), (int) Math.floor(at[1]), 6);
            p.teleportTo(ow, s.getX() + 0.5, s.getY() + 0.05, s.getZ() + 0.5, p.getYRot(), 10);
            QaCommands.note(p, "open", "at " + s.toShortString());
            return 1;
        }));
    }

    // ticking ---------------------------------------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        if (LOGS.isEmpty()) return;
        for (Map.Entry<UUID, Log> e : LOGS.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Goal g = GOALS.get(e.getKey());
            if (p == null || g == null) continue;
            Log l = e.getValue();
            try {
                switch (l.mode) {
                    case "lead" -> lead(p, l, g);
                    case "patrol" -> patrol(p, l, g);
                    case "barrier" -> {
                        if (server.getTickCount() % 5 == 0) barrier(p, l);
                    }
                    default -> { }
                }
            } catch (RuntimeException ex) {
                QaCommands.fail(p, "watch-" + l.mode, "threw " + ex);
                l.mode = "off";
            }
        }
    }

    private static void lead(ServerPlayer p, Log l, Goal g) {
        Lead lead = Lead.of(p);
        long now = p.level().getGameTime();
        if (lead == null) {
            if (l.leadStart >= 0 && l.leadEnd < 0) {
                l.leadEnd = now;
                Lead.Stop last = l.lastStop;
                if (last != null && l.reached == l.stops - 1
                        && Math.hypot(p.getX() - last.x(), p.getZ() - last.z()) <= Math.max(last.radius(), 9) + 2) {
                    l.reached = l.stops;                    // the last stop ends the walk in the same tick it is reached
                    QaCommands.note(p, "lead-stop", l.reached + "/" + l.stops + " after " + (now - l.leadStart) / 20 + "s (the last)");
                }
                g.active = false;
                g.go = false;
                QaCommands.note(p, "lead", "ended: " + summary(l, now));
            }
            return;
        }
        if (l.leadEnd >= 0) return;                         // one walk per watch: the next (the hunt's watch) is not this one
        WCatEntity cat = lead.cat();
        if (l.leadStart < 0) {
            l.leadStart = now;
            l.leadEnd = -1;
            l.lastStopAt = now;
            l.cat = cat.getUUID();
            l.catName = plain(cat);
            l.reached = l.teleports = l.catchUps = l.hops = l.waitTicks = l.leadTicks = l.longestStall = 0;
            l.walked = 0;
            l.px = Double.NaN;
            l.cx = Double.NaN;
            l.crumbs.clear();
            l.stops = stopsOf(lead);
            l.route = routeOf(lead, p.getX(), p.getZ());
            List<Lead.Stop> all = stops(lead);
            l.lastStop = all.isEmpty() ? null : all.get(all.size() - 1);
            l.stopList = all;
            l.stallBest = Double.MAX_VALUE;
            l.stallSince = now;
            l.stalls = l.rescues = 0;
            StringBuilder b = new StringBuilder();
            for (Lead.Stop s : all) b.append((int) s.x()).append(' ').append((int) s.z()).append(" r").append((int) s.radius()).append("; ");
            QaCommands.note(p, "lead-stops", b.toString());
            QaCommands.note(p, "lead", String.format(Locale.ROOT, "began: %s leads, %d stops, %.0f blocks in straight lines, %s",
                    l.catName, l.stops, l.route, lead.accompanying() ? "accompanying" : "leading"));
        }
        l.leadTicks++;
        if (lead.waiting()) l.waitTicks++;
        if (!Double.isNaN(l.px)) l.walked += Math.hypot(p.getX() - l.px, p.getZ() - l.pz);
        l.px = p.getX();
        l.pz = p.getZ();
        if (!Double.isNaN(l.cx)) {
            double moved = Math.sqrt(cat.distanceToSqr(l.cx, l.cy, l.cz));
            if (moved > 3.5) {
                // the Lead put the cat somewhere: catching up with a far player, or round a wall it could not pass
                double before = Math.hypot(l.cx - p.getX(), l.cz - p.getZ());
                l.teleports++;
                if (before > 30) l.catchUps++;
                else l.hops++;
                l.crumbs.clear();
                QaCommands.note(p, "lead-teleport", String.format(Locale.ROOT, "%s moved %.0f blocks in a tick (player %.0f away before): %s",
                        l.catName, moved, before, before > 30 ? "catch-up" : "stuck hop"));
            }
        }
        l.cx = cat.getX();
        l.cy = cat.getY();
        l.cz = cat.getZ();
        if (lead.reached() != l.reached) {
            l.longestStall = (int) Math.max(l.longestStall, now - l.lastStopAt);
            l.lastStopAt = now;
            l.reached = lead.reached();
            l.stallBest = Double.MAX_VALUE;                 // a new stop: headway is measured to it from here
            l.stallSince = now;
            QaCommands.note(p, "lead-stop", String.format(Locale.ROOT, "%d/%d after %ds at %d %d", l.reached, l.stops, (now - l.leadStart) / 20,
                    p.getBlockX(), p.getBlockZ()));
        }
        // a stall: the cat no nearer its stop for a minute. Logged (where cat, player and stop are), then the bot is put
        // beside the cat, so what is left is the cat's own way forward (the bot is no pathfinder in a marsh)
        if (!lead.accompanying() && l.reached < l.stopList.size()) {
            Lead.Stop s = l.stopList.get(l.reached);
            double catTo = Math.hypot(cat.getX() - s.x(), cat.getZ() - s.z());
            if (catTo < l.stallBest - 8) {
                l.stallBest = catTo;
                l.stallSince = now;
            } else if (now - l.stallSince > 20 * 60) {
                l.stalls++;
                QaCommands.note(p, "lead-stall", String.format(Locale.ROOT, "%s no nearer stop %d (%d %d) for 60s: cat at %s (%.0f off, nav %s, waiting %s), player at %s",
                        l.catName, l.reached + 1, (int) s.x(), (int) s.z(), cat.blockPosition().toShortString(), catTo,
                        cat.getNavigation().isDone() ? "idle" : "moving", lead.waiting(), p.blockPosition().toShortString()));
                if (cat.distanceTo(p) > 4) {
                    BlockPos at = BookClans.safeSpot(p.serverLevel(), cat.getBlockX(), cat.getBlockZ(), 3);
                    p.teleportTo(p.serverLevel(), at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5, p.getYRot(), p.getXRot());
                    l.rescues++;
                }
                l.stallBest = catTo;
                l.stallSince = now;
            }
        }
        // the bot follows the cat's own footsteps (it found the way round trees and streams), never the straight line
        double[] tail = l.crumbs.peekLast();
        if (tail == null || Math.hypot(tail[0] - cat.getX(), tail[1] - cat.getZ()) > 1.5) l.crumbs.addLast(new double[] {cat.getX(), cat.getZ()});
        while (l.crumbs.size() > 1 && Math.hypot(l.crumbs.peekFirst()[0] - p.getX(), l.crumbs.peekFirst()[1] - p.getZ()) < 1.6) l.crumbs.pollFirst();
        while (l.crumbs.size() > 200) l.crumbs.pollFirst();
        double[] head = l.crumbs.peekFirst();
        double toCat = cat.distanceTo(p);
        g.x = head == null || toCat < 4 ? cat.getX() : head[0];
        g.z = head == null || toCat < 4 ? cat.getZ() : head[1];
        g.farX = cat.getX();
        g.farZ = cat.getZ();
        g.go = toCat > (lead.accompanying() ? 6 : 2.8);
        g.reached = l.reached;
        g.stops = l.stops;
        g.active = true;
    }

    private static String summary(Log l, long now) {
        long secs = ((l.leadEnd >= 0 ? l.leadEnd : now) - l.leadStart) / 20;
        return String.format(Locale.ROOT, "%s, %ds, reached %d/%d stops, %.0f blocks walked (%.0f straight), teleports %d (catch-ups %d, stuck hops %d), "
                        + "waiting %d%% of the time, longest between stops %ds, bot nudges %d, stalls %d (bot put by the cat %d)",
                l.catName, secs, l.reached, l.stops, l.walked, l.route, l.teleports, l.catchUps, l.hops,
                l.leadTicks == 0 ? 0 : 100 * l.waitTicks / l.leadTicks, Math.max(l.longestStall, now - l.lastStopAt) / 20, l.nudges, l.stalls, l.rescues);
    }

    @SuppressWarnings("unchecked")
    private static List<Lead.Stop> stops(Lead lead) {
        try {
            var f = Lead.class.getDeclaredField("stops");
            f.setAccessible(true);
            return (List<Lead.Stop>) f.get(lead);
        } catch (ReflectiveOperationException e) {
            return List.of();
        }
    }

    private static int stopsOf(Lead lead) {
        return stops(lead).size();
    }

    private static double routeOf(Lead lead, double x, double z) {
        double len = 0;
        for (Lead.Stop s : stops(lead)) {
            len += Math.hypot(s.x() - x, s.z() - z);
            x = s.x();
            z = s.z();
        }
        return len;
    }

    private static void patrol(ServerPlayer p, Log l, Goal g) {
        long now = p.level().getGameTime();
        Task patrol = null;
        for (Task t : QuestState.duties(p)) if (t.kind == Task.Kind.BORDER_PATROL) patrol = t;
        Clan clan = l.clan;
        Landmark camp = clan == null ? null : Homeland.camp(ClanWorld.current(), clan);
        if (patrol == null || camp == null) {
            g.active = false;                                   // no patrol duty (yet, or any more)
            return;
        }
        if (l.patrolStart < 0) {
            l.patrolStart = now;
            l.px = Double.NaN;
            l.walked = 0;
        }
        if (l.patrolHome < 0 && !Double.isNaN(l.px)) l.walked += Math.hypot(p.getX() - l.px, p.getZ() - l.pz);
        l.px = p.getX();
        l.pz = p.getZ();
        int marked = 0, all = 0;
        Task.Objective next = null;
        for (Task.Objective o : patrol.objectives) {
            if (o.type != Task.Objective.Type.VISIT) continue;
            all++;
            if (o.done()) marked++;
            else if (next == null) next = o;
        }
        if (next == null && l.patrolMarked < 0) {
            l.patrolMarked = now;
            QaCommands.note(p, "patrol", "every border point marked after " + (now - l.patrolStart) / 20 + "s");
        }
        if (g.reached != marked && marked > 0) QaCommands.note(p, "patrol-point", marked + "/" + all + " after " + (now - l.patrolStart) / 20 + "s");
        double tx = next != null ? next.x : camp.x(), tz = next != null ? next.z : camp.z();
        double d = Math.hypot(tx - p.getX(), tz - p.getZ());
        boolean home = next == null && Math.hypot(camp.x() - p.getX(), camp.z() - p.getZ()) < Math.min(camp.radius(), 16);
        if (home && l.patrolHome < 0) {
            l.patrolHome = now;
            QaCommands.note(p, "patrol", "home in camp after " + (now - l.patrolStart) / 20 + "s");
        }
        g.x = g.farX = tx;
        g.z = g.farZ = tz;
        g.go = d > (next != null ? 4 : 6);
        g.reached = marked;
        g.stops = all;
        g.home = home;
        g.active = true;
        // the warriors walking it: how many, and how far from the player, once a second
        List<WCatEntity> escorts = p.serverLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(160),
                c -> c.isAlive() && c.getTags().contains("greatjourney_patrol"));
        for (WCatEntity c : escorts) {
            double[] was = l.last.get(c.getUUID());
            if (was != null && Math.sqrt(c.distanceToSqr(was[0], was[1], was[2])) > 3.5) l.escortTeleports++;
            l.last.put(c.getUUID(), new double[] {c.getX(), c.getY(), c.getZ()});
        }
        if (now % 20 != 0 || l.patrolHome >= 0) return;
        l.samples++;
        l.maxEscorts = Math.max(l.maxEscorts, escorts.size());
        l.escortsSeen += escorts.size();
        for (WCatEntity c : escorts) {
            double gap = c.distanceTo(p);
            l.maxGap = Math.max(l.maxGap, gap);
            l.sumGap += gap;
            l.gapN++;
            if (gap > 12) l.far++;
        }
    }

    /** Every 5 ticks: each cat of the player's Clan near its camp, and where it crosses the barrier. */
    private static void barrier(ServerPlayer p, Log l) {
        if (l.clan == null) return;
        ServerLevel ow = p.getServer().overworld();
        Landmark camp = Homeland.camp(ClanWorld.current(), l.clan);
        CampPlan plan = CampPlan.of(camp);
        if (plan == null) return;
        double[] bar = plan.barrier();
        UUID clanId = LifeData.get(ow).clanId(l.clan);
        double ux = Math.cos(plan.gate), uz = Math.sin(plan.gate);
        for (WCatEntity c : ow.getEntitiesOfClass(WCatEntity.class, new AABB(plan.cx - bar[1] - 64, ow.getMinBuildHeight(), plan.cz - bar[1] - 64,
                plan.cx + bar[1] + 64, ow.getMaxBuildHeight(), plan.cz + bar[1] + 64), c -> c.isAlive() && clanId != null && clanId.equals(c.getClanUUID()))) {
            double r = Math.hypot(c.getX() - plan.cx, c.getZ() - plan.cz);
            int zone = r < bar[0] ? 0 : r > bar[1] ? 2 : 1;
            for (CampPlan.Den d : plan.dens)                // a den dug into the barrier (a cliff cave) is inside the camp
                if (Math.hypot(c.getX() - d.x(), c.getZ() - d.z()) <= d.radius() + 1 + Math.max(0, d.length())) zone = 0;
            double[] st = l.state.get(c.getUUID());
            if (st == null) {
                // {last firm side (0 in, 2 out), x, z where it left that side, closest to the gate line meanwhile}
                l.state.put(c.getUUID(), new double[] {zone == 1 ? 0 : zone, c.getX(), c.getZ(), Double.MAX_VALUE});
                continue;
            }
            double along = (c.getX() - plan.cx) * ux + (c.getZ() - plan.cz) * uz;
            double perp = Math.abs(-(c.getX() - plan.cx) * uz + (c.getZ() - plan.cz) * ux);
            if (zone == 1) {
                st[3] = Math.min(st[3], along > 0 ? perp : Double.MAX_VALUE);
                continue;
            }
            if (zone != st[0]) {
                // crossed: by the way in if, in the band or at either end, it was within the gate's width of its line
                double mx = (st[1] + c.getX()) / 2, mz = (st[2] + c.getZ()) / 2;
                double mAlong = (mx - plan.cx) * ux + (mz - plan.cz) * uz, mPerp = Math.abs(-(mx - plan.cx) * uz + (mz - plan.cz) * ux);
                double best = Math.min(st[3], mAlong > 0 ? mPerp : Double.MAX_VALUE);
                l.crossings++;
                if (best <= plan.gateHalf + 2.5) {
                    l.gateCrossings++;
                } else {
                    boolean busy = c.getTags().contains(BookClans.BUSY_TAG);
                    double jump = Math.hypot(st[1] - c.getX(), st[2] - c.getZ());
                    l.offGate.add(String.format(Locale.ROOT, "%s %s at %d %d from %d %d (%.0f from the gate line%s%s; job %s, mode %s, tags %s)", plain(c),
                            zone == 2 ? "left" : "came in", c.getBlockX(), c.getBlockZ(), (int) st[1], (int) st[2], best == Double.MAX_VALUE ? -1 : best,
                            busy ? ", busy" : "", jump > 4 ? String.format(Locale.ROOT, ", in a %.0f-block jump", jump) : "", job(l.clan, c.getUUID()),
                            c.mode, c.getTags()));
                }
                st[0] = zone;
                st[3] = Double.MAX_VALUE;
            }
            st[1] = c.getX();
            st[2] = c.getZ();
        }
    }

    // reports ---------------------------------------------------------------------------------------------------

    private static void report(ServerPlayer p) {
        Log l = LOGS.get(p.getUUID());
        if (l == null) {
            QaCommands.fail(p, "watch report", "nothing watched");
            return;
        }
        long now = p.level().getGameTime();
        switch (l.mode) {
            case "lead" -> {
                if (l.leadStart < 0) {
                    QaCommands.fail(p, "lead began", "no lead began");
                    return;
                }
                QaCommands.note(p, "lead", summary(l, now));
                QaCommands.note(p, "pace", speeds(p));
                if (l.reached >= l.stops && l.stops > 0) QaCommands.pass(p, "lead reached every stop", l.reached + "/" + l.stops);
                else QaCommands.fail(p, "lead reached every stop", l.reached + "/" + l.stops);
                // a sane time: half a cat's walking pace over the stops' straight lines, and a minute to spare, counting
                // only the time the cat was on the move (waiting is for the bot, who is no pathfinder in a marsh)
                long secs = ((l.leadEnd >= 0 ? l.leadEnd : now) - l.leadStart) / 20, limit = (long) (l.route / 2.0) + 60;
                long moving = secs - (long) (secs * (l.leadTicks == 0 ? 0 : (double) l.waitTicks / l.leadTicks));
                String t = secs + "s in all, " + moving + "s on the move, " + limit + "s allowed for " + (int) l.route + " blocks";
                if (moving <= limit) QaCommands.pass(p, "lead in time", t);
                else QaCommands.fail(p, "lead in time", t);
            }
            case "patrol" -> {
                if (l.patrolStart < 0) {
                    QaCommands.fail(p, "patrol watched", "no border patrol duty seen");
                    return;
                }
                long end = l.patrolHome >= 0 ? l.patrolHome : now;
                String s = String.format(Locale.ROOT, "%ds out and home (all points after %s), %d samples, up to %d warriors, %.1f on average, gap mean %.1f max %.1f, "
                                + "%d samples over 12 blocks, %d warrior teleports, bot nudges %d", (end - l.patrolStart) / 20,
                        l.patrolMarked < 0 ? "-" : (l.patrolMarked - l.patrolStart) / 20 + "s", l.samples, l.maxEscorts,
                        l.samples == 0 ? 0 : (double) l.escortsSeen / l.samples, l.gapN == 0 ? 0 : l.sumGap / l.gapN, l.maxGap, l.far, l.escortTeleports, l.nudges);
                QaCommands.note(p, "patrol", s);
                QaCommands.note(p, "pace", String.format(Locale.ROOT, "player walked %.0f blocks in %ds (%.1f a second); %s",
                        l.walked, (end - l.patrolStart) / 20, l.walked / Math.max(1, (end - l.patrolStart) / 20.0), speeds(p)));
                if (l.patrolHome >= 0) QaCommands.pass(p, "patrol walked", s);
                else QaCommands.fail(p, "patrol walked", "not home: " + s);
                if (l.maxEscorts >= 2 && l.samples > 0 && l.escortsSeen >= 1.8 * l.samples) QaCommands.pass(p, "two warriors along", s);
                else QaCommands.fail(p, "two warriors along", s);
                if (l.gapN > 0 && l.far <= l.gapN / 10) QaCommands.pass(p, "warriors stay near", s);
                else QaCommands.fail(p, "warriors stay near", s);
                double mins = (end - l.patrolStart) / 20.0 / 60;
                if (l.patrolHome >= 0 && mins <= 10) QaCommands.pass(p, "patrol time", String.format(Locale.ROOT, "%.1f min", mins));
                else QaCommands.fail(p, "patrol time", String.format(Locale.ROOT, "%.1f min%s", mins, l.patrolHome >= 0 ? "" : " (not finished)"));
            }
            case "barrier" -> {
                String s = l.crossings + " crossings, " + l.gateCrossings + " by the way in, " + l.state.size() + " cats watched over "
                        + (now - l.since) / 20 + "s" + (l.offGate.isEmpty() ? "" : "; elsewhere: " + l.offGate);
                if (l.offGate.isEmpty()) QaCommands.pass(p, "cats leave by the way in", s);
                else QaCommands.fail(p, "cats leave by the way in", s);
            }
            default -> QaCommands.note(p, "watch", "mode " + l.mode);
        }
    }

    /** The routine's job for a cat ("act step i/n"), or "-". */
    @SuppressWarnings("unchecked")
    private static String job(Clan clan, UUID cat) {
        try {
            var f = Class.forName("com.ninjacat.greatjourney.clanlands.life.events.CampRoutine").getDeclaredField("CAMPS");
            f.setAccessible(true);
            Object camp = ((Map<Clan, ?>) f.get(null)).get(clan);
            if (camp == null) return "-";
            var jf = camp.getClass().getDeclaredField("jobs");
            jf.setAccessible(true);
            Object job = ((Map<UUID, ?>) jf.get(camp)).get(cat);
            if (job == null) return "-";
            var af = job.getClass().getDeclaredField("act");
            var sf = job.getClass().getDeclaredField("steps");
            var inf = job.getClass().getDeclaredField("i");
            af.setAccessible(true);
            sf.setAccessible(true);
            inf.setAccessible(true);
            List<?> steps = (List<?>) sf.get(job);
            int i = inf.getInt(job);
            return af.get(job) + " step " + i + "/" + steps.size() + (i >= 0 && i < steps.size() ? " " + steps.get(i) : "");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    /** A tally of what the camp's cats are doing (the routine's jobs), and whether it fits the time of day. */
    @SuppressWarnings("unchecked")
    private static void routine(ServerPlayer p) {
        Clan clan = clanOf(p);
        if (clan == null) {
            QaCommands.fail(p, "routine", "no Clan");
            return;
        }
        ServerLevel ow = p.getServer().overworld();
        Map<String, Integer> acts = new TreeMap<>();
        int guards = 0, jobs = 0, cats = 0, resting = 0, busy = 0, outside = 0;
        try {
            Class<?> cr = Class.forName("com.ninjacat.greatjourney.clanlands.life.events.CampRoutine");
            var f = cr.getDeclaredField("CAMPS");
            f.setAccessible(true);
            Object camp = ((Map<Clan, ?>) f.get(null)).get(clan);
            if (camp == null) {
                QaCommands.fail(p, "routine", "the routine is not running at " + clan + "'s camp");
                return;
            }
            var jf = camp.getClass().getDeclaredField("jobs");
            jf.setAccessible(true);
            var gf = camp.getClass().getDeclaredField("guards");
            gf.setAccessible(true);
            Map<UUID, ?> js = (Map<UUID, ?>) jf.get(camp);
            guards = ((java.util.Set<UUID>) gf.get(camp)).size();
            for (Object job : js.values()) {
                var af = job.getClass().getDeclaredField("act");
                af.setAccessible(true);
                acts.merge(String.valueOf(af.get(job)), 1, Integer::sum);
                jobs++;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            QaCommands.fail(p, "routine", "could not read the routine: " + e);
            return;
        }
        Landmark camp = Homeland.camp(ClanWorld.current(), clan);
        CampPlan plan = CampPlan.of(camp);
        double outer = plan == null ? camp.radius() : plan.barrier()[1];
        UUID clanId = LifeData.get(ow).clanId(clan);
        for (WCatEntity c : ow.getEntitiesOfClass(WCatEntity.class, new AABB(camp.x() - 96, ow.getMinBuildHeight(), camp.z() - 96, camp.x() + 96,
                ow.getMaxBuildHeight(), camp.z() + 96), c -> c.isAlive() && clanId != null && clanId.equals(c.getClanUUID()))) {
            cats++;
            if (c.isResting()) resting++;
            if (c.getTags().contains(BookClans.BUSY_TAG)) busy++;
            if (Math.hypot(c.getX() - camp.x(), c.getZ() - camp.z()) > outer) outside++;
        }
        int time = (int) (ow.getDayTime() % 24000);
        String s = String.format(Locale.ROOT, "time %d: %d cats near (%d outside the barrier, %d resting, %d busy), %d jobs %s, %d guards",
                time, cats, outside, resting, busy, jobs, acts, guards);
        QaCommands.note(p, "routine", s);
        boolean night = time >= 13500 && time < 22500;
        if (night) {
            int bed = acts.getOrDefault("SLEEP", 0) + acts.getOrDefault("HOME", 0);
            if (jobs > 0 && bed >= (jobs - guards) * 0.7) QaCommands.pass(p, "routine night", s);
            else QaCommands.fail(p, "routine night", s);
            if (guards >= 1 && guards <= 2) QaCommands.pass(p, "routine guards", s);
            else QaCommands.fail(p, "routine guards", s);
        } else {
            if (acts.size() >= 3) QaCommands.pass(p, "routine variety", s);
            else QaCommands.fail(p, "routine variety", s);
        }
    }

    // checks ----------------------------------------------------------------------------------------------------

    /** The checks QaCommands does not know: null when it holds, else why not. */
    static String check(ServerPlayer p, String kind, String arg) {
        ServerLevel ow = p.getServer().overworld();
        switch (kind) {
            case "nest" -> {                                    // nest <den>: the player has a nest of their own there, with a bed
                Nests.Claim n = Nests.of(p);
                if (n == null) return "no nest";
                if (!arg.isEmpty() && !n.den().name().equalsIgnoreCase(arg)) return "nest in the " + n.den() + " den";
                Clan clan = clanOf(p);
                Landmark camp = clan == null ? null : Homeland.camp(ClanWorld.current(), clan);
                if (camp != null && Math.hypot(n.pos().getX() - camp.x(), n.pos().getZ() - camp.z()) > camp.radius() + 4)
                    return "nest at " + n.pos().toShortString() + ", outside the camp";
                var block = BuiltInRegistries.BLOCK.getKey(ow.getBlockState(n.pos()).getBlock());
                QaCommands.note(p, "nest", n.den() + " at " + n.pos().toShortString() + ": " + block);
                return block.getPath().equals("moss_bed") ? null : "no moss bed on the nest: " + block;
            }
            case "respawn" -> {                                 // respawn nest: the player wakes on their nest
                Nests.Claim n = Nests.of(p);
                BlockPos r = p.getRespawnPosition();
                if (r == null) return "no respawn point";
                if (p.getRespawnDimension() != Level.OVERWORLD) return "respawn in " + p.getRespawnDimension().location();
                if (n == null) return "no nest; respawn at " + r.toShortString();
                return r.distManhattan(n.pos()) <= 1 ? null : "respawn at " + r.toShortString() + ", nest at " + n.pos().toShortString();
            }
            case "beds" -> {                                    // beds: every den of the player's camp has its full set of moss beds
                Clan clan = clanOf(p);
                if (clan == null) return "no Clan";
                Landmark camp = Homeland.camp(ClanWorld.current(), clan);
                CampPlan plan = CampPlan.of(camp);
                if (plan == null) return "no camp plan";
                List<String> per = new ArrayList<>(), short_ = new ArrayList<>();
                for (CampPlan.Den den : plan.dens) {
                    int want = plan.nests(den.kind()).size(), laid = 0;
                    for (int i = 0; i < want; i++) {
                        BlockPos at = BookClans.nestBlock(ow, plan, den.kind(), i);
                        if (BuiltInRegistries.BLOCK.getKey(ow.getBlockState(at).getBlock()).getPath().equals("moss_bed")) laid++;
                        else per.add("no bed at " + den.kind() + " nest " + i + " " + at.toShortString() + ": " + ow.getBlockState(at)
                                + " over " + ow.getBlockState(at.below()) + " under " + ow.getBlockState(at.above()));
                    }
                    int seen = bedsNear(ow, den);
                    per.add(String.format(Locale.ROOT, "%s %d/%d planned (full set %d), %d beds in the den", den.kind(), laid, want,
                            CampPlan.nestCount(den.kind()), seen));
                    if (laid < want) short_.add(den.kind().name());
                }
                QaCommands.note(p, "beds", clan + ": " + per);
                return short_.isEmpty() ? null : "missing beds in " + short_ + ": " + per;
            }
            case "story" -> {                                   // story <n>: the apprentice path has reached step n
                int s = QuestState.storyStep(p);
                return s >= Integer.parseInt(arg) ? null : "on step " + s;
            }
            case "lead" -> {                                    // lead none|leading|accompanying [blocks]
                Lead l = Lead.of(p);
                String[] a = arg.split("\\s+");
                String state = l == null ? "none" : l.accompanying() ? "accompanying" : "leading";
                if (!state.equals(a[0])) return "lead is " + state;
                if (l != null && a.length > 1 && l.cat().distanceTo(p) > Double.parseDouble(a[1]))
                    return String.format(Locale.ROOT, "%s is %.0f blocks off", plain(l.cat()), l.cat().distanceTo(p));
                if (l != null) QaCommands.note(p, "lead", plain(l.cat()) + " " + state + String.format(Locale.ROOT, ", %.0f blocks off", l.cat().distanceTo(p)));
                return null;
            }
            case "hostiles" -> {                                // hostiles <radius>: no monster (any mod's) near the player
                double r = Double.parseDouble(arg);
                Map<String, Integer> found = new TreeMap<>();
                for (Entity e : p.serverLevel().getEntities((Entity) null, p.getBoundingBox().inflate(r), e -> e.getType().getCategory() == MobCategory.MONSTER && e.isAlive()))
                    found.merge(EntityType.getKey(e.getType()).toString(), 1, Integer::sum);
                Map<String, Integer> all = new TreeMap<>();
                for (Entity e : p.serverLevel().getAllEntities())
                    if (e.getType().getCategory() == MobCategory.MONSTER && e.isAlive()) all.merge(EntityType.getKey(e.getType()).toString(), 1, Integer::sum);
                QaCommands.note(p, "hostiles", "within " + (int) r + ": " + found + "; loaded anywhere: " + all
                        + "; time " + p.level().getDayTime() % 24000 + ", light " + p.level().getMaxLocalRawBrightness(p.blockPosition()));
                return found.isEmpty() ? null : found.toString();
            }
            case "outofplace" -> {                              // outofplace: no tiger, lion and the like loaded anywhere
                TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("clanwild", "out_of_place"));
                Map<String, Integer> bad = new TreeMap<>(), wild = new TreeMap<>();
                for (Entity e : p.serverLevel().getAllEntities()) {
                    var key = EntityType.getKey(e.getType());
                    if (e.getType().is(tag) || key.getPath().matches("tiger|lion|zebra|giraffe|hippo|elephant|rhino|mammoth|ostrich"))
                        bad.merge(key.toString(), 1, Integer::sum);
                    else if (!key.getNamespace().equals("minecraft") && e.getType().getCategory() != MobCategory.MISC)
                        wild.merge(key.toString(), 1, Integer::sum);
                }
                int tagged = 0;
                for (var h : BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(tag)) tagged++;
                QaCommands.note(p, "wildlife", "out_of_place tag has " + tagged + " types; out of place loaded: " + bad + "; other mod animals loaded: " + wild);
                return bad.isEmpty() ? null : bad.toString();
            }
            case "escorts" -> {                                 // escorts <n>: n warriors on the player's patrol within 16 blocks
                List<WCatEntity> es = p.serverLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(16),
                        c -> c.isAlive() && c.getTags().contains("greatjourney_patrol"));
                QaCommands.note(p, "escorts", es.stream().map(c -> plain(c) + String.format(Locale.ROOT, " %.0f", c.distanceTo(p))).toList().toString());
                return es.size() >= Integer.parseInt(arg) ? null : es.size() + " warriors on the patrol near";
            }
            case "seeking" -> {                                 // seeking <clan>|none
                var s = com.ninjacat.greatjourney.clanlands.life.Starts.seeking(p);
                String now = s == null ? "none" : s.key();
                return now.equalsIgnoreCase(arg) ? null : "seeking " + now;
            }
            default -> {
                return "unknown check '" + kind + "'";
            }
        }
    }

    /** The player's and nearby cats' movement speed attributes (a Walkers cat form may not walk at a player's pace). */
    private static String speeds(ServerPlayer p) {
        var attr = net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED;
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "player speed %.3f%s", p.getAttributeValue(attr), p.isSprinting() ? " sprinting" : ""));
        for (WCatEntity c : p.serverLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(24), WCatEntity::isAlive))
            b.append(String.format(Locale.ROOT, ", %s %.3f", plain(c), c.getAttributeValue(attr)));
        return b.toString();
    }

    /** Moss beds on the den's floor (within its radius and two blocks, a few blocks either side of its height). */
    private static int bedsNear(ServerLevel ow, CampPlan.Den den) {
        int r = (int) Math.ceil(den.radius()) + 2, n = 0;
        int bx = (int) Math.floor(den.x()), bz = (int) Math.floor(den.z());
        int y0 = den.y() == CampPlan.SURFACE ? BookClans.surface(ow, bx, bz).getY() : den.y();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = bx - r; x <= bx + r; x++)
            for (int z = bz - r; z <= bz + r; z++)
                for (int y = y0 - 4; y <= y0 + 4; y++)
                    if (BuiltInRegistries.BLOCK.getKey(ow.getBlockState(m.set(x, y, z)).getBlock()).getPath().equals("moss_bed")) n++;
        return n;
    }

    private static Clan clanOf(ServerPlayer p) {
        var clan = Joining.clanOf(p);
        BookClans.Info info = clan == null ? null : BookClans.Info.byKey(clan.name);
        return info == null ? null : info.clan;
    }

    private static WCatEntity deputy(ServerPlayer p) {
        var clan = Joining.clanOf(p);
        if (clan == null) return null;
        return p.serverLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(96),
                        c -> c.isAlive() && c.getRank() == WCatEntity.Rank.DEPUTY && clan.clanUUID.equals(c.getClanUUID()))
                .stream().min(java.util.Comparator.comparingDouble(c -> c.distanceToSqr(p))).orElse(null);
    }

    static String plain(WCatEntity c) {
        return c.getCustomName() == null ? "?" : c.getCustomName().getString().replace(" ♂", "").replace(" ♀", "");
    }

}
