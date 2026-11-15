package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.events.Cats;
import com.ninjacat.greatjourney.clanlore.LoreHooks;
import com.ninjacat.greatjourney.clanlore.role.ClanKeeper;
import com.ninjacat.greatjourney.clanlore.role.Role;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Clan Lore's scenes (ceremonies, the Gathering's news, retirements) told through The Great Journey's dialogue: a
 * witness within 24 blocks sees the window, the Clan's players elsewhere get the lines as a passing subtitle, as for
 * the Clan events.
 * <p>
 * It also gives Clan Lore our word on the Clans we run ({@link Keeper}): their leaders and lives are ours, kept in
 * {@link LifeData} with the succession in {@code events/Deaths}, and a cat's new role moves its nest to its den.
 */
public final class LoreBridge {
    private LoreBridge() {}

    static final int WITNESS = 24, NEAR = 64;

    public static void init() {
        LoreHooks.announcer(LoreBridge::tell);
        Roles.keeper(new Keeper());
    }

    /** Clan Lore's view of our Clans: a cat of a Clan recorded in {@link LifeData} is ours. */
    static final class Keeper implements ClanKeeper {
        private static Clan clan(WCatEntity cat) {
            if (!(cat.level() instanceof ServerLevel level) || cat.getClanUUID() == null) return null;
            return LifeData.get(level.getServer().overworld()).clanOf(cat.getClanUUID());
        }

        private static LifeData life(WCatEntity cat) {
            return LifeData.get(((ServerLevel) cat.level()).getServer().overworld());
        }

        @Override
        public boolean keeps(WCatEntity cat) {
            return clan(cat) != null;
        }

        @Override
        public boolean mentorsPlayer(WCatEntity cat) {
            return com.ninjacat.greatjourney.clanlands.life.quests.Mentors.mentorsPlayer(cat);
        }

        @Override
        public boolean leads(WCatEntity cat) {
            Clan c = clan(cat);
            return c != null && Cats.isLeader(life(cat), c, cat);
        }

        @Override
        public int lives(WCatEntity cat) {
            Clan c = clan(cat);
            return c == null ? 0 : life(cat).lives(c);
        }

        /**
         * A free nest in the den the role sleeps in (the elders' den, the nursery, the leader's den; the warriors' den
         * for a cat leaving the nursery or the elders' den), as the camp's plan lays them out. Only loaded nests are
         * looked at; a den with every nest taken is shared.
         */
        @Override
        public boolean rehome(WCatEntity cat, Role role) {
            Clan c = clan(cat);
            if (c == null || !(cat.level() instanceof ServerLevel level) || level != level.getServer().overworld()) return false;
            Landmark camp = Cats.camp(c);
            CampPlan plan = camp == null ? null : CampPlan.of(camp);
            if (plan == null) return false;
            BlockPos home = cat.getHomePosition();
            DenKind kind = switch (role) {
                case ELDER -> DenKind.ELDERS;
                case QUEEN -> DenKind.NURSERY;
                case LEADER -> DenKind.LEADER;
                default -> DenKind.WARRIORS;
            };
            if (role == Role.NONE && !in(level, plan, DenKind.NURSERY, home) && !in(level, plan, DenKind.ELDERS, home)) return true;
            if (plan.den(kind) == null) return false;
            if (in(level, plan, kind, home)) return true;
            Set<BlockPos> taken = new HashSet<>();
            for (Nests.Claim claim : Nests.get(level).claims.values()) taken.add(claim.pos());
            for (WCatEntity o : level.getEntitiesOfClass(WCatEntity.class, new AABB(camp.x() - 80, level.getMinBuildHeight(), camp.z() - 80,
                    camp.x() + 80, level.getMaxBuildHeight(), camp.z() + 80), x -> x != cat && x.hasHomePosition())) {
                taken.add(o.getHomePosition());
            }
            BlockPos shared = null;
            for (int i = 0; i < plan.nests(kind).size(); i++) {
                double[] at = plan.nest(kind, i);
                if (!level.hasChunk((int) Math.floor(at[0]) >> 4, (int) Math.floor(at[1]) >> 4)) continue;
                BlockPos nest = BookClans.nestBlock(level, plan, kind, i);
                if (!taken.contains(nest)) {
                    cat.setHomePosition(nest);
                    return true;
                }
                if (shared == null) shared = nest;
            }
            if (shared == null) return false;
            cat.setHomePosition(shared);
            return true;
        }

        /** Whether a nest of this den is at {@code pos} (looking only at loaded nests). */
        private static boolean in(ServerLevel level, CampPlan plan, DenKind kind, BlockPos pos) {
            if (pos == null || plan.den(kind) == null) return false;
            for (int i = 0; i < plan.nests(kind).size(); i++) {
                double[] at = plan.nest(kind, i);
                if (Math.abs(at[0] - pos.getX()) > 1.5 || Math.abs(at[1] - pos.getZ()) > 1.5) continue;
                if (level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && BookClans.nestBlock(level, plan, kind, i).equals(pos)) return true;
            }
            return false;
        }
    }

    static void tell(ServerLevel level, UUID clanId, BlockPos where, List<LoreHooks.Speech> speech) {
        List<Dialog.Line> lines = new ArrayList<>();
        for (LoreHooks.Speech s : speech) {
            Dialog.Line l = s.speaker() == null ? Dialog.Line.narrate(s.text()) : Dialog.Line.say(s.speaker(), s.colour(), s.text());
            if (s.speakerEntity() != null && level.getEntity(s.speakerEntity()) != null) l = l.speakerEntity(level.getEntity(s.speakerEntity()));
            lines.add(l);
        }
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            UUID mine = p.getData(net.snowteb.warriorcats_events.attachments.ModAttachments.PLAYER_WCE_DATA.get()).getCurrentClanUUID();
            boolean member = clanId != null && clanId.equals(mine);
            double d2 = where == null || p.level() != level ? Double.MAX_VALUE : p.blockPosition().distSqr(where);
            if (!member && d2 > NEAR * NEAR) continue;
            if (d2 <= WITNESS * WITNESS) Dialog.play(p, lines);
            else Dialog.subtitle(p, lines);
        }
    }
}
