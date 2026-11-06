package com.ninjacat.greatjourney.clanlore.role;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Finding a Clan's cats the way Warrior Cats Events records them: its Clan list holds every member cat's id, and the
 * live cat is looked up only if it is loaded. Nothing here loads a chunk.
 */
final class ClanCats {
    private ClanCats() {}

    static final String TOM = "♂", SHE = "♀";

    /** Who speaks a line: a name, and the cat saying it if there is one. */
    record Voice(String name, UUID entity) {}

    /** The cat's Clan in Warrior Cats Events' records, or null for a cat with none. */
    static ClanData.Clan clan(WCatEntity cat) {
        UUID id = cat.getClanUUID();
        if (id == null || id.equals(ClanData.EMPTY_UUID) || !(cat.level() instanceof ServerLevel level)) return null;
        return ClanData.get(level.getServer().overworld()).getClan(id);
    }

    /** The Clan's cats that are loaded and alive in this level. */
    static List<WCatEntity> loaded(ServerLevel level, ClanData.Clan clan) {
        List<WCatEntity> out = new ArrayList<>();
        for (UUID id : List.copyOf(clan.clanCats.keySet())) {
            if (level.getEntity(id) instanceof WCatEntity c && c.isAlive() && clan.clanUUID.equals(c.getClanUUID())) out.add(c);
        }
        return out;
    }

    /** The cat's name without Warrior Cats Events' gender sign. */
    static String name(WCatEntity cat) {
        if (cat.getCustomName() == null) return "a Clan cat";
        return cat.getCustomName().getString().replace(TOM, "").replace(SHE, "").trim();
    }

    /** The first part of the cat's name ("Moss" of Mosskit), as WCE keeps it, else read off the name. */
    static String prefix(WCatEntity cat) {
        Component p = cat.getPrefix();
        if (p != null && !p.getString().isBlank()) return p.getString().trim();
        String n = name(cat);
        for (String end : new String[] {"paw", "kit", "star"}) if (n.endsWith(end) && n.length() > end.length()) return n.substring(0, n.length() - end.length());
        return n;
    }

    /** Gives a cat a new name in Warrior Cats Events' style (the gender sign after it). */
    static void rename(WCatEntity cat, String prefix, String full) {
        cat.setCustomName(Component.literal(full + (cat.getGender() == 0 ? " " + TOM : " " + SHE)));
        cat.setPrefix(Component.literal(prefix));
    }

    /** A warrior in its prime: a grown warrior or deputy with no other role. */
    static boolean ableWarrior(WCatEntity c) {
        return !c.isBaby() && (c.getRank() == WCatEntity.Rank.WARRIOR || c.getRank() == WCatEntity.Rank.DEPUTY) && Roles.role(c) == Role.NONE;
    }

    /**
     * Who speaks for the Clan at a ceremony: its leader if loaded, else its deputy, else any warrior, else the name
     * Warrior Cats Events has for the Clan's leader (often a player). Never {@code not}, the cat the ceremony is for.
     */
    static Voice leader(ServerLevel level, ClanData.Clan clan, WCatEntity not) {
        List<WCatEntity> cats = loaded(level, clan);
        cats.remove(not);
        WCatEntity pick = cats.stream().filter(c -> Roles.role(c) == Role.LEADER).findFirst()
                .or(() -> cats.stream().filter(c -> c.getRank() == WCatEntity.Rank.DEPUTY).findFirst())
                .or(() -> cats.stream().filter(c -> !c.isBaby() && c.getRank() == WCatEntity.Rank.WARRIOR && Roles.role(c) != Role.ELDER).findFirst())
                .orElse(null);
        if (pick != null) return new Voice(name(pick), pick.getUUID());
        String n = clan.leaderName == null || clan.leaderName.isBlank() ? "The leader of " + clan.name : clan.leaderName;
        return new Voice(n, null);
    }
}
