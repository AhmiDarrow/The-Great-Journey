package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Starts;
import com.ninjacat.greatjourney.clanlands.life.gathering.Gatherings;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapSpot;
import com.ninjacat.greatjourney.clanlands.life.events.ClanEvents;
import com.ninjacat.greatjourney.clanlands.life.events.Skirmish;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.snowteb.warriorcats_events.clan.ClanData;

/**
 * Every quest's place on the Clan map, and which quest the player follows. Built beside the journal
 * ({@link QuestLog#view}) from the same quests, under the same ids: a task's places still to reach (a patrol's points
 * numbered in the order it lists them), else whoever it goes back to; a camp being looked for; the walk for nine
 * lives; where a loner could found a Clan; tonight's meeting under the moon. The quest followed is drawn as the gold
 * star: the one the player chose ("Show on map"), for as long as it has a place, else the first in the list, which
 * is the story's step.
 */
public final class QuestMarks {
    private QuestMarks() {}

    private static final String FOLLOWED = "followed";

    /** The id the journal and the map share for a task. */
    static String id(Task t) {
        return t.kind.name().toLowerCase(java.util.Locale.ROOT) + (t.giverId == null ? "" : ":" + t.giverId);
    }

    /** The marks for this player, the followed quest's flagged. {@code first} (or null) is the Campaign chapter's. */
    public static List<MapMark> of(ServerPlayer p, MapMark first) {
        List<MapMark> all = new ArrayList<>();
        if (first != null) all.add(first);
        ClanWorld w = ClanWorld.current();
        if (w != null) all.addAll(places(p, w));
        String followed = followed(p, all);
        List<MapMark> out = new ArrayList<>(all.size());
        for (MapMark m : all) out.add(m.follow(m.quest().equals(followed)));
        return out;
    }

    /** The quest followed among these marks: the player's choice while it is there, else the first. */
    static String followed(ServerPlayer p, List<MapMark> marks) {
        String chosen = QuestState.root(p).getString(FOLLOWED);
        for (MapMark m : marks) if (m.quest().equals(chosen)) return chosen;
        return marks.isEmpty() ? "" : marks.get(0).quest();
    }

    /** "Show on map": follow this quest from now on. */
    static boolean follow(ServerPlayer p, String quest) {
        if (quest.isBlank() || quest.length() > 96) return false;
        QuestState.root(p).putString(FOLLOWED, quest);
        Ui.push(p);
        return true;
    }

    private static List<MapMark> places(ServerPlayer p, ClanWorld w) {
        List<MapMark> story = new ArrayList<>(), other = new ArrayList<>();
        for (Task t : QuestState.active(p)) (t.story ? story : other).addAll(of(p, w, t));
        List<MapMark> out = new ArrayList<>(story);
        Starts.seekPlace(p).ifPresent(at -> out.add(MapMark.at("seek:" + at.key(), "Find the " + at.title() + " camp", at.x(), at.z())));
        Clan clan = Kin.bookClan(p);
        if (clan != null && Ranks.leads(p) && QuestState.root(p).getBoolean("nineLives")) {
            Landmark pool = Ranks.starPlace();
            if (pool != null) out.add(MapMark.at("ninelives", "Receive your nine lives at " + Ranks.starPlaceName(), pool.x(), pool.z()));
        }
        out.addAll(other);
        MapMark found = Founding.mark(p, w);
        if (found != null) out.add(found);
        out.addAll(Gatherings.marks(p));
        return out;
    }

    /**
     * What else is on the map just now: the cats of the player's quests, while they are loaded (their apprentice, their
     * mentor while they train, the patrol walking with them, trespassers they have come upon, a friend they have spoken
     * with), and where their Clan's latest event happened.
     */
    public static List<MapSpot> spots(ServerPlayer p) {
        List<MapSpot> out = new ArrayList<>();
        ServerLevel ow = p.getServer().overworld();
        if (p.level() != ow) return out;
        Set<UUID> seen = new HashSet<>();
        cat(out, seen, ow, Pupils.pupilId(p), MapSpot.CAT, "Your apprentice, %s");
        int step = QuestState.storyStep(p);
        if (step > 0 && step < QuestState.WARRIOR) cat(out, seen, ow, QuestState.mentorId(p), MapSpot.CAT, "Your mentor, %s");
        for (UUID id : Patrols.walkers(p)) cat(out, seen, ow, id, MapSpot.CAT, "%s, on your patrol");
        for (UUID id : Skirmish.met(p)) cat(out, seen, ow, id, MapSpot.STRANGER, "%s, on your side of the border");
        cat(out, seen, ow, Friendship.met(p), MapSpot.FRIEND, "%s, across the border");
        ClanData.Clan wc = Joining.clanOf(p);
        ClanEvents.Latest news = wc == null ? null : ClanEvents.latest(wc.clanUUID, ow.getGameTime());
        if (news != null) out.add(new MapSpot(MapSpot.EVENT, "Latest news: " + news.words(), news.x(), news.z()));
        return out;
    }

    private static void cat(List<MapSpot> out, Set<UUID> seen, ServerLevel ow, UUID id, int kind, String words) {
        if (id == null || !(ow.getEntity(id) instanceof WCatEntity c) || !c.isAlive() || !seen.add(id)) return;
        out.add(new MapSpot(kind, String.format(words, Kin.name(c)), c.getX(), c.getZ()));
    }

    /** A task's places still to reach; with none, whoever it goes back to. */
    static List<MapMark> of(ServerPlayer p, ClanWorld w, Task t) {
        String id = id(t);
        List<MapMark> out = new ArrayList<>();
        int points = 0;
        for (Task.Objective o : t.objectives) if (place(o)) points++;
        int n = 0;
        for (Task.Objective o : t.objectives) {
            if (!place(o)) continue;
            n++;
            if (!o.done()) out.add(new MapMark(id, t.title + ": " + o.text, o.x, o.z, points > 1 ? n : 0, false));
        }
        if (!out.isEmpty()) return out;
        boolean ready = Talk.hasDeliveries(p, t);
        if (t.kind == Task.Kind.ERRAND) {                           // it finishes by itself, where it says
            CompoundTag root = QuestState.root(p);
            if (root.contains("errandAt")) {
                CompoundTag a = root.getCompound("errandAt");
                out.add(MapMark.at(id, t.title, a.getDouble("x"), a.getDouble("z")));
            }
            return out;
        }
        double[] at = giver(p, w, t.giverId);
        if (at != null) out.add(MapMark.at(id, ready ? "Take it back to " + t.giverName : t.title + " (back to " + t.giverName + " when done)", at[0], at[1]));
        return out;
    }

    private static boolean place(Task.Objective o) {
        return o.type == Task.Objective.Type.VISIT || o.type == Task.Objective.Type.CLIMB;
    }

    /** Where a task's giver is: the cat, if loaded, else the player's camp. */
    private static double[] giver(ServerPlayer p, ClanWorld w, UUID id) {
        Entity e = id == null ? null : p.getServer().overworld().getEntity(id);
        if (e != null) return new double[] {e.getX(), e.getZ()};
        Clan clan = Kin.bookClan(p);
        if (clan != null) {
            Landmark camp = Homeland.camp(w, clan);
            return new double[] {camp.x(), camp.z()};
        }
        ClanData.Clan wc = Joining.clanOf(p);
        if (wc == null || wc.coreTerritory == null) return null;          // a loner's favour: its giver wanders
        return new double[] {wc.coreTerritory.getMiddleBlockX(), wc.coreTerritory.getMiddleBlockZ()};
    }

    /** How the journal words a task's reward. */
    static String reward(Task t) {
        List<String> parts = new ArrayList<>();
        if (t.standing > 0) parts.add("+" + t.standing + " standing");
        if (t.xp > 0) parts.add(t.xp + " experience");
        if (t.rewardCount > 0 && !t.rewardItem.isEmpty()) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(t.rewardItem));
            if (item != net.minecraft.world.item.Items.AIR) {
                parts.add(t.rewardCount + " × " + new net.minecraft.world.item.ItemStack(item).getHoverName().getString());
            }
        }
        return String.join("  ·  ", parts);
    }

    /** What the journal says once a task has nothing left to do but be finished, or "". */
    static String ready(ServerPlayer p, Task t) {
        if (!Talk.hasDeliveries(p, t)) return "";
        if (t.kind == Task.Kind.ERRAND) return "Ready: it finishes where it ends";
        return "Ready: take it back to " + t.giverName;
    }
}
