package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** Finding, naming and making Clan cats for events. */
public final class Cats {
    private Cats() {}

    /** How far from a camp's centre a Clan's cats count as "at camp". */
    static final double CAMP_REACH = 80;

    /** A cat's name without Warrior Cats Events' gender sign. */
    public static String plainName(WCatEntity cat) {
        if (cat.getCustomName() == null) return "a Clan cat";
        return cat.getCustomName().getString().replace("♂", "").replace("♀", "").trim();
    }

    /** Gives a cat a new name, keeping Warrior Cats Events' gender sign style. */
    public static void rename(WCatEntity cat, String prefix, String full) {
        cat.setCustomName(Component.literal(full + (cat.getGender() == 0 ? " ♂" : " ♀")));
        cat.setPrefix(Component.literal(prefix));
    }

    public static Landmark camp(Clan c) {
        ClanWorld w = ClanWorld.current();
        return w == null ? null : Homeland.camp(w, c);
    }

    public static BlockPos campCentre(ServerLevel level, Clan c) {
        Landmark camp = camp(c);
        return surface(level, (int) Math.floor(camp.x()), (int) Math.floor(camp.z()));
    }

    /** Whether the camp's centre chunk is loaded (its cats are in the world and can take part). */
    public static boolean campLoaded(ServerLevel level, Clan c) {
        Landmark camp = camp(c);
        return camp != null && level.isLoaded(BlockPos.containing(camp.x(), 64, camp.z()));
    }

    /**
     * Whether the cats of every chunk the camp covers have loaded. Entities load a little after the land: just after a
     * player arrives, a camp can be loaded with only some of its cats in it.
     */
    public static boolean campCatsLoaded(ServerLevel level, Clan c) {
        Landmark camp = camp(c);
        if (camp == null) return false;
        double r = camp.radius() + 8;
        for (int cx = (int) Math.floor(camp.x() - r) >> 4; cx <= (int) Math.floor(camp.x() + r) >> 4; cx++) {
            for (int cz = (int) Math.floor(camp.z() - r) >> 4; cz <= (int) Math.floor(camp.z() + r) >> 4; cz++) {
                if (!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) return false;
            }
        }
        return true;
    }

    /** The Clan's cats in and around its camp. */
    public static List<WCatEntity> atCamp(ServerLevel level, Clan c) {
        Landmark camp = camp(c);
        UUID id = LifeData.get(level).clanId(c);
        if (camp == null || id == null) return List.of();
        return level.getEntitiesOfClass(WCatEntity.class,
                new AABB(camp.x() - CAMP_REACH, level.getMinBuildHeight(), camp.z() - CAMP_REACH,
                        camp.x() + CAMP_REACH, level.getMaxBuildHeight(), camp.z() + CAMP_REACH),
                cat -> cat.isAlive() && id.equals(cat.getClanUUID()));
    }

    public static List<WCatEntity> withRank(List<WCatEntity> cats, WCatEntity.Rank rank) {
        return cats.stream().filter(c -> c.getRank() == rank).toList();
    }

    /** The name of the first cat of the given rank, or the fallback. */
    public static String speaker(List<WCatEntity> cats, WCatEntity.Rank rank, String fallback) {
        return withRank(cats, rank).stream().findFirst().map(Cats::plainName).orElse(fallback);
    }

    public static boolean isLeader(LifeData life, Clan c, WCatEntity cat) {
        return plainName(cat).equals(life.leader(c));
    }

    /** Lower-case names already used in a Clan's camp, so new names never clash. */
    public static Set<String> namesIn(List<WCatEntity> cats) {
        Set<String> used = new HashSet<>();
        for (WCatEntity c : cats) used.add(plainName(c).toLowerCase(Locale.ROOT));
        return used;
    }

    /** A warrior name that keeps the cat's own prefix (as the books do), with a new suffix not used in the Clan. */
    public static String warriorName(String prefix, RandomSource r, Set<String> used) {
        String best = prefix + "heart";
        for (int i = 0; i < 40; i++) {
            String sfx = WCatEntity.SUFIXES[r.nextInt(WCatEntity.SUFIXES.length)].toLowerCase(Locale.ROOT).trim();
            if (sfx.isEmpty() || sfx.equals("paw") || sfx.equals("kit") || sfx.equals("star")) continue;
            if (prefix.toLowerCase(Locale.ROOT).endsWith(sfx.substring(0, 1))) continue;
            String full = prefix + sfx;
            if (!used.contains(full.toLowerCase(Locale.ROOT))) return full;
            best = full;
        }
        return best;
    }

    /** The prefix of a cat's name: its stored prefix, or its name without a -paw / -kit / -star ending. */
    public static String prefixOf(WCatEntity cat) {
        Component p = cat.getPrefix();
        if (p != null && !p.getString().isBlank()) return p.getString().trim();
        String n = plainName(cat);
        for (String end : new String[] {"paw", "kit", "star"}) if (n.endsWith(end)) return n.substring(0, n.length() - end.length());
        return n;
    }

    /** Makes a new cat of a lake Clan at a position (kits, raiders). Mirrors how the camps are first filled. */
    public static WCatEntity spawn(ServerLevel level, Clan c, WCatEntity.Rank rank, String prefix, String full, int gender,
                                   BlockPos at, BlockPos home, RandomSource r) {
        UUID id = LifeData.get(level).clanId(c);
        WCatEntity cat = ModEntities.WCAT.get().create(level);
        if (cat == null || id == null) return null;
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
        cat.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setGender(gender);
        rename(cat, prefix, full);
        cat.setRank(rank);
        if (rank == WCatEntity.Rank.KIT) {
            cat.setBaby(true);
            cat.setAgeInMoons(0);
        } else if (rank == WCatEntity.Rank.APPRENTICE) {
            cat.setAppScale(true);
            cat.setAgeInMoons(8);
        } else {
            cat.setAgeInMoons(Math.min(com.ninjacat.greatjourney.clanlands.life.BookClans.ADULT_MOONS, 14 + r.nextInt(40)));
        }
        cat.setClan(Component.literal(BookClans.Info.of(c).title));
        cat.setTame(true, false);
        cat.setOwnerUUID(BookClans.standInLeader(c));
        level.addFreshEntity(cat);
        cat.setClanUUID(id);
        cat.setHomePosition(home);
        cat.wanderCenter = campCentre(level, c);
        cat.mode = WCatEntity.CatMode.WANDER;
        return cat;
    }

    public static BlockPos surface(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** A random spot in the Clan's own territory, between {@code min} and {@code max} blocks from camp, or null. */
    public static BlockPos inTerritory(ServerLevel level, Clan c, RandomSource r, double min, double max) {
        ClanWorld w = ClanWorld.current();
        Landmark camp = camp(c);
        if (w == null || camp == null) return null;
        for (int i = 0; i < 40; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = min + r.nextDouble() * (max - min);
            double x = camp.x() + Math.cos(a) * d, z = camp.z() + Math.sin(a) * d;
            if (Homeland.territoryAt(w, x, z) == c) return surface(level, (int) Math.floor(x), (int) Math.floor(z));
        }
        return null;
    }

    /** The border spot between a Clan's camp and a neighbour's camp: where the land stops being the Clan's. */
    public static BlockPos borderToward(ServerLevel level, Clan home, Clan neighbour) {
        ClanWorld w = ClanWorld.current();
        Landmark a = camp(home), b = camp(neighbour);
        if (w == null || a == null || b == null) return null;
        double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
        for (double t = 0; t < len; t += 6) {
            double x = a.x() + (b.x() - a.x()) * t / len, z = a.z() + (b.z() - a.z()) * t / len;
            Clan here = Homeland.territoryAt(w, x, z);
            if (here != null && here != home) return surface(level, (int) Math.floor(x), (int) Math.floor(z));
        }
        return null;
    }

    /** The Clans next to a Clan around the lake, in the books' order. */
    public static List<Clan> neighbours(Clan c) {
        Clan[] ring = {Clan.THUNDER, Clan.WIND, Clan.RIVER, Clan.SHADOW};
        int i = 0;
        while (ring[i] != c) i++;
        return List.of(ring[(i + 1) % 4], ring[(i + 3) % 4]);
    }

    /** "north", "south-east"... from a camp toward a spot, for hints. */
    public static String direction(double fromX, double fromZ, double toX, double toZ) {
        double b = Math.toDegrees(Math.atan2(toX - fromX, -(toZ - fromZ)));
        if (b < 0) b += 360;
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(b / 45.0) % 8];
    }
}
