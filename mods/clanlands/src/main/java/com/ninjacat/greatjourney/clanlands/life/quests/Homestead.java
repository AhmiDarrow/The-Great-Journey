package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.ClanNames;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import com.ninjacat.greatjourney.clanlands.life.story.StoryData;
import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Wolf;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Life in a Clan a player founded ({@link Founding}). The four book Clans have their events; a founded Clan had none,
 * and once it stood there was nothing more to it. Now, while its founder is among their cats, things happen there as
 * they would anywhere: a wanderer asks to join, a kit is born, a cat falls sick, a stray dog comes sniffing round,
 * the Clan goes hungry. The storyteller weighs them as they do the book Clans' events.
 */
final class Homestead {
    private Homestead() {}

    enum Happening { WANDERER, KITS, SICKNESS, DOG, HUNGER }

    static final String WANDERER_TAG = "greatjourney_wanderer", DOG_TAG = "greatjourney_homestead_dog";
    /** A founded Clan grows by its own events to this many cats; more join only by the founder's own paws. */
    static final int FULL = 16;
    /** At least this long between two things happening. */
    static final long BETWEEN = 20L * 60 * 10;
    static final long STAYS = 20L * 60 * 3;
    private static final int ONE_IN = 600;
    private static final String NEXT = "homeNext";
    /** Cats and dogs a happening brought, and when they leave, by their own id. */
    private static final Map<UUID, Long> VISITORS = new HashMap<>();

    static void reset() {
        VISITORS.clear();
    }

    /** The weight of each thing that could happen now, as the storyteller has it. */
    static Map<Happening, Integer> weights(Teller t, int cats, boolean parents, boolean room) {
        Map<Happening, Integer> w = new EnumMap<>(Happening.class);
        if (cats < FULL) {
            w.put(Happening.WANDERER, 30);
            if (parents) w.put(Happening.KITS, (int) Math.round(20 * t.weight("KITS")));
        }
        w.put(Happening.SICKNESS, (int) Math.max(1, Math.round(20 * t.weight("SICKNESS"))));
        w.put(Happening.DOG, (int) Math.max(1, Math.round(15 * t.weight("DOGS"))));
        if (room) w.put(Happening.HUNGER, (int) Math.max(1, Math.round(25 * t.weight("SHORTAGE"))));
        return w;
    }

    private static List<WCatEntity> around(ServerPlayer p, UUID clan) {
        return p.serverLevel().getEntitiesOfClass(WCatEntity.class, p.getBoundingBox().inflate(48),
                c -> c.isAlive() && clan.equals(c.getClanUUID()));
    }

    /** Once a second for every player in the overworld. */
    static void tick(ServerPlayer p) {
        ServerLevel ow = p.serverLevel();
        long now = ow.getGameTime();
        leave(ow, now);
        ClanData.Clan wc = com.ninjacat.greatjourney.clanlands.life.Joining.clanOf(p);
        if (!Founding.founder(p, wc) || Mode.of(p.getServer()) != Mode.FREE || !p.isAlive() || Dialog.busy(p)) return;
        CompoundTag root = QuestState.root(p);
        if (now < root.getLong(NEXT) || p.getRandom().nextInt(ONE_IN) != 0) return;
        List<WCatEntity> cats = around(p, wc.clanUUID);
        if (cats.size() < 2) return;                                // the founder is not among their cats
        root.putLong(NEXT, now + BETWEEN);
        boolean parents = cats.stream().anyMatch(c -> !c.isBaby() && c.getGender() == 0) && cats.stream().anyMatch(c -> !c.isBaby() && c.getGender() == 1);
        Map<Happening, Integer> w = weights(StoryData.get(ow).teller(), wc.clanCats.size(), parents, QuestState.duties(p).size() < QuestState.MAX_DUTIES);
        int roll = p.getRandom().nextInt(w.values().stream().mapToInt(Integer::intValue).sum());
        for (var e : w.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) {
                happen(p, wc.clanUUID, wc.name, cats, e.getKey());
                return;
            }
        }
    }

    static boolean happen(ServerPlayer p, UUID clan, String title, List<WCatEntity> cats, Happening what) {
        ServerLevel ow = p.serverLevel();
        RandomSource r = p.getRandom();
        WCatEntity one = cats.get(r.nextInt(cats.size()));
        BlockPos where = what == Happening.WANDERER ? p.blockPosition() : one.blockPosition();
        boolean happened = happens(p, clan, title, cats, what, ow, r, one);
        if (happened) {
            com.ninjacat.greatjourney.clanlands.life.events.ClanEvents.noticed(clan, where, switch (what) {
                case WANDERER -> "A wanderer waits at the edge of " + title + "'s camp.";
                case KITS -> "A kit is born to " + title + ".";
                case SICKNESS -> Kin.name(one) + " is sick.";
                case DOG -> "A stray dog is at " + title + "'s camp.";
                case HUNGER -> title + " goes hungry.";
            }, ow.getGameTime());
        }
        return happened;
    }

    private static boolean happens(ServerPlayer p, UUID clan, String title, List<WCatEntity> cats, Happening what, ServerLevel ow,
                                   RandomSource r, WCatEntity one) {
        switch (what) {
            case WANDERER -> {
                WCatEntity cat = cat(ow, near(ow, p, r, 7), r, false);
                if (cat == null) return false;
                cat.addTag(WANDERER_TAG);
                cat.getPersistentData().putUUID(CAME_TO, p.getUUID());
                VISITORS.put(cat.getUUID(), ow.getGameTime() + STAYS);
                ow.addFreshEntity(cat);
                List<Line> there = List.of(Line.narrate("A thin cat with no Clan scent waits at the edge of your camp, not daring to come closer."));
                if (QuestState.quiet(p)) Ui.subtitle(p, there);
                else Ui.play(p, there);
                // a quiet cat is left to go and greet the wanderer when it suits them
                if (!QuestState.quiet(p)) Ui.play(p, asks(cat));
            }
            case KITS -> {
                WCatEntity mother = cats.stream().filter(c -> !c.isBaby() && c.getGender() == 1).findFirst().orElse(null);
                if (mother == null) return false;
                WCatEntity kit = cat(ow, mother.blockPosition(), r, true);
                if (kit == null) return false;
                ow.addFreshEntity(kit);
                take(p, kit, clan, mother, WCatEntity.Rank.KIT);
                Ui.subtitle(p, List.of(Line.narrate(Kin.name(mother) + " has had a kit. " + title + " has a new voice in its nursery: " + Kin.name(kit) + ".")));
            }
            case SICKNESS -> {
                var illness = r.nextBoolean() ? DiseaseTypes.WHITECOUGH : DiseaseTypes.FEVER;
                if (one.hasDisease(illness)) return false;
                one.addDisease(illness, true);
                Ui.subtitle(p, List.of(Line.narrate(Kin.name(one) + " is sick. Your Clan looks to you, and to your herbs.")));
            }
            case DOG -> {
                Wolf dog = EntityType.WOLF.create(ow);
                if (dog == null) return false;
                BlockPos at = near(ow, p, r, 14);
                dog.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
                dog.setCustomName(Component.literal("Dog"));
                // the Clan events' own mark: a patrol goes for it, and one that loads after its time is turned away
                dog.addTag(DOG_TAG);
                dog.addTag("greatjourney_event_foe");
                dog.getPersistentData().putLong("greatjourney_leave_at", ow.getGameTime() + STAYS);
                VISITORS.put(dog.getUUID(), ow.getGameTime() + STAYS);
                ow.addFreshEntity(dog);
                dog.setTarget(one);
                Ui.subtitle(p, List.of(Line.narrate("A stray dog comes crashing into the edge of your camp, making for " + Kin.name(one) + ". Drive it off!")));
            }
            case HUNGER -> {
                List<Task> d = QuestState.duties(p);
                if (d.size() >= QuestState.MAX_DUTIES || d.stream().anyMatch(t -> one.getUUID().equals(t.giverId))) return false;
                d.add(new Task(Task.Kind.HUNT, "Feed " + title, title + "'s fresh-kill pile is bare, and " + Kin.name(one)
                        + " says the Clan goes hungry. Catch four pieces of prey, and bring two home to " + Kin.name(one) + " for the pile.",
                        Kin.name(one), one.getUUID(), false, new ArrayList<>(List.of(Task.Objective.catchPrey("Catch 4 prey for " + title, "any", 4),
                        Task.Objective.bring("Bring 2 prey to " + Kin.name(one), Tasks.ANY_PREY, 2))), 5, 40, "", 0));
                QuestState.setDuties(p, d);
                Ui.subtitle(p, List.of(Line.narrate(Kin.name(one) + " says the Clan is hungry. It is in your journal.")));
                Ui.push(p);
            }
        }
        return true;
    }

    private static List<Line> asks(WCatEntity cat) {
        return List.of(Line.say(Kin.name(cat), 0xC8B890, "I've walked alone a long while. I hunt well enough. ...Would your Clan have me?").speakerEntity(cat)
                .withChoices(Choice.of("Welcome them", "greatjourney quest wanderer welcome " + cat.getId()),
                        new Choice("Send them on their way", "greatjourney quest wanderer turn " + cat.getId(), 0xAAAAAA)));
    }

    private static BlockPos near(ServerLevel ow, ServerPlayer p, RandomSource r, int d) {
        double a = r.nextDouble() * Math.PI * 2;
        return BookClans.safeSpot(ow, (int) Math.floor(p.getX() + Math.cos(a) * d), (int) Math.floor(p.getZ() + Math.sin(a) * d), 3);
    }

    /** A cat of no Clan yet (not added to the world): a grown wanderer, or a kit. */
    private static WCatEntity cat(ServerLevel ow, BlockPos at, RandomSource r, boolean kit) {
        WCatEntity cat = ModEntities.WCAT.get().create(ow);
        if (cat == null) return null;
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
        cat.finalizeSpawn(ow, ow.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        ClanNames names = new ClanNames(ow.getSeed() ^ r.nextLong());
        ClanNames.Name n = names.warrior();
        String full = kit ? n.prefix() + "kit" : n.full();
        cat.setCustomName(Component.literal(full + (cat.getGender() == 0 ? " ♂" : " ♀")));
        cat.setPrefix(Component.literal(n.prefix()));
        cat.setRank(kit ? WCatEntity.Rank.KIT : WCatEntity.Rank.NONE);
        cat.setBaby(kit);
        cat.setAgeInMoons(kit ? 0 : 20 + r.nextInt(30));
        cat.mode = WCatEntity.CatMode.WANDER;
        cat.wanderCenter = at;
        return cat;
    }

    /** The cat is the Clan's now: its member, in the founder's care, with a home beside a Clanmate's. */
    static void take(ServerPlayer p, WCatEntity cat, UUID clan, WCatEntity beside, WCatEntity.Rank rank) {
        cat.setTame(true, false);
        cat.setOwnerUUID(p.getUUID());
        cat.setClanUUID(clan);
        cat.setRank(rank);
        BlockPos home = beside != null && beside.hasHomePosition() ? beside.getHomePosition() : cat.blockPosition();
        cat.setHomePosition(home);
        cat.wanderCenter = home;
        cat.updateClanCatData();
    }

    private static final String CAME_TO = "greatjourney_for";

    /** Whether this wanderer came to this founder: no other may take them or send them off. */
    static boolean cameTo(WCatEntity cat, ServerPlayer p) {
        return !cat.getPersistentData().hasUUID(CAME_TO) || cat.getPersistentData().getUUID(CAME_TO).equals(p.getUUID());
    }

    /** "welcome" or "turn" to a wanderer still waiting. */
    static boolean answer(ServerPlayer p, String what, int entityId) {
        Dialog.clear(p);
        ClanData.Clan wc = com.ninjacat.greatjourney.clanlands.life.Joining.clanOf(p);
        if (!(p.serverLevel().getEntity(entityId) instanceof WCatEntity cat) || !cat.getTags().contains(WANDERER_TAG)
                || !Founding.founder(p, wc) || cat.distanceTo(p) > 32 || !cameTo(cat, p)) return false;
        return settle(p, cat, wc.clanUUID, wc.name, what);
    }

    static boolean settle(ServerPlayer p, WCatEntity cat, UUID clan, String title, String what) {
        if (!what.equals("welcome") && !what.equals("turn")) return false;
        VISITORS.remove(cat.getUUID());
        cat.removeTag(WANDERER_TAG);
        if (what.equals("welcome")) {
            take(p, cat, clan, around(p, clan).stream().findFirst().orElse(null), WCatEntity.Rank.WARRIOR);
            Ui.play(p, List.of(Line.narrate(Kin.name(cat) + " dips their head to you. " + title + " has one more warrior.")));
        } else {
            cat.discard();
            Ui.play(p, List.of(Line.narrate("They look at you a moment, nod, and go on alone.")));
        }
        return true;
    }

    static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!Dialog.talksWith(p.getMainHandItem())) return;
        if (p.isShiftKeyDown() || !(e.getTarget() instanceof WCatEntity cat) || !cat.getTags().contains(WANDERER_TAG) || !cameTo(cat, p)) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (!Dialog.busy(p) && Founding.founder(p, com.ninjacat.greatjourney.clanlands.life.Joining.clanOf(p))) Dialog.play(p, asks(cat));
    }

    /** A visitor whose time is up goes on its way. */
    private static void leave(ServerLevel ow, long now) {
        if (VISITORS.isEmpty()) return;
        for (var e : List.copyOf(VISITORS.entrySet())) {
            if (now < e.getValue()) continue;
            VISITORS.remove(e.getKey());
            Entity gone = ow.getEntity(e.getKey());
            if (gone != null) gone.discard();
        }
    }

    /** A wanderer loading after a restart has long since moved on. */
    static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide() && e.getEntity().getTags().contains(WANDERER_TAG) && !VISITORS.containsKey(e.getEntity().getUUID())) {
            e.setCanceled(true);
        }
    }
}
