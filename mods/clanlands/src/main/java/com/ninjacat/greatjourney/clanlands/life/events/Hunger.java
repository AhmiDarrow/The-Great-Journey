package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import com.ninjacat.greatjourney.clanlore.role.Role;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.items.IItemHandler;
import net.snowteb.warriorcats_events.block.entity.FreshkillPileBlockEntity;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Leaf-bare hunger, in Free Mode: through the cold season the Clan lives on what is really in its fresh-kill pile.
 * Once a day the elders, queens and kits eat first, from the pile itself. When it cannot feed them, one of them
 * falls sick and the Clan sends out hunters. Whatever a cat brings home (the hunting patrols, the camp's own hunters,
 * a player filling the pile) is what keeps them well. The day's meal is taken the first time the camp is live that
 * day, so a camp no one visits neither eats nor starves.
 */
public final class Hunger {
    private Hunger() {}

    /** The pieces of prey those who eat first need for a day: half a piece each, more in a hard season. */
    static int need(int mouths, double hardness) {
        return mouths <= 0 ? 0 : (int) Math.ceil(mouths * hardness / 2.0);
    }

    static boolean eatsFirst(WCatEntity c) {
        if (c.getRank() == WCatEntity.Rank.KIT) return true;
        Role r = Roles.role(c);
        return r == Role.ELDER || r == Role.QUEEN;
    }

    static int pieces(IItemHandler pile) {
        int n = 0;
        for (int i = 0; i < pile.getSlots(); i++) n += pile.getStackInSlot(i).getCount();
        return n;
    }

    /** Takes up to {@code wanted} pieces from the pile; returns how many it gave. */
    static int eat(IItemHandler pile, int wanted) {
        int got = 0;
        for (int i = 0; i < pile.getSlots() && got < wanted; i++) got += pile.extractItem(i, wanted - got, false).getCount();
        return got;
    }

    /** Whether going short today makes a cat ill: under Spottedleaf only an empty pile does. */
    static boolean sickens(Teller t, int got, int wanted) {
        return got < wanted && (t != Teller.SPOTTEDLEAF || got == 0);
    }

    /** Every few seconds: each live camp's meal of the day, in leaf-bare. */
    static void everySecond(ServerLevel ow) {
        if (ow.getServer().getTickCount() % 100 != 0) return;
        Teller t = StoryData.active(ow);
        if (t == null || !Seasons.isLeafBare(ow)) return;
        StoryData data = StoryData.get(ow);
        long day = ow.getDayTime() / 24000L + 1;
        for (Clan c : Clan.values()) {
            if (data.number("fed." + c.name()) == day || !Cats.campActive(ow, c) || !Cats.campCatsLoaded(ow, c)) continue;
            FreshkillPileBlockEntity pile = pile(ow, c);
            if (pile == null) continue;
            data.put("fed." + c.name(), day);
            meal(ow, c, t, pile);
        }
    }

    private static void meal(ServerLevel ow, Clan c, Teller t, FreshkillPileBlockEntity pile) {
        List<WCatEntity> cats = Cats.atCamp(ow, c);
        List<WCatEntity> first = new ArrayList<>(cats.stream().filter(Hunger::eatsFirst).toList());
        int wanted = need(first.size(), t.hunger);
        if (wanted == 0) return;
        int got = eat(pile.getItemHandler(), wanted);
        if (got > 0) {
            pile.setChanged();
            ow.sendBlockUpdated(pile.getBlockPos(), pile.getBlockState(), pile.getBlockState(), 3);
        }
        if (got >= wanted) return;
        Info info = Info.of(c);
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate(got == 0 ? "The fresh-kill pile is bare. The elders and queens go to their nests hungry."
                : "There was not enough on the fresh-kill pile to feed every elder, queen and kit."));
        if (sickens(t, got, wanted)) {
            first.removeIf(x -> x.hasDisease(DiseaseTypes.WHITECOUGH) || x.hasDisease(DiseaseTypes.GREENCOUGH));
            if (!first.isEmpty()) {
                WCatEntity weak = first.get(ow.getRandom().nextInt(first.size()));
                weak.addDisease(DiseaseTypes.WHITECOUGH, true);
                lines.add(Line.narrate(Cats.plainName(weak) + ", thin with hunger, has started to cough."));
                EventNews.record(c, "Hunger bit " + info.title + " in leaf-bare, and " + Cats.plainName(weak) + " fell sick.");
            }
        }
        Happenings.sendHunters(ow, c, cats, ow.getRandom());
        lines.add(Line.say(deputy, info.colour, "We need prey on that pile before another day goes by. Every cat who can hunt, hunt!"));
        Announce.tell(ow, c, Cats.campCentre(ow, c), lines);
    }

    private static FreshkillPileBlockEntity pile(ServerLevel ow, Clan c) {
        Landmark camp = Cats.camp(c);
        CampSite site = camp == null ? null : CampSite.of(ow, c, camp);
        if (site == null || site.pile == null || !ow.hasChunk(site.pile.getX() >> 4, site.pile.getZ() >> 4)) return null;
        return ow.getBlockEntity(site.pile) instanceof FreshkillPileBlockEntity pile ? pile : null;
    }

    /** For the journal: what the pile holds against what the day asks, in leaf-bare with the camp near; else null. */
    public static String status(ServerLevel ow, Clan c) {
        Teller t = StoryData.active(ow);
        if (t == null || ClanWorld.current() == null || !Seasons.isLeafBare(ow) || !Cats.campActive(ow, c)) return null;
        FreshkillPileBlockEntity pile = pile(ow, c);
        if (pile == null) return null;
        int wanted = need((int) Cats.atCamp(ow, c).stream().filter(Hunger::eatsFirst).count(), t.hunger);
        return "Leaf-bare: the fresh-kill pile holds " + pieces(pile.getItemHandler()) + "; the elders, queens and kits need " + wanted + " a day.";
    }
}
