package com.ninjacat.greatjourney.clanlands.life;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.util.RandomSource;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Warrior names for the cats of a world, drawn from Warrior Cats Events' own name pools so they sound like the
 * rest of the mod. Seeded per world and never repeated within it: every world gets its own Clan cats.
 */
public final class ClanNames {
    /**
     * The books' famous cats (villains above all), which no generated cat is ever named: they appear only in the dream
     * and the guide, and in-game Clans use generated cats. A prefix that would make a famous leader's -star name
     * (Tiger, Broken, Night...) is not drawn at all, so a deputy's promotion cannot land on one either.
     */
    public static final Set<String> BOOK_NAMES = Set.of(
            "brokenstar", "tigerstar", "tigerclaw", "hawkfrost", "darkstripe", "mapleshade", "scourge", "sol", "breezepelt",
            "ashfur", "thistleclaw", "nightstar", "bluestar", "firestar", "fireheart", "graystripe", "greystripe", "sandstorm",
            "yellowfang", "ravenpaw", "spottedleaf", "whitestorm", "lionheart", "redtail", "oakheart", "crookedstar",
            "leopardstar", "mistystar", "onestar", "tallstar", "blackstar", "bramblestar", "brambleclaw", "rowanstar",
            "squirrelflight", "leafpool", "jayfeather", "lionblaze", "hollyleaf", "dovewing", "ivypool", "cinderpelt",
            "tawnypelt", "sorreltail", "cloudtail", "brightheart", "longtail", "mousefur", "dustpelt", "darkstar");

    private final RandomSource random;
    private final Set<String> used = new HashSet<>();

    public ClanNames(long seed) {
        this.random = RandomSource.create(seed ^ 0x5EEDCA7L);
    }

    private String prefix() {
        for (int tries = 0; tries < 200; tries++) {
            String p = WCatEntity.PREFIXES[random.nextInt(WCatEntity.PREFIXES.length)];
            if (!used.contains(p.toLowerCase(Locale.ROOT)) && !famous(p)) {
                used.add(p.toLowerCase(Locale.ROOT));
                return capital(p);
            }
        }
        String p;
        do p = WCatEntity.PREFIXES[random.nextInt(WCatEntity.PREFIXES.length)]; while (famous(p));      // the pool is spent: repeats, but never a book name
        return capital(p);
    }

    private String suffix() {
        return WCatEntity.SUFIXES[random.nextInt(WCatEntity.SUFIXES.length)].toLowerCase(Locale.ROOT);
    }

    /** A cat's name: the prefix (kept when a kit or apprentice grows up) and the full name. */
    public record Name(String prefix, String full) {}

    public Name leader() {
        String p = prefix();
        return new Name(p, p + "star");
    }

    public Name warrior() {
        String p = prefix(), s = suffix();
        for (int tries = 0; tries < 50 && !fine(p, s); tries++) s = suffix();
        return new Name(p, p + s);
    }

    public Name apprentice() {
        String p = prefix();
        return new Name(p, p + "paw");
    }

    public Name kit() {
        String p = prefix();
        return new Name(p, p + "kit");
    }

    /** Whether a prefix would make a book cat's -star or -paw name. */
    private static boolean famous(String p) {
        String lp = p.toLowerCase(Locale.ROOT).trim();
        return BOOK_NAMES.contains(lp + "star") || BOOK_NAMES.contains(lp + "paw");
    }

    /** Whether prefix and suffix join into a name worth giving: no "Mossspring"-style doubles, "Mosmoss", or book cats. */
    private static boolean fine(String p, String s) {
        String lp = p.toLowerCase(Locale.ROOT);
        return !lp.endsWith(s.substring(0, 1)) && !lp.endsWith(s) && !BOOK_NAMES.contains(lp + s);
    }

    private static String capital(String s) {
        s = s.trim();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
