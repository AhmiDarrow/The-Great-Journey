package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.snowteb.warriorcats_events.attachments.ModAttachments;
import net.snowteb.warriorcats_events.attachments.WCEPlayerData;
import net.snowteb.warriorcats_events.attachments.WCEPlayerDataUtils;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A player who comes to their Clan as a kit (their Warrior Cats Events cat is a kit) lives in the nursery for a short
 * while before they are old enough to train. Joining used to make every newcomer an apprentice at once, kit or not,
 * and Warrior Cats Events never grows a player's cat on its own, so a kit stayed kit-sized for good while a mentor
 * sent them out on patrol. Now a kit joins as a kit, sleeps in the nursery, takes no apprentice steps, and after
 * {@link #KIT_TICKS} of play (in camp, so the Clan can see it) the leader holds the apprentice ceremony: the cat
 * grows to an apprentice, the name ends in "-paw", and the training begins. The warrior (or medicine cat) naming
 * grows the cat to its full size ({@link #grow}).
 */
public final class Kits {
    private Kits() {}

    /** How long a player is a kit: eight minutes of play (game ticks, so a faster or slower day does not change it). */
    public static final long KIT_TICKS = 20L * 60 * 8;
    private static final String SINCE = "kit.since", TOLD = "kit.told";

    private static WCEPlayerData data(ServerPlayer p) {
        return p.getData(ModAttachments.PLAYER_WCE_DATA.get());
    }

    /** Whether the player's own cat is a kit (as Warrior Cats Events draws it). */
    public static boolean kitAged(ServerPlayer p) {
        return data(p).getMorphAge() == WCEPlayerData.Age.KIT;
    }

    private static ClanData.ClanPlayerRank rank(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        return wc == null ? null : wc.members.get(p.getUUID());
    }

    /** Whether the player is one of their Clan's kits now. */
    public static boolean isKit(ServerPlayer p) {
        return rank(p) == ClanData.ClanPlayerRank.KIT;
    }

    /** A kit has joined (or been found to be one): the nursery, and the clock until their apprentice ceremony. */
    static void begin(ServerPlayer p) {
        Journey.stamp(p, SINCE, p.level().getGameTime());
        Journey.stamp(p, TOLD, 0);
    }

    /** The lines a kit hears on joining, in place of the apprentice ceremony. */
    static List<Line> welcome(String name, Info clan, String leader, net.minecraft.world.entity.Entity leaderCat) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(leader, clan.colour, "Cats of " + clan.title + ", a new kit has come to our Clan. Welcome, " + name + ".").speakerEntity(leaderCat));
        lines.add(Line.say(leader, clan.colour, "You are too young to train yet. Stay in the nursery with the queens, and grow strong.").speakerEntity(leaderCat));
        lines.add(Line.narrate("A warm nest of moss waits for you in the nursery. Explore the camp, play, and listen to the elders' stories: "
                + "in a few minutes you will be old enough to become an apprentice."));
        return lines;
    }

    /** How long until the kit is old enough, in whole minutes (at least 1). */
    public static long minutesLeftOf(ServerPlayer p) {
        return minutesLeft(p);
    }

    static long minutesLeft(ServerPlayer p) {
        long left = KIT_TICKS - (p.level().getGameTime() - Journey.stamped(p, SINCE));
        return Math.max(1, (left + 20 * 60 - 1) / (20 * 60));
    }

    /**
     * Once a second for a Clan player: a kit old enough, in camp and not busy, is made an apprentice. An apprentice
     * whose cat is still a kit is put right: a kit who has not begun training goes back to the nursery for the rest
     * of its kithood; one already training simply grows.
     */
    public static void tick(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        ClanData.ClanPlayerRank rank = rank(p);
        if (wc == null || rank == null || Journey.stage(p) != Journey.Stage.PLAYING) return;
        if (rank == ClanData.ClanPlayerRank.APPRENTICE && kitAged(p)) {
            if (com.ninjacat.greatjourney.clanlands.life.quests.QuestState.storyStep(p) == 0 && Journey.stamped(p, SINCE) == 0) {
                ClanData.get(p.serverLevel().getServer().overworld()).changeMemberRank(p.getUUID(), wc.clanUUID, ClanData.ClanPlayerRank.KIT);
                Clan c = clanOf(p);
                if (c != null) Nests.claim(p, c, DenKind.NURSERY);
                begin(p);
                Dialog.subtitle(p, Line.narrate("You are still a kit, too young to train. Stay near the nursery: in a few minutes you will be made an apprentice."));
            } else {
                grow(p, WCEPlayerData.Age.APPRENTICE);
            }
            return;
        }
        // a medicine cat's apprentice whose cat is still drawn as a kit simply grows
        if (rank == ClanData.ClanPlayerRank.MEDICINEAPP && kitAged(p)) grow(p, WCEPlayerData.Age.APPRENTICE);
        if (rank != ClanData.ClanPlayerRank.KIT) return;
        long since = Journey.stamped(p, SINCE);
        long now = p.level().getGameTime();
        if (since == 0) {
            begin(p);
            return;
        }
        if (now - since < KIT_TICKS || Dialog.busy(p)) return;
        Clan c = clanOf(p);
        ClanWorld w = ClanWorld.current();
        if (c == null || w == null) return;
        Landmark camp = Homeland.camp(w, c);
        boolean inCamp = p.level() == p.serverLevel().getServer().overworld()
                && Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) < camp.radius() + 20;
        if (!inCamp) {
            if (now - Journey.stamped(p, TOLD) > 20L * 60) {
                Journey.stamp(p, TOLD, now);
                Dialog.subtitle(p, Line.narrate("You are old enough to be an apprentice now! Go back to camp: the leader is waiting to hold your ceremony."));
            }
            return;
        }
        apprentice(p, wc, c);
    }

    /** The apprentice ceremony for a kit grown old enough: a "-paw" name, the apprentices' den, and the training. */
    static void apprentice(ServerPlayer p, ClanData.Clan wc, Clan c) {
        ServerLevel ow = p.serverLevel().getServer().overworld();
        ClanData.get(ow).changeMemberRank(p.getUUID(), wc.clanUUID, ClanData.ClanPlayerRank.APPRENTICE);
        WCEPlayerData d = data(p);
        String suffix = d.getSufix() == null ? "" : d.getSufix().trim().toLowerCase(Locale.ROOT);
        if (suffix.isEmpty() || suffix.equals("kit")) {
            String prefix = com.ninjacat.greatjourney.clanlands.life.quests.Paths.namePrefix(p);
            d.setPrefix(prefix);
            d.setSufix("paw");
            d.setUseSufixes(true);
            d.setMorphName(prefix + "paw");
        }
        grow(p, WCEPlayerData.Age.APPRENTICE);
        Journey.stamp(p, SINCE, 0);
        com.ninjacat.greatjourney.clanlands.life.quests.Paths.joined(p);
        boolean nest = Nests.claim(p, c, DenKind.APPRENTICES) != null;
        Info info = Info.of(c);
        LifeData life = LifeData.get(ow);
        String leader = life.leader(c);
        WCatEntity leaderCat = null;
        Landmark camp = Homeland.camp(ClanWorld.current(), c);
        for (WCatEntity cat : ow.getEntitiesOfClass(WCatEntity.class, new net.minecraft.world.phys.AABB(camp.x() - 80, -64, camp.z() - 80, camp.x() + 80, 320, camp.z() + 80),
                x -> wc.clanUUID.equals(x.getClanUUID()) && x.getCustomName() != null)) {
            if (cat.getCustomName().getString().replace(" ♂", "").replace(" ♀", "").trim().equals(leader)) leaderCat = cat;
        }
        String name = Joining.catName(p);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(leader, info.colour, "Let all cats old enough to catch their own prey gather beneath the Highrock for a Clan meeting!").speakerEntity(leaderCat));
        lines.add(Line.say(leader, info.colour, "This kit has reached the age to become an apprentice. From this day on, until they have earned their warrior name, "
                + "this apprentice will be known as " + name + ".").speakerEntity(leaderCat));
        lines.add(Line.say(leader, info.colour, "A mentor will find you in camp, " + name + ". Learn our ways, and feed the Clan before yourself.").speakerEntity(leaderCat));
        lines.add(Line.narrate("The Clan calls your new name: " + name + "! " + name + "!"));
        if (nest) lines.add(Line.narrate("Your nest moves from the nursery to the apprentices' den."));
        lines.add(com.ninjacat.greatjourney.clanlands.life.quests.Paths.ceremonyChoice());
        Dialog.play(p, lines);
    }

    /** Grows the player's own cat to this age (never smaller), and redraws it. */
    public static void grow(ServerPlayer p, WCEPlayerData.Age to) {
        WCEPlayerData d = data(p);
        WCEPlayerData.Age now = d.getMorphAge();
        if (now != null && now.ordinal() >= to.ordinal()) return;
        d.setMorphAge(to);
        if (tocraft.walkers.api.PlayerShape.getCurrentShape(p) != null) {
            tocraft.walkers.api.PlayerShape.updateShapes(p, WCEPlayerDataUtils.createShape(p, p.level()));
        }
    }

    private static Clan clanOf(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        if (wc == null) return null;
        return LifeData.get(p.serverLevel().getServer().overworld()).clanOf(wc.clanUUID);
    }
}
