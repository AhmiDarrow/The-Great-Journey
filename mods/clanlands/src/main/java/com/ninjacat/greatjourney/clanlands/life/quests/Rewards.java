package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** Finishing a task: thanks from the cat, Clan standing, experience (Warrior Cats Events' skills cost it) and a gift. */
final class Rewards {
    private Rewards() {}

    static void complete(ServerPlayer p, Task t, WCatEntity giver) {
        String name = giver != null ? Kin.name(giver) : t.giverName;
        int colour = Kin.colour(p);
        QuestState.addStanding(p, t.standing);
        QuestState.countCompleted(p, t.kind);
        if (t.xp > 0) p.giveExperiencePoints(t.xp);
        Bag.give(p, t.rewardItem, t.rewardCount);
        if (t.story) {
            Story.stepDone(p, t, giver);
        } else {
            List<Task> d = QuestState.duties(p);
            d.removeIf(x -> QuestState.same(x, t));
            QuestState.setDuties(p, d);
            if (t.giverId != null) QuestState.markCompletedToday(p, t.giverId, p.level().getDayTime() / 24000L);
            Line thanks = Line.say(name, colour, thanks(t));
            if (giver != null) thanks = thanks.speakerEntity(giver);
            Dialog.play(p, thanks, Line.narrate("Your standing in the Clan grows (+" + t.standing + ")."));
        }
        Ui.push(p);
    }

    private static String thanks(Task t) {
        return switch (t.kind) {
            case HERBS -> "These are perfect. You have a good nose for herbs. Here, take some traveling herbs for a long day.";
            case HUNT, FISH -> "The Clan will eat well tonight. The fresh-kill pile hasn't looked this good in a moon!";
            case BORDER_PATROL -> "Our borders are marked and safe. Good work.";
            case MOSS -> "Ahh, soft as a kit's fur. Sit down, I'll tell you about the time I fought off a fox single-pawed...";
            case FEED_ELDER -> "Kind of you, young one. You'll make a fine warrior.";
            case FEED_QUEEN -> "Thank you. The kits will grow strong and loyal, like you.";
            case MOSS_BALL -> "Look at them pounce! You've earned the nursery's thanks.";
            case CLIMB -> "You climbed like a squirrel! A warrior needs that.";
            case STALK -> "Silent paws, quick strike. That's how a warrior hunts.";
            default -> "Well done.";
        };
    }
}
