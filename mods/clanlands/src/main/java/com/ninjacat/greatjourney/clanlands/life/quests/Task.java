package com.ninjacat.greatjourney.clanlands.life.quests;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * One thing a Clan cat has asked of the player: a duty (optional, repeatable) or a step of the apprentice path. It
 * has a giver (the cat to report back to), a list of objectives, and a reward. Saved in the player's persistent data.
 */
public final class Task {
    /** What kind of duty this is, so a giver can be asked again and completed counts can be kept. */
    public enum Kind {
        HERBS, HUNT, FISH, BORDER_PATROL, MOSS, FEED_ELDER, FEED_QUEEN, MOSS_BALL, CLIMB, STALK,
        STORY_TERRITORY, STORY_HUNT, STORY_HERBS, STORY_PATROL, STORY_PLACES, STORY_ASSESSMENT,
        /** The medicine cat's path (its first step is STORY_TERRITORY, its fifth STORY_PLACES). */
        STORY_MED_HERBS, STORY_MED_REMEDIES, STORY_MED_ELDERS, STORY_MED_TEST,
        /** A medicine cat's duties: cure the sick, restock the herb store, ease the elders. */
        TEND, HERB_STORE, ELDER_HERBS,
        /** A task the Campaign's story gives (one at a time, see {@link Errands}). */
        ERRAND,
        /** A lesson a grown cat gives their own apprentice (one at a time, see {@link Pupils}). */
        LESSON
    }

    public final Kind kind;
    public final String title, description, giverName;
    public final UUID giverId;
    public final boolean story;
    public final List<Objective> objectives;
    public final int standing, xp;
    /** Item reward: "namespace:id" and count, or "" for none. */
    public final String rewardItem;
    public final int rewardCount;

    public Task(Kind kind, String title, String description, String giverName, UUID giverId, boolean story,
                List<Objective> objectives, int standing, int xp, String rewardItem, int rewardCount) {
        this.kind = kind;
        this.title = title;
        this.description = description;
        this.giverName = giverName;
        this.giverId = giverId;
        this.story = story;
        this.objectives = objectives;
        this.standing = standing;
        this.xp = xp;
        this.rewardItem = rewardItem;
        this.rewardCount = rewardCount;
    }

    /** True when every objective that is not a hand-in is done (hand-ins are checked against the inventory on return). */
    public boolean readyToHandIn() {
        for (Objective o : objectives) if (!o.done()) return false;
        return true;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind.name());
        t.putString("title", title);
        t.putString("desc", description);
        t.putString("giver", giverName);
        if (giverId != null) t.putUUID("giverId", giverId);
        t.putBoolean("story", story);
        t.putInt("standing", standing);
        t.putInt("xp", xp);
        t.putString("reward", rewardItem);
        t.putInt("rewardCount", rewardCount);
        ListTag list = new ListTag();
        for (Objective o : objectives) list.add(o.save());
        t.put("objectives", list);
        return t;
    }

    public static Task load(CompoundTag t) {
        List<Objective> objectives = new ArrayList<>();
        Kind kind;
        try {
            for (Tag o : t.getList("objectives", Tag.TAG_COMPOUND)) objectives.add(Objective.load((CompoundTag) o));
            kind = Kind.valueOf(t.getString("kind"));
        } catch (IllegalArgumentException e) {
            return null;                          // a task from a newer or broken save: dropped, not a crash every second
        }
        return new Task(kind, t.getString("title"), t.getString("desc"), t.getString("giver"),
                t.hasUUID("giverId") ? t.getUUID("giverId") : null, t.getBoolean("story"), objectives,
                t.getInt("standing"), t.getInt("xp"), t.getString("reward"), t.getInt("rewardCount"));
    }

    /**
     * One step: visit a place, catch prey, bring items, climb high, stalk. {@code key} names what counts (an item or
     * tag for hand-ins, a prey set for catches); x/z/radius locate visits; {@code y} is a height for climbs.
     */
    public static final class Objective {
        public enum Type { VISIT, CATCH, BRING, CLIMB, STALK, CATCH_IN_TIME, CURE }

        public final Type type;
        public final String text, key;
        public final int goal;
        public final double x, z, radius;
        public final int y;
        public int progress;
        /** For CATCH_IN_TIME: game time the timer started (0 until the first catch). */
        public long started;

        public Objective(Type type, String text, String key, int goal, double x, double z, double radius, int y) {
            this.type = type;
            this.text = text;
            this.key = key;
            this.goal = goal;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.y = y;
        }

        public static Objective visit(String text, double x, double z, double radius) {
            return new Objective(Type.VISIT, text, "", 1, x, z, radius, 0);
        }

        public static Objective catchPrey(String text, String preySet, int goal) {
            return new Objective(Type.CATCH, text, preySet, goal, 0, 0, 0, 0);
        }

        public static Objective bring(String text, String itemOrTag, int goal) {
            return new Objective(Type.BRING, text, itemOrTag, goal, 0, 0, 0, 0);
        }

        public static Objective climb(String text, double x, double z, double radius, int y) {
            return new Objective(Type.CLIMB, text, "", 1, x, z, radius, y);
        }

        public static Objective stalk(String text, String preySet, int goal) {
            return new Objective(Type.STALK, text, preySet, goal, 0, 0, 0, 0);
        }

        /** Catch {@code goal} prey within {@code seconds} of the first catch. */
        public static Objective catchInTime(String text, String preySet, int goal, int seconds) {
            return new Objective(Type.CATCH_IN_TIME, text, preySet, goal, 0, 0, 0, seconds);
        }

        /**
         * Cure a cat of a Warrior Cats Events disease: {@code key} is "disease id@cat uuid" (see {@link Patients}), or
         * "plan:who:disease:count" for a duty whose patients are only chosen when it is taken on.
         */
        public static Objective cure(String text, String key, int goal) {
            return new Objective(Type.CURE, text, key, goal, 0, 0, 0, 0);
        }

        public boolean done() {
            return progress >= goal;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("type", type.name());
            t.putString("text", text);
            t.putString("key", key);
            t.putInt("goal", goal);
            t.putDouble("x", x);
            t.putDouble("z", z);
            t.putDouble("r", radius);
            t.putInt("y", y);
            t.putInt("progress", progress);
            t.putLong("started", started);
            return t;
        }

        static Objective load(CompoundTag t) {
            Objective o = new Objective(Type.valueOf(t.getString("type")), t.getString("text"), t.getString("key"),
                    t.getInt("goal"), t.getDouble("x"), t.getDouble("z"), t.getDouble("r"), t.getInt("y"));
            o.progress = t.getInt("progress");
            o.started = t.getLong("started");
            return o;
        }
    }
}
