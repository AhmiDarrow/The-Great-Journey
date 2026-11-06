package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import net.minecraft.util.RandomSource;
import net.snowteb.warriorcats_events.entity.custom.wcat.GeneticsModule;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCGenetics;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * How the cats look. Warrior Cats Events draws a cat from its genes: two alleles per gene ("B-b"), held in synced
 * entity data and saved with the cat, plus drawing choices (which stripe, white or eye picture) it rolls once. Its
 * spawn rolls all of them at random, so every look here is set after {@code finalizeSpawn} and before the cat is
 * added to the level: the spawn packet then carries the genes and the client builds the texture from them. (The
 * client never rebuilds a cat's texture once drawn, so changing a cat already in view only shows after it reloads.)
 * <p>
 * The four StarClan teachers and the Great Journey's route cats have their book coats. Lake Clan cats lean toward
 * their Clan's coats in the books by chance, not rule, and kits born in camp inherit from their mother the way
 * Warrior Cats Events breeds its own cats.
 */
public final class BookLooks {
    private BookLooks() {}

    /** Tag on a cat whose look was set here, so a fix-up pass changes an old cat once. */
    public static final String TAG = "greatjourney_book_look";

    /** The sizes Warrior Cats Events itself draws grown cats at (its own pelts come at 0.8, 0.9 and full size). */
    static final float SMALLEST = 0.8f, BIGGEST = 1.0f;

    /** One coat: the genes shown and the pictures drawn for them. Unset hidden genes keep what the spawn rolled. */
    static final class Coat {
        String base = "B-B", dilute = "d-d", agouti = "a-a", stripes = "Mc-Mc", orange = "o-o", white = "w-w", silver = "i-i";
        String chest = "s-s", belly = "s-s", legs = "s-s", head = "s-s", cheek = "s-s", back = "s-s", tail = "s-s";
        String eyes = "yellow", rightEyes;
        int eyeVar, tabbyVar, orangeVar, whiteVar, silverVar, rufous;
        float size = 0.9f;
        boolean book;            // a book cat: no hidden colourpoint, chimera, short tail or odd eyes left over

        Coat fur(String f) {
            chest = belly = legs = head = cheek = back = tail = f;
            return this;
        }

        void into(WCGenetics g) {
            g.base = base;
            g.dilute = dilute;
            g.agouti = agouti;
            g.tabbyStripes = stripes;
            g.orangeBase = orange;
            g.whiteRatio = white;
            g.silver = silver;
            g.chestFur = chest;
            g.bellyFur = belly;
            g.legsFur = legs;
            g.headFur = head;
            g.cheekFur = cheek;
            g.backFur = back;
            g.tailFur = tail;
            g.albino = book ? "C-C" : shown(g.albino, "C");             // no Siamese or albino Clan cats
            g.chimeraGene = book ? "C-C" : shown(g.chimeraGene, "C");   // no half-and-half chimeras
            g.bobtail = book ? "B-B" : shown(g.bobtail, "B");           // full tails
            if (book) g.eyesAnomaly = "H-H";
        }
    }

    /** A gene with the given allele shown, keeping one of the cat's own alleles hidden for its kits. */
    private static String shown(String gene, String allele) {
        if (gene == null || !gene.contains("-")) return allele + "-" + allele;
        String[] a = gene.split("-");
        return allele + "-" + (a[0].equals(allele) ? a[1] : a[0]);
    }

    /** Gives a cat a coat; call between {@code finalizeSpawn} (and {@code setGender}) and {@code addFreshEntity}. */
    static void apply(WCatEntity cat, Coat c) {
        GeneticsModule gm = cat.getGeneticsModule();
        WCGenetics g = gm.getGenetics();
        c.into(g);
        gm.setGenetics(g);
        cat.getEntityData().set(WCatEntity.CHIMERA_GENE, g.chimeraGene);   // setGenetics leaves this one out
        WCGenetics.GeneticalVariants v = gm.getGenVariants();
        v.eyeColorLeft = c.eyes;
        v.eyeColorRight = c.rightEyes == null ? c.eyes : c.rightEyes;
        v.leftEyeVar = c.eyeVar;
        v.rightEyeVar = c.eyeVar;
        v.tabbyVar = c.tabbyVar;
        v.orangeVar = c.orangeVar;
        v.whiteVar = c.whiteVar;
        v.silverVar = c.silverVar;
        v.rufousingVariant = c.rufous;
        if (c.book) v.blueRufousingVariant = 0;
        v.size = Math.max(SMALLEST, Math.min(BIGGEST, c.size));
        v.skin_color = WCGenetics.Constants.getSkinShade(g.whiteRatio, g.base, g.agouti, g.orangeBase, cat.getGender(), cat.getRandom());
        gm.setGeneticalVariants(v);
        cat.setOnGeneticalSkin(true);
        cat.addTag(TAG);
    }

    // ---- the book cats -------------------------------------------------------------------------------------------

    /** The StarClan teachers as the books describe them. */
    public static void teacher(WCatEntity cat, String name) {
        Coat c = new Coat();
        c.book = true;
        switch (name) {
            case "Bluestar" -> {                  // blue-grey she-cat, silver about the muzzle, blue eyes
                c.dilute = "D-D";
                c.silver = "I-i";                 // on a solid coat Warrior Cats Events draws silver as a pale smoke
                c.eyes = "blue";
                c.eyeVar = 8;
                c.size = 0.95f;
            }
            case "Lionheart" -> {                 // big golden tabby tom, thick mane, green eyes
                c.orange = "O-O";
                c.agouti = "A-A";
                c.stripes = "mc-mc";
                c.tabbyVar = 1;
                c.chest = c.head = c.cheek = "L-L";
                c.back = "L-s";
                c.eyes = "green";
                c.eyeVar = 1;
                c.size = 1.0f;
            }
            case "Spottedleaf" -> {               // small dark tortoiseshell, dappled, white chest and paws, amber eyes
                c.orange = "O-o";
                c.orangeVar = 0;                  // the darkest, most broken-up of the tortie patterns
                c.white = "S-w";
                c.whiteVar = 15;                  // the low white that covers chest and front paws
                c.eyes = "yellow";
                c.eyeVar = 5;                     // deep amber
                c.size = 0.82f;
            }
            case "Yellowfang" -> {                // old dark grey she-cat, long matted fur, orange eyes
                c.silver = "I-i";                 // smoke over black: dark grey with paler roots
                c.silverVar = 1;
                c.fur("L-L");
                c.eyes = "yellow";
                c.eyeVar = 2;                     // orange
                c.size = 0.92f;
            }
            default -> {
                return;
            }
        }
        apply(cat, c);
        if (name.equals("Yellowfang")) {          // an old medicine cat: grey about the muzzle (Clan Lore draws it)
            com.ninjacat.greatjourney.clanlore.traits.Traits.add(cat, com.ninjacat.greatjourney.clanlore.traits.Trait.GRIZZLED_MUZZLE);
        }
    }

    /** Cats along the Great Journey: Purdy the old kittypet, Stoneteller and the mud-brown Tribe of Rushing Water. */
    public static void routeCat(WCatEntity cat, String name) {
        RandomSource r = cat.getRandom();
        Coat c;
        if (name.equals("Purdy")) {               // old brown tabby tom, ragged coat
            c = new Coat();
            c.book = true;
            c.agouti = "A-a";
            c.stripes = "mc-mc";
            c.tabbyVar = 2;
            c.back = c.cheek = c.tail = "L-s";    // long only in patches: an unkempt, ragged look
            c.rufous = 2;
            c.eyes = "yellow";
            c.eyeVar = 0;
            c.size = 0.95f;
        } else if (name.equals("Stoneteller")) {  // lean old brown tabby
            c = new Coat();
            c.book = true;
            c.agouti = "A-A";
            c.tabbyVar = 3;
            c.eyes = "yellow";
            c.eyeVar = 5;
            c.size = 0.88f;
        } else {
            c = roll(TRIBE, cat.getGender(), r);
        }
        apply(cat, c);
    }

    // ---- the lake Clans ------------------------------------------------------------------------------------------

    /**
     * A people's coat leanings, each a chance out of 100: tabby (and of tabbies, classic blotches), ginger, tortoiseshell
     * (she-cats), dilute, silver, the brown bases (black-brown, chocolate, else cinnamon), white (a little, a lot, all),
     * long fur, eyes (yellow, green, else blue), the warmest rufous tint, and a size range.
     */
    record Leaning(int tabby, int classic, int ginger, int tortie, int dilute, int silver, int black, int chocolate,
                   int lowWhite, int highWhite, int fullWhite, int longFur, int yellowEyes, int greenEyes,
                   int maxRufous, float minSize, float maxSize) {}

    /** ThunderClan: every kind of coat, with many tabbies and gingers. */
    static final Leaning THUNDER = new Leaning(60, 40, 30, 20, 20, 6, 70, 20, 30, 10, 3, 25, 45, 40, 6, 0.86f, 1.0f);
    /** WindClan: small and lean, light browns, greys and tabbies, short fur. */
    static final Leaning WIND = new Leaning(65, 30, 8, 8, 35, 5, 45, 25, 25, 5, 1, 0, 55, 35, 4, 0.8f, 0.88f);
    /** RiverClan: big and sleek, often silver, grey or tabby, thick fur. */
    static final Leaning RIVER = new Leaning(65, 35, 8, 8, 35, 45, 80, 15, 20, 5, 2, 20, 40, 30, 3, 0.92f, 1.0f);
    /** ShadowClan: mostly dark pelts, black, dark grey and dark tabby. */
    static final Leaning SHADOW = new Leaning(40, 50, 4, 6, 15, 15, 85, 15, 15, 2, 0, 25, 60, 35, 1, 0.86f, 0.98f);
    /** The Tribe of Rushing Water: lean mud-brown cats, tabby and solid browns, a few greys. */
    static final Leaning TRIBE = new Leaning(80, 40, 0, 5, 20, 5, 40, 35, 10, 0, 0, 30, 60, 40, 3, 0.8f, 0.88f);

    static Leaning of(Clan c) {
        return switch (c) {
            case THUNDER -> THUNDER;
            case WIND -> WIND;
            case RIVER -> RIVER;
            case SHADOW -> SHADOW;
        };
    }

    private static boolean chance(RandomSource r, int percent) {
        return r.nextInt(100) < percent;
    }

    private static String eyes(Leaning l, RandomSource r) {
        int e = r.nextInt(100);
        return e < l.yellowEyes() ? "yellow" : e < l.yellowEyes() + l.greenEyes() ? "green" : "blue";
    }

    /** A coat drawn from a leaning. */
    static Coat roll(Leaning l, int gender, RandomSource r) {
        Coat c = new Coat();
        int b = r.nextInt(100);
        c.base = b < l.black() ? "B-" + (r.nextInt(4) == 0 ? "b" : "B") : b < l.black() + l.chocolate() ? "b-b" : "b1-b1";
        c.dilute = chance(r, l.dilute()) ? "D-d" : "d-d";
        c.agouti = chance(r, l.tabby()) ? "A-" + (r.nextBoolean() ? "A" : "a") : "a-a";
        c.stripes = chance(r, l.classic()) ? "mc-mc" : "Mc-" + (r.nextBoolean() ? "Mc" : "mc");
        if (chance(r, l.ginger())) c.orange = "O-O";
        else if (gender == 1 && chance(r, l.tortie())) c.orange = "O-o";
        int w = r.nextInt(100);
        c.white = w < l.fullWhite() ? "Wd-w" : w < l.fullWhite() + l.highWhite() ? "S-S"
                : w < l.fullWhite() + l.highWhite() + l.lowWhite() ? "S-w" : "w-w";
        c.silver = chance(r, l.silver()) ? "I-i" : "i-i";
        c.fur(chance(r, l.longFur()) ? "L-s" : "s-s");
        c.eyes = eyes(l, r);
        c.eyeVar = r.nextInt(12);
        c.tabbyVar = r.nextInt(5);
        c.orangeVar = r.nextInt(18);
        c.whiteVar = r.nextInt(21);
        c.silverVar = r.nextInt(3);
        c.rufous = r.nextInt(Math.min(c.base.startsWith("B") ? 3 : 7, l.maxRufous() + 1));
        c.size = l.minSize() + r.nextFloat() * (l.maxSize() - l.minSize());
        return c;
    }

    /** Gives a newly made lake Clan cat a coat leaning toward its Clan's. */
    public static void clanCat(WCatEntity cat, Clan c) {
        RandomSource r = cat.getRandom();
        Coat coat = roll(of(c), cat.getGender(), r);
        if (WCGenetics.EyesAnomaly.isHeteroChromic(cat.getGeneticsModule().getGenetics().eyesAnomaly)) {
            coat.rightEyes = eyes(of(c), r);                      // Warrior Cats Events' rare odd eyes stay
        }
        apply(cat, coat);
    }

    /**
     * A kit born in camp: Warrior Cats Events' own inheritance from its mother and a father (a tom of the Clan, or, with
     * none about, a Clan-leaning stranger), drawn at its Clan's size.
     */
    public static void kit(WCatEntity kit, Clan c, WCatEntity mother, WCatEntity father) {
        RandomSource r = kit.getRandom();
        GeneticsModule gm = kit.getGeneticsModule();
        WCGenetics dad;
        if (father != null) {
            dad = father.getGeneticsModule().getGenetics();
        } else {
            dad = gm.getGenetics();
            roll(of(c), 0, r).into(dad);
        }
        gm.inheritGeneticsFromParents(mother.getGeneticsModule().getGenetics(), dad);
        WCGenetics.GeneticalVariants v = gm.getGenVariants();
        v.size = of(c).minSize() + r.nextFloat() * (of(c).maxSize() - of(c).minSize());
        gm.setGeneticalVariants(v);
        kit.setOnGeneticalSkin(true);
        kit.addTag(TAG);
    }
}
