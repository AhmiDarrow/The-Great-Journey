package com.ninjacat.greatjourney.clanlore.role;

/**
 * A Clan cat's place beyond its Warrior Cats Events rank. WCE ranks a cat only as kit, apprentice, warrior, medicine
 * cat or deputy; the books also have the leader (with nine lives), the elders who have earned their rest, and the
 * queens who nurse the Clan's kits. A cat keeps its WCE rank alongside (a leader or an elder is a WCE warrior).
 */
public enum Role {
    NONE, LEADER, ELDER, QUEEN;

    static Role byId(int id) {
        return id >= 0 && id < values().length ? values()[id] : NONE;
    }
}
