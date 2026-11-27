package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.story.Prophecy;
import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The storyteller's hand on the Clans' events ({@link ClanEvents}): how often they come and which, a rest after a
 * loss and trouble after a long calm, and warnings. A dangerous event is first given as an omen, and happens a
 * little later, so that a Clan can make ready. In the Campaign there is no storyteller, and nothing here changes
 * anything.
 */
public final class Director {
    private Director() {}

    private static final Set<ClanEvents.Kind> TROUBLE = EnumSet.of(ClanEvents.Kind.BADGER, ClanEvents.Kind.PREDATOR, ClanEvents.Kind.RAID,
            ClanEvents.Kind.INJURY, ClanEvents.Kind.SICKNESS, ClanEvents.Kind.LOST_KIT, ClanEvents.Kind.DOGS, ClanEvents.Kind.ROGUES,
            ClanEvents.Kind.RATS, ClanEvents.Kind.ADDER, ClanEvents.Kind.SHORTAGE, ClanEvents.Kind.FIRE, ClanEvents.Kind.FLOOD);

    /** A calm longer than this (three days) brings trouble on. */
    static final long LONG_CALM = 24000L * 3;
    /** Midnight's quiet lasts this long before the storm; the storm is this many troubles. */
    static final long MIDNIGHT_CALM = 24000L * 2;
    static final int STORM = 3;
    /** A warning not come true within this long of its time (its camp never visited) is let go. */
    static final long OMEN_WAIT = 12000L;

    static boolean trouble(ClanEvents.Kind k) {
        return TROUBLE.contains(k);
    }

    /** What a warning calls this event, or null for one that comes without warning. */
    static Teller.Danger danger(ClanEvents.Kind k) {
        return switch (k) {
            case BADGER, PREDATOR, DOGS -> Teller.Danger.BEAST;
            case RAID, ROGUES -> Teller.Danger.ENEMY;
            case RATS -> Teller.Danger.PEST;
            case SICKNESS -> Teller.Danger.SICKNESS;
            case FIRE -> Teller.Danger.FIRE;
            case FLOOD -> Teller.Danger.WATER;
            default -> null;
        };
    }

    /** How the pace weighs a trouble just now: less in mourning, more after a long calm. */
    static double pace(Teller t, StoryData data, long now, int strength) {
        double w = 1;
        if (now < data.number("mournUntil")) w = 0.25;
        else if (now - data.number("calmSince") > LONG_CALM) w = 2;
        if (t == Teller.TIGERSTAR) w *= 1 + Math.min(1.0, strength / 200.0);          // the stronger, the more he sends
        if (t == Teller.MIDNIGHT) w *= data.number("burst") > 0 ? 3 : 0.35;
        return w;
    }

    /** The weight of an event kind in this world: {@code base} as it always was, as the storyteller has it now. */
    static int weight(ServerLevel ow, ClanEvents.Kind k, int base) {
        Teller t = StoryData.active(ow);
        if (t == null) return base;
        double w = base * t.weight(k.name());
        if (trouble(k)) w *= pace(t, StoryData.get(ow), ow.getGameTime(), strength(ow));
        // kept in tenths so that a rare event stays possible
        return (int) Math.max(1, Math.round(w * 10));
    }

    /** How strong the strongest cat playing has grown: their standing with their Clan. */
    private static int strength(ServerLevel ow) {
        int best = 0;
        for (ServerPlayer p : ow.getServer().getPlayerList().getPlayers()) {
            best = Math.max(best, com.ninjacat.greatjourney.clanlands.life.quests.QuestState.standing(p));
        }
        return best;
    }

    /** The wait to the next event, from the wait it would have been. */
    static long interval(ServerLevel ow, long ticks) {
        Teller t = StoryData.active(ow);
        if (t == null) return ticks;
        double f = t.interval;
        if (t == Teller.MIDNIGHT) f = StoryData.get(ow).number("burst") > 0 ? 0.35 : 1.4;
        return Math.max(200, Math.round(ticks * f));
    }

    /**
     * How long a Clan rests between its own events, from the rest as configured. It keeps the storyteller's pace like
     * the wait between rolls (a rest left the same for all of them held every one to about an event per rest), and
     * Midnight's storm gives none at all.
     */
    static long rest(ServerLevel ow, long ticks) {
        Teller t = StoryData.active(ow);
        if (t == null) return ticks;
        return t == Teller.MIDNIGHT && StoryData.get(ow).number("burst") > 0 ? 0 : interval(ow, ticks);
    }

    /** Before each roll: Midnight's calm breaks into her storm; a new world starts its calm now. */
    static void beforeRoll(ServerLevel ow) {
        Teller t = StoryData.active(ow);
        if (t == null) return;
        StoryData data = StoryData.get(ow);
        long now = ow.getGameTime();
        if (!data.has("calmSince")) data.put("calmSince", now);
        if (t == Teller.MIDNIGHT && data.number("burst") <= 0 && now - data.number("calmSince") > MIDNIGHT_CALM) {
            data.put("burst", STORM);
            data.put("stormWarned", 0);
        }
    }

    /** A trouble has begun: the calm is over, and a storm has one less to give. */
    static void happened(ServerLevel ow, ClanEvents.Kind k) {
        if (StoryData.active(ow) == null || !trouble(k)) return;
        StoryData data = StoryData.get(ow);
        data.put("calmSince", ow.getGameTime());
        if (data.number("burst") > 0) data.put("burst", data.number("burst") - 1);
    }

    /** A cat has been lost: the storyteller leaves the world in peace a while (all but Tigerstar). */
    static void mourn(ServerLevel ow) {
        Teller t = StoryData.active(ow);
        if (t == null || t.mercy <= 0) return;
        StoryData.get(ow).put("mournUntil", ow.getGameTime() + t.mercy);
    }

    /** Under Spottedleaf no cat dies of anything but old age. */
    static boolean spares(ServerLevel ow, String cause) {
        return StoryData.active(ow) == Teller.SPOTTEDLEAF && !cause.equals("old age");
    }

    // --- omens

    /**
     * Gives a dangerous event as a warning to come true later; false when this event comes without one (or there is no
     * storyteller), for the caller to make it happen now. While a warning waits, another danger is let go rather than
     * left to strike unforetold. Midnight warns once for a whole storm: what she warned of comes first, and the
     * storm's other dangers after it come at once.
     */
    static boolean forewarn(ServerLevel ow, Clan c, ClanEvents.Kind k) {
        Teller t = StoryData.active(ow);
        Teller.Danger d = danger(k);
        StoryData data = StoryData.get(ow);
        if (t == null || d == null) return false;
        boolean storm = t == Teller.MIDNIGHT && data.number("burst") > 0;
        if (!data.part("omen").isEmpty()) return true;
        if (storm) {
            if (data.number("stormWarned") > 0) return false;
            data.put("stormWarned", 1);
        }
        CompoundTag o = new CompoundTag();
        o.putString("clan", c.name());
        o.putString("kind", k.name());
        o.putLong("due", ow.getGameTime() + t.omenLead);
        data.keep("omen", o);
        announceOmen(ow, c, t, d);
        return true;
    }

    private static void announceOmen(ServerLevel ow, Clan c, Teller t, Teller.Danger d) {
        LifeData life = LifeData.get(ow);
        Info info = Info.of(c);
        BlockPos camp = Cats.campCentre(ow, c);
        String healer = Cats.atCamp(ow, c).stream().filter(x -> x.getRank() == WCatEntity.Rank.MEDICINE).map(Cats::plainName).findFirst().orElse("Your medicine cat");
        UUID id = life.clanId(c);
        ClanData.Clan wc = id == null ? null : ClanData.get(ow).getClan(id);
        for (ServerPlayer p : Announce.audience(ow, c, camp)) {
            boolean medicine = wc != null && wc.members.get(p.getUUID()) == ClanData.ClanPlayerRank.MEDICINE;
            Line sign = Line.say(t.title, t.colour, t.omen(d));
            Dialog.subtitle(p, medicine
                    ? List.of(Line.narrate("A sign comes to you, and you alone, " + info.title + "'s medicine cat."), sign)
                    : List.of(Line.narrate(healer + " stiffens, eyes far away. StarClan has sent a sign:"), sign));
        }
    }

    /** A prophecy not come true within this long of its time (its camp never visited) passes by. */
    static final long PROPHECY_WAIT = 24000L;

    /** A prophecy's time has come: what it foretold happens, and a Clan whose cat worked it out is ready for it. */
    static void fulfil(ServerLevel ow) {
        StoryData data = StoryData.get(ow);
        Prophecy pr = Prophecy.of(data);
        // (counted by the day's clock, which a night slept through moves on: "two or three days later")
        long now = ow.getDayTime();
        long longest = com.ninjacat.greatjourney.clanlands.life.quests.Riddles.SOONEST + com.ninjacat.greatjourney.clanlands.life.quests.Riddles.SPREAD;
        if (pr != null && pr.due() - now > longest) {          // the clock was set far back: it keeps its "two or three days"
            Prophecy.keep(data, new Prophecy(pr.clan(), pr.meaning(), now + com.ninjacat.greatjourney.clanlands.life.quests.Riddles.SOONEST, pr.solved()));
            return;
        }
        if (pr == null || now < pr.due() || StoryData.active(ow) == null) return;
        if (now > pr.due() + PROPHECY_WAIT) {
            Prophecy.keep(data, null);
            return;
        }
        ClanEvents.Kind k = ClanEvents.Kind.valueOf(pr.meaning().name());
        if (!ready(ow, pr.clan(), k)) return;
        Info info = Info.of(pr.clan());
        BlockPos camp = Cats.campCentre(ow, pr.clan());
        if (pr.solved() && k == ClanEvents.Kind.SICKNESS) {
            // forewarned of a cough, the Clan has its herbs in and it passes them by
            Prophecy.keep(data, null);
            Announce.tell(ow, pr.clan(), camp, Line.narrate("The prophecy comes true, and passes by: the herb store was full in time. "
                    + info.title + " stays well."));
            EventNews.record(pr.clan(), info.title + " heeded a prophecy, and a sickness passed it by.");
            return;
        }
        // if it cannot happen just now (no cat that fits), it waits for another moment
        if (run(ow, pr.clan(), k)) {
            Prophecy.keep(data, null);
            Announce.tell(ow, pr.clan(), camp, Line.narrate(pr.solved() ? "The prophecy has come true, and " + info.title + " stands ready for it."
                    : "Too late, the words of the prophecy make sense."));
            if (pr.solved()) brace(ow, pr.clan());
            happened(ow, k);
            EventNews.record(pr.clan(), pr.solved() ? "A prophecy came true, and found " + info.title + " ready." : "A prophecy over " + info.title + " came true.");
        }
    }

    /** Once a second: prophecies and warnings whose time has come happen, once their Clan's camp is live. */
    static void everySecond(ServerLevel ow) {
        fulfil(ow);
        StoryData data = StoryData.get(ow);
        CompoundTag o = data.part("omen");
        if (o.isEmpty()) return;
        long now = ow.getGameTime(), due = o.getLong("due");
        if (now < due) return;
        Clan c;
        ClanEvents.Kind k;
        try {
            c = Clan.valueOf(o.getString("clan"));
            k = ClanEvents.Kind.valueOf(o.getString("kind"));
        } catch (IllegalArgumentException ex) {
            data.keep("omen", null);
            return;
        }
        if (now > due + OMEN_WAIT || StoryData.active(ow) == null) {
            data.keep("omen", null);
            return;
        }
        if (!ready(ow, c, k) || !run(ow, c, k)) return;
        data.keep("omen", null);
        happened(ow, k);
    }

    /** Whether a foretold event can happen now: the camp live with its cats loaded, and nothing of its sort under way. */
    private static boolean ready(ServerLevel ow, Clan c, ClanEvents.Kind k) {
        return Cats.campActive(ow, c) && Cats.campCatsLoaded(ow, c) && !(k.major && ClanEvents.activeKind != null)
                && !(Weather.isWeather(k) && Weather.running(ow) != null);
    }

    /** Makes an event happen, weather or otherwise. */
    static boolean run(ServerLevel ow, Clan c, ClanEvents.Kind k) {
        return Weather.isWeather(k) ? Weather.start(ow, c, k, ow.getRandom(), true) : Happenings.run(ow, c, k, ow.getRandom());
    }

    /** Camp cats braced for what they were warned of: harder to hurt, and hitting harder, for a few minutes. */
    static void brace(ServerLevel ow, Clan c) {
        for (WCatEntity cat : Cats.atCamp(ow, c)) {
            cat.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 60 * 4, 1, false, false));
            cat.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 60 * 4, 0, false, false));
        }
    }
}
