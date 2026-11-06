package com.ninjacat.greatjourney.clanlands.life.route;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.layout.JourneyRoute;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.slf4j.Logger;

/**
 * Those who live along the Great Journey: the Tribe of Rushing Water in their cave behind the waterfall, led by
 * their Stoneteller; Midnight the badger in her sett by the sun-drown-place; old Purdy, a chatty kittypet by the
 * Twoleg garden at the edge of the lake's ThunderClan woods; and the eagles of the mountain pass. Each is settled
 * once, the first time its place is loaded (a saved flag keeps them from being settled again when the chunk reloads),
 * standing on the floors the landmark painters laid; the named ones talk when a player right-clicks them with an
 * empty paw. Midnight, Purdy and the Stoneteller cannot be hurt, so the story never loses them.
 */
public final class RouteLife {
    private RouteLife() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final String TRIBE = "greatjourney_tribe", STONETELLER = "greatjourney_stoneteller",
            MIDNIGHT = "greatjourney_midnight", PURDY = "greatjourney_purdy";
    private static final int TRIBE_COLOUR = 0x9FB4C8, MIDNIGHT_COLOUR = 0xD8D8D8, PURDY_COLOUR = 0xE0B070;

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(RouteLife::logPlaces);
        NeoForge.EVENT_BUS.addListener(RouteLife::chunkLoaded);
        NeoForge.EVENT_BUS.addListener(RouteLife::settleWaiting);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> WAITING.clear());
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, RouteLife::interact);
    }

    private static void logPlaces(ServerStartedEvent e) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return;
        JourneyRoute j = w.journey;
        log("forest exit", j.forestExit());
        log("highway crossing", j.highwayCrossing());
        log("Twoleg farms", j.twolegFarm());
        log("mountain pass", j.mountainPass());
        log("Tribe cave", j.tribeCave());
        log("sun-drown-place", j.sunDrownPlace());
        log("Midnight's sett", j.midnightSett());
        log("lake ridge", j.lakeArrival());
    }

    private static void log(String what, double[] at) {
        ClanWorld w = ClanWorld.current();
        var s = w == null ? null : w.sample(at[0], at[1]);
        String biome = "?";
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            var ow = server.overworld();
            biome = ow.getChunkSource().getGenerator().getBiomeSource()
                    .getNoiseBiome((int) at[0] >> 2, 80 >> 2, (int) at[1] >> 2, ow.getChunkSource().randomState().sampler())
                    .unwrapKey().map(k -> k.location().toString()).orElse("?");
        }
        LOG.info("Clanlands journey {} at {} {} (zone {}, weight {}, biome {})", what, (int) at[0], (int) at[1],
                s == null ? null : s.zone(), s == null ? 0 : s.weight(), biome);
    }

    private static boolean chunkOf(ChunkPos c, double[] at) {
        return c.equals(new ChunkPos(BlockPos.containing(at[0], 0, at[1])));
    }

    private static void chunkLoaded(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        ClanWorld w = ClanWorld.current();
        if (w == null || !ClanWorld.isClanWorld(level)) return;
        ChunkPos c = e.getChunk().getPos();
        JourneyRoute j = w.journey;
        RouteData d = RouteData.get(level);
        if (!d.tribe && chunkOf(c, j.tribeCave())) queue(level, c, "tribe", () -> {
            d.tribe = true;
            settleTribe(level, w);
        });
        if (!d.midnight && chunkOf(c, j.midnightSett())) queue(level, c, "midnight", () -> {
            d.midnight = true;
            settleMidnight(level, w);
        });
        if (!d.purdy && chunkOf(c, purdySpot(w))) queue(level, c, "purdy", () -> {
            d.purdy = true;
            settlePurdy(level, w);
        });
        if (!d.eagles && chunkOf(c, j.mountainPass())) queue(level, c, "eagles", () -> {
            d.eagles = true;
            settleEagles(level, w);
        });
    }

    private record Settle(ServerLevel level, ChunkPos chunk, String who, Runnable run) {}

    private static final java.util.List<Settle> WAITING = new java.util.ArrayList<>();

    /**
     * Settles a creature once its chunk's entities have loaded. Adding one the moment the chunk loads (its entities
     * still on their way from disk) lost it: the playtest harness found Midnight and the Tribe marked as settled
     * but nowhere in the world. The saved flag is set only when they are really added.
     */
    private static void queue(ServerLevel level, ChunkPos chunk, String who, Runnable run) {
        for (Settle s : WAITING) if (s.who.equals(who)) return;
        LOG.debug("Clanlands journey: {} waits for chunk {} to load its entities", who, chunk);
        WAITING.add(new Settle(level, chunk, who, run));
    }

    private static void settleWaiting(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (WAITING.isEmpty()) return;
        if (e.getServer().getTickCount() % 200 == 0)
            for (Settle s : WAITING) LOG.debug("Clanlands journey: {} still waiting, entities loaded {}", s.who, s.level.areEntitiesLoaded(s.chunk.toLong()));
        WAITING.removeIf(s -> {
            if (!s.level.areEntitiesLoaded(s.chunk.toLong())) return false;
            s.run.run();
            RouteData.get(s.level).setDirty();
            LOG.info("Clanlands journey: {} settled in chunk {}", s.who, s.chunk);
            return true;
        });
    }

    /**
     * The ground as it stands where the chunk is loaded; elsewhere the planned ground (an unloaded chunk's heightmap
     * reads as the bottom of the world, which would drop a creature into the void).
     */
    private static int ground(ServerLevel level, ClanWorld w, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return planned(w, x, z);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    /** The planned ground height the landmark painters build on (they lay cave and sett floors from it). */
    private static int planned(ClanWorld w, double x, double z) {
        return (int) Math.round(w.sample(x, z).height());
    }

    /** Tribe names follow the books' pattern: what they are, and where or of what. */
    static List<String> tribeNames(long seed, int count) {
        String[] first = {"Talons", "Brook", "Crag", "Night", "Rain", "Mist", "Pebble", "Feather", "Splash", "Cloud",
                "Snow", "Stone", "Shadow", "Bird", "Moss", "Wind", "Dawn", "Frost"};
        String[] where = {"Small Fish Swim", "Eagles Nest", "Water Falls", "Moss Grows Thick", "the Sun Rises",
                "the Wind Whistles", "Rocks Tumble", "Frost Bites", "Ice Glitters", "the River Bends"};
        String[] of = {"Swooping Eagle", "No Stars", "Falling Water", "Whispering Pines", "Many Echoes", "Cold Sky",
                "Grey Cloud", "Shining Ice", "Rushing Water", "Distant Thunder"};
        RandomSource r = RandomSource.create(seed ^ 0x7A1BE5L);
        Set<String> used = new HashSet<>();
        List<String> out = new ArrayList<>();
        while (out.size() < count) {
            String a = first[r.nextInt(first.length)];
            String n = r.nextBoolean() ? a + " Where " + where[r.nextInt(where.length)] : a + " of " + of[r.nextInt(of.length)];
            if (used.add(a)) out.add(n);
        }
        return out;
    }

    private static void settleTribe(ServerLevel level, ClanWorld w) {
        JourneyRoute j = w.journey;
        double[] f = j.tribeFacing();
        double cx = j.tribeCave()[0] - f[0] * 22, cz = j.tribeCave()[1] - f[1] * 22;
        int floor = planned(w, j.tribeCave()[0] + f[0] * 14, j.tribeCave()[1] + f[1] * 14) + 1;
        BlockPos centre = BlockPos.containing(cx, floor, cz);
        RandomSource r = RandomSource.create(w.seed ^ 0x5701E7L);
        List<String> names = tribeNames(w.seed, 7);
        for (int i = 0; i < 8; i++) {
            double a = i / 8.0 * Math.PI * 2;
            BlockPos at = BlockPos.containing(cx + Math.cos(a) * 8, floor, cz + Math.sin(a) * 8);
            String name = i == 0 ? "Stoneteller" : names.get(i - 1);
            WCatEntity cat = cat(level, at, name, i == 0 ? 0 : r.nextInt(2), i == 0 ? 90 : 14 + r.nextInt(40), r);
            if (cat == null) continue;
            cat.addTag(TRIBE);
            if (i == 0) {
                cat.addTag(STONETELLER);
                cat.setInvulnerable(true);
            }
            cat.wanderCenter = centre;
            cat.setHomePosition(at);
        }
    }

    private static WCatEntity cat(ServerLevel level, BlockPos at, String name, int gender, int moons, RandomSource r) {
        WCatEntity cat = ModEntities.WCAT.get().create(level);
        if (cat == null) return null;
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
        cat.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setGender(gender);
        cat.setCustomName(Component.literal(name + (gender == 0 ? " ♂" : " ♀")));
        cat.setAgeInMoons(Math.min(com.ninjacat.greatjourney.clanlands.life.BookClans.ADULT_MOONS, moons));   // WCE draws past 12 moons giant and headless
        cat.setPersistenceRequired();
        com.ninjacat.greatjourney.clanlands.life.BookLooks.routeCat(cat, name);
        level.addFreshEntity(cat);
        cat.mode = WCatEntity.CatMode.WANDER;
        return cat;
    }

    private static void settleMidnight(ServerLevel level, ClanWorld w) {
        JourneyRoute j = w.journey;
        double[] s = j.midnightSett(), sea = j.seaward();
        double x = s[0] - sea[0] * 8, z = s[1] - sea[1] * 8;
        int y = planned(w, s[0] + sea[0] * 6, s[1] + sea[1] * 6) + 1;
        Mob badger = ModEntities.BADGER.get().create(level);
        if (badger == null) return;
        badger.moveTo(x, y, z, (float) Math.toDegrees(Math.atan2(-sea[0], sea[1])), 0);
        badger.setCustomName(Component.literal("Midnight"));
        badger.setCustomNameVisible(true);
        badger.setNoAi(true);                       // she stays in her sett, and is never hostile
        badger.setInvulnerable(true);
        badger.setPersistenceRequired();
        badger.addTag(MIDNIGHT);
        level.addFreshEntity(badger);
    }

    /** Where Purdy sits: by the fence of his Twoleg garden, toward the woods. */
    private static double[] purdySpot(ClanWorld w) {
        var home = w.lake.kittypetHome();
        double ang = home.facing();
        return new double[] {home.x() + Math.sin(ang) * 18, home.z() - Math.cos(ang) * 18};
    }

    private static void settlePurdy(ServerLevel level, ClanWorld w) {
        double[] spot = purdySpot(w);
        double x = spot[0], z = spot[1];
        BlockPos at = new BlockPos((int) Math.floor(x), ground(level, w, x, z), (int) Math.floor(z));
        WCatEntity purdy = cat(level, at, "Purdy", 0, 150, RandomSource.create(w.seed ^ 0x9E7D1L));
        if (purdy == null) return;
        purdy.addTag(PURDY);
        purdy.setInvulnerable(true);
        purdy.wanderCenter = at;
        purdy.setHomePosition(at);
    }

    private static void settleEagles(ServerLevel level, ClanWorld w) {
        double[] p = w.journey.mountainPass();
        RandomSource r = RandomSource.create((long) (p[0] * 31 + p[1]));
        for (int i = 0; i < 3; i++) {
            Mob eagle = ModEntities.EAGLE.get().create(level);
            if (eagle == null) continue;
            double x = p[0] + (r.nextDouble() - 0.5) * 160, z = p[1] + (r.nextDouble() - 0.5) * 160;
            eagle.moveTo(x, ground(level, w, x, z) + 24 + r.nextInt(12), z, r.nextFloat() * 360, 0);
            eagle.setPersistenceRequired();
            level.addFreshEntity(eagle);
        }
    }

    /** Midnight, Purdy and the Tribe talk to whoever right-clicks them with an empty paw (not sneaking). */
    private static void interact(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!p.getMainHandItem().isEmpty() || p.isShiftKeyDown()) return;
        Entity t = e.getTarget();
        List<Line> lines = null;
        if (t.getTags().contains(MIDNIGHT)) lines = midnight(p);
        else if (t.getTags().contains(PURDY)) lines = purdy(p);
        else if (t.getTags().contains(STONETELLER)) lines = stoneteller(p, t);
        else if (t.getTags().contains(TRIBE)) lines = tribe(p, t);
        if (lines == null) return;
        lines = lines.stream().map(l -> l.speaker() == null ? l : l.speakerEntity(t)).toList();
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (Dialog.busy(p)) return;
        Dialog.play(p, lines);
    }

    private static String name(Entity e) {
        return e.getCustomName() == null ? "?" : e.getCustomName().getString().replace(" ♂", "").replace(" ♀", "");
    }

    private static List<Line> midnight(ServerPlayer p) {
        String n = "Midnight";
        return List.of(
                Line.narrate("A badger looks up from the dark of her sett, and her small eyes shine with something like a smile."),
                Line.say(n, MIDNIGHT_COLOUR, "Come in, come in, little cat. Not afraid of Midnight? Good. Is no need. Badgers not all the same, as cats not all the same."),
                Line.say(n, MIDNIGHT_COLOUR, "Here the sun drowns every night in the great water, and every night it comes back. So, too, will your Clans."),
                Line.say(n, MIDNIGHT_COLOUR, "Old forest is ending. Twolegs come with their monsters. But listen: a new home waits, where the stars shine on still water."),
                Line.say(n, MIDNIGHT_COLOUR, "Follow the setting sun past the mountains. Trust your warrior ancestors. They walk with you, always."));
    }

    private static List<Line> purdy(ServerPlayer p) {
        String n = "Purdy";
        return List.of(
                Line.narrate("An old tabby with a frayed collar stretches on the fence post, delighted to have company."),
                Line.say(n, PURDY_COLOUR, "Well, well! A visitor! Don't get many of you wild sorts stopping to chat with old Purdy."),
                Line.say(n, PURDY_COLOUR, "Did I ever tell you about the time I chased a rat clear across three gardens? Biggest rat you ever saw. Size of a dog, near enough."),
                Line.say(n, PURDY_COLOUR, "If you're looking for the lake, it's just through those trees, can't miss it. The woods are thick with Clan cats, mind. ThunderClan, they call themselves."),
                Line.say(n, PURDY_COLOUR, "Come back any time. I'll be right here. Where else would I be?"));
    }

    private static List<Line> stoneteller(ServerPlayer p, Entity t) {
        String n = name(t);
        return List.of(
                Line.narrate("Behind the roar of the waterfall, a lean grey cat watches you from a stone ledge. The others call him the Teller of the Pointed Stones."),
                Line.say(n, TRIBE_COLOUR, "You have come through the mountains. Few strangers find the cave behind the falling water."),
                Line.say(n, TRIBE_COLOUR, "We are the Tribe of Rushing Water. Our ancestors are the Tribe of Endless Hunting, as yours walk in StarClan."),
                Line.say(n, TRIBE_COLOUR, "Rest here, and share our prey. The mountains are cruel to those who do not know them, and the eagles are always hungry."),
                Line.say(n, TRIBE_COLOUR, "When you go on, follow the path the sun takes. The way down to the still water lies beyond the last peak."));
    }

    private static List<Line> tribe(ServerPlayer p, Entity t) {
        String n = name(t);
        String[] says = {
                "Watch the sky in the pass. An eagle can lift a cat as easily as a mouse.",
                "Prey is scarce among the rocks. We hunt as one, or we go hungry.",
                "Mind the wet stones by the falls. The water has taken careless paws before.",
                "The Stoneteller speaks with the Tribe of Endless Hunting. Listen when they speak."};
        return List.of(Line.say(n, TRIBE_COLOUR, says[Math.floorMod(t.getUUID().hashCode(), says.length)]));
    }
}
