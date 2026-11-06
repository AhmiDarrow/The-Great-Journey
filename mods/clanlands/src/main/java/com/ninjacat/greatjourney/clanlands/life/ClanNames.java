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

    /**
     * Warrior Cats Events' prefixes that do not fit the books, which name cats after what a forest cat knows (Fire,
     * Blue, Bramble, Leaf, Mouse, Rain): no ideas (Destiny, Hope), animals a lake cat never meets (Donkey, Camel,
     * Shark), stones and gems from Minecraft, garden and kitchen words, colours a cat would not name, and the
     * "-y", "-ed" and "-ing" forms the books never use.
     */
    static final Set<String> UNFIT = Set.of(
            "destiny", "hope", "joy", "wish", "happy", "noble", "proud", "fierce", "bold", "dull", "odd", "loud", "quiet", "shy",
            "steady", "mellow", "gentle", "warm", "cold", "damp", "deep", "radiant", "ravenous", "hallow", "hallowed",
            "void", "big", "large", "heavy", "plump", "tiny", "bug", "sunny",
            "armadillo", "axolotl", "bengal", "boa", "camel", "cod", "dolphin", "donkey", "dog", "elk", "gecko", "goat",
            "guppy", "horse", "hound", "bear", "wolf", "llama", "mule", "ocelot", "panda", "parrot", "python", "raccoon",
            "rattlesnake", "rooster", "scorpion", "shark", "sheep", "skunk", "squid", "turtle", "crane", "duck", "kiwi",
            "amethyst", "andesite", "diorite", "granite", "diamond", "emerald", "crystal", "pearl", "ivory", "iron",
            "cobble", "marble", "marbled", "silk", "milk", "egg", "bubble", "lantern", "torch", "chisel", "mold",
            "rainbow", "sparkle", "ocean", "jungle", "tsunami", "typhoon", "hurricane", "blizzard", "current",
            "cactus", "pistachio", "pecan", "peanut", "almond", "melon", "pumpkin", "cocoa", "cinnamon", "nutmeg",
            "olive", "fig", "pear", "strawberry", "raspberry", "blueberry", "cranberry", "mushroom", "allium", "bluet",
            "peony", "orchid", "tulip", "sunflower", "lavender", "lilac", "rosemary", "parsley",
            "azure", "indigo", "scarlet", "beige", "pink", "umber", "orange",
            "ashy", "blotchy", "dusty", "frosty", "fuzzy", "hazy", "icy", "minty", "mossy", "stormy", "thorny",
            "ashen", "bent", "blotched", "burnt", "clouded", "dappled", "faded", "flecked", "frosted", "misted",
            "patched", "shaded", "speckled", "splotched", "wilted",
            "blazing", "blooming", "burning", "chirping", "flowering", "flying", "glowing", "howling", "jumping",
            "running", "shimmering", "shivering", "smoldering", "sparkling", "storming", "waving", "whispering", "winding",
            "hunch", "kink", "trudge", "stomp", "sniff", "twitch", "hop", "jump", "zap", "zip", "pop", "rattle",
            "whirl", "whorl", "float", "drip", "flow", "chirp", "howl", "bone", "woods");

    /** Suffixes that do not fit the books either: ideas, spirits, Twoleg words and odd plurals. */
    static final Set<String> UNFIT_SUFFIXES = Set.of(
            "fate", "soul", "spirit", "ghost", "charm", "dream", "orb", "orbs", "pride", "eater", "keep", "keeper",
            "glaze", "race", "sneeze", "fig", "mountain", "forest", "plant", "mound", "bee", "dig", "slice", "crash",
            "eyes", "feet", "footed", "teeth", "dapples", "speckles", "splotches", "patches", "winds");

    private final RandomSource random;
    private final Set<String> used = new HashSet<>();

    public ClanNames(long seed) {
        this.random = RandomSource.create(seed ^ 0x5EEDCA7L);
    }

    private String prefix() {
        for (int tries = 0; tries < 200; tries++) {
            String p = WCatEntity.PREFIXES[random.nextInt(WCatEntity.PREFIXES.length)];
            if (!used.contains(p.toLowerCase(Locale.ROOT)) && !famous(p) && fits(p)) {
                used.add(p.toLowerCase(Locale.ROOT));
                return capital(p);
            }
        }
        String p;
        do p = WCatEntity.PREFIXES[random.nextInt(WCatEntity.PREFIXES.length)]; while (famous(p) || !fits(p));      // the pool is spent: repeats, but never a book name
        return capital(p);
    }

    private String suffix() {
        String s;
        do s = WCatEntity.SUFIXES[random.nextInt(WCatEntity.SUFIXES.length)].toLowerCase(Locale.ROOT); while (!fitsSuffix(s));
        return s;
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

    /** Whether a prefix sounds like the books (see {@link #UNFIT}). */
    private static boolean fits(String p) {
        return !UNFIT.contains(p.toLowerCase(Locale.ROOT).trim());
    }

    /** Whether a suffix sounds like the books (see {@link #UNFIT_SUFFIXES}). */
    public static boolean fitsSuffix(String s) {
        return !UNFIT_SUFFIXES.contains(s.toLowerCase(Locale.ROOT).trim());
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
