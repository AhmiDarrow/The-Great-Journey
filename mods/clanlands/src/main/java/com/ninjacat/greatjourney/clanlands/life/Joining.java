package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.attachments.ModAttachments;
import net.snowteb.warriorcats_events.attachments.WCEPlayerData;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** Invitations to join a Clan, and the apprentice ceremony that follows. */
public final class Joining {
    private Joining() {}

    private static final long OFFER_TICKS = 20L * 60 * 10;

    /** The Clan the player belongs to in Warrior Cats Events, or null for loners and kittypets. */
    public static ClanData.Clan clanOf(ServerPlayer p) {
        WCEPlayerData d = p.getData(ModAttachments.PLAYER_WCE_DATA.get());
        UUID id = d.getCurrentClanUUID();
        return id == null ? null : ClanData.get(p.serverLevel().getServer().overworld()).getClan(id);
    }

    /** What the player is called as a cat (their Warrior Cats Events name), falling back to their own name. */
    public static String catName(ServerPlayer p) {
        String n = p.getData(ModAttachments.PLAYER_WCE_DATA.get()).getMorphName();
        // Warrior Cats Events stores "None" until the cat is named
        return n == null || n.isBlank() || n.equalsIgnoreCase("none") ? p.getGameProfile().getName() : n;
    }

    /** A Clan cat asks the player to join; the answer is a click on [Join ...] or [Not yet]. */
    public static void offer(ServerPlayer p, Info clan, String speaker, List<Line> lead) {
        Journey.offer(p, clan.key(), p.level().getGameTime() + OFFER_TICKS);
        List<Line> lines = new ArrayList<>(lead);
        lines.add(Line.say(speaker, clan.colour, "Will you join " + clan.title + "?").withChoices(
                Choice.of("Join " + clan.title, "greatjourney join " + clan.key()),
                new Choice("Not yet", "greatjourney notyet", 0xAAAAAA)));
        Dialog.play(p, lines);
    }

    /** Joins the player to a Clan as an apprentice (members can command Clan cats) and holds the ceremony. */
    public static boolean join(ServerPlayer p, Info clan) {
        ServerLevel ow = p.serverLevel().getServer().overworld();
        LifeData life = LifeData.get(ow);
        UUID id = life.clanId(clan.clan);
        ClanData data = ClanData.get(ow);
        if (id == null || data.getClan(id) == null) {
            p.sendSystemMessage(Component.literal(clan.title + " does not live in this world."));
            return false;
        }
        ClanData.Clan current = clanOf(p);
        if (current != null) {
            p.sendSystemMessage(Component.literal("You already belong to " + current.name + "."));
            return false;
        }
        // a kit (their cat is a kit) is too young to train: the nursery first, the apprentice ceremony later (Kits)
        boolean kit = Kits.kitAged(p);
        data.addMember(p, id, kit ? ClanData.ClanPlayerRank.KIT : ClanData.ClanPlayerRank.APPRENTICE);
        com.ninjacat.greatjourney.clanlands.life.quests.Paths.joined(p);
        data.changeMemberPermissions(p.getUUID(), id, ClanData.ClanPermissions.MEMBER);
        Journey.clearOffer(p);
        Journey.setStage(p, Journey.Stage.PLAYING);
        if (kit) {
            Kits.begin(p);
            boolean nursery = Nests.claim(p, clan.clan, com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind.NURSERY) != null;
            List<Line> lines = Kits.welcome(catName(p), clan, life.leader(clan.clan), catNamed(p, clan, id, life.leader(clan.clan)));
            if (!nursery) lines.remove(lines.size() - 1);
            Dialog.play(p, lines);
            return true;
        }
        // a nest of their own in the apprentices' den, where they wake from now on (each player their own)
        boolean nest = Nests.claim(p, clan.clan, com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind.APPRENTICES) != null;
        WCatEntity mentor = mentorFor(p, clan, id);
        String mentorName = mentor == null ? null : plainName(mentor);
        // each speaker hands the window their own cat: the camp's cats may not have reached the client yet, and a
        // lookup by name from the player's side only reaches so far
        ceremony(p, clan, life.leader(clan.clan), catNamed(p, clan, id, life.leader(clan.clan)), mentorName, mentor, nest);
        return true;
    }

    private static String plainName(WCatEntity c) {
        return c.getCustomName().getString().replace(" ♂", "").replace(" ♀", "");
    }

    private static WCatEntity mentorFor(ServerPlayer p, Info clan, UUID clanId) {
        List<WCatEntity> warriors = campCats(p, clan, clanId, c -> c.getRank() == WCatEntity.Rank.WARRIOR);
        if (warriors.isEmpty()) return null;
        return warriors.get(p.getRandom().nextInt(warriors.size()));
    }

    /** The Clan's named cats in or near its camp that pass {@code test}. */
    private static List<WCatEntity> campCats(ServerPlayer p, Info clan, UUID clanId, java.util.function.Predicate<WCatEntity> test) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return List.of();
        Landmark camp = Homeland.camp(w, clan.clan);
        ServerLevel ow = p.serverLevel().getServer().overworld();
        return ow.getEntitiesOfClass(WCatEntity.class,
                new AABB(camp.x() - 80, -64, camp.z() - 80, camp.x() + 80, 320, camp.z() + 80),
                c -> clanId.equals(c.getClanUUID()) && c.getCustomName() != null && test.test(c));
    }

    /** The Clan's cat of that name, or null if they are not in the loaded part of the camp. */
    private static WCatEntity catNamed(ServerPlayer p, Info clan, UUID clanId, String name) {
        var found = campCats(p, clan, clanId, c -> plainName(c).equals(name));
        return found.isEmpty() ? null : found.get(0);
    }

    private static void ceremony(ServerPlayer p, Info clan, String leader, WCatEntity leaderCat, String mentor, WCatEntity mentorCat, boolean nest) {
        List<Line> lines = new ArrayList<>(ceremonyLines(catName(p), clan, leader, leaderCat, mentor, mentorCat));
        if (nest) lines.add(Line.narrate("A moss nest of your own waits in the apprentices' den. You will wake there."));
        lines.add(com.ninjacat.greatjourney.clanlands.life.quests.Paths.ceremonyChoice());   // a warrior's path, or a medicine cat's
        Dialog.play(p, lines);
    }

    /** The joining ceremony; the leader's and the mentor's lines carry their cats (null for none) for the portrait. */
    public static List<Line> ceremonyLines(String name, Info clan, String leader, net.minecraft.world.entity.Entity leaderCat,
            String mentor, net.minecraft.world.entity.Entity mentorCat) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(leader, clan.colour, "Cats of " + clan.title + ", gather round! A new cat joins our Clan today.").speakerEntity(leaderCat));
        lines.add(Line.say(leader, clan.colour, name + ", from this day on you are an apprentice of " + clan.title
                + ". Learn our ways, guard our borders and feed the Clan before yourself.").speakerEntity(leaderCat));
        if (mentor != null) {
            lines.add(Line.say(leader, clan.colour, mentor + ", you are ready for an apprentice. Teach " + name + " all you know.").speakerEntity(leaderCat));
            lines.add(Line.say(mentor, clan.colour, "Stay close, " + name + ". Tomorrow we walk the borders.").speakerEntity(mentorCat));
        }
        lines.add(Line.narrate("The Clan calls your name: " + name + "! " + name + "!"));
        lines.add(Line.narrate("You can now ask " + clan.title + " cats to follow or stay by shift-clicking them."));
        return lines;
    }
}
