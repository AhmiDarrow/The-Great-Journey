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
            quests.add(new JournalView.Quest(QuestMarks.id(t), t.title, t.giverName, t.description, obj, t.story, false)
                    .withDetail(QuestMarks.reward(t), QuestMarks.ready(p, t)));
        }
        JournalView.Quest seek = com.ninjacat.greatjourney.clanlands.life.Starts.seekQuest(p);   // a clanless cat looking for a camp
        if (seek != null) quests.add(0, seek);
        // a new leader's walk for their nine lives (Ranks), and a Clan of the player's own (Founding)
        JournalView.Quest lives = book == null ? null : Ranks.quest(p, Ranks.leads(p) && QuestState.root(p).getBoolean("nineLives"), BookClans.Info.of(book).title);
        if (lives != null) quests.add(0, lives);
        JournalView.Quest own = Founding.quest(p);
        if (own != null) quests.add(own);
        JournalView.Quest riddle = Riddles.quest(p);
        if (riddle != null) quests.add(0, riddle);
        List<JournalView.Place> places = new ArrayList<>();
        Set<String> found = QuestState.places(p);
        for (Places.Place pl : Places.all()) places.add(new JournalView.Place(pl.name(), found.contains(pl.id()), pl.hint()));
        List<String> mates = new ArrayList<>();
        if (wc != null) {
            UUID leading = book == null ? null : life.leaderPlayer(book);
            // a player who leads is listed with the players below when they are here
            if (book != null && (leading == null || p.serverLevel().getServer().getPlayerList().getPlayer(leading) == null)) mates.add(life.leader(book) + " — Leader");
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
                mates.add(Joining.catName(member) + " — " + (id.equals(leading) ? "Leader" : niceRank(e.getValue().name())) + (member == p ? " (you)" : ""));
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
        // which quests have a place on the Clan map, and which of them is followed
        Set<String> marked = new java.util.HashSet<>();
        String followed = "";
        for (var m : com.ninjacat.greatjourney.clanlands.life.rpg.ClanMaps.marksFor(p)) {
            marked.add(m.quest());
            if (m.followed()) followed = m.quest();
        }
        for (int i = 0; i < quests.size(); i++) {
            JournalView.Quest q = quests.get(i);
            quests.set(i, q.withMap(marked.contains(q.id()), q.id().equals(followed)));
        }
        return new JournalView(Joining.catName(p), wc == null ? "" : wc.name, rank, colour, QuestState.standing(p),
                nextGoal(p, wc), quests, places, mates, starclan, book == null ? List.of() : JournalView.LADDER, QuestState.quiet(p));
    }

    private static String rank(ServerPlayer p, ClanData.Clan wc) {
        if (wc == null) {
            return switch (Journey.start(p)) {
                case KITTYPET -> "Kittypet";
                case LONER -> "Loner";
                default -> "Newcomer";
            };
        }
        if (Ranks.leads(p)) return "Leader";
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
            var seeking = com.ninjacat.greatjourney.clanlands.life.Starts.seeking(p);
            if (seeking != null) return "Find the " + seeking.title + " camp.";
            String own = Founding.goal(p, null);
            // what a cat of no Clan can do meanwhile (Strangers, MoonDreams)
            String alone = " Loners you meet may ask a favour of you, and StarClan speaks at the Moonpool by night.";
            return (Journey.start(p) == Journey.Start.KITTYPET
                    ? "Explore the woods beyond your garden. Wild cats live there..."
                    : "Visit a Clan camp if you wish to join a Clan." + (own == null ? "" : " " + own)) + alone;
        }
        Clan book = Kin.bookClan(p);
        if (book == null) {
            // a Clan players founded themselves: no mentor waits there
            String own = Founding.goal(p, wc);
            return own != null ? own : "Serve " + wc.name + ": talk to your Clanmates for duties.";
        }
        int step = QuestState.storyStep(p);
        if (step == 0) return "Go to your camp and meet your mentor. Your leader can talk with you about your place in the Clan.";
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        if (step <= 6 && Mentors.lost(p, wc, life) != null) return "Go back to camp: " + life.leader(book) + " will name a new mentor for you.";
        if (step <= 6) return (healer ? "Earn your medicine cat name: " : "Earn your warrior name: ") + Tasks.title(QuestState.path(p), step) + ".";
        if (step == QuestState.CEREMONY) return healer ? "Go back to camp, where your mentor will name you a medicine cat." : "Go back to camp for your warrior ceremony.";
        ClanData.ClanPlayerRank r = wc.members.get(p.getUUID());
        if (r == ClanData.ClanPlayerRank.ELDER) return "Rest in the elders' den and share your stories. Ask your leader if you want your old duties back.";
        if (r == ClanData.ClanPlayerRank.QUEEN) return "Help raise the Clan's kits in the nursery. Ask your leader if you want to go back to your duties.";
        // a named cat: their own apprentice (or why none yet), and how far they are from deputy and leader
        String pupil = Pupils.goal(p), rise = Ranks.goal(p);
        String pile = com.ninjacat.greatjourney.clanlands.life.events.Hunger.status(p.getServer().overworld(), book);
        String more = (pupil == null ? "" : " " + pupil) + (rise == null ? "" : " " + rise) + (pile == null ? "" : " " + pile);
        if (healer) return "Heal your Clan: gather herbs, tend the sick, and share tongues with StarClan at the half moon." + more;
        // a deputy has no deputy to ask: the leader gives them the patrols (Tasks.dutyFor)
        if (r == ClanData.ClanPlayerRank.DEPUTY && !Ranks.leads(p)) return "Lead the patrols: ask " + life.leader(book) + " where the Clan needs them, and see to your Clanmates." + more;
        return (Ranks.leads(p) ? "" : "Serve your Clan: talk to your Clanmates for duties, and make the journey to the old forest.") + more;
    }
}
