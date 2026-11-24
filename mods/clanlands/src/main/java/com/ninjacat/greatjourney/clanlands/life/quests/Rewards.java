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
        } else if (t.kind == Task.Kind.LESSON) {
            Pupils.lessonDone(p, giver);
        } else {
            List<Task> d = QuestState.duties(p);
            d.removeIf(x -> QuestState.same(x, t));
            QuestState.setDuties(p, d);
            if (t.giverId != null) QuestState.markCompletedToday(p, t.giverId, p.level().getDayTime() / 24000L);
            Line thanks = Line.say(name, colour, thanks(t, Kin.grown(Kin.role(p))));
            if (giver != null) thanks = thanks.speakerEntity(giver);
            Dialog.play(p, thanks, Line.narrate("Your standing in the Clan grows (+" + t.standing + ")."));
        }
        Ui.push(p);
    }

    /** The giver's thanks; a named cat ({@code grown}) is thanked as one, not as an apprentice. */
    private static String thanks(Task t, boolean grown) {
        return switch (t.kind) {
            case HERBS -> "These are perfect. You have a good nose for herbs. Here, take some traveling herbs for a long day.";
            case HUNT, FISH -> "The Clan will eat well tonight. The fresh-kill pile hasn't looked this good in a moon!";
            case BORDER_PATROL -> grown ? "Our borders are marked and safe. The Clan sleeps easier for it." : "Our borders are marked and safe. Good work.";
            case MOSS -> "Ahh, soft as a kit's fur. Sit down, I'll tell you about the time I fought off a fox single-pawed...";
            case FEED_ELDER -> grown ? "Kind of you. Not every warrior remembers the elders." : "Kind of you, young one. You'll make a fine warrior.";
            case FEED_QUEEN -> "Thank you. The kits will grow strong and loyal, like you.";
            case MOSS_BALL -> "Look at them pounce! You've earned the nursery's thanks.";
            case CLIMB -> grown ? "All the way up! I owe you first pick from the fresh-kill pile." : "You climbed like a squirrel! A warrior needs that.";
            case STALK -> "Silent paws, quick strike. That's how a warrior hunts.";
            case TEND -> "That's much better already. Thank you. StarClan guides your paws.";
            case HERB_STORE -> "Fresh and well picked. The store will see us through a moon or more. Here, take some traveling herbs.";
            case ELDER_HERBS -> grown ? "Ahh. That's eased the ache. Sit a while, and I'll tell you how it was in my day..."
                    : "Ahh. That's eased the ache, young one. Sit a while, and I'll tell you how it was in my day...";
            case SCOUT -> "Good. That is what I needed to know, and the Clan is safer for it.";
            case ELDER_TALE -> "Still there? Ha! I climbed all over it once, with a fox snapping at my tail. Sit down, I'll tell you...";
            case RACE -> "Three already? I'd only caught two. You win. This time.";
            case SHARE_PREY -> "You're a good Clanmate. I'll take your dawn patrol one of these days.";
            case TEACH -> "I saw how you did it! Low, and slow, and then all at once. Thank you!";
            case DARE -> "You beat me! Next time I'll win, you'll see.";
            case PLAY -> "It's HUGE! Did you get it all by yourself? When I'm big I'm going to catch ten!";
            default -> "Well done.";
        };
    }
}
