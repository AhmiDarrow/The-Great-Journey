package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.snowteb.warriorcats_events.diseases.DiseaseManager;
import net.snowteb.warriorcats_events.diseases.DiseaseRegistry;
import net.snowteb.warriorcats_events.diseases.DiseaseType;
import net.snowteb.warriorcats_events.diseases.DiseaseTypes;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The sick and the hurt a medicine cat is sent to: real Warrior Cats Events diseases on real Clan cats, cured the way
 * WCE cures them (a right-click with the right herb or remedy). A duty or an errand names its patients when it is
 * taken on: a cat that is already ill is chosen first (WCE makes cats ill on its own), and otherwise one falls ill
 * with something mild (a fever, whitecough, chills, sore pads), never greencough or deathberries.
 *
 * <p>A cure objective is done once the cat no longer carries the disease or WCE counts it healed. A cat whose chunk
 * is not loaded is simply waited for. If WCE's diseases are turned off, the duty asks for the cure's herbs instead.
 */
public final class Patients {
    private Patients() {}

    /** What a duty may make a cat ill with: never a deadly one. */
    static final List<DiseaseType<?>> MILD = List.of(DiseaseTypes.FEVER, DiseaseTypes.WHITECOUGH, DiseaseTypes.CHILLS, DiseaseTypes.SORE_PADS);
    /** A disease is only checked this long after the objective was made (WCE adds diseases on the cat's next tick). */
    static final long SETTLE = 100;

    /** Who a duty's patients are, as its plan names them. */
    enum Who { SELF, ANY, WARRIOR, KIT, ELDER }

    /** A planned cure objective, filled in with real patients when the duty is taken on ({@link #prepare}). */
    static Task.Objective plan(String text, Who who, DiseaseType<?> disease, int count) {
        return Task.Objective.cure(text, "plan:" + who.name() + ":" + disease.getID() + ":" + count, count);
    }

    /**
     * The duty with its planned cures turned into real patients near the giver; a plan no patient can be found for
     * asks for the cure's herbs instead (two of them), so the duty can always be done.
     */
    static Task prepare(ServerPlayer p, WCatEntity giver, Task duty) {
        List<Task.Objective> out = new ArrayList<>();
        long now = p.level().getGameTime();
        for (Task.Objective o : duty.objectives) {
            if (o.type != Task.Objective.Type.CURE || !o.key.startsWith("plan:")) {
                out.add(o);
                continue;
            }
            String[] part = o.key.split(":");
            Who who = Who.valueOf(part[1]);
            DiseaseType<?> type = DiseaseRegistry.getByID(part[2]);
            int count = Integer.parseInt(part[3]);
            List<WCatEntity> pool = candidates(p.serverLevel(), giver, who);
            List<Task.Objective> cures = sicken(pool, type == null ? DiseaseTypes.FEVER : type, count, now, p.getRandom());
            if (cures.isEmpty()) out.add(fallback(type == null ? DiseaseTypes.FEVER : type, giver));
            else out.addAll(cures);
        }
        return new Task(duty.kind, duty.title, duty.description, duty.giverName, duty.giverId, duty.story, out,
                duty.standing, duty.xp, duty.rewardItem, duty.rewardCount);
    }

    /** The cats a plan may choose from: the giver, or the giver's Clanmates nearby of the kind it names. */
    private static List<WCatEntity> candidates(ServerLevel level, WCatEntity giver, Who who) {
        if (who == Who.SELF) return List.of(giver);
        LifeData life = LifeData.get(level.getServer().overworld());
        Predicate<WCatEntity> fits = switch (who) {
            case WARRIOR -> c -> Kin.role(c, life) == Kin.Role.WARRIOR || Kin.role(c, life) == Kin.Role.DEPUTY;
            case KIT -> c -> Kin.role(c, life) == Kin.Role.KIT;
            case ELDER -> c -> Kin.role(c, life) == Kin.Role.ELDER;
            default -> c -> Kin.role(c, life) != Kin.Role.LEADER;
        };
        List<WCatEntity> pool = new ArrayList<>(Kin.catsNear(level, giver.getClanUUID(), giver.getX(), giver.getZ(), 48));
        pool.removeIf(c -> !c.isAlive() || c.getTags().contains(BookClans.BUSY_TAG) || !fits.test(c));
        if (pool.isEmpty() && who == Who.KIT) return List.of(giver);       // no kit about: the queen herself is poorly
        return pool;
    }

    /**
     * Up to {@code count} patients from {@code pool}, nearest first: the ones already ill with a curable mild disease
     * first, then cats made ill with {@code type}. Each gets its own cure objective.
     */
    public static List<Task.Objective> sicken(List<WCatEntity> pool, DiseaseType<?> type, int count, long now, RandomSource r) {
        List<WCatEntity> order = new ArrayList<>(pool);
        order.sort(Comparator.comparing((WCatEntity c) -> ill(c) == null));
        List<Task.Objective> out = new ArrayList<>();
        for (WCatEntity c : order) {
            if (out.size() >= count) break;
            DiseaseType<?> has = ill(c);
            if (has == null) {
                if (!c.addDisease(type, true)) continue;                  // WCE's diseases are off, or this cat can't have it
                has = type;
            }
            Task.Objective o = Task.Objective.cure("Cure " + Kin.name(c) + "'s " + name(has) + " (" + cureWords(has) + ")",
                    has.getID() + "@" + c.getUUID(), 1);
            o.started = now;
            out.add(o);
        }
        return out;
    }

    /** The mild disease the cat already has, or null. */
    private static DiseaseType<?> ill(WCatEntity c) {
        for (DiseaseType<?> t : MILD) if (c.hasDisease(t) && !c.getDisease(t).isHealed()) return t;
        return null;
    }

    /** Without a patient: bring two of the cure instead. */
    private static Task.Objective fallback(DiseaseType<?> type, WCatEntity giver) {
        List<Item> cures = DiseaseManager.getCuresFor(type);
        StringBuilder key = new StringBuilder();
        for (Item i : cures) key.append(key.isEmpty() ? "" : "|").append(BuiltInRegistries.ITEM.getKey(i));
        if (key.isEmpty()) key.append("warriorcats_events:feverfew_leaves");
        return Task.Objective.bring("Bring 2 " + cureWords(type) + " to " + Kin.name(giver) + " for the medicine den", key.toString(), 2);
    }

    /** Whether a cure objective's patient is well now (only once the disease has had time to take hold). */
    static boolean cured(ServerLevel level, Task.Objective o, long now) {
        if (o.key.startsWith("plan:") || now - o.started < SETTLE) return false;
        int at = o.key.indexOf('@');
        if (at < 0) return false;
        DiseaseType<?> type = DiseaseRegistry.getByID(o.key.substring(0, at));
        UUID id;
        try {
            id = UUID.fromString(o.key.substring(at + 1));
        } catch (IllegalArgumentException e) {
            return true;                                                   // a broken key: nothing to wait for
        }
        if (type == null) return true;
        if (!(level.getEntity(id) instanceof WCatEntity c)) return false;   // not loaded: wait for it
        return !c.isAlive() || !c.hasDisease(type) || c.getDisease(type).isHealed();
    }

    /** The disease's name in lower case ("fever", "whitecough"). */
    static String name(DiseaseType<?> type) {
        return type.getName().getString().toLowerCase(Locale.ROOT);
    }

    /** What cures it, as WCE says: "feverfew leaves or ...". */
    static String cureWords(DiseaseType<?> type) {
        List<String> names = new ArrayList<>();
        for (Item i : DiseaseManager.getCuresFor(type)) names.add(new ItemStack(i).getHoverName().getString().toLowerCase(Locale.ROOT));
        return names.isEmpty() ? "herbs" : String.join(" or ", names);
    }
}
