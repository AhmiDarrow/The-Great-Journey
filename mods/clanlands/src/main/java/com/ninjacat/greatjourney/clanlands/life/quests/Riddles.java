package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.life.story.Prophecy;
import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * A player's side of a {@link Prophecy}: StarClan gives it in their dream at the Moonpool, the journal keeps the
 * words, and they puzzle it out with their Clan's medicine cat. One guess a day; a right one before its time has the
 * Clan ready for what comes ({@code Director}).
 */
final class Riddles {
    private Riddles() {}

    /** A prophecy comes true between two and three days after it is given. */
    static final long SOONEST = 48000L, SPREAD = 24000L;
    private static final String GUESSED = "riddleDay", ASKING = "riddleAsking";
    private static final int STAR = 0xCFE3FF;

    /** What StarClan adds to a dream: a new prophecy for the dreamer's Clan, if none is waiting; else nothing. */
    static List<Line> dream(ServerPlayer p, StoryData data, RandomSource r) {
        Clan clan = Kin.bookClan(p);
        if (clan == null || Prophecy.of(data) != null) return List.of();
        Prophecy.Meaning m = Prophecy.Meaning.values()[r.nextInt(Prophecy.Meaning.values().length)];
        Prophecy.keep(data, new Prophecy(clan, m, p.getServer().overworld().getGameTime() + SOONEST + r.nextInt((int) SPREAD), false));
        QuestState.root(p).remove(GUESSED);
        return List.of(Line.narrate("As the stars fade, many voices speak together:"), Line.say("StarClan", STAR, m.riddle),
                Line.narrate("A prophecy. Puzzle out what it means with your medicine cat before it comes true. It is in your journal."));
    }

    /** The prophecy waiting on this player's Clan and not yet worked out, or null. */
    static Prophecy puzzling(ServerPlayer p) {
        Prophecy pr = Prophecy.of(StoryData.get(p.getServer().overworld()));
        return pr != null && !pr.solved() && pr.clan() == Kin.bookClan(p) ? pr : null;
    }

    /** "About the prophecy...": the medicine cat (or, to a medicine cat, any Clanmate) hears the cat's guess. */
    static void ask(ServerPlayer p, String listener) {
        Prophecy pr = puzzling(p);
        if (pr == null) return;
        long day = p.level().getDayTime() / 24000L + 1;
        if (QuestState.root(p).getLong(GUESSED) == day) {
            Dialog.play(p, Line.say(listener, Kin.colour(p), "We've talked it over enough for one day. Sleep on it, and tell me again tomorrow."));
            return;
        }
        QuestState.root(p).putBoolean(ASKING, true);
        List<Choice> choices = new ArrayList<>();
        for (Prophecy.Meaning m : Prophecy.Meaning.values()) choices.add(Choice.of(m.guess, "greatjourney quest riddle " + m.name().toLowerCase(java.util.Locale.ROOT)));
        choices.add(new Choice("I'm not sure yet", "greatjourney quest bye", 0xAAAAAA));
        Dialog.play(p, Line.say(listener, Kin.colour(p), pr.meaning().riddle + " ...What do you think StarClan means by it?")
                .withChoices(choices.toArray(Choice[]::new)));
    }

    /** A guess, only just after it was asked for. */
    static boolean guess(ServerPlayer p, String key) {
        Prophecy pr = puzzling(p);
        Dialog.clear(p);
        if (pr == null || !QuestState.root(p).getBoolean(ASKING)) return false;
        Prophecy.Meaning m;
        try {
            m = Prophecy.Meaning.valueOf(key.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        QuestState.root(p).remove(ASKING);
        QuestState.root(p).putLong(GUESSED, p.level().getDayTime() / 24000L + 1);
        if (m != pr.meaning()) {
            Ui.play(p, List.of(Line.narrate("You turn it over together, but it does not fit. The words still do not make sense.")));
            return true;
        }
        ServerLevel ow = p.getServer().overworld();
        Prophecy.keep(StoryData.get(ow), new Prophecy(pr.clan(), pr.meaning(), pr.due(), true));
        p.giveExperiencePoints(60);
        Ui.play(p, List.of(Line.narrate("\"" + m.guess + ".\" As you say it, you both know it is right. " + BookClans.Info.of(pr.clan()).title + " will be ready.")));
        Deeds.weigh(p, 6, 0);
        return true;
    }

    /** The journal's entry for a prophecy over the player's Clan, or null. */
    static JournalView.Quest quest(ServerPlayer p) {
        Prophecy pr = Prophecy.of(StoryData.get(p.getServer().overworld()));
        if (pr == null || pr.clan() != Kin.bookClan(p)) return null;
        return new JournalView.Quest("prophecy", "A prophecy", "StarClan", pr.meaning().riddle,
                List.of(new JournalView.Objective(pr.solved() ? "Worked out: " + pr.meaning().guess + ". Your Clan is making ready."
                        : "Talk to your medicine cat about what it means", pr.solved() ? 1 : 0, 1)), true, false);
    }
}
