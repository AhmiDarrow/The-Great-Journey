package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlore.role.Role;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import com.ninjacat.greatjourney.clanlore.traits.Traits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Deaths in the Clans, as in the books and without gore: a cat that dies (old age, sickness or battle) goes to hunt
 * with StarClan, the Clan sits vigil, and StarClan remembers the name. A leader has nine lives; when the last is
 * gone the deputy becomes leader and names a new deputy.
 */
public final class Deaths {
    private Deaths() {}

    /** Why a cat is about to die, when an event (not a hit) causes it: read when the death arrives. */
    private static final Map<UUID, String> PENDING_CAUSE = new HashMap<>();

    static void reset() {
        PENDING_CAUSE.clear();
    }

    static void diePeacefully(WCatEntity cat) {
        PENDING_CAUSE.put(cat.getUUID(), "old age");
        cat.hurt(cat.damageSources().starve(), Float.MAX_VALUE);
    }

    static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof WCatEntity cat) || !(cat.level() instanceof ServerLevel level)) return;
        ServerLevel ow = level.getServer().overworld();
        LifeData life = LifeData.get(ow);
        Clan c = life.clanOf(cat.getClanUUID());
        if (c == null) return;
        String name = Cats.plainName(cat);
        String cause = PENDING_CAUSE.containsKey(cat.getUUID()) ? PENDING_CAUSE.remove(cat.getUUID()) : causeOf(e.getSource());
        Info info = Info.of(c);
        if (ClanEvents.isRaider(cat)) {
            ClanEvents.raiderGone(cat);
        }
        boolean leader = Cats.isLeader(life, c, cat);

        if (!EventsConfig.DEATHS.get()) {
            e.setCanceled(true);
            cat.setHealth(Math.max(2, cat.getMaxHealth() * 0.2F));
            Announce.tell(ow, c, cat.blockPosition(), Line.narrate(name + " is badly hurt, but will live."));
            return;
        }
        if (leader) {
            int left = life.loseLife(c);
            Roles.setLives(cat, left);                       // Clan Lore's name tag shows the count kept here
            if (left > 0) {
                e.setCanceled(true);
                cat.setHealth(cat.getMaxHealth());
                cat.clearFire();
                Announce.tell(ow, c, cat.blockPosition(),
                        Line.narrate(name + " lies still... then stirs. StarClan has sent them back."),
                        Line.say(name, info.colour, "I have lost a life. I have " + left + (left == 1 ? " life" : " lives") + " left to give my Clan."));
                return;
            }
        }

        life.rememberInStarClan(name, c, cause, ow.getGameTime());
        UUID id = life.clanId(c);
        if (id != null) ClanData.get(ow).removeClanCatFromClan(id, cat);

        List<WCatEntity> cats = new ArrayList<>(Cats.atCamp(ow, c));
        cats.remove(cat);
        String voice = leader ? Cats.speaker(cats, WCatEntity.Rank.DEPUTY, "The Clan") : life.leader(c);
        List<Line> lines = new ArrayList<>();
        RandomSource r = cat.getRandom();
        lines.add(Line.narrate(cause.equals("old age")
                ? name + " has gone to hunt with StarClan, peacefully, in their sleep."
                : Happenings.pick(r, name + " has gone to hunt with StarClan.",
                        name + " has joined StarClan. A new star shines in Silverpelt tonight.",
                        name + "'s spirit has gone to walk among the stars of Silverpelt.")));
        lines.add(Line.say(voice, info.colour, Happenings.pick(r,
                name + " served " + info.title + " well. Tonight we sit vigil, and share tongues with them one last time.",
                "May StarClan light " + name + "'s path. The Clan will sit vigil with them tonight.",
                name + " hunts in StarClan's forest now, where prey is always plentiful. We will never forget them.")));
        if (leader) {
            lines.addAll(succession(ow, life, c, cats));
        } else if (cat.getRank() == WCatEntity.Rank.DEPUTY) {
            lines.addAll(newDeputy(ow, life, c, cats, life.leader(c)));
        } else if (cat.getRank() == WCatEntity.Rank.MEDICINE && cats.stream().noneMatch(x -> x.getRank() == WCatEntity.Rank.MEDICINE)) {
            // the Clan names its new medicine cat at the vigil, as it does a deputy (the once-a-minute look in
            // ClanEvents only catches a medicine cat lost some other way)
            WCatEntity heir = ClanEvents.nameMedicineCat(life, c, cats);
            if (heir != null) lines.add(ClanEvents.medicineChosen(life, c, heir));
        }
        Announce.tell(ow, c, cat.blockPosition(), lines);
    }

    private static String causeOf(DamageSource s) {
        String id = s.getMsgId();
        if (id.contains("cough") || id.contains("disease") || id.contains("deathberries") || id.contains("fever")) return "sickness";
        if (s.getEntity() != null) return "battle";
        return "misfortune";
    }

    /** The deputy becomes leader (taking a "-star" name and nine lives), then names a new deputy. */
    public static List<Line> succession(ServerLevel ow, LifeData life, Clan c, List<WCatEntity> cats) {
        Info info = Info.of(c);
        // a player who is the Clan's deputy, and here, leads (Ranks)
        List<Line> ours = com.ninjacat.greatjourney.clanlands.life.quests.Ranks.succession(ow, life, c, cats);
        if (ours != null) return ours;
        List<Line> lines = new ArrayList<>();
        WCatEntity heir = Cats.withRank(cats, WCatEntity.Rank.DEPUTY).stream().findFirst()
                .orElse(cats.stream().filter(x -> x.getRank() == WCatEntity.Rank.WARRIOR && Roles.role(x) != Role.ELDER)
                        .max(Comparator.comparingDouble(Traits::moons)).orElse(null));
        if (heir == null) return lines;
        String before = Cats.plainName(heir);
        String newName = Cats.prefixOf(heir) + "star";
        Cats.rename(heir, Cats.prefixOf(heir), newName);
        heir.setRank(WCatEntity.Rank.WARRIOR);
        life.setLeader(c, newName);
        Roles.setRole(heir, Role.LEADER);                       // Clan Lore shows the role; the lives stay counted here
        UUID id = life.clanId(c);
        ClanData data = ClanData.get(ow);
        if (id != null && data.getClan(id) != null) {
            data.getClan(id).leaderName = newName;
            data.setDirty();
        }
        lines.add(Line.narrate(before + " travels to the Moonpool, and StarClan gives them nine lives."));
        lines.add(Line.say(newName, info.colour, "I am " + newName + " now. I will lead " + info.title + " as well as I can."));
        lines.addAll(newDeputy(ow, life, c, cats.stream().filter(x -> x != heir).toList(), newName));
        return lines;
    }

    public static List<Line> newDeputy(ServerLevel ow, LifeData life, Clan c, List<WCatEntity> cats, String leaderName) {
        // a player who has earned it is named (or already holds the place): no Clan cat is deputy beside them (Ranks)
        List<Line> ours = com.ninjacat.greatjourney.clanlands.life.quests.Ranks.deputyVacancy(ow, life, c, leaderName);
        if (ours != null) return ours;
        List<WCatEntity> warriors = Cats.withRank(cats, WCatEntity.Rank.WARRIOR).stream()
                .filter(x -> !Cats.isLeader(life, c, x) && Roles.role(x) != Role.ELDER).toList();
        if (warriors.isEmpty()) return List.of();
        WCatEntity pick = warriors.get(RandomSource.create(ow.getGameTime()).nextInt(warriors.size()));
        pick.setRank(WCatEntity.Rank.DEPUTY);
        return List.of(Line.say(leaderName, Info.of(c).colour, "I say these words before StarClan, so our ancestors may hear my choice: "
                + Cats.plainName(pick) + " will be the new deputy of " + Info.of(c).title + "."));
    }

    /**
     * Rarely, an old cat in a loaded camp dies peacefully in their sleep. Warrior Cats Events stops a grown cat's age
     * at 12 moons, so the age is Clan Lore's count ({@link Traits#moons}), which goes on from there.
     */
    static boolean oldAge(ServerLevel ow, Clan c, RandomSource r) {
        int moons = EventsConfig.OLD_AGE_MOONS.get();
        List<WCatEntity> old = Cats.atCamp(ow, c).stream()
                .filter(x -> Traits.moons(x) >= moons && x.getRank() != WCatEntity.Rank.KIT).toList();
        if (old.isEmpty() || r.nextDouble() >= EventsConfig.OLD_AGE_CHANCE.get()) return false;
        diePeacefully(old.get(r.nextInt(old.size())));
        return true;
    }
}
