package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.snowteb.warriorcats_events.attachments.ModAttachments;
import net.snowteb.warriorcats_events.attachments.WCEPlayerData;
import net.snowteb.warriorcats_events.attachments.WCEPlayerDataUtils;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The apprentice path: a mentor from the Clan's warriors teaches the player in six steps (territory, hunting, herbs,
 * a border patrol, the places every apprentice knows, and an assessment), then the leader holds the warrior ceremony
 * in camp and the player chooses their warrior name. Each player walks it on their own.
 */
final class Story {
    private Story() {}

    /** Once a second per player in a book Clan: start the path, keep the mentor, and call the ceremony. */
    static void tick(ServerPlayer p) {
        Clan clan = Kin.bookClan(p);
        ClanWorld w = ClanWorld.current();
        if (clan == null || w == null || Dialog.busy(p)) return;
        ClanData.Clan wc = Joining.clanOf(p);
        int step = QuestState.storyStep(p);
        Landmark camp = Homeland.camp(w, clan);
        double toCamp = Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z());
        if (step == 0) {
            if (wc.members.get(p.getUUID()) == ClanData.ClanPlayerRank.APPRENTICE && toCamp < camp.radius() + 40) {
                assignMentor(p, clan, wc, camp, false);
            }
            return;
        }
        if (step >= 1 && step <= 6) {
            LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
            String mentor = QuestState.mentorName(p);
            boolean gone = life.starClan().stream().anyMatch(f -> f.name().equalsIgnoreCase(mentor));
            if (gone && toCamp < camp.radius() + 40) {
                assignMentor(p, clan, wc, camp, true);
            } else if (QuestState.story(p) == null) {
                QuestState.setStory(p, Tasks.storyStep(step, clan, mentor, QuestState.mentorId(p), p.getUUID().getMostSignificantBits()));
                Ui.push(p);
            }
            return;
        }
        long now = p.level().getGameTime();
        if (step == QuestState.CEREMONY && toCamp < camp.radius() + 6 && now - QuestState.root(p).getLong("ceremonyAt") > 600) {
            QuestState.root(p).putLong("ceremonyAt", now);     // asked again at most every 30 s if the window is closed
            ceremony(p, clan);
        }
    }

    private static void assignMentor(ServerPlayer p, Clan clan, ClanData.Clan wc, Landmark camp, boolean replacement) {
        ServerLevel level = p.serverLevel();
        LifeData life = LifeData.get(level.getServer().overworld());
        List<WCatEntity> warriors = new ArrayList<>(Kin.catsNear(level, wc.clanUUID, camp.x(), camp.z(), camp.radius() + 60));
        warriors.removeIf(c -> Kin.role(c, life) != Kin.Role.WARRIOR);
        if (warriors.isEmpty()) return;                               // the camp isn't loaded or has no warriors yet
        warriors.sort(Comparator.comparingDouble(c -> c.distanceToSqr(p)));
        WCatEntity mentor = warriors.get(0);
        String name = Kin.name(mentor);
        QuestState.setMentor(p, name, mentor.getUUID());
        int step = Math.max(1, QuestState.storyStep(p));
        QuestState.setStoryStep(p, step);
        Task t = Tasks.storyStep(step, clan, name, mentor.getUUID(), p.getUUID().getMostSignificantBits());
        QuestState.setStory(p, t);
        int colour = BookClans.Info.of(clan).colour;
        String you = Joining.catName(p);
        Dialog.play(p,
                replacement
                        ? Line.say(name, colour, "Your mentor hunts with StarClan now, " + you + ". I'll finish your training. We go on from where you left off.").speakerEntity(mentor)
                        : Line.say(name, colour, "So you're my new apprentice, " + you + ". I'm " + name + ". Stay close and keep your ears open.").speakerEntity(mentor),
                Line.say(name, colour, t.title + ": " + t.description).speakerEntity(mentor),
                Line.narrate("Your journal shows what to do next. Talk to " + name + " when you are done."));
        Ui.push(p);
    }

    /** A step is handed in: on to the next, or ready for the ceremony after the assessment. */
    static void stepDone(ServerPlayer p, Task done, WCatEntity mentor) {
        Clan clan = Kin.bookClan(p);
        int colour = Kin.colour(p);
        String name = mentor != null ? Kin.name(mentor) : QuestState.mentorName(p);
        int step = QuestState.storyStep(p) + 1;
        QuestState.setStoryStep(p, step);
        List<Line> lines = new ArrayList<>();
        if (step <= 6 && clan != null) {
            Task next = Tasks.storyStep(step, clan, name, QuestState.mentorId(p), p.getUUID().getMostSignificantBits());
            QuestState.setStory(p, next);
            lines.add(say(name, colour, praise(done.kind), mentor));
            lines.add(say(name, colour, "Next: " + next.title + ". " + next.description, mentor));
        } else {
            QuestState.setStory(p, null);
            QuestState.setStoryStep(p, QuestState.CEREMONY);
            String leader = clan == null ? "our leader" : LifeData.get(p.serverLevel().getServer().overworld()).leader(clan);
            lines.add(say(name, colour, "I watched you hunt. You were swift and silent, and you thought of the Clan first.", mentor));
            lines.add(say(name, colour, "You're ready. Go back to camp: " + leader + " will give you your warrior name.", mentor));
        }
        lines.add(Line.narrate("Your standing in the Clan grows (+" + done.standing + ")."));
        Dialog.play(p, lines);
    }

    private static Line say(String name, int colour, String text, WCatEntity cat) {
        Line l = Line.say(name, colour, text);
        return cat == null ? l : l.speakerEntity(cat);
    }

    private static String praise(Task.Kind kind) {
        return switch (kind) {
            case STORY_TERRITORY -> "You'll know these borders in your sleep soon. Good.";
            case STORY_HUNT -> "Not bad at all! The elders will be pleased.";
            case STORY_HERBS -> "The medicine cat says you have a gentle paw with herbs.";
            case STORY_PATROL -> "Our borders are safe, and you kept up the whole way.";
            case STORY_PLACES -> "Now you know the places every warrior knows.";
            default -> "Well done.";
        };
    }

    /** The warrior ceremony: the leader names the player, who chooses the end of their warrior name. */
    static void ceremony(ServerPlayer p, Clan clan) {
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        String leader = life.leader(clan);
        int colour = BookClans.Info.of(clan).colour;
        String prefix = prefix(p);
        RandomSource r = RandomSource.create(p.getUUID().getLeastSignificantBits() ^ 0xCE7E30L);
        List<String> choices = new ArrayList<>();
        while (choices.size() < 4) {
            String s = WCatEntity.SUFIXES[r.nextInt(WCatEntity.SUFIXES.length)].toLowerCase(Locale.ROOT).trim();
            if (!s.isEmpty() && !s.equals("paw") && !s.equals("kit") && !s.equals("star") && !choices.contains(s)) choices.add(s);
        }
        QuestState.setCeremonyChoices(p, choices);
        WCatEntity leaderCat = leaderCat(p, clan, leader);
        List<Choice> options = new ArrayList<>();
        for (String s : choices) options.add(Choice.of(prefix + s, "greatjourney quest warrior " + s));
        Dialog.play(p,
                say(leader, colour, "Cats of " + BookClans.Info.of(clan).title + ", gather round! Tonight we welcome a new warrior.", leaderCat),
                say(leader, colour, "StarClan, look down on this apprentice. They have learned the warrior code and served the Clan with brave paws and a loyal heart.", leaderCat),
                say(leader, colour, "Will you guard this Clan and keep the warrior code, even at the cost of your life? Then it is time for your warrior name.", leaderCat),
                say(leader, colour, Joining.catName(p) + ", by the powers of StarClan, what name will you carry as a warrior?", leaderCat)
                        .withChoices(options.toArray(Choice[]::new)));
    }

    /** The name chosen: Warrior Cats Events renames the player's cat, the Clan makes them a warrior, all cheer. */
    static boolean becomeWarrior(ServerPlayer p, String suffix) {
        Clan clan = Kin.bookClan(p);
        if (clan == null || QuestState.storyStep(p) != QuestState.CEREMONY || !QuestState.ceremonyChoices(p).contains(suffix)) return false;
        String prefix = prefix(p);
        String name = prefix + suffix;
        WCEPlayerData data = p.getData(ModAttachments.PLAYER_WCE_DATA.get());
        data.setPrefix(prefix);
        data.setSufix(suffix);
        data.setUseSufixes(true);
        data.setMorphName(name);
        if (tocraft.walkers.api.PlayerShape.getCurrentShape(p) != null) {
            tocraft.walkers.api.PlayerShape.updateShapes(p, WCEPlayerDataUtils.createShape(p, p.level()));
        }
        ClanData.Clan wc = Joining.clanOf(p);
        ClanData.get(p.serverLevel().getServer().overworld()).changeMemberRank(p.getUUID(), wc.clanUUID, ClanData.ClanPlayerRank.WARRIOR);
        QuestState.setStoryStep(p, QuestState.WARRIOR);
        QuestState.setWarriorName(p, name);
        QuestState.addStanding(p, 15);
        p.giveExperiencePoints(120);

        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        String leader = life.leader(clan);
        int colour = BookClans.Info.of(clan).colour;
        WCatEntity leaderCat = leaderCat(p, clan, leader);
        List<Line> lines = List.of(
                say(leader, colour, "From this moment you will be known as " + name + ". StarClan smiles on your courage and your kindness, and we welcome you as a warrior of " + BookClans.Info.of(clan).title + ".", leaderCat),
                Line.narrate("The Clan calls your new name: " + name + "! " + name + "!"),
                say(leader, colour, "Tonight you will sit a silent vigil and guard the camp while the rest of us sleep.", leaderCat));
        Dialog.play(p, lines);
        // everyone else in the Clan, and anyone close by, hears it too
        List<Line> news = List.of(Line.say(leader, colour, "From this moment, " + Joining.catName(p) + " will be known as " + name + "!"),
                Line.narrate("The Clan calls: " + name + "! " + name + "!"));
        MinecraftServer server = p.serverLevel().getServer();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == p) continue;
            boolean member = wc.members.containsKey(other.getUUID());
            boolean near = other.level() == p.level() && other.distanceTo(p) < 48;
            if (member || near) Dialog.subtitle(other, news);
        }
        Ui.push(p);
        return true;
    }

    /** The first part of the player's cat name, without "-paw" or "-kit". */
    static String prefix(ServerPlayer p) {
        WCEPlayerData data = p.getData(ModAttachments.PLAYER_WCE_DATA.get());
        String pre = data.getPrefix();
        if (pre != null && !pre.isBlank()) return capital(pre.trim());
        String n = Joining.catName(p).trim();
        String lower = n.toLowerCase(Locale.ROOT);
        for (String end : new String[] {"paw", "kit"}) {
            if (lower.endsWith(end) && n.length() > end.length()) return capital(n.substring(0, n.length() - end.length()));
        }
        int space = n.indexOf(' ');
        return capital(space > 0 ? n.substring(0, space) : n);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static WCatEntity leaderCat(ServerPlayer p, Clan clan, String leader) {
        Landmark camp = Homeland.camp(ClanWorld.current(), clan);
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        for (WCatEntity c : Kin.catsNear(p.serverLevel(), life.clanId(clan), camp.x(), camp.z(), camp.radius() + 40)) {
            if (Kin.name(c).equalsIgnoreCase(leader)) return c;
        }
        return null;
    }
}
