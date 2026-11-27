package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.LakeLands;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Weather from the books, as Clan events: the lake's edge freezing in leaf-bare, RiverClan's marsh flooding after
 * newleaf rain, the shallows drying to mud in a greenleaf drought, lightning setting a tree alight in a storm, and
 * morning mist that spoils the hunting. One weather event runs at a time. Everything it changes is saved in
 * {@link WeatherData} and put back when it ends (a fire's scar stays, as in the books).
 */
final class Weather {
    private Weather() {}

    private static final int MAX_CHANGES = 30000;
    private static final long FLOOD_TICKS = 20L * 60 * 10, DROUGHT_TICKS = 20L * 60 * 15, FIRE_TICKS = 20L * 60 * 2,
            FOG_TICKS = 20L * 60 * 5, FREEZE_CAP_TICKS = 20L * 60 * 60 * 2;

    static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            ServerLevel ow = e.getServer().overworld();
            if (ClanWorld.current() == null || !ClanWorld.isClanWorld(ow)) return;
            WeatherData d = WeatherData.get(ow);
            if ("fire".equals(d.kind)) containFire(ow, d);
        });
    }

    static boolean isWeather(ClanEvents.Kind k) {
        return k == ClanEvents.Kind.FREEZE || k == ClanEvents.Kind.FLOOD || k == ClanEvents.Kind.DROUGHT
                || k == ClanEvents.Kind.FIRE || k == ClanEvents.Kind.FOG;
    }

    static boolean frozen(ServerLevel ow) {
        return "freeze".equals(WeatherData.get(ow).kind);
    }

    static boolean foggy(ServerLevel ow) {
        return "fog".equals(WeatherData.get(ow).kind);
    }

    /** Whether this weather could start now (season, sky, nothing else running, enabled). */
    static boolean canStart(ServerLevel ow, ClanEvents.Kind k) {
        WeatherData d = WeatherData.get(ow);
        if (d.active() || !d.changes.isEmpty()) return false;
        Seasons.Season s = Seasons.season(ow);
        return switch (k) {
            case FREEZE -> EventsConfig.FREEZE.get() && s == Seasons.Season.LEAF_BARE;
            case FLOOD -> EventsConfig.FLOOD.get() && s == Seasons.Season.NEWLEAF && ow.isRaining();
            case DROUGHT -> EventsConfig.DROUGHT.get() && s == Seasons.Season.GREENLEAF && !ow.isRaining();
            case FIRE -> EventsConfig.FIRE.get() && ow.isThundering();
            case FOG -> EventsConfig.FOG.get() && !ow.isRaining() && morning(ow);
            default -> false;
        };
    }

    private static boolean morning(Level level) {
        long t = level.getDayTime() % 24000L;
        return t > 22500 || t < 2500;
    }

    /** The weather event still running or still being undone, or null: only one at a time. */
    static String running(ServerLevel ow) {
        WeatherData d = WeatherData.get(ow);
        return d.active() || !d.changes.isEmpty() ? d.kind : null;
    }

    /** Starts a weather event for a Clan; {@code force} skips the season and sky checks (for testing). */
    static boolean start(ServerLevel ow, Clan c, ClanEvents.Kind k, RandomSource r, boolean force) {
        WeatherData d = WeatherData.get(ow);
        if (d.active() || !d.changes.isEmpty()) return false;
        if (!force && !canStart(ow, k)) return false;
        List<WCatEntity> cats = Cats.atCamp(ow, c);
        Info info = Info.of(c);
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        String medicine = Cats.speaker(cats, WCatEntity.Rank.MEDICINE, deputy);
        BlockPos camp = Cats.campCentre(ow, c);
        d.kind = k.name().toLowerCase(Locale.ROOT);
        d.clan = c.name();
        d.forced = force;
        boolean ok = switch (k) {
            case FREEZE -> {
                d.until = ow.getGameTime() + FREEZE_CAP_TICKS;
                freezeNearPlayers(ow, d);
                snowCamps(ow, d, r);
                Announce.tell(ow, c, camp,
                        Line.say(deputy, info.colour, "The lake's frozen at the edges. Careful out there: the ice is thin, and it won't hold a cat for long."),
                        Line.say(medicine, info.colour, "Leaf-bare's bitten deep. Keep the kits in the nursery and the elders warm, and watch for coughing."),
                        Line.narrate("Prey is scarce under the snow. Every piece of fresh-kill matters now."));
                yield true;
            }
            case FLOOD -> {
                if (c != Clan.RIVER) {
                    d.clan = Clan.RIVER.name();
                    c = Clan.RIVER;
                    info = Info.of(c);
                    cats = Cats.atCamp(ow, c);
                    deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
                    camp = Cats.campCentre(ow, c);
                }
                d.until = ow.getGameTime() + FLOOD_TICKS;
                int n = flood(ow, d, camp);
                if (n == 0) yield false;
                Announce.tell(ow, c, camp,
                        Line.say(deputy, info.colour, "The streams have burst their banks! Water's all over the marsh."),
                        Line.say(deputy, info.colour, "Get the kits and elders up to the high ground in camp. Nobody swims after the current, not even you warriors!"),
                        Line.narrate("The flood will go down when the rain eases."));
                yield true;
            }
            case DROUGHT -> {
                d.until = ow.getGameTime() + DROUGHT_TICKS;
                int n = drought(ow, d, r);
                Announce.tell(ow, c, camp,
                        Line.say(deputy, info.colour, "No rain for days. The shallows are drying to mud and the streams are running thin."),
                        Line.say(medicine, info.colour, "Drink when you can, and bring wet moss back for the elders and the kits. Watch them in the heat."),
                        Line.narrate(n > 0 ? "The lake's edge has shrunk back from the shore." : "The land is parched; even the lake seems lower."));
                yield true;
            }
            case FIRE -> {
                BlockPos tree = findTree(ow, c, r);
                if (tree == null) yield false;
                d.until = ow.getGameTime() + FIRE_TICKS;
                d.fireCentre = tree.asLong();
                d.fireRadius = 6;
                LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(ow);
                if (bolt != null) {
                    bolt.moveTo(tree.getX() + 0.5, tree.getY(), tree.getZ() + 0.5);
                    bolt.setVisualOnly(true);
                    ow.addFreshEntity(bolt);
                }
                ignite(ow, tree);
                moveToSafety(ow, d, c, cats);
                Announce.tell(ow, c, camp,
                        Line.narrate("Lightning cracks across the sky, and a tree in " + info.title + "'s territory bursts into flame!"),
                        Line.say(deputy, info.colour, "Fire! Get the kits out of camp! Head for the lake!"),
                        Line.say(deputy, info.colour, "Elders first. Warriors, stay with the queens. Nobody goes near the smoke."));
                Announce.tell(ow, c, tree, Line.narrate("Smoke pours from the burning tree to the " + Cats.direction(camp.getX(), camp.getZ(), tree.getX(), tree.getZ()) + " of camp."));
                yield true;
            }
            case FOG -> {
                d.until = ow.getGameTime() + FOG_TICKS;
                Announce.tell(ow, c, camp,
                        Line.narrate("Mist lies thick over " + info.land + ". You can barely see the next tree."),
                        Line.say(deputy, info.colour, "The dawn patrol has lost the scent in this fog. Stay close to your Clanmates and keep your ears open."));
                yield true;
            }
            default -> false;
        };
        if (!ok) {
            d.kind = "";
            d.clan = "";
        }
        d.setDirty();
        return ok;
    }

    /** Once a second: freezing spreads near players, events end when their time or weather is over, undoing waits. */
    static void everySecond(ServerLevel ow) {
        WeatherData d = WeatherData.get(ow);
        if (d.active()) {
            long now = ow.getGameTime();
            boolean over = now > d.until;
            switch (d.kind) {
                case "freeze" -> {
                    over |= !d.forced && !Seasons.isLeafBare(ow);
                    if (!over && ow.getServer().getTickCount() % 600 == 0) freezeNearPlayers(ow, d);
                }
                case "flood" -> over |= !d.forced && !ow.isRaining();
                case "drought" -> over |= !d.forced && ow.isRaining();
                default -> { }
            }
            // a fire is put out only where it can be reached: with its land unloaded it waits, so no flame is left behind
            if (over && d.kind.equals("fire") && d.fireCentre != 0) {
                BlockPos at = BlockPos.of(d.fireCentre);
                int reach = d.fireRadius + 6;
                over = ow.hasChunksAt(at.offset(-reach, 0, -reach), at.offset(reach, 0, reach));
            }
            if (over) end(ow, d);
        }
        if (!d.changes.isEmpty() && !d.active()) {
            if (d.restoreLoaded(ow) == 0) d.restoring = false;
        }
        if (!d.moved.isEmpty() && !d.active()) returnHome(ow, d);
    }

    private static void end(ServerLevel ow, WeatherData d) {
        String kind = d.kind;
        Clan c = Clan.valueOf(d.clan);
        Info info = Info.of(c);
        BlockPos camp = Cats.campCentre(ow, c);
        List<WCatEntity> cats = Cats.atCamp(ow, c);
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        switch (kind) {
            case "freeze" -> Announce.tell(ow, c, camp, Line.say(deputy, info.colour, "The ice is breaking up. Newleaf can't be far off now."));
            case "flood" -> Announce.tell(ow, c, camp, Line.say(deputy, info.colour, "The water's going down. Check the nursery and the dens for anything washed away."));
            case "drought" -> Announce.tell(ow, c, camp, Line.say(deputy, info.colour, "Rain at last! StarClan has heard us."));
            case "fire" -> {
                putOutFire(ow, d);
                returnHome(ow, d);
                Announce.tell(ow, c, camp, Line.say(deputy, info.colour, "The fire's out. It's safe to go home. Thank StarClan nobody was hurt."));
            }
            case "fog" -> Announce.tell(ow, c, camp, Line.narrate("The mist lifts, and the territory shines in the sun."));
            default -> { }
        }
        d.kind = "";
        d.clan = "";
        d.restoring = !d.changes.isEmpty();
        d.setDirty();
        d.restoreLoaded(ow);
    }

    // ---- freeze ------------------------------------------------------------------------------------------------

    /** Freezes the lake's shallow edge (open water within 6 blocks of the shore) around every player near the lake. */
    private static void freezeNearPlayers(ServerLevel ow, WeatherData d) {
        LakeLands lake = ClanWorld.current().lake;
        for (ServerPlayer p : ow.getServer().getPlayerList().getPlayers()) {
            if (p.level() != ow || !lake.covers(p.getX(), p.getZ())) continue;
            for (int x = p.getBlockX() - 64; x <= p.getBlockX() + 64 && d.changes.size() < MAX_CHANGES; x++) {
                for (int z = p.getBlockZ() - 64; z <= p.getBlockZ() + 64; z++) {
                    BlockPos top = edgeWater(ow, lake, x, z, 6);
                    if (top != null && !d.changes.containsKey(top.asLong())) d.change(ow, top, Blocks.ICE.defaultBlockState());
                }
            }
        }
    }

    /**
     * The surface water block of a lake column within {@code reach} blocks of dry shore, or null. Only loaded
     * chunks, only still source water at the top, only shallow water near land.
     */
    private static BlockPos edgeWater(ServerLevel ow, LakeLands lake, int x, int z, int reach) {
        BlockPos probe = new BlockPos(x, 63, z);
        if (!ow.isLoaded(probe)) return null;
        int surface = ow.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos top = new BlockPos(x, surface, z);
        if (!ow.getFluidState(top).is(Fluids.WATER) || !ow.getFluidState(top).isSource()) return null;
        Sample s = lake.sample(x, z);
        if (s.zone() != Zone.LAKE) return null;
        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4;
            for (int r = 2; r <= reach; r += 2) {
                int nx = x + (int) Math.round(Math.cos(ang) * r), nz = z + (int) Math.round(Math.sin(ang) * r);
                BlockPos n = new BlockPos(nx, 63, nz);
                if (!ow.isLoaded(n)) continue;
                int ns = ow.getHeight(Heightmap.Types.WORLD_SURFACE, nx, nz) - 1;
                if (ow.getFluidState(new BlockPos(nx, ns, nz)).isEmpty()) return top;
            }
        }
        return null;
    }

    /** A deeper snow cover over each loaded camp. */
    private static void snowCamps(ServerLevel ow, WeatherData d, RandomSource r) {
        for (Clan c : Clan.values()) {
            if (!Cats.campLoaded(ow, c)) continue;
            Landmark camp = Cats.camp(c);
            int rad = (int) Math.max(8, camp.radius() - 8);
            for (int x = (int) camp.x() - rad; x <= (int) camp.x() + rad; x++) {
                for (int z = (int) camp.z() - rad; z <= (int) camp.z() + rad; z++) {
                    if (Math.hypot(x - camp.x(), z - camp.z()) > rad) continue;
                    BlockPos top = Cats.surface(ow, x, z);
                    BlockState at = ow.getBlockState(top), below = ow.getBlockState(top.below());
                    int layers = 1 + r.nextInt(2);
                    if (at.is(Blocks.SNOW)) {
                        d.change(ow, top, at.setValue(SnowLayerBlock.LAYERS, Math.min(4, at.getValue(SnowLayerBlock.LAYERS) + layers)));
                    } else if (at.isAir() && below.isFaceSturdy(ow, top.below(), net.minecraft.core.Direction.UP)) {
                        d.change(ow, top, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
                    }
                }
            }
        }
    }

    // ---- flood -------------------------------------------------------------------------------------------------

    /** Raises the water a block or two over RiverClan's low marsh around its camp. */
    private static int flood(ServerLevel ow, WeatherData d, BlockPos camp) {
        LakeLands lake = ClanWorld.current().lake;
        int n = 0;
        for (int x = camp.getX() - 60; x <= camp.getX() + 60; x++) {
            for (int z = camp.getZ() - 60; z <= camp.getZ() + 60; z++) {
                if (Math.hypot(x - camp.getX(), z - camp.getZ()) > 60) continue;
                if (!ow.isLoaded(new BlockPos(x, 64, z))) continue;
                Sample s = lake.sample(x, z);
                if (s.zone() != Zone.RIVERCLAN_MARSH && s.zone() != Zone.RIVERCLAN_WETLAND || s.height() >= 65) continue;
                int ground = ow.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (ground > 64 || !ow.getFluidState(new BlockPos(x, ground, z)).isEmpty()) continue;
                for (int y = ground + 1; y <= Math.min(ground + 2, 65); y++) {
                    BlockPos at = new BlockPos(x, y, z);
                    BlockState here = ow.getBlockState(at);
                    if (!here.isAir() && !here.canBeReplaced()) break;
                    d.change(ow, at, Blocks.WATER.defaultBlockState());
                    n++;
                }
            }
        }
        return n;
    }

    // ---- drought -----------------------------------------------------------------------------------------------

    /** Dries the lake's shallow edge near players to mud, cracked earth and dead grass. */
    private static int drought(ServerLevel ow, WeatherData d, RandomSource r) {
        LakeLands lake = ClanWorld.current().lake;
        int n = 0;
        for (ServerPlayer p : ow.getServer().getPlayerList().getPlayers()) {
            if (p.level() != ow || !lake.covers(p.getX(), p.getZ())) continue;
            for (int x = p.getBlockX() - 64; x <= p.getBlockX() + 64 && d.changes.size() < MAX_CHANGES; x++) {
                for (int z = p.getBlockZ() - 64; z <= p.getBlockZ() + 64; z++) {
                    BlockPos top = edgeWater(ow, lake, x, z, 4);
                    if (top == null || d.changes.containsKey(top.asLong())) continue;
                    int floor = ow.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                    if (top.getY() - floor > 1) continue;                       // only the shallowest water dries
                    for (int y = floor; y < top.getY(); y++) d.change(ow, new BlockPos(x, y, z), Blocks.MUD.defaultBlockState());
                    d.change(ow, top, (x * 31 + z * 17 & 3) == 0 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.MUD.defaultBlockState());
                    BlockPos above = top.above();
                    if (ow.getBlockState(above).isAir() && r.nextInt(9) == 0) d.change(ow, above, Blocks.DEAD_BUSH.defaultBlockState());
                    n++;
                }
            }
        }
        return n;
    }

    // ---- fire --------------------------------------------------------------------------------------------------

    /** A tree in the Clan's territory, 45-140 blocks from camp, in a loaded chunk: the top of its crown. */
    private static BlockPos findTree(ServerLevel ow, Clan c, RandomSource r) {
        for (int i = 0; i < 40; i++) {
            BlockPos p = Cats.inTerritory(ow, c, r, 45, 140);
            if (p == null || !ow.isLoaded(p)) continue;
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    int x = p.getX() + dx, z = p.getZ() + dz;
                    BlockPos top = new BlockPos(x, ow.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
                    if (ow.getBlockState(top).is(BlockTags.LEAVES)) return top;
                }
            }
        }
        return null;
    }

    private static void ignite(ServerLevel ow, BlockPos centre) {
        for (BlockPos at : BlockPos.betweenClosed(centre.offset(-3, -3, -3), centre.offset(3, 2, 3))) {
            if (at.distSqr(centre) > 10 || !ow.getBlockState(at).isAir()) continue;
            if (!flammableNear(ow, at)) continue;
            ow.setBlock(at, BaseFireBlock.getState(ow, at), 3);
        }
    }

    private static boolean flammableNear(ServerLevel ow, BlockPos at) {
        for (var dir : net.minecraft.core.Direction.values()) {
            BlockState s = ow.getBlockState(at.relative(dir));
            if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return true;
        }
        return false;
    }

    /** Every tick of a fire: any flame that got beyond the fire's reach is put out at once. */
    private static void containFire(ServerLevel ow, WeatherData d) {
        BlockPos c = BlockPos.of(d.fireCentre);
        int keep = d.fireRadius, reach = keep + 5;
        double keep2 = (keep + 0.5) * (keep + 0.5);
        for (BlockPos at : BlockPos.betweenClosed(c.offset(-reach, -reach, -reach), c.offset(reach, reach, reach))) {
            if (at.distSqr(c) <= keep2) continue;
            if (ow.isLoaded(at) && ow.getBlockState(at).is(BlockTags.FIRE)) ow.removeBlock(at, false);
        }
    }

    /** Puts out every flame and leaves the scar: some of the burnt tree's logs stay charred dark. */
    private static void putOutFire(ServerLevel ow, WeatherData d) {
        BlockPos c = BlockPos.of(d.fireCentre);
        int reach = d.fireRadius + 6;
        for (BlockPos at : BlockPos.betweenClosed(c.offset(-reach, -reach, -reach), c.offset(reach, reach, reach))) {
            if (!ow.isLoaded(at)) continue;
            BlockState s = ow.getBlockState(at);
            if (s.is(BlockTags.FIRE)) {
                ow.removeBlock(at, false);
            } else if (at.distSqr(c) <= (d.fireRadius + 2) * (d.fireRadius + 2) && s.is(BlockTags.LOGS)
                    && ((at.getX() * 7 + at.getY() * 13 + at.getZ() * 5) & 3) != 0) {
                BlockState scar = Blocks.DARK_OAK_LOG.defaultBlockState();
                if (s.hasProperty(RotatedPillarBlock.AXIS)) scar = scar.setValue(RotatedPillarBlock.AXIS, s.getValue(RotatedPillarBlock.AXIS));
                ow.setBlock(at, scar, 3);
            }
        }
        d.fireCentre = 0;
    }

    /** Kits and the oldest cats leave camp for the lake shore until the fire is out. */
    private static void moveToSafety(ServerLevel ow, WeatherData d, Clan c, List<WCatEntity> cats) {
        LakeLands lake = ClanWorld.current().lake;
        BlockPos camp = Cats.campCentre(ow, c);
        double dx = lake.centreX() - camp.getX(), dz = lake.centreZ() - camp.getZ(), len = Math.max(1, Math.hypot(dx, dz));
        BlockPos safe = Cats.surface(ow, (int) (camp.getX() + dx / len * Math.min(80, len * 0.6)), (int) (camp.getZ() + dz / len * Math.min(80, len * 0.6)));
        List<WCatEntity> go = new ArrayList<>(Cats.withRank(cats, WCatEntity.Rank.KIT));
        cats.stream().filter(x -> x.getRank() != WCatEntity.Rank.KIT && com.ninjacat.greatjourney.clanlore.traits.Traits.moons(x) >= EventsConfig.OLD_AGE_MOONS.get() * 0.7)
                .forEach(go::add);
        for (WCatEntity cat : go) {
            if (cat.wanderCenter != null) d.moved.put(cat.getUUID(), cat.wanderCenter.asLong());
            cat.wanderCenter = safe;
            cat.getNavigation().moveTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, 1.2);
        }
    }

    /** Sends moved cats home; cats not loaded right now are tried again later. */
    private static void returnHome(ServerLevel ow, WeatherData d) {
        d.moved.entrySet().removeIf(e -> {
            if (ow.getEntity(e.getKey()) instanceof WCatEntity cat) {
                cat.wanderCenter = BlockPos.of(e.getValue());
                return true;
            }
            return false;
        });
        d.setDirty();
    }
}
