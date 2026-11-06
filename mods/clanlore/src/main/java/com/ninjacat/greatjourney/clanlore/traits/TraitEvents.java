package com.ninjacat.greatjourney.clanlore.traits;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.snowteb.warriorcats_events.diseases.Diseaseable;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.diseases.kinds.BrokenPaw;
import net.snowteb.warriorcats_events.entity.custom.BadgerEntity;
import net.snowteb.warriorcats_events.entity.custom.EagleEntity;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * How cats come by their marks, all on the server:
 * <ul>
 *   <li>A fight that costs a cat most of its health (to a mob or another cat) may leave a scar or a torn or nicked ear,
 *       once the cat has come through it: ten quiet seconds alive. An eagle's or a badger's claws can, very rarely,
 *       take an eye.</li>
 *   <li>A cat's muzzle greys when it grows old, counted in WCE's own moon length.</li>
 *   <li>A broken paw sometimes heals badly and leaves a lame leg; more often when nobody wrapped it.</li>
 * </ul>
 * Traits are sent to a player as they start seeing a cat, and players get their own on joining and respawning.
 */
final class TraitEvents {
    private TraitEvents() {}

    /** A fight in progress: what the cat has lost to enemies, and when it was last hit. */
    private static final class Fight {
        float lost;
        long lastHit;
        boolean wild;      // an eagle or a badger did some of it
    }

    /** Ten seconds without a blow and the fight is over. */
    private static final int CALM = 200;

    private static final Map<LivingEntity, Fight> FIGHTS = new WeakHashMap<>();
    /** Cats with a broken paw just now, and whether it was wrapped when last seen. */
    private static final Map<LivingEntity, Boolean> BROKEN = new WeakHashMap<>();

    private static final Trait[] BATTLE_MARKS = {Trait.SCAR_FACE, Trait.SCAR_FLANK, Trait.TORN_EAR, Trait.NICKED_EAR};

    static void init() {
        NeoForge.EVENT_BUS.addListener(TraitEvents::onDamage);
        NeoForge.EVENT_BUS.addListener(TraitEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(TraitEvents::onEntityTick);
        NeoForge.EVENT_BUS.addListener(TraitEvents::onJoin);
        NeoForge.EVENT_BUS.addListener(TraitEvents::onStartTracking);
        NeoForge.EVENT_BUS.addListener(TraitEvents::onLogin);
        NeoForge.EVENT_BUS.addListener(TraitEvents::onRespawn);
        NeoForge.EVENT_BUS.addListener(TraitEvents::onChangedDimension);
    }

    private static boolean counts(LivingEntity e) {
        return e instanceof WCatEntity || (e instanceof Player && TraitsConfig.PLAYERS.get());
    }

    // ---- battle scars --------------------------------------------------------------------------------------------

    private static void onDamage(LivingDamageEvent.Post event) {
        LivingEntity cat = event.getEntity();
        if (cat.level().isClientSide() || !TraitsConfig.BATTLE_SCARS.get() || !counts(cat)) return;
        Entity by = event.getSource().getEntity();
        if (!(by instanceof LivingEntity) || by == cat) return;
        long now = cat.level().getGameTime();
        Fight f = FIGHTS.get(cat);
        if (f == null || now - f.lastHit > CALM) {
            f = new Fight();
            FIGHTS.put(cat, f);
        }
        f.lost += event.getNewDamage();
        f.lastHit = now;
        if (by instanceof EagleEntity || by instanceof BadgerEntity) f.wild = true;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (FIGHTS.isEmpty() || event.getServer().getTickCount() % 20 != 0) return;
        List<Map.Entry<LivingEntity, Fight>> over = new ArrayList<>();
        for (Iterator<Map.Entry<LivingEntity, Fight>> it = FIGHTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<LivingEntity, Fight> e = it.next();
            LivingEntity cat = e.getKey();
            if (cat == null || cat.isRemoved() || !cat.isAlive()) {
                it.remove();
            } else if (cat.level().getGameTime() - e.getValue().lastHit >= CALM) {
                over.add(e);
                it.remove();
            }
        }
        for (Map.Entry<LivingEntity, Fight> e : over) survived(e.getKey(), e.getValue());
    }

    /** A cat came through a fight: if it was badly hurt, it may keep a mark. */
    private static void survived(LivingEntity cat, Fight f) {
        if (f.lost < cat.getMaxHealth() * TraitsConfig.BADLY_HURT.get()) return;
        RandomSource r = cat.getRandom();
        if (f.wild && r.nextDouble() < TraitsConfig.MISSING_EYE_CHANCE.get() && Traits.add(cat, Trait.MISSING_EYE)) return;
        if (r.nextDouble() >= TraitsConfig.SCAR_CHANCE.get()) return;
        List<Trait> open = new ArrayList<>();
        for (Trait t : BATTLE_MARKS) if (!Traits.has(cat, t)) open.add(t);
        if (!open.isEmpty()) Traits.add(cat, open.get(r.nextInt(open.size())));
    }

    // ---- age and old wounds, checked now and then on each cat ------------------------------------------------------

    private static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity e) || e.level().isClientSide()) return;
        if (!(e instanceof WCatEntity) && !(e instanceof Player)) return;
        if ((e.tickCount + e.getId()) % 20 != 0) return;
        if (TraitsConfig.LAME_LEGS.get() && counts(e)) paw(e);
        if (e instanceof WCatEntity cat && (e.tickCount + e.getId()) % 1200 == 0) age(cat);
    }

    /** Watches a broken paw heal; when it is gone, it may have healed crooked. */
    private static void paw(LivingEntity e) {
        if (!((Object) e instanceof Diseaseable<?> d)) return;
        if (d.hasDisease(DiseaseTypes.BROKEN_PAW)) {
            boolean wrapped = d.getDisease(DiseaseTypes.BROKEN_PAW) instanceof BrokenPaw bp && bp.isBoneWrapped();
            BROKEN.merge(e, wrapped, (a, b) -> a || b);
            return;
        }
        Boolean wasWrapped = BROKEN.remove(e);
        if (wasWrapped == null || !e.isAlive()) return;
        double chance = wasWrapped ? TraitsConfig.LAME_CHANCE_WRAPPED.get() : TraitsConfig.LAME_CHANCE.get();
        if (e.getRandom().nextDouble() < chance) Traits.add(e, Trait.LAME_LEG);
    }

    /** Notes when a cat is first seen grown, and greys its muzzle once it is old. */
    private static void age(WCatEntity cat) {
        if (cat.isBaby() || cat.getAgeInMoons() < 12f) return;
        TraitRecord r = Traits.record(cat);
        if (r.adultSince() < 0) {
            Traits.set(cat, r.grownAt(cat.level().getGameTime()));
            return;
        }
        if (TraitsConfig.GRIZZLE.get() && !Trait.GRIZZLED_MUZZLE.in(r.mask()) && Traits.moons(cat) >= TraitsConfig.GRIZZLE_MOONS.get()) {
            Traits.add(cat, Trait.GRIZZLED_MUZZLE);
        }
    }

    // ---- keeping the lame step and the client in step ------------------------------------------------------------

    private static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity e)) return;
        if (Traits.has(e, Trait.LAME_LEG)) Traits.limp(e);
    }

    private static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof LivingEntity e && event.getEntity() instanceof ServerPlayer p) {
            int m = Traits.mask(e);
            if (m != 0) PacketDistributor.sendToPlayer(p, new TraitPayloads.Sync(e.getId(), m));
        }
    }

    private static void self(Player player) {
        if (player instanceof ServerPlayer p && Traits.mask(p) != 0) {
            PacketDistributor.sendToPlayer(p, new TraitPayloads.Sync(p.getId(), Traits.mask(p)));
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        self(event.getEntity());
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        self(event.getEntity());
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        self(event.getEntity());
    }
}
