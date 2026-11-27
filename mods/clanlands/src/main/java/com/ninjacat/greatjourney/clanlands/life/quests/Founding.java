package com.ninjacat.greatjourney.clanlands.life.quests;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import com.ninjacat.greatjourney.clanlands.life.events.EventNews;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.clan.ClanData.ClanPlayerRank;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A Clan of your own, in Free Mode, as a path with steps instead of a command. A loner wins over wild cats who will
 * follow them (Warrior Cats Events' fresh-kill and herbs), finds a home outside the four Clans' borders, and founds
 * the Clan there (Warrior Cats Events' own screen names it and adopts the followers). Then the Clan is built: a camp
 * claimed, six cats, a deputy and a medicine cat named among them; and when it stands, its founder walks to the
 * Moonpool for nine lives and a leader's name, and the four Clans hear of the new Clan at their next Gathering.
 * Asked for after play (2026-11-23): founding a Clan was one command with nothing before or after it.
 *
 * <p>Every step is read from what is true (the cats that follow, the Clan's own records), so a Clan founded straight
 * from Warrior Cats Events' command joins the path where it stands. The journal shows the steps and how far each is.
 */
public final class Founding {
    private Founding() {}

    /** Cats who must follow a loner before they can found a Clan, and cats a Clan needs before StarClan knows it. */
    static final int FOLLOWERS = 3, CLAN_CATS = 6;
    /** A new camp lies at least this far from each of the four Clans' camps. */
    static final double CAMP_APART = 300;
    /** How far round the loner their followers are counted. */
    static final double FOLLOW_REACH = 48;
    /** The founding is offered again at most this often. */
    static final long ASK_AGAIN = 20L * 60 * 5;
    /** What Warrior Cats Events takes to claim a Clan's first land. */
    static final int CAMP_XP = 450;

    // --- the loner

    /** The wild cats this player has won over that are with them now (tame, theirs, in no Clan). */
    static int followers(ServerPlayer p) {
        return p.serverLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(FOLLOW_REACH),
                c -> c.isAlive() && c.isTame() && p.getUUID().equals(c.getOwnerUUID()) && !inClan(c)).size();
    }

    private static boolean inClan(WCatEntity c) {
        return c.getClanUUID() != null && !c.getClanUUID().equals(ClanData.EMPTY_UUID);
    }

    /** Why a camp cannot be founded where the player stands, or null if it can. */
    static String siteProblem(ServerPlayer p) {
        ClanWorld w = ClanWorld.current();
        ServerLevel ow = p.getServer().overworld();
        if (w == null || p.level() != ow) return "This is no place for a camp.";
        String problem = siteProblem(w, p.getX(), p.getZ());
        if (problem == null && marked(ClanData.get(ow), p.getX(), p.getZ())) return "A Clan's scent marks this ground already. Walk a little further out.";
        return problem;
    }

    /**
     * Whether any Clan holds the chunk at x, z. Warrior Cats Events claims a chunk with its neighbours, so a Clan's
     * land reaches a little past the border on the map, and a claim there is refused.
     */
    static boolean marked(ClanData data, double x, double z) {
        ChunkPos at = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        return data.isChunkClaimedByOtherClan(at, ClanData.EMPTY_UUID);
    }

    /** Whether the Clan holds its camp (Warrior Cats Events keeps the core's place after every claim is given up). */
    static boolean hasCamp(ClanData.Clan wc) {
        return wc.coreTerritory != null && wc.claimedTerritory.containsKey(wc.coreTerritory);
    }

    /** What stands in the way of a camp at x, z, or null where one could be founded. */
    static String siteProblem(ClanWorld w, double x, double z) {
        Clan here = Homeland.territoryAt(w, x, z);
        if (here != null) return "This is " + BookClans.Info.of(here).title + " territory. A new Clan needs land no Clan has marked.";
        if (Homeland.heart(w, x, z)) return "This is no place for a camp.";
        for (Clan c : Clan.values()) {
            Landmark camp = Homeland.camp(w, c);
            if (Math.hypot(x - camp.x(), z - camp.z()) < CAMP_APART) {
                return "The " + BookClans.Info.of(c).title + " camp is too near. Their patrols would never let a new Clan settle here.";
            }
        }
        return null;
    }

    /** How far out, and how finely, the map looks for land a Clan could be founded on. */
    private static final int SITE_STEP = 64, SITE_RINGS = 48, SITE_WAYS = 16;

    /** The nearest land to x, z that is no Clan's and far enough from every camp, or null. */
    static double[] siteNear(ClanWorld w, double x, double z) {
        return siteNear(w, x, z, (sx, sz) -> true);
    }

    private static double[] siteNear(ClanWorld w, double x, double z, java.util.function.BiPredicate<Double, Double> open) {
        for (int ring = 0; ring <= SITE_RINGS; ring++) {
            for (int i = 0; i < (ring == 0 ? 1 : SITE_WAYS); i++) {
                double a = i * Math.PI * 2 / SITE_WAYS, sx = x + Math.cos(a) * ring * SITE_STEP, sz = z + Math.sin(a) * ring * SITE_STEP;
                if (siteProblem(w, sx, sz) == null && open.test(sx, sz)) return new double[] {sx, sz};
            }
        }
        return null;
    }

    /** The map's mark for a Clan of the player's own: where it could be founded, then its camp or the Moonpool. */
    static com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark mark(ServerPlayer p, ClanWorld w) {
        if (quest(p) == null || p.level() != p.getServer().overworld()) return null;
        ClanData.Clan wc = Joining.clanOf(p);
        if (wc == null) {
            ClanData data = ClanData.get(p.getServer().overworld());
            double[] at = siteNear(w, p.getX(), p.getZ(), (x, z) -> !marked(data, x, z));
            return at == null ? null : com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark.at("founding", "Land where a Clan could be founded", at[0], at[1]);
        }
        if (stands(wc)) {
            Landmark pool = Ranks.starPlace();
            return pool == null ? null : com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark.at("founding", "Receive your nine lives at " + Ranks.starPlaceName(), pool.x(), pool.z());
        }
        return !hasCamp(wc) ? null : com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark.at("founding", wc.name + "'s camp",
                wc.coreTerritory.getMiddleBlockX(), wc.coreTerritory.getMiddleBlockZ());
    }

    private static boolean freeLoner(ServerPlayer p) {
        return Mode.of(p.getServer()) == Mode.FREE && Journey.stage(p) == Journey.Stage.PLAYING && Joining.clanOf(p) == null;
    }

    /** Whether the player founded the Clan they are in (Warrior Cats Events makes the founder its leader). */
    static boolean founder(ServerPlayer p, ClanData.Clan wc) {
        return wc != null && Kin.bookClan(p) == null && wc.members.get(p.getUUID()) == ClanPlayerRank.LEADER;
    }

    /** Once a second for every player in the overworld ({@link Tracking#tick}). */
    static void tick(ServerPlayer p) {
        if (Journey.stage(p) != Journey.Stage.PLAYING || !p.isAlive() || Dialog.busy(p)) return;
        ClanData.Clan wc = Joining.clanOf(p);
        if (wc != null) {
            if (founder(p, wc)) build(p, wc);
            return;
        }
        if (Mode.of(p.getServer()) != Mode.FREE) return;
        int n = followers(p);
        if (n == 0) return;
        long now = p.level().getGameTime();
        if (Journey.once(p, "found.first")) {
            Ui.subtitle(p, List.of(Line.narrate("A cat follows you now. With " + FOLLOWERS + " at your side you could found a Clan of your own, "
                    + "on land no Clan has marked. Your journal keeps count.")));
            Ui.push(p);
        }
        if (n < FOLLOWERS || siteProblem(p) != null || now - Journey.stamped(p, "found.asked") < ASK_AGAIN) return;
        Journey.stamp(p, "found.asked", now);
        Ui.play(p, List.of(
                Line.narrate(n + " cats look to you now, and no Clan's scent marks this land. This could be a home."),
                Line.narrate("Will you found your Clan here?").withChoices(
                        Choice.of("Found a Clan here", "greatjourney found"),
                        new Choice("Not here", "greatjourney quest bye", 0xAAAAAA))));
    }

    /** "Found a Clan here": checked again, then Warrior Cats Events' own screen names the Clan and adopts the followers. */
    static boolean found(ServerPlayer p) {
        if (!freeLoner(p)) {
            Ui.subtitle(p, List.of(Line.narrate(Joining.clanOf(p) != null ? "You belong to a Clan already." : "Founding a Clan is for a loner in Free Mode.")));
            return false;
        }
        int n = followers(p);
        String problem = n < FOLLOWERS ? "A Clan is more than one cat. " + n + " of " + FOLLOWERS + " cats follow you: win over wild cats with fresh-kill and herbs."
                : siteProblem(p);
        if (problem != null) {
            Ui.subtitle(p, List.of(Line.narrate(problem)));
            return false;
        }
        Dialog.clear(p);
        Journey.once(p, "found.earned");
        p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack().withSuppressedOutput(), "wce clan register");
        return true;
    }

    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("found").executes(c -> found(c.getSource().getPlayerOrException()) ? 1 : 0));
    }

    // --- the founder

    private static boolean hasRank(ClanData.Clan wc, WCatEntity.Rank rank) {
        for (ClanData.ClanCat c : wc.clanCats.values()) if (rank.name().equals(c.catRank)) return true;
        return false;
    }

    /** The Clan stands: a camp, enough cats, a deputy and a medicine cat. */
    private static boolean stands(ClanData.Clan wc) {
        return hasCamp(wc) && wc.clanCats.size() >= CLAN_CATS && hasRank(wc, WCatEntity.Rank.DEPUTY) && hasRank(wc, WCatEntity.Rank.MEDICINE);
    }

    private static boolean named(ServerPlayer p) {
        return QuestState.root(p).getBoolean("ownStar");
    }

    private static void build(ServerPlayer p, ClanData.Clan wc) {
        CompoundTag root = QuestState.root(p);
        if (Journey.once(p, "found.done")) {
            if (!Journey.once(p, "found.earned")) {
                QuestState.addStanding(p, 20);
                Ui.play(p, List.of(
                        Line.narrate("Under Silverpelt, the cats who followed you call the name of their Clan for the first time: " + wc.name + "! " + wc.name + "!"),
                        Line.narrate("A Clan needs a camp, warriors, a deputy and a medicine cat before StarClan will know it. Your journal shows what " + wc.name + " still lacks.")));
            } else {
                // a Clan that stood already (a save from before this path, or one made by Warrior Cats Events' command):
                // what it has is simply noted, with no ceremony for things done long ago
                if (hasCamp(wc)) Journey.once(p, "found.camp");
                if (wc.clanCats.size() >= CLAN_CATS) Journey.once(p, "found.cats");
                if (hasRank(wc, WCatEntity.Rank.DEPUTY)) Journey.once(p, "found.deputy");
                if (hasRank(wc, WCatEntity.Rank.MEDICINE)) Journey.once(p, "found.medicine");
                Ui.subtitle(p, List.of(Line.narrate("Your journal now shows what " + wc.name + " needs before StarClan gives its leader nine lives.")));
            }
            Ui.push(p);
            return;
        }
        // Claiming costs more experience than a new founder has (with no monsters it comes slowly): given once, also to
        // a founder from before this who never could claim
        if (!hasCamp(wc) && !named(p) && Journey.once(p, "found.campxp")) {
            p.giveExperiencePoints(CAMP_XP);
            Ui.subtitle(p, List.of(Line.narrate("StarClan lends you the strength to mark your first border: " + CAMP_XP
                    + " experience, what claiming your camp costs. Stand on clear ground and claim it from the Clan screen.")));
        }
        if (root.getBoolean("ownLifeLost")) {
            root.remove("ownLifeLost");
            int left = root.getInt("ownLives");
            Ui.subtitle(p, List.of(Line.narrate(left > 0 ? "You lie still... then stir. StarClan has sent you back. You have " + left + (left == 1 ? " life" : " lives") + " left."
                    : "Your nine lives are spent. StarClan sends you back all the same: " + wc.name + " has no other leader.")));
            Ui.push(p);
        }
        milestone(p, "found.camp", hasCamp(wc), "Scent marks the borders of " + wc.name + "'s camp. This land is yours.");
        milestone(p, "found.cats", wc.clanCats.size() >= CLAN_CATS, wc.name + " has cats enough to hunt, to patrol and to guard the camp.");
        milestone(p, "found.deputy", hasRank(wc, WCatEntity.Rank.DEPUTY), wc.name + " has a deputy to send out the patrols.");
        milestone(p, "found.medicine", hasRank(wc, WCatEntity.Rank.MEDICINE), wc.name + " has a medicine cat to heal the sick and read StarClan's signs.");
        if (named(p) || !stands(wc)) return;
        Landmark at = Ranks.starPlace();
        ServerLevel ow = p.getServer().overworld();
        if (at == null || p.level() != ow || Math.hypot(p.getX() - at.x(), p.getZ() - at.z()) > Ranks.STAR_REACH) {
            if (Journey.once(p, "found.ready")) {
                Ui.play(p, List.of(Line.narrate(wc.name + " stands: a camp, warriors, a deputy, a medicine cat. One thing is left. Travel to "
                        + Ranks.starPlaceName() + ", where StarClan gives a leader nine lives and a name.")));
                Ui.push(p);
            } else if ((ow.getGameTime() / 20) % 10 == 0) {
                Ui.push(p);                                   // the journal's "which way" keeps up with them
            }
            return;
        }
        String before = Joining.catName(p);
        String star = Ranks.rename(p, "star");
        root.putBoolean("ownStar", true);
        root.putInt("ownLives", LifeData.LEADER_LIVES);
        wc.leaderName = star;
        ClanData.get(ow).setDirty();
        QuestState.addStanding(p, 30);
        p.giveExperiencePoints(200);
        // the four Clans hear of it: each leader tells it at the next Gathering
        for (Clan c : Clan.values()) EventNews.record(c, "A new Clan has made its home beyond our borders: " + wc.name + ", led by " + star + ".");
        Ui.play(p, Ranks.nineLivesScene(before, star, wc.name, List.of()));
        Ui.push(p);
    }

    private static void milestone(ServerPlayer p, String flag, boolean reached, String words) {
        if (!reached || !Journey.once(p, flag)) return;
        QuestState.addStanding(p, 10);
        Ui.subtitle(p, List.of(Line.narrate(words)));
        Ui.push(p);
    }

    /** A founder's death costs one of their nine lives (told when they wake). */
    static void onDeath(ServerPlayer p) {
        CompoundTag root = QuestState.root(p);
        if (!named(p) || !founder(p, Joining.clanOf(p)) || root.getInt("ownLives") <= 0) return;
        root.putInt("ownLives", root.getInt("ownLives") - 1);
        root.putBoolean("ownLifeLost", true);
    }

    // --- the journal

    /** The founding path as the journal shows it, or null for a cat not on it. */
    static JournalView.Quest quest(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        List<JournalView.Objective> obj = new ArrayList<>();
        if (wc == null) {
            if (!freeLoner(p)) return null;
            int n = Math.min(FOLLOWERS, followers(p));
            if (n == 0 && Journey.start(p) != Journey.Start.LONER) return null;
            String problem = siteProblem(p);
            obj.add(new JournalView.Objective("Win over cats who will follow you", n, FOLLOWERS));
            obj.add(new JournalView.Objective("Stand on land no Clan has marked, far from their camps", problem == null ? 1 : 0, 1));
            return new JournalView.Quest("founding", "A Clan of your own", "", "A loner need not join a Clan: they can found one. "
                    + "Wild cats can be won over with fresh-kill and herbs. When " + FOLLOWERS + " follow you, lead them to land outside the four Clans' "
                    + "borders, and found your Clan there." + (problem == null ? "" : " Here: " + problem), obj, true, false);
        }
        if (!founder(p, wc) || named(p)) return null;
        obj.add(new JournalView.Objective("Claim your camp (Clan screen, standing there; " + CAMP_XP + " experience)", hasCamp(wc) ? 1 : 0, 1));
        obj.add(new JournalView.Objective("Gather cats into the Clan", Math.min(CLAN_CATS, wc.clanCats.size()), CLAN_CATS));
        obj.add(new JournalView.Objective("Name a deputy among them", hasRank(wc, WCatEntity.Rank.DEPUTY) ? 1 : 0, 1));
        obj.add(new JournalView.Objective("Name a medicine cat among them", hasRank(wc, WCatEntity.Rank.MEDICINE) ? 1 : 0, 1));
        JournalView.Quest way = Ranks.quest(p, stands(wc), wc.name);
        obj.add(way != null ? way.objectives().get(0) : new JournalView.Objective("Receive your nine lives at " + Ranks.starPlaceName(), 0, 1));
        return new JournalView.Quest("founding", "Build " + wc.name, "StarClan", "A Clan is more than a name. " + wc.name
                + " needs a camp, cats to hunt and patrol, a deputy and a medicine cat (shift-click one of your cats with Whiskers to change its rank). "
                + "To grow your land, claim again from the first unmarked chunk beside it (850 experience each time). "
                + "Then StarClan will give its leader nine lives.", obj, true, false);
    }

    /** One line for the journal on what a founder (or a loner who might be one) aims for; null for anyone else. */
    static String goal(ServerPlayer p, ClanData.Clan wc) {
        if (wc == null) return freeLoner(p) ? "Or win over " + FOLLOWERS + " cats and found a Clan of your own." : null;
        if (!founder(p, wc)) return null;
        if (!named(p)) return stands(wc) ? "Travel to " + Ranks.starPlaceName() + " to receive your nine lives." : "Build " + wc.name + ": your journal shows what it still lacks.";
        int left = QuestState.root(p).getInt("ownLives");
        return "Lead " + wc.name + " (" + left + (left == 1 ? " life" : " lives") + " left): keep your cats fed, your borders marked and your Clan growing.";
    }
}
