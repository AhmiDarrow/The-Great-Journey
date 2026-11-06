package com.ninjacat.greatjourney.clanlore.moon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The moon in Clan words, and the truce of the full moon. Warrior Cats Events never reads the moon (its "There is a
 * gathering tonight" is only a title splash), so Clan Lore does: the phase as the Clans name it, whether tonight is a
 * Gathering night or a half moon, and the truce that holds at a Gathering place on a full-moon night.
 *
 * <p>The phase is vanilla's: the moon goes through its eight shapes once every eight days, full on the first. Better
 * Days (in The Great Journey) changes only how fast the clock runs, never the day count, so the phases fall the same
 * with it; its night runs from 12500 to 23500 and so does the Clans' night here (both can be moved in
 * {@code clanlore-moon-common.toml}). A whole night falls inside one day's count, so the moon a cat sees at dusk is the moon
 * it sees before dawn, and the afternoon before a full-moon night already reads as the full moon.
 *
 * <p>A pack registers its Gathering places as truce areas ({@link #truceArea}); in them, on a Gathering night, cats and
 * players of different Clans cannot hurt each other. A player who strikes anyway is warned first; after that the truce
 * is broken: clouds cover the moon and the Gathering is over for the night ({@link #onTruceBroken}).
 */
public final class Moon {
    private Moon() {}

    public static final long DAY = 24000L;

    /** The moon's eight shapes, as vanilla counts them (full first), in the words the Clans use. */
    public enum Phase {
        FULL("the full moon"),
        WANING_GIBBOUS("a waning moon"),
        HALF_WANING("the half moon"),
        WANING_CRESCENT("a thin waning moon"),
        NEW("the dark of the moon"),
        WAXING_CRESCENT("a thin waxing moon"),
        HALF_WAXING("the half moon"),
        WAXING_GIBBOUS("a waxing moon");

        /** How a cat says it: "Tonight is the full moon", "It is the dark of the moon". */
        public final String words;

        Phase(String words) {
            this.words = words;
        }

        public static Phase of(int vanillaPhase) {
            return values()[Math.floorMod(vanillaPhase, 8)];
        }

        public boolean full() {
            return this == FULL;
        }

        public boolean half() {
            return this == HALF_WANING || this == HALF_WAXING;
        }

        /** The dark of the moon: no moon at all. */
        public boolean dark() {
            return this == NEW;
        }

        public boolean waxing() {
            return this == WAXING_CRESCENT || this == HALF_WAXING || this == WAXING_GIBBOUS;
        }

        public boolean waning() {
            return this == WANING_GIBBOUS || this == HALF_WANING || this == WANING_CRESCENT;
        }
    }

    /** A Gathering place: a circle (x, z, radius) in one dimension where the full-moon truce holds. */
    public record TruceArea(String id, ResourceKey<Level> dimension, double x, double z, double radius) {
        public boolean contains(Level level, BlockPos pos) {
            if (level.dimension() != dimension) return false;
            double dx = pos.getX() + 0.5 - x, dz = pos.getZ() + 0.5 - z;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    /** Told when a player breaks the truce (once a night): the pack ends its Gathering scene. */
    @FunctionalInterface
    public interface TruceBroken {
        /** {@code area} is null when the truce held everywhere ({@code truceEverywhere}). */
        void broken(ServerLevel level, ServerPlayer by, BlockPos where, TruceArea area);
    }

    private static final Map<String, TruceArea> AREAS = new ConcurrentHashMap<>();
    private static final List<TruceBroken> LISTENERS = new CopyOnWriteArrayList<>();
    /** Levels where a Gathering has been called whatever the moon (operators testing, a pack's own scene). */
    private static final Set<ResourceKey<Level>> HELD = new HashSet<>();
    /** Levels where the Clans are on their way to a Gathering: the truce holds on every path there, not only at the place. */
    private static final Set<ResourceKey<Level>> PASSAGE = new HashSet<>();
    /** The night (see {@link #night}) whose Gathering ended in a broken truce, per level. */
    private static final Map<ResourceKey<Level>, Long> BROKEN = new HashMap<>();

    public static void init(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, MoonConfig.SPEC, "clanlore-moon-common.toml");
        Truce.init();
        // the server stopped: its levels and their nights are gone (a pack registers its places again on start)
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            AREAS.clear();
            HELD.clear();
            PASSAGE.clear();
            BROKEN.clear();
            Truce.reset();
        });
    }

    // --- the phase and the night

    /** The moon's shape tonight (the night that ends this day's count; see the class comment). */
    public static Phase phase(Level level) {
        return Phase.of(level.getMoonPhase());
    }

    /** How bright the moon is, from 1 (full) to 0 (the dark of the moon). */
    public static float brightness(Level level) {
        return level.getMoonBrightness();
    }

    /** Which night this is: the day's count, the same from the afternoon before it until dawn. */
    public static long night(Level level) {
        return Math.floorDiv(level.getDayTime(), DAY);
    }

    /** The time of day, 0 to 23999 (0 is sunrise, 6000 noon, 12000 sunset, 18000 midnight). */
    public static long timeOfDay(Level level) {
        return Math.floorMod(level.getDayTime(), DAY);
    }

    public static int nightStart() {
        return MoonConfig.get(MoonConfig.NIGHT_START::get, 12500);
    }

    public static int dayStart() {
        return MoonConfig.get(MoonConfig.DAY_START::get, 23500);
    }

    /** Whether it is night as the Clans count it (Better Days' night by default). Never in a dimension without a sky clock. */
    public static boolean isNight(Level level) {
        if (level.dimensionType().hasFixedTime()) return false;
        long t = timeOfDay(level);
        return t >= nightStart() && t < dayStart();
    }

    /** Sunrises until the next full moon (0 when tonight is the full moon). */
    public static int daysUntilFull(Level level) {
        return Math.floorMod(-level.getMoonPhase(), 8);
    }

    /** The full moon, at night: when the Clans meet in peace. False once the truce has been broken tonight. */
    public static boolean isGatheringNight(Level level) {
        if (cloudedOver(level)) return false;
        return held(level) || (phase(level).full() && isNight(level));
    }

    /** The half moon, when medicine cats meet at their sacred place (any time of that day). */
    public static boolean isHalfMoon(Level level) {
        return phase(level).half();
    }

    /** The half moon, at night. */
    public static boolean isHalfMoonNight(Level level) {
        return isHalfMoon(level) && isNight(level);
    }

    /**
     * Calls a Gathering in this level whatever the moon (true), or lets the moon decide again (false). For operators'
     * test commands and a pack's own Gathering scenes; calling one also clears tonight's broken truce.
     */
    public static void holdGathering(ServerLevel level, boolean on) {
        if (on) {
            HELD.add(level.dimension());
            BROKEN.remove(level.dimension());
        } else {
            HELD.remove(level.dimension());
        }
    }

    /**
     * Safe passage (true) while a pack's Gathering is on: the Clans cross each other's territories to reach it, and
     * the truce holds wherever they walk, not only at the Gathering place. A blow struck away from the place is
     * still stopped, but it does not count against the truce or end the Gathering.
     */
    public static void safePassage(ServerLevel level, boolean on) {
        if (on) PASSAGE.add(level.dimension()); else PASSAGE.remove(level.dimension());
    }

    /** Whether the Clans have safe passage to a Gathering in this level now. */
    public static boolean safePassage(Level level) {
        return PASSAGE.contains(level.dimension());
    }

    /** Whether a Gathering has been called in this level whatever the moon. */
    public static boolean held(Level level) {
        return HELD.contains(level.dimension());
    }

    /** The truce was broken tonight: clouds have covered the moon and the Gathering is over. */
    public static boolean cloudedOver(Level level) {
        Long n = BROKEN.get(level.dimension());
        return n != null && n == night(level);
    }

    static void markBroken(Level level) {
        BROKEN.put(level.dimension(), night(level));
    }

    // --- the truce

    /** Registers (or moves) a Gathering place where the truce holds; the id is the pack's own name for it. */
    public static void truceArea(String id, ResourceKey<Level> dimension, double x, double z, double radius) {
        AREAS.put(id, new TruceArea(id, dimension, x, z, radius));
    }

    public static void removeTruceArea(String id) {
        AREAS.remove(id);
    }

    public static List<TruceArea> truceAreas() {
        return new ArrayList<>(AREAS.values());
    }

    /** The Gathering place holding this spot, or null. */
    public static TruceArea truceAreaAt(Level level, BlockPos pos) {
        for (TruceArea a : AREAS.values()) if (a.contains(level, pos)) return a;
        return null;
    }

    /**
     * Whether the truce holds here now: a Gathering night (or a called Gathering) and a Gathering place. It goes on
     * holding for the rest of the night after it has been broken: the clouds end the Gathering, not the peace, so
     * nobody is set on because one cat lost its temper.
     */
    public static boolean truce(Level level, BlockPos pos) {
        if (!MoonConfig.get(MoonConfig.TRUCE::get, true)) return false;
        boolean night = held(level) || safePassage(level) || (phase(level).full() && isNight(level));
        if (!night) return false;
        return MoonConfig.get(MoonConfig.TRUCE_EVERYWHERE::get, false) || safePassage(level) || truceAreaAt(level, pos) != null;
    }

    /** Listens for a broken truce (the pack ends its Gathering scene). */
    public static void onTruceBroken(TruceBroken listener) {
        LISTENERS.add(listener);
    }

    static void tellBroken(ServerLevel level, ServerPlayer by, BlockPos where, TruceArea area) {
        for (TruceBroken l : LISTENERS) {
            try {
                l.broken(level, by, where, area);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(Moon.class).warn("Clan Lore: a truce listener failed: {}", ex.toString());
            }
        }
    }
}
