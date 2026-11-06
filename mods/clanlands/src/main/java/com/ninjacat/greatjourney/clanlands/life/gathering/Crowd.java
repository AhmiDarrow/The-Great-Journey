package com.ninjacat.greatjourney.clanlands.life.gathering;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.BookLooks;
import com.ninjacat.greatjourney.clanlands.life.ChunkWait;
import com.ninjacat.greatjourney.clanlands.life.ClanNames;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Lead;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Starts;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The cats of the moon's meetings. The Clans' cats who go to a Gathering (or medicine cats at the Moonpool) are
 * scene cats: they wear the names of real cats from their Clan's roster, sit still and cannot be hurt, and are gone
 * once the meeting is over and nobody is close enough to see them go (or a few minutes later at the latest). The
 * camps' own cats stay home: the camp routine owns them and puts them to bed at sunset, and walking them across
 * unloaded land to the island and back would lose some. A scene cat saved with the world never comes back.
 *
 * <p>A guide is different: a real cat of the player's Clan, made for the walk (as the guides who bring newcomers to a
 * camp are), who leads the player there with {@link Lead} and can lead them home after. It wears Lead's guide tag, so a
 * guide cut off by a restart is gone when its land loads again, and it leaves its Clan's list when it goes.
 */
final class Crowd {
    private Crowd() {}

    static final String TAG = "greatjourney_gathering";
    /** A cat that is still there once the meeting is over goes once no player is this close. */
    private static final double OUT_OF_SIGHT = 40;
    /** Cats on their way out: id, and the game time they go at the latest. */
    private static final Map<UUID, Long> FADING = new HashMap<>();

    static void reset() {
        FADING.clear();
    }

    /** A scene cat sitting (or, for a leader, standing) at x, z, turned toward fx, fz. */
    static WCatEntity scene(ServerLevel level, Clan clan, String name, BookClans.Role role, double x, double z, double fx, double fz) {
        WCatEntity cat = ModEntities.WCAT.get().create(level);
        if (cat == null) return null;
        BlockPos at = BookClans.safeSpot(level, (int) Math.floor(x), (int) Math.floor(z), 2);
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        BookClans.rest(level, cat);                                   // still cats have no gravity: right on the ground
        cat.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        BookLooks.clanCat(cat, clan);
        cat.setCustomName(Component.literal(name));
        cat.setNoAi(true);
        cat.setInvulnerable(true);
        cat.setPersistenceRequired();
        cat.addTag(TAG);
        switch (role) {
            case APPRENTICE -> {
                cat.setAppScale(true);
                cat.setAgeInMoons(8);
                cat.setRank(WCatEntity.Rank.APPRENTICE);
            }
            case MEDICINE -> {
                cat.setAgeInMoons(BookClans.ADULT_MOONS);
                cat.setRank(WCatEntity.Rank.MEDICINE);
            }
            default -> cat.setAgeInMoons(BookClans.ADULT_MOONS);   // with no AI the age never updates from 0 (a kit-sized cat)
        }
        if (role != BookClans.Role.LEADER) cat.setInSittingPose(true);
        face(cat, fx, fz);
        level.addFreshEntity(cat);
        return cat;
    }

    /**
     * A warrior (or medicine cat) of the player's Clan steps up beside them to walk with them; null if the Clan has no
     * id yet or nothing could be made. Its name is its own, or {@code name} when given.
     */
    static WCatEntity guide(ServerPlayer p, Clan clan, BookClans.Role role, String name) {
        ServerLevel ow = p.serverLevel();
        UUID id = LifeData.get(ow.getServer().overworld()).clanId(clan);
        ClanWorld w = ClanWorld.current();
        if (id == null || w == null || ow.dimension() != Level.OVERWORLD) return null;
        Landmark camp = Homeland.camp(w, clan);
        RandomSource r = p.getRandom();
        double a = r.nextDouble() * Math.PI * 2;
        BlockPos at = BookClans.safeSpot(ow, (int) Math.floor(p.getX() + Math.cos(a) * 3), (int) Math.floor(p.getZ() + Math.sin(a) * 3), 4);
        BlockPos home = BookClans.surface(ow, (int) Math.floor(camp.x()), (int) Math.floor(camp.z()));
        ClanNames.Name n = name != null ? new ClanNames.Name(name, name)
                : new ClanNames(ow.getSeed() ^ p.getUUID().getMostSignificantBits() ^ ow.getGameTime() ^ (clan.ordinal() + 1) * 0x2545F491L).warrior();
        WCatEntity cat = BookClans.spawnCat(ow, clan, id, role, n, r.nextInt(2), at, home, r);
        if (cat == null) return null;
        cat.wanderCenter = at;
        cat.addTag(Lead.GUIDE_TAG);
        return cat;
    }

    /** A guide who has arrived sits down where it is, turned toward fx, fz, and keeps still until it is needed again. */
    static void settle(WCatEntity cat, double fx, double fz) {
        cat.getNavigation().stop();
        cat.setNoAi(true);
        cat.setInSittingPose(true);
        face(cat, fx, fz);
    }

    /** A settled guide gets up again to walk. */
    static void rouse(WCatEntity cat) {
        cat.setNoAi(false);
        cat.setInSittingPose(false);
        cat.setOrderedToSit(false);
    }

    private static void face(Entity cat, double fx, double fz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(fx - cat.getX()), fz - cat.getZ()));
        cat.setYRot(yaw);
        cat.setYHeadRot(yaw);
        if (cat instanceof WCatEntity c) c.setYBodyRot(yaw);
    }

    /**
     * Leads the player home to their camp: through {@code first} (the way off the island), round the lake rather
     * than across it, and in through the camp's way in. Null if the cat could not lead.
     */
    static Lead leadHome(ServerPlayer p, WCatEntity cat, Clan clan, List<double[]> first, Consumer<Lead> home) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return null;
        Landmark camp = Homeland.camp(w, clan);
        List<Lead.Stop> stops = new ArrayList<>();
        double fx = p.getX(), fz = p.getZ();
        for (double[] pt : first) {
            stops.add(new Lead.Stop(pt[0], pt[1], 6, null));
            fx = pt[0];
            fz = pt[1];
        }
        for (double[] pt : Starts.wayShore(w, fx, fz, camp.x(), camp.z())) stops.add(new Lead.Stop(pt[0], pt[1], 12, null));
        List<double[]> in = Starts.wayIn(w, camp);
        for (int i = 0; i < in.size(); i++) {
            double[] pt = in.get(i);
            boolean last = i == in.size() - 1;
            stops.add(new Lead.Stop(pt[0], pt[1], last ? Math.min(camp.radius(), 14) : 8, last ? home : null));
        }
        rouse(cat);
        return Lead.start(p, cat, stops, (l, how) -> fade(l.cat(), 20 * 60));
    }

    /**
     * Takes the player to x, z once the land there has loaded, when no cat can walk them (they are not in the lake's
     * world, or no guide could be made). Their respawn point stays where it was.
     */
    static void carry(ServerPlayer p, double x, double z, Consumer<ServerPlayer> then) {
        ServerLevel ow = p.getServer().overworld();
        UUID id = p.getUUID();
        ChunkWait.whenLoaded(ow, x, z, 16, () -> {
            ServerPlayer q = ow.getServer().getPlayerList().getPlayer(id);
            if (q == null) return;
            BlockPos at = BookClans.safeSpot(ow, (int) Math.floor(x), (int) Math.floor(z), 6);
            q.fallDistance = 0;
            q.teleportTo(ow, at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, q.getYRot(), 0);
            Dialog.hold(q, 40);
            if (then != null) then.accept(q);
        });
    }

    /** The cat goes once no player is in sight of it, and after {@code maxTicks} whatever happens. */
    static void fade(Entity cat, long maxTicks) {
        if (cat != null) FADING.put(cat.getUUID(), cat.level().getGameTime() + maxTicks);
    }

    static void fade(ServerLevel level, Collection<UUID> cats, long maxTicks) {
        long until = level.getGameTime() + maxTicks;
        for (UUID u : cats) if (u != null) FADING.put(u, until);
    }

    /** Removes a cat now: a guide also leaves its Clan's list, and stops leading. */
    static void dismiss(ServerLevel level, UUID id) {
        if (id == null) return;
        FADING.remove(id);
        Entity e = level.getServer().overworld().getEntity(id);
        if (e == null) return;
        if (e instanceof WCatEntity cat && cat.getTags().contains(Lead.GUIDE_TAG)) {
            ClanData.get(level.getServer().overworld()).removeClanCatFromAnyClan(cat);
        }
        e.discard();
    }

    /** Once a second: cats on their way out go when nobody is looking, or when their time is up (never mid-lead). */
    static void tick(MinecraftServer server) {
        if (FADING.isEmpty()) return;
        ServerLevel ow = server.overworld();
        long now = ow.getGameTime();
        for (UUID id : List.copyOf(FADING.keySet())) {
            Entity e = ow.getEntity(id);
            if (e == null) {
                FADING.remove(id);            // unloaded: saved with its tag, it never comes back
                continue;
            }
            if (Lead.leading(e)) continue;
            boolean seen = false;
            for (ServerPlayer p : ow.players()) seen |= !p.isSpectator() && p.distanceTo(e) < OUT_OF_SIGHT;
            if (!seen || now >= FADING.get(id)) dismiss(ow, id);
        }
    }

    /** Scene cats saved with the world are turned away when they load. */
    static void entityJoined(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !e.loadedFromDisk()) return;
        if (e.getEntity().getTags().contains(TAG)) e.setCanceled(true);
    }

    /**
     * Up to {@code n} names of the Clan's own cats whose rank reads {@code rank} (WARRIOR, DEPUTY, APPRENTICE,
     * MEDICINE...), as Warrior Cats Events keeps them on the Clan's list: cats not loaded anywhere come first, so a
     * player at camp does not see the same cat in two places. Topped up with new warrior or apprentice names.
     */
    static List<String> names(ServerLevel level, Clan clan, String rank, int n, RandomSource r, Set<String> taken) {
        List<String> loose = new ArrayList<>(), loaded = new ArrayList<>();
        ServerLevel ow = level.getServer().overworld();
        LifeData life = LifeData.get(ow);
        UUID id = life.clanId(clan);
        ClanData.Clan wc = id == null ? null : ClanData.get(ow).getClan(id);
        if (wc != null) {
            for (ClanData.ClanCat cc : wc.clanCats.values()) {
                if (cc.catRank == null || cc.catName == null || !cc.catRank.toUpperCase(Locale.ROOT).contains(rank)) continue;
                String name = plain(cc.catName.getString());
                if (name.isEmpty() || taken.contains(name) || name.equals(life.leader(clan))) continue;
                (ow.getEntity(cc.catUUID) == null ? loose : loaded).add(name);
            }
        }
        java.util.Collections.shuffle(loose, new java.util.Random(r.nextLong()));
        java.util.Collections.shuffle(loaded, new java.util.Random(r.nextLong()));
        List<String> out = new ArrayList<>();
        for (String s : loose) if (out.size() < n) out.add(s);
        for (String s : loaded) if (out.size() < n) out.add(s);
        ClanNames gen = new ClanNames(r.nextLong());
        for (int tries = 0; out.size() < n && tries < 20; tries++) {
            String s = (rank.equals("APPRENTICE") ? gen.apprentice() : gen.warrior()).full();
            if (!taken.contains(s) && !out.contains(s)) out.add(s);
        }
        taken.addAll(out);
        return out;
    }

    /** A cat's name without Warrior Cats Events' gender sign. */
    static String plain(String name) {
        return name.replace("♂", "").replace("♀", "").trim();
    }

    static String plain(Entity e) {
        return e == null || e.getCustomName() == null ? "" : plain(e.getCustomName().getString());
    }

    /** The player's lake Clan, or null for kittypets, loners and players of any other Clan. */
    static Clan clanOf(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        return wc == null ? null : LifeData.get(p.getServer().overworld()).clanOf(wc.clanUUID);
    }

    /** The player's rank in their Clan (Warrior Cats Events' own), or null. */
    static ClanData.ClanPlayerRank rank(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        return wc == null ? null : wc.members.get(p.getUUID());
    }

    static boolean near(Entity e, double x, double z, double r) {
        return e.level().dimension() == Level.OVERWORLD && Math.hypot(e.getX() - x, e.getZ() - z) <= r;
    }
}
