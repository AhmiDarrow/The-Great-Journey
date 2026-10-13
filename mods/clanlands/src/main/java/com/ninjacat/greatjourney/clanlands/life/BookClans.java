package com.ninjacat.greatjourney.clanlands.life;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan;
import com.ninjacat.greatjourney.clanlands.layout.LakeLands;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.slf4j.Logger;

/**
 * The four lake Clans as real Warrior Cats Events Clans: created once per world with freshly named cats, holding
 * their book territories, and living in their camps. Leaders, deputies, medicine cats, warriors, apprentices and
 * kits all get new warrior names in every world; the book cats appear only in the StarClan dream and the guide.
 */
public final class BookClans {
    private BookClans() {}

    private static final Logger LOG = LogUtils.getLogger();
    /** The age at which Warrior Cats Events draws a cat full grown, and the age its own tick gives every adult. */
    public static final int ADULT_MOONS = 12;

    /** How each Clan shows in Warrior Cats Events' Clan list and on its territory. */
    public enum Info {
        THUNDER(Clan.THUNDER, "ThunderClan", 0xE8A33D, "Brave and loyal hunters of the forest.", 18, "ThunderClan woods"),
        WIND(Clan.WIND, "WindClan", 0xC9B47A, "Swift runners of the open moor.", 52, "WindClan moor"),
        RIVER(Clan.RIVER, "RiverClan", 0x4A90C8, "Swimmers and fishers of the streams and marsh.", 87, "RiverClan marsh"),
        SHADOW(Clan.SHADOW, "ShadowClan", 0x3D5C3A, "Proud hunters of the dark pines.", 121, "ShadowClan pines");

        public final Clan clan;
        public final String title, motto, land;
        public final int colour, symbol;

        Info(Clan clan, String title, int colour, String motto, int symbol, String land) {
            this.clan = clan;
            this.title = title;
            this.colour = colour;
            this.motto = motto;
            this.symbol = symbol;
            this.land = land;
        }

        public static Info of(Clan c) {
            for (Info i : values()) if (i.clan == c) return i;
            throw new IllegalArgumentException(c.name());
        }

        public static Info byKey(String key) {
            for (Info i : values()) if (i.title.equalsIgnoreCase(key)) return i;
            return null;
        }

        public String key() {
            return title.toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A stable stand-in leader id per Clan: Warrior Cats Events needs a leader, and no player holds the post. */
    public static UUID standInLeader(Clan c) {
        return UUID.nameUUIDFromBytes(("greatjourney:leader:" + c.name()).getBytes(StandardCharsets.UTF_8));
    }

    /** Creates the Clans and their territory the first time a Clan-lands world starts. */
    public static void setup(MinecraftServer server) {
        ClanWorld w = ClanWorld.current();
        ServerLevel ow = server.overworld();
        if (w == null || !Clanlife.isClanWorld(ow)) return;
        LifeData life = LifeData.get(ow);
        if (life.created) return;
        if (Mode.of(server) == Mode.CAMPAIGN) return;                 // in the Campaign the lake Clans are founded in Act 4
        ClanData data = ClanData.get(ow);
        for (Clan c : Clan.values()) {
            Info info = Info.of(c);
            ClanNames.Name leader = names(w, c).leader();
            ClanData.Clan wc = data.getClanByName(info.title);
            if (wc == null) {
                wc = data.createClan(info.title, info.colour, standInLeader(c), leader.full(), info.motto, info.symbol);
            }
            life.clanIds.put(c, wc.clanUUID);
            life.leaders.put(c, wc.leaderName);
        }
        int claims = claimTerritory(w.lake, data, life);
        life.created = true;
        life.setDirty();
        refresh(server);
        data.syncTerritoriesToClients(ow);
        LOG.info("Great Journey: the four lake Clans are founded ({} territory claims)", claims);
    }

    /** One Clan's own name stream: the leader is always its first name, so restarts rebuild the same cats. */
    public static ClanNames names(ClanWorld w, Clan c) {
        return new ClanNames(w.seed + 7919L * (c.ordinal() + 1));
    }

    private static int claimTerritory(LakeLands lake, ClanData data, LifeData life) {
        int r = (int) Math.ceil(lake.reach() / 16) + 1;
        int ccx = (int) Math.floor(lake.centreX()) >> 4, ccz = (int) Math.floor(lake.centreZ()) >> 4;
        int claims = 0;
        // Warrior Cats Events claims a chunk and its eight neighbours, so every third chunk covers the land.
        for (int dx = -r; dx <= r; dx += 3) {
            for (int dz = -r; dz <= r; dz += 3) {
                int cx = ccx + dx, cz = ccz + dz;
                Clan c = lake.territoryAt(cx * 16 + 8, cz * 16 + 8);
                if (c == null) continue;
                if (data.claimChunk(life.clanId(c), new ChunkPos(cx, cz), areaName(lake, c, cx * 16 + 8, cz * 16 + 8))) claims++;
            }
        }
        for (Clan c : Clan.values()) {
            Landmark camp = lake.camp(c);
            ChunkPos core = new ChunkPos(BlockPos.containing(camp.x(), 0, camp.z()));
            data.claimChunk(life.clanId(c), core, "Camp");
            data.getClan(life.clanId(c)).coreTerritory = core;
        }
        data.setDirty();
        return claims;
    }

    /** What the territory HUD calls this stretch: a landmark's name when one is close, else the Clan's land. */
    private static String areaName(LakeLands lake, Clan c, double x, double z) {
        for (Landmark l : lake.landmarks()) {
            if (Math.hypot(l.x() - x, l.z() - z) < 70) {
                String n = switch (l.kind()) {
                    case STONE_HOLLOW -> "The stone hollow";
                    case SKY_OAK -> "The Sky Oak";
                    case ABANDONED_NEST -> "The abandoned Twoleg nest";
                    case SHADOWCLAN_CAMP, WINDCLAN_HOLLOW, RIVERCLAN_CAMP -> "Camp";
                    case HALF_BRIDGE -> "The half-bridge";
                    case HORSEPLACE -> "The horseplace";
                    default -> null;
                };
                if (n != null) return n;
            }
        }
        return Info.of(c).land;
    }

    /** Keeps the Clans' land fresh: Warrior Cats Events would otherwise let unmarked territory fade away. */
    public static void refresh(MinecraftServer server) {
        ServerLevel ow = server.overworld();
        LifeData life = LifeData.get(ow);
        if (!life.created) return;
        ClanData data = ClanData.get(ow);
        int max = data.getMaxTerritoryTime();
        for (Clan c : Clan.values()) {
            ClanData.Clan wc = data.getClan(life.clanId(c));
            if (wc == null) continue;
            for (ClanData.TerritoryChunk t : wc.claimedTerritory.values()) t.time = Math.max(t.time, max);
        }
        data.setDirty();
    }

    /** Fills a camp with its Clan the first time the camp's chunk is loaded. */
    public static void populateIfDue(ServerLevel level, ChunkPos loaded) {
        ClanWorld w = ClanWorld.current();
        if (w == null || !Clanlife.isClanWorld(level)) return;
        LifeData life = LifeData.get(level);
        if (!life.created) return;
        for (Clan c : Clan.values()) {
            if (life.populated.contains(c)) continue;
            Landmark camp = w.lake.camp(c);
            if (!new ChunkPos(BlockPos.containing(camp.x(), 0, camp.z())).equals(loaded)) continue;
            life.populated.add(c);
            life.setDirty();
            // the whole camp loads first (off the server thread), so filling it never generates land mid-tick
            level.getServer().execute(() -> ChunkWait.whenLoaded(level, camp.x(), camp.z(), (int) camp.radius(), () -> populate(level, w, life, c, camp)));
        }
    }

    public enum Role { LEADER, DEPUTY, MEDICINE, WARRIOR, QUEEN, APPRENTICE, KIT }

    private static void populate(ServerLevel level, ClanWorld w, LifeData life, Clan c, Landmark camp) {
        RandomSource r = RandomSource.create(w.seed ^ (c.ordinal() * 0x9E3779B9L));
        ClanNames names = names(w, c);
        names.leader();                                             // the leader's name, already chosen at founding
        List<Role> roster = new ArrayList<>(List.of(Role.LEADER, Role.DEPUTY, Role.MEDICINE, Role.QUEEN, Role.APPRENTICE, Role.APPRENTICE, Role.KIT, Role.KIT));
        int warriors = 4 + r.nextInt(3);
        for (int i = 0; i < warriors; i++) roster.add(3, Role.WARRIOR);
        double ring = Math.max(6, camp.radius() - 14);
        int cx = (int) Math.floor(camp.x()), cz = (int) Math.floor(camp.z());
        BlockPos centre = surface(level, cx, cz);
        place(level, surface(level, cx + 2, cz), block("warriorcats_events:fresh_kill_pile"));
        int[] perDen = new int[CampPlan.DenKind.values().length];
        for (int i = 0; i < roster.size(); i++) {
            Role role = roster.get(i);
            double a = (i + r.nextDouble() * 0.4) / roster.size() * Math.PI * 2;
            double rr = role == Role.KIT ? ring * 0.45 : ring * (0.75 + r.nextDouble() * 0.2);
            BlockPos nest = denNest(level, camp, role, perDen);
            if (nest == null) nest = surface(level, cx + (int) Math.round(Math.cos(a) * rr), cz + (int) Math.round(Math.sin(a) * rr));
            place(level, nest, block("warriorcats_events:moss_bed"));
            if (role == Role.MEDICINE) place(level, herbSpot(level, nest), block("warriorcats_events:herb_rock"));
            ClanNames.Name name = switch (role) {
                case LEADER -> new ClanNames.Name(life.leader(c).replace("star", ""), life.leader(c));
                case APPRENTICE -> names.apprentice();
                case KIT -> names.kit();
                default -> names.warrior();
            };
            int gender = role == Role.QUEEN || (role == Role.MEDICINE && r.nextBoolean()) ? 1 : r.nextInt(2);
            spawnCat(level, c, life.clanId(c), role, name, gender, nest, centre, r);
        }
    }

    public static WCatEntity spawnCat(ServerLevel level, Clan c, UUID clanId, Role role, ClanNames.Name name, int gender,
                               BlockPos home, BlockPos centre, RandomSource r) {
        WCatEntity cat = ModEntities.WCAT.get().create(level);
        if (cat == null) return null;
        cat.moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, r.nextFloat() * 360, 0);
        cat.finalizeSpawn(level, level.getCurrentDifficultyAt(home), MobSpawnType.EVENT, null);
        cat.setGender(gender);
        cat.setCustomName(Component.literal(name.full() + (gender == 0 ? " ♂" : " ♀")));
        cat.setPrefix(Component.literal(name.prefix()));
        cat.setRank(switch (role) {
            case DEPUTY -> WCatEntity.Rank.DEPUTY;
            case MEDICINE -> WCatEntity.Rank.MEDICINE;
            case APPRENTICE -> WCatEntity.Rank.APPRENTICE;
            case KIT -> WCatEntity.Rank.KIT;
            default -> WCatEntity.Rank.WARRIOR;
        });
        switch (role) {
            case KIT -> {
                cat.setBaby(true);
                cat.setAgeInMoons(2 + r.nextInt(3));
            }
            case APPRENTICE -> {
                cat.setAppScale(true);
                cat.setAgeInMoons(7 + r.nextInt(4));
            }
            // Warrior Cats Events draws a cat from its age with no cap past 12 moons: the body keeps growing (4.4x at 80
            // moons) and the head shrinks to nothing at 52 and turns inside out past it. Its tick puts every adult at 12
            // anyway, but only once the cat ticks, so camp cats out of simulation range stood giant and headless. The
            // rolls stay (the same seed gives the same roster); grown cats are given the full-grown 12
            case LEADER -> cat.setAgeInMoons(Math.min(ADULT_MOONS, 60 + r.nextInt(40)));
            default -> cat.setAgeInMoons(Math.min(ADULT_MOONS, 14 + r.nextInt(50)));
        }
        cat.setClan(Component.literal(Info.of(c).title));
        cat.setTame(true, false);
        cat.setOwnerUUID(standInLeader(c));
        level.addFreshEntity(cat);
        cat.setClanUUID(clanId);
        cat.setHomePosition(home);
        cat.wanderCenter = centre;
        cat.mode = WCatEntity.CatMode.WANDER;
        return cat;
    }

    /**
     * Where a cat of this role sleeps in its camp: a nest in its own den (the nursery for queens and kits, the
     * warriors' den for the deputy and warriors...) as the camp was built; null for a camp without a plan.
     * {@code perDen} counts the cats already given each den.
     */
    public static BlockPos denNest(ServerLevel level, Landmark camp, Role role, int[] perDen) {
        CampPlan plan = CampPlan.of(camp);
        if (plan == null) return null;
        CampPlan.DenKind kind = switch (role) {
            case LEADER -> CampPlan.DenKind.LEADER;
            case MEDICINE -> CampPlan.DenKind.MEDICINE;
            case QUEEN, KIT -> CampPlan.DenKind.NURSERY;
            case APPRENTICE -> CampPlan.DenKind.APPRENTICES;
            default -> CampPlan.DenKind.WARRIORS;
        };
        double[] at = plan.nest(kind, perDen[kind.ordinal()]++);
        int x = (int) Math.floor(at[0]), z = (int) Math.floor(at[1]);
        int y = plan.den(kind).y();
        if (y == CampPlan.SURFACE) return surface(level, x, z);
        level.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, y, z);                                  // a burrow, cleft or cave: dug to the plan
    }

    /** Beside a nest, on the same floor, for the medicine cat's herb rock. */
    public static BlockPos herbSpot(ServerLevel level, BlockPos nest) {
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos b = nest.relative(d);
            if (level.getBlockState(b).canBeReplaced() && !level.getBlockState(b.below()).canBeReplaced()) return b;
        }
        return nest.above();
    }

    public static BlockPos surface(ServerLevel level, int x, int z) {
        // an unloaded chunk reads as height -64, which put players joining a far camp under the world: load it first
        level.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /**
     * The nearest spot to x, z (within {@code reach} blocks) where a player can safely stand: firm footing, two blocks
     * of air and no wall at head height beside it; a spot with a wall beside it only if there is no open one. Stand
     * there block-centred. Arrivals use it, and so do the playtest harness's camp views: the harness once stood a
     * player flush against the WindClan hollow's boulder, its eyes a hair inside the rock, and they suffocated.
     * Falls back to the surface at x, z.
     */
    public static BlockPos safeSpot(ServerLevel level, int x, int z, int reach) {
        BlockPos walled = null;
        for (int r = 0; r <= reach; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;           // this ring only, nearest first
                    BlockPos at = standable(level, x + dx, z + dz);
                    if (at == null) continue;
                    if (open(level, at)) return at;
                    if (walled == null) walled = at;
                }
            }
        }
        return walled != null ? walled : surface(level, x, z);
    }

    /** Where a player can stand in this column (its top, checked against the blocks themselves), or null. */
    public static BlockPos standable(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = surface(level, x, z).mutable();
        int top = p.getY();
        // the heightmap is only a hint: climb out of anything built over it, or drop to a floor dug below it
        while (blocks(level, p) && p.getY() < top + 8) p.move(0, 1, 0);
        while (!blocks(level, p.below()) && p.getY() > top - 8) p.move(0, -1, 0);
        BlockPos feet = p.immutable();
        net.minecraft.world.level.block.state.BlockState under = level.getBlockState(feet.below());
        if (!under.getFluidState().isEmpty() || under.is(Blocks.MAGMA_BLOCK)
                || !under.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) return null;
        return blocks(level, feet) || blocks(level, feet.above()) ? null : feet;
    }

    /** No wall or rock at head height in the eight columns around. */
    private static boolean open(ServerLevel level, BlockPos feet) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) if ((dx != 0 || dz != 0) && blocks(level, feet.offset(dx, 1, dz))) return false;
        }
        return true;
    }

    /** Whether a block is in the way of standing (or would hurt to stand in). */
    private static boolean blocks(ServerLevel level, BlockPos p) {
        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
        return !s.getCollisionShape(level, p).isEmpty() || !s.getFluidState().isEmpty() || s.is(net.minecraft.tags.BlockTags.FIRE)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CACTUS);
    }

    private static void place(ServerLevel level, BlockPos pos, Block block) {
        if (block != Blocks.AIR && level.getBlockState(pos).canBeReplaced()) level.setBlock(pos, block.defaultBlockState(), 3);
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }
}
