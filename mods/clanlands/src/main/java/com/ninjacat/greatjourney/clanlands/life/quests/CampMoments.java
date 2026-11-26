package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.gathering.ClanNews;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The quiet things Clan cats do together in camp: sharing tongues, an elder's tale, and sitting vigil for a Clanmate
 * who has gone to StarClan. The tales are the world's own history, told from the Clan's news and the names StarClan
 * keeps, so every world's elders tell different ones.
 */
final class CampMoments {
    private CampMoments() {}

    /** A vigil is sat on the night of the loss, or the day after. */
    static final long VIGIL = 24000L;
    private static final String TONGUES = "tonguesDay", SAT = "vigilFor";

    /** The Clan's own cat most lately lost, if their vigil is still open and this player has not sat it; else null. */
    static LifeData.Fallen vigil(ServerPlayer p, LifeData life, long now) {
        Clan c = Kin.bookClan(p);
        if (c == null) return null;
        List<LifeData.Fallen> all = life.starClan();
        for (int i = all.size() - 1; i >= 0; i--) {
            LifeData.Fallen f = all.get(i);
            if (f.clan() != c) continue;
            return now - f.gameTime() <= VIGIL && !key(f).equals(QuestState.root(p).getString(SAT)) ? f : null;
        }
        return null;
    }

    private static String key(LifeData.Fallen f) {
        return f.name() + "@" + f.gameTime();
    }

    /** "Sit with me a while": what there is to do with this Clanmate. */
    static void sit(ServerPlayer p, WCatEntity cat) {
        ServerLevel ow = p.getServer().overworld();
        LifeData life = LifeData.get(ow);
        Kin.Role role = Kin.role(cat, life);
        int id = cat.getId();
        List<Choice> choices = new ArrayList<>();
        choices.add(Choice.of("Share tongues", "greatjourney quest tongues " + id));
        if (role == Kin.Role.ELDER) choices.add(Choice.of("Tell me a tale", "greatjourney quest tale " + id));
        LifeData.Fallen lost = vigil(p, life, ow.getGameTime());
        if (lost != null) choices.add(Choice.of("Sit vigil for " + lost.name(), "greatjourney quest vigil " + id));
        choices.add(new Choice("Another time", "greatjourney quest bye", 0xAAAAAA));
        Dialog.play(p, Line.say(Kin.name(cat), Kin.colour(p), "There's room here. What's on your mind?").speakerEntity(cat)
                .withChoices(choices.toArray(Choice[]::new)));
    }

    static void tongues(ServerPlayer p, WCatEntity cat) {
        CompoundTag root = QuestState.root(p);
        long day = p.level().getDayTime() / 24000L + 1;
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0, false, false));
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate("You settle beside " + Kin.name(cat) + " and share tongues, grooming the burrs from each other's fur while the camp goes quiet."));
        Ui.play(p, lines);
        // the first time each day is noticed
        if (root.getLong(TONGUES) != day) {
            root.putLong(TONGUES, day);
            Deeds.weigh(p, 1, 0);
        }
    }

    /** An elder's tale: what has happened in this world, in their words. */
    static List<Line> tale(ServerPlayer p, String elder) {
        ServerLevel ow = p.getServer().overworld();
        LifeData life = LifeData.get(ow);
        Clan c = Kin.bookClan(p);
        int colour = Kin.colour(p);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(elder, colour, "A tale? Sit, then. Not on my tail."));
        List<ClanNews.Item> news = c == null ? List.of() : ClanNews.recent(ow, c, 3);
        for (ClanNews.Item n : news) lines.add(Line.say(elder, colour, "I remember it well. " + n.text()));
        List<String> gone = new ArrayList<>();
        List<LifeData.Fallen> all = life.starClan();
        for (int i = all.size() - 1; i >= 0 && gone.size() < 3; i--) if (all.get(i).clan() == c) gone.add(all.get(i).name());
        if (!gone.isEmpty()) {
            lines.add(Line.say(elder, colour, "And look up tonight. " + String.join(", ", gone) + (gone.size() == 1 ? " walks" : " walk")
                    + " in Silverpelt now. Don't you forget the names."));
        }
        if (news.isEmpty() && gone.isEmpty()) {
            lines.add(Line.say(elder, colour, "There is not much to tell of " + (c == null ? "a Clan so young" : BookClans.Info.of(c).title + " by this lake")
                    + " yet. Go and do something worth telling, and come back."));
        }
        return lines;
    }

    static void sitVigil(ServerPlayer p, WCatEntity cat) {
        ServerLevel ow = p.getServer().overworld();
        LifeData.Fallen lost = vigil(p, LifeData.get(ow), ow.getGameTime());
        if (lost == null) return;
        QuestState.root(p).putString(SAT, key(lost));
        Ui.play(p, List.of(
                Line.narrate("You lie down beside " + Kin.name(cat) + " in the middle of the camp and press your nose to " + lost.name()
                        + "'s memory. Silverpelt turns slowly overhead until the sky grows pale."),
                Line.say(Kin.name(cat), Kin.colour(p), "May StarClan light your path, " + lost.name() + ". May you find good hunting.").speakerEntity(cat)));
        Deeds.weigh(p, 3, 1);
    }
}
