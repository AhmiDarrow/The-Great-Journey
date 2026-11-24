package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The camps' nests beyond the cats': each player's own nest (one per player, in their own den, where they wake),
 * and which camps have had every den's beds laid, so a camp filled before dens had their full set gets the rest once.
 */
public final class Nests extends SavedData {
    private static final String ID = "greatjourney_nests";

    /** A player's nest: the camp (see {@link #key}), the den and the block its bed is on. */
    public record Claim(String camp, DenKind den, BlockPos pos) {}

    final Map<UUID, Claim> claims = new HashMap<>();
    final Set<String> furnished = new HashSet<>();

    public static Nests get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new Factory<>(Nests::new, Nests::load), ID);
    }

    private static Nests load(CompoundTag tag, HolderLookup.Provider registries) {
        Nests n = new Nests();
        for (Tag t : tag.getList("furnished", Tag.TAG_STRING)) n.furnished.add(t.getAsString());
        for (Tag t : tag.getList("claims", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            try {
                n.claims.put(c.getUUID("player"), new Claim(c.getString("camp"), DenKind.valueOf(c.getString("den")),
                        new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z"))));
            } catch (IllegalArgumentException ignored) {
                // an unknown den in an old save: the player claims a nest again when they next need one
            }
        }
        return n;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag f = new ListTag();
        for (String s : furnished) f.add(StringTag.valueOf(s));
        tag.put("furnished", f);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Claim> e : claims.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("player", e.getKey());
            c.putString("camp", e.getValue().camp());
            c.putString("den", e.getValue().den().name());
            c.putInt("x", e.getValue().pos().getX());
            c.putInt("y", e.getValue().pos().getY());
            c.putInt("z", e.getValue().pos().getZ());
            list.add(c);
        }
        tag.put("claims", list);
        return tag;
    }

    /** A camp's name in the save: the same landmark in every run of the world. */
    static String key(Landmark camp) {
        return camp.kind().name() + "@" + (int) Math.floor(camp.x()) + "," + (int) Math.floor(camp.z());
    }

    /** The player's nest, or null before they have one. */
    public static Claim of(ServerPlayer p) {
        return get(p.getServer().overworld()).claims.get(p.getUUID());
    }

    /** Notes a camp's beds as laid (a newly filled camp lays them all). */
    public static void markFurnished(ServerLevel level, Landmark camp) {
        Nests n = get(level.getServer().overworld());
        if (n.furnished.add(key(camp))) n.setDirty();
    }

    /**
     * A camp filled before every den had its full set of nests: the first time its chunk loads, the missing beds are
     * laid (over the moss carpet the painter put there), once.
     */
    public static void furnishIfDue(ServerLevel level, Landmark camp, ChunkPos loaded) {
        if (!new ChunkPos(BlockPos.containing(camp.x(), 0, camp.z())).equals(loaded)) return;
        Nests n = get(level.getServer().overworld());
        if (!n.furnished.add(key(camp))) return;
        n.setDirty();
        level.getServer().execute(() -> ChunkWait.whenLoaded(level, camp.x(), camp.z(), (int) camp.radius(), () -> BookClans.furnish(level, camp)));
    }

    /** Gives the player a nest of their own in their Clan's camp, in a den of this kind, and makes it where they wake. */
    public static BlockPos claim(ServerPlayer p, Clan clan, DenKind kind) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return null;
        // on the Great Journey the old camps stand empty: the nest comes when the Clan settles at the lake (rehome)
        ServerLevel ow = p.getServer().overworld();
        if (Homeland.forest() && Mode.of(ow) == Mode.CAMPAIGN
                && com.ninjacat.greatjourney.clanlands.life.campaign.CampaignData.get(ow).act() >= 3) return null;
        return claim(p, Homeland.camp(w, clan), kind);
    }

    /** The den a Clan player sleeps in, by their place in the Clan. */
    public static DenKind denFor(ServerPlayer p, ClanData.Clan wc) {
        ClanData.ClanPlayerRank rank = wc == null ? null : wc.members.get(p.getUUID());
        if (com.ninjacat.greatjourney.clanlands.life.quests.Ranks.leads(p)) return DenKind.LEADER;
        if (rank != null) {
            switch (rank) {
                case MEDICINE, MEDICINEAPP -> {
                    return DenKind.MEDICINE;
                }
                case ELDER -> {
                    return DenKind.ELDERS;
                }
                case QUEEN, KIT -> {
                    return DenKind.NURSERY;
                }
                default -> { }
            }
        }
        return com.ninjacat.greatjourney.clanlands.life.quests.QuestState.storyStep(p) >= com.ninjacat.greatjourney.clanlands.life.quests.QuestState.WARRIOR
                ? DenKind.WARRIORS : DenKind.APPRENTICES;
    }

    /** The warrior ceremony: the new warrior moves to a nest in the warriors' den. */
    public static void warrior(ServerPlayer p, Clan clan) {
        claim(p, clan, DenKind.WARRIORS);
    }

    static BlockPos claim(ServerPlayer p, Landmark camp, DenKind kind) {
        CampPlan plan = CampPlan.of(camp);
        if (plan == null) return null;
        ServerLevel ow = p.getServer().overworld();
        Nests n = get(ow);
        Claim mine = n.claims.get(p.getUUID());
        BlockPos pick = mine != null && mine.camp().equals(key(camp)) && mine.den() == kind && BookClans.bed(ow, mine.pos()) ? mine.pos() : null;
        if (pick == null) {
            // a nest no other player has and no cat calls home, from the den's last nests (the cats fill it from the first);
            // only one whose bed is really there: in a world dug before the den grew, the outer nests are still earth
            Set<BlockPos> taken = new HashSet<>();
            for (Map.Entry<UUID, Claim> e : n.claims.entrySet()) if (!e.getKey().equals(p.getUUID())) taken.add(e.getValue().pos());
            for (WCatEntity cat : ow.getEntitiesOfClass(WCatEntity.class, new AABB(camp.x() - 80, -64, camp.z() - 80, camp.x() + 80, 320, camp.z() + 80),
                    WCatEntity::hasHomePosition)) {
                taken.add(cat.getHomePosition());
            }
            int count = plan.nests(kind).size();
            for (int i = count - 1; i >= 0 && pick == null; i--) {
                BlockPos at = BookClans.nestBlock(ow, plan, kind, i);
                if (!taken.contains(at) && BookClans.bed(ow, at)) pick = at;
            }
            for (int i = count - 1; i >= 0 && pick == null; i--) {                       // a full den: share a nest
                BlockPos at = BookClans.nestBlock(ow, plan, kind, i);
                if (BookClans.bed(ow, at)) pick = at;
            }
            if (pick == null) return null;                                              // no bed fits: they keep where they wake
        }
        n.claims.put(p.getUUID(), new Claim(key(camp), kind, pick));
        n.setDirty();
        p.setRespawnPosition(Level.OVERWORLD, pick, 0, true, false);
        return pick;
    }

    /**
     * A Clan settles a new camp (the Campaign's lake): its players online move their nests there, into the same den
     * (or, joined on the road with none yet, the apprentices' or warriors' den by their rank). A player offline then
     * keeps waking where they did until they next get a nest.
     */
    public static void rehome(ServerLevel level, Clan clan, Landmark camp) {
        Nests n = get(level.getServer().overworld());
        LifeData life = LifeData.get(level.getServer().overworld());
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            Claim c = n.claims.get(p.getUUID());
            if (c != null && c.camp().equals(key(camp))) continue;
            ClanData.Clan wc = Joining.clanOf(p);
            if (wc == null || life.clanOf(wc.clanUUID) != clan) continue;
            DenKind den = c != null ? c.den() : denFor(p, wc);
            claim(p, camp, den);
        }
    }
}
