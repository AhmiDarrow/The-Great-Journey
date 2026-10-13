package com.ninjacat.greatjourney.clanlands.life.dream;

import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What the StarClan cats say at each step of the dream. Original lines in the spirit of the books. */
final class DreamScript {
    private DreamScript() {}

    static final int BLUE = 0x7FA7D9, LION = 0xE8B04A, SPOT = 0xD98C5F, YELLOW = 0x9C9C9C;

    static final Choice CONTINUE = new Choice("Continue", "greatjourney tutorial next", 0x55FF55);
    static final Choice SKIP = new Choice("Skip the dream", "greatjourney tutorial skip", 0xAAAAAA);
    static final Choice NEW_MOUSE = new Choice("It got away!", "greatjourney tutorial mouse", 0xFFAA00);

    static Line bluestar(String t) { return Line.say("Bluestar", BLUE, t); }
    static Line lionheart(String t) { return Line.say("Lionheart", LION, t); }
    static Line spottedleaf(String t) { return Line.say("Spottedleaf", SPOT, t); }
    static Line yellowfang(String t) { return Line.say("Yellowfang", YELLOW, t); }

    static List<Line> step(int step) {
        return switch (step) {
            case 1 -> List.of(
                    Line.narrate("You curl up and close your eyes... and open them in a clearing silvered with starlight. The air smells of warm grass and something older."),
                    bluestar("Welcome, young one. There is nothing to fear here. You are dreaming, and these are StarClan's hunting grounds."),
                    bluestar("I am Bluestar. Once I led ThunderClan. Now I walk with StarClan, and we watch over every Clan from Silverpelt."),
                    bluestar("Before your own path begins, some old friends of mine would like to teach you what every Clan cat must know."),
                    bluestar("Will you learn from us?").withChoices(CONTINUE, SKIP));
            case 2 -> List.of(
                    lionheart("A new face! I'm Lionheart. I was ThunderClan's deputy, and I mentored more apprentices than I can count."),
                    lionheart("Every warrior feeds their Clan, and that begins with the hunting crouch. Weight in your haunches, tail low and still."),
                    lionheart("Pad lightly. Prey feels your pawsteps through the ground long before it hears you. Then pounce! A strike at a run or a leap lands hardest."),
                    lionheart("There's a mouse in the grass by me. Go on, catch it!").withChoices(NEW_MOUSE, SKIP));
            case 3 -> List.of(
                    spottedleaf("Well hunted! I'm Spottedleaf. I was ThunderClan's medicine cat, and herbs were my whole world."),
                    spottedleaf("Do you see the patch beside me? Every one of those herbs eases a sick or hurt cat in its own way."),
                    spottedleaf("Pick any herb you like, and I'll tell you what it's for.").withChoices(SKIP));
            case 4 -> List.of(
                    yellowfang("Hmph. So you're the new one. I'm Yellowfang. My bones are too old for long speeches, so listen well."),
                    yellowfang("The forest isn't all mice and sunshine. Badgers, foxes and dogs will kill a careless cat. Never face one alone if you can help it."),
                    yellowfang("Keep off the Thunderpath. The monsters come faster than you think, and you'll hear them only just in time. And never touch deathberries. Not even one."),
                    yellowfang("Respect the other Clans' borders; their scent marks tell you where they lie. Defend your Clan, and help any cat in need, whatever Clan they're from."),
                    yellowfang("That's the heart of the warrior code. Now off with you, before I change my mind about you.").withChoices(CONTINUE, SKIP));
            case 5 -> List.of(
                    bluestar("You have learned well. The stars are fading, and soon you will wake."),
                    // in a Campaign world the Clans still live in the old forest, and the dream ends at Fourtrees
                    com.ninjacat.greatjourney.clanlands.life.Homeland.forest()
                            ? bluestar("Four Clans live in this forest: ThunderClan in the oak woods, WindClan on the high moor, RiverClan by the river and ShadowClan among the pines.")
                            : bluestar("Four Clans live around the great lake now: ThunderClan in the woods, WindClan on the moor, RiverClan by the streams and ShadowClan among the pines."),
                    com.ninjacat.greatjourney.clanlands.life.Homeland.forest()
                            ? bluestar("At every full moon they meet in peace at Fourtrees, in the hollow of the four great oaks. That is where you will wake.")
                            : bluestar("Far to the south-east lies the old forest, where the Clans lived before the Great Journey. Perhaps one day your paws will take you there."),
                    bluestar("Whatever path you choose, StarClan will be watching over you. Go well, young one.").withChoices(new Choice("Wake up", "greatjourney tutorial next", 0x55FF55)));
            default -> List.of();
        };
    }

    /** Spottedleaf's word on each herb, as the medicine cats of the books use them. */
    private static final Map<String, String> HERBS = Map.ofEntries(
            Map.entry("catmint", "Catmint! The most precious herb of all. It's the best cure we know for greencough, the sickness that comes in leaf-bare."),
            Map.entry("yarrow", "Yarrow makes a cat sick, and that's how it gets poison out of them. Mixed into an ointment, it soothes cracked pads."),
            Map.entry("comfrey", "Comfrey roots, chewed into a poultice, help broken bones mend and ease stiff, aching legs."),
            Map.entry("feverfew", "Feverfew cools a cat burning with fever, and it eases a pounding head."),
            Map.entry("juniper", "Juniper berries soothe a bellyache and help a cat who's struggling to breathe. They give strength, too."),
            Map.entry("dock", "Dock leaves are wonderful for poultices. Chewed and spread on, they soothe scratches and sore pads."),
            Map.entry("sorrel", "Sorrel is one of the traveling herbs. It keeps hunger away on a long journey."),
            Map.entry("burnet", "Burnet is a traveling herb too. It keeps a cat strong on the long road ahead."),
            Map.entry("chamomile_stems", "Chamomile strengthens the heart and calms the mind. It's good for a cat who is frightened or grieving."),
            Map.entry("daisy", "Daisy leaves, chewed to a paste, ease aching joints. The elders are always glad of them."));

    static List<Line> herb(String id) {
        List<Line> out = new ArrayList<>();
        out.add(spottedleaf(HERBS.getOrDefault(id, "Good choice. Every herb has its use, and a medicine cat learns them all.")));
        out.add(herbDone());
        return out;
    }

    /** Spottedleaf's last word on the herbs, and the way on. */
    static Line herbDone() {
        return spottedleaf("You have a gift for this. Remember: a Clan is only as strong as the care it gives its sick and its elders.").withChoices(CONTINUE, SKIP);
    }

    static List<Line> caught() {
        return List.of(lionheart("Great pounce! Your Clan will eat well with a hunter like you."));
    }

    static List<Line> mouseAgain() {
        return List.of(lionheart("It happens to every apprentice. Here's another one. Slower this time, and keep low!"));
    }
}
