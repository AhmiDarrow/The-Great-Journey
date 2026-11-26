package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.BookLooks;
import com.ninjacat.greatjourney.clanlands.life.ClanNames;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.quests.Deeds;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestState;
import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A border skirmish, as in the books: a neighbouring Clan's patrol is found hunting on a cat's own side of the border.
 * The cat may warn them off (they listen to a cat with the weight to be listened to: a deputy or leader, one leading a
 * patrol, a trusted warrior, or one with Clanmates at their side), fight them off, or let them take what they have
 * caught. Doing nothing loses the prey too. Either way both Clans have news of it for the next Gathering. It comes to a
 * player out in their territory, away from camp, since borders lie far from any camp.
 *
 * <p>The trespassers are cats of the story and no Clan's members: they carry no Clan id (a Clan's list would keep each
 * one for ever), cannot be killed (as in the code, a beaten cat runs), and go home when it is over. One skirmish runs
 * at a time, and none outlasts the server.
 */
public final class Skirmish {
    private Skirmish() {}

    public static final String TAG = "greatjourney_skirmish";
    /** Added once it comes to claws, so that a cat's own patrol goes for them ({@code Patrols}). */
    public static final String HOSTILE_TAG = "greatjourney_skirmish_hostile";
    static final long LASTS = 20L * 60 * 3;
    /** How far out of camp a cat must be to come upon trespassers. */
    static final double FROM_CAMP = 60;
    /** The standing at which a warrior's word alone turns a patrol back. */
    public static final int LISTENED_TO = 40;

    /** The one running. */
    static final class Running {
        final Clan home, theirs;
        final UUID player;
        final List<UUID> cats = new ArrayList<>();
        final String speaker;
        long until;
        boolean fighting;

        Running(Clan home, Clan theirs, UUID player, String speaker, long until) {
            this.home = home;
            this.theirs = theirs;
            this.player = player;
            this.speaker = speaker;
            this.until = until;
        }
    }

    static Running now;

    /** The trespassers this player has come upon, while their skirmish runs. */
    public static List<UUID> met(ServerPlayer p) {
        return now == null || !p.getUUID().equals(now.player) ? List.of() : List.copyOf(now.cats);
    }

    static void reset() {
        now = null;
    }

    /** At each event roll: perhaps a patrol is where it should not be, near a player out in their territory. */
    static void roll(ServerLevel ow) {
        Teller t = StoryData.active(ow);
        ClanWorld w = ClanWorld.current();
        if (t == null || w == null || now != null) return;
        RandomSource r = ow.getRandom();
        if (r.nextDouble() >= t.skirmish) return;
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : ow.players()) if (outIn(ow, w, p) != null) out.add(p);
        if (out.isEmpty()) return;
        ServerPlayer p = out.get(r.nextInt(out.size()));
        start(ow, outIn(ow, w, p), p);
    }

    /** The Clan whose land the player is out in (their own, away from its camp), or null. */
    static Clan outIn(ServerLevel ow, ClanWorld w, ServerPlayer p) {
        if (p.isSpectator() || !p.isAlive() || Dialog.busy(p)) return null;
        UUID mine = p.getData(net.snowteb.warriorcats_events.attachments.ModAttachments.PLAYER_WCE_DATA.get()).getCurrentClanUUID();
        Clan c = LifeData.get(ow).clanOf(mine);
        if (c == null || Homeland.territoryAt(w, p.getX(), p.getZ()) != c) return null;
        Landmark camp = Homeland.camp(w, c);
        return Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) > camp.radius() + FROM_CAMP ? c : null;
    }

    /** A neighbour's patrol steps out ahead of the player. */
    static boolean start(ServerLevel ow, Clan home, ServerPlayer p) {
        RandomSource r = ow.getRandom();
        List<Clan> next = Cats.neighbours(home);
        double a = Math.toRadians(p.getYRot() + 90);
        BlockPos at = BookClans.safeSpot(ow, (int) Math.floor(p.getX() + Math.cos(a) * 12), (int) Math.floor(p.getZ() + Math.sin(a) * 12), 3);
        return begin(ow, home, next.get(r.nextInt(next.size())), p, at);
    }

    static boolean begin(ServerLevel ow, Clan home, Clan theirs, ServerPlayer p, BlockPos at) {
        if (now != null) return false;
        RandomSource r = ow.getRandom();
        ClanNames names = new ClanNames(ow.getSeed() ^ ow.getGameTime() ^ theirs.ordinal() * 0x51L);
        List<ClanNames.Name> who = new ArrayList<>(List.of(names.warrior(), names.warrior()));
        if (r.nextBoolean()) who.add(names.apprentice());
        List<WCatEntity> cats = new ArrayList<>();
        // recorded before they step out: a trespasser with no skirmish of its own is turned away as it joins (stale)
        Running s = new Running(home, theirs, p.getUUID(), who.get(0).full(), ow.getGameTime() + LASTS);
        now = s;
        for (int i = 0; i < who.size(); i++) {
            WCatEntity cat = ModEntities.WCAT.get().create(ow);
            if (cat == null) continue;
            cat.moveTo(at.getX() + 0.5 + i - 1, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
            cat.finalizeSpawn(ow, ow.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
            cat.setGender(r.nextInt(2));
            BookLooks.clanCat(cat, theirs);
            Cats.rename(cat, who.get(i).prefix(), who.get(i).full());
            cat.setRank(WCatEntity.Rank.NONE);
            cat.setAgeInMoons(i == 2 ? 9 : 20 + r.nextInt(30));
            cat.mode = WCatEntity.CatMode.WANDER;
            cat.wanderCenter = at;
            cat.addTag(TAG);
            s.cats.add(cat.getUUID());
            if (ow.addFreshEntity(cat)) cats.add(cat);
            else s.cats.remove(cat.getUUID());
        }
        if (cats.isEmpty()) {
            now = null;
            return false;
        }
        play(p, scent(s, cats.get(0)));
        return true;
    }

    // fake players (machines, tests) have no client to show a window to
    private static void play(ServerPlayer p, Line... lines) {
        if (!(p instanceof net.neoforged.neoforge.common.util.FakePlayer)) Dialog.play(p, lines);
    }

    private static void play(ServerPlayer p, List<Line> lines) {
        play(p, lines.toArray(Line[]::new));
    }

    private static void subtitle(ServerPlayer p, Line... lines) {
        if (!(p instanceof net.neoforged.neoforge.common.util.FakePlayer)) Dialog.subtitle(p, lines);
    }

    /** The trespassers as they are found, and the choice of what to do. */
    private static List<Line> scent(Running s, WCatEntity first) {
        Info them = Info.of(s.theirs), us = Info.of(s.home);
        return List.of(
                Line.narrate("A strange scent, strong and fresh. " + them.title + " cats, on " + us.title + "'s side of the border, with prey between their paws."),
                Line.say(s.speaker, them.colour, "This mouse ran over from our side. It's ours by right, and so is whatever followed it.").speakerEntity(first),
                choices());
    }

    private static Line choices() {
        return Line.narrate("What will you do?").withChoices(
                Choice.of("This is our land. Leave it, now.", "greatjourney skirmish warn"),
                new Choice("Drive them off with claws", "greatjourney skirmish fight", 0xFF7755),
                new Choice("Take what you caught and go. This once.", "greatjourney skirmish mercy", 0xAAAAAA));
    }

    /** Whether a warning from this cat is one a patrol would heed. */
    static boolean heeded(ServerLevel ow, ServerPlayer p, Clan home) {
        if (Deeds.commands(p) || Deeds.leadsPatrol(p)) return true;
        if (!Deeds.grown(p)) return false;
        if (QuestState.standing(p) >= LISTENED_TO) return true;
        UUID id = LifeData.get(ow).clanId(home);
        return id != null && ow.getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(16),
                c -> id.equals(c.getClanUUID()) && !c.isBaby()).size() >= 2;
    }

    /** The player's answer: "warn", "fight" or "mercy". */
    static boolean answer(ServerPlayer p, String what) {
        Running s = now;
        ServerLevel ow = p.getServer().overworld();             // where the trespassers are, wherever the answer is typed
        Dialog.clear(p);
        if (s == null || !p.getUUID().equals(s.player)) return false;
        WCatEntity first = first(ow, s);
        Info them = Info.of(s.theirs), us = Info.of(s.home);
        switch (what) {
            case "warn" -> {
                if (!heeded(ow, p, s.home)) {
                    play(p, Line.say(s.speaker, them.colour, "And who's going to make us? You and whose patrol?").speakerEntity(first), choices());
                    return true;
                }
                play(p, Line.say(s.speaker, them.colour, "...Fine. This isn't over. Come on.").speakerEntity(first),
                        Line.narrate("The " + them.title + " patrol backs away over the border, leaving the prey behind."));
                Deeds.weigh(p, 4, 1);
                end(ow, s, Deeds.name(p) + " of " + us.title + " warned a " + them.title + " patrol off " + us.title + "'s land.", false);
            }
            case "fight" -> fight(ow, s, p);
            case "mercy" -> {
                boolean hungry = Seasons.isLeafBare(ow);
                play(p, Line.say(s.speaker, them.colour, hungry ? "...Our kits are starving. I won't forget this."
                                : "Soft, aren't you? Suits us.").speakerEntity(first),
                        Line.narrate("They slip back over the border, and the prey goes with them."));
                // kindness to the starving is the code's own; handing over prey in a full season is only giving in
                Deeds.weigh(p, -3, hungry ? 2 : 0);
                end(ow, s, hungry ? "In the leaf-bare cold, " + us.title + " let a hungry " + them.title + " patrol keep its catch."
                        : "A " + them.title + " patrol took prey from " + us.title + "'s land and was let go.", true);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** It comes to claws: the trespassers go for the player, and Clanmates close by go for them. */
    private static void fight(ServerLevel ow, Running s, ServerPlayer p) {
        if (s.fighting) return;
        s.fighting = true;
        for (UUID id : s.cats) {
            if (!(ow.getEntity(id) instanceof WCatEntity c)) continue;
            c.addTag(HOSTILE_TAG);
            c.setTarget(p);
        }
        WCatEntity first = first(ow, s);
        UUID mine = LifeData.get(ow).clanId(s.home);
        if (mine != null && first != null) {
            // grown warriors only: no apprentice or elder is sent into it
            for (WCatEntity c : ow.getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(16),
                    x -> mine.equals(x.getClanUUID()) && !x.isBaby() && (x.getRank() == WCatEntity.Rank.WARRIOR || x.getRank() == WCatEntity.Rank.DEPUTY)
                            && com.ninjacat.greatjourney.clanlore.role.Roles.role(x) != com.ninjacat.greatjourney.clanlore.role.Role.ELDER)) {
                c.setTarget(first);
            }
        }
        Info them = Info.of(s.theirs);
        subtitle(p, Line.say(s.speaker, them.colour, "Have it your way! " + them.title + ", attack!").speakerEntity(first),
                Line.narrate("Beat them until they run. Claws only: no warrior needs to kill to win."));
    }

    private static WCatEntity first(ServerLevel ow, Running s) {
        for (UUID id : s.cats) if (ow.getEntity(id) instanceof WCatEntity c) return c;
        return null;
    }

    /** Right-clicking a trespasser brings the choice back (a window closed, or a warning that was not heeded). */
    static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p) || !e.getTarget().getTags().contains(TAG)) return;
        if (p.isShiftKeyDown()) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        Running s = now;
        if (s == null || !s.cats.contains(e.getTarget().getUUID()) || Dialog.busy(p) || s.fighting) return;
        if (!p.getUUID().equals(s.player)) {
            subtitle(p, Line.say(s.speaker, Info.of(s.theirs).colour, "We're not talking to you."));
            return;
        }
        play(p, choices());
    }

    /**
     * No cat dies in a border fight: a blow that would kill a trespasser, or any cat a trespasser strikes, leaves them
     * standing. And a player who goes at the trespassers with claws, whatever they chose, has chosen to fight.
     */
    static void beforeDamage(LivingDamageEvent.Pre e) {
        LivingEntity hurt = e.getEntity();
        if (hurt.level().isClientSide()) return;
        Entity by = e.getSource().getEntity();
        boolean trespasser = hurt.getTags().contains(TAG);
        if (!trespasser && !(hurt instanceof WCatEntity && by != null && by.getTags().contains(TAG))) return;
        Running s = now;
        if (trespasser && s != null && by instanceof ServerPlayer p && s.cats.contains(hurt.getUUID())) {
            ServerPlayer mine = p.getUUID().equals(s.player) ? p : p.getServer().getPlayerList().getPlayer(s.player);
            fight(p.getServer().overworld(), s, mine != null ? mine : p);
        }
        float spare = Math.max(0, hurt.getHealth() - 2);
        if (e.getNewDamage() > spare) e.setNewDamage(spare);
    }

    /** Once a second: beaten cats run; with none left to fight the border is held; and a patrol left alone goes home fed. */
    private static final String OWED = "owed.";

    /** Takes from each live camp's pile what trespassers carried off while it was out of reach. */
    private static void collect(ServerLevel ow) {
        StoryData data = StoryData.get(ow);
        for (Clan c : Clan.values()) {
            long owed = data.number(OWED + c.name());
            if (owed <= 0 || ClanWorld.current() == null || !Cats.campLoaded(ow, c)) continue;
            CampSite site = CampSite.of(ow, c, Cats.camp(c));
            if (site == null || site.pile == null || !ow.hasChunk(site.pile.getX() >> 4, site.pile.getZ() >> 4)) continue;
            while (owed > 0 && BookEvents.steal(ow, site.pile)) owed--;
            data.put(OWED + c.name(), 0);                       // a bare pile had no more to lose
        }
    }

    static void everySecond(ServerLevel ow) {
        if (ow.getServer().getTickCount() % 100 == 0) collect(ow);
        if (now != null) step(ow, ow.getServer().getPlayerList().getPlayer(now.player));
    }

    /** {@code p} is the player it came to, or null once they are gone. */
    static void step(ServerLevel ow, ServerPlayer p) {
        Running s = now;
        if (s == null) return;
        if (s.fighting) {
            for (UUID id : List.copyOf(s.cats)) {
                if (!(ow.getEntity(id) instanceof WCatEntity c)) continue;
                if (c.getHealth() > c.getMaxHealth() / 2) {
                    if (p != null && (c.getTarget() == null || !c.getTarget().isAlive())) c.setTarget(p);
                    continue;
                }
                s.cats.remove(id);
                c.discard();
                if (p != null) subtitle(p, Line.narrate(Cats.plainName(c) + " has had enough, and bolts for the border."));
            }
            if (s.cats.isEmpty()) {
                Info us = Info.of(s.home), them = Info.of(s.theirs);
                if (p != null) Deeds.weigh(p, 8, 0);
                end(ow, s, us.title + " drove a " + them.title + " patrol off its land in a fight on the border.", false);
                return;
            }
        }
        boolean lost = s.fighting && (p == null || !p.isAlive());
        if (ow.getGameTime() < s.until && !lost) return;
        Info us = Info.of(s.home), them = Info.of(s.theirs);
        if (p != null) subtitle(p, Line.narrate("The " + them.title + " patrol is gone, and the prey with it."));
        end(ow, s, "A " + them.title + " patrol hunted on " + us.title + "'s land and got away with its catch.", true);
    }

    /** It is over: the trespassers go, both Clans have the news, and prey carried off is missing from the pile. */
    private static void end(ServerLevel ow, Running s, String news, boolean preyLost) {
        now = null;
        for (UUID id : s.cats) {
            Entity e = ow.getEntity(id);
            if (e != null) e.discard();
        }
        if (preyLost) {
            // what they took is what the Clan would have eaten: missed from the pile now, or when it is next there to see
            StoryData data = StoryData.get(ow);
            data.put(OWED + s.home.name(), data.number(OWED + s.home.name()) + 1);
            collect(ow);
        }
        EventNews.record(s.home, news);
        EventNews.record(s.theirs, news);
    }

    /** A trespasser loading with no skirmish of its own (after a restart) has long since gone home. */
    static boolean stale(Entity e) {
        return e.getTags().contains(TAG) && (now == null || !now.cats.contains(e.getUUID()));
    }
}
