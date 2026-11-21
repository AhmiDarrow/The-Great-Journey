package com.ninjacat.greatjourney.clanlands.life;

import java.util.HashSet;
import java.util.List;
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

    /**
     * Warrior-name endings in the books' style: body parts (Longtail, Brindleface, Crookedjaw), pelt marks (Graystripe,
     * Spottedleaf), weather and sky (Sandstorm, Hawkfrost, Dawnmist), plants (Rosepetal, Ferncloud)
     * and the way a cat moves (Squirrelflight, Lionblaze). Never -paw, -kit or -star, which are ranks, not names.
     * Our own list, not Warrior Cats Events' pool, which mixes in ideas and Twoleg words (fate, soul, keeper, race).
     */
    public static final List<String> SUFFIXES = List.of(
            // body
            "belly", "claw", "ear", "eye", "face", "fang", "foot", "fur", "heart", "jaw", "leg", "muzzle", "nose", "pad",
            "pelt", "scar", "snout", "tail", "throat", "tooth", "tuft", "whisker", "whiskers", "wing", "feather", "talon",
            "mask", "stripe", "spot", "spots", "speck", "fleck", "patch", "dapple", "splash", "streak", "smudge", "splotch",
            "speckle", "blotch", "freckle", "cheek", "chin", "spine", "flank", "back", "shoulder", "fluff",
            // sky and weather
            "storm", "frost", "cloud", "mist", "rain", "snow", "ice", "wind", "breeze", "thunder", "sky", "dawn", "dusk",
            "moon", "sun", "light", "shine", "glow", "gleam", "glimmer", "shimmer", "shadow", "shade", "haze", "fog", "dew",
            "flake", "hail", "sleet", "drizzle", "gale", "gust", "squall", "morning", "night", "twilight", "fire", "flame",
            "blaze", "spark", "ember", "cinder", "ash", "smoke", "flash", "flicker", "echo",
            // plants
            "leaf", "fern", "petal", "blossom", "bloom", "flower", "thorn", "briar", "bramble", "reed", "rush", "sedge",
            "seed", "berry", "stem", "root", "bark", "branch", "twig", "bough", "frond", "willow", "poppy", "rose", "vine",
            "ivy", "thistle", "gorse", "heather", "bracken", "moss", "nettle", "clover", "daisy", "fennel", "tansy",
            "sorrel", "holly", "pine", "birch", "alder", "rowan", "hazel", "acorn", "burr", "grass", "bush", "needle",
            "mint", "lichen", "pod",
            // water and land
            "pool", "stream", "river", "brook", "creek", "pond", "puddle", "ripple", "wave", "tide", "water", "shore",
            "stone", "pebble", "rock", "cliff", "cleft", "path", "trail", "field", "hollow", "fall", "spring", "sand",
            "dust", "mud", "shell", "marsh", "fen", "dune", "ridge",
            // animals and prey
            "hawk", "fox", "mouse", "bird", "fish", "minnow", "wren", "lark", "owl", "crow", "sparrow", "finch", "robin",
            "shrew", "vole", "hare", "mole", "moth", "beetle",
            // movement and voice
            "flight", "leap", "step", "dash", "pounce", "strike", "swoop", "slash", "scratch", "jump", "skip", "runner",
            "song", "whisper", "call", "cry", "chirp", "purr", "hiss", "sight", "gaze", "wish", "bound", "stalk",
            "hunt", "climb", "drift", "twist", "curl", "tumble", "bounce", "flutter", "dart", "swift", "slip");

    /** A random warrior suffix from the books' style. */
    public static String randomSuffix(RandomSource r) {
        return SUFFIXES.get(r.nextInt(SUFFIXES.size()));
    }


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
        return randomSuffix(random);
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

    /** Whether a prefix would make a book cat's -star or -paw name. */
    private static boolean famous(String p) {
        String lp = p.toLowerCase(Locale.ROOT).trim();
        return BOOK_NAMES.contains(lp + "star") || BOOK_NAMES.contains(lp + "paw");
    }

    /**
     * Whether prefix and suffix join into a name worth giving: no word twice ("Firefire", "Mosspelt" from a "Mosspelt"
     * prefix, "Leafleaf"), no "Mossspring" letter doubles, and no book cat.
     */
    public static boolean fine(String p, String s) {
        String lp = p.toLowerCase(Locale.ROOT).trim(), ls = s.toLowerCase(Locale.ROOT).trim();
        return !ls.isEmpty() && !lp.isEmpty() && !lp.endsWith(ls.substring(0, 1)) && !lp.contains(ls) && !ls.contains(lp)
                && !BOOK_NAMES.contains(lp + ls);
    }

    private static String capital(String s) {
        s = s.trim();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
