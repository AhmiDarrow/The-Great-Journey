package com.ninjacat.greatjourney.clanlore.moon;

import com.ninjacat.greatjourney.clanlore.LoreHooks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.snowteb.warriorcats_events.attachments.ModAttachments;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The full-moon truce at work. A cat here is a player or a Warrior Cats Events cat; two cats are at peace under the
 * truce unless both belong to the same Clan (a Clan's own quarrels are its own business, and a cat of no Clan is
 * sheltered like any other). While the truce holds, a blow between cats at peace does nothing, and Clan cats do not
 * pick a cat of another Clan as a target (one already set on another is called off each second).
 *
 * <p>A player's blow counts against them: the first ones (as many as {@code warnings}) are only a warning, and the
 * next within a minute breaks the truce. The moon clouds over (a short rain), everyone near hears it, and the
 * Gathering ends for the night; the peace itself goes on holding until dawn. Kiki's game should never turn a
 * misclick into a brawl.
 */
final class Truce {
    private Truce() {}

    /** Blows a player has struck at a Gathering, and when the last one fell: {count, gameTime}. */
    private static final Map<UUID, long[]> STRIKES = new HashMap<>();
    private static final long STRIKE_MEMORY = 20L * 60;

    static void init() {
        NeoForge.EVENT_BUS.addListener(Truce::attack);
        NeoForge.EVENT_BUS.addListener(Truce::damage);
        NeoForge.EVENT_BUS.addListener(Truce::target);
        NeoForge.EVENT_BUS.addListener(Truce::levelTick);
    }

    static void reset() {
        STRIKES.clear();
    }

    /** A player swings at a cat: under the truce the blow never lands. */
    private static void attack(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        Entity victim = e.getTarget();
        if (!atPeace(p, victim) || !Moon.truce(level, victim.blockPosition())) return;
        e.setCanceled(true);
        strike(level, p, victim.blockPosition());
    }

    /** Any other hurt between cats at peace (a claw, an arrow, a pet's bite counts as its owner's) is cancelled. */
    private static void damage(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        Entity by = e.getSource().getEntity();
        if (by == null || by == victim || !atPeace(by, victim) || !Moon.truce(level, victim.blockPosition())) return;
        e.setCanceled(true);
        // a player's own blow was already counted when it was swung (it never reaches here); arrows and the like count now
        if (by instanceof ServerPlayer p && e.getSource().getDirectEntity() != p) strike(level, p, victim.blockPosition());
    }

    /** A Clan cat turning on a cat of another Clan under the truce thinks better of it. */
    private static void target(LivingChangeTargetEvent e) {
        if (!(e.getEntity() instanceof WCatEntity cat) || !(cat.level() instanceof ServerLevel level)) return;
        LivingEntity t = e.getNewAboutToBeSetTarget();
        if (t == null || !MoonConfig.get(MoonConfig.STOP_TARGETING::get, true)) return;
        if (atPeace(cat, t) && Moon.truce(level, cat.blockPosition())) e.setCanceled(true);
    }

    /** Once a second in a level with a Gathering place under the truce: Clan cats already set on another Clan stand down. */
    private static void levelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 7) return;
        if (!MoonConfig.get(MoonConfig.STOP_TARGETING::get, true)) return;
        for (Moon.TruceArea a : Moon.truceAreas()) {
            if (a.dimension() != level.dimension()) continue;
            BlockPos centre = BlockPos.containing(a.x(), level.getSeaLevel(), a.z());
            if (!level.isLoaded(centre) || !Moon.truce(level, centre)) continue;
            AABB box = new AABB(a.x() - a.radius(), level.getMinBuildHeight(), a.z() - a.radius(), a.x() + a.radius(), level.getMaxBuildHeight(), a.z() + a.radius());
            for (WCatEntity cat : level.getEntitiesOfClass(WCatEntity.class, box)) {
                LivingEntity t = cat.getTarget();
                if (t != null && atPeace(cat, t)) cat.setTarget(null);
            }
        }
    }

    /** Whether both are cats, and not of the same Clan (a pet or a projectile counts as whoever owns it). */
    static boolean atPeace(Entity a, Entity b) {
        a = owner(a);
        b = owner(b);
        if (a == null || b == null || a == b || !isCat(a) || !isCat(b)) return false;
        UUID ca = clanOf(a), cb = clanOf(b);
        return ca == null || !Objects.equals(ca, cb);
    }

    private static Entity owner(Entity e) {
        if (e instanceof WCatEntity) return e;
        if (e instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwner() != null) return o.getOwner();
        return e;
    }

    static boolean isCat(Entity e) {
        return e instanceof Player || e instanceof WCatEntity;
    }

    /** The Warrior Cats Events Clan a cat belongs to, or null. */
    static UUID clanOf(Entity e) {
        UUID id = null;
        if (e instanceof Player p) id = p.getData(ModAttachments.PLAYER_WCE_DATA.get()).getCurrentClanUUID();
        else if (e instanceof WCatEntity c) id = c.getClanUUID();
        return id == null || id.equals(ClanData.EMPTY_UUID) ? null : id;
    }

    /** A player struck at the Gathering: a warning, or the truce is broken. */
    private static void strike(ServerLevel level, ServerPlayer p, BlockPos where) {
        long now = level.getGameTime();
        long[] s = STRIKES.computeIfAbsent(p.getUUID(), k -> new long[2]);
        if (now - s[1] > STRIKE_MEMORY) s[0] = 0;
        s[0]++;
        s[1] = now;
        if (Moon.truceAreaAt(level, where) == null && !MoonConfig.get(MoonConfig.TRUCE_EVERYWHERE::get, false)) {
            // on the way to the Gathering: the blow is stopped, but only a fight at the Gathering itself breaks the truce
            p.displayClientMessage(Component.literal("Sheathe your claws! It is the full moon, and the Clans pass in peace tonight.").withStyle(net.minecraft.ChatFormatting.GOLD), true);
            return;
        }
        if (Moon.cloudedOver(level)) {
            p.displayClientMessage(Component.literal("Clouds already hide the moon. Sheathe your claws: the truce still holds.").withStyle(net.minecraft.ChatFormatting.GRAY), true);
            return;
        }
        int warnings = MoonConfig.get(MoonConfig.WARNINGS::get, 1);
        if (s[0] <= warnings) {
            p.displayClientMessage(Component.literal("Sheathe your claws! It is the full moon, and no cat may fight at a Gathering.").withStyle(net.minecraft.ChatFormatting.GOLD), true);
            return;
        }
        s[0] = 0;
        breakTruce(level, p, where);
    }

    /** The truce is broken: clouds cover the moon, everyone near hears it, and the Gathering is over for the night. */
    static void breakTruce(ServerLevel level, ServerPlayer by, BlockPos where) {
        Moon.markBroken(level);
        Moon.TruceArea area = Moon.truceAreaAt(level, where);
        if (MoonConfig.get(MoonConfig.CLOUD_OVER::get, true) && !level.isRaining() && level.dimensionType().hasSkyLight()) {
            boolean thunder = MoonConfig.get(MoonConfig.THUNDER::get, false);
            level.setWeatherParameters(0, MoonConfig.get(MoonConfig.CLOUD_TICKS::get, 2400), true, thunder);
        }
        LoreHooks.tell(level, clanOf(by), where, List.of(
                LoreHooks.Speech.narrate("Clouds slide across the face of the moon, and the clearing goes dark. A cold wind stirs the fur of every cat."),
                LoreHooks.Speech.narrate("StarClan is angry: the truce has been broken. The Gathering is over for tonight.")));
        Moon.tellBroken(level, by, where, area);
    }
}
