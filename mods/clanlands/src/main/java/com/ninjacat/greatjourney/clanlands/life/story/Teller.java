package com.ninjacat.greatjourney.clanlands.life.story;

import java.util.Locale;

/**
 * The cat who watches over a Free Mode world: one per world, picked when it is made and changeable at the Moonpool.
 * A storyteller is how often things happen, which things, how long the world is left in peace after a loss, how
 * plainly they warn, and how they speak. Bluestar is the balance the pack had before there were storytellers.
 */
public enum Teller {
    SPOTTEDLEAF("Spottedleaf", 0xE8B070, "Gentle. Few attacks, more kits and ceremonies, plain warnings. Cats die only of old age.",
            1.25, 48000, 3600, 0.05, 0.6),
    BLUESTAR("Bluestar", 0x9FC0E8, "The books as written: trouble, then a quiet stretch.",
            1.0, 24000, 2400, 0.15, 1.0),
    YELLOWFANG("Yellowfang", 0xB8B090, "Hard seasons. Sickness and injuries come often and leaf-bare bites deep. A medicine cat's life.",
            1.0, 12000, 2400, 0.10, 1.5),
    TIGERSTAR("Tigerstar", 0xD06040, "War. Raids, rogues and border fights, worse the stronger you grow. He will offer you things.",
            0.8, 0, 1200, 0.45, 1.0),
    MIDNIGHT("Midnight", 0xC8C8D8, "Long calms, then everything at once. She warns early, and never plainly.",
            1.0, 24000, 6000, 0.20, 1.0);

    /** The standing at which StarClan speaks of a cat as one their Clan trusts ({@link #judge}). */
    public static final int TRUSTED = 60;

    /** What a warning is about. */
    public enum Danger { BEAST, ENEMY, PEST, SICKNESS, FIRE, WATER }

    public final String title;
    public final int colour;
    public final String blurb;
    /** Times the usual wait between events. */
    public final double interval;
    /** How long the world is left in peace after a loss, in game ticks. */
    public final long mercy;
    /** How long between a warning and what it warns of, in game ticks. */
    public final long omenLead;
    /** Chance, each event roll, of a border skirmish near a player out in their territory. */
    public final double skirmish;
    /** Times the usual hunger of leaf-bare. */
    public final double hunger;

    Teller(String title, int colour, String blurb, double interval, long mercy, long omenLead, double skirmish, double hunger) {
        this.title = title;
        this.colour = colour;
        this.blurb = blurb;
        this.interval = interval;
        this.mercy = mercy;
        this.omenLead = omenLead;
        this.skirmish = skirmish;
        this.hunger = hunger;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Teller byKey(String s) {
        for (Teller t : values()) if (t.key().equalsIgnoreCase(s == null ? "" : s.trim())) return t;
        return null;
    }

    /** Times the usual weight of an event kind ({@code ClanEvents.Kind}'s name). */
    public double weight(String kind) {
        return switch (this) {
            case SPOTTEDLEAF -> switch (kind) {
                case "BADGER", "RAID", "ROGUES", "DOGS", "RATS", "ADDER", "PREDATOR", "FIRE" -> 0.3;
                case "KITS", "CEREMONY", "STORY" -> 2.5;
                case "SICKNESS", "INJURY", "SHORTAGE" -> 0.5;
                default -> 1.0;
            };
            case YELLOWFANG -> switch (kind) {
                case "SICKNESS", "INJURY", "SHORTAGE" -> 2.5;
                case "FREEZE", "DROUGHT", "ADDER" -> 2.0;
                case "BADGER", "RAID", "ROGUES", "DOGS", "RATS", "PREDATOR" -> 0.8;
                default -> 1.0;
            };
            case TIGERSTAR -> switch (kind) {
                case "RAID", "ROGUES" -> 3.0;
                case "BADGER", "PREDATOR", "DOGS" -> 1.5;
                case "KITS", "CEREMONY", "STORY" -> 0.5;
                default -> 1.0;
            };
            case BLUESTAR, MIDNIGHT -> 1.0;
        };
    }

    /** The warning this storyteller gives of a danger coming. */
    public String omen(Danger d) {
        return switch (this) {
            case SPOTTEDLEAF -> switch (d) {
                case BEAST -> "A wild beast is coming to the camp, very soon. Keep the kits close and your warriors by the entrance.";
                case ENEMY -> "Enemies mean to come at the camp, very soon. Set your guards at the entrance now.";
                case PEST -> "Vermin are on their way to the fresh-kill pile. Guard it, and keep the kits back.";
                case SICKNESS -> "A sickness is coming to the camp. Look to your herbs, and watch the elders and the kits.";
                case FIRE -> "I see fire in your territory, soon. Keep the Clan out of the trees when the sky rumbles.";
                case WATER -> "The water will rise. Move what you can to higher ground.";
            };
            case BLUESTAR -> switch (d) {
                case BEAST -> "I dreamed of great claws tearing at the camp wall.";
                case ENEMY -> "I dreamed of strange scents at the entrance, and warriors with their fur on end.";
                case PEST -> "I dreamed of small teeth rustling through the fresh-kill pile.";
                case SICKNESS -> "I dreamed of coughing in the dark of the dens.";
                case FIRE -> "I dreamed of smoke between the trees.";
                case WATER -> "I dreamed of wet paws where the ground should be dry.";
            };
            case YELLOWFANG -> switch (d) {
                case BEAST -> "Something big is on its way, mouse-brain. Claws out, or lose cats.";
                case ENEMY -> "Trouble with fur on it is coming. Don't stand there waiting for it.";
                case PEST -> "Vermin. Guard your food, unless you fancy going hungry.";
                case SICKNESS -> "I can smell a sickness coming. If your herb store is empty, that's nobody's fault but yours.";
                case FIRE -> "Smoke. I know that smell better than any cat. Stay clear of the trees.";
                case WATER -> "Flood's coming. Wet paws are the least of it.";
            };
            case TIGERSTAR -> switch (d) {
                case BEAST -> "Something hungry is coming. Let us see whether your Clan deserves to live.";
                case ENEMY -> "Blood will be spilled at your entrance before long. Will it be theirs, or yours?";
                case PEST -> "Even vermin think your Clan is weak. Prove them wrong.";
                case SICKNESS -> "The weak will fall sick soon. A strong Clan has no use for them.";
                case FIRE -> "Your forest will burn a little. Fear it, if you are the fearing kind.";
                case WATER -> "The water comes for the soft and the slow.";
            };
            case MIDNIGHT -> switch (d) {
                case BEAST -> "Heavy paws the ground is feeling. Not cat, not small.";
                case ENEMY -> "Cats who are not your cats, thinking of your doorstep they are.";
                case PEST -> "Many small makes one big trouble, where the food sleeps.";
                case SICKNESS -> "Quiet thing travels breath to breath. Leaves you will be wanting.";
                case FIRE -> "Red flower the sky is planting. Where it grows, do not stand.";
                case WATER -> "What drinks from the lake, the lake is giving back.";
            };
        };
    }

    /** What they say of a cat who has come to the Moonpool, by the way that cat has lived: honour kept, standing earned. */
    public String judge(String you, int honour, int standing) {
        boolean kept = honour >= 2, broke = honour <= -2, trusted = standing >= TRUSTED;
        return switch (this) {
            case SPOTTEDLEAF -> broke ? "You have made hard choices, " + you + ". It is never too late to choose gently."
                    : kept ? "You have been kind when it would have been easier not to be. I saw it, " + you + "."
                    : "Walk softly, " + you + ", and look after one another.";
            case BLUESTAR -> broke ? "The warrior code is not a thing to keep only when it suits you, " + you + "."
                    : kept ? "You keep the warrior code, " + you + ". Your Clan is stronger for it."
                    : trusted ? "Your Clan trusts you, " + you + ". Be worthy of it when the choice is hard."
                    : "The warrior code will guide you, " + you + ", if you let it.";
            case YELLOWFANG -> broke ? "Don't look at me like that. You know what you did."
                    : kept ? "Hmph. You've done better than I expected. Don't let it go to your head."
                    : "Still alive, then. Keep the herb store full and you might stay that way.";
            case TIGERSTAR -> broke ? "You are learning, " + you + ". Rules are for cats who cannot win without them."
                    : kept ? "So loyal. So obedient. How long before they thank you for it? They will not."
                    : trusted ? "They look to you now. Why should another cat give you orders?"
                    : "I see something in you, " + you + ". Something they are too timid to use.";
            case MIDNIGHT -> broke ? "Paths you have walked, bent ones. Where they go, you are still not seeing."
                    : kept ? "Straight you walk, mostly. Good. Soon a crooked place comes."
                    : "Watching I am. Much there is still to happen to you.";
        };
    }
}
