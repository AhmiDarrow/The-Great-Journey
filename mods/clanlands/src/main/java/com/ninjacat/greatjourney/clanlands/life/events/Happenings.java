package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.ClanNames;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/** The events themselves: each one sets something happening in a camp and tells the Clan in its cats' voices. */
final class Happenings {
    private Happenings() {}

    private static final long BADGER_TICKS = 20L * 60 * 4;
    private static final long RAID_TICKS = 20L * 60 * 3;
    private static final long LOST_TICKS = 20L * 60 * 20;

    /** Marks a kit that is out of camp, lost. Saved with the kit, so one left lost by a restart still finds its way home. */
    static final String LOST_TAG = "greatjourney_lost_kit";

    /** The lost kit, while one is lost: the kit, and the player leading it home once found. */
    private static UUID lostKit;
    private static UUID guide;

    static void reset() {
        lostKit = null;
        guide = null;
    }

    static boolean run(ServerLevel ow, Clan c, ClanEvents.Kind kind, RandomSource r) {
        List<WCatEntity> cats = Cats.atCamp(ow, c);
        if (cats.isEmpty()) return false;
        return switch (kind) {
            case BADGER -> badger(ow, c, cats, r);
            case PREDATOR -> predator(ow, c, cats, r);
            case RAID -> raid(ow, c, cats, r);
            case INJURY -> injury(ow, c, cats, r);
            case SICKNESS -> sickness(ow, c, cats, r);
            case KITS -> kits(ow, c, cats, r);
            case CEREMONY -> ceremony(ow, c, cats, r);
            case HUNT -> hunt(ow, c, cats, r);
            case LOST_KIT -> lostKit(ow, c, cats, r);
            case OLD_AGE -> false;
            case FREEZE, FLOOD, DROUGHT, FIRE, FOG -> Weather.start(ow, c, kind, r, false);
        };
    }

    /** One of a few ways of saying the same thing, so the Clan doesn't repeat itself word for word. */
    static String pick(RandomSource r, String... ways) {
        return ways[r.nextInt(ways.length)];
    }

    private static Info info(Clan c) {
        return Info.of(c);
    }

    private static List<WCatEntity> fighters(List<WCatEntity> cats) {
        return cats.stream().filter(x -> x.getRank() == WCatEntity.Rank.WARRIOR || x.getRank() == WCatEntity.Rank.DEPUTY).toList();
    }

    private static BlockPos campEdge(ServerLevel ow, Clan c, RandomSource r, double extra) {
        Landmark camp = Cats.camp(c);
        double a = r.nextDouble() * Math.PI * 2, d = Math.max(6, camp.radius() - 6) + extra;
        return Cats.surface(ow, (int) Math.floor(camp.x() + Math.cos(a) * d), (int) Math.floor(camp.z() + Math.sin(a) * d));
    }

    private static void rally(List<WCatEntity> defenders, LivingEntity foe, int max) {
        int n = 0;
        for (WCatEntity d : defenders) {
            if (n++ >= max) break;
            d.setTarget(foe);
        }
    }

    // 1: a badger blunders into camp
    private static boolean badger(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Mob badger = ModEntities.BADGER.get().create(ow);
        if (badger == null) return false;
        BlockPos at = campEdge(ow, c, r, 2);
        badger.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
        badger.finalizeSpawn(ow, ow.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        badger.setCustomName(Component.literal("Badger"));
        ow.addFreshEntity(badger);
        List<WCatEntity> kits = Cats.withRank(cats, WCatEntity.Rank.KIT);
        if (!kits.isEmpty()) badger.setTarget(kits.get(0));
        rally(fighters(cats), badger, 4);
        start(ow, ClanEvents.Kind.BADGER, c, BADGER_TICKS, badger.getUUID());
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        Announce.tell(ow, c, at,
                Line.narrate("A heavy, musty stink rolls into " + info(c).title + "'s camp..."),
                Line.say(deputy, info(c).colour, "Badger! Protect the nursery! Warriors, with me!"));
        return true;
    }

    // 2: a fox or an eagle prowls near the nursery
    private static boolean predator(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        boolean eagle = c == Clan.WIND || r.nextInt(3) == 0;
        Mob foe = eagle ? ModEntities.EAGLE.get().create(ow) : EntityType.FOX.create(ow);
        if (foe == null) return false;
        BlockPos at = campEdge(ow, c, r, 8).above(eagle ? 12 : 0);
        foe.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
        foe.finalizeSpawn(ow, ow.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        ow.addFreshEntity(foe);
        rally(fighters(cats), foe, 2);
        String who = Cats.withRank(cats, WCatEntity.Rank.APPRENTICE).stream().findFirst().map(Cats::plainName)
                .orElse(Cats.speaker(cats, WCatEntity.Rank.WARRIOR, "A warrior"));
        Announce.tell(ow, c, at, Line.say(who, info(c).colour,
                eagle ? pick(r, "Look up! An eagle is circling over the nursery! Get the kits inside!",
                                "Eagle! Everyone under cover, now! Keep the kits close!")
                        : pick(r, "Fox! There's a fox sniffing round the camp wall!",
                                "I smell fox, fresh and close! It's prowling near the nursery!")));
        return true;
    }

    // 3: a neighbouring Clan's patrol raids the border
    private static boolean raid(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        List<Clan> sides = Cats.neighbours(c);
        Clan enemy = sides.get(r.nextInt(sides.size()));
        BlockPos border = Cats.borderToward(ow, c, enemy);
        LifeData life = LifeData.get(ow);
        if (border == null || life.clanId(enemy) == null) return false;
        ClanNames names = new ClanNames(ow.getGameTime() ^ enemy.ordinal());
        List<WCatEntity> attackers = new ArrayList<>();
        int n = 2 + r.nextInt(2);
        BlockPos enemyHome = Cats.campCentre(ow, enemy);
        for (int i = 0; i < n; i++) {
            ClanNames.Name name = names.warrior();
            BlockPos at = Cats.surface(ow, border.getX() + r.nextInt(5) - 2, border.getZ() + r.nextInt(5) - 2);
            WCatEntity cat = Cats.spawn(ow, enemy, WCatEntity.Rank.WARRIOR, name.prefix(), name.full(), r.nextInt(2), at, enemyHome, r);
            if (cat == null) continue;
            attackers.add(cat);
            ClanEvents.raiders.put(cat.getUUID(), enemy);
        }
        if (attackers.isEmpty()) return false;
        List<WCatEntity> defenders = new ArrayList<>(fighters(cats));
        for (int i = 0; i < attackers.size() && i < defenders.size(); i++) {
            WCatEntity a = attackers.get(i), d = defenders.get(i);
            a.setTarget(d);
            d.setTarget(a);
            d.wanderCenter = border;
        }
        start(ow, ClanEvents.Kind.RAID, c, RAID_TICKS, attackers.stream().map(Entity::getUUID).toArray(UUID[]::new));
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, life.leader(c));
        Announce.tell(ow, c, border,
                Line.say(Cats.plainName(attackers.get(0)), info(enemy).colour, "This land belongs to " + info(enemy).title + " now!"),
                Line.say(deputy, info(c).colour, info(enemy).title + " warriors on our border, " + Cats.direction(Cats.camp(c).x(), Cats.camp(c).z(), border.getX(), border.getZ())
                        + " of camp! Drive them back!"));
        return true;
    }

    // 4: a clanmate is hurt on patrol
    private static boolean injury(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        List<WCatEntity> able = cats.stream().filter(x -> x.getRank() == WCatEntity.Rank.WARRIOR || x.getRank() == WCatEntity.Rank.APPRENTICE)
                .filter(x -> !x.hasDisease(DiseaseTypes.BROKEN_PAW) && !x.hasDisease(DiseaseTypes.SORE_PADS)).toList();
        if (able.isEmpty()) return false;
        WCatEntity hurt = able.get(r.nextInt(able.size()));
        boolean broken = r.nextInt(3) == 0;
        hurt.addDisease(broken ? DiseaseTypes.BROKEN_PAW : DiseaseTypes.SORE_PADS, true);
        hurt.setHealth(Math.max(2, hurt.getHealth() * 0.6F));
        String med = Cats.speaker(cats, WCatEntity.Rank.MEDICINE, "The medicine cat");
        String name = Cats.plainName(hurt);
        Announce.tell(ow, c, hurt.blockPosition(),
                Line.narrate(name + (broken
                        ? pick(r, " fell from a tree while chasing a squirrel, and limps back into camp holding up a paw.",
                                " slipped on wet rocks by the stream, and limps back into camp holding up a paw.",
                                " limps back into camp, holding up a paw.")
                        : pick(r, " walked too far over stony ground, and limps back into camp on sore, cracked pads.",
                                " limps back into camp on sore, cracked pads.",
                                " crossed a hot, stony stretch of ground, and limps home on sore pads."))),
                Line.say(med, info(c).colour, broken
                        ? "That paw is broken. Lie still in my den, " + name + ". A comfrey poultice and a leg wrap will help it mend."
                        : "Sore pads. A dock or yarrow poultice will soothe them. Could someone fetch me some?"));
        return true;
    }

    // 5: sickness, worse in leaf-bare
    private static boolean sickness(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        boolean leafBare = Seasons.isLeafBare(ow);
        List<WCatEntity> well = cats.stream().filter(x -> !x.hasDisease(DiseaseTypes.WHITECOUGH) && !x.hasDisease(DiseaseTypes.GREENCOUGH)
                && x.getRank() != WCatEntity.Rank.MEDICINE).toList();
        if (well.isEmpty()) return false;
        int n = Math.min(well.size(), 1 + (leafBare ? r.nextInt(2) : 0));
        boolean green = leafBare && r.nextInt(3) == 0;
        List<String> sick = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            WCatEntity cat = well.get(r.nextInt(well.size()));
            cat.addDisease(green ? DiseaseTypes.GREENCOUGH : DiseaseTypes.WHITECOUGH, true);
            sick.add(Cats.plainName(cat));
        }
        String med = Cats.speaker(cats, WCatEntity.Rank.MEDICINE, "The medicine cat");
        Announce.tell(ow, c, Cats.campCentre(ow, c),
                Line.narrate("Coughing echoes from the dens: " + String.join(" and ", sick) + (sick.size() > 1 ? " are" : " is") + " sick."),
                Line.say(med, info(c).colour, green
                        ? "It's greencough. We need catmint, and quickly. Search the old Twoleg places and the sheltered banks."
                        : "Whitecough. Catmint or juniper berries will cure it, before it turns to greencough."));
        return true;
    }

    // 6: kits are born in the nursery
    private static boolean kits(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        LifeData life = LifeData.get(ow);
        List<WCatEntity> shes = cats.stream().filter(x -> x.getGender() == 1 && x.getRank() == WCatEntity.Rank.WARRIOR
                && !Cats.isLeader(life, c, x) && !x.isBaby()).toList();
        if (shes.isEmpty() || Cats.withRank(cats, WCatEntity.Rank.KIT).size() >= 6) return false;
        WCatEntity mother = shes.get(r.nextInt(shes.size()));
        Set<String> used = Cats.namesIn(cats);
        ClanNames names = new ClanNames(ow.getGameTime() ^ mother.getUUID().getLeastSignificantBits());
        int n = 1 + r.nextInt(3);
        BlockPos home = mother.getHomePosition() != null ? mother.getHomePosition() : Cats.campCentre(ow, c);
        List<String> born = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ClanNames.Name name = names.kit();
            for (int t = 0; t < 10 && used.contains(name.full().toLowerCase(java.util.Locale.ROOT)); t++) name = names.kit();
            used.add(name.full().toLowerCase(java.util.Locale.ROOT));
            WCatEntity kit = Cats.spawn(ow, c, WCatEntity.Rank.KIT, name.prefix(), name.full(), r.nextInt(2), home, home, r);
            if (kit == null) continue;
            kit.setMother(Component.literal(Cats.plainName(mother)));
            kit.setMotherUUID(mother.getUUID());
            born.add(name.full());
        }
        if (born.isEmpty()) return false;
        Announce.tell(ow, c, home,
                Line.narrate("Tiny mews from the nursery! " + Cats.plainName(mother) + " has had " + (born.size() == 1 ? "a kit" : born.size() + " kits") + "."),
                Line.say(Cats.plainName(mother), info(c).colour, pick(r,
                        "Meet " + String.join(", ", born) + ". The Clan grows stronger.",
                        "This is " + String.join(", ", born) + ". Aren't they perfect? They'll be fine warriors one day.",
                        "Shh, they're sleeping. " + String.join(", ", born) + ". StarClan has blessed " + info(c).title + ".")));
        return true;
    }

    // 7: an apprentice earns their warrior name
    private static boolean ceremony(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        List<WCatEntity> ready = cats.stream().filter(x -> x.getRank() == WCatEntity.Rank.APPRENTICE && x.getAgeInMoons() >= 12).toList();
        if (ready.isEmpty()) return false;
        WCatEntity app = ready.get(r.nextInt(ready.size()));
        String before = Cats.plainName(app);
        String after = makeWarrior(app, Cats.namesIn(cats), r);
        String leader = LifeData.get(ow).leader(c);
        Announce.tell(ow, c, Cats.campCentre(ow, c),
                Line.say(leader, info(c).colour, "Let all cats old enough to catch their own prey gather for a Clan meeting!"),
                Line.say(leader, info(c).colour, before + ", you have trained hard and served your Clan well. From this moment you will be known as "
                        + after + ". StarClan honours your courage and your loyalty."),
                Line.narrate("The Clan calls the new warrior's name: " + after + "! " + after + "!"));
        return true;
    }

    /** Makes an apprentice a warrior: keeps its prefix, gives a new suffix. Returns the new name. */
    static String makeWarrior(WCatEntity app, Set<String> used, RandomSource r) {
        String prefix = Cats.prefixOf(app);
        String name = Cats.warriorName(prefix, r, used);
        Cats.rename(app, prefix, name);
        app.setRank(WCatEntity.Rank.WARRIOR);
        app.setAppScale(false);
        return name;
    }

    // 8: the fresh-kill pile runs low and a hunting patrol goes out
    private static boolean hunt(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        Map<Integer, BlockPos> waypoints = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            BlockPos p = Cats.inTerritory(ow, c, r, 30, 120);
            if (p != null) waypoints.put(waypoints.size(), p);
        }
        if (waypoints.isEmpty()) return false;
        List<WCatEntity> party = fighters(cats).stream().limit(3).toList();
        for (WCatEntity w : party) w.setOnHuntingPatrol(new HashMap<>(waypoints));
        boolean fog = Weather.foggy(ow);
        for (BlockPos p : waypoints.values()) {
            for (int i = 0; i < (fog ? 1 : 2); i++) {
                if (fog && r.nextBoolean()) continue;                  // in the mist, half the prey slips away
                Mob prey = switch (r.nextInt(3)) {
                    case 0 -> ModEntities.MOUSE.get().create(ow);
                    case 1 -> ModEntities.SQUIRREL.get().create(ow);
                    default -> ModEntities.PIGEON.get().create(ow);
                };
                if (prey == null) continue;
                BlockPos at = Cats.surface(ow, p.getX() + r.nextInt(9) - 4, p.getZ() + r.nextInt(9) - 4);
                prey.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
                ow.addFreshEntity(prey);
            }
        }
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        String who = party.isEmpty() ? "" : " " + String.join(", ", party.stream().map(Cats::plainName).toList()) + ", go.";
        Announce.tell(ow, c, Cats.campCentre(ow, c),
                Line.say(deputy, info(c).colour, pick(r, "The fresh-kill pile is almost empty. We need a hunting patrol.",
                        "The elders and queens need feeding, and the fresh-kill pile is bare. Hunting patrol!",
                        "Prey's running in the territory today. Let's fill the fresh-kill pile.") + who),
                Line.narrate("Prey is stirring in " + info(c).title + "'s territory. Any warrior who hunts today feeds the Clan."));
        return true;
    }

    // 9: a kit wanders off
    private static boolean lostKit(ServerLevel ow, Clan c, List<WCatEntity> cats, RandomSource r) {
        List<WCatEntity> kits = Cats.withRank(cats, WCatEntity.Rank.KIT);
        BlockPos spot = Cats.inTerritory(ow, c, r, 60, 120);
        if (kits.isEmpty() || spot == null) return false;
        WCatEntity kit = kits.get(r.nextInt(kits.size()));
        kit.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        kit.wanderCenter = spot;
        kit.addTag(LOST_TAG);
        lostKit = kit.getUUID();
        guide = null;
        start(ow, ClanEvents.Kind.LOST_KIT, c, LOST_TICKS, kit.getUUID());
        Landmark camp = Cats.camp(c);
        String mother = kit.getMother() != null && !kit.getMother().getString().isBlank() ? kit.getMother().getString()
                : Cats.speaker(cats, WCatEntity.Rank.WARRIOR, "A queen");
        Announce.tell(ow, c, Cats.campCentre(ow, c),
                Line.say(mother, info(c).colour, "Has anyone seen " + Cats.plainName(kit) + "? They were here a moment ago!"),
                Line.say(Cats.speaker(cats, WCatEntity.Rank.DEPUTY, "The deputy"), info(c).colour,
                        "Their scent heads " + Cats.direction(camp.x(), camp.z(), spot.getX(), spot.getZ()) + ", out of camp. Find them before dark!"));
        return true;
    }

    private static void start(ServerLevel ow, ClanEvents.Kind k, Clan c, long ticks, UUID... who) {
        if (!k.major) return;
        ClanEvents.activeKind = k;
        ClanEvents.activeClan = c;
        ClanEvents.activeUntil = ow.getGameTime() + ticks;
        ClanEvents.activeEntities.clear();
        ClanEvents.activeEntities.addAll(List.of(who));
    }

    private static void finish() {
        ClanEvents.activeKind = null;
        ClanEvents.activeClan = null;
        ClanEvents.activeEntities.clear();
        lostKit = null;
        guide = null;
    }

    /** Once a second: big events time out, the lost kit is found and led home. */
    static void everySecond(ServerLevel ow) {
        ClanEvents.Kind k = ClanEvents.activeKind;
        if (k == null) return;
        Clan c = ClanEvents.activeClan;
        Info info = Info.of(c);
        boolean late = ow.getGameTime() > ClanEvents.activeUntil;
        switch (k) {
            case BADGER -> {
                Entity b = first(ow);
                if (b == null) {
                    // gone, or only out of reach (its chunk unloaded): give it until the event's end to come back
                    if (late) finish();
                } else if (late) {
                    b.discard();
                    Announce.tell(ow, c, b.blockPosition(), Line.narrate("The badger lumbers away into the undergrowth, grumbling."));
                    finish();
                }
            }
            case RAID -> {
                if (late) {
                    BlockPos where = null;
                    for (UUID id : List.copyOf(ClanEvents.activeEntities)) {
                        Entity e = ow.getEntity(id);
                        if (e instanceof WCatEntity cat) {
                            where = cat.blockPosition();
                            retreat(ow, cat);
                        }
                    }
                    if (where != null) Announce.tell(ow, c, where, Line.narrate("The raiders turn tail and flee back across the border."));
                    finish();
                } else if (ClanEvents.activeEntities.isEmpty()) {
                    List<WCatEntity> cats = Cats.atCamp(ow, c);
                    Announce.tell(ow, c, Cats.campCentre(ow, c), Line.say(Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c)),
                            info.colour, "They're gone! Well fought, everyone. Our border is safe again."));
                    finish();
                }
            }
            case LOST_KIT -> lostKitTick(ow, c, info, late);
            default -> finish();
        }
    }

    private static Entity first(ServerLevel ow) {
        for (UUID id : ClanEvents.activeEntities) {
            Entity e = ow.getEntity(id);
            if (e != null && e.isAlive()) return e;
        }
        return null;
    }

    private static void lostKitTick(ServerLevel ow, Clan c, Info info, boolean late) {
        Entity found = ow.getEntity(lostKit);
        if (found == null) {
            // its chunk is not loaded: wait; past the deadline the kit keeps its tag and goes home when it loads
            if (late) finish();
            return;
        }
        if (!(found instanceof WCatEntity kit) || !kit.isAlive()) {
            finish();
            return;
        }
        Landmark camp = Cats.camp(c);
        BlockPos centre = Cats.campCentre(ow, c);
        if (Math.hypot(kit.getX() - camp.x(), kit.getZ() - camp.z()) < Math.max(10, camp.radius() - 4)) {
            kit.wanderCenter = centre;
            kit.removeTag(LOST_TAG);
            String helper = guide != null && ow.getServer().getPlayerList().getPlayer(guide) instanceof ServerPlayer p ? Joining.catName(p) : null;
            Announce.tell(ow, c, centre, Line.say(kit.getMother() != null && !kit.getMother().getString().isBlank()
                            ? kit.getMother().getString() : LifeData.get(ow).leader(c), info.colour,
                    Cats.plainName(kit) + ", you're safe!" + (helper != null ? " Thank you, " + helper + ". " + info.title + " won't forget this." : "")));
            finish();
            return;
        }
        if (late) {
            kit.teleportTo(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5);
            kit.wanderCenter = centre;
            kit.removeTag(LOST_TAG);
            Announce.tell(ow, c, centre, Line.narrate("A patrol carries " + Cats.plainName(kit) + " home by the scruff, tired but unhurt."));
            finish();
            return;
        }
        if (guide == null) {
            for (ServerPlayer p : ow.players()) {
                if (p.distanceTo(kit) < 4) {
                    guide = p.getUUID();
                    com.ninjacat.greatjourney.clanlands.life.Dialog.play(p,
                            Line.say(Cats.plainName(kit), info.colour, "I got lost chasing a butterfly... Will you take me home?"),
                            Line.narrate("Lead " + Cats.plainName(kit) + " back to " + info.title + "'s camp. They will follow you."));
                    break;
                }
            }
        }
        if (guide != null && ow.getServer().getPlayerList().getPlayer(guide) instanceof ServerPlayer p && p.level() == ow && p.distanceTo(kit) < 40) {
            kit.wanderCenter = p.blockPosition();
            kit.getNavigation().moveTo(p, 1.15);
        }
    }

    /**
     * A kit still marked lost loads when no search is running for it (the server restarted, or the search ran out
     * while its chunk was unloaded): a patrol has found it, and it heads home.
     */
    static void onJoin(Entity e) {
        if (!(e instanceof WCatEntity kit) || !kit.getTags().contains(LOST_TAG) || kit.getUUID().equals(lostKit)) return;
        kit.removeTag(LOST_TAG);
        LifeData life = LifeData.get(((ServerLevel) kit.level()).getServer().overworld());
        Clan c = life.clanOf(kit.getClanUUID());
        BlockPos home = kit.getHomePosition();
        if (c != null && Cats.camp(c) != null) {
            Landmark camp = Cats.camp(c);
            home = BlockPos.containing(camp.x(), home != null ? home.getY() : kit.getY(), camp.z());
        }
        if (home != null) kit.wanderCenter = home;
    }

    private static void retreat(ServerLevel ow, WCatEntity cat) {
        LifeData life = LifeData.get(ow);
        Clan c = ClanEvents.raiders.get(cat.getUUID());
        UUID id = c == null ? null : life.clanId(c);
        if (id != null) ClanData.get(ow).removeClanCatFromClan(id, cat);
        ClanEvents.raiderGone(cat);
        cat.discard();
    }

    /** A raider badly hurt runs for home instead of fighting to the death. */
    static void onDamage(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof WCatEntity cat) || !(cat.level() instanceof ServerLevel level) || !ClanEvents.isRaider(cat)) return;
        if (cat.isAlive() && cat.getHealth() < cat.getMaxHealth() * 0.5F) {
            Clan home = ClanEvents.raiders.get(cat.getUUID());
            ServerLevel ow = level.getServer().overworld();
            if (ClanEvents.activeClan != null) {
                Announce.tell(ow, ClanEvents.activeClan, cat.blockPosition(), Line.narrate(Cats.plainName(cat) + " yowls and flees back toward "
                        + (home == null ? "their own land." : Info.of(home).title + " territory.")));
            }
            retreat(ow, cat);
        }
    }

    /** The badger or a predator is beaten: the Clan thanks whoever helped. */
    static void onCreatureDeath(LivingDeathEvent e) {
        Entity dead = e.getEntity();
        if (ClanEvents.activeKind != ClanEvents.Kind.BADGER || !ClanEvents.activeEntities.contains(dead.getUUID())) return;
        if (!(dead.level() instanceof ServerLevel level)) return;
        ServerLevel ow = level.getServer().overworld();
        Clan c = ClanEvents.activeClan;
        List<WCatEntity> cats = Cats.atCamp(ow, c);
        String deputy = Cats.speaker(cats, WCatEntity.Rank.DEPUTY, LifeData.get(ow).leader(c));
        String thanks = e.getSource().getEntity() instanceof ServerPlayer p ? " " + Joining.catName(p) + ", you fought like a true warrior!" : "";
        Announce.tell(ow, c, dead.blockPosition(), Line.say(deputy, Info.of(c).colour,
                "The badger is gone! Thank StarClan, the kits are safe." + thanks));
        finish();
    }
}
