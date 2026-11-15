package com.ninjacat.greatjourney.clanlore.role;

import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A pack that runs some Clans itself (The Great Journey's lake Clans) tells Clan Lore which, so the two never both
 * keep a leader's lives or both hold a succession. For a cat of such a Clan the pack's word on who leads and how
 * many lives are left is the only one; Clan Lore mirrors it onto the cat and shows it. Every other Clan in the world
 * is Clan Lore's own.
 */
public interface ClanKeeper {
    /** Whether the pack keeps this cat's Clan: its leader, the leader's lives, and what happens when they run out. */
    boolean keeps(WCatEntity cat);

    /** Whether this cat leads its Clan (asked only for a cat whose Clan the pack keeps). */
    boolean leads(WCatEntity cat);

    /** How many lives this leader has left (asked only for a cat the pack says leads). */
    int lives(WCatEntity cat);

    /**
     * Moves the cat's nest to suit its new role: an elder to the elders' den, a queen to the nursery, a cat back to
     * the warriors' den from either. Returns false if the pack has no den for it (the cat keeps its nest).
     */
    default boolean rehome(WCatEntity cat, Role role) {
        return false;
    }

    /** Whether this cat teaches a player (the pack's own apprentices), so it is not given a Clan apprentice too. */
    default boolean mentorsPlayer(WCatEntity cat) {
        return false;
    }
}
