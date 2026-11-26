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
        FREEZE(false), FLOOD(false), DROUGHT(false), FIRE(false), FOG(false),
        // the book events in BookEvents: a loose Twoleg dog, rogues at the fresh-kill pile, rats, an adder bite, a
        // kittypet at the border, a leaf-bare prey shortage and an elder's story
        DOGS(true), ROGUES(true), RATS(true), ADDER(false), KITTYPET(false), SHORTAGE(false), STORY(false),
        // a neighbour's patrol hunting over the border: it comes to a player out in their territory (Skirmish)
        SKIRMISH(false);

        final boolean major;

        Kind(boolean major) {
            this.major = major;
        }

        static Kind byName(String s) {
            for (Kind k : values()) if (k.name().equalsIgnoreCase(s) || k.name().replace("_", "").equalsIgnoreCase(s)) return k;
            return null;
        }
    }

    /** Where a Clan's latest event happened, in the words it was first told in. */
    public record Latest(double x, double z, String words, long at) {}

    /** The map shows the latest event for this long (five minutes). */
    static final long NEWS_SHOWN = 20L * 60 * 5;
    private static final Map<UUID, Latest> latest = new HashMap<>();

    /** Something has happened to the Clan with this id, at {@code where}. */
    public static void noticed(UUID clan, net.minecraft.core.BlockPos where, String words, long now) {
        if (clan != null && where != null) latest.put(clan, new Latest(where.getX() + 0.5, where.getZ() + 0.5, words, now));
    }

    /** The Clan's latest event while it is still news, or null. */
    public static Latest latest(UUID clan, long now) {
        Latest l = latest.get(clan);
        return l == null || now - l.at() > NEWS_SHOWN || now < l.at() ? null : l;
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
        NeoForge.EVENT_BUS.addListener(Skirmish::onInteract);
        NeoForge.EVENT_BUS.addListener(Skirmish::beforeDamage);
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            Deaths.onDeath(e);
            if (!e.isCanceled()) {
                Happenings.onCreatureDeath(e);
                BookEvents.onCreatureDeath(e);
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingDamageEvent.Post e) -> {
            Happenings.onDamage(e);
            BookEvents.onDamage(e);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> CampRoutine.stopAll(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> reset());
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (e.getLevel().isClientSide()) return;
            if (Happenings.overdueFoe(e.getEntity()) || Skirmish.stale(e.getEntity())) {
                e.setCanceled(true);                          // an event's fox or eagle whose time ran out while it was unloaded
                return;
            }
            Happenings.onJoin(e.getEntity());
            BookEvents.onJoin(e.getEntity());
            CampRoutine.onJoin(e.getEntity());
        });
    }

    static void reset() {
        nextRoll = -1;
        latest.clear();
        lastEvent.clear();
        activeKind = null;
        activeClan = null;
        activeEntities.clear();
        raiders.clear();
        Deaths.reset();
        Happenings.reset();
        BookEvents.reset();
        Skirmish.reset();
    }

    private static void tick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % 20 != 0) return;
        ServerLevel ow = server.overworld();
        if (ClanWorld.current() == null || !ClanWorld.isClanWorld(ow) || !LifeData.get(ow).created()) return;
        Happenings.everySecond(ow);
        BookEvents.everySecond(ow);
        Weather.everySecond(ow);
        CampRoutine.everySecond(ow);
        Director.everySecond(ow);
        Skirmish.everySecond(ow);
        Hunger.everySecond(ow);
        if (server.getTickCount() % 1200 == 0) for (Clan c : Clan.values()) keepMedicineCat(ow, c);
        if (!EventsConfig.ENABLED.get()) return;
        long now = server.getTickCount();
        RandomSource r = ow.getRandom();
        if (nextRoll < 0) nextRoll = now + Director.interval(ow, interval(r));
        if (now < nextRoll) return;
        Director.beforeRoll(ow);
        nextRoll = now + Director.interval(ow, interval(r));
        Skirmish.roll(ow);                                       // out on the borders, wherever the camps are
        Clan c = pickClan(ow, r);
        if (c == null) return;
        if (Deaths.oldAge(ow, c, r)) {
            lastEvent.put(c, now);
            return;
        }
        Kind k = pickKind(ow, c, r);
        if (k == null) return;
        if (Director.forewarn(ow, c, k)) {                       // a warning first: it happens a little later
            lastEvent.put(c, now);
        } else if (Happenings.run(ow, c, k, r)) {
            lastEvent.put(c, now);
            Director.happened(ow, k);
        }
    }

    private static long interval(RandomSource r) {
        int lo = EventsConfig.MIN_INTERVAL.get(), hi = Math.max(lo, EventsConfig.MAX_INTERVAL.get());
        return 20L * (lo + r.nextInt(hi - lo + 1));
    }

    /**
     * A Clan to visit: its camp must be live (its creatures moving, so near a player) and it must have rested; Clans
     * with players online come first. A camp only loaded at the edge of a player's view has its cats standing still:
     * a badger sent there never came, though the Clan was told it had.
     */
    private static Clan pickClan(ServerLevel ow, RandomSource r) {
        long now = ow.getServer().getTickCount(), rest = Director.rest(ow, 20L * EventsConfig.CLAN_COOLDOWN.get());
        Map<Clan, Integer> weights = new EnumMap<>(Clan.class);
        LifeData life = LifeData.get(ow);
        for (Clan c : Clan.values()) {
            if (!Cats.campActive(ow, c) || now - lastEvent.getOrDefault(c, -rest) < rest) continue;
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
            w.put(Kind.DOGS, 1);
            w.put(Kind.ROGUES, 2);
            if (BookEvents.ratsCanCome(ow)) w.put(Kind.RATS, 1);
        }
        if (!leafBare && BookEvents.hasSnakes()) w.put(Kind.ADDER, 2);   // adders sleep through leaf-bare
        w.put(Kind.KITTYPET, 2);
        if (leafBare) w.put(Kind.SHORTAGE, 3);
        if (BookEvents.playerInCamp(ow, c)) w.put(Kind.STORY, 3);       // a scene: only worth it with someone there to hear it
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
        w.replaceAll((k, base) -> Director.weight(ow, k, base));      // the storyteller's say
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
        LifeData life = LifeData.get(ow);
        // a medicine cat still called "-paw" (chosen before the chosen were given their names) is named now
        for (WCatEntity x : cats) {
            String was = Cats.plainName(x);
            UUID bound = x.getPlayerBoundUuid();                // a player's own cat shape is theirs to name
            if (bound != null && !bound.equals(net.snowteb.warriorcats_events.clan.ClanData.EMPTY_UUID)) continue;
            if (x.getRank() != WCatEntity.Rank.MEDICINE || x.isBaby() || !was.endsWith("paw") || x.getTags().contains("greatjourney_gathering")) continue;
            Happenings.makeWarrior(x, Cats.namesIn(cats), x.getRandom());
            x.setRank(WCatEntity.Rank.MEDICINE);
            x.updateClanCatData();
            Announce.tell(ow, c, x.blockPosition(), com.ninjacat.greatjourney.clanlands.life.Dialog.Line.say(life.leader(c), Info.of(c).colour,
                    was + ", you have served " + Info.of(c).title + " as its medicine cat, and it is time you had your full name. From this moment you will be known as "
                            + Cats.plainName(x) + "."));
            EventNews.record(c, was + " of " + Info.of(c).title + " has earned the medicine cat name " + Cats.plainName(x) + ".");
        }
        if (cats.size() < 4 || hasMedicineCat(ow, life, c, cats)) return;
        com.ninjacat.greatjourney.clanlands.life.Dialog.Line said = chooseMedicineCat(life, c, cats);
        if (said != null) Announce.tell(ow, c, Cats.campCentre(ow, c), said);
    }

    /**
     * Whether the Clan has a medicine cat: one in camp, or one the Clan's own list still has (out for herbs, or away
     * at the half moon: Warrior Cats Events drops a cat from the list when it dies). Looking only in camp, the Clan
     * named a second medicine cat, and a third, whenever its own was out gathering (seen in a world played
     * 2026-11-23). The keeper of the tunnels is a medicine cat too, and no camp's.
     */
    static boolean hasMedicineCat(ServerLevel ow, LifeData life, Clan c, List<WCatEntity> cats) {
        if (cats.stream().anyMatch(x -> x.isAlive() && x.getRank() == WCatEntity.Rank.MEDICINE)) return true;
        UUID id = life.clanId(c);
        net.snowteb.warriorcats_events.clan.ClanData.Clan wc = id == null ? null : net.snowteb.warriorcats_events.clan.ClanData.get(ow).getClan(id);
        if (wc == null) return false;
        for (Map.Entry<UUID, net.snowteb.warriorcats_events.clan.ClanData.ClanCat> e : wc.clanCats.entrySet()) {
            net.snowteb.warriorcats_events.clan.ClanData.ClanCat cc = e.getValue();
            if (!WCatEntity.Rank.MEDICINE.name().equals(cc.catRank) || cc.catName == null) continue;
            if (ow.getEntity(e.getKey()) instanceof WCatEntity x && !x.isAlive()) continue;         // dying this very moment
            String n = cc.catName.getString().replace("♂", "").replace("♀", "").trim();
            if (!n.equalsIgnoreCase(life.tunnelKeeper())) return true;
        }
        return false;
    }

    /**
     * The Clan names its medicine cat from {@code cats}: one of its own apprentices, who takes a full name at once
     * (nobody is left to name them later: they stayed "-paw" for good), else a warrior. Never an apprentice a player
     * is training (theirs was taken from them, unnamed, and counted as trained), nor one still growing (Warrior Cats
     * Events makes that one a warrior when it grows up), nor a player's mentor while another warrior will do.
     * Returns the leader's words, or null if no cat fits.
     */
    public static com.ninjacat.greatjourney.clanlands.life.Dialog.Line chooseMedicineCat(LifeData life, Clan c, List<WCatEntity> cats) {
        java.util.function.Predicate<WCatEntity> warrior = x -> x.isAlive() && !x.isBaby() && x.getRank() == WCatEntity.Rank.WARRIOR && !Cats.isLeader(life, c, x);
        WCatEntity heir = cats.stream().filter(x -> x.isAlive() && !x.isBaby() && x.getRank() == WCatEntity.Rank.APPRENTICE
                        && !com.ninjacat.greatjourney.clanlands.life.quests.Pupils.isPupil(x)).findFirst()
                .or(() -> cats.stream().filter(warrior).filter(x -> !com.ninjacat.greatjourney.clanlands.life.quests.Mentors.mentorsPlayer(x)).findFirst())
                .or(() -> cats.stream().filter(warrior).findFirst()).orElse(null);
        if (heir == null) return null;
        Info info = Info.of(c);
        String was = Cats.plainName(heir);
        if (heir.getRank() == WCatEntity.Rank.APPRENTICE) Happenings.makeWarrior(heir, Cats.namesIn(cats), heir.getRandom());
        heir.setRank(WCatEntity.Rank.MEDICINE);
        heir.updateClanCatData();                         // the Clan's list knows its medicine cat at once (hasMedicineCat reads it)
        String now = Cats.plainName(heir);
        EventNews.record(c, was + " has become " + info.title + "'s medicine cat" + (now.equals(was) ? "." : ", and is " + now + " now."));
        return com.ninjacat.greatjourney.clanlands.life.Dialog.Line.say(life.leader(c), info.colour, info.title + " cannot be without a medicine cat. "
                + was + ", StarClan has chosen you. Learn the herbs, and listen for their voices."
                + (now.equals(was) ? "" : " From this moment you will be known as " + now + "."));
    }

    /** /greatjourney skirmish warn|fight|mercy: what the dialogue's choices run, as the player. */
    private static void skirmishCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        var s = Commands.literal("skirmish");
        for (String what : new String[] {"warn", "fight", "mercy"}) {
            s.then(Commands.literal(what).executes(c -> Skirmish.answer(c.getSource().getPlayerOrException(), what) ? 1 : 0));
        }
        root.then(s);
    }

    /** /greatjourney event &lt;clan&gt; &lt;kind&gt; (operators): makes an event happen now, for testing. */
    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        skirmishCommands(root);
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
        WCatEntity oldest = cats.stream().max(java.util.Comparator.comparingDouble(com.ninjacat.greatjourney.clanlore.traits.Traits::moons)).get();
        Deaths.diePeacefully(oldest);
        return true;
    }
}
