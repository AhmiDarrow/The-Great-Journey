package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.snowteb.warriorcats_events.clan.ClanData;

/** Builds the journal one player sees: who they are, their tasks, the places they've found, their Clan, StarClan. */
public final class QuestLog {
    private QuestLog() {}

    /** How many of StarClan's names the journal lists. */
    static final int STARCLAN_SHOWN = 40;

    public static JournalView view(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        Clan book = Kin.bookClan(p);
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        String rank = rank(p, wc);
        List<JournalView.Quest> quests = new ArrayList<>();
        for (Task t : QuestState.active(p)) {
            List<JournalView.Objective> obj = new ArrayList<>();
            for (Task.Objective o : t.objectives) obj.add(new JournalView.Objective(o.text, Math.min(o.progress, o.goal), o.goal));
            quests.add(new JournalView.Quest(t.kind.name().toLowerCase(java.util.Locale.ROOT) + (t.giverId == null ? "" : ":" + t.giverId),
                    t.title, t.giverName, t.description, obj, t.story, false));
        }
        List<JournalView.Place> places = new ArrayList<>();
        Set<String> found = QuestState.places(p);
        for (Places.Place pl : Places.all()) places.add(new JournalView.Place(pl.name(), found.contains(pl.id()), pl.hint()));
        List<String> mates = new ArrayList<>();
        if (wc != null) {
            if (book != null) mates.add(life.leader(book) + " — Leader");
            for (ClanData.ClanCat cat : wc.clanCats.values()) {
                String n = cat.catName == null ? "?" : cat.catName.getString().replace(" ♂", "").replace(" ♀", "").trim();
                if (book != null && n.equalsIgnoreCase(life.leader(book))) continue;
                mates.add(n + " — " + niceRank(cat.catRank));
            }
            var server = p.serverLevel().getServer();
            for (var e : wc.members.entrySet()) {
                UUID id = e.getKey();
                ServerPlayer member = server.getPlayerList().getPlayer(id);
                if (member == null) continue;                           // stand-in leader or offline players
                mates.add(Joining.catName(member) + " — " + niceRank(e.getValue().name()) + (member == p ? " (you)" : ""));
            }
        }
        // the most recent first, and only so many: a long-lived world remembers hundreds
        List<String> starclan = new ArrayList<>();
        List<LifeData.Fallen> fallen = life.starClan();
        for (int i = fallen.size() - 1; i >= 0 && starclan.size() < STARCLAN_SHOWN; i--) {
            LifeData.Fallen f = fallen.get(i);
            starclan.add(f.name() + " of " + BookClans.Info.of(f.clan()).title);
        }
        int colour = book != null ? BookClans.Info.of(book).colour : wc != null ? wc.color : 0xC8B88A;
        return new JournalView(Joining.catName(p), wc == null ? "" : wc.name, rank, colour, QuestState.standing(p),
                nextGoal(p, wc), quests, places, mates, starclan);
    }

    private static String rank(ServerPlayer p, ClanData.Clan wc) {
        if (wc == null) {
            return switch (Journey.start(p)) {
                case KITTYPET -> "Kittypet";
                case LONER -> "Loner";
                default -> "Newcomer";
            };
        }
        ClanData.ClanPlayerRank r = wc.members.get(p.getUUID());
        return r == null ? "Guest" : niceRank(r.name());
    }

    private static String niceRank(String raw) {
        if (raw == null || raw.isBlank()) return "Warrior";
        String s = raw.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        if (s.equals("medicineapp")) return "Medicine cat apprentice";
        if (s.equals("medicine")) return "Medicine cat";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String nextGoal(ServerPlayer p, ClanData.Clan wc) {
        if (wc == null) {
            return Journey.start(p) == Journey.Start.KITTYPET
                    ? "Explore the woods beyond your garden. Wild cats live there..."
                    : "Visit a Clan camp if you wish to join a Clan.";
        }
        int step = QuestState.storyStep(p);
        if (step == 0) return "Go to your camp and meet your mentor.";
        if (step <= 6) return "Earn your warrior name: " + Tasks.STORY_TITLES[step] + ".";
        if (step == QuestState.CEREMONY) return "Go back to camp for your warrior ceremony.";
        return "Serve your Clan: talk to your Clanmates for duties, and make the journey to the old forest.";
    }
}
