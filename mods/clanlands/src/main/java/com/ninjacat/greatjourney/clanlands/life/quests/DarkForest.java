package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;

/**
 * The Dark Forest, in Free Mode: under Tigerstar, or once a cat has broken the code often enough, a cat with no stars
 * comes to their sleep and offers to train them. The training is real (experience for Warrior Cats Events' skills, and
 * claws that hit harder for a day), and so is the price: their Clan trusts them less, and StarClan sees. After a few
 * nights StarClan calls them to account at the Moonpool: turn away from him, or keep going and walk without
 * StarClan's signs.
 */
final class DarkForest {
    private DarkForest() {}

    /** Honour this low draws the Dark Forest's eye, whoever watches over the Clans. */
    static final int NOTICED = -3;
    /** After this many nights of training StarClan asks for an answer. */
    static final int RECKONING = 3;
    static final long APART = 24000L * 2;
    static final int TRAINING_XP = 120;
    private static final int COLOUR = 0x9A3030, STAR = 0xCFE3FF;
    private static final String VISITS = "darkVisits", NEXT = "darkNext", OFFERED = "darkOffered", SILENCED = "darkSilenced";

    static boolean noticed(ServerPlayer p, Teller t) {
        return t == Teller.TIGERSTAR || Deeds.honour(p) <= NOTICED;
    }

    /** Waking from a night's sleep: perhaps he came. */
    static void onWake(PlayerWakeUpEvent e) {
        // only a night slept through: getting up again out of the nest asks for the level to be updated, a morning does not
        if (e.updateLevel() || !(e.getEntity() instanceof ServerPlayer p) || Journey.stage(p) != Journey.Stage.PLAYING) return;
        var ow = p.getServer().overworld();
        Teller t = StoryData.active(ow);
        if (t == null || Joining.clanOf(p) == null || Dialog.busy(p) || !noticed(p, t)) return;
        if (ow.getGameTime() < QuestState.root(p).getLong(NEXT) || p.getRandom().nextBoolean()) return;
        Ui.play(p, offer(p, ow.getGameTime()));
    }

    static List<Line> offer(ServerPlayer p, long now) {
        CompoundTag root = QuestState.root(p);
        root.putLong(NEXT, now + APART);
        root.putBoolean(OFFERED, true);
        int visits = root.getInt(VISITS);
        return List.of(
                Line.narrate("You dream of a forest with no stars. The trunks are black, nothing grows, and something is watching you."),
                Line.say("A shadowy tabby", COLOUR, visits == 0 ? "There you are, " + Deeds.name(p) + ". Your Clan does not see what you could be. I do. Let me teach you."
                        : "Back again? Good. Tonight I will show you how to strike so they never get up too fast to be afraid.")
                        .withChoices(new Choice("Train with him", "greatjourney quest dark train", 0xFF7755),
                                Choice.of("Turn your back and wake up", "greatjourney quest dark wake")));
    }

    /** "train" or "wake" to his offer; "turn" or "keep" to StarClan's question. */
    static boolean answer(ServerPlayer p, String what) {
        CompoundTag root = QuestState.root(p);
        Dialog.clear(p);
        switch (what) {
            case "train", "wake" -> {
                if (!root.getBoolean(OFFERED)) return false;
                root.remove(OFFERED);
                if (what.equals("wake")) {
                    Ui.play(p, List.of(Line.narrate("You wrench yourself awake. His laughter follows you a little way into the morning.")));
                    Deeds.weigh(p, 0, 1);
                    return true;
                }
                root.putInt(VISITS, root.getInt(VISITS) + 1);
                p.giveExperiencePoints(TRAINING_XP);
                p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 24000, 0, false, false));
                Ui.play(p, List.of(Line.narrate("You train until your pads bleed. You wake stronger, with scratches you cannot explain, and your Clanmates look at them.")));
                Deeds.weigh(p, -3, -1);
            }
            case "turn", "keep" -> {
                if (root.getInt(VISITS) < RECKONING) return false;
                if (what.equals("turn")) {
                    root.remove(VISITS);
                    root.remove(SILENCED);
                    Ui.play(p, List.of(Line.say("StarClan", STAR, "Then walk in the light, and earn back what you spent."),
                            Line.narrate("The scratches fade. The tabby does not come that night, nor the next.")));
                    Deeds.weigh(p, -5, Math.max(0, -Deeds.honour(p)));
                } else {
                    root.putBoolean(SILENCED, true);
                    Ui.play(p, List.of(Line.say("StarClan", STAR, "Then you walk without us."),
                            Line.narrate("The stars go out one by one. Your Clan senses a change in you.")));
                    Deeds.weigh(p, -20, -2);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** StarClan's question in a dream at the Moonpool, once there is something to answer for; else nothing. */
    static List<Line> reckoning(ServerPlayer p) {
        if (QuestState.root(p).getInt(VISITS) < RECKONING) return List.of();
        return List.of(Line.say("StarClan", STAR, "We have seen where you go when you sleep, " + Deeds.name(p) + ". Choose.")
                .withChoices(Choice.of("Turn away from him", "greatjourney quest dark turn"),
                        new Choice("I will keep training", "greatjourney quest dark keep", 0xFF7755)));
    }

    /** Whether StarClan keeps its signs from this cat. */
    static boolean silenced(ServerPlayer p) {
        return QuestState.root(p).getBoolean(SILENCED);
    }
}
