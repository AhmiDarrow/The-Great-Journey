package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/**
 * The tasks the Campaign's story gives along the way, one at a time: feeding a starving Clan or saving the herbs as
 * the forest falls, hunting or carrying traveling herbs on the Great Journey, scouting the pass or tending the cold and
 * weary, marking the new borders or stocking the new medicine den. A warrior and a medicine cat get their own task at
 * the same points of the story, with the same reward, and neither is needed to go on: the chapters move on whether
 * or not they are done. An errand has no giver to go back to; it is done once its objectives are, and where it
 * names a place, once the player is there. Each is given once.
 */
public final class Errands {
    private Errands() {}

    /** Whether this errand was ever given to the player. */
    public static boolean given(ServerPlayer p, String id) {
        for (Tag t : QuestState.root(p).getList("errandsGiven", Tag.TAG_STRING)) if (t.getAsString().equals(id)) return true;
        return false;
    }

    /** Whether the player has an errand under way. */
    public static boolean busy(ServerPlayer p) {
        return QuestState.errand(p) != null;
    }

    /**
     * Gives the player an errand: {@code task} (its kind is made {@link Task.Kind#ERRAND}), done where {@code at} is
     * (x, z, radius; null for anywhere), with {@code done} said when it is.
     */
    public static void give(ServerPlayer p, String id, Task task, double[] at, String intro, String done) {
        CompoundTag root = QuestState.root(p);
        ListTag list = root.getList("errandsGiven", Tag.TAG_STRING);
        list.add(StringTag.valueOf(id));
        root.put("errandsGiven", list);
        Task t = new Task(Task.Kind.ERRAND, task.title, task.description, task.giverName, null, false, task.objectives,
                task.standing, task.xp, task.rewardItem, task.rewardCount);
        QuestState.setErrand(p, t);
        root.putString("errandId", id);
        root.putString("errandDone", done);
        if (at == null) root.remove("errandAt");
        else {
            CompoundTag a = new CompoundTag();
            a.putDouble("x", at[0]);
            a.putDouble("z", at[1]);
            a.putDouble("r", at[2]);
            root.put("errandAt", a);
        }
        Ui.subtitle(p, List.of(Line.narrate(intro), Line.narrate("A new task in your journal: " + t.title + ".")));
        Ui.push(p);
    }

    /** Once a second: the errand finished, if every objective is done (and the player is where it ends). */
    static void tick(ServerPlayer p) {
        Task t = QuestState.errand(p);
        if (t == null || Dialog.busy(p)) return;
        for (Task.Objective o : t.objectives) {
            if (o.type == Task.Objective.Type.BRING ? Bag.count(p, o.key) < o.goal : !o.done()) return;
        }
        CompoundTag root = QuestState.root(p);
        if (root.contains("errandAt")) {
            CompoundTag a = root.getCompound("errandAt");
            if (Math.hypot(p.getX() - a.getDouble("x"), p.getZ() - a.getDouble("z")) > a.getDouble("r")) return;
        }
        for (Task.Objective o : t.objectives) if (o.type == Task.Objective.Type.BRING) Bag.handOver(p, o);
        QuestState.addStanding(p, t.standing);
        QuestState.countCompleted(p, Task.Kind.ERRAND);
        if (t.xp > 0) p.giveExperiencePoints(t.xp);
        Bag.give(p, t.rewardItem, t.rewardCount);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate(root.getString("errandDone")));
        lines.add(Line.narrate("Your standing in the Clan grows (+" + t.standing + ")."));
        QuestState.setErrand(p, null);
        root.remove("errandId");
        root.remove("errandDone");
        root.remove("errandAt");
        Ui.play(p, lines);
        Ui.push(p);
    }

    /** The id of the errand under way, or "". */
    public static String current(ServerPlayer p) {
        return QuestState.root(p).getString("errandId");
    }

    /** Sets the errand aside (the story has moved past where it could be done). */
    public static void drop(ServerPlayer p, String why) {
        if (QuestState.errand(p) == null) return;
        QuestState.setErrand(p, null);
        CompoundTag root = QuestState.root(p);
        root.remove("errandId");
        root.remove("errandDone");
        root.remove("errandAt");
        if (why != null) Ui.subtitle(p, List.of(Line.narrate(why)));
        Ui.push(p);
    }
}
