package com.ninjacat.greatjourney.clanlands.life.campaign;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.ChunkWait;
import com.ninjacat.greatjourney.clanlands.life.ClanNames;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Kittypets and loners in a Campaign world. They have no Clan, so the story comes to them as it does in the first
 * book: wander into a Clan's land and a patrol finds you, and asks you to join (a kittypet's first patrol is the one
 * that finds Rusty, in ThunderClan's woods by the Twoleg garden). A cat who says "not yet" is asked again another
 * day, by any Clan whose land they cross. If the Clans leave the forest first, the cat is asked to go with them.
 */
final class Outsiders {
    private Outsiders() {}

    /** How far from where they began a kittypet or loner must wander before a patrol notices them. */
    static final double WANDER = 64;
    /** A Clan's patrols ask the same cat again after a day. */
    static final long AGAIN = 24000L;

    /** Once a second for a clanless kittypet or loner whose window is free. */
    static void tick(ServerPlayer p, CampaignData cd, ClanWorld w, CompoundTag d, long now) {
        if (Joining.clanOf(p) != null) {                    // joined some other way: the story picks them up
            Campaign.setChapter(p, Chapter.startFor(cd.act, cd.signGiven, cd.departed, cd.lakeFounded));
            return;
        }
        if (cd.departed && !cd.lakeFounded) {
            if (now - d.getLong("asked") > 2400) {
                d.putLong("asked", now);
                List<Choice> choices = new ArrayList<>();
                for (Info i : Info.values()) choices.add(new Choice(i.title, "greatjourney campaign join " + i.key(), i.colour));
                Dialog.play(p,
                        Line.narrate("Word spreads along the forest's edge: the Clans are leaving, all four together, to find a new home far away."),
                        Line.narrate("A lone cat would not last long here now, with the trees falling. Will you go with them?").withChoices(choices.toArray(Choice[]::new)));
            }
            return;
        }
        if (p.level().dimension() != Level.OVERWORLD) return;
        if (cd.lakeFounded && w.forest.covers(p.getX(), p.getZ())) {
            // a kittypet or loner still in the empty old forest: point them toward where the Clans live now
            if (now - d.getLong("asked") > 6000) {
                d.putLong("asked", now);
                Dialog.subtitle(p, Line.narrate("The forest is empty and torn. The Clans live by a great lake now, far to the north-west, past the mountains."));
            }
            return;
        }
        boolean firstEver = true;
        for (Clan o : Clan.values()) if (Journey.stamped(p, "patrol." + o.name()) != 0) firstEver = false;
        Clan c = patrolFor(p, w, d, now);
        if (c != null) patrol(p, c, firstEver && Journey.start(p) == Journey.Start.KITTYPET && c == Clan.THUNDER);
    }

    /**
     * The Clan whose patrol finds the player now, or null: they have wandered from where they began into that Clan's
     * land, and that Clan has not asked them today. Marks the Clan as having asked.
     */
    static Clan patrolFor(ServerPlayer p, ClanWorld w, CompoundTag d, long now) {
        if (Math.hypot(p.getX() - d.getDouble("startX"), p.getZ() - d.getDouble("startZ")) < WANDER) {
            d.putBoolean("arrived", true);
            return null;
        }
        // not before they have been there at all: while the start loads they still stand where the dream left them
        if (!d.getBoolean("arrived")) return null;
        Clan c = Homeland.territoryAt(w, p.getX(), p.getZ());
        if (c == null) return null;
        String key = "patrol." + c.name();
        long last = Journey.stamped(p, key);
        if (last != 0 && now - last < AGAIN) return null;
        Journey.stamp(p, key, now);
        return c;
    }

    /** Three cats of the Clan step out in front of the player and ask them to join. */
    static void patrol(ServerPlayer p, Clan clan, boolean firstKittypet) {
        ServerLevel ow = p.serverLevel();
        LifeData life = LifeData.get(ow.getServer().overworld());
        UUID id = life.clanId(clan);
        ClanWorld w = ClanWorld.current();
        if (id == null || w == null) return;
        Info info = Info.of(clan);
        RandomSource r = p.getRandom();
        ClanNames names = new ClanNames(ow.getSeed() ^ p.getUUID().getMostSignificantBits() ^ (clan.ordinal() * 0x9E37L) ^ ow.getGameTime());
        List<ClanNames.Name> who = List.of(names.warrior(), names.warrior(), names.apprentice());
        double ahead = Math.toRadians(p.getYRot() + 90);
        double ax = p.getX() + Math.cos(ahead) * 8, az = p.getZ() + Math.sin(ahead) * 8;
        Landmark camp = Homeland.camp(w, clan);
        String lead = who.get(0).full(), second = who.get(1).full(), app = who.get(2).full();
        List<Line> lines = firstKittypet
                ? List.of(Line.narrate("Three cats step out of the bracken, fur bristling."),
                        Line.say(lead, info.colour, "Halt! What is a kittypet doing in " + info.title + " territory?"),
                        Line.say(app, info.colour, "It's not even scared of us. Look how it stands its ground!"),
                        Line.say(second, info.colour, "Brave, for a kittypet. Our leader might like that."),
                        Line.say(lead, info.colour, "Leave your Twoleg nest behind, and live as a warrior with us."))
                : List.of(Line.narrate("A patrol of " + info.title + " cats pads out to meet you, ears pricked."),
                        Line.say(lead, info.colour, "You're on " + info.title + " land, stranger. You smell of " + (Journey.start(p) == Journey.Start.KITTYPET ? "Twolegs" : "no Clan at all") + "."),
                        Line.say(second, info.colour, "Skinny, but strong-looking. " + life.leader(clan) + " is always glad of another warrior."),
                        Line.say(lead, info.colour, "Come back to camp with us, and learn the ways of a Clan cat."));
        // the patrol steps out once the ground ahead is loaded (it nearly always already is: the player is right there),
        // and only then do they speak, each line handing the window its own cat for the portrait
        UUID who0 = p.getUUID();
        ChunkWait.whenLoaded(ow, ax, az, 6, () -> {
            BlockPos centre = BlockPos.containing(camp.x(), p.getY(), camp.z());
            Map<String, Entity> cats = new HashMap<>();
            for (int i = 0; i < who.size(); i++) {
                BlockPos at = BookClans.surface(ow, (int) Math.floor(ax + (i - 1) * 2), (int) Math.floor(az));
                cats.put(who.get(i).full(), BookClans.spawnCat(ow, clan, id, i == 2 ? BookClans.Role.APPRENTICE : BookClans.Role.WARRIOR, who.get(i),
                        r.nextInt(2), at, centre, r));
            }
            ServerPlayer q = ow.getServer().getPlayerList().getPlayer(who0);
            if (q == null) return;
            Joining.offer(q, info, lead, lines.stream().map(l -> l.speaker() == null ? l : l.speakerEntity(cats.get(l.speaker()))).toList());
        });
    }

    /** What the journal asks of a kittypet or loner. */
    static String goal(ServerPlayer p) {
        return Journey.start(p) == Journey.Start.KITTYPET
                ? "Climb your garden fence and explore the woods beyond. Wild cats live there; perhaps a patrol will find you."
                : "Walk your own path, or wander into a Clan's land: its patrols may ask you to join them.";
    }
}
