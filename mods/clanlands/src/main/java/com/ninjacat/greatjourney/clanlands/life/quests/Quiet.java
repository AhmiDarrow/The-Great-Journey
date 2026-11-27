package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * Quiet paws: a player's own choice not to be called away or stopped by a window they did not ask for. With it on there
 * is no invitation to the Gathering or the half-moon meeting (and so no night held), no Moonpool dream, no code moment,
 * Dark Forest visit, friend across the border or border patrol, and StarClan's sign in the Campaign is told without
 * carrying the cat off to the dream. What the player starts themselves (talking to a cat, the journal) is unchanged,
 * as are a new leader's nine lives and the story's own steps.
 * Turned on and off in the journal, from either invitation, or with {@code /greatjourney quest quiet on|off}.
 */
public final class Quiet {
    private Quiet() {}

    /** Sets it; turning it on also lets go of anything already waiting on this player. */
    public static boolean set(ServerPlayer p, boolean on) {
        QuestState.root(p).putBoolean("quiet", on);
        if (on) {
            com.ninjacat.greatjourney.clanlands.life.gathering.Gatherings.leaveMeetings(p);
            com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.wake(p);
        }
        Ui.subtitle(p, List.of(Line.narrate(on
                ? "Quiet paws: on. No meetings or surprises will call you away. Turn it off in your journal."
                : "Quiet paws: off. Your Clan will call you to meetings again.")));
        Ui.push(p);
        return true;
    }
}
