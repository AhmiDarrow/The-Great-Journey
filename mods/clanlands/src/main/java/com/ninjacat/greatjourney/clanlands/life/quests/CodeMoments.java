package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import com.ninjacat.greatjourney.clanlands.life.events.EventNews;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * The warrior code as choices, in Free Mode: now and then, out in their Clan's land, a cat meets a moment the books
 * would know, with two honest answers. Each moves standing (what the Clan thinks) and honour (whether the code was
 * kept), and often not the same way: kindness to a stranger can cost a cat their Clan's good opinion. At most one a
 * day; a window closed on it leaves it waiting, and talking to any Clanmate brings it back.
 */
final class CodeMoments {
    private CodeMoments() {}

    /** One answer: what the button says, what happens, and what it moves. {@code news} (or null) goes to the Gathering. */
    record Answer(String label, String outcome, int standing, int honour, String news) {}

    record Moment(String id, String scene, Answer first, Answer second) {}

    static final List<Moment> MOMENTS = List.of(
            new Moment("loner", "A loner crouches under a bush on your Clan's land, ribs showing, a mouse of your Clan's between their paws. They do not even try to run.",
                    new Answer("Let them eat, and go in peace", "They bolt the mouse and look at you a long moment. \"I won't forget it,\" they say.", -2, 2, null),
                    new Answer("Take back the mouse and send them off", "They slink away empty. The mouse goes to your Clan's pile, where it belongs.", 2, -1, null)),
            new Moment("kit", "A kit is mewling in the bracken. It smells of another Clan: it has wandered over the border, and no cat has found it but you.",
                    new Answer("Carry it home to its own Clan", "A queen on the far side of the scent line takes it from you without a word, and then licks your ear. Some of your Clanmates grumble.",
                            -1, 3, "{you} carried a lost kit back across the border to its own Clan."),
                    new Answer("Bring it to your own nursery", "Your queens take it in. Your Clan has one more mouth, and one day one more warrior. Somewhere across the border a mother is calling.",
                            3, -3, "{you} brought another Clan's lost kit to their own nursery.")),
            new Moment("hungry", "Your belly aches with hunger, and the vole you have just caught is warm. No cat would ever know.",
                    new Answer("Carry it to the fresh-kill pile", "You drop it on the pile. The elders eat first, as the code says.", 2, 1, null),
                    new Answer("Eat it where you stand", "It is the best vole you have ever tasted. It goes down hard.", 0, -2, null)),
            new Moment("rogue", "A wounded rogue lies by the border stream. A senior warrior of yours wants them off the land now, whether they can walk or not.",
                    new Answer("Let them rest until they can walk", "You sit guard while the rogue sleeps. By sunset they limp away. The warrior will tell the others you went soft.", -3, 2, null),
                    new Answer("Drive them off, as you are told", "The rogue staggers away over the stones. The warrior nods: orders followed.", 3, -2, null)),
            new Moment("blame", "The leader is angry: the dawn scent markers were missed. A young apprentice is being blamed, but the mistake was yours.",
                    new Answer("Own the mistake", "\"It was me.\" The leader's eyes rest on you a long while. The apprentice stares at you as if you hung the moon.", -3, 3, null),
                    new Answer("Say nothing", "The apprentice is sent to clean the elders' nests. They never learn why.", 0, -2, null)),
            new Moment("scentline", "Your quarry darts over the scent line into another Clan's land. It is a fine fat one, and no patrol is in sight.",
                    new Answer("Stop at the scent line", "You watch it go. It is their prey now.", 0, 2, null),
                    new Answer("Follow it over", "You are back across with it before any cat sees. The pile is the better for it. Your scent is still on their side.",
                            3, -2, null)),
            new Moment("river", "A warrior of a rival Clan is struggling in the stream, the current dragging them under on your side.",
                    new Answer("Pull them out", "They cough up half the stream. \"Why?\" they ask. The code says no cat should be left to die.",
                            -1, 3, "{you} pulled a rival warrior from the water."),
                    new Answer("Leave it to their own Clan", "You call out toward their border and walk on. You do not look back to see whether any cat comes.", 1, -3, null)),
            new Moment("secret", "A Clanmate begs you to keep a secret: they have been slipping away at night to meet a cat of another Clan.",
                    new Answer("Talk with them first", "You talk until the moon is low. They promise to think hard about where their loyalty lies. If it comes out, you knew.", -1, 1, null),
                    new Answer("Tell your leader", "The leader thanks you gravely. Your Clanmate does not speak to you for a long while.", 3, 0, null)));

    private static final String PENDING = "codePending", DAY = "codeDay";
    /** Once a second, a chance this small that today's moment comes now: about one in ten minutes out in the territory. */
    private static final int ONE_IN = 600;

    /** Once a second for every player in the overworld. */
    static void tick(ServerPlayer p) {
        ClanWorld w = ClanWorld.current();
        Clan clan = w == null ? null : Kin.bookClan(p);
        if (clan == null || Mode.of(p.getServer()) != Mode.FREE || Journey.stage(p) != Journey.Stage.PLAYING || !p.isAlive() || Dialog.busy(p)) return;
        CompoundTag root = QuestState.root(p);
        long day = p.level().getDayTime() / 24000L + 1;
        if (root.getLong(DAY) == day || !root.getString(PENDING).isEmpty() || p.getRandom().nextInt(ONE_IN) != 0) return;
        if (Homeland.territoryAt(w, p.getX(), p.getZ()) != clan) return;
        root.putLong(DAY, day);
        offer(p, pick(root, p.getRandom()));
    }

    /** A moment this cat has not met lately: each is met once before any comes round again. */
    static Moment pick(CompoundTag root, RandomSource r) {
        List<String> met = new ArrayList<>(List.of(root.getString("codeMet").split(",")));
        List<Moment> fresh = new ArrayList<>(MOMENTS.stream().filter(m -> !met.contains(m.id())).toList());
        if (fresh.isEmpty()) {
            root.remove("codeMet");
            fresh.addAll(MOMENTS);
        }
        return fresh.get(r.nextInt(fresh.size()));
    }

    static void offer(ServerPlayer p, Moment m) {
        CompoundTag root = QuestState.root(p);
        root.putString(PENDING, m.id());
        root.putString("codeMet", root.getString("codeMet") + "," + m.id());
        Ui.play(p, scene(m));
    }

    private static List<Line> scene(Moment m) {
        return List.of(Line.narrate(m.scene()), Line.narrate("What will you do?").withChoices(
                Choice.of(m.first().label(), "greatjourney quest code first"),
                Choice.of(m.second().label(), "greatjourney quest code second")));
    }

    private static Moment pending(ServerPlayer p) {
        String id = QuestState.root(p).getString(PENDING);
        for (Moment m : MOMENTS) if (m.id().equals(id)) return m;
        return null;
    }

    /** A moment still unanswered (its window closed) is put to the cat again; false with none waiting. */
    static boolean again(ServerPlayer p) {
        Moment m = pending(p);
        if (m == null) return false;
        Dialog.play(p, scene(m));
        return true;
    }

    /** "first" or "second": only to a moment that is waiting. */
    static boolean answer(ServerPlayer p, String which) {
        Moment m = pending(p);
        Dialog.clear(p);
        if (m == null || !which.equals("first") && !which.equals("second")) return false;
        QuestState.root(p).remove(PENDING);
        Answer a = which.equals("first") ? m.first() : m.second();
        Ui.play(p, List.of(Line.narrate(a.outcome())));
        Deeds.weigh(p, a.standing(), a.honour());
        Clan clan = Kin.bookClan(p);
        if (a.news() != null && clan != null) EventNews.record(clan, a.news().replace("{you}", Deeds.name(p) + " of " + BookClans.Info.of(clan).title));
        return true;
    }
}
