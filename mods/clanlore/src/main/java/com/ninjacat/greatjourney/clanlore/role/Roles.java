package com.ninjacat.greatjourney.clanlore.role;

import com.ninjacat.greatjourney.clanlore.Clanlore;
import com.ninjacat.greatjourney.clanlore.traits.Traits;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Leaders, elders, queens and mentors: the places in a Clan that Warrior Cats Events has no rank for. Each cat keeps
 * its role on itself ({@link RoleData}, saved with the cat), shown under its name tag. The ceremonies that change
 * roles are in {@link Ceremonies}; this class is the small public face other mods call.
 * <p>
 * Everything here is server side: the client only shows what it is sent.
 */
public final class Roles {
    private Roles() {}

    /** A leader's lives when StarClan gives them, as in the books. */
    public static final int MAX_LIVES = 9;
    /** Warrior Cats Events draws a cat full grown, and stops counting its age, at twelve moons. */
    public static final int GROWN_MOONS = 12;

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Clanlore.MOD_ID);
    public static final Supplier<AttachmentType<RoleData>> DATA = ATTACHMENTS.register("role", () -> AttachmentType.serializable(RoleData::new).build());

    private static volatile ClanKeeper keeper;

    public static void init(IEventBus modBus, ModContainer container) {
        ATTACHMENTS.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, RoleConfig.SPEC, "clanlore-common.toml");
        modBus.addListener(RoleNet::payloads);
        Ceremonies.init();
    }

    /** Installs the pack's word on the Clans it runs itself (see {@link ClanKeeper}); null for none. */
    public static void keeper(ClanKeeper k) {
        keeper = k;
    }

    static ClanKeeper keeper() {
        return keeper;
    }

    /** Whether the pack keeps this cat's Clan (its leader and lives) rather than Clan Lore. */
    static boolean kept(WCatEntity cat) {
        ClanKeeper k = keeper;
        return k != null && k.keeps(cat);
    }

    static RoleData data(WCatEntity cat) {
        return cat.getData(DATA);
    }

    public static Role role(WCatEntity cat) {
        return cat.hasData(DATA) ? data(cat).role : Role.NONE;
    }

    /**
     * Gives the cat a role. A new leader gets nine lives; any other role has none. The pack may move the cat's nest
     * to fit (an elder to the elders' den, a queen to the nursery).
     */
    public static void setRole(WCatEntity cat, Role role) {
        RoleData d = data(cat);
        if (d.role == role) return;
        d.role = role;
        if (role == Role.LEADER) {
            if (d.lives <= 0) d.lives = MAX_LIVES;
        } else {
            d.lives = 0;
        }
        d.queenEmpty = 0;
        ClanKeeper k = keeper;
        if (k != null && cat.level() instanceof ServerLevel) k.rehome(cat, role);
        changed(cat);
    }

    /** A leader's lives left (0 for any other cat). For a Clan the pack keeps, the pack's count. */
    public static int lives(WCatEntity cat) {
        if (!cat.hasData(DATA) || data(cat).role != Role.LEADER) return 0;
        ClanKeeper k = keeper;
        if (k != null && !cat.level().isClientSide && k.keeps(cat)) return Math.max(0, Math.min(MAX_LIVES, k.lives(cat)));
        return data(cat).lives;
    }

    /** Sets a leader's lives (a pack mirrors its own count here; the name tag shows it at once). */
    public static void setLives(WCatEntity cat, int lives) {
        RoleData d = data(cat);
        d.lives = Math.max(0, Math.min(MAX_LIVES, lives));
        changed(cat);
    }

    /** The cat's mentor, if it is an apprentice with one. */
    public static UUID mentorOf(WCatEntity cat) {
        return cat.hasData(DATA) ? data(cat).mentor : null;
    }

    /** The cat's apprentice, if it is a mentor. */
    public static UUID apprenticeOf(WCatEntity cat) {
        return cat.hasData(DATA) ? data(cat).apprentice : null;
    }

    /** Makes {@code mentor} the mentor of {@code apprentice}, ending any earlier pairing either had. */
    public static void link(WCatEntity mentor, WCatEntity apprentice) {
        unlink(mentor, false, true);
        unlink(apprentice, true, false);
        RoleData m = data(mentor), a = data(apprentice);
        m.apprentice = apprentice.getUUID();
        m.apprenticeName = ClanCats.name(apprentice);
        a.mentor = mentor.getUUID();
        a.mentorName = ClanCats.name(mentor);
        changed(mentor);
        changed(apprentice);
    }

    /** Ends the cat's pairings, as mentor and as apprentice, on both sides (the other side if it is loaded). */
    public static void unlink(WCatEntity cat) {
        unlink(cat, true, true);
    }

    static void unlink(WCatEntity cat, boolean asApprentice, boolean asMentor) {
        if (!cat.hasData(DATA)) return;
        RoleData d = data(cat);
        if (asApprentice && d.mentor != null) {
            if (other(cat, d.mentor) instanceof WCatEntity m && cat.getUUID().equals(data(m).apprentice)) {
                data(m).apprentice = null;
                data(m).apprenticeName = "";
                changed(m);
            }
            d.mentor = null;
            d.mentorName = "";
        }
        if (asMentor && d.apprentice != null) {
            if (other(cat, d.apprentice) instanceof WCatEntity a && cat.getUUID().equals(data(a).mentor)) {
                data(a).mentor = null;
                data(a).mentorName = "";
                changed(a);
            }
            d.apprentice = null;
            d.apprenticeName = "";
        }
        changed(cat);
    }

    private static Object other(WCatEntity cat, UUID id) {
        return cat.level() instanceof ServerLevel level ? level.getEntity(id) : null;
    }

    /**
     * How many moons old the cat is. Warrior Cats Events stops counting at twelve; the traits count on from when the
     * cat was first seen grown ({@link Traits#moons}), the one clock Clan Lore keeps for age.
     */
    public static float moons(WCatEntity cat) {
        return Traits.moons(cat);
    }

    /** Sets a grown cat's age in moons (a pack rolling its cats' ages; below twelve is WCE's own count). */
    public static void setMoons(WCatEntity cat, float moons) {
        if (moons >= GROWN_MOONS) Traits.setMoons(cat, moons);
    }

    /** The line under a cat's name tag: "Leader · 7 lives", "Elder · Mentor of Ashpaw"; "" for nothing to show. */
    public static String describe(Role role, int lives, String mentor, String apprentice) {
        StringBuilder s = new StringBuilder();
        switch (role) {
            case LEADER -> s.append("Leader · ").append(lives).append(lives == 1 ? " life" : " lives");
            case ELDER -> s.append("Elder");
            case QUEEN -> s.append("Queen");
            default -> {}
        }
        if (apprentice != null && !apprentice.isEmpty()) s.append(s.isEmpty() ? "" : " · ").append("Mentor of ").append(apprentice);
        if (mentor != null && !mentor.isEmpty()) s.append(s.isEmpty() ? "" : " · ").append("Apprentice of ").append(mentor);
        return s.toString();
    }

    /** The cat's line now (server side). */
    public static String line(WCatEntity cat) {
        if (!cat.hasData(DATA)) return "";
        RoleData d = data(cat);
        return describe(d.role, lives(cat), d.mentor == null ? "" : d.mentorName, d.apprentice == null ? "" : d.apprenticeName);
    }

    private static void changed(WCatEntity cat) {
        if (!cat.level().isClientSide) RoleNet.update(cat, data(cat));
    }
}
