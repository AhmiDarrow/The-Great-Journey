package com.ninjacat.greatjourney.clanlore.traits;

import com.ninjacat.greatjourney.clanlore.Clanlore;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Scars, torn ears, grizzled muzzles and old wounds, kept on the cat (a data attachment, saved with it) and drawn on it.
 * WCE's cats and players both carry them; a player's marks show on their cat shape. Cats gain them by living
 * ({@link TraitEvents}); a pack can also give them outright, for book cats, through {@link #add}.
 */
public final class Traits {
    private Traits() {}

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Clanlore.MOD_ID);

    /** A cat's traits. Players keep theirs through death: a scar does not heal in StarClan's sight. */
    public static final Supplier<AttachmentType<TraitRecord>> DATA = ATTACHMENTS.register("traits",
            () -> AttachmentType.builder(() -> TraitRecord.EMPTY)
                    .serialize(TraitRecord.CODEC, TraitRecord::worthKeeping)
                    .copyOnDeath()
                    .build());

    /** The lame leg's slower step. */
    static final ResourceLocation LAME_MODIFIER = ResourceLocation.fromNamespaceAndPath(Clanlore.MOD_ID, "lame_leg");

    public static void init(IEventBus modBus, ModContainer container) {
        ATTACHMENTS.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, TraitsConfig.SPEC, "clanlore-traits-common.toml");
        modBus.addListener(TraitPayloads::register);
        TraitEvents.init();
        if (FMLEnvironment.dist.isClient()) com.ninjacat.greatjourney.clanlore.client.TraitClient.init(modBus);
    }

    // ---- the API ---------------------------------------------------------------------------------------------------

    /** Gives a cat (or a player) a trait for good; returns false if it already had it. Call on the server. */
    public static boolean add(LivingEntity cat, Trait t) {
        TraitRecord r = record(cat);
        if (t.in(r.mask())) return false;
        set(cat, r.with(t));
        if (t == Trait.LAME_LEG) limp(cat);
        return true;
    }

    /** Takes a trait away (a command, a miracle); returns false if the cat did not have it. */
    public static boolean remove(LivingEntity cat, Trait t) {
        TraitRecord r = record(cat);
        if (!t.in(r.mask())) return false;
        set(cat, r.without(t));
        if (t == Trait.LAME_LEG) {
            AttributeInstance speed = cat.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.removeModifier(LAME_MODIFIER);
        }
        return true;
    }

    public static boolean has(LivingEntity cat, Trait t) {
        return t.in(mask(cat));
    }

    public static Set<Trait> all(LivingEntity cat) {
        EnumSet<Trait> out = EnumSet.noneOf(Trait.class);
        int m = mask(cat);
        for (Trait t : Trait.values()) if (t.in(m)) out.add(t);
        return out;
    }

    public static int mask(LivingEntity cat) {
        return cat.hasData(DATA) ? cat.getData(DATA).mask() : 0;
    }

    /**
     * A cat's age in moons. WCE stops at 12 once a cat is grown, so past that Clan Lore counts on from when it first saw
     * the cat grown, in WCE's own moon length (its kit growth time over 12). A cat never seen grown is taken as 12.
     */
    public static float moons(WCatEntity cat) {
        float wce = cat.getAgeInMoons();
        if (cat.isBaby() || wce < 12f) return wce;
        long since = cat.hasData(DATA) ? cat.getData(DATA).adultSince() : -1L;
        if (since < 0) return Math.max(12f, wce);
        return 12f + (cat.level().getGameTime() - since) / (float) ticksPerMoon(cat);
    }

    /** Makes a grown cat this many moons old, for Clan Lore's count (an elder brought in from a story, say). */
    public static void setMoons(WCatEntity cat, float moons) {
        long back = (long) (Math.max(0f, moons - 12f) * ticksPerMoon(cat));
        set(cat, record(cat).grownAt(cat.level().getGameTime() - back));
    }

    /** The trait mask a WCE cat should be drawn with: its own, or, for a player's cat shape, the player's. */
    public static int shown(WCatEntity cat) {
        UUID bound = cat.getPlayerBoundUuid();
        if (bound != null && !bound.equals(ClanData.EMPTY_UUID) && cat.level() != null) {
            Player p = cat.level().getPlayerByUUID(bound);
            return p == null ? 0 : mask(p);
        }
        return mask(cat);
    }

    // ---- inside --------------------------------------------------------------------------------------------------

    static long ticksPerMoon(WCatEntity cat) {
        return Math.max(20L, 100L * cat.getKitGrowthTimeMinutes());
    }

    static TraitRecord record(LivingEntity cat) {
        return cat.hasData(DATA) ? cat.getData(DATA) : TraitRecord.EMPTY;
    }

    /** Stores a record and, when the visible part changed, tells everyone who can see the cat. */
    static void set(LivingEntity cat, TraitRecord r) {
        int before = mask(cat);
        cat.setData(DATA, r);
        if (r.mask() != before) sync(cat);
    }

    static void sync(LivingEntity cat) {
        if (cat.level() instanceof ServerLevel) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(cat, new TraitPayloads.Sync(cat.getId(), mask(cat)));
        }
    }

    /** Puts the lame leg's slower step on (again: it is not saved, so it follows the config). */
    static void limp(LivingEntity cat) {
        AttributeInstance speed = cat.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        speed.removeModifier(LAME_MODIFIER);
        double slow = TraitsConfig.SPEC.isLoaded() ? TraitsConfig.LAME_SLOWDOWN.get() : 0.08;
        if (slow > 0) speed.addTransientModifier(new AttributeModifier(LAME_MODIFIER, -slow, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
}
