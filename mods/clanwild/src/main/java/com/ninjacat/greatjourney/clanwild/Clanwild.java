package com.ninjacat.greatjourney.clanwild;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Clanwild: the glue The Great Journey needs around Warrior Cats Events.
 * Code: the speed strike, no monsters spawning on their own, and fewer dangerous animals ({@link Wildlife}). Data (in the jar): spawn removals for animals Alex's Mobs and Naturalist both add,
 * c:foods tags for Alex's Mobs meats, and Warrior Cats Events prey/predator tag entries.
 */
@Mod(Clanwild.MOD_ID)
public final class Clanwild {
    public static final String MOD_ID = "clanwild";

    public Clanwild(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, ClanwildConfig.SPEC);
        NeoForge.EVENT_BUS.register(SpeedStrike.class);
        NeoForge.EVENT_BUS.register(NoMonsters.class);
        NeoForge.EVENT_BUS.register(Wildlife.class);
    }
}
