package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Moonpool dreams, in Free Mode. Any cat (of a Clan or of none) who comes to the Moonpool by night lies down at the
 * water and dreams: a cat who has fallen in this world speaks to them (one of their own Clan, if any has), and
 * StarClan gives a sign, the way to a place of the books they have not found yet. They wake rested, with a little
 * experience. StarClan speaks once a moon (eight nights) to each cat.
 *
 * <p>The world's storyteller ({@link Teller}) comes too, with a word on how the cat has lived, and the dreamer may ask
 * for another to watch over the Clans: one storyteller for the whole world, so everyone playing is told.
 *
 * <p>A new leader's nine lives ({@link Ranks}) and the medicine cats' half-moon meeting come first: a cat waiting for
 * their lives is given those, and a cat in the middle of any scene is left to it. The Campaign has its own Moonpool
 * chapter, so these dreams are Free Mode's.
 */
final class MoonDreams {
    private MoonDreams() {}

    /** StarClan speaks to a cat once in this long: a moon, the eight nights of Minecraft's own. */
    static final long MOON = 24000L * 8;
    private static final int STAR_COLOUR = 0xCFE3FF;
    private static final String KEY = "moonDream", OFFERED = "tellerOffered";

    /** Once a second. */
    static void tick(MinecraftServer server) {
        ServerLevel ow = server.overworld();
        if (Mode.of(server) != Mode.FREE || !Tasks.night(ow)) return;
        Landmark pool = Ranks.starPlace();
        if (pool == null) return;
        for (ServerPlayer p : ow.players()) {
            if (p.isSpectator() || !p.isAlive() || Math.hypot(p.getX() - pool.x(), p.getZ() - pool.z()) > Ranks.STAR_REACH) continue;
            if (Journey.stage(p) != Journey.Stage.PLAYING || Dialog.busy(p) || QuestState.quiet(p)) continue;
            CompoundTag root = QuestState.root(p);
            if (root.getBoolean("nineLives") || !due(root, ow.getGameTime())) continue;
            root.putLong(KEY, ow.getGameTime());
            p.setHealth(p.getMaxHealth());
            p.giveExperiencePoints(60);
            List<Line> lines = new ArrayList<>(dream(p, LifeData.get(ow), Places.all(), QuestState.places(p)));
            lines.addAll(Riddles.dream(p, StoryData.get(ow), p.getRandom()));
            // StarClan's question about the Dark Forest comes before any other choice
            List<Line> account = DarkForest.reckoning(p);
            lines.addAll(account.isEmpty() ? watcher(p, StoryData.get(ow).teller()) : account);
            Dialog.play(p, lines);
        }
    }

    /** The storyteller's word on the dreamer, and the choice of who watches from now on. */
    static List<Line> watcher(ServerPlayer p, Teller t) {
        QuestState.root(p).putBoolean(OFFERED, true);
        List<Choice> choices = new ArrayList<>();
        for (Teller o : Teller.values()) if (o != t) choices.add(new Choice(o.title, "greatjourney quest teller " + o.key(), o.colour));
        if (DarkForest.silenced(p)) choices.add(DarkForest.wayBack(p));
        choices.add(new Choice("Let " + t.title + " watch still", "greatjourney quest bye", 0xAAAAAA));
        return List.of(
                Line.say(t.title, t.colour, t.judge(Joining.catName(p), Deeds.honour(p), QuestState.standing(p))),
                Line.narrate(t.title + " watches over your Clans. Ask for another, or let things be.").withChoices(choices.toArray(Choice[]::new)));
    }

    /** "Let another watch": only at the water, by night, for a cat StarClan has spoken to. */
    static boolean choose(ServerPlayer p, String key) {
        Teller t = Teller.byKey(key);
        ServerLevel ow = p.getServer().overworld();
        Landmark pool = Ranks.starPlace();
        Dialog.clear(p);
        // one choice for each time the dream puts it, so the world's watcher cannot be flipped back and forth
        if (t == null || Mode.of(p.getServer()) != Mode.FREE || !QuestState.root(p).getBoolean(OFFERED) || t == StoryData.get(ow).teller() || p.level() != ow || !Tasks.night(ow)
                || pool == null || Math.hypot(p.getX() - pool.x(), p.getZ() - pool.z()) > Ranks.STAR_REACH) {
            Ui.subtitle(p, List.of(Line.narrate("StarClan hears such things only at the Moonpool, by night.")));
            return false;
        }
        QuestState.root(p).remove(OFFERED);
        watchedBy(ow, t, Joining.catName(p));
        return true;
    }

    /** The world has a new storyteller, and every cat playing hears of it. */
    static void watchedBy(ServerLevel ow, Teller t, String asker) {
        StoryData.get(ow).setTeller(t);
        for (ServerPlayer q : ow.getServer().getPlayerList().getPlayers()) {
            Ui.subtitle(q, List.of(Line.narrate(asker + " has spoken at the water. " + t.title + " watches over the Clans now."),
                    Line.say(t.title, t.colour, t.judge(Joining.catName(q), Deeds.honour(q), QuestState.standing(q)))));
        }
    }

    static boolean due(CompoundTag root, long now) {
        return !root.contains(KEY) || now - root.getLong(KEY) >= MOON;
    }

    /** What the cat dreams: who comes, what they say, and the sign (the nearest of {@code places} not in {@code found}). */
    static List<Line> dream(ServerPlayer p, LifeData life, List<Places.Place> places, Set<String> found) {
        Clan clan = Kin.bookClan(p);
        String you = Joining.catName(p);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate("You lie down at the water's edge and touch your nose to the Moonpool. Cold spreads through you, and the stars come down."));
        // one of their own Clan's fallen if any, else the most lately lost of any Clan
        LifeData.Fallen who = null;
        List<LifeData.Fallen> all = life.starClan();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (who == null) who = all.get(i);
            if (all.get(i).clan() == clan) {
                who = all.get(i);
                break;
            }
        }
        String speaker = who == null ? "A starlit cat" : who.name();
        if (who == null) {
            lines.add(Line.say(speaker, STAR_COLOUR, "Welcome, " + you + ". Few of this lake's cats walk our skies yet. May it be long before you join us."));
        } else if (who.clan() == clan) {
            String title = BookClans.Info.of(clan).title;
            lines.add(Line.narrate("A cat you knew pads toward you over the water, stars in their fur."));
            lines.add(Line.say(speaker, STAR_COLOUR, "Do not grieve for me, " + you + ". I hunt in bright forests now, and I watch over " + title + " still."));
        } else {
            lines.add(Line.say(speaker, STAR_COLOUR, "I was " + who.name() + " of " + BookClans.Info.of(who.clan()).title + ", " + you
                    + ". There are no borders in StarClan. Any cat who comes to this water is heard."));
        }
        if (Joining.clanOf(p) == null) {
            lines.add(Line.say(speaker, STAR_COLOUR, "You walk alone, but not unseen. A Clan would be the stronger for you. Or gather cats of your own, and we will know their names too."));
        }
        if (DarkForest.silenced(p)) {
            lines.add(Line.narrate("The starlit cat opens its mouth to speak, and no sound comes. StarClan keeps its signs from you."));
            return lines;
        }
        Places.Place sign = null;
        for (Places.Place pl : places) {
            if (found.contains(pl.id())) continue;
            if (sign == null || Math.hypot(pl.x() - p.getX(), pl.z() - p.getZ()) < Math.hypot(sign.x() - p.getX(), sign.z() - p.getZ())) sign = pl;
        }
        lines.add(Line.say(speaker, STAR_COLOUR, sign == null ? "You know these lands as few cats do. Walk them well, and teach them to those who come after you."
                : "Here is a sign for you. There is a place your paws have not found: " + sign.name() + ". " + sign.hint() + ". Seek it to the "
                + Kin.direction(p.getX(), p.getZ(), sign.x(), sign.z()) + "."));
        lines.add(Line.narrate("You wake beside the water as the stars fade, rested and whole. StarClan will speak to you again when the moon has turned."));
        return lines;
    }
}
