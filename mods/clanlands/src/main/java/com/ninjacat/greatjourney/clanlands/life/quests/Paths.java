package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Lead;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Nests;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.clan.ClanData.ClanPlayerRank;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A player's place in their Clan, and changing it. Every apprentice trains as a warrior unless they choose the medicine
 * cat's path, at the end of their apprentice ceremony or later by asking their leader. A warrior can retire to the
 * elders' den or move to the nursery to help raise the Clan's kits, and go back to their duties again; a medicine cat
 * can retire too. The place is Warrior Cats Events' own rank for the player, and their nest moves to the den that goes
 * with it. The playtest (2026-10-19) found no way to be anything but a warrior.
 */
public final class Paths {
    private Paths() {}

    /** Marks one of {@link #takeRole}'s ceremony lines as the player's own answer. */
    private static final String REPLY = "you: ";

    /** The last line of the apprentice ceremony: which path to train on (closing the window keeps the warrior's). */
    public static Line ceremonyChoice() {
        return Line.narrate("Most apprentices train as warriors, but a cat who feels StarClan calling them to heal may learn the ways of a "
                        + "medicine cat instead. You can ask your leader about this later, too.")
                .withChoices(Choice.of("Train as a warrior", "greatjourney quest path warrior"),
                        Choice.of("Train as a medicine cat", "greatjourney quest path medicine"));
    }

    /** The first part of the player's cat name, without "-paw" or "-kit" (for a new name ending). */
    public static String namePrefix(ServerPlayer p) {
        return Story.prefix(p);
    }

    /** A cat joins a Clan as an apprentice: on the warrior's path, until they choose otherwise. */
    public static void joined(ServerPlayer p) {
        if (QuestState.storyStep(p) == 0) QuestState.setPath(p, QuestState.Path.WARRIOR);
    }

    /** Whether the player works as a medicine cat now (a medicine cat or their apprentice): their duties are healing. */
    public static boolean healer(ServerPlayer p) {
        ClanPlayerRank r = rank(p);
        return r == ClanPlayerRank.MEDICINE || r == ClanPlayerRank.MEDICINEAPP;
    }

    private static ClanPlayerRank rank(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        return wc == null ? null : wc.members.get(p.getUUID());
    }

    private static boolean training(ServerPlayer p, ClanPlayerRank rank) {
        return (rank == ClanPlayerRank.APPRENTICE || rank == ClanPlayerRank.MEDICINEAPP) && QuestState.storyStep(p) < QuestState.CEREMONY;
    }

    /** "About my place in the Clan", asked of the leader: what they can offer this player now. */
    static void offer(ServerPlayer p, WCatEntity leader) {
        ClanPlayerRank rank = rank(p);
        String name = Kin.name(leader), you = Joining.catName(p);
        int colour = Kin.colour(p);
        List<Choice> choices = new ArrayList<>();
        String text;
        if (rank == null) {
            text = "You'll have to join a Clan first, " + you + ".";
        } else if (training(p, rank) && rank == ClanPlayerRank.APPRENTICE) {
            text = "Your training, " + you + "? Every Clan needs its warriors, but it needs a medicine cat as well. Where do you feel StarClan calling you?";
            choices.add(Choice.of("Train as a medicine cat instead", "greatjourney quest path medicine"));
        } else if (training(p, rank)) {
            text = "Is the medicine den not for you, " + you + "? There's no shame in it. A medicine cat's life is not an easy one.";
            choices.add(Choice.of("Train as a warrior instead", "greatjourney quest path warrior"));
        } else {
            switch (rank) {
                case WARRIOR, DEPUTY -> {
                    text = "You have served the Clan well, " + you + ". What is on your mind?";
                    choices.add(Choice.of("Retire to the elders' den", "greatjourney quest role elder"));
                    choices.add(Choice.of("Help raise the kits in the nursery", "greatjourney quest role queen"));
                }
                case MEDICINE -> {
                    text = "The whole Clan leans on you, " + you + ". Are you tired, old friend?";
                    choices.add(Choice.of("Retire to the elders' den", "greatjourney quest role elder"));
                }
                case ELDER, QUEEN -> {
                    text = rank == ClanPlayerRank.ELDER ? "Restless in the elders' den already, " + you + "?" : "The kits will miss you, " + you + ".";
                    choices.add(Choice.of(QuestState.roleBefore(p).equals("medicine") ? "Go back to the medicine den" : "Go back to warrior duties",
                            "greatjourney quest role back"));
                    if (rank == ClanPlayerRank.QUEEN) choices.add(Choice.of("Retire to the elders' den", "greatjourney quest role elder"));
                }
                case KIT -> text = "Patience, little one! You are still a kit. In " + com.ninjacat.greatjourney.clanlands.life.Kits.minutesLeftOf(p)
                        + " minutes or so you will be old enough, and then I will make you an apprentice.";
                default -> text = "Your ceremony is waiting, " + you + ". Come to camp and we'll see it done.";
            }
        }
        Line line = Line.say(name, colour, text).speakerEntity(leader);
        if (!choices.isEmpty()) {
            choices.add(Choice.of("Never mind", "greatjourney quest bye"));
            line = line.withChoices(choices.toArray(Choice[]::new));
        }
        Dialog.play(p, line);
    }

    /**
     * An apprentice chooses (or changes) their path: a medicine cat apprentice learns from the Clan's medicine cat and
     * sleeps in the medicine den; a warrior's apprentice learns from a warrior. A change starts the training again
     * from the first step, with a new mentor who finds them in camp. Clan standing is kept.
     */
    public static boolean choosePath(ServerPlayer p, QuestState.Path path) {
        ClanData.Clan wc = Joining.clanOf(p);
        Clan clan = Kin.bookClan(p);
        ClanPlayerRank rank = rank(p);
        if (wc == null || clan == null || !training(p, rank)) {
            Dialog.subtitle(p, Line.narrate("Only an apprentice still in training can choose the path they train on."));
            return false;
        }
        ClanPlayerRank to = path == QuestState.Path.MEDICINE ? ClanPlayerRank.MEDICINEAPP : ClanPlayerRank.APPRENTICE;
        if (QuestState.path(p) == path && rank == to) {
            Dialog.subtitle(p, Line.narrate(path == QuestState.Path.MEDICINE
                    ? "You train as a medicine cat. Your mentor will find you in camp."
                    : "You train as a warrior. Your mentor will find you in camp."));
            return true;
        }
        var ow = p.serverLevel().getServer().overworld();
        Lead lead = Lead.of(p);
        if (lead != null && lead.cat().getUUID().equals(QuestState.mentorId(p))) Lead.stop(p);     // the old mentor's walk ends
        QuestState.setPath(p, path);
        QuestState.setStoryStep(p, 0);
        QuestState.setStory(p, null);
        QuestState.clearMentor(p);
        ClanData.get(ow).changeMemberRank(p.getUUID(), wc.clanUUID, to);
        boolean healer = path == QuestState.Path.MEDICINE;
        Nests.claim(p, clan, healer ? DenKind.MEDICINE : DenKind.APPRENTICES);
        LifeData life = LifeData.get(ow);
        String leader = life.leader(clan), you = Joining.catName(p);
        int colour = Kin.colour(p);
        WCatEntity leaderCat = Story.catNamed(p, clan, leader);
        String teacher = healer ? medicineCat(p, clan) : null;
        Line said = Line.say(leader, colour, healer
                ? "So be it. From this day " + you + " will learn the ways of a medicine cat" + (teacher == null ? "." : ", and " + teacher + " will teach them.")
                : "So be it. " + you + " will train as a warrior, and one of our warriors will take them as their apprentice.");
        Dialog.play(p, leaderCat == null ? said : said.speakerEntity(leaderCat),
                Line.narrate(healer ? "Your nest is in the medicine den now. " + (teacher == null ? "Your mentor" : teacher) + " will find you in camp."
                        : "Your nest is in the apprentices' den. Your new mentor will find you in camp."));
        Ui.push(p);
        return true;
    }

    /**
     * A grown cat takes another place: "elder" (from a warrior, the deputy, a medicine cat or a queen), "queen" (from a
     * warrior: the nursery) or "back" (an elder or a queen back to what they were before).
     */
    public static boolean takeRole(ServerPlayer p, String role) {
        ClanData.Clan wc = Joining.clanOf(p);
        Clan clan = Kin.bookClan(p);
        ClanPlayerRank rank = rank(p);
        if (wc == null || clan == null || rank == null) return false;
        String you = Joining.catName(p);
        ClanPlayerRank to = null;
        DenKind den = null;
        String before = QuestState.roleBefore(p);
        List<String> words = new ArrayList<>();
        switch (role) {
            case "elder" -> {
                if (rank == ClanPlayerRank.WARRIOR || rank == ClanPlayerRank.DEPUTY || rank == ClanPlayerRank.MEDICINE || rank == ClanPlayerRank.QUEEN) {
                    if (rank != ClanPlayerRank.QUEEN) before = rank == ClanPlayerRank.MEDICINE ? "medicine" : "warrior";
                    to = ClanPlayerRank.ELDER;
                    den = DenKind.ELDERS;
                    words.add(you + ", is it your wish to give up your duties and go to join the elders?");
                    words.add(REPLY + "It is.");
                    words.add("Your Clan honours you and all the service you have given. I will call upon StarClan to give you many moons of rest.");
                }
            }
            case "queen" -> {
                if (rank == ClanPlayerRank.WARRIOR || rank == ClanPlayerRank.DEPUTY) {
                    before = "warrior";
                    to = ClanPlayerRank.QUEEN;
                    den = DenKind.NURSERY;
                    words.add("The nursery can always use another pair of paws. Move your nest there, " + you + ", and help the queens raise the Clan's kits.");
                }
            }
            case "back" -> {
                if (rank == ClanPlayerRank.ELDER || rank == ClanPlayerRank.QUEEN) {
                    boolean healer = before.equals("medicine");
                    to = healer ? ClanPlayerRank.MEDICINE : ClanPlayerRank.WARRIOR;
                    den = healer ? DenKind.MEDICINE : DenKind.WARRIORS;
                    words.add("Welcome back to your duties, " + you + ". The Clan has missed you" + (healer ? " in the medicine den." : " on patrol."));
                }
            }
            default -> { }
        }
        if (to == null) {
            Dialog.subtitle(p, Line.narrate("That isn't something you can ask for now."));
            return false;
        }
        var ow = p.serverLevel().getServer().overworld();
        QuestState.setRoleBefore(p, before);
        ClanData.get(ow).changeMemberRank(p.getUUID(), wc.clanUUID, to);
        Nests.claim(p, clan, den);
        String leader = LifeData.get(ow).leader(clan);
        WCatEntity leaderCat = Story.catNamed(p, clan, leader);
        int colour = Kin.colour(p);
        List<Line> lines = new ArrayList<>();
        for (String w : words) {
            if (w.startsWith(REPLY)) {
                lines.add(Line.say(you, colour, w.substring(REPLY.length())));
                continue;
            }
            Line l = Line.say(leader, colour, w);
            lines.add(leaderCat == null ? l : l.speakerEntity(leaderCat));
        }
        lines.add(Line.narrate(switch (to) {
            case ELDER -> "Your nest is in the elders' den now. If you ever want your old duties back, ask " + leader + ".";
            case QUEEN -> "Your nest is in the nursery now. If you want to go back to your duties, ask " + leader + ".";
            default -> "Your nest is back where it was.";
        }));
        Dialog.play(p, lines);
        Ui.push(p);
        return true;
    }

    /** The name of the Clan's medicine cat in or near camp, or null. */
    private static String medicineCat(ServerPlayer p, Clan clan) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return null;
        Landmark camp = Homeland.camp(w, clan);
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        for (WCatEntity c : Kin.catsNear(p.serverLevel(), life.clanId(clan), camp.x(), camp.z(), camp.radius() + 60)) {
            if (Kin.role(c, life) == Kin.Role.MEDICINE) return Kin.name(c);
        }
        return null;
    }
}
