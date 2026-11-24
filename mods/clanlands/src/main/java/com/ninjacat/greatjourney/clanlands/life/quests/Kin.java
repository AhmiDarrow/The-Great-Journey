package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Geo;
import com.ninjacat.greatjourney.clanlands.layout.LakeLands;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Who is who, for quests: the player's Clan (and whether it is one of the four book Clans), what part a Clan cat
 * plays (leader, deputy, medicine cat, queen, elder, warrior...), and places in a Clan's territory.
 */
public final class Kin {
    private Kin() {}

    public enum Role { LEADER, DEPUTY, MEDICINE, QUEEN, ELDER, WARRIOR, APPRENTICE, KIT }

    /** The player's Clan as the four books know it, or null (clanless, or a Clan players founded themselves). */
    static Clan bookClan(ServerPlayer p) {
        ClanData.Clan c = Joining.clanOf(p);
        if (c == null) return null;
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        return life.created() ? life.clanOf(c.clanUUID) : null;
    }

    static int colour(ServerPlayer p) {
        Clan c = bookClan(p);
        if (c != null) return BookClans.Info.of(c).colour;
        ClanData.Clan wc = Joining.clanOf(p);
        return wc == null ? 0xC8C8C8 : wc.color;
    }

    /** The cat's name without Warrior Cats Events' gender sign. */
    static String name(WCatEntity cat) {
        if (cat.getCustomName() == null) return "A Clan cat";
        return cat.getCustomName().getString().replace(" ♂", "").replace(" ♀", "").trim();
    }

    static Role role(WCatEntity cat, LifeData life) {
        String n = name(cat);
        Clan c = cat.getClanUUID() == null ? null : life.clanOf(cat.getClanUUID());
        // Clan Lore's role says who has retired: a leader who stepped down for a player keeps the "-star" name as an elder
        boolean retired = com.ninjacat.greatjourney.clanlore.role.Roles.role(cat) == com.ninjacat.greatjourney.clanlore.role.Role.ELDER;
        boolean playerLeads = c != null && life.leaderPlayer(c) != null;
        if (c != null && !playerLeads && n.equalsIgnoreCase(life.leader(c))) return Role.LEADER;
        if (!retired && !playerLeads && n.toLowerCase(java.util.Locale.ROOT).endsWith("star") && cat.getRank() == WCatEntity.Rank.WARRIOR) return Role.LEADER;
        return switch (cat.getRank()) {
            case DEPUTY -> Role.DEPUTY;
            case MEDICINE -> Role.MEDICINE;
            case KIT -> Role.KIT;
            case APPRENTICE -> Role.APPRENTICE;
            default -> {
                // Clan Lore's role first: it knows who has retired and who is nursing kits
                var lore = com.ninjacat.greatjourney.clanlore.role.Roles.role(cat);
                if (lore == com.ninjacat.greatjourney.clanlore.role.Role.ELDER) yield Role.ELDER;
                if (lore == com.ninjacat.greatjourney.clanlore.role.Role.QUEEN) yield Role.QUEEN;
                if (lore == com.ninjacat.greatjourney.clanlore.role.Role.LEADER && !playerLeads) yield Role.LEADER;
                if (cat.getGender() == 1 && nearKits(cat)) yield Role.QUEEN;
                yield Role.WARRIOR;
            }
        };
    }

    /**
     * The player's own place in their Clan, as their Clanmates see it: a kit or an apprentice until they are named.
     * Reported after play (2026-11-23): a warrior and a deputy were still greeted, and set to work, as apprentices.
     */
    static Role role(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        ClanData.ClanPlayerRank r = wc == null ? null : wc.members.get(p.getUUID());
        if (r == null) return Role.APPRENTICE;
        if (Ranks.leads(p)) return Role.LEADER;
        return switch (r) {
            case LEADER -> Role.LEADER;
            case DEPUTY -> Role.DEPUTY;
            case MEDICINE -> Role.MEDICINE;
            case WARRIOR -> Role.WARRIOR;
            case ELDER -> Role.ELDER;
            case QUEEN -> Role.QUEEN;
            case KIT -> Role.KIT;
            default -> Role.APPRENTICE;
        };
    }

    /** A named cat: anyone past their apprenticeship. */
    static boolean grown(Role r) {
        return r != Role.KIT && r != Role.APPRENTICE;
    }

    private static boolean nearKits(WCatEntity cat) {
        return !cat.level().getEntitiesOfClass(WCatEntity.class, cat.getBoundingBox().inflate(20),
                k -> k.getRank() == WCatEntity.Rank.KIT && java.util.Objects.equals(k.getClanUUID(), cat.getClanUUID())).isEmpty();
    }

    /** Cats of a Clan within {@code r} blocks of a point. */
    static List<WCatEntity> catsNear(ServerLevel level, java.util.UUID clanId, double x, double z, double r) {
        return level.getEntitiesOfClass(WCatEntity.class, new AABB(x - r, -64, z - r, x + r, 320, z + r),
                c -> clanId != null && clanId.equals(c.getClanUUID()) && c.getCustomName() != null);
    }

    static String roleTitle(Role r) {
        return switch (r) {
            case LEADER -> "leader";
            case DEPUTY -> "deputy";
            case MEDICINE -> "medicine cat";
            case QUEEN -> "queen";
            case ELDER -> "elder";
            case WARRIOR -> "warrior";
            case APPRENTICE -> "apprentice";
            case KIT -> "kit";
        };
    }

    // --- places in a Clan's territory (lake territories)

    /**
     * Points on the Clan's borders where it meets other Clans, nudged a little inside its own land: where a border
     * patrol goes. Scans rings around the lake at the camp's distance and either side of it (the rings near the camp
     * are close together, so a patrol can keep to the stretches of border nearest home).
     */
    static List<double[]> borderPoints(Clan clan) {
        return borderPoints(ClanWorld.current(), clan);
    }

    public static List<double[]> borderPoints(ClanWorld w, Clan clan) {
        List<double[]> out = new ArrayList<>();
        if (w == null) return out;
        Landmark camp = Homeland.camp(w, clan);
        double[] heart = Homeland.centre(w);
        double cx = heart[0], cz = heart[1];
        double base = Math.hypot(camp.x() - cx, camp.z() - cz);
        for (double f : new double[] {0.75, 0.9, 1.0, 1.15, 1.3}) {
            double r = base * f;
            Clan prev = null;
            double prevB = 0;
            for (double b = 0; b <= 361; b += 1) {
                double[] pt = Geo.toward(cx, cz, b % 360, r);
                Clan here = Homeland.territoryAt(w, pt[0], pt[1]);
                if (prev != null && here != null && prev != here && (prev == clan || here == clan)) {
                    double inside = here == clan ? b + 2 : prevB - 2;   // two degrees into our own land
                    out.add(Geo.toward(cx, cz, inside % 360, r));
                }
                if (here != null) {
                    prev = here;
                    prevB = b;
                }
            }
        }
        return out;
    }

    /** The edge of the lake in the Clan's land: walk from the camp toward the lake until the water. */
    static double[] shorePoint(Clan clan) {
        return shorePoint(ClanWorld.current(), clan);
    }

    public static double[] shorePoint(ClanWorld w, Clan clan) {
        Landmark camp = Homeland.camp(w, clan);
        double[] heart = Homeland.centre(w);
        double dx = heart[0] - camp.x(), dz = heart[1] - camp.z(), len = Math.hypot(dx, dz);
        double[] last = {camp.x(), camp.z()};
        for (double s = 0; s < len; s += 8) {
            double x = camp.x() + dx / len * s, z = camp.z() + dz / len * s;
            if (Homeland.heart(w, x, z)) return last;
            last = new double[] {x, z};
        }
        return last;
    }

    /**
     * The far edge of the Clan's land: walk from the camp away from the lake until the territory ends. The lands are
     * wide (the true edge can lie a thousand blocks out), so the walk stops short where the round trip, down to the
     * shore and out again, would outgrow a duty's walk ({@link Tasks#ROUTE_CAP}): the far reaches, if not the very edge.
     */
    static double[] farEdgePoint(Clan clan) {
        return farEdgePoint(ClanWorld.current(), clan);
    }

    public static double[] farEdgePoint(ClanWorld w, Clan clan) {
        Landmark camp = Homeland.camp(w, clan);
        double[] heart = Homeland.centre(w);
        double dx = camp.x() - heart[0], dz = camp.z() - heart[1], len = Math.hypot(dx, dz);
        double[] shore = shorePoint(w, clan);
        double reach = Math.max(200, Tasks.ROUTE_CAP / 2 - Math.hypot(shore[0] - camp.x(), shore[1] - camp.z()));
        double[] last = {camp.x(), camp.z()};
        for (double s = 0; s < Math.min(3000, reach); s += 12) {
            double x = camp.x() + dx / len * s, z = camp.z() + dz / len * s;
            if (Homeland.territoryAt(w, x, z) != clan) return last;
            last = new double[] {x, z};
        }
        return last;
    }

    /** A rough compass direction from one point to another, for hints ("to the north-east"). */
    static String direction(double fromX, double fromZ, double toX, double toZ) {
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        double b = Geo.bearing(toX - fromX, toZ - fromZ);
        return names[(int) Math.round(b / 45) % 8];
    }

    // --- prey

    /** Entity ids counted as each Clan's favourite prey, and the general prey every Clan hunts. */
    static Set<String> prey(String set) {
        return switch (set) {
            case "thunder" -> Set.of("warriorcats_events:squirrel", "warriorcats_events:mouse", "warriorcats_events:pigeon", "minecraft:rabbit");
            case "wind" -> Set.of("minecraft:rabbit", "warriorcats_events:mouse", "warriorcats_events:pigeon");
            case "river" -> Set.of("minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "naturalist:bass",
                    "naturalist:catfish", "alexsmobs:catfish", "warriorcats_events:mouse");
            case "shadow" -> Set.of("minecraft:frog", "warriorcats_events:lizard", "warriorcats_events:mouse", "warriorcats_events:pigeon");
            default -> Set.of("warriorcats_events:mouse", "warriorcats_events:squirrel", "warriorcats_events:pigeon",
                    "minecraft:rabbit", "minecraft:frog", "warriorcats_events:lizard", "minecraft:cod", "minecraft:salmon");
        };
    }

    /** Every Clan's prey, and the rest Warrior Cats Events counts as prey. */
    private static final Set<String> ALL_PREY = allPrey();
    private static final net.minecraft.tags.TagKey<net.minecraft.world.entity.EntityType<?>> MORE_PREY = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.ENTITY_TYPE, net.minecraft.resources.ResourceLocation.parse("warriorcats_events:additional_prey"));

    private static Set<String> allPrey() {
        Set<String> all = new java.util.HashSet<>();
        for (String set : new String[] {"thunder", "wind", "river", "shadow", "any"}) all.addAll(prey(set));
        all.addAll(Set.of("minecraft:chicken", "naturalist:bird", "naturalist:rat", "naturalist:mole", "naturalist:duck", "naturalist:lizard",
                "alexsmobs:crow", "alexsmobs:blue_jay", "alexsmobs:jerboa"));
        return Set.copyOf(all);
    }

    /**
     * Whether an entity counts as a catch for any hunt: the Clan's favourite prey is only what the mentor talks of, and
     * a WindClan apprentice's mouse feeds the Clan as well as a rabbit (playtest 2026-10-19: catches outside the Clan's
     * own list were not counted). Anything Warrior Cats Events tags as prey counts too.
     */
    static boolean anyPrey(String id) {
        if (ALL_PREY.contains(id)) return true;
        var key = net.minecraft.resources.ResourceLocation.tryParse(id);
        return key != null && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(key).map(t -> t.is(MORE_PREY)).orElse(false);
    }

    static String preySet(Clan c) {
        return c == null ? "any" : switch (c) {
            case THUNDER -> "thunder";
            case WIND -> "wind";
            case RIVER -> "river";
            case SHADOW -> "shadow";
        };
    }

    static String preyWords(Clan c) {
        return c == null ? "prey" : switch (c) {
            case THUNDER -> "squirrels and mice";
            case WIND -> "rabbits";
            case RIVER -> "fish";
            case SHADOW -> "frogs and lizards";
        };
    }
}
