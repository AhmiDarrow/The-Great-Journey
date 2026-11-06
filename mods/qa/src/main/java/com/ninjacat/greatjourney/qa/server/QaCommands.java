package com.ninjacat.greatjourney.qa.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan;
import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Road;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.campaign.CampaignData;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestLog;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestState;
import com.ninjacat.greatjourney.clanlands.life.quests.Task;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.life.tour.Tour;
import com.ninjacat.greatjourney.clanlands.life.traffic.Traffic;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.slf4j.Logger;

/**
 * TEST ONLY. {@code /qa ...} (operators): the playtest harness's server half. It puts the player where a scenario
 * needs them (camp views, landmarks, roadside) and checks game state, logging one machine-readable line per check:
 * {@code [QA] PASS <player> <scenario> <check>} or {@code [QA] FAIL <player> <scenario> <check>: <why>}.
 * tools/playtest.py collects these from the server's log (the client's own log in singleplayer).
 */
public final class QaCommands {
    private QaCommands() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID, String> SCENARIO = new HashMap<>();
    private static final Map<String, java.util.Set<UUID>> ARRIVED = new HashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> qa = Commands.literal("qa").requires(s -> s.hasPermission(2));
        qa.then(Commands.literal("begin").then(Commands.argument("scenario", StringArgumentType.word()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            SCENARIO.put(p.getUUID(), StringArgumentType.getString(c, "scenario"));
            line("BEGIN", p, "-", "at " + p.blockPosition().toShortString() + " in " + p.level().dimension().location());
            return 1;
        })));
        qa.then(Commands.literal("run").then(Commands.argument("command", StringArgumentType.greedyString()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            String command = StringArgumentType.getString(c, "command");
            List<String> said = new ArrayList<>();
            int[] result = {0};
            var out = new net.minecraft.commands.CommandSource() {
                @Override public void sendSystemMessage(Component m) {
                    said.add(m.getString());
                }

                @Override public boolean acceptsSuccess() {
                    return true;
                }

                @Override public boolean acceptsFailure() {
                    return true;
                }

                @Override public boolean shouldInformAdmins() {
                    return false;
                }
            };
            CommandSourceStack src = p.createCommandSourceStack().withPermission(4).withSource(out)
                    .withCallback((ok, r) -> result[0] = ok && r > 0 ? r : 0);
            try {
                p.getServer().getCommands().getDispatcher().execute(command, src);
            } catch (Exception e) {
                said.add(e.getMessage());
            }
            line(result[0] > 0 ? "PASS" : "FAIL", p, "run " + command, String.join(" | ", said));
            return result[0];
        })));
        qa.then(Commands.literal("cats").executes(c -> {          // who is around: name, rank, distance
            ServerPlayer p = c.getSource().getPlayerOrException();
            List<String> all = new ArrayList<>();
            for (WCatEntity cat : p.serverLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(96)))
                all.add((cat.getCustomName() == null ? "?" : cat.getCustomName().getString()) + " " + cat.getRank()
                        + " " + (int) cat.distanceTo(p) + (cat.isAlive() ? "" : " dead"));
            line("NOTE", p, "cats", all.size() + ": " + all);
            return 1;
        }));
        qa.then(Commands.literal("route").executes(c -> {         // the Great Journey's folk: settled? where?
            ServerPlayer p = c.getSource().getPlayerOrException();
            StringBuilder b = new StringBuilder();
            try {
                Class<?> rd = Class.forName("com.ninjacat.greatjourney.clanlands.life.route.RouteData");
                var get = rd.getDeclaredMethod("get", ServerLevel.class);
                get.setAccessible(true);
                Object data = get.invoke(null, p.serverLevel());
                for (String f : new String[] {"tribe", "midnight", "purdy", "eagles"}) {
                    var field = rd.getDeclaredField(f);
                    field.setAccessible(true);
                    b.append(f).append("=").append(field.get(data)).append(" ");
                }
            } catch (ReflectiveOperationException e) {
                b.append("no RouteData: ").append(e).append(' ');
            }
            var j = ClanWorld.current().journey;
            b.append(String.format(Locale.ROOT, "sett %.0f away, cave %.0f away; ", Math.hypot(p.getX() - j.midnightSett()[0], p.getZ() - j.midnightSett()[1]),
                    Math.hypot(p.getX() - j.tribeCave()[0], p.getZ() - j.tribeCave()[1])));
            for (Entity e : p.serverLevel().getAllEntities())
                if (e.getCustomName() != null && e.getCustomName().getString().matches("(?i)(midnight|purdy|stoneteller).*"))
                    b.append(e.getCustomName().getString()).append('@').append(e.blockPosition().toShortString()).append(' ');
            line("NOTE", p, "route", b.toString());
            return 1;
        }));
        qa.then(Commands.literal("note").then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            line("NOTE", p, "-", StringArgumentType.getString(c, "text"));
            return 1;
        })));
        qa.then(Commands.literal("state").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            line("NOTE", p, "state", state(p));
            return 1;
        }));
        qa.then(Commands.literal("check").then(Commands.argument("what", StringArgumentType.greedyString()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            String what = StringArgumentType.getString(c, "what").trim();
            String fail;
            try {
                fail = check(p, what);
            } catch (Exception e) {
                fail = "threw " + e;
            }
            if (fail == null) line("PASS", p, what, "");
            else line("FAIL", p, what, fail);
            return fail == null ? 1 : 0;
        })));
        qa.then(Commands.literal("tp")
                .then(Commands.literal("camp").then(Commands.argument("map", StringArgumentType.word())
                        .then(Commands.argument("clan", StringArgumentType.word())
                                .then(Commands.argument("view", StringArgumentType.word()).executes(QaCommands::tpCamp)))))
                .then(Commands.literal("landmark").then(Commands.argument("map", StringArgumentType.word())
                        .then(Commands.argument("kind", StringArgumentType.word()).executes(c -> tpLandmark(c, 0))
                                .then(Commands.argument("index", IntegerArgumentType.integer(0)).executes(c ->
                                        tpLandmark(c, IntegerArgumentType.getInteger(c, "index")))))))
                .then(Commands.literal("road").then(Commands.argument("map", StringArgumentType.word())
                        .then(Commands.argument("kind", StringArgumentType.word()).executes(c -> tpRoad(c, false))
                                .then(Commands.literal("on").executes(c -> tpRoad(c, true)))))));
        qa.then(Commands.literal("arrive").then(Commands.argument("tag", StringArgumentType.word())
                .then(Commands.argument("count", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    String tag = StringArgumentType.getString(c, "tag");
                    var set = ARRIVED.computeIfAbsent(tag, k -> new java.util.HashSet<>());
                    set.add(p.getUUID());
                    if (set.size() >= IntegerArgumentType.getInteger(c, "count")) {
                        LOG.info("[QA] NOTE * - barrier {} released", tag);
                        for (ServerPlayer o : p.getServer().getPlayerList().getPlayers())
                            o.sendSystemMessage(Component.literal("[QA] GO " + tag));
                    }
                    return 1;
                }))));
        qa.then(Commands.literal("monster").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            Road road = nearestRoad(p);
            if (road == null) {
                line("FAIL", p, "monster", "no road near");
                return 0;
            }
            double along = road.path().nearest(p.getX(), p.getZ(), 1e9)[1];
            boolean ok = Traffic.sendPast(p.serverLevel(), road, along);
            line(ok ? "PASS" : "FAIL", p, "monster sent", ok ? "" : "Traffic.sendPast refused");
            return ok ? 1 : 0;
        }).then(Commands.literal("at-me").executes(c -> {
            // for the strike test: "qa tp road ... on" stands the player in the lane of monsters driving toward the
            // road's start, so one comes that way, 60 blocks off (sendPast picks its way at random, and one driving the
            // other way passes in the far lane: the old test then waited on chance traffic)
            ServerPlayer p = c.getSource().getPlayerOrException();
            Road road = nearestRoad(p);
            if (road == null) {
                line("FAIL", p, "monster", "no road near");
                return 0;
            }
            double along = road.path().nearest(p.getX(), p.getZ(), 1e9)[1];
            boolean ok = Traffic.placeForTest(p.serverLevel(), road, Math.min(road.path().length(), along + 60), -1);
            line(ok ? "PASS" : "FAIL", p, "monster sent", ok ? "" : "Traffic.placeForTest refused");
            return ok ? 1 : 0;
        })));
        qa.then(Commands.literal("talk").then(Commands.argument("rank", StringArgumentType.word()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            String rank = StringArgumentType.getString(c, "rank").toUpperCase(Locale.ROOT);
            WCatEntity cat = talkedTo(p, rank);
            if (cat == null) cat = nearestCat(p, rank);
            if (cat != null) TALKED.put(p.getUUID() + rank, cat.getUUID());
            if (cat == null) {
                line("FAIL", p, "talk " + rank, "no " + rank + " of the player's Clan within 96 blocks");
                return 0;
            }
            // stand in front of the cat, then right-click it the way a player does (NeoForge fires EntityInteract)
            Dialog.clear(p);
            p.teleportTo(p.serverLevel(), cat.getX() + 1.5, cat.getY(), cat.getZ(), 90, 0);
            var result = p.interactOn(cat, InteractionHand.MAIN_HAND);
            line("NOTE", p, "talk " + rank, "talked to " + (cat.getCustomName() == null ? "?" : cat.getCustomName().getString())
                    + " holding " + BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()) + ", result " + result
                    + ", cat clan " + cat.getClanUUID() + ", dialogue " + (Dialog.busy(p) ? "open" : "idle"));
            return 1;
        })));
        qa.then(Commands.literal("kill").then(Commands.argument("rank", StringArgumentType.word()).executes(c -> {
            // the nearest cat of that rank in the player's Clan dies peacefully, the way Clan events take an old cat
            // ("qa check medicine" then wants its successor): the forced old-age event picks the oldest cat instead
            ServerPlayer p = c.getSource().getPlayerOrException();
            String rank = StringArgumentType.getString(c, "rank").toUpperCase(Locale.ROOT);
            WCatEntity cat = nearestCat(p, rank);
            if (cat == null) {
                line("FAIL", p, "kill " + rank, "no " + rank + " of the player's Clan within 96 blocks");
                return 0;
            }
            KILLED.add(cat.getUUID());
            String name = cat.getCustomName() == null ? "?" : cat.getCustomName().getString();
            try {
                var die = Class.forName("com.ninjacat.greatjourney.clanlands.life.events.Deaths").getDeclaredMethod("diePeacefully", WCatEntity.class);
                die.setAccessible(true);
                die.invoke(null, cat);
            } catch (ReflectiveOperationException e) {
                cat.hurt(cat.damageSources().starve(), Float.MAX_VALUE);
            }
            line(cat.isAlive() ? "FAIL" : "NOTE", p, "kill " + rank, name + (cat.isAlive() ? " is still alive" : " has died"));
            return cat.isAlive() ? 0 : 1;
        })));
        qa.then(Commands.literal("fulfil").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            int n = fulfil(p);
            line(n > 0 ? "NOTE" : "FAIL", p, "fulfil duties", n + " objectives satisfied");
            return n;
        }));
        Portraits.register(qa);
        Watch.register(qa);
        d.register(qa);
    }

    static void note(ServerPlayer p, String check, String detail) {
        line("NOTE", p, check, detail);
    }

    static void pass(ServerPlayer p, String check, String detail) {
        line("PASS", p, check, detail);
    }

    static void fail(ServerPlayer p, String check, String detail) {
        line("FAIL", p, check, detail);
    }

    private static void line(String verdict, ServerPlayer p, String check, String detail) {
        String scen = SCENARIO.getOrDefault(p.getUUID(), "-");
        LOG.info("[QA] {} {} {} {}{}", verdict, p.getGameProfile().getName(), scen, check.replace(' ', '_'), detail.isEmpty() ? "" : ": " + detail);
        if (!verdict.equals("NOTE") && !verdict.equals("BEGIN"))
            p.sendSystemMessage(Component.literal("QA " + verdict + " " + check + (detail.isEmpty() ? "" : ": " + detail)));
    }

    // checks ----------------------------------------------------------------------------------------------------

    /** Null when the check holds, else why not. */
    private static String check(ServerPlayer p, String what) {
        String[] a = what.split("\\s+");
        String arg = a.length > 1 ? what.substring(a[0].length()).trim() : "";
        ServerLevel level = p.serverLevel();
        ClanWorld w = ClanWorld.current();
        switch (a[0]) {
            case "alive" -> {
                return p.isAlive() && !p.isDeadOrDying() ? null : "the player is dead";
            }
            case "ground" -> {
                if (!p.isAlive()) return "dead";
                if (p.getY() < level.getMinBuildHeight() + 8) return "below the world floor at y " + (int) p.getY();
                BlockPos feet = p.blockPosition();
                if (level.getBlockState(feet).isSuffocating(level, feet) || level.getBlockState(feet.above()).isSuffocating(level, feet.above()))
                    return "inside a solid block at " + feet.toShortString() + " (" + level.getBlockState(feet).getBlock() + ")";
                for (int dy = 0; dy <= 4; dy++) {
                    BlockState s = level.getBlockState(feet.below(dy));
                    if (!s.isAir() && (s.isSolid() || !s.getFluidState().isEmpty())) return null;
                }
                return "nothing to stand on within 4 blocks below " + feet.toShortString();
            }
            case "dim" -> {
                return level.dimension().location().getPath().equals(arg) ? null : "in " + level.dimension().location();
            }
            case "stage" -> {
                return Journey.stage(p).name().equalsIgnoreCase(arg) ? null : "stage is " + Journey.stage(p);
            }
            case "start" -> {
                Journey.Start s = Journey.start(p);
                return s != null && s.name().equalsIgnoreCase(arg) ? null : "start is " + s;
            }
            case "clan" -> {
                var clan = Joining.clanOf(p);
                if (arg.equals("none")) return clan == null ? null : "belongs to " + clan.name;
                return clan != null && clan.name.toLowerCase(Locale.ROOT).contains(arg.toLowerCase(Locale.ROOT)) ? null
                        : "belongs to " + (clan == null ? "no Clan" : clan.name);
            }
            case "incamp" -> {
                String[] m = arg.split("\\s+");
                CampPlan plan = plan(w, m[0], m[1]);
                if (plan == null) return "no camp " + arg;
                double d = Math.hypot(p.getX() - plan.cx, p.getZ() - plan.cz);
                return d <= plan.clearing + 2 ? null : String.format(Locale.ROOT, "%.0f blocks from the camp middle (clearing %.0f)", d, plan.clearing);
            }
            case "near" -> {                                    // near <map> <KIND> <radius>
                String[] m = arg.split("\\s+");
                Landmark l = landmark(w, m[0], m[1], 0);
                if (l == null) return "no landmark " + m[1] + " on " + m[0];
                double r = Double.parseDouble(m[2]), d = Math.hypot(p.getX() - l.x(), p.getZ() - l.z());
                return d <= r ? null : String.format(Locale.ROOT, "%.0f blocks from %s", d, l.kind());
            }
            case "act" -> {
                int act = CampaignData.get(p.getServer().overworld()).act();
                return act >= Integer.parseInt(arg) ? null : "the world is in act " + act;
            }
            case "dialogue" -> {
                boolean busy = Dialog.busy(p);
                return arg.equals("open") == busy ? null : "dialogue is " + (busy ? "open" : "idle");
            }
            case "journal" -> {
                JournalView v = QuestLog.view(p);
                String all = v.toString().toLowerCase(Locale.ROOT);
                return all.contains(arg.toLowerCase(Locale.ROOT)) ? null : "journal lacks '" + arg + "': " + abbreviate(v.toString());
            }
            case "duties" -> {
                int n = QuestState.duties(p).size();
                return n >= Integer.parseInt(arg) ? null : n + " duties";
            }
            case "standing" -> {
                int n = QuestState.standing(p);
                return n >= Integer.parseInt(arg) ? null : "standing " + n;
            }
            case "mentor" -> {
                return QuestState.mentorName(p) != null ? null : "no mentor";
            }
            case "monsters" -> {
                long parts = monsterParts(p);
                return switch (arg) {
                    case "none" -> parts == 0 && Traffic.active() == 0 ? null : parts + " monster parts, " + Traffic.active() + " active";
                    // none left over from before: new traffic may come along at any moment while a cat stands by the road,
                    // but no monster part may have been on it for more than 30 seconds (a pass takes about 15)
                    case "gone" -> {
                        long old = 0;
                        for (Entity e : p.serverLevel().getAllEntities()) if (e.getTags().contains(Traffic.PART_TAG) && e.tickCount > 20 * 30) old++;
                        yield old == 0 ? null : old + " monster parts on the road for over 30 s (" + parts + " parts, " + Traffic.active() + " active)";
                    }
                    default -> parts > 0 || Traffic.active() > 0 ? null : "no monster";
                };
            }
            case "named" -> {                                   // named <text>: an entity so named within 64 blocks
                List<Entity> es = level.getEntities((Entity) null, p.getBoundingBox().inflate(64),
                        e -> e.getCustomName() != null && e.getCustomName().getString().toLowerCase(Locale.ROOT).contains(arg.toLowerCase(Locale.ROOT)));
                return es.isEmpty() ? "nobody named '" + arg + "' within 64 blocks" : null;
            }
            case "unique" -> {                                  // unique <text>: exactly one so named loaded anywhere here
                long n = 0;
                for (Entity e : level.getAllEntities())
                    if (e.getCustomName() != null && e.getCustomName().getString().toLowerCase(Locale.ROOT).contains(arg.toLowerCase(Locale.ROOT))) n++;
                return n == 1 ? null : n + " entities named '" + arg + "'";
            }
            case "touring" -> {
                boolean t = Tour.touring(p);
                return Boolean.parseBoolean(arg) == t ? null : "touring is " + t;
            }
            case "gamemode" -> {
                return p.gameMode.getGameModeForPlayer().getName().equalsIgnoreCase(arg) ? null : "game mode " + p.gameMode.getGameModeForPlayer().getName();
            }
            case "item" -> {                                    // item <namespace:id>
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(arg));
                return p.getInventory().contains(new ItemStack(item)) ? null : "no " + arg + " in the inventory";
            }
            case "cats" -> {                                    // cats <clan> <min>: WCE cats of that Clan near its camp
                String[] m = arg.split("\\s+");
                BookClans.Info info = BookClans.Info.byKey(m[0]);
                Landmark camp = Homeland.camp(w, info.clan);
                List<WCatEntity> cats = level.getEntitiesOfClass(WCatEntity.class,
                        new AABB(camp.x() - 64, level.getMinBuildHeight(), camp.z() - 64, camp.x() + 64, level.getMaxBuildHeight(), camp.z() + 64));
                return cats.size() >= Integer.parseInt(m[1]) ? null : cats.size() + " cats at the camp";
            }
            case "medicine" -> {                                // medicine: the player's Clan has exactly one living medicine cat at camp
                var clan = Joining.clanOf(p);
                BookClans.Info info = clan == null ? null : BookClans.Info.byKey(clan.name);
                if (info == null) return "the player has no lake Clan";
                Landmark camp = Homeland.camp(w, info.clan);
                List<WCatEntity> meds = level.getEntitiesOfClass(WCatEntity.class,
                        new AABB(camp.x() - 80, level.getMinBuildHeight(), camp.z() - 80, camp.x() + 80, level.getMaxBuildHeight(), camp.z() + 80),
                        cat -> cat.isAlive() && cat.getRank() == WCatEntity.Rank.MEDICINE && clan.clanUUID.equals(cat.getClanUUID()));
                List<String> names = meds.stream().map(cat -> cat.getCustomName() == null ? "?" : cat.getCustomName().getString()).toList();
                if (meds.size() != 1) return meds.size() + " medicine cats " + names;
                if (KILLED.contains(meds.get(0).getUUID())) return names.get(0) + " was killed but still counts as the medicine cat";
                line("NOTE", p, "medicine cat", names.get(0));
                return null;
            }
            case "nostructures" -> {                            // nostructures <radius>: no surface structure starts near
                int r = Integer.parseInt(arg) >> 4;
                List<String> found = new ArrayList<>();
                int pcx = p.blockPosition().getX() >> 4, pcz = p.blockPosition().getZ() >> 4;
                for (int cx = pcx - r; cx <= pcx + r; cx++) {
                    for (int cz = pcz - r; cz <= pcz + r; cz++) {
                        var chunk = level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS, false);
                        if (chunk == null) continue;
                        chunk.getAllStarts().forEach((st, start) -> {
                            if (start.isValid() && st.step() == net.minecraft.world.level.levelgen.GenerationStep.Decoration.SURFACE_STRUCTURES) found.add(level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(st)
                                    + "@" + start.getBoundingBox().getCenter().toShortString());
                        });
                    }
                }
                return found.isEmpty() ? null : "structures: " + found;
            }
            default -> {
                return Watch.check(p, a[0], arg);              // the walking scenarios' checks (nests, leads, monsters...)
            }
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static long monsterParts(ServerPlayer p) {
        long n = 0;
        for (Entity e : p.serverLevel().getAllEntities()) if (e.getTags().contains(Traffic.PART_TAG)) n++;
        return n;
    }

    private static String state(ServerPlayer p) {
        var clan = Joining.clanOf(p);
        return "stage " + Journey.stage(p) + ", start " + Journey.start(p) + ", clan " + (clan == null ? "-" : clan.name)
                + ", dim " + p.level().dimension().location() + ", at " + p.blockPosition().toShortString()
                + ", duties " + QuestState.duties(p).size() + ", standing " + QuestState.standing(p)
                + ", act " + CampaignData.get(p.getServer().overworld()).act() + ", dialogue " + (Dialog.busy(p) ? "open" : "idle")
                + ", touring " + Tour.touring(p) + ", mode " + p.gameMode.getGameModeForPlayer().getName();
    }

    // places ----------------------------------------------------------------------------------------------------

    private static ClanMap map(ClanWorld w, String name) {
        return switch (name) {
            case "lake" -> w.lake;
            case "forest" -> w.forest;
            case "journey" -> w.journey;
            default -> null;
        };
    }

    private static Landmark landmark(ClanWorld w, String mapName, String kind, int index) {
        ClanMap m = map(w, mapName);
        if (m == null) return null;
        List<Landmark> all = m.landmarks().stream().filter(l -> l.kind().name().equalsIgnoreCase(kind)).toList();
        return index < all.size() ? all.get(index) : null;
    }

    private static CampPlan plan(ClanWorld w, String mapName, String clanKey) {
        BookClans.Info info = BookClans.Info.byKey(clanKey);
        if (info == null) return null;
        Landmark l = mapName.equals("forest") ? Homeland.forestCamp(w, info.clan) : w.lake.camp(info.clan);
        return CampPlan.of(w, l);
    }

    /**
     * /qa tp camp lake|forest &lt;clan&gt; &lt;view&gt;: centre (in the clearing, looking at the way in), gate (just
     * inside the way in, looking across the camp), outside (outside the barrier, looking at the way in), above (high
     * over the camp, looking down), or a den kind (in front of that den, looking at it).
     */
    private static int tpCamp(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ClanWorld w = ClanWorld.current();
        String mapName = StringArgumentType.getString(c, "map"), view = StringArgumentType.getString(c, "view").toUpperCase(Locale.ROOT);
        CampPlan plan = plan(w, mapName, StringArgumentType.getString(c, "clan"));
        if (plan == null) {
            line("FAIL", p, "tp camp", "no such camp");
            return 0;
        }
        double gx = plan.cx + Math.cos(plan.gate) * plan.wallOut, gz = plan.cz + Math.sin(plan.gate) * plan.wallOut;
        double fx, fz, tx, tz, up = 1.6;
        switch (view) {
            case "CENTRE" -> {
                fx = plan.cx - Math.cos(plan.gate) * plan.clearing * 0.4;
                fz = plan.cz - Math.sin(plan.gate) * plan.clearing * 0.4;
                tx = gx;
                tz = gz;
            }
            case "GATE" -> {
                double r = Math.max(plan.clearing - 3, plan.clearing * 0.75);
                fx = plan.cx + Math.cos(plan.gate) * r;
                fz = plan.cz + Math.sin(plan.gate) * r;
                tx = plan.cx;
                tz = plan.cz;
            }
            case "OUTSIDE" -> {
                double r = plan.wallOut + 9;
                fx = plan.cx + Math.cos(plan.gate) * r;
                fz = plan.cz + Math.sin(plan.gate) * r;
                tx = plan.cx;
                tz = plan.cz;
                up = 4;
            }
            case "ABOVE" -> {
                fx = plan.cx + Math.cos(plan.gate) * (plan.wallOut + 6);
                fz = plan.cz + Math.sin(plan.gate) * (plan.wallOut + 6);
                tx = plan.cx;
                tz = plan.cz;
                up = 26;
            }
            default -> {
                CampPlan.Den den;
                try {
                    den = plan.den(CampPlan.DenKind.valueOf(view));
                } catch (IllegalArgumentException e) {
                    den = null;
                }
                if (den == null) {
                    line("FAIL", p, "tp camp " + view, "no such den");
                    return 0;
                }
                double toMid = Math.atan2(plan.cz - den.z(), plan.cx - den.x());
                double r = den.radius() + 4;
                fx = den.x() + Math.cos(toMid) * r;
                fz = den.z() + Math.sin(toMid) * r;
                tx = den.x();
                tz = den.z();
            }
        }
        boolean fly = view.equals("ABOVE") || view.equals("OUTSIDE");
        if (!fly) {
            // on the ground: the open floor nearest the view point, block-centred, as the game picks for arrivals (a
            // raw point by the WindClan hollow's boulder put the player's eyes a hair inside the rock: suffocated)
            BlockPos at = BookClans.safeSpot(p.getServer().overworld(), (int) Math.floor(fx), (int) Math.floor(fz), 5);
            fx = at.getX() + 0.5;
            fz = at.getZ() + 0.5;
        }
        return view(p, fx, fz, tx, tz, up, fly);
    }

    private static int tpLandmark(CommandContext<CommandSourceStack> c, int index) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Landmark l = landmark(ClanWorld.current(), StringArgumentType.getString(c, "map"), StringArgumentType.getString(c, "kind"), index);
        if (l == null) {
            line("FAIL", p, "tp landmark", "no such landmark");
            return 0;
        }
        double back = Math.max(18, l.radius() * 1.3), a = l.facing() + Math.PI;
        return view(p, l.x() + Math.cos(a) * back, l.z() + Math.sin(a) * back, l.x(), l.z(), Math.max(10, l.radius() * 0.5), true);
    }

    private static int tpRoad(CommandContext<CommandSourceStack> c, boolean onRoad) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ClanMap m = map(ClanWorld.current(), StringArgumentType.getString(c, "map"));
        String kind = StringArgumentType.getString(c, "kind");
        Road road = m == null ? null : m.roads().stream().filter(r -> r.kind().name().equalsIgnoreCase(kind)).findFirst().orElse(null);
        if (road == null) {
            line("FAIL", p, "tp road", "no road " + kind);
            return 0;
        }
        double along = road.path().length() * 0.5;
        double[] at = road.path().pointAt(along), ahead = road.path().pointAt(along + 4), look = road.path().pointAt(along + 18);
        double ux = ahead[0] - at[0], uz = ahead[1] - at[1], ul = Math.max(1e-6, Math.hypot(ux, uz));
        ux /= ul;
        uz /= ul;
        double side = onRoad ? road.half() * 0.5 : road.half() + 2.5;         // "on": in the near lane
        double px = at[0] - uz * side, pz = at[1] + ux * side;
        return view(p, px, pz, look[0], look[1], 1.6, false);
    }

    /** Puts the player at (fx, fz), {@code up} blocks above the ground there, looking at the ground at (tx, tz). */
    private static int view(ServerPlayer p, double fx, double fz, double tx, double tz, double up, boolean fly) {
        ServerLevel level = p.getServer().overworld();
        level.getChunk(BlockPos.containing(fx, 0, fz));
        level.getChunk(BlockPos.containing(tx, 0, tz));
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(fx), (int) Math.floor(fz));
        BlockPos stand = fly ? null : BookClans.standable(level, (int) Math.floor(fx), (int) Math.floor(fz));
        if (stand != null) gy = stand.getY();                                 // the floor itself, not just the heightmap's word
        int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(tx), (int) Math.floor(tz));
        double y = gy + (up > 2 ? up : 0);
        double dx = tx - fx, dz = tz - fz, dy = (ty + 1) - (y + 1.62);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        if (fly) {
            p.getAbilities().mayfly = true;
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
        }
        p.teleportTo(level, fx, y + 0.05, fz, yaw, pitch);
        line("NOTE", p, "view", String.format(Locale.ROOT, "at %.0f %.0f %.0f looking at %.0f %d %.0f", fx, y, fz, tx, ty, tz));
        return 1;
    }

    private static Road nearestRoad(ServerPlayer p) {
        ClanWorld w = ClanWorld.current();
        Road best = null;
        double bestD = 400;
        for (ClanMap m : w.maps()) {
            for (Road r : m.roads()) {
                double d = r.path().distance(p.getX(), p.getZ(), bestD);
                if (d < bestD) {
                    bestD = d;
                    best = r;
                }
            }
        }
        return best;
    }

    /** The cat of that rank this player last talked to, if still alive and near: a duty is handed in to whoever gave it. */
    private static final Map<String, UUID> TALKED = new HashMap<>();
    /** Cats "qa kill" has killed: a medicine cat check passes only on a living successor. */
    private static final java.util.Set<UUID> KILLED = new java.util.HashSet<>();

    private static WCatEntity talkedTo(ServerPlayer p, String rank) {
        UUID id = TALKED.get(p.getUUID() + rank);
        if (id == null) return null;
        var e = p.serverLevel().getEntity(id);
        return e instanceof WCatEntity cat && cat.isAlive() && cat.getRank().name().equals(rank) && cat.distanceToSqr(p) < 96 * 96 ? cat : null;
    }

    private static WCatEntity nearestCat(ServerPlayer p, String rank) {
        var clan = Joining.clanOf(p);
        UUID clanId = clan == null ? null : clan.clanUUID;
        // a warrior asked for a duty is never the player's own mentor, who answers with the apprentice path instead
        String mentor = QuestState.mentorName(p);
        var cats = p.serverLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(96),
                        cat -> cat.isAlive() && cat.getRank().name().equals(rank) && (clanId == null || clanId.equals(cat.getClanUUID())));
        return cats.stream().filter(cat -> mentor == null || cat.getCustomName() == null || !cat.getCustomName().getString().startsWith(mentor))
                .min(Comparator.comparingDouble(cat -> cat.distanceToSqr(p)))
                .orElse(cats.stream().min(Comparator.comparingDouble(cat -> cat.distanceToSqr(p))).orElse(null));
    }

    /** Gives the player what their duties ask to be brought and counts their catches done, so hand-ins can be tested. */
    private static int fulfil(ServerPlayer p) {
        int n = 0;
        List<Task> duties = new ArrayList<>(QuestState.duties(p));
        for (Task t : duties) {
            for (Task.Objective o : t.objectives) {
                switch (o.type) {
                    case BRING -> {
                        Item item = null;
                        String key = o.key.split("\\|")[0];             // "a|b|#tag": any one of them will do
                        if (key.startsWith("#")) {
                            var tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(key.substring(1)));
                            for (var h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                                item = h.value();
                                break;
                            }
                        } else {
                            item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(key));
                        }
                        if (item != null) {
                            p.getInventory().add(new ItemStack(item, o.goal));
                            n++;
                        }
                    }
                    case VISIT, CLIMB -> {
                        o.progress = o.goal;
                        n++;
                    }
                    default -> {
                        o.progress = o.goal;
                        n++;
                    }
                }
            }
        }
        QuestState.setDuties(p, duties);
        return n;
    }
}
