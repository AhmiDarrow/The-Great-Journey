package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a cat has done, as the Clans and StarClan keep it: <b>standing</b> (how far their Clan trusts them) and
 * <b>honour</b> (whether they have kept the warrior code when it was hard to). The two can pull apart: mercy shown to a
 * starving stranger is honourable and costs standing. The storyteller speaks of honour at the Moonpool, and a cat who
 * has broken the code too often is visited from the Dark Forest. Also what the Clan events need to know of a player.
 */
public final class Deeds {
    private Deeds() {}

    /** Honour is kept within this far of nothing, so that a long life can still be turned about. */
    static final int HONOUR_LIMIT = 10;

    public static int honour(ServerPlayer p) {
        return QuestState.root(p).getInt("honour");
    }

    static void addHonour(ServerPlayer p, int n) {
        QuestState.root(p).putInt("honour", Math.max(-HONOUR_LIMIT, Math.min(HONOUR_LIMIT, honour(p) + n)));
    }

    /** Changes standing (never below nothing) and honour, and tells the player what it cost or earned. */
    public static void weigh(ServerPlayer p, int standing, int honour) {
        int before = QuestState.standing(p);
        QuestState.addStanding(p, Math.max(-before, standing));
        addHonour(p, honour);
        List<String> said = new ArrayList<>();
        int moved = QuestState.standing(p) - before;
        if (moved != 0) said.add((moved > 0 ? "+" : "") + moved + " standing");
        if (honour != 0) said.add(honour > 0 ? "StarClan marks your honour" : "StarClan saw that");
        if (!said.isEmpty()) Ui.subtitle(p, List.of(Line.narrate("(" + String.join("; ", said) + ")")));
        Ui.push(p);
    }

    /** A named cat: anyone past their apprenticeship. */
    public static boolean grown(ServerPlayer p) {
        return Kin.grown(Kin.role(p));
    }

    /** Whether the player speaks for their Clan: its deputy or leader. */
    public static boolean commands(ServerPlayer p) {
        return Talk.commands(p);
    }

    /** Whether the player leads a patrol of their own just now. */
    public static boolean leadsPatrol(ServerPlayer p) {
        return Patrols.leads(p);
    }

    /** The player's cat's name. */
    public static String name(ServerPlayer p) {
        return Joining.catName(p);
    }
}
