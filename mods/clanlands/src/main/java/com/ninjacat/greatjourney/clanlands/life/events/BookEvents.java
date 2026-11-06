package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.world.CampSanctuary;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.snowteb.warriorcats_events.block.entity.FreshkillPileBlockEntity;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The book events that came after the first set: a Twoleg dog loose in the territory, rogues at the fresh-kill pile,
 * an adder on the warm rocks, rats from the Twoleg dump, a kittypet at the border, prey running short in leaf-bare,
 * and an elder's story for the kits. Each happens where a player can see it (the camp, or a place with a player by
 * it, its creatures moving), and each ends: whatever comes for the Clan runs when it is hurt and leaves on a timer
 * like the event fox ({@link Happenings#markFoe}), and every cat an event borrows goes back to the camp routine.
 */
final class BookEvents {
    private BookEvents() {}

    /** On a Clan cat an event is steering (with {@link BookClans#BUSY_TAG}), so one left so by a restart is given back. */
    static final String EVENT_CAT = "greatjourney_event_cat";

    private static final long DOG_TICKS = 20L * 60 * 2, ROGUE_TICKS = 20L * 60 * 3, RAT_TICKS = 20L * 60 * 2;
    /** How long a creature that has had enough has to run out of sight before it is gone. */
    private static final long FLEE_TICKS = 20L * 6;
    private static final ResourceLocation SNAKE = ResourceLocation.fromNamespaceAndPath("naturalist", "snake");
    private static final ResourceLocation RAT = ResourceLocation.fromNamespaceAndPath("naturalist", "rat");
    private static final Set<Landmark.Kind> TWOLEG = EnumSet.of(Landmark.Kind.HORSEPLACE, Landmark.Kind.KITTYPET_HOME,
            Landmark.Kind.TWOLEG_NEST, Landmark.Kind.BARLEY_FARM, Landmark.Kind.TWOLEG_FARMS);
    private static final Set<Landmark.Kind> WARM_ROCKS = EnumSet.of(Landmark.Kind.SUNNINGROCKS, Landmark.Kind.SNAKEROCKS);
    private static final String[] ROGUE_NAMES = {"Rook", "Snag", "Flint", "Slash", "Crow", "Bone", "Grit", "Thorn", "Patch", "Nettle"};
    private static final String[] KITTYPET_NAMES = {"Smudge", "Princess", "Jake", "Daisy", "Tiny", "Socks", "Biscuit", "Muffin"};
    private static final int KITTYPET_COLOUR = 0xD8C8E8;

    /** Raid creatures that have had enough and only run now. */
    private static final Set<UUID> fled = new HashSet<>();
    /** Where a dog or rats were let loose away from the camp (null: at the camp), and that place's name. */
    private static BlockPos loose;
    private static String looseName;
    /** The camp the rogues or rats are raiding, worked out once when they come. */
    private static CampSite raidSite;
    private static boolean firstTheft;

    private static Scene adder, kittypet, story;

    static void reset() {
        majorEnded();
        adder = kittypet = story = null;
    }

    /** The running big event ended (any kind): forget what only it needed. */
    static void majorEnded() {
        fled.clear();
        loose = null;
        looseName = null;
        raidSite = null;
        firstTheft = false;
    }

    static boolean run(ServerLevel ow, Clan c, ClanEvents.Kind kind, List<WCatEntity> cats, RandomSource r) {
        return switch (kind) {
            case DOGS -> dog(ow, c, cats, r);
            case ROGUES -> rogues(ow, c, cats, r);
            case RATS -> rats(ow, c, cats, r);
            case ADDER -> adder(ow, c, cats, r);
            case KITTYPET -> kittypet(ow, c, cats, r);
            case SHORTAGE -> shortage(ow, c, cats, r);
            case STORY -> story(ow, c, cats, r);
            default -> false;
        };
    }

    /** Whether Naturalist's snakes are in the pack (the adder needs one). */
    static boolean hasSnakes() {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(SNAKE);
    }

    /** Whether rats can come now: Naturalist's rats are in the pack, and it is day (at night they curl up asleep where they stand). */
    static boolean ratsCanCome(ServerLevel ow) {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(RAT) && ow.isDay();
    }

    /** Whether a player stands inside the Clan's camp, to see a scene there. */
    static boolean playerInCamp(ServerLevel ow, Clan c) {
        Landmark camp = Cats.camp(c);
        if (camp == null) return false;
        for (ServerPlayer p : ow.players()) {
            if (!p.isSpectator() && Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) <= camp.radius()) return true;
        }
        return false;
    }

    // ---- 10: a loose Twoleg dog

    private static boolean dog(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Wolf dog = EntityType.WOLF.create(ow);
        Landmark camp = Cats.camp(c);
        if (dog == null || camp == null) return false;
        // by a Twoleg place if a player is there to see it; else at the camp's way in, where the Clan's cats are
        Landmark place = watchedPlace(ow, c, TWOLEG, 500);
        BlockPos at = place != null ? edgeToward(ow, place, camp) : Happenings.outsideWayIn(ow, c, r, 10);
        put(ow, dog, at, r);
        dog.setTame(true, false);                         // a collar: someone's pet got loose, not a wild wolf
        dog.setCustomName(Component.literal("Dog"));
        setBase(dog.getAttribute(Attributes.MAX_HEALTH), 16);
        setBase(dog.getAttribute(Attributes.ATTACK_DAMAGE), 2);   // it snaps and barks; it doesn't maul
        dog.setHealth(16);
        Happenings.markFoe(dog, c, ow.getGameTime() + DOG_TICKS, "The dog loses interest and lopes off home to its Twolegs.");
        ow.addFreshEntity(dog);
        Happenings.start(ow, ClanEvents.Kind.DOGS, c, DOG_TICKS, dog.getUUID());
        loose = place != null ? at : null;
        looseName = place != null ? placeName(place.kind()) : null;
        Happenings.rally(Happenings.fighters(place != null ? clanCatsNear(ow, c, at, 48) : cats), dog, 3);
        Info info = Info.of(c);
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        Announce.tell(ow, c, at,
                Line.narrate("Loud, stupid barking crashes through the undergrowth " + (place != null ? "by " + looseName : "at the camp entrance")
                        + ". A Twoleg dog has got loose!"),
                Line.say(deputy, info.colour, Happenings.pick(r, "Dog! Warriors, drive it off! Stay together, and go for its nose!",
                        "A dog! Keep the kits back! Warriors, chase that flea-bitten thing out of our territory!")));
        return true;
    }

    private static void dogTick(ServerLevel ow, Clan c, Mob dog) {
        if (dog.getTarget() != null && dog.getTarget().isAlive() && dog.distanceTo(dog.getTarget()) < 32) return;
        LivingEntity cat = nearestCat(ow, c, dog, 24);
        if (cat != null) {
            dog.setTarget(cat);
            if (dog instanceof NeutralMob n) {
                n.setPersistentAngerTarget(cat.getUUID());
                n.startPersistentAngerTimer();
            }
        } else if (loose == null) {
            Happenings.steerIn(ow, c, dog, 1.2);
        }
    }

    // ---- 11: rogues come for the fresh-kill pile

    private static boolean rogues(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Landmark camp = Cats.camp(c);
        if (camp == null) return false;
        CampSite site = CampSite.of(ow, c, camp);
        BlockPos at = Happenings.outsideWayIn(ow, c, r, 8);
        long until = ow.getGameTime() + ROGUE_TICKS;
        List<WCatEntity> rogues = new ArrayList<>();
        List<String> used = new ArrayList<>();
        int n = 2 + r.nextInt(2);
        for (int i = 0; i < n; i++) {
            WCatEntity cat = ModEntities.WCAT.get().create(ow);
            if (cat == null) continue;
            put(ow, cat, near(ow, at, r, 2), r);
            String name = ROGUE_NAMES[r.nextInt(ROGUE_NAMES.length)];
            for (int t = 0; t < 10 && used.contains(name); t++) name = ROGUE_NAMES[r.nextInt(ROGUE_NAMES.length)];
            used.add(name);
            Cats.rename(cat, name, name);
            cat.setRank(WCatEntity.Rank.NONE);            // no Clan, no warrior name: a rogue
            cat.setBaby(false);
            cat.setAgeInMoons(20 + r.nextInt(40));
            cat.mode = WCatEntity.CatMode.WANDER;
            cat.wanderCenter = site != null && site.pile != null ? site.pile : at;
            Happenings.markFoe(cat, c, until, "");
            ow.addFreshEntity(cat);
            rogues.add(cat);
        }
        if (rogues.isEmpty()) return false;
        Happenings.start(ow, ClanEvents.Kind.ROGUES, c, ROGUE_TICKS, rogues.stream().map(Entity::getUUID).toArray(UUID[]::new));
        raidSite = site;
        // the guards meet them at the entrance, one each; the rest of the Clan joins when they see the fight
        List<WCatEntity> defenders = Happenings.fighters(cats);
        for (int i = 0; i < rogues.size() && i < defenders.size(); i++) defenders.get(i).setTarget(rogues.get(i));
        Info info = Info.of(c);
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        Announce.tell(ow, c, at,
                Line.narrate("Strange scents drift in at the camp entrance: rogues, lean and hungry, eyeing the fresh-kill pile."),
                Line.say(Cats.plainName(rogues.get(0)), 0xB0A090, Happenings.pick(r, "Look at all that prey, just lying there. Help yourselves, friends!",
                        "Clan cats, so soft and well fed. They won't miss a mouse or two.")),
                Line.say(deputy, info.colour, "Rogues! Guard the fresh-kill pile! Send them running!"));
        return true;
    }

    // ---- 12: rats from the Twoleg dump

    private static boolean rats(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Landmark camp = Cats.camp(c);
        if (camp == null || !ratsCanCome(ow)) return false;
        // Carrionplace, the old forest's Twoleg dump, when a player is by it; the lake has none, so there the rats
        // come scrabbling in at the camp's way in after the fresh-kill pile
        Landmark dump = Homeland.forest() ? watchedPlace(ow, c, EnumSet.of(Landmark.Kind.CARRIONPLACE), 650) : null;
        BlockPos at = dump != null ? edgeToward(ow, dump, camp) : Happenings.outsideWayIn(ow, c, r, 6);
        long until = ow.getGameTime() + RAT_TICKS;
        List<Mob> rats = new ArrayList<>();
        int n = 3 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            Mob rat = create(ow, RAT);
            if (rat == null) break;
            put(ow, rat, near(ow, at, r, 3), r);
            rat.setCustomName(Component.literal("Rat"));  // named: the camp sanctuary lets it in, and it is plainly the event's
            Happenings.markFoe(rat, c, until, "");
            ow.addFreshEntity(rat);
            rats.add(rat);
        }
        if (rats.isEmpty()) return false;
        Happenings.start(ow, ClanEvents.Kind.RATS, c, RAT_TICKS, rats.stream().map(Entity::getUUID).toArray(UUID[]::new));
        loose = dump != null ? at : null;
        looseName = dump != null ? "Carrionplace" : null;
        raidSite = dump != null ? null : CampSite.of(ow, c, camp);
        List<WCatEntity> defenders = Happenings.fighters(dump != null ? clanCatsNear(ow, c, at, 48) : cats);
        for (int i = 0; i < defenders.size() && i < 4; i++) defenders.get(i).setTarget(rats.get(i % rats.size()));
        Info info = Info.of(c);
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        Announce.tell(ow, c, at,
                Line.narrate(dump != null ? "A squeaking, scrabbling swarm boils out of the rubbish at Carrionplace: rats!"
                        : "A swarm of rats comes scrabbling in at the camp entrance, sniffing for the fresh-kill pile!"),
                Line.say(deputy, info.colour, Happenings.pick(r, "Rats! Chase them off, and mind their teeth! A rat bite goes bad.",
                        "Rats! Don't let them near the fresh-kill pile, or the kits!")));
        return true;
    }

    /**
     * A rogue or a rat makes for the fresh-kill pile: in by the way in, then to the pile, where it snatches a piece of
     * prey and runs. One that is fighting is left to it; at Carrionplace the rats only scurry about.
     */
    private static void thiefTick(ServerLevel ow, Clan c, ClanEvents.Kind k, Mob m) {
        if (m.getTarget() != null && m.getTarget().isAlive()) return;
        if (loose != null) {
            if (m.getNavigation().isDone()) {
                BlockPos to = near(ow, loose, m.getRandom(), 8);
                m.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.0);
            }
            return;
        }
        double speed = k == ClanEvents.Kind.RATS ? 1.3 : 1.1;
        if (!Happenings.steerIn(ow, c, m, speed)) return;
        CampSite site = raidSite;
        if (site == null || site.pile == null) {
            if (k == ClanEvents.Kind.ROGUES) {
                LivingEntity cat = nearestCat(ow, c, m, 16);    // no pile to rob: they pick a fight instead
                if (cat != null) m.setTarget(cat);
            }
            return;
        }
        double dx = m.getX() - (site.pile.getX() + 0.5), dz = m.getZ() - (site.pile.getZ() + 0.5);
        if (dx * dx + dz * dz < 2.5 * 2.5) {
            boolean took = steal(ow, site.pile);
            String who = m instanceof WCatEntity cat ? Cats.plainName(cat) : "A rat";
            boolean say = k == ClanEvents.Kind.ROGUES || !firstTheft;
            firstTheft = true;
            flee(ow, c, m, !say ? null : took ? who + " snatches a piece of prey from the fresh-kill pile and runs!"
                    : who + " finds the fresh-kill pile bare, and turns tail.");
            return;
        }
        if (m.getNavigation().isDone()) m.getNavigation().moveTo(site.eat.getX() + 0.5, site.eat.getY(), site.eat.getZ() + 0.5, speed);
    }

    private static boolean steal(ServerLevel ow, BlockPos at) {
        if (!ow.hasChunk(at.getX() >> 4, at.getZ() >> 4) || !(ow.getBlockEntity(at) instanceof FreshkillPileBlockEntity pile)) return false;
        var h = pile.getItemHandler();
        for (int i = 0; i < h.getSlots(); i++) {
            if (!h.extractItem(i, 1, false).isEmpty()) {
                pile.setChanged();
                ow.sendBlockUpdated(at, ow.getBlockState(at), ow.getBlockState(at), 3);
                return true;
            }
        }
        return false;
    }

    /** Once a second while a dog, rogues or rats are about: they come on, and the event ends when they are all gone. */
    static void majorTick(ServerLevel ow, Clan c, ClanEvents.Kind k, boolean late) {
        List<Mob> left = new ArrayList<>();
        for (UUID id : List.copyOf(ClanEvents.activeEntities)) {
            Entity e = ow.getEntity(id);
            if (e instanceof Mob m && m.isAlive()) left.add(m);
            // else gone, or only unloaded with its land: it is waited for until the event's time is up
        }
        Info info = Info.of(c);
        if (late) {
            for (Mob m : left) m.discard();
            if (!left.isEmpty()) {
                Announce.tell(ow, c, left.get(0).blockPosition(), Line.narrate(switch (k) {
                    case DOGS -> "The dog loses interest and lopes off home to its Twolegs.";
                    case ROGUES -> "The rogues slink away, empty-mouthed.";
                    default -> "The last of the rats scurry away into the undergrowth.";
                }));
            }
            EventNews.record(c, news(k, info));
            Happenings.finish();
            return;
        }
        if (ClanEvents.activeEntities.isEmpty()) {
            List<WCatEntity> cats = Cats.atCamp(ow, c);
            String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
            Announce.tell(ow, c, loose != null ? loose : Cats.campCentre(ow, c), Line.say(deputy, info.colour, switch (k) {
                case DOGS -> "It's gone! Well done, everyone. That dog won't be back in a hurry.";
                case ROGUES -> "Those rogues won't forget " + info.title + " claws! Well fought, all of you.";
                default -> "The rats are gone. Throw out any prey they touched, and keep the kits away from that stink.";
            }));
            EventNews.record(c, news(k, info));
            Happenings.finish();
            return;
        }
        for (Mob m : left) {
            if (k == ClanEvents.Kind.DOGS) dogTick(ow, c, m);
            else thiefTick(ow, c, k, m);
        }
    }

    private static String news(ClanEvents.Kind k, Info info) {
        return switch (k) {
            case DOGS -> "A loose Twoleg dog ran into " + info.title + " territory" + (looseName != null ? " by " + looseName : "") + " and was chased off.";
            case ROGUES -> "Rogues came for " + info.title + "'s fresh-kill pile and were driven off.";
            default -> "Rats " + (looseName != null ? "from " + looseName + " " : "") + "raided " + info.title + "'s territory and were chased away.";
        };
    }

    /** A creature that has had enough runs off: out of the camp by its way in, or straight away from it. */
    private static void flee(ServerLevel ow, Clan c, Mob m, String say) {
        ClanEvents.activeEntities.remove(m.getUUID());
        fled.add(m.getUUID());
        m.setTarget(null);
        if (m instanceof NeutralMob n) n.stopBeingAngry();
        BlockPos to = CampSanctuary.inCamp(m.getX(), m.getZ()) ? Happenings.outsideWayIn(ow, c, m.getRandom(), 12) : awayFrom(ow, c, m.blockPosition(), 24);
        m.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.4);
        Happenings.leaveSoon(m, FLEE_TICKS, "");
        if (say != null) Announce.tell(ow, c, m.blockPosition(), Line.narrate(say));
    }

    /** The dog runs when it is badly hurt, a rogue when it is half beaten (as a raider does), a rat at the first bite. */
    static void onDamage(LivingDamageEvent.Post e) {
        LivingEntity hurt = e.getEntity();
        ClanEvents.Kind k = ClanEvents.activeKind;
        if (k == null || !(hurt instanceof Mob m) || !(hurt.level() instanceof ServerLevel level) || !hurt.isAlive()
                || !ClanEvents.activeEntities.contains(hurt.getUUID()) || fled.contains(hurt.getUUID())) {
            return;
        }
        ServerLevel ow = level.getServer().overworld();
        Clan c = ClanEvents.activeClan;
        boolean beaten = m.getHealth() < m.getMaxHealth() * 0.5F;
        switch (k) {
            case DOGS -> {
                if (beaten) flee(ow, c, m, "The dog yelps and bolts for home with its tail between its legs!");
            }
            case ROGUES -> {
                if (beaten) flee(ow, c, m, (m instanceof WCatEntity cat ? Cats.plainName(cat) : "The rogue") + " yowls and flees back the way they came.");
            }
            case RATS -> flee(ow, c, m, null);
            default -> {}
        }
    }

    static void onCreatureDeath(LivingDeathEvent e) {
        ClanEvents.Kind k = ClanEvents.activeKind;
        if (k == ClanEvents.Kind.DOGS || k == ClanEvents.Kind.ROGUES || k == ClanEvents.Kind.RATS) {
            ClanEvents.activeEntities.remove(e.getEntity().getUUID());
        }
    }

    // ---- 13: an adder on the warm rocks

    private static boolean adder(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Landmark camp = Cats.camp(c);
        if (adder != null || camp == null) return false;
        Mob snake = create(ow, SNAKE);
        if (snake == null) return false;
        CampSite site = CampSite.of(ow, c, camp);
        LifeData life = LifeData.get(ow);
        WCatEntity victim = null;
        BlockPos spot = null;
        // Sunningrocks or Snakerocks, when a player is there and a Clan cat is out on the rocks with them
        Landmark rocks = Homeland.forest() ? watchedPlace(ow, c, WARM_ROCKS, 700) : null;
        if (rocks != null) {
            BlockPos mid = Cats.surface(ow, (int) Math.floor(rocks.x()), (int) Math.floor(rocks.z()));
            victim = clanCatsNear(ow, c, mid, 48).stream().filter(x -> bitable(x, c, life))
                    .min(Comparator.comparingDouble(x -> x.distanceToSqr(mid.getX(), mid.getY(), mid.getZ()))).orElse(null);
            if (victim != null) spot = near(ow, mid, r, (int) Math.max(2, rocks.radius() / 3));
        }
        if (victim == null) {
            List<WCatEntity> able = cats.stream().filter(x -> bitable(x, c, life)).toList();
            List<WCatEntity> apps = Cats.withRank(able, WCatEntity.Rank.APPRENTICE);
            List<WCatEntity> pick = apps.isEmpty() ? able : apps;
            if (pick.isEmpty()) return false;
            victim = pick.get(r.nextInt(pick.size()));
            spot = warmRock(ow, c, r);
        }
        put(ow, snake, spot, r);
        snake.setCustomName(Component.literal("Adder"));
        Happenings.markFoe(snake, c, ow.getGameTime() + 20L * 60 * 2, "");
        ow.addFreshEntity(snake);
        grab(victim);
        Scene s = new Scene(c, site, ow.getGameTime());
        s.lead = victim.getUUID();
        s.creature = snake.getUUID();
        s.walk = new Walk(route(site, victim.blockPosition(), spot));
        adder = s;
        return true;                                       // the Clan hears of it when the adder strikes
    }

    private static boolean bitable(WCatEntity x, Clan c, LifeData life) {
        return (x.getRank() == WCatEntity.Rank.APPRENTICE || x.getRank() == WCatEntity.Rank.WARRIOR) && !x.isBaby()
                && !Cats.isLeader(life, c, x) && free(x, c);
    }

    private static void adderTick(ServerLevel ow) {
        Scene s = adder;
        if (s == null) return;
        long now = ow.getGameTime();
        if (!(ow.getEntity(s.lead) instanceof WCatEntity victim) || !victim.isAlive()) {
            endScene(ow, s);                               // unloaded with its camp: given back when it loads (onJoin)
            adder = null;
            return;
        }
        Info info = Info.of(s.clan);
        if (s.phase == 0) {
            Entity snake = ow.getEntity(s.creature);
            if (!(snake instanceof Mob adderMob) || !snake.isAlive()) {
                endScene(ow, s);                           // the adder went (or a cat killed it) before it struck
                adder = null;
                return;
            }
            boolean over = now - s.started > 20 * 60;
            if (!s.walk.done() && !over) {
                s.walk.step(ow, victim, 1.0);
                return;
            }
            if (victim.distanceTo(snake) > 2.5) {
                if (!over) {
                    victim.getNavigation().moveTo(snake, 1.0);
                    return;
                }
                adderMob.teleportTo(victim.getX() + 1, victim.getY(), victim.getZ());
            }
            bite(ow, s, info, victim, adderMob);
            return;
        }
        // limping home to the medicine cat, who comes to the front of the medicine den
        boolean home = s.walk.step(ow, victim, 0.8);
        Entity me = s.other == null ? null : ow.getEntity(s.other);
        WCatEntity med = me instanceof WCatEntity m && m.isAlive() ? m : null;
        boolean ready = med == null || (s.walk2 != null && s.walk2.step(ow, med, 1.1)) || med.distanceTo(victim) < 4;
        boolean over = now - s.phaseAt > 20 * 90;
        if (!(home && ready) && !over) return;
        victim.removeEffect(MobEffects.POISON);
        victim.heal(victim.getMaxHealth());
        String name = Cats.plainName(victim);
        boolean together = med != null && med.distanceTo(victim) < 12;
        String healer = med != null ? Cats.plainName(med) : Cats.speaker(Cats.atCamp(ow, s.clan), WCatEntity.Rank.MEDICINE, null);
        if (together) {
            Announce.tell(ow, s.clan, victim.blockPosition(),
                    Line.say(healer, info.colour, "Lie still, " + name + ". I'll chew up some yarrow to draw out the poison, and wrap the bite in cobweb."),
                    Line.say(healer, info.colour, "There. Rest in my den tonight, and no hunting for a few days."),
                    Line.narrate(name + " will be sore for a while, but the poison is out."));
        } else {
            Announce.tell(ow, s.clan, victim.blockPosition(), Line.narrate("A patrol helps " + name + " home, and "
                    + (healer != null ? healer : "the Clan") + " draws out the adder's poison with yarrow. " + name + " will be all right."));
        }
        EventNews.record(s.clan, name + " was bitten by an adder" + (healer != null ? ", and " + healer + " drew out the poison." : ", and has recovered."));
        endScene(ow, s);
        adder = null;
    }

    private static void bite(ServerLevel ow, Scene s, Info info, WCatEntity victim, Mob snake) {
        snake.swing(InteractionHand.MAIN_HAND);
        float hit = Math.min(2.0F, victim.getHealth() - 4.0F);
        if (hit > 0) victim.hurt(victim.damageSources().mobAttack(snake), hit);
        victim.addEffect(new MobEffectInstance(MobEffects.POISON, 20 * 15, 0));   // vanilla poison never takes the last heart
        snake.setTarget(null);
        BlockPos away = awayFrom(ow, s.clan, snake.blockPosition(), 10);
        snake.getNavigation().moveTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5, 1.0);
        Happenings.leaveSoon(snake, 20 * 5, "");
        RandomSource r = ow.getRandom();
        CampSite site = s.site;
        BlockPos den = site != null ? site.fronts.getOrDefault(DenKind.MEDICINE, site.centre) : Cats.campCentre(ow, s.clan);
        WCatEntity med = Cats.atCamp(ow, s.clan).stream().filter(x -> x.getRank() == WCatEntity.Rank.MEDICINE && free(x, s.clan)).findFirst().orElse(null);
        if (med != null) {
            grab(med);
            s.other = med.getUUID();
            s.walk2 = new Walk(route(site, med.blockPosition(), den));
        }
        s.walk = new Walk(route(site, victim.blockPosition(), den));
        s.phase = 1;
        s.phaseAt = ow.getGameTime();
        String name = Cats.plainName(victim);
        String helper = med != null ? Cats.plainName(med) : Cats.speaker(Cats.atCamp(ow, s.clan), WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(s.clan));
        Announce.tell(ow, s.clan, victim.blockPosition(),
                Line.narrate(name + " yowls and leaps back from the sun-warmed stones. An adder was basking there, and it has bitten "
                        + (victim.getGender() == 0 ? "him" : "her") + "!"),
                Line.say(name, info.colour, Happenings.pick(r, "My leg! It burns...", "It bit me! I feel all dizzy...")),
                Line.say(helper, info.colour, med != null ? "Bring " + name + " to my den, quickly! An adder bite is dangerous, but I know what to do."
                        : "Get back to camp, " + name + ", slowly. Lie down by the medicine den."));
    }

    /** A stony, sunny spot in the Clan's land near the camp's way in, where an adder might bask; else just outside the way in. */
    private static BlockPos warmRock(ServerLevel ow, Clan c, RandomSource r) {
        ClanWorld w = ClanWorld.current();
        BlockPos gate = Happenings.outsideWayIn(ow, c, r, 0);
        for (int i = 0; i < 80; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * 30;
            int x = (int) Math.floor(gate.getX() + 0.5 + Math.cos(a) * d), z = (int) Math.floor(gate.getZ() + 0.5 + Math.sin(a) * d);
            if (!ow.hasChunk(x >> 4, z >> 4) || CampSanctuary.inCamp(x + 0.5, z + 0.5)) continue;
            if (w != null && Homeland.territoryAt(w, x, z) != c) continue;
            BlockPos top = Cats.surface(ow, x, z);
            if (!ow.isPositionEntityTicking(top) || !ow.canSeeSky(top)) continue;
            if (warm(ow.getBlockState(top.below()))) return top;
        }
        return Happenings.outsideWayIn(ow, c, r, 6);
    }

    private static boolean warm(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.SANDSTONE) || s.is(Blocks.SMOOTH_STONE);
    }

    // ---- 14: a kittypet at the border

    private static boolean kittypet(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Landmark camp = Cats.camp(c);
        if (kittypet != null || camp == null) return false;
        CampSite site = CampSite.of(ow, c, camp);
        LifeData life = LifeData.get(ow);
        List<WCatEntity> patrol = cats.stream().filter(x -> (x.getRank() == WCatEntity.Rank.WARRIOR || x.getRank() == WCatEntity.Rank.DEPUTY
                || x.getRank() == WCatEntity.Rank.APPRENTICE) && !x.isBaby() && !Cats.isLeader(life, c, x) && free(x, c)).limit(2).toList();
        Cat pet = EntityType.CAT.create(ow);
        if (patrol.isEmpty() || pet == null) return false;
        BlockPos at = Happenings.outsideWayIn(ow, c, r, 12);
        put(ow, pet, at, r);
        pet.setTame(true, false);                         // a Twoleg's pet: its red collar shows
        String name = KITTYPET_NAMES[r.nextInt(KITTYPET_NAMES.length)];
        pet.setCustomName(Component.literal(name));
        pet.setInvulnerable(true);                        // a visitor to be sent home, never hurt
        Happenings.markFoe(pet, c, ow.getGameTime() + 20L * 60 * 3, "");
        ow.addFreshEntity(pet);
        Scene s = new Scene(c, site, ow.getGameTime());
        s.creature = pet.getUUID();
        s.name = name;
        s.spot = Happenings.outsideWayIn(ow, c, r, 0);
        for (WCatEntity cat : patrol) {
            grab(cat);
            s.cats.add(cat.getUUID());
            s.walks.put(cat.getUUID(), new Walk(route(site, cat.blockPosition(), s.spot)));
        }
        kittypet = s;
        Info info = Info.of(c);
        Announce.tell(ow, c, at, Line.say(Cats.plainName(patrol.get(0)), info.colour, Happenings.pick(r,
                "I smell kittypet! Something that reeks of Twoleg food is by the camp entrance.",
                "Kittypet scent, right on our border! Come on, let's see it off.")));
        return true;
    }

    private static void kittypetTick(ServerLevel ow) {
        Scene s = kittypet;
        if (s == null) return;
        long now = ow.getGameTime();
        Entity pe = ow.getEntity(s.creature);
        if (s.phase == 0 && (pe == null || !pe.isAlive())) {
            endScene(ow, s);
            kittypet = null;
            return;
        }
        Info info = Info.of(s.clan);
        if (s.phase == 0) {
            Mob pet = (Mob) pe;
            if (pet.getNavigation().isDone() && pet.distanceToSqr(s.spot.getX() + 0.5, s.spot.getY(), s.spot.getZ() + 0.5) > 9) {
                pet.getNavigation().moveTo(s.spot.getX() + 0.5, s.spot.getY(), s.spot.getZ() + 0.5, 0.8);
            }
            WCatEntity first = null;
            for (UUID id : s.cats) {
                if (!(ow.getEntity(id) instanceof WCatEntity cat) || !cat.isAlive()) continue;
                if (first == null) first = cat;
                Walk w = s.walks.get(id);
                if (w != null && !w.done()) w.step(ow, cat, 1.1);
                else cat.getNavigation().moveTo(pet, 1.1);
                if (cat.distanceTo(pet) < 4) first = cat;
            }
            boolean met = first != null && first.distanceTo(pet) < 4;
            boolean over = now - s.started > 20 * 75;
            if (!met && !over && first != null) return;
            RandomSource r = ow.getRandom();
            if (met) {
                String warrior = Cats.plainName(first);
                Announce.tell(ow, s.clan, pet.blockPosition(),
                        Line.say(s.name, KITTYPET_COLOUR, Happenings.pick(r, "Oh, hello! Is this where the forest cats live? I smelled the most wonderful mice out here...",
                                "Hi! I'm " + s.name + ". My Twolegs feed me, but I wanted to see what the forest is like!")),
                        Line.say(warrior, info.colour, Happenings.pick(r, "Go home, kittypet! This is " + info.title + " territory, and you don't belong here.",
                                "Run back to your Twolegs, kittypet, before you get hurt. Clan cats don't share their prey.")),
                        Line.say(s.name, KITTYPET_COLOUR, Happenings.pick(r, "All right, all right! I'm going. Nobody out here has any manners...",
                                "Eek! Sorry! I'm going!")),
                        Line.narrate(s.name + " scampers off toward the Twolegs' nests, tail held high."));
            } else {
                Announce.tell(ow, s.clan, pet.blockPosition(), Line.narrate(s.name + " loses interest and wanders back toward its Twolegs."));
            }
            EventNews.record(s.clan, "A kittypet called " + s.name + " strayed to the " + info.title + " border and was sent home.");
            BlockPos away = awayFrom(ow, s.clan, pet.blockPosition(), 24);
            pet.getNavigation().moveTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5, 1.3);
            Happenings.leaveSoon(pet, 20 * 8, "");
            // the patrol walks back in by the way in
            BlockPos home = s.site != null ? s.site.centre : Cats.campCentre(ow, s.clan);
            s.walks.clear();
            for (UUID id : s.cats) {
                if (ow.getEntity(id) instanceof WCatEntity cat) s.walks.put(id, new Walk(route(s.site, cat.blockPosition(), home)));
            }
            s.phase = 1;
            s.phaseAt = now;
            return;
        }
        boolean all = true;
        for (UUID id : s.cats) {
            Walk w = s.walks.get(id);
            if (ow.getEntity(id) instanceof WCatEntity cat && cat.isAlive() && w != null && !w.step(ow, cat, 1.0)) all = false;
        }
        if (all || now - s.phaseAt > 20 * 60) {
            endScene(ow, s);
            kittypet = null;
        }
    }

    // ---- 15: prey runs short in leaf-bare

    private static boolean shortage(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Landmark camp = Cats.camp(c);
        if (camp == null) return false;
        CampSite site = CampSite.of(ow, c, camp);
        if (site != null && site.pile != null && ow.hasChunk(site.pile.getX() >> 4, site.pile.getZ() >> 4)
                && ow.getBlockEntity(site.pile) instanceof FreshkillPileBlockEntity pile) {
            var h = pile.getItemHandler();
            for (int i = 0; i < h.getSlots(); i++) {
                int n = h.getStackInSlot(i).getCount();
                if (n > 0) h.extractItem(i, (n + 1) / 2, false);   // eaten down to half: the Clan has been hungry a while
            }
            pile.setChanged();
            ow.sendBlockUpdated(site.pile, ow.getBlockState(site.pile), ow.getBlockState(site.pile), 3);
        }
        List<WCatEntity> party = Happenings.sendHunters(ow, c, cats, r);
        boolean leafBare = Seasons.isLeafBare(ow);
        Info info = Info.of(c);
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        String queen = cats.stream().filter(x -> x.getGender() == 1 && !x.isBaby() && x.getRank() == WCatEntity.Rank.WARRIOR
                && site != null && site.denAt(x.getHomePosition()) == DenKind.NURSERY).findFirst().map(Cats::plainName).orElse(null);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate(leafBare ? "The fresh-kill pile is down to a few stringy scraps. The cold has driven the prey deep into their holes."
                : "The fresh-kill pile is almost bare. Prey has been scarce for days."));
        if (queen != null) lines.add(Line.say(queen, info.colour, "My kits are hungry, and there's barely a mouse left..."));
        lines.add(Line.say(deputy, info.colour, "We can't let the elders and queens go hungry. "
                + (party != null && !party.isEmpty() ? String.join(", ", party.stream().map(Cats::plainName).toList()) + ", hunt hard today. " : "")
                + "Every cat who can hunt, hunt!"));
        Announce.tell(ow, c, Cats.campCentre(ow, c), lines);
        EventNews.record(c, "Prey ran short in " + info.title + "'s camp" + (leafBare ? " in the leaf-bare cold." : "."));
        return true;
    }

    // ---- 16: an elder tells the kits a story

    private static boolean story(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Landmark camp = Cats.camp(c);
        if (story != null || camp == null || !playerInCamp(ow, c)) return false;
        CampSite site = CampSite.of(ow, c, camp);
        if (site == null) return false;
        LifeData life = LifeData.get(ow);
        List<WCatEntity> home = cats.stream().filter(x -> free(x, c) && !site.outside(x.blockPosition())).toList();
        WCatEntity elder = home.stream().filter(x -> !x.isBaby() && CampRoutine.partOf(x, site) == CampRoutine.Part.ELDER).findFirst()
                .orElse(home.stream().filter(x -> x.getRank() == WCatEntity.Rank.WARRIOR && !Cats.isLeader(life, c, x)
                        && com.ninjacat.greatjourney.clanlore.traits.Traits.moons(x) >= 60)   // WCE stops counting at 12; Clan Lore counts on
                        .max(Comparator.comparingDouble(com.ninjacat.greatjourney.clanlore.traits.Traits::moons)).orElse(null));
        if (elder == null) return false;
        List<WCatEntity> young = home.stream().filter(x -> x.getRank() == WCatEntity.Rank.KIT || x.isBaby()).limit(3).toList();
        if (young.isEmpty()) young = home.stream().filter(x -> x.getRank() == WCatEntity.Rank.APPRENTICE).limit(2).toList();
        if (young.isEmpty()) return false;
        grab(elder);
        sit(elder);
        Scene s = new Scene(c, site, ow.getGameTime());
        s.lead = elder.getUUID();
        for (WCatEntity k : young) {
            grab(k);
            s.cats.add(k.getUUID());
        }
        story = s;
        return true;
    }

    private static void storyTick(ServerLevel ow) {
        Scene s = story;
        if (s == null) return;
        long now = ow.getGameTime();
        if (!(ow.getEntity(s.lead) instanceof WCatEntity elder) || !elder.isAlive()) {
            endScene(ow, s);
            story = null;
            return;
        }
        if (s.phase == 0) {
            List<WCatEntity> young = new ArrayList<>();
            boolean all = true;
            int i = 0;
            for (UUID id : s.cats) {
                if (!(ow.getEntity(id) instanceof WCatEntity k) || !k.isAlive()) continue;
                young.add(k);
                if (k.distanceTo(elder) < 2.5) {
                    if (!k.isOrderedToSit()) sit(k);
                } else {
                    all = false;
                    double a = elder.getYRot() * Math.PI / 180 + i * 1.2;
                    k.getNavigation().moveTo(elder.getX() - Math.sin(a) * 1.6, elder.getY(), elder.getZ() + Math.cos(a) * 1.6, 0.9);
                }
                i++;
            }
            if (young.isEmpty()) {
                endScene(ow, s);
                story = null;
                return;
            }
            if (!all && now - s.started < 20 * 20) return;
            Announce.witnesses(ow, elder.blockPosition(), tale(ow, s.clan, elder, young));
            s.phase = 1;
            s.phaseAt = now;
            return;
        }
        if (now - s.phaseAt > 20 * 45) {
            endScene(ow, s);
            story = null;
        }
    }

    /** One of the elders' tales: the old great Clans, the truce of the full moon, how the Clan began, or a boast from their own youth. */
    private static List<Line> tale(ServerLevel ow, Clan c, WCatEntity elder, List<WCatEntity> young) {
        RandomSource r = ow.getRandom();
        Info info = Info.of(c);
        String old = Cats.plainName(elder), one = Cats.plainName(young.get(0)), two = Cats.plainName(young.get(young.size() - 1));
        int colour = info.colour;
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(one, colour, Happenings.pick(r, "Tell us a story, " + old + "! Please?", old + ", tell us about the old days!")));
        switch (r.nextInt(4)) {
            case 0 -> {
                lines.add(Line.say(old, colour, "Hmph. All right, if you'll sit still. Long ago, before there were four Clans, there were the great Clans: LionClan, TigerClan and LeopardClan."));
                lines.add(Line.say(old, colour, "LionClan had golden manes like the sun, TigerClan prowled the shadows, and LeopardClan ran faster than any cat alive."));
                lines.add(Line.say(two, colour, "Were they bigger than badgers?"));
                lines.add(Line.say(old, colour, "Much bigger! They hunt with StarClan now, and a little of them lives on in every Clan cat."));
            }
            case 1 -> {
                lines.add(Line.say(two, colour, "Why don't the Clans fight at Gatherings?"));
                lines.add(Line.say(old, colour, "Because when the moon is full, StarClan watches over us all. Long ago the first leaders agreed that no cat would raise a claw under a full moon."));
                lines.add(Line.say(old, colour, "And if a Clan ever breaks the truce, StarClan hides the moon behind clouds, to show how angry they are."));
                lines.add(Line.narrate("The young cats stare up at the sky with wide eyes."));
            }
            case 2 -> {
                String[] founder = switch (c) {
                    case THUNDER -> new String[] {"Thunder", "They hunted under the great oaks, and were as brave as a storm."};
                    case RIVER -> new String[] {"River Ripple", "He taught them to swim and to fish, where other cats feared to put a paw."};
                    case WIND -> new String[] {"Wind Runner", "She ran with the wind across the open moor, faster than the hares."};
                    case SHADOW -> new String[] {"Tall Shadow", "She made a home in the dark pines, where only the boldest cats would hunt."};
                };
                lines.add(Line.say(old, colour, "Seasons upon seasons ago, before the warrior code, a cat called " + founder[0] + " gathered the first " + info.title + " cats together."));
                lines.add(Line.say(old, colour, founder[1] + " That's why we are " + info.title + ", and why we always will be."));
                lines.add(Line.say(two, colour, "I'm going to be just like " + founder[0] + "!"));
            }
            default -> {
                lines.add(Line.say(old, colour, "When I was an apprentice, I caught a rabbit bigger than I was. Dragged it all the way home by myself."));
                lines.add(Line.say(two, colour, "Bigger than you? Really?"));
                lines.add(Line.say(old, colour, "Well... nearly as big. It was a very big rabbit. Now off you go, before I start on the one about the fox."));
            }
        }
        lines.add(Line.narrate("The young cats pad off, whispering about the story."));
        return lines;
    }

    // ---- scenes and the cats they borrow

    /** One running scene: the cats it borrows from a camp, the creature in it, and where it has got to. */
    private static final class Scene {
        final Clan clan;
        final CampSite site;
        final long started;
        int phase;
        long phaseAt;
        UUID lead, other, creature;
        String name;
        BlockPos spot;
        Walk walk, walk2;
        final List<UUID> cats = new ArrayList<>();
        final Map<UUID, Walk> walks = new HashMap<>();

        Scene(Clan clan, CampSite site, long started) {
            this.clan = clan;
            this.site = site;
            this.started = started;
            this.phaseAt = started;
        }

        boolean holds(UUID id) {
            return id.equals(lead) || id.equals(other) || cats.contains(id);
        }
    }

    private static void endScene(ServerLevel ow, Scene s) {
        List<UUID> all = new ArrayList<>(s.cats);
        if (s.lead != null) all.add(s.lead);
        if (s.other != null) all.add(s.other);
        for (UUID id : all) release(ow.getEntity(id), s.site);
    }

    /** Once a second: the scenes with borrowed cats move on. */
    static void everySecond(ServerLevel ow) {
        adderTick(ow);
        kittypetTick(ow);
        storyTick(ow);
    }

    /** A cat still marked as borrowed loads when no scene holds it (a restart, or its scene ended while it was unloaded): it is given back. */
    static void onJoin(Entity e) {
        if (!(e instanceof WCatEntity cat) || !cat.getTags().contains(EVENT_CAT)) return;
        UUID id = cat.getUUID();
        for (Scene s : new Scene[] {adder, kittypet, story}) if (s != null && s.holds(id)) return;
        release(cat, null);
    }

    /** Whether an event may borrow this cat: one of the Clan's own, not busy with anything else. */
    static boolean free(WCatEntity cat, Clan c) {
        Set<String> tags = cat.getTags();
        return cat.isAlive() && !cat.isPassenger() && !cat.isBeingCarried && cat.isTame()
                && BookClans.standInLeader(c).equals(cat.getOwnerUUID())       // a player's own cat is theirs
                && !tags.contains(BookClans.BUSY_TAG) && !tags.contains(Happenings.LOST_TAG)
                && !ClanEvents.activeEntities.contains(cat.getUUID()) && !ClanEvents.raiders.containsKey(cat.getUUID())
                && !cat.onBorderPatrolFlag && !cat.onHuntingPatrolFlag && !cat.returnHomeFlag && !cat.returningFromPatrol
                && !cat.tellingCatsToPatrol && !cat.leaderCallingToFollowFlag && !cat.leaderCallingToSitFlag;
    }

    /**
     * Borrows a cat: busy (so the camp routine lets go of it) and in FOLLOW mode, whose owner is the Clan's stand-in
     * leader and never online, so Warrior Cats Events' own wandering, patrols and homing leave it to us.
     */
    private static void grab(WCatEntity cat) {
        cat.addTag(BookClans.BUSY_TAG);
        cat.addTag(EVENT_CAT);
        cat.setOrderedToSit(false);
        cat.setInSittingPose(false);
        if (cat.isResting()) cat.setResting(false, 0);
        if (cat.isChilling()) cat.setChilling(false, 0);
        cat.setTarget(null);
        cat.mode = WCatEntity.CatMode.FOLLOW;
        cat.getNavigation().stop();
    }

    private static void sit(WCatEntity cat) {
        cat.getNavigation().stop();
        cat.mode = WCatEntity.CatMode.SIT;
        cat.setOrderedToSit(true);
        cat.setInSittingPose(true);
    }

    /** Gives a borrowed cat back: to the camp routine in camp; from outside it walks home to its nest. */
    private static void release(Entity e, CampSite site) {
        if (!(e instanceof WCatEntity cat) || !cat.getTags().contains(EVENT_CAT)) return;
        cat.removeTag(EVENT_CAT);
        cat.removeTag(BookClans.BUSY_TAG);
        cat.setOrderedToSit(false);
        cat.setInSittingPose(false);
        cat.mode = WCatEntity.CatMode.WANDER;
        cat.getNavigation().stop();
        if (site == null) return;
        if (site.outside(cat.blockPosition())) cat.returnHomeFlag = cat.getHomePosition() != null;
        else cat.wanderCenter = site.centre;
    }

    /** A cat's walk through a list of stops: through the camp's way in whenever it crosses the barrier. */
    private static final class Walk {
        final List<BlockPos> stops;
        int at;
        double best = Double.MAX_VALUE;
        int stuck;

        Walk(List<BlockPos> stops) {
            this.stops = stops;
        }

        boolean done() {
            return at >= stops.size();
        }

        /**
         * Once a second, one step on; true once the last stop is reached. A cat that stops getting nearer for ten
         * seconds hops to its next stop, but only where no player is near enough to see it (as the camp routine does).
         */
        boolean step(ServerLevel ow, Mob m, double speed) {
            while (!done()) {
                BlockPos to = stops.get(at);
                double d = Math.hypot(m.getX() - (to.getX() + 0.5), m.getZ() - (to.getZ() + 0.5));
                if (d < 2.0 && Math.abs(m.getY() - to.getY()) < 3) {
                    next();
                    continue;
                }
                if (d < best - 0.5) {
                    best = d;
                    stuck = 0;
                } else if (++stuck >= 10 && ow.hasChunk(to.getX() >> 4, to.getZ() >> 4) && !watched(ow, m.blockPosition(), 24) && !watched(ow, to, 24)) {
                    m.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
                    next();
                    continue;
                }
                m.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, speed);
                return false;
            }
            return true;
        }

        private void next() {
            at++;
            best = Double.MAX_VALUE;
            stuck = 0;
        }
    }

    /** Stops from one spot to another, through the way in when one is inside the camp and the other is not. */
    private static List<BlockPos> route(CampSite site, BlockPos from, BlockPos to) {
        List<BlockPos> out = new ArrayList<>();
        if (site != null && !site.way.isEmpty()) {
            boolean fromOut = site.outside(from), toOut = site.outside(to);
            if (!fromOut && toOut) {
                out.addAll(site.way);
            } else if (fromOut && !toOut) {
                List<BlockPos> in = new ArrayList<>(site.way);
                Collections.reverse(in);
                out.addAll(in);
            }
        }
        out.add(to);
        return out;
    }

    // ---- places and creatures

    private static boolean watched(ServerLevel ow, BlockPos p, double reach) {
        for (ServerPlayer pl : ow.players()) {
            if (!pl.isSpectator() && Math.hypot(pl.getX() - (p.getX() + 0.5), pl.getZ() - (p.getZ() + 0.5)) <= reach) return true;
        }
        return false;
    }

    /** A landmark of these kinds within {@code fromCamp} of the Clan's camp, with a player by it and its creatures moving; null if none. */
    private static Landmark watchedPlace(ServerLevel ow, Clan c, Set<Landmark.Kind> kinds, double fromCamp) {
        ClanWorld w = ClanWorld.current();
        Landmark camp = Cats.camp(c);
        if (w == null || camp == null) return null;
        for (Landmark l : Homeland.map(w).landmarks()) {
            if (!kinds.contains(l.kind()) || Math.hypot(l.x() - camp.x(), l.z() - camp.z()) > fromCamp) continue;
            BlockPos mid = BlockPos.containing(l.x(), 64, l.z());
            if (ow.isPositionEntityTicking(mid) && watched(ow, mid, l.radius() + 40)) return l;
        }
        return null;
    }

    /** The edge of a landmark on the side facing the camp, out in the open past its buildings; its middle if that is not live. */
    private static BlockPos edgeToward(ServerLevel ow, Landmark l, Landmark camp) {
        double dx = camp.x() - l.x(), dz = camp.z() - l.z(), len = Math.max(0.1, Math.hypot(dx, dz)), d = l.radius() + 4;
        int x = (int) Math.floor(l.x() + dx / len * d), z = (int) Math.floor(l.z() + dz / len * d);
        if (ow.hasChunk(x >> 4, z >> 4)) {
            BlockPos p = Cats.surface(ow, x, z);
            if (ow.isPositionEntityTicking(p)) return p;
        }
        return Cats.surface(ow, (int) Math.floor(l.x()), (int) Math.floor(l.z()));
    }

    private static String placeName(Landmark.Kind k) {
        return switch (k) {
            case HORSEPLACE -> "the horseplace";
            case KITTYPET_HOME -> "the kittypet gardens";
            case TWOLEG_NEST -> "the Twoleg nest";
            case BARLEY_FARM -> "Barley's farm";
            default -> "the Twoleg farms";
        };
    }

    /** A live spot within {@code spread} blocks of {@code at}, else {@code at}. */
    private static BlockPos near(ServerLevel ow, BlockPos at, RandomSource r, int spread) {
        int x = at.getX() + r.nextInt(spread * 2 + 1) - spread, z = at.getZ() + r.nextInt(spread * 2 + 1) - spread;
        if (!ow.hasChunk(x >> 4, z >> 4)) return at;
        BlockPos p = Cats.surface(ow, x, z);
        return ow.isPositionEntityTicking(p) && Math.abs(p.getY() - at.getY()) < 4 ? p : at;
    }

    /** A spot {@code dist} blocks on from {@code from}, straight away from the camp's middle; {@code from} where that land is not loaded. */
    private static BlockPos awayFrom(ServerLevel ow, Clan c, BlockPos from, double dist) {
        Landmark camp = Cats.camp(c);
        if (camp == null) return from;
        double dx = from.getX() + 0.5 - camp.x(), dz = from.getZ() + 0.5 - camp.z(), len = Math.max(0.1, Math.hypot(dx, dz));
        int x = (int) Math.floor(from.getX() + 0.5 + dx / len * dist), z = (int) Math.floor(from.getZ() + 0.5 + dz / len * dist);
        return ow.hasChunk(x >> 4, z >> 4) ? Cats.surface(ow, x, z) : from;
    }

    private static List<WCatEntity> clanCatsNear(ServerLevel ow, Clan c, BlockPos at, double reach) {
        UUID id = LifeData.get(ow).clanId(c);
        if (id == null) return List.of();
        return ow.getEntitiesOfClass(WCatEntity.class, new AABB(at).inflate(reach, 24, reach), x -> x.isAlive() && id.equals(x.getClanUUID()));
    }

    /** The nearest of the Clan's grown cats, or a player, within reach of a creature; kits are never its first choice. */
    private static LivingEntity nearestCat(ServerLevel ow, Clan c, Mob from, double reach) {
        LivingEntity best = null;
        double bestD = reach * reach;
        for (WCatEntity cat : clanCatsNear(ow, c, from.blockPosition(), reach)) {
            if (cat.isBaby() || cat.getRank() == WCatEntity.Rank.KIT || cat.getTags().contains(EVENT_CAT)) continue;
            double d = cat.distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = cat;
            }
        }
        for (ServerPlayer p : ow.players()) {
            if (p.isSpectator() || p.isCreative()) continue;
            double d = p.distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static Mob create(ServerLevel ow, ResourceLocation id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        Entity e = type == null ? null : type.create(ow);
        return e instanceof Mob m ? m : null;
    }

    private static void put(ServerLevel ow, Mob m, BlockPos at, RandomSource r) {
        m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
        m.finalizeSpawn(ow, ow.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
    }

    private static void setBase(AttributeInstance a, double v) {
        if (a != null) a.setBaseValue(v);
    }
}
