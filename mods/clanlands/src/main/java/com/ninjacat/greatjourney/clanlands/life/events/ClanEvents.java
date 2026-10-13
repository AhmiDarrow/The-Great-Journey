package com.ninjacat.greatjourney.clanlands.life.events;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.snowteb.warriorcats_events.attachments.ModAttachments;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Random events in the lake Clans' lives. Every few minutes a Clan is picked (Clans with players online, and camps
 * that are loaded, are picked far more often) and something happens: an attack, an injury, sickness, kits, a
 * ceremony, a hunting patrol, a lost kit. Deaths come from {@link Deaths}. Events need the Clan's camp loaded, so
 * the cats can take part; at most one big event (badger, raid, lost kit) runs at a time.
 */
public final class ClanEvents {
    private ClanEvents() {}

    public enum Kind {
        BADGER(true), PREDATOR(false), RAID(true), INJURY(false), SICKNESS(false), KITS(false), CEREMONY(false),
        HUNT(false), LOST_KIT(true), OLD_AGE(false),
        FREEZE(false), FLOOD(false), DROUGHT(false), FIRE(false), FOG(false);

        final boolean major;

        Kind(boolean major) {
            this.major = major;
        }

        static Kind byName(String s) {
            for (Kind k : values()) if (k.name().equalsIgnoreCase(s) || k.name().replace("_", "").equalsIgnoreCase(s)) return k;
            return null;
        }
    }

    private static long nextRoll = -1;
    private static final Map<Clan, Long> lastEvent = new EnumMap<>(Clan.class);
    /** The running big event, if any: its kind, Clan and the creatures taking part. */
    static Kind activeKind;
    static Clan activeClan;
    static long activeUntil;
    static final Set<UUID> activeEntities = new HashSet<>();
    static final Map<UUID, Clan> raiders = new HashMap<>();

    public static void init(IEventBus modBus) {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, EventsConfig.SPEC, "clanlands-events-server.toml");
        NeoForge.EVENT_BUS.addListener(ClanEvents::tick);
        Weather.init();
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            Deaths.onDeath(e);
            if (!e.isCanceled()) Happenings.onCreatureDeath(e);
        });
        NeoForge.EVENT_BUS.addListener((LivingDamageEvent.Post e) -> Happenings.onDamage(e));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> reset());
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (!e.getLevel().isClientSide()) Happenings.onJoin(e.getEntity());
        });
    }

    static void reset() {
        nextRoll = -1;
        lastEvent.clear();
        activeKind = null;
        activeClan = null;
        activeEntities.clear();
        raiders.clear();
        Deaths.reset();
        Happenings.reset();
    }

    private static void tick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % 20 != 0) return;
        ServerLevel ow = server.overworld();
        if (ClanWorld.current() == null || !ClanWorld.isClanWorld(ow) || !LifeData.get(ow).created()) return;
        Happenings.everySecond(ow);
        Weather.everySecond(ow);
        if (server.getTickCount() % 1200 == 0) for (Clan c : Clan.values()) keepMedicineCat(ow, c);
        if (!EventsConfig.ENABLED.get()) return;
        long now = server.getTickCount();
        RandomSource r = ow.getRandom();
        if (nextRoll < 0) nextRoll = now + interval(r);
        if (now < nextRoll) return;
        nextRoll = now + interval(r);
        Clan c = pickClan(ow, r);
        if (c == null) return;
        if (Deaths.oldAge(ow, c, r)) {
            lastEvent.put(c, now);
            return;
        }
        Kind k = pickKind(ow, c, r);
        if (k != null && Happenings.run(ow, c, k, r)) lastEvent.put(c, now);
    }

    private static long interval(RandomSource r) {
        int lo = EventsConfig.MIN_INTERVAL.get(), hi = Math.max(lo, EventsConfig.MAX_INTERVAL.get());
        return 20L * (lo + r.nextInt(hi - lo + 1));
    }

    /** A Clan to visit: its camp must be loaded and it must have rested; Clans with players online come first. */
    private static Clan pickClan(ServerLevel ow, RandomSource r) {
        long now = ow.getServer().getTickCount(), rest = 20L * EventsConfig.CLAN_COOLDOWN.get();
        Map<Clan, Integer> weights = new EnumMap<>(Clan.class);
        LifeData life = LifeData.get(ow);
        for (Clan c : Clan.values()) {
            if (!Cats.campLoaded(ow, c) || now - lastEvent.getOrDefault(c, -rest) < rest) continue;
            int w = 1;
            UUID id = life.clanId(c);
            for (ServerPlayer p : ow.getServer().getPlayerList().getPlayers()) {
                if (id != null && id.equals(p.getData(ModAttachments.PLAYER_WCE_DATA.get()).getCurrentClanUUID())) w += 4;
            }
            weights.put(c, w);
        }
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return null;
        int roll = r.nextInt(total);
        for (Map.Entry<Clan, Integer> e : weights.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) return e.getKey();
        }
        return null;
    }

    private static Kind pickKind(ServerLevel ow, Clan c, RandomSource r) {
        boolean leafBare = Seasons.isLeafBare(ow);
        Map<Kind, Integer> w = new EnumMap<>(Kind.class);
        boolean majorFree = activeKind == null;
        if (majorFree) {
            w.put(Kind.BADGER, 2);
            w.put(Kind.RAID, 2);
            w.put(Kind.LOST_KIT, 1);
        }
        w.put(Kind.PREDATOR, 3);
        w.put(Kind.INJURY, 4);
        w.put(Kind.SICKNESS, leafBare ? 6 : 2);
        w.put(Kind.KITS, leafBare ? 1 : 3);
        w.put(Kind.CEREMONY, 3);
        w.put(Kind.HUNT, (leafBare ? 5 : 3) + (Weather.frozen(ow) ? 4 : 0));
        int weather = EventsConfig.WEATHER_WEIGHT.get();
        if (weather > 0) {
            for (Kind k : new Kind[] {Kind.FREEZE, Kind.FLOOD, Kind.DROUGHT, Kind.FIRE, Kind.FOG}) {
                if (Weather.canStart(ow, k)) w.put(k, k == Kind.FIRE ? Math.max(1, weather / 2) : weather);
            }
        }
        int total = w.values().stream().mapToInt(Integer::intValue).sum();
        int roll = r.nextInt(total);
        for (Map.Entry<Kind, Integer> e : w.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) return e.getKey();
        }
        return null;
    }

    static boolean isRaider(Entity e) {
        return raiders.containsKey(e.getUUID());
    }

    static void raiderGone(Entity e) {
        raiders.remove(e.getUUID());
        activeEntities.remove(e.getUUID());
    }

    /**
     * A Clan whose medicine cat has gone to StarClan names another, as the books' Clans always do: an apprentice if
     * there is one, else the gentlest warrior. Without this a Clan lost its medicine duties for good (the playtest
     * harness saw an old-age death take ThunderClan's only medicine cat). A medicine cat's death names the heir at
     * once ({@link Deaths}); this once-a-minute look is the safety net, and it waits until the camp's cats have all
     * loaded: the land loads first, and a camp half full of cats (the medicine cat's den not in yet) would otherwise
     * have been given a second medicine cat.
     */
    static void keepMedicineCat(ServerLevel ow, Clan c) {
        if (!Cats.campLoaded(ow, c) || !Cats.campCatsLoaded(ow, c)) return;
        List<WCatEntity> cats = Cats.atCamp(ow, c);
        if (cats.size() < 4 || cats.stream().anyMatch(x -> x.getRank() == WCatEntity.Rank.MEDICINE)) return;
        LifeData life = LifeData.get(ow);
        WCatEntity heir = nameMedicineCat(life, c, cats);
        if (heir == null) return;
        Announce.tell(ow, c, Cats.campCentre(ow, c), medicineChosen(life, c, heir));
    }

    /** Makes one of {@code cats} the Clan's medicine cat (an apprentice if there is one, else a warrior who is not the leader); null if none fits. */
    static WCatEntity nameMedicineCat(LifeData life, Clan c, List<WCatEntity> cats) {
        WCatEntity heir = cats.stream().filter(x -> x.isAlive() && x.getRank() == WCatEntity.Rank.APPRENTICE).findFirst()
                .orElse(cats.stream().filter(x -> x.isAlive() && x.getRank() == WCatEntity.Rank.WARRIOR && !Cats.isLeader(life, c, x)).findFirst().orElse(null));
        if (heir != null) heir.setRank(WCatEntity.Rank.MEDICINE);
        return heir;
    }

    static com.ninjacat.greatjourney.clanlands.life.Dialog.Line medicineChosen(LifeData life, Clan c, WCatEntity heir) {
        Info info = Info.of(c);
        return com.ninjacat.greatjourney.clanlands.life.Dialog.Line.say(life.leader(c), info.colour, info.title + " cannot be without a medicine cat. "
                + Cats.plainName(heir) + ", StarClan has chosen you. Learn the herbs, and listen for their voices.");
    }

    /** /greatjourney event &lt;clan&gt; &lt;kind&gt; (operators): makes an event happen now, for testing. */
    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("event").requires(s -> s.hasPermission(2))
                .then(Commands.argument("clan", StringArgumentType.word())
                        .then(Commands.argument("kind", StringArgumentType.word()).executes(ctx -> {
                            ServerLevel ow = ctx.getSource().getServer().overworld();
                            Info info = Info.byKey(StringArgumentType.getString(ctx, "clan"));
                            Kind k = Kind.byName(StringArgumentType.getString(ctx, "kind"));
                            if (info == null || k == null) {
                                ctx.getSource().sendFailure(Component.literal("Usage: /greatjourney event <thunderclan|windclan|riverclan|shadowclan> <"
                                        + String.join("|", java.util.Arrays.stream(Kind.values()).map(x -> x.name().toLowerCase(Locale.ROOT)).toList()) + ">"));
                                return 0;
                            }
                            if (!Cats.campLoaded(ow, info.clan)) {
                                ctx.getSource().sendFailure(Component.literal(info.title + "'s camp is not loaded; go there first."));
                                return 0;
                            }
                            if (Weather.isWeather(k) && Weather.running(ow) != null) {
                                ctx.getSource().sendFailure(Component.literal("The " + Weather.running(ow) + " is still going on: one weather event runs at a time."));
                                return 0;
                            }
                            if (k.major && activeKind != null) {
                                ctx.getSource().sendFailure(Component.literal("A " + activeKind.name().toLowerCase(Locale.ROOT) + " event is still running."));
                                return 0;
                            }
                            boolean ok = k == Kind.OLD_AGE ? forceOldAge(ow, info.clan)
                                    : Weather.isWeather(k) ? Weather.start(ow, info.clan, k, ow.getRandom(), true)
                                    : Happenings.run(ow, info.clan, k, ow.getRandom());
                            if (ok) ctx.getSource().sendSuccess(() -> Component.literal("Started " + k.name().toLowerCase(Locale.ROOT) + " for " + info.title), true);
                            else ctx.getSource().sendFailure(Component.literal("Nothing could happen: " + info.title + " has no cat that fits."));
                            return ok ? 1 : 0;
                        }))));
    }

    private static boolean forceOldAge(ServerLevel ow, Clan c) {
        List<WCatEntity> cats = Cats.atCamp(ow, c).stream()
                .filter(x -> x.getRank() != WCatEntity.Rank.KIT && !Cats.isLeader(LifeData.get(ow), c, x)).toList();
        if (cats.isEmpty()) return false;
        WCatEntity oldest = cats.stream().max(java.util.Comparator.comparingDouble(WCatEntity::getAgeInMoons)).get();
        Deaths.diePeacefully(oldest);
        return true;
    }
}
