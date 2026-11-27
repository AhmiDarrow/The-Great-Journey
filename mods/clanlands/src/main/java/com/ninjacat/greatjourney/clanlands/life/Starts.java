package com.ninjacat.greatjourney.clanlands.life;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.landmark.CampPlan;
import com.ninjacat.greatjourney.clanlands.layout.Geo;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.life.rpg.Journals;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The three ways to begin, chosen on waking from the StarClan dream: a kittypet in a Twoleg garden at the edge of
 * the ThunderClan woods (a patrol finds you), an apprentice of a Clan of your choice, or a loner by the lake.
 * Clanless cats who walk into a camp are offered a place in that Clan.
 */
public final class Starts {
    private Starts() {}

    public static void offerChoices(ServerPlayer p) {
        Journey.setStage(p, Journey.Stage.CHOOSING);
        Journey.stamp(p, "choose.asked", p.serverLevel().getServer().overworld().getGameTime());
        if (com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.offerStarts(p)) return;      // the Campaign's own way
        Dialog.play(p,
                Line.narrate("The stars fade. You wake in the world of the Clans. How will your story begin?"),
                choiceLine("Choose one:"));
    }

    private static Line choiceLine(String text) {
        return Line.narrate(text).withChoices(
                Choice.of("A kittypet at the forest edge", "greatjourney start kittypet"),
                Choice.of("Join a Clan now", "greatjourney start clan"),
                Choice.of("A loner by the lake", "greatjourney start loner"));
    }

    public static void chooseClan(ServerPlayer p) {
        List<Choice> c = new ArrayList<>();
        for (Info i : Info.values()) c.add(new Choice(i.title, "greatjourney start clan " + i.key(), i.colour));
        Dialog.play(p, Line.narrate("Which Clan will you join?").withChoices(c.toArray(Choice[]::new)));
    }

    public static boolean begin(ServerPlayer p, Journey.Start start, Info clan) {
        ClanWorld w = ClanWorld.current();
        if (w == null || Journey.stage(p) != Journey.Stage.CHOOSING) return false;
        ServerLevel ow = p.serverLevel().getServer().overworld();
        if (Mode.of(ow) == Mode.CAMPAIGN) return com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.beginStart(p, start, clan);
        Dialog.clear(p);
        if (start == Journey.Start.CLAN && clan == null) return false;
        markArriving(p);
        switch (start) {
            case KITTYPET -> {
                Landmark home = w.lake.kittypetHome();
                arrive(p, ow, home.x() + 2, home.z() + 7, q -> Dialog.play(q,
                        Line.narrate("You are a kittypet, warm and well fed in your Twoleg garden."),
                        Line.narrate("Beyond the fence the woods rustle with strange scents. Wild cats live out there, they say..."),
                        seekLine("Four Clans of wild cats live around the lake. Which Clan's cats will you seek? Or climb the fence and explore when you are ready.",
                                "None yet: I'll explore", null)));
            }
            case CLAN -> {
                if (clan == null) return false;
                Landmark camp = w.lake.camp(clan.clan);
                Journey.setStart(p, start);
                Journey.setStage(p, Journey.Stage.PLAYING);
                arrive(p, ow, camp.x() + 1, camp.z() + 1, q -> Joining.join(q, clan));
                giveGuide(p);
                return true;
            }
            case LONER -> {
                double[] s = w.lake.lonerSpot();
                arrive(p, ow, s[0], s[1], q -> Dialog.play(q,
                        Line.narrate("You are a loner, belonging to no one. The great lake shines before you."),
                        seekLine("Four Clans live around these shores. Which Clan's camp will you seek? Or visit their camps when you wish, or walk your own path.",
                                "None yet: I'll walk my own path", null)));
            }
            default -> {
                return false;
            }
        }
        Journey.setStart(p, start);
        Journey.setStage(p, Journey.Stage.PLAYING);
        giveGuide(p);
        return true;
    }

    private static final String ARRIVING = "greatjourney.arriving";

    /** A start is chosen and the cat is on the way to it, until {@link #arrive} sets them down. */
    public static void markArriving(ServerPlayer p) {
        p.getPersistentData().putBoolean(ARRIVING, true);
    }

    /** A cat who left before the land of their start had loaded was never set down there: they are asked again. */
    public static void loggedIn(ServerPlayer p) {
        if (!p.getPersistentData().getBoolean(ARRIVING)) return;
        p.getPersistentData().remove(ARRIVING);
        Journey.setStage(p, Journey.Stage.CHOOSING);
    }

    /** How long after arriving the dialogue waits, so the camp has drawn on the client when the window opens. */
    static final int ARRIVAL_HOLD = 40;

    /**
     * Takes the player to x, z (on the ground, with their respawn point there) once the land there has loaded, then
     * runs {@code then} with them. The land loads without stalling the server (see {@link ChunkWait}); a player who
     * logged out meanwhile is looked up again by id, or skipped.
     */
    public static void arrive(ServerPlayer p, ServerLevel ow, double x, double z, java.util.function.Consumer<ServerPlayer> then) {
        UUID id = p.getUUID();
        // a far start can take a while to load; say something, so the choice does not seem to have done nothing
        if (Math.hypot(p.getX() - x, p.getZ() - z) > 64)
            Dialog.subtitle(p, Line.narrate("You set off through the trees toward your new home..."));
        ChunkWait.whenLoaded(ow, x, z, 24, () -> {
            ServerPlayer q = ow.getServer().getPlayerList().getPlayer(id);
            if (q == null) return;
            BlockPos at = BookClans.safeSpot(ow, (int) Math.floor(x), (int) Math.floor(z), 6);   // firm, open ground: never in a rock or a wall
            q.fallDistance = 0;
            q.teleportTo(ow, at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, q.getYRot(), 0);
            q.setRespawnPosition(Level.OVERWORLD, at, 0, true, false);
            Dialog.hold(q, ARRIVAL_HOLD);               // the client draws the land and the cats before any window opens
            q.getPersistentData().remove(ARRIVING);
            if (then != null) then.accept(q);
        });
    }

    /** The things a cat always carries from the start of their story: StarClan's Guidance, the journal, claws and whiskers. */
    public static void giveGuide(ServerPlayer p) {
        Keepsakes.ensure(p);
    }

    /** A dismissed or lost choice (window closed, logged out) is asked again after this long. */
    private static final long ASK_AGAIN = 20L * 30;
    /** A camp invites the same clanless cat again after a day (they may have said "Not yet"). */
    static final long INVITE_AGAIN = 24000L;

    /** Once a second: the start choice until it is made, patrols that find kittypets, camps that invite visitors. */
    public static void tick(MinecraftServer server) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return;
        long now = server.overworld().getGameTime();
        boolean campaign = Mode.of(server) == Mode.CAMPAIGN;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Journey.Stage stage = Journey.stage(p);
            if (stage == Journey.Stage.CHOOSING) {
                if (!Dialog.busy(p) && now - Journey.stamped(p, "choose.asked") > ASK_AGAIN) {
                    Journey.stamp(p, "choose.asked", now);
                    Dialog.play(p, campaign ? com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.startLine(p, "Your story is waiting. How will it begin?")
                            : choiceLine("Your story is waiting. How will it begin?"));
                }
                continue;
            }
            if (campaign) continue;                     // the Campaign finds its kittypets and loners its own way
            if (p.level().dimension() != Level.OVERWORLD || stage != Journey.Stage.PLAYING) continue;
            if (Joining.clanOf(p) != null || Dialog.busy(p)) continue;
            if (Lead.of(p) != null) continue;           // a guide is taking them to a camp, and hands them over there
            boolean invited = false;
            for (Clan c : Clan.values()) {
                Landmark camp = w.lake.camp(c);
                String key = "visit." + c.name();
                long last = Journey.stamped(p, key);
                if (Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) < camp.radius() + 6 && (last == 0 || now - last > INVITE_AGAIN)) {
                    Journey.stamp(p, key, now);
                    if (seeking(p) == Info.of(c)) setSeeking(p, null);     // found it on their own
                    visitCamp(p, Info.of(c), last != 0);
                    invited = true;
                    break;
                }
            }
            if (invited) continue;
            Info seek = seeking(p);
            if (seek != null) {
                guideIfDue(p, seek, now);               // a cat who has chosen is guided there, not ambushed on the way
                if ((now / 20) % 10 == 0) Journals.push(p);                 // the journal's "which way" keeps up with them
                continue;
            }
            if (now - Journey.stamped(p, "seek.asked") > INVITE_AGAIN) {
                // now and then a clanless cat is asked again: the Clans are easier to find with one of their cats along
                Journey.stamp(p, "seek.asked", now);
                if (Journey.stamped(p, "seek.started") != 0) {
                    Dialog.play(p, seekLine("Another day passes without a Clan. Will you seek one of them out?", "Not yet", null));
                    continue;
                }
                Journey.stamp(p, "seek.started", now);
            }
            if (Journey.start(p) == Journey.Start.KITTYPET) {
                Sample s = w.sample(p.getX(), p.getZ());
                // the garden itself backs onto ThunderClan's woods: the patrol waits until the kittypet has climbed
                // the fence and gone exploring (the playtest harness saw it pounce the moment the kittypet arrived)
                Landmark home = w.lake.kittypetHome();
                // and only once they have been home at all: the start is chosen where the dream left them, and while the
                // garden loads they are still standing out there (often in ThunderClan's woods), far from any garden
                boolean away = Math.hypot(p.getX() - home.x(), p.getZ() - home.z()) > 48;
                if (!away && Journey.stamped(p, "kittypet.home") == 0) Journey.stamp(p, "kittypet.home", now);
                boolean exploring = away && Journey.stamped(p, "kittypet.home") != 0;
                if (exploring && s.zone() == Zone.THUNDERCLAN_FOREST && s.weight() > 0.9 && Journey.once(p, "patrol.thunder")) {
                    patrolFinds(p, Info.THUNDER);
                }
            } else if (Journey.start(p) == Journey.Start.LONER) {
                lonerFound(p, w, now);
            }
        }
    }

    /** How far from the lake shore where a loner begins they must wander before a patrol notices them. */
    static final double LONER_WANDER = 64;

    /**
     * A loner who wanders from the shore into a Clan's land is found by one of its patrols and asked to join, as a
     * kittypet is in ThunderClan's woods (Kiki's loner wandered for an age and was never asked). Each Clan asks once
     * a day.
     */
    private static void lonerFound(ServerPlayer p, ClanWorld w, long now) {
        double[] spot = w.lake.lonerSpot();
        boolean away = Math.hypot(p.getX() - spot[0], p.getZ() - spot[1]) > LONER_WANDER;
        // only once they have been at the shore at all: while it loads they still stand where the dream left them
        if (!away && Journey.stamped(p, "loner.shore") == 0) Journey.stamp(p, "loner.shore", now);
        if (!away || Journey.stamped(p, "loner.shore") == 0) return;
        Clan c = Homeland.territoryAt(w, p.getX(), p.getZ());
        if (c == null) return;
        String key = "patrol." + c.name();
        long last = Journey.stamped(p, key);
        if (last != 0 && now - last < INVITE_AGAIN) return;
        Journey.stamp(p, key, now);
        patrolFinds(p, Info.of(c));
    }

    /**
     * Once a second for a clanless cat seeking a Clan, in either mode: a guide comes for them, or, if they found the
     * camp on their own, the camp takes them in.
     */
    public static void guideTick(ServerPlayer p, Info seek, long now) {
        if (Lead.of(p) != null || Dialog.busy(p)) return;
        Landmark camp = Homeland.camp(ClanWorld.current(), seek.clan);
        if (Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) < camp.radius() + 6) {
            String key = "visit." + seek.clan.name();
            long last = Journey.stamped(p, key);
            if (last == 0 || now - last > INVITE_AGAIN) {
                Journey.stamp(p, key, now);
                setSeeking(p, null);
                visitCamp(p, seek, last != 0);
            }
            return;
        }
        guideIfDue(p, seek, now);
        if ((now / 20) % 10 == 0) Journals.push(p);                 // the journal's "which way" keeps up with them
    }

    /** No longer seeking a Clan (the Clans set off on their journey): any guide is let go. */
    public static void clearSeek(ServerPlayer p) {
        if (seeking(p) == null) return;
        Lead.stop(p);
        setSeeking(p, null);
    }

    private static void visitCamp(ServerPlayer p, Info clan, boolean again) {
        String host = speakerIn(p, clan, WCatEntity.Rank.DEPUTY);
        Joining.offer(p, clan, host, again
                ? List.of(Line.say(host, clan.colour, "You again. You keep finding your way back to our camp, don't you?"),
                        Line.say(host, clan.colour, "The offer still stands. " + clan.title + " never turns away a cat with a brave heart."))
                : List.of(Line.say(host, clan.colour, "A stranger in our camp? Easy, everyone. I can smell no harm on this one."),
                        Line.say(host, clan.colour, clan.title + " could use another pair of sharp claws.")));
    }

    /** A ThunderClan patrol catches a kittypet in the woods, the way the old stories begin. */
    private static void patrolFinds(ServerPlayer p, Info clan) {
        ServerLevel ow = p.serverLevel();
        LifeData life = LifeData.get(ow);
        UUID id = life.clanId(clan.clan);
        if (id == null) return;
        RandomSource r = p.getRandom();
        ClanNames names = new ClanNames(ow.getSeed() ^ p.getUUID().getMostSignificantBits());
        ClanWorld w = ClanWorld.current();
        Landmark camp = w.lake.camp(clan.clan);
        BlockPos centre = BookClans.surface(ow, (int) camp.x(), (int) camp.z());
        List<String> who = new ArrayList<>();
        List<WCatEntity> cats = new ArrayList<>();
        double ahead = Math.toRadians(p.getYRot() + 90);
        for (int i = 0; i < 3; i++) {
            ClanNames.Name n = i == 2 ? names.apprentice() : names.warrior();
            int x = (int) (p.getX() + Math.cos(ahead) * 8 + (i - 1) * 2), z = (int) (p.getZ() + Math.sin(ahead) * 8);
            BlockPos at = BookClans.safeSpot(ow, x, z, 3);                     // on the ground, not up in a tree
            cats.add(BookClans.spawnCat(ow, clan.clan, id, i == 2 ? BookClans.Role.APPRENTICE : BookClans.Role.WARRIOR, n,
                    r.nextInt(2), at, centre, r));
            who.add(n.full());
        }
        // Joining.offer's invitation, with a third answer: a cat who wants another Clan is shown the way there
        Journey.offer(p, clan.key(), p.level().getGameTime() + 20L * 60 * 10);
        if (Journey.start(p) == Journey.Start.LONER) {
            Dialog.play(p,
                    Line.narrate("Three cats step out of the undergrowth, fur bristling."),
                    Line.say(who.get(0), clan.colour, "Halt! You're on " + clan.title + " land, stranger. You smell of no Clan at all.").speakerEntity(cats.get(0)),
                    Line.say(who.get(2), clan.colour, "A rogue? Should we chase it off?").speakerEntity(cats.get(2)),
                    Line.say(who.get(1), clan.colour, "No rogue would stand and face us like that. Our leader might want to meet this one.").speakerEntity(cats.get(1)),
                    Line.say(who.get(0), clan.colour, "Living alone is hard, stranger. A Clan hunts together, and no cat of ours goes hungry.").speakerEntity(cats.get(0)),
                    joinOrSeek(Line.say(who.get(0), clan.colour, "Will you join " + clan.title + "?").speakerEntity(cats.get(0)), clan));
            return;
        }
        Dialog.play(p,
                Line.narrate("Three cats step out of the bracken, fur bristling."),
                Line.say(who.get(0), clan.colour, "Halt! What is a kittypet doing in " + clan.title + " territory?").speakerEntity(cats.get(0)),
                Line.say(who.get(2), clan.colour, "It's not even scared of us. Look how it stands its ground!").speakerEntity(cats.get(2)),
                Line.say(who.get(1), clan.colour, "Brave, for a kittypet. Our leader might like that.").speakerEntity(cats.get(1)),
                Line.say(who.get(0), clan.colour, "Leave your Twoleg nest behind, and live as a warrior with us.").speakerEntity(cats.get(0)),
                joinOrSeek(Line.say(who.get(0), clan.colour, "Will you join " + clan.title + "?").speakerEntity(cats.get(0)), clan));
    }

    /** The patrol's question: join this Clan, look for another one (a cat of that Clan comes to guide you), or not yet. */
    static Line joinOrSeek(Line question, Info clan) {
        return question.withChoices(
                Choice.of("Join " + clan.title, "greatjourney join " + clan.key()),
                new Choice("I seek another Clan", "greatjourney seek other " + clan.key(), 0xC8B88A),
                new Choice("Not yet", "greatjourney notyet", 0xAAAAAA));
    }

    // --- seeking a Clan: a kittypet or loner chooses one, and a cat of that Clan comes to show them the way

    private static final String SEEK = "greatjourney_seek";
    /** A guide who was turned down, or lost its cat on the way, is sent again after this long. */
    static final long GUIDE_AGAIN = 20L * 60 * 3;

    /** "Which Clan's cats will you seek?": one button per Clan (but {@code except}), and one to go on alone for now. */
    public static Line seekLine(String text, String alone, Info except) {
        List<Choice> c = new ArrayList<>();
        for (Info i : Info.values()) if (i != except) c.add(new Choice(i.title, "greatjourney seek " + i.key(), i.colour));
        c.add(new Choice(alone, "greatjourney seek none", 0xAAAAAA));
        return Line.narrate(text).withChoices(c.toArray(Choice[]::new));
    }

    private static CompoundTag seekData(ServerPlayer p) {
        CompoundTag persisted = p.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        if (!p.getPersistentData().contains(ServerPlayer.PERSISTED_NBT_TAG)) p.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        CompoundTag mine = persisted.getCompound(SEEK);
        if (!persisted.contains(SEEK)) persisted.put(SEEK, mine);
        return mine;
    }

    /** The Clan whose camp the player is looking for, or null. */
    public static Info seeking(ServerPlayer p) {
        String key = seekData(p).getString("clan");
        return key.isEmpty() ? null : Info.byKey(key);
    }

    static void setSeeking(ServerPlayer p, Info clan) {
        CompoundTag d = seekData(p);
        for (String k : List.copyOf(d.getAllKeys())) d.remove(k);
        if (clan != null) d.putString("clan", clan.key());
    }

    /**
     * A clanless cat past the start, the one who may seek a Clan: in Free Mode, or in a Campaign world a kittypet or
     * loner while the Clans have camps to go to (not while they are away on the Great Journey).
     */
    static boolean canSeek(ServerPlayer p) {
        if (ClanWorld.current() == null || Journey.stage(p) != Journey.Stage.PLAYING || Joining.clanOf(p) != null
                || p.level().dimension() != Level.OVERWORLD) return false;
        return Mode.of(p.serverLevel().getServer()) == Mode.FREE || com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.maySeek(p);
    }

    /** /greatjourney seek ...: what the choices run (as the player). */
    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("seek").executes(c -> askSeek(c.getSource().getPlayerOrException(), null) ? 1 : 0)
                .then(Commands.literal("none").executes(c -> {
                    stopSeeking(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("follow").executes(c -> follow(c.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("wait").executes(c -> {
                    notNow(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("other").then(Commands.argument("clan", StringArgumentType.word()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Journey.clearOffer(p);
                    return askSeek(p, Info.byKey(StringArgumentType.getString(c, "clan"))) ? 1 : 0;
                })))
                .then(Commands.argument("clan", StringArgumentType.word()).executes(c ->
                        seek(c.getSource().getPlayerOrException(), Info.byKey(StringArgumentType.getString(c, "clan"))) ? 1 : 0)));
    }

    static boolean askSeek(ServerPlayer p, Info except) {
        if (!canSeek(p)) {
            p.sendSystemMessage(Component.literal(Joining.clanOf(p) != null ? "You already belong to a Clan." : "You cannot seek a Clan right now."));
            return false;
        }
        Journey.stamp(p, "seek.asked", p.level().getGameTime());
        Dialog.play(p, seekLine(except == null ? "Which Clan's cats will you seek?" : "Which Clan will you seek instead?", "None yet", except));
        return true;
    }

    /** Hurt lately, burning or falling: no cat is whisked away from that (by a seek or a tour). */
    public static boolean inDanger(ServerPlayer p) {
        return p.isOnFire() || p.fallDistance > 3 || p.hurtTime > 0
                || p.getLastHurtByMob() != null && p.tickCount - p.getLastHurtByMobTimestamp() < 200;
    }

    /** Between one long way to a camp and the next. */
    private static final long WENT_AGAIN = 20L * 60;

    /** The player has chosen a Clan: a cat of that Clan will find them (see {@link #guideIfDue}). */
    public static boolean seek(ServerPlayer p, Info clan) {
        if (clan == null || !canSeek(p)) return false;
        long now = p.level().getGameTime(), went = Journey.stamped(p, "seek.went");
        if (inDanger(p) || went != 0 && now - went < WENT_AGAIN) {
            Dialog.subtitle(p, Line.narrate(inDanger(p) ? "Not now: see to your own safety first." : "You have only just set out. Catch your breath first."));
            return false;
        }
        if (seeking(p) != clan) {
            Lead.stop(p);
            WCatEntity old = guideOf(p);
            if (old != null) sendHome(old);
            setSeeking(p, clan);
        }
        Journey.stamp(p, "seek.went", now);
        Journey.stamp(p, "seek.asked", p.level().getGameTime());
        Journals.push(p);
        toCamp(p, clan);
        return true;
    }

    /** How far outside the way in a cat seeking a Clan arrives. */
    static final double ARRIVE_OUT = 12;

    /**
     * The long way to the camp is skipped: the cat sets out and arrives just outside the camp's way in, where one of
     * its cats meets them and takes them the last few steps in. The guided walk from the far side of the lake was a
     * trek of up to three kilometres (the owner asked for just the camp, 2026-10-18).
     */
    static void toCamp(ServerPlayer p, Info clan) {
        ClanWorld w = ClanWorld.current();
        Landmark camp = Homeland.camp(w, clan.clan);
        List<double[]> in = wayIn(w, camp);
        double[] gate = in.get(0);
        double dx = gate[0] - camp.x(), dz = gate[1] - camp.z(), len = Math.max(1, Math.hypot(dx, dz));
        double x = gate[0] + dx / len * ARRIVE_OUT, z = gate[1] + dz / len * ARRIVE_OUT;
        Lead.stop(p);
        Dialog.play(p, Line.narrate("You set your heart on " + clan.title + ", and follow the scent of its land for a long way..."));
        arrive(p, p.serverLevel().getServer().overworld(), x, z, q -> {
            if (seeking(q) != clan) return;
            seekData(q).putLong("sent", q.level().getGameTime());
            Dialog.subtitle(q, Line.narrate(clan.title + " scent is everywhere here. Their camp must be close."));
            sendGuide(q, clan, false);
        });
    }

    private static void stopSeeking(ServerPlayer p) {
        Journey.stamp(p, "seek.asked", p.level().getGameTime());
        if (seeking(p) == null) {
            Dialog.play(p, Line.narrate("You will find your own way, for now."));
            return;
        }
        Lead.stop(p);
        setSeeking(p, null);
        Dialog.play(p, Line.narrate("You decide to find your own way, for now."));
        Journals.push(p);
    }

    /** Sends the player a guide, unless one is with them, they are nearly at the camp, or one was sent a moment ago. */
    private static void guideIfDue(ServerPlayer p, Info clan, long now) {
        Landmark camp = Homeland.camp(ClanWorld.current(), clan.clan);
        if (Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) < camp.radius() + 40) return;   // the camp itself invites them
        long sent = seekData(p).getLong("sent");
        if (sent != 0 && now - sent < GUIDE_AGAIN) return;
        seekData(p).putLong("sent", now);
        sendGuide(p, clan, sent != 0);
    }

    /** The player's guide, if it is still about (loaded, alive and near). */
    private static WCatEntity guideOf(ServerPlayer p) {
        CompoundTag d = seekData(p);
        if (!d.hasUUID("guide")) return null;
        return p.serverLevel().getEntity(d.getUUID("guide")) instanceof WCatEntity c && c.isAlive() && c.distanceTo(p) < 48 ? c : null;
    }

    /**
     * A warrior of the chosen Clan comes out of the undergrowth to meet the player and offers to take them to camp. It
     * is a new cat of that Clan, homed in its camp (the camp itself is far off and not loaded).
     */
    static WCatEntity sendGuide(ServerPlayer p, Info clan, boolean again) {
        WCatEntity guide = guideOf(p);
        if (guide == null) {
            ServerLevel ow = p.serverLevel();
            // the last guide, left behind when the player walked off: it goes home, rather than a new one joining it
            CompoundTag d = seekData(p);
            if (d.hasUUID("guide") && ow.getEntity(d.getUUID("guide")) instanceof WCatEntity old) sendHome(old);
            UUID id = LifeData.get(ow).clanId(clan.clan);
            if (id == null) return null;
            Landmark camp = Homeland.camp(ClanWorld.current(), clan.clan);
            double dx = camp.x() - p.getX(), dz = camp.z() - p.getZ(), len = Math.max(1, Math.hypot(dx, dz));
            BlockPos at = BookClans.safeSpot(ow, (int) Math.floor(p.getX() + dx / len * 7), (int) Math.floor(p.getZ() + dz / len * 7), 4);
            BlockPos home = new BlockPos((int) Math.floor(camp.x()), at.getY(), (int) Math.floor(camp.z()));
            RandomSource r = p.getRandom();
            ClanNames.Name n = new ClanNames(ow.getSeed() ^ p.getUUID().getLeastSignificantBits() ^ (clan.ordinal() + 1) * 0x51ED27L ^ p.level().getGameTime()).warrior();
            guide = BookClans.spawnCat(ow, clan.clan, id, BookClans.Role.WARRIOR, n, r.nextInt(2), at, home, r);
            if (guide == null) return null;
            guide.setHomePosition(home);                   // spawned where it meets the player; it belongs in its camp
            guide.wanderCenter = at;
            guide.addTag(Lead.GUIDE_TAG);
        }
        seekData(p).putUUID("guide", guide.getUUID());
        String name = plain(guide);
        boolean kittypet = Journey.start(p) == Journey.Start.KITTYPET;
        List<Line> lines = new ArrayList<>();
        if (again) {
            lines.add(Line.say(name, clan.colour, "There you are! Still looking for " + clan.title + "'s camp? Stay close to me this time.").speakerEntity(guide));
        } else {
            lines.add(Line.narrate(kittypet ? "A lean cat drops down from the fence beside you, smelling of " + clan.land + "."
                    : "A cat steps out of the undergrowth and stops, nose working. It smells of " + clan.land + "."));
            lines.add(Line.say(name, clan.colour, (kittypet ? "You're the kittypet who keeps staring at the woods. " : "You smell of no Clan at all. ")
                    + "I'm " + name + " of " + clan.title + ".").speakerEntity(guide));
            lines.add(Line.say(name, clan.colour, "If it's " + clan.title + " you're looking for, I'll take you to our camp. Our deputy will want a look at you.").speakerEntity(guide));
        }
        lines.add(Line.say(name, clan.colour, "Keep up, and don't wander off.").speakerEntity(guide).withChoices(
                Choice.of("Lead the way", "greatjourney seek follow"),
                new Choice("Not now", "greatjourney seek wait", 0xAAAAAA)));
        Dialog.play(p, lines);
        return guide;
    }

    /** "Lead the way": the guide sets off for its camp with the player. */
    static boolean follow(ServerPlayer p) {
        Info clan = seeking(p);
        WCatEntity guide = clan == null || !canSeek(p) ? null : guideOf(p);
        if (guide == null) {
            if (clan != null) seekData(p).putLong("sent", 0);              // it has gone: another comes when the player is free
            return false;
        }
        Lead lead = Lead.start(p, guide, guideStops(p, clan), (l, how) -> guideEnded(l, how, clan));
        if (lead == null) return false;
        String name = plain(guide);
        lead.whenWaiting(l -> Dialog.subtitle(l.player(), Line.say(name, clan.colour, switch (l.player().getRandom().nextInt(3)) {
            case 0 -> "Keep up! The camp won't come to you.";
            case 1 -> "This way! Follow my tail.";
            default -> "Are you coming or not?";
        }).speakerEntity(l.cat())));
        Dialog.subtitle(p, Line.say(name, clan.colour, "This way. Stay close!").speakerEntity(guide));
        Journals.push(p);
        return true;
    }

    /** "Not now": the guide slips away; another comes in a while, unless the player stops seeking. */
    private static void notNow(ServerPlayer p) {
        WCatEntity guide = guideOf(p);
        Info clan = seeking(p);
        seekData(p).putLong("sent", p.level().getGameTime());
        if (guide != null && clan != null) {
            Dialog.play(p, Line.say(plain(guide), clan.colour, "Suit yourself. We'll find you again when you're ready.").speakerEntity(guide),
                    Line.narrate(plain(guide) + " slips away into the undergrowth."));
            sendHome(guide);
        }
    }

    /** A guide the player no longer needs goes home: gone from here, and from its Clan's list. */
    private static void sendHome(WCatEntity guide) {
        if (!guide.getTags().contains(Lead.GUIDE_TAG) || !(guide.level() instanceof ServerLevel sl)) return;
        net.snowteb.warriorcats_events.clan.ClanData.get(sl.getServer().overworld()).removeClanCatFromAnyClan(guide);
        guide.discard();
    }

    /**
     * The guide's route: round the lake (or Fourtrees) rather than across it, to the camp's way in, then into the
     * middle of the camp. Crossing into its own Clan's land, the guide says so.
     */
    static List<Lead.Stop> guideStops(ServerPlayer p, Info clan) {
        ClanWorld w = ClanWorld.current();
        Landmark camp = Homeland.camp(w, clan.clan);
        List<double[]> way = new ArrayList<>(wayRound(w, p.getX(), p.getZ(), camp.x(), camp.z()));
        List<double[]> in = wayIn(w, camp);
        List<Lead.Stop> stops = new ArrayList<>();
        boolean home = Homeland.territoryAt(w, p.getX(), p.getZ()) == clan.clan;
        for (double[] pt : way) {
            boolean ours = !home && Homeland.territoryAt(w, pt[0], pt[1]) == clan.clan;
            home |= ours;
            stops.add(new Lead.Stop(pt[0], pt[1], 12, ours ? l -> Dialog.subtitle(l.player(), Line.say(plain(l.cat()), clan.colour,
                    "Smell that? " + clan.title + " scent. We're on our own land now.").speakerEntity(l.cat())) : null));
        }
        for (int i = 0; i < in.size(); i++) {
            double[] pt = in.get(i);
            boolean last = i == in.size() - 1;
            stops.add(new Lead.Stop(pt[0], pt[1], last ? Math.min(camp.radius(), 14) : 8, last || in.size() < 2 ? null
                    : l -> Dialog.subtitle(l.player(), Line.say(plain(l.cat()), clan.colour,
                    "This is the way into camp. Mind your manners in there.").speakerEntity(l.cat()))));
        }
        return stops;
    }

    /** At the camp the guide hands the player over to the deputy's invitation; a guide who lost its cat goes home. */
    private static void guideEnded(Lead lead, Lead.End how, Info clan) {
        ServerPlayer p = lead.player();
        WCatEntity guide = lead.cat();
        String name = plain(guide);
        if (how == Lead.End.DONE) {
            guide.removeTag(Lead.GUIDE_TAG);                // it lives in its camp now
            BlockPos centre = BookClans.surface(p.serverLevel(), guide.getBlockX(), guide.getBlockZ());
            guide.wanderCenter = centre;
            guide.setHomePosition(centre);
            if (p.hasDisconnected()) return;
            setSeeking(p, null);
            long now = p.level().getGameTime();
            Journey.stamp(p, "visit." + clan.clan.name(), now);
            String host = speakerIn(p, clan, WCatEntity.Rank.DEPUTY);
            WCatEntity hostCat = rankCat(p, clan, WCatEntity.Rank.DEPUTY);
            Joining.offer(p, clan, host, List.of(
                    Line.say(name, clan.colour, "Here we are: the " + clan.title + " camp. Wait here, I'll fetch " + host + ".").speakerEntity(guide),
                    Line.narrate("Cats look up from the fresh-kill pile as " + name + " pads across the camp."),
                    Line.say(host, clan.colour, "So " + name + " found you, and you came all this way to find us.").speakerEntity(hostCat),
                    Line.say(host, clan.colour, clan.title + " could use another pair of sharp claws.").speakerEntity(hostCat)));
            Journals.push(p);
            return;
        }
        if (!guide.isAlive() && !p.hasDisconnected()) {
            seekData(p).putLong("sent", 0);                 // lost on the way (the void): another comes as soon as the player is free
            return;
        }
        if (p.hasDisconnected() || !p.isAlive() || guide.distanceTo(p) < 32) return;   // it waits where it is; the next guide may be it
        // the player kept going the other way: the guide gives up and goes home, and another comes later
        Dialog.subtitle(p, Line.narrate(name + " has given up waiting for you. Another " + clan.title + " cat may find you later."));
        seekData(p).putLong("sent", p.level().getGameTime());
        sendHome(guide);
    }

    /**
     * Points to walk through from one place to another so the way goes round the lake (or the Fourtrees hollow)
     * instead of across it: none if the straight way is dry, else points on an arc round the heart of the Clans' land,
     * the shorter way round. A place in the heart itself (Fourtrees, the island's tree-bridge) is walked into straight
     * at the end: only the way more than 60 blocks short of it counts as crossing.
     */
    public static List<double[]> wayRound(ClanWorld w, double fx, double fz, double tx, double tz) {
        double len = Math.hypot(tx - fx, tz - fz);
        double[] c = Homeland.centre(w);
        if (Math.hypot(tx - c[0], tz - c[1]) < 100) return List.of();          // going to the very middle: straight in
        double into = Homeland.heart(w, tx, tz) ? 60 : 0;
        boolean crosses = false;
        for (double s = 0; s <= len - into && !crosses; s += 12) crosses = Homeland.heart(w, fx + (tx - fx) / len * s, fz + (tz - fz) / len * s);
        if (!crosses) return List.of();
        double r = Math.max(Math.hypot(fx - c[0], fz - c[1]), Math.hypot(tx - c[0], tz - c[1]));
        double from = Geo.bearing(fx - c[0], fz - c[1]), to = Geo.bearing(tx - c[0], tz - c[1]);
        double turn = ((to - from) % 360 + 540) % 360 - 180;
        int n = (int) Math.ceil(Math.abs(turn) / 15);
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < n; i++) out.add(Geo.toward(c[0], c[1], from + turn * i / n, r));
        return out;
    }

    /**
     * Like {@link #wayRound}, but the straightest way a cat can walk: round the lake (or the Fourtrees hollow) close
     * along its edge, through whatever territories lie between, instead of out at the far edge of the land. On the
     * way to a Gathering or the half-moon meeting the truce holds and the Clans cross each other's land: a cat at
     * the outer edge of its territory took a wide arc round the whole lake and missed the Gathering (2026-11).
     */
    public static List<double[]> wayShore(ClanWorld w, double fx, double fz, double tx, double tz) {
        double len = Math.hypot(tx - fx, tz - fz);
        double[] c = Homeland.centre(w);
        if (len < 1 || Math.hypot(tx - c[0], tz - c[1]) < 100) return List.of();
        double into = Homeland.heart(w, tx, tz) ? 60 : 0;
        boolean crosses = false;
        for (double s = 0; s <= len - into && !crosses; s += 12) crosses = Homeland.heart(w, fx + (tx - fx) / len * s, fz + (tz - fz) / len * s);
        if (!crosses) return List.of();
        double rf = Math.hypot(fx - c[0], fz - c[1]), rt = Math.hypot(tx - c[0], tz - c[1]);
        double from = Geo.bearing(fx - c[0], fz - c[1]), to = Geo.bearing(tx - c[0], tz - c[1]);
        double turn = ((to - from) % 360 + 540) % 360 - 180;
        int n = (int) Math.ceil(Math.abs(turn) / 12);
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            double b = from + turn * i / n;
            // the outer edge of the water (or the hollow) along this bearing, then a little clear of it
            double far = Math.max(rf, rt), edge = 0;
            for (double d = far; d > 0; d -= 4) {
                double[] pt = Geo.toward(c[0], c[1], b, d);
                if (Homeland.heart(w, pt[0], pt[1])) {
                    edge = d;
                    break;
                }
            }
            double r = edge == 0 ? far : Math.min(far, edge + 20);
            out.add(Geo.toward(c[0], c[1], b, r));
        }
        return out;
    }

    /** The way into a camp: just outside its one way in, then the middle of the camp (only the middle if it has no plan). */
    public static List<double[]> wayIn(ClanWorld w, Landmark camp) {
        CampPlan plan = CampPlan.of(w, camp);
        if (plan == null) return List.of(new double[] {camp.x(), camp.z()});
        double out = Math.max(plan.wallOut, Double.isNaN(plan.cliff) ? 0 : plan.cliff) + 6;
        return List.of(new double[] {plan.cx + Math.cos(plan.gate) * out, plan.cz + Math.sin(plan.gate) * out}, new double[] {plan.cx, plan.cz});
    }

    /** The journal's "Find the <Clan> camp" while the player seeks one, or null. */
    public static JournalView.Quest seekQuest(ServerPlayer p) {
        Info clan = seeking(p);
        ClanWorld w = ClanWorld.current();
        if (clan == null || w == null || Joining.clanOf(p) != null) return null;
        Lead lead = Lead.of(p);
        return seekQuest(clan, Homeland.camp(w, clan.clan), p.getX(), p.getZ(),
                lead != null && !lead.accompanying() ? plain(lead.cat()) : null);
    }

    /** The camp a clanless cat is looking for. */
    public record SeekPlace(String key, String title, double x, double z) {}

    public static java.util.Optional<SeekPlace> seekPlace(ServerPlayer p) {
        Info clan = seeking(p);
        ClanWorld w = ClanWorld.current();
        if (clan == null || w == null || Joining.clanOf(p) != null) return java.util.Optional.empty();
        Landmark camp = Homeland.camp(w, clan.clan);
        return java.util.Optional.of(new SeekPlace(clan.key(), clan.title, camp.x(), camp.z()));
    }

    /** "Find the <Clan> camp", with which way and how far it lies from x, z, and who is leading the way (or null). */
    public static JournalView.Quest seekQuest(Info clan, Landmark camp, double x, double z, String guide) {
        double d = Math.hypot(camp.x() - x, camp.z() - z);
        String where = d < camp.radius() + 10 ? "you are there"
                : "about " + Math.max(10, Math.round(d / 10) * 10) + " blocks to the " + direction(x, z, camp.x(), camp.z());
        String text = (guide != null ? guide + " is leading you there: stay close, and they will wait if you fall behind. "
                : "A cat of " + clan.title + " may find you and show you the way, or you can go alone. ")
                + "The camp lies " + where + ".";
        return new JournalView.Quest("seek:" + clan.key(), "Find the " + clan.title + " camp", guide != null ? guide : clan.title, text,
                List.of(new JournalView.Objective("Reach the " + clan.title + " camp (" + where + ")", 0, 1)), true, false);
    }

    /** A rough compass direction from one point to another ("north-east"). */
    static String direction(double fromX, double fromZ, double toX, double toZ) {
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(Geo.bearing(toX - fromX, toZ - fromZ) / 45) % 8];
    }

    /** The name of a cat of the given rank in the Clan's camp, for the Clan to speak through. */
    private static String speakerIn(ServerPlayer p, Info clan, WCatEntity.Rank rank) {
        ClanWorld w = ClanWorld.current();
        LifeData life = LifeData.get(p.serverLevel());
        UUID id = life.clanId(clan.clan);
        Landmark camp = Homeland.camp(w, clan.clan);
        var cats = p.serverLevel().getEntitiesOfClass(WCatEntity.class,
                new AABB(camp.x() - 60, -64, camp.z() - 60, camp.x() + 60, 320, camp.z() + 60),
                c -> id != null && id.equals(c.getClanUUID()) && c.getRank() == rank && c.getCustomName() != null);
        if (cats.isEmpty()) return life.leader(clan.clan);
        return cats.get(0).getCustomName().getString().replace(" ♂", "").replace(" ♀", "");
    }

    /** The cat {@link #speakerIn} speaks through, for the portrait (null if it is not loaded, or the leader stands in). */
    private static WCatEntity rankCat(ServerPlayer p, Info clan, WCatEntity.Rank rank) {
        String name = speakerIn(p, clan, rank);
        Landmark camp = Homeland.camp(ClanWorld.current(), clan.clan);
        var cats = p.serverLevel().getEntitiesOfClass(WCatEntity.class,
                new AABB(camp.x() - 60, -64, camp.z() - 60, camp.x() + 60, 320, camp.z() + 60),
                c -> c.getCustomName() != null && plain(c).equals(name.trim()));
        return cats.isEmpty() ? null : cats.get(0);
    }

    /** A cat's name without Warrior Cats Events' gender sign. */
    static String plain(WCatEntity c) {
        return c.getCustomName() == null ? "A Clan cat" : c.getCustomName().getString().replace(" ♂", "").replace(" ♀", "").trim();
    }
}
