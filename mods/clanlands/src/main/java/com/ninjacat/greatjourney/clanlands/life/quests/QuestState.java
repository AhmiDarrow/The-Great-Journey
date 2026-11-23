package com.ninjacat.greatjourney.clanlands.life.quests;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player's quests, duties and Clan standing, kept in their persistent data so they survive deaths, restarts and
 * dimension changes. Every player has their own; nothing here is shared between players.
 */
public final class QuestState {
    private QuestState() {}

    private static final String ROOT = "greatjourney_quests";
    public static final int MAX_DUTIES = 3;

    /**
     * Apprentice path: 0 = not started, 1..6 = steps, 7 = ready for the naming ceremony, 8 = named (a warrior, or a
     * full medicine cat on the medicine cat's path).
     */
    public static final int CEREMONY = 7, WARRIOR = 8;

    /** Which apprentice path a player walks: a warrior's (everyone's, unless they choose otherwise) or a medicine cat's. */
    public enum Path { WARRIOR, MEDICINE }

    static CompoundTag root(ServerPlayer p) {
        CompoundTag persisted = p.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        if (!p.getPersistentData().contains(ServerPlayer.PERSISTED_NBT_TAG)) p.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        CompoundTag mine = persisted.getCompound(ROOT);
        if (!persisted.contains(ROOT)) persisted.put(ROOT, mine);
        return mine;
    }

    // --- the apprentice path

    public static int storyStep(ServerPlayer p) {
        return root(p).getInt("storyStep");
    }

    public static void setStoryStep(ServerPlayer p, int step) {
        root(p).putInt("storyStep", step);
    }

    public static Path path(ServerPlayer p) {
        return root(p).getString("path").equals("medicine") ? Path.MEDICINE : Path.WARRIOR;
    }

    static void setPath(ServerPlayer p, Path path) {
        root(p).putString("path", path == Path.MEDICINE ? "medicine" : "warrior");
    }

    /** Forgets the mentor (a new path gets a new one). */
    static void clearMentor(ServerPlayer p) {
        root(p).remove("mentor");
        root(p).remove("mentorId");
    }

    /** What an elder or a queen goes back to when they return to their duties: "warrior" or "medicine". */
    static String roleBefore(ServerPlayer p) {
        String s = root(p).getString("roleBefore");
        return s.isEmpty() ? "warrior" : s;
    }

    static void setRoleBefore(ServerPlayer p, String role) {
        root(p).putString("roleBefore", role);
    }

    public static String mentorName(ServerPlayer p) {
        return root(p).getString("mentor");
    }

    public static UUID mentorId(ServerPlayer p) {
        return root(p).hasUUID("mentorId") ? root(p).getUUID("mentorId") : null;
    }

    public static void setMentor(ServerPlayer p, String name, UUID id) {
        UUID old = mentorId(p);
        root(p).putString("mentor", name);
        if (id != null) root(p).putUUID("mentorId", id);
        // since when: a cat of the same name who died before this is not this mentor (Mentors.lost)
        if (id == null || !id.equals(old)) root(p).putLong("mentorSince", p.level().getGameTime());
        if (p.getServer() != null) Mentors.mark(p, old, id);    // the cat knows it has a pupil (Mentors)
    }

    public static Task story(ServerPlayer p) {
        return root(p).contains("story") ? Task.load(root(p).getCompound("story")) : null;
    }

    public static void setStory(ServerPlayer p, Task t) {
        if (t == null) root(p).remove("story"); else root(p).put("story", t.save());
    }

    /** The warrior-name endings offered at the ceremony, so only those can be chosen. */
    public static List<String> ceremonyChoices(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        for (Tag t : root(p).getList("ceremonyChoices", Tag.TAG_STRING)) out.add(t.getAsString());
        return out;
    }

    public static void setCeremonyChoices(ServerPlayer p, List<String> choices) {
        ListTag l = new ListTag();
        for (String s : choices) l.add(StringTag.valueOf(s));
        root(p).put("ceremonyChoices", l);
    }

    public static String warriorName(ServerPlayer p) {
        return root(p).getString("warriorName");
    }

    public static void setWarriorName(ServerPlayer p, String name) {
        root(p).putString("warriorName", name);
    }

    // --- the Campaign's errand (one at a time; see Errands)

    public static Task errand(ServerPlayer p) {
        return root(p).contains("errand") ? Task.load(root(p).getCompound("errand")) : null;
    }

    static void setErrand(ServerPlayer p, Task t) {
        if (t == null) root(p).remove("errand"); else root(p).put("errand", t.save());
    }

    // --- the lesson a grown cat is giving their own apprentice (one at a time; see Pupils)

    public static Task lesson(ServerPlayer p) {
        return root(p).contains("lesson") ? Task.load(root(p).getCompound("lesson")) : null;
    }

    static void setLesson(ServerPlayer p, Task t) {
        if (t == null) root(p).remove("lesson"); else root(p).put("lesson", t.save());
    }

    // --- duties

    public static List<Task> duties(ServerPlayer p) {
        List<Task> out = new ArrayList<>();
        for (Tag t : root(p).getList("duties", Tag.TAG_COMPOUND)) {
            Task task = Task.load((CompoundTag) t);
            if (task != null) out.add(task);
        }
        return out;
    }

    public static void setDuties(ServerPlayer p, List<Task> duties) {
        ListTag l = new ListTag();
        for (Task t : duties) l.add(t.save());
        root(p).put("duties", l);
    }

    /** Every active task: the apprentice-path step first, then duties. */
    public static List<Task> active(ServerPlayer p) {
        List<Task> out = new ArrayList<>();
        Task s = story(p);
        if (s != null) out.add(s);
        out.addAll(duties(p));
        Task e = errand(p);
        if (e != null) out.add(e);
        Task l = lesson(p);
        if (l != null) out.add(l);
        return out;
    }

    /** Saves a changed task back where it came from (matched by kind and giver). */
    public static void save(ServerPlayer p, Task changed) {
        if (changed.story) {
            setStory(p, changed);
            return;
        }
        if (changed.kind == Task.Kind.ERRAND) {
            setErrand(p, changed);
            return;
        }
        if (changed.kind == Task.Kind.LESSON) {
            setLesson(p, changed);
            return;
        }
        List<Task> d = duties(p);
        for (int i = 0; i < d.size(); i++) {
            if (same(d.get(i), changed)) d.set(i, changed);
        }
        setDuties(p, d);
    }

    static boolean same(Task a, Task b) {
        return a.kind == b.kind && a.story == b.story && java.util.Objects.equals(a.giverId, b.giverId);
    }

    /** Whether the player already finished a duty for this cat today (each cat asks once a day). */
    public static boolean completedToday(ServerPlayer p, UUID giver, long day) {
        return root(p).getCompound("doneDay").getLong(giver.toString()) == day + 1;
    }

    public static void markCompletedToday(ServerPlayer p, UUID giver, long day) {
        CompoundTag d = root(p).getCompound("doneDay");
        // only today's matter: forget older days so the list doesn't grow with every cat ever helped
        for (String k : java.util.List.copyOf(d.getAllKeys())) if (d.getLong(k) < day + 1) d.remove(k);
        d.putLong(giver.toString(), day + 1);
        root(p).put("doneDay", d);
    }

    // --- standing, counts, places

    public static int standing(ServerPlayer p) {
        return root(p).getInt("standing");
    }

    public static void addStanding(ServerPlayer p, int n) {
        root(p).putInt("standing", standing(p) + n);
    }

    public static int completed(ServerPlayer p, Task.Kind kind) {
        return root(p).getCompound("completed").getInt(kind.name());
    }

    public static void countCompleted(ServerPlayer p, Task.Kind kind) {
        CompoundTag c = root(p).getCompound("completed");
        c.putInt(kind.name(), c.getInt(kind.name()) + 1);
        root(p).put("completed", c);
    }

    public static Set<String> places(ServerPlayer p) {
        Set<String> out = new LinkedHashSet<>();
        for (Tag t : root(p).getList("places", Tag.TAG_STRING)) out.add(t.getAsString());
        return out;
    }

    /** Marks a place found; true if it was new. */
    public static boolean addPlace(ServerPlayer p, String id) {
        ListTag l = root(p).getList("places", Tag.TAG_STRING);
        for (Tag t : l) if (t.getAsString().equals(id)) return false;
        l.add(StringTag.valueOf(id));
        root(p).put("places", l);
        return true;
    }

    /** Forgets everything (admin reset). */
    public static void reset(ServerPlayer p) {
        CompoundTag persisted = p.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        persisted.remove(ROOT);
    }
}
