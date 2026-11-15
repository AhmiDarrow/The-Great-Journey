package com.ninjacat.greatjourney.clanlore.role;

import com.ninjacat.greatjourney.clanlore.LoreHooks;
import com.ninjacat.greatjourney.clanlore.LoreHooks.Speech;
import com.ninjacat.greatjourney.clanlore.role.ClanCats.Voice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity.Rank;

/**
 * The ceremonies, seen on the server for any Warrior Cats Events Clan. WCE gives no signal when a cat changes, so each
 * Clan cat is looked at every two seconds and compared with how it was last seen (kept on the cat, so a change made
 * on its first tick after loading still counts):
 * <ul>
 *   <li>a kit WCE has made an apprentice (renamed "-paw") gets its apprentice ceremony and a mentor;</li>
 *   <li>an apprentice made a warrior, by WCE or by a pack, ends its pairing with its mentor; one WCE grew up is
 *   given its warrior name before the Clan (a pack's own ceremony speaks for itself);</li>
 *   <li>an old warrior or deputy asks to join the elders;</li>
 *   <li>a she-cat expecting kits, or with kits of her own in the nursery, is a queen until they are apprenticed;</li>
 *   <li>a leader loses a life to each death, until the last (for Clans the pack does not keep).</li>
 * </ul>
 */
final class Ceremonies {
    private Ceremonies() {}

    /** Ticks between looks at one cat. */
    static final int LOOK = 40;
    /** How far a queen's kits may be from her and still count as in the nursery with her. */
    static final double NURSERY_REACH = 48;
    /** Looks in a row (ten seconds apart) with no kits before a queen leaves the nursery. */
    static final int QUEEN_EMPTY_LOOKS = 6;

    /** Per Clan, the game time before which no other cat may retire. */
    private static final Map<UUID, Long> RETIRE_NEXT = new HashMap<>();

    static void init() {
        NeoForge.EVENT_BUS.addListener(Ceremonies::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.StartTracking e) -> {
            if (e.getTarget() instanceof WCatEntity cat && e.getEntity() instanceof ServerPlayer p && cat.hasData(Roles.DATA)) RoleNet.startSeeing(p, cat);
        });
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (e.getEntity() instanceof WCatEntity cat && !e.getLevel().isClientSide() && !cat.isAnImage()) MentorWalk.addTo(cat);
        });
        NeoForge.EVENT_BUS.addListener(Ceremonies::onDeath);
        NeoForge.EVENT_BUS.addListener(Ceremonies::onTarget);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> RETIRE_NEXT.clear());
    }

    private static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof WCatEntity cat) || !(cat.level() instanceof ServerLevel level)) return;
        if ((cat.tickCount + cat.getId()) % LOOK != 0 || !cat.isAlive() || cat.isAnImage() || isMorph(cat)) return;
        look(level, cat, (cat.tickCount + cat.getId()) / LOOK);
    }

    /** A player's own cat shape (WCE binds it to the player): no Clan roles. */
    private static boolean isMorph(WCatEntity cat) {
        UUID bound = cat.getPlayerBoundUuid();
        return bound != null && !bound.equals(ClanData.EMPTY_UUID);
    }

    static void look(ServerLevel level, WCatEntity cat, long n) {
        RoleData d = Roles.data(cat);
        Rank rank = cat.getRank();
        boolean baby = cat.isBaby();
        ClanData.Clan clan = ClanCats.clan(cat);
        if (d.lastRank == null) firstSight(level, cat, d, rank, baby, clan);
        else if (rank != d.lastRank) rankChanged(level, cat, d, d.lastRank, rank, clan);
        d.lastRank = rank;
        d.lastBaby = baby;
        d.lastName = ClanCats.name(cat);
        if (clan == null) {
            // a cat with no Clan (kicked out, or never in one) has no Clan role
            if (d.role != Role.NONE) Roles.setRole(cat, Role.NONE);
            if (d.mentor != null || d.apprentice != null) Roles.unlink(cat);
        } else {
            if (RoleConfig.KITS_GROW_UP.get() && rank == Rank.KIT && baby && !cat.kitBorn && cat.isTame()) growLikeBorn(cat);
            leadership(cat, d);
            if (n % 5 == 0) {
                checkLinks(level, cat, d, clan);
                if (rank == Rank.APPRENTICE && d.mentor == null && RoleConfig.MENTORS.get()) giveMentor(level, cat, clan, true);
            }
            if (n % 5 == 2 && RoleConfig.QUEENS.get()) queen(level, cat, d, rank, clan);
            if (n % 15 == 7 && RoleConfig.ELDERS.get()) retire(level, cat, d, rank, clan);
        }
        RoleNet.update(cat, d);
    }

    // seeing changes -----------------------------------------------------------------------------------------------

    private static void firstSight(ServerLevel level, WCatEntity cat, RoleData d, Rank rank, boolean baby, ClanData.Clan clan) {
        if (!baby && rank != Rank.KIT && rank != Rank.APPRENTICE && Roles.moons(cat) < Roles.GROWN_MOONS + 0.5F) {
            // nobody knows how old a grown cat found like this is (one from before Clan Lore, or found wild):
            // somewhere in its warrior moons, so a Clan does not go years with no elders
            int span = Math.max(0, RoleConfig.UNKNOWN_AGE_MAX.get() - Roles.GROWN_MOONS);
            Roles.setMoons(cat, Roles.GROWN_MOONS + cat.getRandom().nextInt(span + 1));
        }
        // an apprentice from before Clan Lore (or a pack's new one) is given a mentor quietly: no ceremony was seen
        if (rank == Rank.APPRENTICE && clan != null && d.mentor == null && RoleConfig.MENTORS.get()) giveMentor(level, cat, clan, false);
    }

    private static void rankChanged(ServerLevel level, WCatEntity cat, RoleData d, Rank from, Rank to, ClanData.Clan clan) {
        if (from == Rank.KIT && to == Rank.APPRENTICE) {
            if (clan != null && RoleConfig.APPRENTICE_CEREMONY.get()) apprenticeCeremony(level, cat, d, clan);
            else if (clan != null && RoleConfig.MENTORS.get()) giveMentor(level, cat, clan, false);
            return;
        }
        if (from == Rank.APPRENTICE) {
            Roles.unlink(cat, true, false);
            if (clan != null) cat.updateClanCatData();         // WCE's Clan list shows the new rank to the mentor's side too
        }
        if (from == Rank.APPRENTICE && to == Rank.WARRIOR && d.lastBaby && !cat.isBaby() && clan != null && RoleConfig.WARRIOR_CEREMONY.get()) {
            warriorCeremony(level, cat, d.lastName, clan);
        }
        if (to == Rank.NONE || to == Rank.KIT || to == Rank.MEDICINE) {
            Roles.unlink(cat, false, true);
            if (d.role == Role.QUEEN || d.role == Role.ELDER || to == Rank.NONE) Roles.setRole(cat, Role.NONE);
        }
    }

    /**
     * A Clan kit WCE did not see born never gets its "-paw" name: WCE only apprentices its own newborns, and grows
     * any other kit straight into a warrior. Marking it as WCE's own newborn, at a newborn's age, lets WCE raise it
     * the usual way, kit, then apprentice, then warrior.
     */
    private static void growLikeBorn(WCatEntity cat) {
        int growth;
        try {
            growth = cat.getKitGrowthTimeMinutes();
        } catch (RuntimeException e) {
            return;
        }
        cat.kitBorn = true;
        cat.setAge(-growth * 1200);
    }

    /** For a Clan the pack keeps: its word on who leads, and how many lives they have left, mirrored onto the cat. */
    private static void leadership(WCatEntity cat, RoleData d) {
        ClanKeeper k = Roles.keeper();
        if (k == null || !k.keeps(cat)) return;
        boolean leads = k.leads(cat);
        if (leads && d.role != Role.LEADER) Roles.setRole(cat, Role.LEADER);
        else if (!leads && d.role == Role.LEADER) Roles.setRole(cat, Role.NONE);
        if (leads) d.lives = Math.max(0, Math.min(Roles.MAX_LIVES, k.lives(cat)));
    }

    // mentors ------------------------------------------------------------------------------------------------------

    /**
     * The mentor for a new apprentice: a warrior of its Clan in its prime with no apprentice (neither a Clan cat nor a
     * player, as the pack tells), the nearest first.
     */
    static WCatEntity pickMentor(ServerLevel level, WCatEntity apprentice, ClanData.Clan clan) {
        ClanKeeper k = Roles.keeper();
        return ClanCats.loaded(level, clan).stream()
                .filter(c -> c != apprentice && ClanCats.ableWarrior(c) && Roles.apprenticeOf(c) == null
                        && (k == null || !k.mentorsPlayer(c)))
                .min(Comparator.comparingDouble(c -> c.distanceToSqr(apprentice))).orElse(null);
    }

    /** Gives an apprentice with none a mentor; {@code announce} has the leader name them (one who lost theirs). */
    private static void giveMentor(ServerLevel level, WCatEntity cat, ClanData.Clan clan, boolean announce) {
        WCatEntity m = pickMentor(level, cat, clan);
        if (m == null) return;
        Roles.link(m, cat);
        if (!announce) return;
        Voice leader = ClanCats.leader(level, clan, cat);
        String paw = ClanCats.name(cat), mentor = ClanCats.name(m);
        tell(level, clan, cat, say(leader, clan.color, mentor + ", " + paw + " needs a mentor, and you are ready to take on an apprentice. "
                + "Pass on all you have learned to this young cat."));
    }

    /** Ends pairings whose other half is gone (dead, gone from the Clan, grown) or has been paired with another cat. */
    private static void checkLinks(ServerLevel level, WCatEntity cat, RoleData d, ClanData.Clan clan) {
        if (d.mentor != null) {
            boolean keep;
            if (level.getEntity(d.mentor) instanceof WCatEntity m && m.isAlive()) {
                keep = cat.getUUID().equals(Roles.apprenticeOf(m)) && cat.getRank() == Rank.APPRENTICE;
                if (keep) d.mentorName = ClanCats.name(m);
            } else {
                keep = clan.clanCats.containsKey(d.mentor) && cat.getRank() == Rank.APPRENTICE;
            }
            if (!keep) {
                d.mentor = null;
                d.mentorName = "";
            }
        }
        if (d.apprentice != null) {
            boolean keep;
            if (level.getEntity(d.apprentice) instanceof WCatEntity a && a.isAlive()) {
                keep = a.getRank() == Rank.APPRENTICE && cat.getUUID().equals(Roles.mentorOf(a));
                if (keep) d.apprenticeName = ClanCats.name(a);
            } else {
                ClanData.ClanCat seen = clan.clanCats.get(d.apprentice);
                keep = seen != null && Rank.APPRENTICE.name().equals(seen.catRank);
            }
            if (!keep) {
                d.apprentice = null;
                d.apprenticeName = "";
            }
        }
    }

    // ceremonies ---------------------------------------------------------------------------------------------------

    private static void apprenticeCeremony(ServerLevel level, WCatEntity cat, RoleData d, ClanData.Clan clan) {
        String paw = ClanCats.name(cat);
        String kit = d.lastName.isEmpty() || d.lastName.equals(paw) ? ClanCats.prefix(cat) + "kit" : d.lastName;
        Voice leader = ClanCats.leader(level, clan, cat);
        int colour = clan.color;
        WCatEntity mentor = RoleConfig.MENTORS.get() ? pickMentor(level, cat, clan) : null;
        List<Speech> lines = new ArrayList<>();
        lines.add(say(leader, colour, "Let all cats old enough to catch their own prey gather for a Clan meeting!"));
        lines.add(say(leader, colour, kit + ", you have reached your sixth moon, and it is time for you to be apprenticed. "
                + "From this day on, until you receive your warrior name, you will be known as " + paw + "."));
        if (mentor != null) {
            Roles.link(mentor, cat);
            String m = ClanCats.name(mentor);
            lines.add(say(leader, colour, m + ", you are ready to take on an apprentice. You will be mentor to " + paw
                    + ". I trust you will pass on all you have learned to this young cat."));
            lines.add(Speech.narrate(paw + " touches noses with " + m + ". The Clan calls the new apprentice's name: " + paw + "! " + paw + "!"));
        } else {
            lines.add(say(leader, colour, "No warrior is free to mentor you yet, " + paw + ". Until one is, the whole Clan will help to teach you."));
            lines.add(Speech.narrate("The Clan calls the new apprentice's name: " + paw + "! " + paw + "!"));
        }
        LoreHooks.tell(level, clan.clanUUID, cat.blockPosition(), lines);
    }

    private static void warriorCeremony(ServerLevel level, WCatEntity cat, String before, ClanData.Clan clan) {
        String name = ClanCats.name(cat);
        if (before.isEmpty() || before.equals(name)) before = ClanCats.prefix(cat) + "paw";
        Voice leader = ClanCats.leader(level, clan, cat);
        int colour = clan.color;
        LoreHooks.tell(level, clan.clanUUID, cat.blockPosition(), List.of(
                say(leader, colour, before + ", do you promise to uphold the warrior code, and to protect and defend your Clan, even at the cost of your life?"),
                new Speech(before, colour, "I do.", cat.getUUID()),
                say(leader, colour, "Then by the powers of StarClan, I give you your warrior name. " + before + ", from this moment you will be known as "
                        + name + ". StarClan honours your courage and your loyalty, and we welcome you as a full warrior of " + clan.name + "."),
                Speech.narrate("The Clan calls the new warrior's name: " + name + "! " + name + "!")));
    }

    // elders -------------------------------------------------------------------------------------------------------

    /** An old warrior or deputy (never the leader, never one still mentoring) asks to join the elders. */
    private static void retire(ServerLevel level, WCatEntity cat, RoleData d, Rank rank, ClanData.Clan clan) {
        if (d.role != Role.NONE || d.apprentice != null || cat.isBaby() || (rank != Rank.WARRIOR && rank != Rank.DEPUTY)) return;
        if (Roles.moons(cat) < RoleConfig.RETIRE_MOONS.get()) return;
        long now = level.getGameTime();
        Long next = RETIRE_NEXT.get(clan.clanUUID);
        if (next != null && now < next) return;
        List<WCatEntity> able = ClanCats.loaded(level, clan).stream().filter(c -> c != cat && ClanCats.ableWarrior(c)).toList();
        if (able.size() < 3) return;                           // the Clan cannot spare them yet
        RETIRE_NEXT.put(clan.clanUUID, now + RoleConfig.RETIRE_GAP_MINUTES.get() * 1200L);

        Voice leader = ClanCats.leader(level, clan, cat);
        String n = ClanCats.name(cat);
        int colour = clan.color;
        List<Speech> lines = new ArrayList<>();
        lines.add(new Speech(n, colour, leader.name() + ", the time has come for me to join the elders. My paws are not as swift as they were.", cat.getUUID()));
        lines.add(say(leader, colour, n + ", is it your wish to give up the name of warrior and go to join the elders?"));
        lines.add(new Speech(n, colour, "It is.", cat.getUUID()));
        lines.add(say(leader, colour, "Your Clan honours you and all the service you have given it. I call upon StarClan to give you many moons of rest."));
        lines.add(Speech.narrate("The Clan calls " + n + "'s name, and " + n + " pads away to the elders' den."));
        if (rank == Rank.DEPUTY) {
            cat.setRank(Rank.WARRIOR);
            List<WCatEntity> warriors = able.stream().filter(c -> c.getRank() == Rank.WARRIOR).toList();
            if (!warriors.isEmpty()) {
                WCatEntity pick = warriors.get(cat.getRandom().nextInt(warriors.size()));
                pick.setRank(Rank.DEPUTY);
                lines.add(say(leader, colour, "I say these words before StarClan, so that our ancestors may hear my choice: "
                        + ClanCats.name(pick) + " will be the new deputy of " + clan.name + "."));
            }
            d.lastRank = Rank.WARRIOR;
        }
        Roles.setRole(cat, Role.ELDER);
        // keep an elder near its nest where WCE does the wandering (a pack running the camp has its own den routine)
        if (!Roles.kept(cat) && cat.hasHomePosition()) cat.wanderCenter = cat.getHomePosition();
        LoreHooks.tell(level, clan.clanUUID, cat.blockPosition(), lines);
    }

    /** Elders leave hunting and fighting to the warriors: they only turn on something that has gone for them. */
    private static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getEntity() instanceof WCatEntity cat) || cat.level().isClientSide() || !cat.hasData(Roles.DATA)) return;
        LivingEntity t = e.getNewAboutToBeSetTarget();
        if (t == null || Roles.role(cat) != Role.ELDER || !RoleConfig.ELDERS_FIGHT_LESS.get()) return;
        if (t == cat.getLastHurtByMob() || t instanceof Mob m && m.getTarget() == cat) return;
        e.setCanceled(true);
    }

    // queens -------------------------------------------------------------------------------------------------------

    private static void queen(ServerLevel level, WCatEntity cat, RoleData d, Rank rank, ClanData.Clan clan) {
        if (d.role == Role.LEADER || d.role == Role.ELDER) return;
        boolean fit = cat.getGender() == 1 && rank == Rank.WARRIOR && !cat.isBaby();
        if (d.role == Role.NONE) {
            if (!fit) return;
            boolean expecting = cat.isExpectingKits();
            if (!expecting && ownKits(level, cat, false).isEmpty()) return;
            Roles.setRole(cat, Role.QUEEN);
            if (expecting) tell(level, clan, cat, Speech.narrate(ClanCats.name(cat) + " is expecting kits, and moves into the nursery."));
            return;
        }
        if (d.role != Role.QUEEN) return;
        if (!fit) {
            Roles.setRole(cat, Role.NONE);
            return;
        }
        if (cat.isExpectingKits() || !ownKits(level, cat, true).isEmpty()) {
            d.queenEmpty = 0;
            return;
        }
        if (++d.queenEmpty < QUEEN_EMPTY_LOOKS) return;
        boolean grown = !level.getEntitiesOfClass(WCatEntity.class, new AABB(cat.blockPosition()).inflate(NURSERY_REACH),
                k -> k.isAlive() && k.getRank() == Rank.APPRENTICE && cat.getUUID().equals(k.getMotherUUID())).isEmpty();
        Roles.setRole(cat, Role.NONE);
        if (grown) tell(level, clan, cat, Speech.narrate("With her kits apprenticed, " + ClanCats.name(cat) + " leaves the nursery and goes back to the warriors' den."));
    }

    /**
     * The kits a she-cat is nursing: her own (WCE records the mother), near her. A queen already in the nursery also
     * nurses the Clan's motherless kits there (a pack's camp is filled with a queen and kits that WCE never saw born).
     */
    private static List<WCatEntity> ownKits(ServerLevel level, WCatEntity cat, boolean orphansToo) {
        UUID clanId = cat.getClanUUID();
        return level.getEntitiesOfClass(WCatEntity.class, new AABB(cat.blockPosition()).inflate(NURSERY_REACH), k -> {
            if (!k.isAlive() || k.getRank() != Rank.KIT || !clanId.equals(k.getClanUUID())) return false;
            UUID mother = k.getMotherUUID();
            if (cat.getUUID().equals(mother)) return true;
            return orphansToo && (mother == null || mother.equals(ClanData.EMPTY_UUID) || mother.equals(WCatEntity.emptyUUID));
        });
    }

    // leaders ------------------------------------------------------------------------------------------------------

    /**
     * A leader of a Clan the pack does not keep loses a life to each death, and StarClan sends them back, until the
     * last; then the deputy becomes leader with nine lives and names a new deputy.
     */
    private static void onDeath(LivingDeathEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof WCatEntity cat) || !(cat.level() instanceof ServerLevel level)) return;
        if (!cat.hasData(Roles.DATA) || Roles.role(cat) != Role.LEADER || !RoleConfig.LEADER_LIVES.get() || Roles.kept(cat)) return;
        RoleData d = Roles.data(cat);
        ClanData.Clan clan = ClanCats.clan(cat);
        String n = ClanCats.name(cat);
        int colour = clan != null ? clan.color : 0xFFFFFF;
        if (d.lives > 1) {
            d.lives--;
            e.setCanceled(true);
            cat.setHealth(cat.getMaxHealth());
            cat.clearFire();
            RoleNet.update(cat, d);
            if (clan != null) {
                LoreHooks.tell(level, clan.clanUUID, cat.blockPosition(), List.of(
                        Speech.narrate(n + " lies still... then stirs. StarClan has sent them back."),
                        new Speech(n, colour, "I have lost a life. I have " + d.lives + (d.lives == 1 ? " life" : " lives") + " left to give my Clan.", cat.getUUID())));
            }
            return;
        }
        d.lives = 0;
        d.role = Role.NONE;
        Roles.unlink(cat);
        if (clan == null) return;
        List<WCatEntity> cats = new ArrayList<>(ClanCats.loaded(level, clan));
        cats.remove(cat);
        List<Speech> lines = new ArrayList<>();
        lines.add(Speech.narrate(n + " has given the last of their nine lives, and gone to hunt with StarClan."));
        WCatEntity heir = cats.stream().filter(c -> c.getRank() == Rank.DEPUTY).findFirst().orElse(null);
        if (heir != null) {
            String before = ClanCats.name(heir);
            String star = ClanCats.prefix(heir) + "star";
            ClanCats.rename(heir, ClanCats.prefix(heir), star);
            heir.setRank(Rank.WARRIOR);
            Roles.data(heir).lastRank = Rank.WARRIOR;
            Roles.setRole(heir, Role.LEADER);
            lines.add(Speech.narrate(before + " goes to share tongues with StarClan, and StarClan gives them nine lives."));
            lines.add(new Speech(star, colour, "I am " + star + " now. I will lead " + clan.name + " as well as I can.", heir.getUUID()));
            List<WCatEntity> warriors = cats.stream().filter(c -> c != heir && c.getRank() == Rank.WARRIOR && ClanCats.ableWarrior(c)).toList();
            if (!warriors.isEmpty()) {
                WCatEntity pick = warriors.get(cat.getRandom().nextInt(warriors.size()));
                pick.setRank(Rank.DEPUTY);
                Roles.data(pick).lastRank = Rank.DEPUTY;
                lines.add(new Speech(star, colour, "I say these words before StarClan, so that our ancestors may hear my choice: "
                        + ClanCats.name(pick) + " will be the new deputy of " + clan.name + ".", heir.getUUID()));
            }
        }
        LoreHooks.tell(level, clan.clanUUID, cat.blockPosition(), lines);
    }

    // telling ------------------------------------------------------------------------------------------------------

    private static Speech say(Voice v, int colour, String text) {
        return new Speech(v.name(), colour, text, v.entity());
    }

    private static void tell(ServerLevel level, ClanData.Clan clan, WCatEntity at, Speech line) {
        LoreHooks.tell(level, clan.clanUUID, at.blockPosition(), List.of(line));
    }
}
