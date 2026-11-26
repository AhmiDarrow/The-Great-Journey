package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Nests;
import com.ninjacat.greatjourney.clanlands.life.events.Cats;
import com.ninjacat.greatjourney.clanlands.life.events.Deaths;
import com.ninjacat.greatjourney.clanlands.life.events.EventNews;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import com.ninjacat.greatjourney.clanlore.role.Role;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.snowteb.warriorcats_events.attachments.ModAttachments;
import net.snowteb.warriorcats_events.attachments.WCEPlayerData;
import net.snowteb.warriorcats_events.attachments.WCEPlayerDataUtils;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.clan.ClanData.ClanPlayerRank;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A warrior's rise in a book Clan, all the way to leader. A warrior who has trained an apprentice ({@link Pupils}) and
 * earned the Clan's trust (standing) can be named deputy: by asking the leader, or when the deputy's place falls
 * empty. A deputy who has served a while leads the Clan when the leader's last life is gone, or when the old leader,
 * asked, steps down to the elders' den. The new leader walks to the Moonpool (the Moonstone, in the old forest) for
 * nine lives and a "-star" name, names a deputy of their own by talking to a warrior, loses a life at each death, and
 * joins the elders when the last is gone, the deputy leading after them. Asked for after play (2026-11-23): a player
 * could go no further than warrior.
 *
 * <p>Who leads is kept in {@link LifeData} with the leader's name and lives, as for a Clan cat. The player's Warrior
 * Cats Events rank stays the deputy's: its own leader rank may manage the Clan in WCE's screens (unclaim its land,
 * put cats out), and these are Clans the pack keeps. Everything is put right from what is true each second: a leader
 * who gave up their rank, a Clan cat still ranked deputy beside a player deputy, a deputy made by command.
 */
public final class Ranks {
    private Ranks() {}

    /** The standing a warrior needs to be named deputy, and a deputy to lead. */
    public static final int DEPUTY_STANDING = 120, LEADER_STANDING = 200;
    /** How long a deputy serves before the old leader will step down for them (three days). */
    static final long DEPUTY_SERVICE = 24000L * 3;
    /** How near the Moonpool (or Mothermouth) StarClan comes to a new leader. */
    static final double STAR_REACH = 24;

    private static LifeData life(ServerPlayer p) {
        return LifeData.get(p.getServer().overworld());
    }

    private static ClanPlayerRank rank(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        return wc == null ? null : wc.members.get(p.getUUID());
    }

    /** Whether the player leads their book Clan. */
    public static boolean leads(ServerPlayer p) {
        Clan c = Kin.bookClan(p);
        return c != null && p.getUUID().equals(life(p).leaderPlayer(c));
    }

    /** The player (online or not) who is the Clan's deputy, or null where a Clan cat is, or nobody. */
    static UUID deputyPlayer(ClanData.Clan wc, LifeData life, Clan c) {
        for (Map.Entry<UUID, ClanPlayerRank> e : wc.members.entrySet()) {
            if (e.getValue() == ClanPlayerRank.DEPUTY && !e.getKey().equals(life.leaderPlayer(c))) return e.getKey();
        }
        return null;
    }

    /** What still stands between this warrior and the deputy's place (each "do this"); empty when nothing does. */
    static List<String> deputyNeeds(ServerPlayer p) {
        List<String> needs = new ArrayList<>();
        if (Pupils.trained(p) < 1) needs.add("train an apprentice to their name");
        int s = QuestState.standing(p);
        if (s < DEPUTY_STANDING) needs.add("earn the Clan's trust through your duties (standing " + s + " of " + DEPUTY_STANDING + ")");
        return needs;
    }

    static List<String> leaderNeeds(ServerPlayer p) {
        List<String> needs = new ArrayList<>();
        long served = p.getServer().overworld().getGameTime() - QuestState.root(p).getLong("deputySince");
        if (served < DEPUTY_SERVICE) needs.add("serve as deputy a while longer (" + Math.max(1, (DEPUTY_SERVICE - served + 23999) / 24000) + " more days)");
        int s = QuestState.standing(p);
        if (s < LEADER_STANDING) needs.add("earn the Clan's trust through your duties (standing " + s + " of " + LEADER_STANDING + ")");
        return needs;
    }

    // --- deputy

    /** Makes the player deputy; returns the leader's words before the Clan. No Clan cat stays deputy beside them. */
    static List<Line> makeDeputy(ServerPlayer p, Clan clan, ClanData.Clan wc, String leaderName) {
        ServerLevel ow = p.getServer().overworld();
        ClanData.get(ow).changeMemberRank(p.getUUID(), wc.clanUUID, ClanPlayerRank.DEPUTY);
        for (WCatEntity c : Cats.atCamp(ow, clan)) if (c.getRank() == WCatEntity.Rank.DEPUTY) c.setRank(WCatEntity.Rank.WARRIOR);
        QuestState.root(p).putLong("deputySince", ow.getGameTime());
        QuestState.addStanding(p, 10);
        BookClans.Info info = BookClans.Info.of(clan);
        String you = Joining.catName(p);
        EventNews.record(clan, you + " is the new deputy of " + info.title + ".");
        Ui.push(p);
        return List.of(
                Line.say(leaderName, info.colour, "I say these words before StarClan, so that the spirits of our ancestors may hear and approve my choice. "
                        + you + " will be the new deputy of " + info.title + "."),
                Line.narrate("The Clan calls the new deputy's name: " + you + "! " + you + "!"));
    }

    /**
     * The deputy's place has fallen empty (the deputy died, or leads now): the leader's words if a player takes it,
     * no words if a player holds it already, null for the Clan to choose one of its warriors.
     */
    public static List<Line> deputyVacancy(ServerLevel ow, LifeData life, Clan c, String leaderName) {
        UUID id = life.clanId(c);
        ClanData.Clan wc = id == null ? null : ClanData.get(ow).getClan(id);
        if (wc == null) return null;
        if (deputyPlayer(wc, life, c) != null) return List.of();
        ServerPlayer best = null;
        for (ServerPlayer q : ow.getServer().getPlayerList().getPlayers()) {
            if (Kin.bookClan(q) != c || rank(q) != ClanPlayerRank.WARRIOR || QuestState.storyStep(q) != QuestState.WARRIOR) continue;
            if (!deputyNeeds(q).isEmpty()) continue;
            if (best == null || QuestState.standing(q) > QuestState.standing(best)) best = q;
        }
        return best == null ? null : makeDeputy(best, c, wc, leaderName);
    }

    /** The leader (a player) names a warrior their deputy, by talking to them. */
    static void nameDeputy(ServerPlayer p, WCatEntity cat) {
        Clan clan = Kin.bookClan(p);
        ClanData.Clan wc = Joining.clanOf(p);
        if (clan == null || wc == null || !leads(p)) return;
        LifeData life = life(p);
        BookClans.Info info = BookClans.Info.of(clan);
        String name = Kin.name(cat), you = Joining.catName(p);
        if (!wc.clanUUID.equals(cat.getClanUUID()) || Kin.role(cat, life) != Kin.Role.WARRIOR || !Mentors.fit(cat, life, false)) {
            Ui.subtitle(p, List.of(Line.narrate("Only a warrior of your own Clan, in their prime, can be your deputy.")));
            return;
        }
        if (deputyPlayer(wc, life, clan) != null) {
            Ui.subtitle(p, List.of(Line.narrate("You have a deputy already, and they serve the Clan well.")));
            return;
        }
        for (WCatEntity c : Cats.atCamp(p.getServer().overworld(), clan)) if (c.getRank() == WCatEntity.Rank.DEPUTY) c.setRank(WCatEntity.Rank.WARRIOR);
        cat.setRank(WCatEntity.Rank.DEPUTY);
        EventNews.record(clan, name + " is the new deputy of " + info.title + ".");
        Ui.play(p, List.of(
                Line.say(you, info.colour, "I say these words before StarClan, so that the spirits of our ancestors may hear and approve my choice. "
                        + name + " will be the new deputy of " + info.title + "."),
                Line.say(name, info.colour, "I never expected this, " + you + ". I will serve " + info.title + " with all my strength.").speakerEntity(cat)));
    }

    // --- leader

    /** The player leads the Clan from now: recorded as its leader, a nest in the leader's den, and nine lives to fetch. */
    private static void lead(ServerPlayer p, Clan clan, ClanData.Clan wc, LifeData life) {
        ServerLevel ow = p.getServer().overworld();
        ClanData data = ClanData.get(ow);
        String you = Joining.catName(p);
        // the deputy's rank stays theirs in Warrior Cats Events (see the class comment); LifeData says who leads
        if (wc.members.get(p.getUUID()) != ClanPlayerRank.DEPUTY) data.changeMemberRank(p.getUUID(), wc.clanUUID, ClanPlayerRank.DEPUTY);
        life.setLeaderPlayer(clan, p.getUUID(), you);
        wc.leaderName = you;
        data.setDirty();
        Nests.claim(p, clan, DenKind.LEADER);
        CompoundTag root = QuestState.root(p);
        root.putBoolean("nineLives", true);
        root.remove("deputySince");
        QuestState.addStanding(p, 20);
        EventNews.record(clan, you + " leads " + BookClans.Info.of(clan).title + " now.");
        Ui.push(p);
    }

    /**
     * The leader's last life is gone: a player who is the Clan's deputy, and here to take it up, leads. Returns what
     * the Clan hears, or null for the Clan's own succession (no player deputy, or one who is away and stays deputy).
     */
    public static List<Line> succession(ServerLevel ow, LifeData life, Clan c, List<WCatEntity> cats) {
        UUID id = life.clanId(c);
        ClanData.Clan wc = id == null ? null : ClanData.get(ow).getClan(id);
        UUID dep = wc == null ? null : deputyPlayer(wc, life, c);
        ServerPlayer p = dep == null ? null : ow.getServer().getPlayerList().getPlayer(dep);
        if (p == null || Kin.bookClan(p) != c) return null;
        BookClans.Info info = BookClans.Info.of(c);
        String you = Joining.catName(p);
        lead(p, c, wc, life);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate(you + ", deputy of " + info.title + ", leads the Clan now."));
        lines.add(Line.say(you, info.colour, "I will lead " + info.title + " as well as I can. " + starPlaceWords() + " StarClan must give me a leader's nine lives."));
        lines.addAll(Deaths.newDeputy(ow, life, c, cats, you));
        return lines;
    }

    private static String starPlaceWords() {
        return Homeland.forest() ? "Tonight I go to Mothermouth:" : "Tonight I go to the Moonpool:";
    }

    /** Where a new leader meets StarClan: the Moonpool by the lake, Mothermouth (the Moonstone) in the old forest. */
    static Landmark starPlace() {
        ClanWorld w = ClanWorld.current();
        if (w == null) return null;
        Landmark.Kind kind = Homeland.forest() ? Landmark.Kind.MOTHERMOUTH : Landmark.Kind.MOONPOOL;
        for (Landmark l : Homeland.map(w).landmarks()) if (l.kind() == kind) return l;
        return null;
    }

    static String starPlaceName() {
        return Homeland.forest() ? "Mothermouth" : "the Moonpool";
    }

    /** Gives the player's cat a new name ending (Warrior Cats Events renames and redraws the cat). */
    static String rename(ServerPlayer p, String suffix) {
        String prefix = Story.prefix(p);
        WCEPlayerData data = p.getData(ModAttachments.PLAYER_WCE_DATA.get());
        data.setPrefix(prefix);
        data.setSufix(suffix);
        data.setUseSufixes(true);
        data.setMorphName(prefix + suffix);
        if (tocraft.walkers.api.PlayerShape.getCurrentShape(p) != null) {
            tocraft.walkers.api.PlayerShape.updateShapes(p, WCEPlayerDataUtils.createShape(p, p.level()));
        }
        QuestState.setWarriorName(p, prefix + suffix);
        return prefix + suffix;
    }

    /** The nine lives, as StarClan gives them: the Clan's own fallen come, the most lately lost first. */
    static List<Line> nineLivesScene(String before, String star, String clanTitle, List<String> givers) {
        String[] gifts = {"courage", "justice", "loyalty", "tireless energy", "protection", "mentoring", "compassion", "love", "nobility, certainty and faith"};
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate("You lie down at the water's edge and touch your nose to it. Cold spreads through you, and the stars come down."));
        lines.add(Line.narrate("The cats of StarClan stand all around you, their fur shining with frost. One by one they step forward."));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < gifts.length; i++) {
            String who = i < givers.size() ? givers.get(i) : "a warrior of StarClan";
            s.append(i == 0 ? "" : " ").append(Character.toUpperCase(who.charAt(0))).append(who.substring(1)).append(": a life for ").append(gifts[i]).append(".");
            if (i % 3 == 2) {
                lines.add(Line.narrate(s.toString()));
                s.setLength(0);
            }
        }
        lines.add(Line.narrate("\"I hail you by your new name, " + star + ". Your old life is no more. You have received the nine lives of a leader, "
                + "and StarClan grants you the guardianship of " + clanTitle + ". Defend it well.\""));
        lines.add(Line.narrate("The stars call your name: " + star + "! " + star + "! You wake beside the water, " + before + " no longer."));
        return lines;
    }

    /** The Clan's cats in StarClan, the most lately lost first, for the nine lives. */
    static List<String> fallen(LifeData life, Clan c) {
        List<String> out = new ArrayList<>();
        List<LifeData.Fallen> all = life.starClan();
        for (int i = all.size() - 1; i >= 0 && out.size() < 9; i--) {
            if (all.get(i).clan() == c && !out.contains(all.get(i).name())) out.add(all.get(i).name());
        }
        return out;
    }

    // --- once a second

    /** Once a second from {@link Story#tick} for a named cat of a book Clan. */
    static void tick(ServerPlayer p, Clan clan, ClanData.Clan wc, Landmark camp, double toCamp) {
        ServerLevel ow = p.getServer().overworld();
        LifeData life = LifeData.get(ow);
        CompoundTag root = QuestState.root(p);
        ClanPlayerRank rank = wc.members.get(p.getUUID());
        long now = ow.getGameTime();
        if (!p.isAlive()) return;
        if (!leads(p)) {
            root.remove("nineLives");
            if (rank != ClanPlayerRank.DEPUTY) {
                root.remove("deputySince");
                return;
            }
            if (!root.contains("deputySince")) root.putLong("deputySince", now);          // made deputy some other way
            // no Clan cat is deputy beside a player (one that was not in camp when the player was named)
            if (now % 200 < 20 && toCamp < camp.radius() + 40) {
                for (WCatEntity c : Cats.atCamp(ow, clan)) if (c.getRank() == WCatEntity.Rank.DEPUTY) c.setRank(WCatEntity.Rank.WARRIOR);
            }
            return;
        }
        BookClans.Info info = BookClans.Info.of(clan);
        if (root.getBoolean("lifeLost")) {
            root.remove("lifeLost");
            int left = life.lives(clan);
            if (left > 0) {
                Ui.subtitle(p, List.of(Line.narrate("You lie still... then stir. StarClan has sent you back. You have "
                        + left + (left == 1 ? " life" : " lives") + " left to give your Clan.")));
            } else {
                // the ninth life: as in the books the leader is gone; here they wake an elder, and the Clan is led on
                ClanData.get(ow).changeMemberRank(p.getUUID(), wc.clanUUID, ClanPlayerRank.ELDER);
                QuestState.setRoleBefore(p, "warrior");
                Nests.claim(p, clan, DenKind.ELDERS);
                Ui.play(p, List.of(Line.narrate("Your ninth life is spent. StarClan sends you back one last time, not to lead: "
                        + "you wake in the elders' den, and " + info.title + " must follow another.")));
            }
            Ui.push(p);
            return;
        }
        if (rank != ClanPlayerRank.DEPUTY) {
            // the leader gave up their place (the elders' den, a rank changed in Warrior Cats Events): the Clan is led on
            // as soon as its cats are there to choose from; until then the old leader's name stands
            if (Cats.campCatsLoaded(ow, clan)) {
                List<WCatEntity> cats = new ArrayList<>(Cats.atCamp(ow, clan));
                tellClan(ow, wc, Deaths.succession(ow, life, clan, cats));
            }
            return;
        }
        if (root.getBoolean("nineLives")) {
            Landmark at = starPlace();
            if (at != null && p.level() == ow && Math.hypot(p.getX() - at.x(), p.getZ() - at.z()) <= STAR_REACH) {
                String before = Joining.catName(p);
                List<String> givers = fallen(life, clan);
                String star = rename(p, "star");
                life.setLeaderPlayer(clan, p.getUUID(), star);
                wc.leaderName = star;
                ClanData.get(ow).setDirty();
                root.remove("nineLives");
                p.giveExperiencePoints(200);
                EventNews.record(clan, before + " of " + info.title + " has received nine lives from StarClan, and is " + star + " now.");
                Ui.play(p, nineLivesScene(before, star, info.title, givers));
                Ui.push(p);
            } else if ((now / 20) % 10 == 0) {
                Ui.push(p);                                   // the journal's "which way" keeps up with them
            }
        }
    }

    private static void tellClan(ServerLevel ow, ClanData.Clan wc, List<Line> lines) {
        if (lines.isEmpty()) return;
        for (ServerPlayer q : ow.getServer().getPlayerList().getPlayers()) {
            ClanData.Clan mine = Joining.clanOf(q);
            if (mine != null && mine.clanUUID.equals(wc.clanUUID)) Ui.subtitle(q, lines);
        }
    }

    /** A leader's death costs a life (told when they wake); the founder of their own Clan counts theirs too. */
    static void onDeath(LivingDeathEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag root = QuestState.root(p);
        if (root.getBoolean("nineLives")) return;              // not yet given their lives: an ordinary death
        if (leads(p)) {
            life(p).loseLife(Kin.bookClan(p));
            root.putBoolean("lifeLost", true);
        } else {
            Founding.onDeath(p);
        }
    }

    // --- asking the leader, and the journal

    /** "deputy" or "leader", asked of the Clan's leader (see {@link Paths#offer}). */
    static boolean ask(ServerPlayer p, String what) {
        Clan clan = Kin.bookClan(p);
        ClanData.Clan wc = Joining.clanOf(p);
        if (clan == null || wc == null || QuestState.storyStep(p) != QuestState.WARRIOR) return false;
        ServerLevel ow = p.getServer().overworld();
        LifeData life = LifeData.get(ow);
        BookClans.Info info = BookClans.Info.of(clan);
        String leader = life.leader(clan), you = Joining.catName(p);
        WCatEntity leaderCat = Story.catNamed(p, clan, leader);
        ClanPlayerRank rank = wc.members.get(p.getUUID());
        boolean deputy = what.equals("deputy");
        List<String> needs = deputy ? deputyNeeds(p) : leaderNeeds(p);
        List<Line> lines = new ArrayList<>();
        if (life.leaderPlayer(clan) != null || leaderCat == null || rank != (deputy ? ClanPlayerRank.WARRIOR : ClanPlayerRank.DEPUTY)) {
            Ui.subtitle(p, List.of(Line.narrate("That isn't something you can ask for now.")));
            return false;
        }
        if (!needs.isEmpty()) {
            lines.add(Line.say(leader, info.colour, (deputy ? "You serve the Clan well, " + you + ", and one day you may stand beside me. But first you must "
                    : "One day " + info.title + " will be yours to lead, " + you + ". Not yet. First you must ") + String.join(", and ", needs) + ".").speakerEntity(leaderCat));
            Ui.play(p, lines);
            return false;
        }
        if (deputy) {
            UUID other = deputyPlayer(wc, life, clan);
            if (other != null) {
                Ui.play(p, List.of(Line.say(leader, info.colour, "I have a deputy, " + you + ", and they serve the Clan well. Your day may come.").speakerEntity(leaderCat)));
                return false;
            }
            WCatEntity was = Cats.withRank(Cats.atCamp(ow, clan), WCatEntity.Rank.DEPUTY).stream().findFirst().orElse(null);
            if (was != null) {
                lines.add(Line.say(leader, info.colour, Kin.name(was) + " has asked to go back to a warrior's duties, and has spoken for you, " + you + ".").speakerEntity(leaderCat));
            }
            for (Line l : makeDeputy(p, clan, wc, leader)) lines.add(l.speaker() == null ? l : l.speakerEntity(leaderCat));
            lines.add(Line.narrate("You are deputy of " + info.title + ". Serve well, and one day the Clan may be yours to lead."));
            Ui.play(p, lines);
            return true;
        }
        // the old leader steps down to the elders' den, and the deputy leads
        lines.add(Line.say(leader, info.colour, "I have led " + info.title + " for many moons, " + you + ", and my paws are tired. "
                + "I have watched you: the Clan trusts you, and so do I.").speakerEntity(leaderCat));
        lines.add(Line.say(leader, info.colour, "I will go to the elders' den, and " + info.title + " will follow you. Go to " + starPlaceName()
                + ": StarClan will give you a leader's nine lives, and your name.").speakerEntity(leaderCat));
        lead(p, clan, wc, life);
        Roles.setRole(leaderCat, Role.ELDER);
        lines.add(Line.narrate("You lead " + info.title + " now. Your nest is in the leader's den. Travel to " + starPlaceName() + " to receive your nine lives."));
        lines.addAll(Deaths.newDeputy(ow, life, clan, new ArrayList<>(Cats.atCamp(ow, clan)), you));
        Ui.play(p, lines);
        return true;
    }

    /** The leader gives up their place and joins the elders (the Clan is led on the next second, see {@link #tick}). */
    static boolean stepDown(ServerPlayer p) {
        Clan clan = Kin.bookClan(p);
        ClanData.Clan wc = Joining.clanOf(p);
        if (clan == null || wc == null || !leads(p)) return false;
        BookClans.Info info = BookClans.Info.of(clan);
        ClanData.get(p.getServer().overworld()).changeMemberRank(p.getUUID(), wc.clanUUID, ClanPlayerRank.ELDER);
        QuestState.setRoleBefore(p, "warrior");
        Nests.claim(p, clan, DenKind.ELDERS);
        Ui.play(p, List.of(
                Line.say(Joining.catName(p), info.colour, "Cats of " + info.title + ", I have led you as well as I could. It is time for another to lead, and for me to rest."),
                Line.narrate("The Clan calls your name one last time as its leader. Your nest is in the elders' den now.")));
        Ui.push(p);
        return true;
    }

    /** What to aim for next in the Clan, for the journal; null for a cat with no further to rise (an elder, a queen). */
    static String goal(ServerPlayer p) {
        Clan clan = Kin.bookClan(p);
        if (clan == null) return null;
        LifeData life = life(p);
        String leader = life.leader(clan);
        if (leads(p)) {
            if (QuestState.root(p).getBoolean("nineLives")) return "Travel to " + starPlaceName() + " to receive your nine lives and your leader's name.";
            int left = life.lives(clan);
            return "Lead your Clan (" + left + (left == 1 ? " life" : " lives") + " left). Talk to a warrior to name them your deputy, or to give them orders.";
        }
        ClanPlayerRank rank = rank(p);
        if (rank == ClanPlayerRank.DEPUTY) {
            List<String> needs = leaderNeeds(p);
            return "The patrols are yours to send: talk to a warrior to give them orders. " + (needs.isEmpty() ? "You are ready to lead: speak with " + leader + " about the Clan's future."
                    : "To lead " + BookClans.Info.of(clan).title + " one day: " + String.join(", and ", needs) + ".");
        }
        if (rank == ClanPlayerRank.WARRIOR) {
            List<String> needs = deputyNeeds(p);
            return needs.isEmpty() ? "You are ready to be deputy: ask " + leader + " about your place in the Clan."
                    : "To be deputy one day: " + String.join(", and ", needs) + ".";
        }
        return null;
    }

    /** The journey for the nine lives, shown in the journal with which way and how far; null when there is none to make. */
    static JournalView.Quest quest(ServerPlayer p, boolean pending, String clanTitle) {
        Landmark at = starPlace();
        if (!pending || at == null) return null;
        double d = Math.hypot(at.x() - p.getX(), at.z() - p.getZ());
        String where = d <= STAR_REACH ? "you are there" : "about " + Math.max(10, Math.round(d / 10) * 10) + " blocks to the " + Kin.direction(p.getX(), p.getZ(), at.x(), at.z());
        return new JournalView.Quest("ninelives", "Nine lives", "StarClan",
                "A leader of " + clanTitle + " must share tongues with StarClan at " + starPlaceName() + ", and receive nine lives and a leader's name.",
                List.of(new JournalView.Objective("Reach " + starPlaceName() + " (" + where + ")", 0, 1)), true, false);
    }
}
