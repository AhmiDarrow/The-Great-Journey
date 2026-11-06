package com.ninjacat.greatjourney.clanlore;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Clan Lore: what Warrior Cats Events is missing for a Clan to live as it does in the books. WCE has no leader, elder
 * or queen, no mentors, no moon and no Gatherings, and no scars; Clan Lore adds them on top of WCE's own cats without
 * changing how WCE plays. It works in any WCE world. The Great Journey's Clanlands hooks into it ({@link LoreHooks})
 * for what needs its map (the Gathering island, the Moonpool) and its dialogue window.
 * <ul>
 *   <li>{@code role}: leaders and their lives, elders, queens, mentors and apprentices, and the ceremonies between.</li>
 *   <li>{@code traits}: scars, torn ears, grizzled muzzles and old wounds, kept on the cat and drawn on it.</li>
 *   <li>{@code moon}: the moon's phase in Clan words, and the truce of the full moon.</li>
 *   <li>{@code mixin}: fixes to WCE itself.</li>
 * </ul>
 */
@Mod(Clanlore.MOD_ID)
public final class Clanlore {
    public static final String MOD_ID = "clanlore";

    public Clanlore(IEventBus modBus, ModContainer container) {
        com.ninjacat.greatjourney.clanlore.role.Roles.init(modBus, container);
        com.ninjacat.greatjourney.clanlore.traits.Traits.init(modBus, container);
        com.ninjacat.greatjourney.clanlore.moon.Moon.init(modBus, container);
    }
}
