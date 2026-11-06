package com.ninjacat.greatjourney.clanlands.life.campaign;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Role;
import com.ninjacat.greatjourney.clanlands.life.ChunkWait;
import com.ninjacat.greatjourney.clanlands.life.ClanNames;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Nests;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.slf4j.Logger;

/**
 * The Clans of the Campaign. They are founded in the old forest, holding the territories and camps of the books'
 * first stories (ThunderClan's ravine, WindClan's hollow on the moor, RiverClan's island, ShadowClan's camp in the
 * pines). When they leave on the Great Journey their cats go with them, and when they reach the lake the same Clans
 * give up the forest and claim their new land, and their cats settle into the new camps.
 */
final class ForestClans {
    private ForestClans() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final String ESCORT_TAG = "greatjourney_escort";

    /** The forest Clans' own name streams: the leader is always the first name drawn. */
    static ClanNames names(ClanWorld w, Clan c) {
        return new ClanNames(w.seed + 104729L * (c.ordinal() + 1));
    }

    /** Founds the four Clans in the old forest, once per Campaign world. */
    static void found(MinecraftServer server, CampaignData cd) {
        ClanWorld w = ClanWorld.current();
        ServerLevel ow = server.overworld();
        if (w == null || cd.forestFounded) return;
        LifeData life = LifeData.get(ow);
        ClanData data = ClanData.get(ow);
        for (Clan c : Clan.values()) {
            Info info = Info.of(c);
            ClanNames.Name leader = names(w, c).leader();
            ClanData.Clan wc = data.getClanByName(info.title);
            if (wc == null) wc = data.createClan(info.title, info.colour, BookClans.standInLeader(c), leader.full(), info.motto, info.symbol);
            life.adopt(c, wc.clanUUID, wc.leaderName);
            life.markPopulated(c);                       // the lake camps are never filled with strangers: the Clans bring their own
        }
        life.markCreated();
        int claims = claim(w.forest, (x, z) -> Homeland.forestTerritoryAt(w, x, z), c -> Homeland.forestCamp(w, c), data, life);
        cd.forestFounded = true;
        cd.advance(1, ow.getGameTime());
        cd.actStartedAt = ow.getGameTime();
        cd.setDirty();
        BookClans.refresh(server);
        data.syncTerritoriesToClients(ow);
        LOG.info("Great Journey Campaign: the four Clans are founded in the old forest ({} territory claims)", claims);
    }

    /** Claims every territory chunk of a map for its Clan (WCE claims each chunk's 3x3, so every third chunk), and the camps as cores. */
    private static int claim(ClanMap map, BiFunction<Double, Double, Clan> territory, java.util.function.Function<Clan, Landmark> camps,
                             ClanData data, LifeData life) {
        int r = (int) Math.ceil(Math.min(map.reach(), 2600) / 16) + 1;
        int ccx = (int) Math.floor(map.centreX()) >> 4, ccz = (int) Math.floor(map.centreZ()) >> 4;
        int claims = 0;
        for (int dx = -r; dx <= r; dx += 3) {
            for (int dz = -r; dz <= r; dz += 3) {
                int cx = ccx + dx, cz = ccz + dz;
                double x = cx * 16 + 8, z = cz * 16 + 8;
                Clan c = territory.apply(x, z);
                if (c == null) continue;
                if (data.claimChunk(life.clanId(c), new ChunkPos(cx, cz), areaName(map, c, x, z))) claims++;
            }
        }
        for (Clan c : Clan.values()) {
            Landmark camp = camps.apply(c);
            ChunkPos core = new ChunkPos(BlockPos.containing(camp.x(), 0, camp.z()));
            data.claimChunk(life.clanId(c), core, "Camp");
            data.getClan(life.clanId(c)).coreTerritory = core;
        }
        data.setDirty();
        return claims;
    }

    private static String areaName(ClanMap map, Clan c, double x, double z) {
        for (Landmark l : map.landmarks()) {
            if (Math.hypot(l.x() - x, l.z() - z) >= 70) continue;
            String n = switch (l.kind()) {
                case SUNNINGROCKS -> "Sunningrocks";
                case SNAKEROCKS -> "Snakerocks";
                case OWL_TREE -> "The owl tree";
                case GREAT_SYCAMORE -> "The Great Sycamore";
                case CARRIONPLACE -> "Carrionplace";
                case STONE_HOLLOW -> "The stone hollow";
                case SKY_OAK -> "The Sky Oak";
                case ABANDONED_NEST -> "The abandoned Twoleg nest";
                case HALF_BRIDGE -> "The half-bridge";
                case THUNDERCLAN_RAVINE, WINDCLAN_HOLLOW, RIVERCLAN_CAMP, SHADOWCLAN_CAMP -> "Camp";
                default -> null;
            };
            if (n != null) return n;
        }
        return Info.of(c).land;
    }

    /** Fills a forest camp with its Clan the first time it loads, and settles arriving Clans into the lake camps. */
    static void populateIfDue(ServerLevel level, ChunkPos loaded, CampaignData cd) {
        ClanWorld w = ClanWorld.current();
        if (w == null || !cd.forestFounded) return;
        LifeData life = LifeData.get(level);
        for (Clan c : Clan.values()) {
            if (!cd.departed && !cd.forestPopulated.contains(c)) {
                Landmark camp = Homeland.forestCamp(w, c);
                if (new ChunkPos(BlockPos.containing(camp.x(), 0, camp.z())).equals(loaded)) {
                    cd.forestPopulated.add(c);
                    cd.setDirty();
                    level.getServer().execute(() -> ChunkWait.whenLoaded(level, camp.x(), camp.z(), (int) camp.radius(), () -> populate(level, w, life, c, camp, null)));
                }
            } else if (!cd.departed) {
                Nests.furnishIfDue(level, Homeland.forestCamp(w, c), loaded);       // filled before every den had its nests
            }
            if (cd.lakeFounded && !cd.lakeArrived.contains(c)) {
                Landmark camp = w.lake.camp(c);
                if (new ChunkPos(BlockPos.containing(camp.x(), 0, camp.z())).equals(loaded)) {
                    cd.lakeArrived.add(c);
                    cd.setDirty();
                    List<String> roster = cd.roster.getOrDefault(c, List.of());
                    level.getServer().execute(() -> ChunkWait.whenLoaded(level, camp.x(), camp.z(), (int) camp.radius(), () -> populate(level, w, life, c, camp, roster)));
                }
            } else if (cd.lakeArrived.contains(c)) {
                Nests.furnishIfDue(level, w.lake.camp(c), loaded);
            }
        }
    }

    /**
     * A camp's cats, their nests, the fresh-kill pile and the medicine cat's herb rock. With a {@code roster} the
     * Clan's own cats arrive (by name, rank and sex); without one, a newly drawn Clan.
     */
    private static void populate(ServerLevel level, ClanWorld w, LifeData life, Clan c, Landmark camp, List<String> roster) {
        RandomSource r = RandomSource.create(w.seed ^ (c.ordinal() * 0x51F15E5L) ^ (roster == null ? 0 : 77));
        List<Object[]> cats = new ArrayList<>();                   // {Role, Name, gender}
        if (roster == null || roster.isEmpty()) {
            ClanNames names = names(w, c);
            names.leader();
            List<Role> roles = new ArrayList<>(List.of(Role.LEADER, Role.DEPUTY, Role.MEDICINE, Role.QUEEN, Role.APPRENTICE, Role.APPRENTICE, Role.KIT, Role.KIT));
            int warriors = 4 + r.nextInt(3);
            for (int i = 0; i < warriors; i++) roles.add(3, Role.WARRIOR);
            for (Role role : roles) {
                ClanNames.Name name = switch (role) {
                    case LEADER -> new ClanNames.Name(life.leader(c).replace("star", ""), life.leader(c));
                    case APPRENTICE -> names.apprentice();
                    case KIT -> names.kit();
                    default -> names.warrior();
                };
                int gender = role == Role.QUEEN || (role == Role.MEDICINE && r.nextBoolean()) ? 1 : r.nextInt(2);
                cats.add(new Object[] {role, name, gender});
            }
        } else {
            for (String line : roster) {
                String[] f = line.split("\\|");
                if (f.length < 3) continue;
                Role role = roleOf(f[1], f[0], life.leader(c));
                String prefix = f[0].replaceAll("(star|paw|kit)$", "");
                cats.add(new Object[] {role, new ClanNames.Name(prefix.isEmpty() ? f[0] : prefix, f[0]), parseInt(f[2])});
            }
        }
        double ring = Math.max(6, camp.radius() - 14);
        int cx = (int) Math.floor(camp.x()), cz = (int) Math.floor(camp.z());
        BlockPos centre = BookClans.surface(level, cx, cz);
        place(level, BookClans.surface(level, cx + 2, cz), block("warriorcats_events:fresh_kill_pile"));
        BookClans.furnish(level, camp);                             // every den's beds, slept in or not
        Nests.markFurnished(level, camp);
        if (roster != null) Nests.rehome(level, c, camp);          // the Clan's players move their nests to the new camp
        int[] perDen = new int[com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind.values().length];
        for (int i = 0; i < cats.size(); i++) {
            Role role = (Role) cats.get(i)[0];
            double a = (i + r.nextDouble() * 0.4) / cats.size() * Math.PI * 2;
            double rr = role == Role.KIT ? ring * 0.45 : ring * (0.75 + r.nextDouble() * 0.2);
            BlockPos nest = BookClans.denNest(level, camp, role, perDen);
            if (nest == null) nest = BookClans.surface(level, cx + (int) Math.round(Math.cos(a) * rr), cz + (int) Math.round(Math.sin(a) * rr));
            place(level, nest, block("warriorcats_events:moss_bed"));
            if (role == Role.MEDICINE) place(level, BookClans.herbSpot(level, nest), block("warriorcats_events:herb_rock"));
            BookClans.spawnCat(level, c, life.clanId(c), role, (ClanNames.Name) cats.get(i)[1], (int) cats.get(i)[2], nest, centre, r);
        }
    }

    private static Role roleOf(String rank, String name, String leader) {
        if (name.equalsIgnoreCase(leader)) return Role.LEADER;
        String r = rank.toUpperCase(Locale.ROOT);
        if (r.contains("KIT")) return Role.KIT;
        if (r.contains("APPRENTICE")) return Role.APPRENTICE;
        if (r.contains("MEDICINE")) return Role.MEDICINE;
        if (r.contains("DEPUTY")) return Role.DEPUTY;
        return Role.WARRIOR;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The Clans leave the forest: each Clan's cats are written down to arrive with them later, the cats still in
     * loaded camps walk away (are removed), and from now on any forest Clan cat that loads is gone too.
     */
    static void depart(MinecraftServer server, CampaignData cd) {
        if (cd.departed) return;
        ServerLevel ow = server.overworld();
        LifeData life = LifeData.get(ow);
        ClanData data = ClanData.get(ow);
        for (Clan c : Clan.values()) {
            UUID id = life.clanId(c);
            ClanData.Clan wc = id == null ? null : data.getClan(id);
            if (wc == null) continue;
            List<String> roster = new ArrayList<>();
            for (ClanData.ClanCat cat : wc.clanCats.values()) {
                String n = cat.catName == null ? "" : cat.catName.getString().replace(" ♂", "").replace(" ♀", "").trim();
                if (n.isEmpty()) continue;
                roster.add(n + "|" + (cat.catRank == null ? "WARRIOR" : cat.catRank) + "|" + cat.catGenderValue);
            }
            cd.roster.put(c, roster);
            wc.clanCats.clear();
        }
        ClanWorld w = ClanWorld.current();
        if (w != null) {
            for (Entity e : ow.getAllEntities()) {
                if (leftBehind(e, w, life)) e.discard();
            }
        }
        cd.departed = true;
        cd.setDirty();
        data.setDirty();
    }

    /** A forest Clan cat still in the old forest after the Clans have left (travelling companions excepted). */
    static boolean leftBehind(Entity e, ClanWorld w, LifeData life) {
        return e instanceof WCatEntity cat && !cat.getTags().contains(ESCORT_TAG) && life.clanOf(cat.getClanUUID()) != null
                && w.forest.covers(cat.getX(), cat.getZ());
    }

    /** The Clans reach the lake: they give up the forest, claim the lake territories and settle their camps. */
    static void settleAtLake(MinecraftServer server, CampaignData cd) {
        if (cd.lakeFounded) return;
        ClanWorld w = ClanWorld.current();
        ServerLevel ow = server.overworld();
        LifeData life = LifeData.get(ow);
        ClanData data = ClanData.get(ow);
        for (Clan c : Clan.values()) {
            UUID id = life.clanId(c);
            ClanData.Clan wc = id == null ? null : data.getClan(id);
            if (wc == null) continue;
            // not ClanData.unclaimChunk: it loads every claimed chunk (thousands, most never visited) to look for
            // marker blocks, which froze the server for minutes when the Clans arrived (found by the playtest
            // harness). Markers can only be in chunks someone has been to, and those are loaded or saved; the loaded
            // ones are swept, the claims are simply let go.
            for (ChunkPos pos : wc.claimedTerritory.keySet()) if (ow.hasChunk(pos.x, pos.z)) data.removeMarkersFromChunk(ow, pos);
            wc.claimedTerritory.clear();
            data.setDirty();
            wc.coreTerritory = null;
        }
        int claims = claim(w.lake, w.lake::territoryAt, w.lake::camp, data, life);
        // the travelling companions join their Clanmates in the new camps
        for (Entity e : ow.getAllEntities()) {
            if (e instanceof WCatEntity cat && cat.getTags().contains(ESCORT_TAG)) cat.discard();
        }
        cd.lakeFounded = true;
        cd.setDirty();
        Homeland.setForest(false);
        BookClans.refresh(server);
        data.syncTerritoriesToClients(ow);
        LOG.info("Great Journey Campaign: the Clans have settled at the lake ({} territory claims)", claims);
    }

    /**
     * Three Clanmates travel with a player on the Great Journey (they follow, as Clan cats follow a Clanmate they
     * trust). Drawn from the Clan's roster so they are cats the player knows.
     */
    static void escort(ServerLevel level, net.minecraft.server.level.ServerPlayer p, Clan c, CampaignData cd) {
        LifeData life = LifeData.get(level.getServer().overworld());
        UUID id = life.clanId(c);
        if (id == null) return;
        // three different Clanmates; the leader walks at the head of the whole Clan, not beside one cat
        List<String> roster = new ArrayList<>(cd.roster.getOrDefault(c, List.of()));
        roster.removeIf(line -> line.split("\\|")[0].equalsIgnoreCase(life.leader(c)));
        RandomSource r = RandomSource.create(p.getUUID().getLeastSignificantBits());
        java.util.Collections.shuffle(roster, new java.util.Random(r.nextLong()));
        for (int i = 0; i < Math.min(3, roster.size()); i++) {
            String[] f = roster.get(i).split("\\|");
            if (f.length < 3) continue;
            Role role = roleOf(f[1], f[0], life.leader(c));
            if (role == Role.KIT) role = Role.WARRIOR;
            BlockPos at = BookClans.surface(level, p.getBlockX() + 2 + i, p.getBlockZ() + 1);
            String prefix = f[0].replaceAll("(star|paw|kit)$", "");
            WCatEntity cat = BookClans.spawnCat(level, c, id, role, new ClanNames.Name(prefix.isEmpty() ? f[0] : prefix, f[0]),
                    parseInt(f[2]), at, at, r);
            if (cat == null) continue;
            cat.addTag(ESCORT_TAG);
            cat.setOwnerUUID(p.getUUID());
            cat.mode = WCatEntity.CatMode.FOLLOW;
            cat.setCustomName(Component.literal(f[0] + (parseInt(f[2]) == 0 ? " ♂" : " ♀")));
        }
    }

    private static void place(ServerLevel level, BlockPos pos, Block block) {
        if (block != Blocks.AIR && level.getBlockState(pos).canBeReplaced()) level.setBlock(pos, block.defaultBlockState(), 3);
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }
}
