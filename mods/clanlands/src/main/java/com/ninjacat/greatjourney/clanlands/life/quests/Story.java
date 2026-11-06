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
 * in camp and the player chooses their warrior name. Each player walks it on their own. The mentor walks parts of it
 * with them: see {@link Mentor}.
 *
 * <p>An apprentice who chose the medicine cat's path ({@link Paths}) walks its six steps instead, taught by the Clan's
 * medicine cat, and the medicine cat names them a full medicine cat at the end.
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
            ClanData.ClanPlayerRank rank = wc.members.get(p.getUUID());
            if ((rank == ClanData.ClanPlayerRank.APPRENTICE || rank == ClanData.ClanPlayerRank.MEDICINEAPP) && toCamp < camp.radius() + 40) {
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
                QuestState.setStory(p, Tasks.storyStep(QuestState.path(p), step, clan, mentor, QuestState.mentorId(p), p.getUUID().getMostSignificantBits()));
                Ui.push(p);
            } else {
                Mentor.tick(p, clan, step, camp, toCamp);           // the mentor walks, watches and looks in (Mentor)
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
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        Kin.Role teaches = healer ? Kin.Role.MEDICINE : Kin.Role.WARRIOR;          // a medicine cat apprentice learns from the medicine cat
        List<WCatEntity> warriors = new ArrayList<>(Kin.catsNear(level, wc.clanUUID, camp.x(), camp.z(), camp.radius() + 60));
        warriors.removeIf(c -> Kin.role(c, life) != teaches);
        if (warriors.isEmpty() && healer) {
            // the medicine cat is out gathering herbs (or its chunk is not loaded): only the medicine cat teaches a
            // medicine cat apprentice, so it is fetched home to camp, or, if the Clan has none loaded at all, one comes
            WCatEntity healerCat = fetchMedicineCat(p, clan, wc, camp, life);
            if (healerCat != null) warriors.add(healerCat);
        }
        if (warriors.isEmpty()) return;                               // the camp isn't loaded or has no warriors (or medicine cat) yet
        warriors.sort(Comparator.comparingDouble(c -> c.distanceToSqr(p)));
        WCatEntity mentor = warriors.get(0);
        String name = Kin.name(mentor);
        QuestState.setMentor(p, name, mentor.getUUID());
        int step = Math.max(1, QuestState.storyStep(p));
        QuestState.setStoryStep(p, step);
        Task t = Tasks.storyStep(QuestState.path(p), step, clan, name, mentor.getUUID(), p.getUUID().getMostSignificantBits());
        QuestState.setStory(p, t);
        int colour = BookClans.Info.of(clan).colour;
        String you = Joining.catName(p);
        Dialog.play(p,
                replacement
                        ? Line.say(name, colour, "Your mentor hunts with StarClan now, " + you + ". I'll finish your training. We go on from where you left off.").speakerEntity(mentor)
                        : healer
                        ? Line.say(name, colour, "So you'll walk the medicine cat's path, " + you + ". I'm " + name + ". There's a lot to learn: every herb, every sickness, every sign StarClan sends.").speakerEntity(mentor)
                        : Line.say(name, colour, "So you're my new apprentice, " + you + ". I'm " + name + ". Stay close and keep your ears open.").speakerEntity(mentor),
                Line.say(name, colour, t.title + ": " + t.description).speakerEntity(mentor),
                Line.narrate("Your journal shows what to do next. Talk to " + name + " when you are done."));
        Ui.push(p);
    }

    /**
     * The medicine cat who will speak to a medicine cat apprentice in camp: their mentor if it is about, else their
     * mentor brought home from wherever it is loaded, else the Clan's medicine cat ({@link #fetchMedicineCat}).
     */
    static WCatEntity healerHere(ServerPlayer p, Clan clan) {
        WCatEntity near = catNamed(p, clan, QuestState.mentorName(p));
        if (near != null) return near;
        ServerLevel ow = p.serverLevel().getServer().overworld();
        ClanData.Clan wc = Joining.clanOf(p);
        ClanWorld w = ClanWorld.current();
        if (wc == null || w == null || p.serverLevel() != ow) return null;
        Landmark camp = Homeland.camp(w, clan);
        java.util.UUID id = QuestState.mentorId(p);
        if (id != null && ow.getEntity(id) instanceof WCatEntity m && m.isAlive() && !m.isNoAi()
                && !com.ninjacat.greatjourney.clanlands.life.Lead.leading(m)) {
            net.minecraft.core.BlockPos at = BookClans.safeSpot(ow, (int) Math.floor(p.getX()) + 2, (int) Math.floor(p.getZ()) + 2, 4);
            m.getNavigation().stop();
            m.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            m.wanderCenter = BookClans.surface(ow, (int) Math.floor(camp.x()), (int) Math.floor(camp.z()));
            return m;
        }
        return fetchMedicineCat(p, clan, wc, camp, LifeData.get(ow));
    }

    /**
     * The Clan's medicine cat, brought to the player in camp: the nearest loaded one of the Clan anywhere in the world is
     * teleported home (a cat in a scene, at a Gathering, guiding, or the tunnel keeper is left where it is), and if no
     * medicine cat of the Clan is loaded at all, a new one is made in camp. Returns null only if none could be made.
     */
    private static WCatEntity fetchMedicineCat(ServerPlayer p, Clan clan, ClanData.Clan wc, Landmark camp, LifeData life) {
        ServerLevel ow = p.serverLevel().getServer().overworld();
        if (p.serverLevel() != ow) return null;
        WCatEntity best = null;
        for (net.minecraft.world.entity.Entity e : ow.getAllEntities()) {
            if (!(e instanceof WCatEntity c) || !c.isAlive() || !wc.clanUUID.equals(c.getClanUUID()) || c.getCustomName() == null) continue;
            if (c.isNoAi() || c.getTags().contains(com.ninjacat.greatjourney.clanlands.life.Lead.GUIDE_TAG) || c.getTags().contains("greatjourney_gathering")
                    || c.getTags().contains(com.ninjacat.greatjourney.clanlands.life.TunnelKeeper.TAG)
                    || c.getTags().contains(com.ninjacat.greatjourney.clanlands.life.dream.DreamClearing.TAG)) continue;
            if (Kin.role(c, life) != Kin.Role.MEDICINE) continue;
            if (best == null || c.distanceToSqr(camp.x(), c.getY(), camp.z()) < best.distanceToSqr(camp.x(), best.getY(), camp.z())) best = c;
        }
        RandomSource r = p.getRandom();
        double a = r.nextDouble() * Math.PI * 2;
        net.minecraft.core.BlockPos at = BookClans.safeSpot(ow, (int) Math.floor(p.getX() + Math.cos(a) * 3), (int) Math.floor(p.getZ() + Math.sin(a) * 3), 4);
        net.minecraft.core.BlockPos home = BookClans.surface(ow, (int) Math.floor(camp.x()), (int) Math.floor(camp.z()));
        if (best != null) {
            best.getNavigation().stop();
            best.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            best.wanderCenter = home;
            best.mode = WCatEntity.CatMode.WANDER;
            return best;
        }
        // no medicine cat of this Clan is loaded anywhere: the Clan's medicine cat comes back to camp (a new cat)
        ClanWorld w = ClanWorld.current();
        if (w == null) return null;
        com.ninjacat.greatjourney.clanlands.life.ClanNames.Name n =
                new com.ninjacat.greatjourney.clanlands.life.ClanNames(ow.getSeed() ^ p.getUUID().getMostSignificantBits() ^ ow.getGameTime() ^ (clan.ordinal() + 1) * 0x2545F491L).warrior();
        return BookClans.spawnCat(ow, clan, wc.clanUUID, BookClans.Role.MEDICINE, n, r.nextInt(2), at, home, r);
    }

    /**
     * Whether this cat can take the player's task: the cat who gave it, or, for a step of the apprentice path whose
     * mentor is not about (gone, replaced, or out of reach), a cat of the mentor's name or, on the medicine cat's path,
     * the Clan's medicine cat now. The step was tied to one cat's id, and a medicine cat apprentice whose medicine cat
     * had gone (or been made anew) could never hand a step in again.
     */
    static boolean answers(ServerPlayer p, WCatEntity cat, Task t) {
        if (cat.getUUID().equals(t.giverId)) return true;
        if (!t.story) return false;
        if (t.giverId != null && p.serverLevel().getEntity(t.giverId) instanceof WCatEntity m && m.isAlive() && m.distanceTo(p) < 96) return false;
        Clan clan = Kin.bookClan(p);
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        if (clan == null || !java.util.Objects.equals(cat.getClanUUID(), life.clanId(clan))) return false;
        if (t.giverName != null && Kin.name(cat).equalsIgnoreCase(t.giverName)) return true;
        return QuestState.path(p) == QuestState.Path.MEDICINE && Kin.role(cat, life) == Kin.Role.MEDICINE;
    }

    /** A step is handed in: on to the next, or ready for the ceremony after the assessment. */
    static void stepDone(ServerPlayer p, Task done, WCatEntity mentor) {
        Clan clan = Kin.bookClan(p);
        int colour = Kin.colour(p);
        String name = mentor != null ? Kin.name(mentor) : QuestState.mentorName(p);
        int step = QuestState.storyStep(p) + 1;
        QuestState.setStoryStep(p, step);
        List<Line> lines = new ArrayList<>();
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        if (step <= 6 && clan != null) {
            Task next = Tasks.storyStep(QuestState.path(p), step, clan, name, QuestState.mentorId(p), p.getUUID().getMostSignificantBits());
            QuestState.setStory(p, next);
            lines.add(say(name, colour, praise(done.kind, healer), mentor));
            lines.add(say(name, colour, "Next: " + next.title + ". " + next.description, mentor));
        } else {
            QuestState.setStory(p, null);
            QuestState.setStoryStep(p, QuestState.CEREMONY);
            String leader = clan == null ? "our leader" : LifeData.get(p.serverLevel().getServer().overworld()).leader(clan);
            if (healer) {
                lines.add(say(name, colour, "You found every one, and you knew what each was for. StarClan has chosen well.", mentor));
                lines.add(say(name, colour, "You're ready. Meet me in camp, and I will give you your full name as a medicine cat.", mentor));
            } else {
                lines.add(say(name, colour, "I watched you hunt. You were swift and silent, and you thought of the Clan first.", mentor));
                lines.add(say(name, colour, "You're ready. Go back to camp: " + leader + " will give you your warrior name.", mentor));
            }
        }
        lines.add(Line.narrate("Your standing in the Clan grows (+" + done.standing + ")."));
        Dialog.play(p, lines);
    }

    private static Line say(String name, int colour, String text, WCatEntity cat) {
        Line l = Line.say(name, colour, text);
        return cat == null ? l : l.speakerEntity(cat);
    }

    private static String praise(Task.Kind kind, boolean healer) {
        if (healer && kind == Task.Kind.STORY_TERRITORY) return "You know the medicine den now, and where every cat sleeps. When one of them is hurt, you'll know where to find them.";
        if (healer && kind == Task.Kind.STORY_PLACES) return "Now you know the way to the Moonpool. Never forget it: one day you'll walk it alone, with a sick Clan waiting on what StarClan tells you.";
        return switch (kind) {
            case STORY_TERRITORY -> "You know your way round camp now. Good.";
            case STORY_HUNT -> "Not bad at all! The elders will be pleased.";
            case STORY_HERBS -> "The medicine cat says you have a gentle paw with herbs.";
            case STORY_PATROL -> "Our borders are safe, and you kept up the whole way.";
            case STORY_PLACES -> "Now you know the places every Clan cat should know.";
            case STORY_MED_HERBS -> "Good, fresh leaves, and the roots left to grow again. You have a gentle paw.";
            case STORY_MED_REMEDIES -> "A good poultice, and not a seed wasted. The Clan will sleep easier for it.";
            case STORY_MED_ELDERS -> "The elders say they haven't slept so soft in moons. They like you.";
            default -> "Well done.";
        };
    }

    /**
     * The warrior ceremony: the leader names the player, who chooses the end of their warrior name. On the medicine
     * cat's path the medicine cat who taught them (the leader, if they are not about) names them a full medicine cat.
     */
    static void ceremony(ServerPlayer p, Clan clan) {
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        String leader = life.leader(clan);
        int colour = BookClans.Info.of(clan).colour;
        String prefix = prefix(p);
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        RandomSource r = RandomSource.create(p.getUUID().getLeastSignificantBits() ^ 0xCE7E30L);
        List<String> choices = new ArrayList<>();
        while (choices.size() < 4) {
            String s = WCatEntity.SUFIXES[r.nextInt(WCatEntity.SUFIXES.length)].toLowerCase(Locale.ROOT).trim();
            if (!s.isEmpty() && !s.equals("paw") && !s.equals("kit") && !s.equals("star") && com.ninjacat.greatjourney.clanlands.life.ClanNames.fitsSuffix(s)
                    && !choices.contains(s)) choices.add(s);
        }
        QuestState.setCeremonyChoices(p, choices);
        WCatEntity leaderCat = leaderCat(p, clan, leader);
        List<Choice> options = new ArrayList<>();
        for (String s : choices) options.add(Choice.of(prefix + s, "greatjourney quest warrior " + s));
        if (healer) {
            // only a medicine cat names a medicine cat: the mentor (or, gone, the Clan's medicine cat) is brought to camp
            WCatEntity healerCat = healerHere(p, clan);
            if (healerCat == null) return;                                // asked again in 30 s
            String by = Kin.name(healerCat);
            WCatEntity byCat = healerCat;
            Dialog.play(p,
                    say(by, colour, "I, " + by + ", medicine cat of " + BookClans.Info.of(clan).title
                            + ", call upon my warrior ancestors to look down on this apprentice.", byCat),
                    say(by, colour, "They have trained hard to understand the ways of a medicine cat, and with your help they will serve their Clan for many moons.", byCat),
                    say(by, colour, Joining.catName(p) + ", do you promise to uphold the ways of a medicine cat, to stand apart from rivalry between Clan and Clan, "
                            + "and to protect all cats equally, even at the cost of your life?", byCat),
                    say(by, colour, "Then by the powers of StarClan, what name will you carry as a medicine cat?", byCat)
                            .withChoices(options.toArray(Choice[]::new)));
            return;
        }
        Dialog.play(p,
                say(leader, colour, "Cats of " + BookClans.Info.of(clan).title + ", gather round! Tonight we welcome a new warrior.", leaderCat),
                say(leader, colour, "StarClan, look down on this apprentice. They have learned the warrior code and served the Clan with brave paws and a loyal heart.", leaderCat),
                say(leader, colour, "Will you guard this Clan and keep the warrior code, even at the cost of your life? Then it is time for your warrior name.", leaderCat),
                say(leader, colour, Joining.catName(p) + ", by the powers of StarClan, what name will you carry as a warrior?", leaderCat)
                        .withChoices(options.toArray(Choice[]::new)));
    }

    /**
     * The name chosen: Warrior Cats Events renames the player's cat, the Clan makes them a warrior (or a full medicine
     * cat), all cheer.
     */
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
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        ClanData.get(p.serverLevel().getServer().overworld()).changeMemberRank(p.getUUID(), wc.clanUUID,
                healer ? ClanData.ClanPlayerRank.MEDICINE : ClanData.ClanPlayerRank.WARRIOR);
        if (healer) com.ninjacat.greatjourney.clanlands.life.Nests.claim(p, clan, com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind.MEDICINE);
        else com.ninjacat.greatjourney.clanlands.life.Nests.warrior(p, clan);            // their nest moves to the warriors' den
        QuestState.setStoryStep(p, QuestState.WARRIOR);
        QuestState.setWarriorName(p, name);
        com.ninjacat.greatjourney.clanlands.life.Kits.grow(p, WCEPlayerData.Age.ADULT);   // full-grown with the full name
        QuestState.addStanding(p, 15);
        p.giveExperiencePoints(120);

        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        String leader = life.leader(clan);
        int colour = BookClans.Info.of(clan).colour;
        WCatEntity leaderCat = leaderCat(p, clan, leader);
        if (healer) {
            WCatEntity healerCat = healerHere(p, clan);
            String by = healerCat != null ? Kin.name(healerCat) : QuestState.mentorName(p);
            Dialog.play(p, List.of(
                    say(by, colour, "From this moment you will be known as " + name + ". StarClan honours your wisdom and your kindness, and we welcome you as a full medicine cat of "
                            + BookClans.Info.of(clan).title + ".", healerCat),
                    Line.narrate("The Clan calls your new name: " + name + "! " + name + "!"),
                    Line.narrate("At every half moon you will share tongues with StarClan alongside the other Clans' medicine cats.")));
            cheer(p, wc, by, colour, name);
            return true;
        }
        List<Line> lines = List.of(
                say(leader, colour, "From this moment you will be known as " + name + ". StarClan smiles on your courage and your kindness, and we welcome you as a warrior of " + BookClans.Info.of(clan).title + ".", leaderCat),
                Line.narrate("The Clan calls your new name: " + name + "! " + name + "!"),
                say(leader, colour, "Tonight you will sit a silent vigil and guard the camp while the rest of us sleep.", leaderCat));
        Dialog.play(p, lines);
        cheer(p, wc, leader, colour, name);
        return true;
    }

    /** Everyone else in the Clan, and anyone close by, hears the new name too. */
    private static void cheer(ServerPlayer p, ClanData.Clan wc, String by, int colour, String name) {
        List<Line> news = List.of(Line.say(by, colour, "From this moment, " + Joining.catName(p) + " will be known as " + name + "!"),
                Line.narrate("The Clan calls: " + name + "! " + name + "!"));
        MinecraftServer server = p.serverLevel().getServer();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == p) continue;
            boolean member = wc.members.containsKey(other.getUUID());
            boolean near = other.level() == p.level() && other.distanceTo(p) < 48;
            if (member || near) Dialog.subtitle(other, news);
        }
        Ui.push(p);
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
        return catNamed(p, clan, leader);
    }

    /** The Clan's cat of that name in or near its camp, or null. */
    static WCatEntity catNamed(ServerPlayer p, Clan clan, String name) {
        if (name == null || name.isEmpty()) return null;
        Landmark camp = Homeland.camp(ClanWorld.current(), clan);
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        for (WCatEntity c : Kin.catsNear(p.serverLevel(), life.clanId(clan), camp.x(), camp.z(), camp.radius() + 40)) {
            if (Kin.name(c).equalsIgnoreCase(name)) return c;
        }
        return null;
    }
}
