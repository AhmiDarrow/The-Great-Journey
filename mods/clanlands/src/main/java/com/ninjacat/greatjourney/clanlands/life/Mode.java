package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * How a world is played, chosen once when it is created and never changed afterwards.
 * <ul>
 *   <li>CAMPAIGN: the story of the Clans in the books' order, beginning at Fourtrees in the old forest.</li>
 *   <li>FREE: the Clans already live at the lake; a new cat chooses how to begin and lives as they like.</li>
 * </ul>
 * A new world takes its mode from the title screen button that created it (singleplayer), otherwise from
 * {@code clanlands-common.toml} {@code newWorldMode} (dedicated servers). Worlds from before modes existed are FREE.
 */
public enum Mode {
    CAMPAIGN, FREE;

    private static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<String> NEW_WORLD_MODE;
    private static final ModConfigSpec.ConfigValue<String> NEW_WORLD_STORYTELLER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("modes");
        NEW_WORLD_MODE = b.comment("How new worlds are played when no title-screen button chose: \"free\" or \"campaign\".",
                        "Dedicated servers use this. An existing world keeps the mode it was created with.")
                .define("newWorldMode", "free", v -> v instanceof String s && parse(s) != null);
        NEW_WORLD_STORYTELLER = b.comment("Who watches over a new Free Mode world when none was chosen on the title screen:",
                        "\"spottedleaf\", \"bluestar\", \"yellowfang\", \"tigerstar\" or \"midnight\". Dedicated servers use this.")
                .define("newWorldStoryteller", "bluestar", v -> v instanceof String s && Teller.byKey(s) != null);
        b.pop();
        SPEC = b.build();
    }

    /** Set by the title screen's Campaign / Free Mode buttons, for the world the player is about to create. */
    private static volatile Mode pending;
    /** The storyteller picked with Free Mode, for that same world. */
    private static volatile Teller pendingTeller;

    public static void init() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, SPEC, "clanlands-common.toml");
    }

    /** The client's menu choice for the next world created in this game session (null clears it). */
    public static void setPending(Mode mode) {
        pending = mode;
        if (mode != Mode.FREE) pendingTeller = null;
    }

    public static void setPendingTeller(Teller teller) {
        pendingTeller = teller;
    }

    public static Mode pending() {
        return pending;
    }

    /** The world's mode. A world that never had one (made before modes existed) is FREE from now on. */
    public static Mode of(MinecraftServer server) {
        return of(server.overworld());
    }

    public static Mode of(ServerLevel level) {
        LifeData life = LifeData.get(level.getServer().overworld());
        Mode m = life.mode();
        if (m == null) {
            m = FREE;
            life.setMode(m);
        }
        return m;
    }

    /**
     * Gives a world being created its mode: the menu's choice if there is one, else the config's. Called while the
     * overworld is set up, before its spawn point is chosen; a world that already has a mode keeps it.
     */
    public static Mode assignNew(ServerLevel overworld) {
        LifeData life = LifeData.get(overworld);
        if (life.mode() != null) return life.mode();
        Mode chosen = pending != null ? pending : configured();
        pending = null;
        life.setMode(chosen);
        if (chosen == FREE) StoryData.get(overworld).setTeller(pendingTeller != null ? pendingTeller : configuredTeller());
        pendingTeller = null;
        return chosen;
    }

    private static Teller configuredTeller() {
        try {
            Teller t = Teller.byKey(NEW_WORLD_STORYTELLER.get());
            return t == null ? Teller.BLUESTAR : t;
        } catch (IllegalStateException notLoaded) {
            return Teller.BLUESTAR;
        }
    }

    private static Mode configured() {
        try {
            Mode m = parse(NEW_WORLD_MODE.get());
            return m == null ? FREE : m;
        } catch (IllegalStateException notLoaded) {
            return FREE;
        }
    }

    static Mode parse(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "campaign" -> CAMPAIGN;
            case "free" -> FREE;
            default -> null;
        };
    }
}
