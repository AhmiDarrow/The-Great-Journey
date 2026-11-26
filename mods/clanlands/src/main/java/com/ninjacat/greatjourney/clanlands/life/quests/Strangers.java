package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import com.ninjacat.greatjourney.clanlands.life.gathering.ClanNews;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cat;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Talking to cats of no Clan, the same way as to Clan cats ({@link Talk}): kittypets (Minecraft's own cats: a Twoleg's
 * pet with a collar, or a stray), and the loners and rogues that live wild (Warrior Cats Events' cats with no Clan).
 * They say who they are, what their life is like, what they have seen of the Clans (the news of the Clan whose land
 * they stand on, as an outsider tells it), and what they make of the cat in front of them. Asked for after play
 * (2026-11-26): only Clan cats could be spoken to.
 *
 * <p>A cat with no Clan has had nothing to do but look for one. A loner now asks such a cat a favour, once a day each
 * (prey caught for them, or a stretch of land looked over), kept in the journal like a Clan duty and handed in by
 * talking to them again. It earns experience and standing: a name the Clans have heard before the cat ever joins one.
 *
 * <p>Cats the pack has given a part of their own keep it: the Tribe, Purdy and the StarClan cats carry a
 * {@code greatjourney_} tag and are answered where they are made. A player's own cat is left to its owner.
 */
final class Strangers {
    private Strangers() {}

    enum Kind { KITTYPET, LONER, ROGUE }

    private static final int KITTYPET_COLOUR = 0xD8C8E8, LONER_COLOUR = 0xC8B890, ROGUE_COLOUR = 0xB08070;
    /** As {@code Happenings.FOE_TAG}: a cat a Clan event set on the camp. */
    private static final String FOE_TAG = "greatjourney_event_foe";

    /** What kind of stranger this is to {@code p}, or null for anything else (a Clan cat, a cat of their own, a story cat). */
    static Kind kind(Entity e, ServerPlayer p) {
        if (!(e instanceof LivingEntity l) || !l.isAlive()) return null;
        if (e instanceof Cat c) return p.getUUID().equals(c.getOwnerUUID()) ? null : Kind.KITTYPET;
        if (!(e instanceof WCatEntity c) || c.isNoAi() || p.getUUID().equals(c.getOwnerUUID())) return null;
        if (c.getClanUUID() != null && !c.getClanUUID().equals(ClanData.EMPTY_UUID)) return null;
        if (c.getTags().contains(FOE_TAG)) return Kind.ROGUE;
        for (String t : c.getTags()) if (t.startsWith("greatjourney_")) return null;
        return Kind.LONER;
    }

    private static String name(Entity e, Kind k) {
        if (e.getCustomName() != null) return e.getCustomName().getString().replace(" ♂", "").replace(" ♀", "").trim();
        return switch (k) {
            case KITTYPET -> e instanceof Cat c && c.isTame() ? "A kittypet" : "A stray";
            case LONER -> "A loner";
            case ROGUE -> "A rogue";
        };
    }

    private static int colour(Kind k) {
        return k == Kind.KITTYPET ? KITTYPET_COLOUR : k == Kind.LONER ? LONER_COLOUR : ROGUE_COLOUR;
    }

    /** One of {@code lines}, always the same one for the same cat: each has its own way of talking. */
    private static String pick(Entity e, String... lines) {
        return lines[Math.floorMod(e.getUUID().hashCode(), lines.length)];
    }

    /** What the player is to a stranger: a Clan cat (and which), a kittypet like them, or a cat of no Clan. */
    private static String scent(ServerPlayer p) {
        ClanData.Clan mine = Joining.clanOf(p);
        return mine != null ? mine.name : Journey.start(p) == Journey.Start.KITTYPET ? "kittypet" : "loner";
    }

    static void greet(ServerPlayer p, Entity e, Kind k) {
        String n = name(e, k), who = scent(p);
        boolean clanCat = Joining.clanOf(p) != null;
        if (k == Kind.ROGUE) {
            Dialog.play(p, Line.say(n, colour(k), pick(e, "Keep your distance, " + (clanCat ? "Clan cat" : "stranger") + ". I take what I need, and I don't ask.",
                    "What are you staring at? Your precious warrior code means nothing to me.",
                    "This prey is mine. Fight me for it or get out of my way.")).speakerEntity(e));
            return;
        }
        String hello = k == Kind.KITTYPET
                ? clanCat ? pick(e, "Oh! You're one of the wild cats, aren't you? You smell like the whole forest!",
                        "Eep! Please don't claw me. My housefolk say the forest cats eat bones!",
                        "A real " + who + " cat, in my garden! Nobody will ever believe this.")
                : who.equals("kittypet") ? pick(e, "Hello! Do you live round here too? I haven't seen you on the fences.",
                        "Oh, hello! Is your food the dry sort or the wet sort? Mine's the dry sort.",
                        "You look like you're off somewhere. Not into the woods, surely?")
                : pick(e, "You don't have a collar. Don't your housefolk worry about you?",
                        "Hello! You look hungry. There's a bowl by my door, if you're quick.",
                        "You smell of far-off places. Where do you sleep at night?")
                : clanCat ? pick(e, "A Clan cat. I keep off your land and you keep your claws to yourself: that's how I've stayed alive.",
                        "Easy, warrior. I'm only passing through, and I've taken nothing of yours.",
                        "Your kind never travels alone. Where's the rest of your patrol?")
                : pick(e, "Another cat walking alone. Good. There's room enough out here for both of us.",
                        "You've the look of a cat with no nest to go back to. I know it well.",
                        "Hmm. Not a Clan cat, by the smell of you. What brings you this way?");
        int id = e.getId();
        List<Choice> choices = new ArrayList<>(List.of(
                Choice.of(k == Kind.KITTYPET ? "What is life with Twolegs like?" : "How do you live out here?", "greatjourney quest stranger life " + id),
                Choice.of("What do you know of the Clans?", "greatjourney quest stranger clans " + id),
                Choice.of("Have you seen anything lately?", "greatjourney quest stranger news " + id)));
        if (k == Kind.LONER && !clanCat) {
            Task owed = owed(p, e);
            if (owed != null && owed.readyToHandIn()) {
                done(p, e, owed);
                return;
            }
            choices.add(Choice.of(owed != null ? "About what you asked of me..." : "Need a paw with anything?", "greatjourney quest stranger favour " + id));
        }
        choices.add(Choice.of("Goodbye", "greatjourney quest bye"));
        Dialog.play(p, Line.say(n, colour(k), hello).speakerEntity(e).withChoices(choices.toArray(Choice[]::new)));
    }

    // --- a loner's favour, for a cat with no Clan

    static final int FAVOUR_STANDING = 3, FAVOUR_XP = 30;

    private static Task owed(ServerPlayer p, Entity e) {
        for (Task t : QuestState.duties(p)) if (e.getUUID().equals(t.giverId)) return t;
        return null;
    }

    /** Today's favour for this loner: the same one however often it is asked for (by the cat and the day). */
    static Task favourFor(Entity e, String n, long day) {
        String who = e.getCustomName() == null ? "the loner" : n, Who = e.getCustomName() == null ? "The loner" : n;
        if (Math.floorMod(e.getUUID().hashCode() + (int) day, 2) == 0) {
            return new Task(Task.Kind.HUNT, "A favour for " + who, Who + " has gone hungry. Catch two pieces of prey for them, and come back.", n, e.getUUID(), false,
                    new ArrayList<>(List.of(Task.Objective.catchPrey("Catch 2 prey for " + who, Kin.preySet(Clan.THUNDER), 2))), FAVOUR_STANDING, FAVOUR_XP, "", 0);
        }
        double a = Math.floorMod(e.getUUID().hashCode() * 31 + (int) day, 360) * Math.PI / 180;
        double x = e.getX() + Math.cos(a) * 160, z = e.getZ() + Math.sin(a) * 160;
        String way = Kin.direction(e.getX(), e.getZ(), x, z);
        return new Task(Task.Kind.SCOUT, "A favour for " + who, Who + " wants to know what moves in the land to the " + way + ". Go and look it over, and come back.",
                n, e.getUUID(), false, new ArrayList<>(List.of(Task.Objective.visit("Look over the land to the " + way + " of where you met " + who, x, z, 20))),
                FAVOUR_STANDING, FAVOUR_XP, "", 0);
    }

    private static boolean favour(ServerPlayer p, Entity e, Kind k) {
        if (k != Kind.LONER || Joining.clanOf(p) != null) return false;
        String n = name(e, k);
        long day = p.level().getDayTime() / 24000L;
        Task owed = owed(p, e);
        String say;
        if (owed != null) {
            String left = "";
            for (Task.Objective o : owed.objectives) if (!o.done() && left.isEmpty()) left = o.text;
            say = "Not done yet, are you? " + left + ". I'll be here.";
        } else if (QuestState.completedToday(p, e.getUUID(), day)) {
            say = "You've done right by me today. Come by tomorrow.";
        } else if (QuestState.duties(p).size() >= QuestState.MAX_DUTIES) {
            say = "You've promised enough to other cats already. Keep those promises first.";
        } else {
            Task t = favourFor(e, n, day);
            List<Task> d = QuestState.duties(p);
            d.add(t);
            QuestState.setDuties(p, d);
            Ui.push(p);
            Dialog.play(p, Line.say(n, colour(k), t.kind == Task.Kind.HUNT ? "My belly's been empty two days. Catch me a couple of mice, and I won't forget it."
                    : "Something's changed out that way, and I'd sooner you found out what than me. Go and look, and tell me.").speakerEntity(e),
                    Line.narrate(t.title + " is in your journal. Talk to them again when it is done."));
            return true;
        }
        Dialog.play(p, Line.say(n, colour(k), say).speakerEntity(e));
        return true;
    }

    private static void done(ServerPlayer p, Entity e, Task t) {
        List<Task> d = QuestState.duties(p);
        d.removeIf(x -> QuestState.same(x, t));
        QuestState.setDuties(p, d);
        QuestState.markCompletedToday(p, e.getUUID(), p.level().getDayTime() / 24000L);
        QuestState.countCompleted(p, t.kind);
        QuestState.addStanding(p, t.standing);
        p.giveExperiencePoints(t.xp);
        String n = name(e, Kind.LONER);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(n, colour(Kind.LONER), t.kind == Task.Kind.HUNT ? "Still warm. You hunt like a cat who's been hungry too. I owe you one."
                : "So that's how it is out there. Good. You've sharp eyes, and you came back: more than most do.").speakerEntity(e));
        lines.add(Line.narrate("A cat who keeps their word is talked of, even among the Clans (+" + t.standing + " standing)."));
        // the way to a Clan of one's own starts with loners who trust you (Founding)
        if (Mode.of(p.getServer()) == Mode.FREE) lines.add(Line.narrate("A loner who trusts you may follow you: offer them fresh-kill and herbs."));
        Dialog.play(p, lines);
        Ui.push(p);
    }

    /** A choice from {@link #greet}: {@code topic} is "life", "clans" or "news". */
    static boolean answer(ServerPlayer p, int entityId, String topic) {
        Entity e = p.serverLevel().getEntity(entityId);
        Kind k = e == null || e.distanceTo(p) > 24 ? null : kind(e, p);
        if (k == null || k == Kind.ROGUE) return false;
        if (topic.equals("favour")) return favour(p, e, k);
        boolean pet = k == Kind.KITTYPET, clanCat = Joining.clanOf(p) != null;
        String text = switch (topic) {
            case "life" -> pet ? pick(e, "My housefolk fill my bowl twice a day, and there's a warm spot by the window. I don't see why any cat would want more.",
                    "It's soft. Too soft, some days. I sit on the fence and watch the trees, and wonder what's under them.",
                    "They carry me to the cutter once a year, and I hate it. But the rest is naps and cream.")
                    : pick(e, "I hunt for myself and answer to no cat. The barns are warm, if you know which Twolegs to stay clear of.",
                    "Lean, mostly. Leaf-bare is the worst of it. But every mouse I catch is my own.",
                    "I move with the prey. No borders, no leader, no one to mourn me. That's the bargain.");
            case "clans" -> pet ? (clanCat ? "Only stories! They say you live in a great camp, and fight badgers, and that your dead walk in the stars. Is it true?"
                    : "They live in the woods, whole crowds of them, and they chase off any cat who crosses their scent marks. "
                    + "But I heard they took in a kittypet once. Walk deep enough under the trees and their patrols will find you.")
                    : (clanCat ? "Four Clans, four borders, and a truce every full moon. I know where each of your scent lines runs, and I mean to keep it that way."
                    : "They guard their land like it was their own tail. But they look after their own, I'll give them that. "
                    + "Wander onto their land in daylight, keep your claws in, and a patrol will bring you to their deputy.");
            default -> news(p, pet);
        };
        Dialog.play(p, Line.say(name(e, k), colour(k), text).speakerEntity(e));
        return true;
    }

    /** The latest news of the Clan whose land this is, as an outsider heard it; a quiet answer where there is none. */
    private static String news(ServerPlayer p, boolean pet) {
        ClanWorld w = ClanWorld.current();
        ServerLevel ow = p.getServer().overworld();
        Clan c = w == null || p.level() != ow ? null : Homeland.territoryAt(w, p.getX(), p.getZ());
        if (c == null) for (Clan o : Clan.values()) if (c == null && !ClanNews.recent(ow, o, 1).isEmpty()) c = o;
        List<ClanNews.Item> items = c == null ? List.of() : ClanNews.recent(ow, c, 1);
        if (items.isEmpty()) return pet ? "Nothing but birds and the monsters on the Thunderpath. It's been ever so quiet."
                : "Quiet. Too quiet for my liking. When the forest goes still, something is coming.";
        return (pet ? "I heard the " + BookClans.Info.of(c).title + " cats yowling about it from my fence: "
                : "Word travels, even to cats like me. From " + BookClans.Info.of(c).title + ": ") + items.get(0).text();
    }
}
