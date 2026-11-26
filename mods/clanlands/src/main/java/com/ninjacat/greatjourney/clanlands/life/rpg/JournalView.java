package com.ninjacat.greatjourney.clanlands.life.rpg;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Everything the journal shows one player, built on the server ({@code QuestLog.view}) and sent to that player's
 * client when the journal opens or changes. Plain data: the server decides, the client only draws.
 *
 * @param catName   the player's cat name
 * @param clan      the player's Clan name, or "" for loners and kittypets
 * @param rank      e.g. "Apprentice", "Warrior", "Kittypet", "Loner"
 * @param clanColour the Clan's colour (RGB), for headings
 * @param standing  Clan standing earned through duties
 * @param nextGoal  one line on what to aim for next ("Earn your warrior name")
 * @param quests    story quests and duties, active first
 * @param places    places of the books, with whether this player has found them
 * @param clanmates "Name — Rank" lines for the player's Clan
 * @param starclan  cats who have gone to StarClan in this world ("Name of ThunderClan")
 * @param ladder    the standing that opens something in a book Clan, lowest first; empty outside one
 */
public record JournalView(String catName, String clan, String rank, int clanColour, int standing, String nextGoal,
                          List<Quest> quests, List<Place> places, List<String> clanmates, List<String> starclan, List<Rung> ladder) {

    /**
     * A story quest or a duty. {@code story} quests drive the apprentice path; duties are optional and repeatable.
     * {@code reward} is in words ("" for none); {@code ready} says where to take it once it only needs finishing ("" until
     * then); {@code onMap} whether it has a place on the Clan map, and {@code followed} whether it is the gold star there.
     */
    public record Quest(String id, String title, String giver, String description, List<Objective> objectives,
                        boolean story, boolean done, String reward, String ready, boolean onMap, boolean followed) {
        public Quest(String id, String title, String giver, String description, List<Objective> objectives, boolean story, boolean done) {
            this(id, title, giver, description, objectives, story, done, "", "", false, false);
        }

        public Quest withDetail(String reward, String ready) {
            return new Quest(id, title, giver, description, objectives, story, done, reward, ready, onMap, followed);
        }

        public Quest withMap(boolean onMap, boolean followed) {
            return new Quest(id, title, giver, description, objectives, story, done, reward, ready, onMap, followed);
        }
    }

    /** One mark of standing: what a cat with at least this much may do or be. */
    public record Rung(int standing, String what) {}

    /** The marks that matter in a book Clan, from the numbers the game itself goes by. */
    public static final List<Rung> LADDER = List.of(
            new Rung(com.ninjacat.greatjourney.clanlands.life.events.Skirmish.LISTENED_TO, "Trespassers heed you"),
            new Rung(com.ninjacat.greatjourney.clanlands.life.story.Teller.TRUSTED, "StarClan calls you trusted"),
            new Rung(com.ninjacat.greatjourney.clanlands.life.quests.Ranks.DEPUTY_STANDING, "You may be named deputy"),
            new Rung(com.ninjacat.greatjourney.clanlands.life.quests.Ranks.LEADER_STANDING, "You may lead the Clan"));

    /** One step of a quest: "Bring yarrow to Mossheart (1/3)". */
    public record Objective(String text, int progress, int goal) {
        public boolean complete() {
            return progress >= goal;
        }
    }

    /** A named place; {@code hint} says where to look until it is found. */
    public record Place(String name, boolean found, String hint) {}

    public static final StreamCodec<RegistryFriendlyByteBuf, JournalView> STREAM_CODEC = StreamCodec.of(JournalView::write, JournalView::read);

    private static void write(RegistryFriendlyByteBuf b, JournalView v) {
        b.writeUtf(v.catName);
        b.writeUtf(v.clan);
        b.writeUtf(v.rank);
        b.writeInt(v.clanColour);
        b.writeVarInt(v.standing);
        b.writeUtf(v.nextGoal);
        b.writeVarInt(v.quests.size());
        for (Quest q : v.quests) {
            b.writeUtf(q.id);
            b.writeUtf(q.title);
            b.writeUtf(q.giver);
            b.writeUtf(q.description, 4096);
            b.writeVarInt(q.objectives.size());
            for (Objective o : q.objectives) {
                b.writeUtf(o.text);
                b.writeVarInt(o.progress);
                b.writeVarInt(o.goal);
            }
            b.writeBoolean(q.story);
            b.writeBoolean(q.done);
            b.writeUtf(q.reward);
            b.writeUtf(q.ready);
            b.writeBoolean(q.onMap);
            b.writeBoolean(q.followed);
        }
        b.writeVarInt(v.places.size());
        for (Place p : v.places) {
            b.writeUtf(p.name);
            b.writeBoolean(p.found);
            b.writeUtf(p.hint);
        }
        writeStrings(b, v.clanmates);
        writeStrings(b, v.starclan);
        b.writeVarInt(v.ladder.size());
        for (Rung r : v.ladder) {
            b.writeVarInt(r.standing);
            b.writeUtf(r.what);
        }
    }

    private static JournalView read(RegistryFriendlyByteBuf b) {
        String cat = b.readUtf(), clan = b.readUtf(), rank = b.readUtf();
        int colour = b.readInt(), standing = b.readVarInt();
        String goal = b.readUtf();
        int nq = b.readVarInt();
        List<Quest> quests = new ArrayList<>(nq);
        for (int i = 0; i < nq; i++) {
            String id = b.readUtf(), title = b.readUtf(), giver = b.readUtf(), desc = b.readUtf(4096);
            int no = b.readVarInt();
            List<Objective> obj = new ArrayList<>(no);
            for (int j = 0; j < no; j++) obj.add(new Objective(b.readUtf(), b.readVarInt(), b.readVarInt()));
            quests.add(new Quest(id, title, giver, desc, obj, b.readBoolean(), b.readBoolean(), b.readUtf(), b.readUtf(),
                    b.readBoolean(), b.readBoolean()));
        }
        int np = b.readVarInt();
        List<Place> places = new ArrayList<>(np);
        for (int i = 0; i < np; i++) places.add(new Place(b.readUtf(), b.readBoolean(), b.readUtf()));
        List<String> mates = readStrings(b), stars = readStrings(b);
        int nr = b.readVarInt();
        List<Rung> ladder = new ArrayList<>(nr);
        for (int i = 0; i < nr; i++) ladder.add(new Rung(b.readVarInt(), b.readUtf()));
        return new JournalView(cat, clan, rank, colour, standing, goal, quests, places, mates, stars, ladder);
    }

    private static void writeStrings(FriendlyByteBuf b, List<String> s) {
        b.writeVarInt(s.size());
        for (String x : s) b.writeUtf(x);
    }

    private static List<String> readStrings(FriendlyByteBuf b) {
        int n = b.readVarInt();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(b.readUtf());
        return out;
    }

    public static JournalView empty(String catName) {
        return new JournalView(catName, "", "Loner", 0xBBAA88, 0, "Find a Clan, or walk your own path.", List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
