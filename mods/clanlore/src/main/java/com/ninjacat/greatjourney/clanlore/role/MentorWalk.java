package com.ninjacat.greatjourney.clanlore.role;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity.CatMode;

/**
 * Now and then, by day, an apprentice pads after its mentor for a while, the way an apprentice shadows its mentor in
 * the books. It is a goal at the same priority as Warrior Cats Events' own wandering, so it starts only when WCE has
 * nothing more pressing for the cat (a call, a patrol, prey, a fight) and gives way to all of that; it never runs for
 * a cat that is sitting, following its owner, or in a Clan the pack runs itself (whose camp routine pairs them).
 */
final class MentorWalk extends Goal {
    /** WCE's bounded wander sits at this priority. */
    static final int PRIORITY = 14;
    /** A roll per check; checks come every other tick, so a walk starts about every few minutes. */
    static final int CHANCE = 2400;
    static final double REACH = 24, CLOSE = 3;

    private static Field goals;
    private static boolean failed;

    private final WCatEntity cat;
    private WCatEntity mentor;
    private int left, repath;

    private MentorWalk(WCatEntity cat) {
        this.cat = cat;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Gives the cat the goal (WCE builds its goals in a protected list, so it is reached by reflection). */
    static void addTo(WCatEntity cat) {
        if (failed) return;
        try {
            if (goals == null) {
                goals = Mob.class.getDeclaredField("goalSelector");
                goals.setAccessible(true);
            }
            ((GoalSelector) goals.get(cat)).addGoal(PRIORITY, new MentorWalk(cat));
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            LogUtils.getLogger().warn("Clan Lore: apprentices will not walk with their mentors ({})", e.toString());
        }
    }

    @Override
    public boolean canUse() {
        if (!RoleConfig.MENTOR_WALKS.get() || cat.getRank() != WCatEntity.Rank.APPRENTICE || !free(cat)) return false;
        UUID id = Roles.mentorOf(cat);
        if (id == null || cat.getRandom().nextInt(CHANCE) != 0 || !cat.level().isDay() || Roles.kept(cat)) return false;
        if (!(cat.level() instanceof ServerLevel level) || !(level.getEntity(id) instanceof WCatEntity m)) return false;
        if (!m.isAlive() || !free(m) || m.distanceTo(cat) > REACH) return false;
        mentor = m;
        return true;
    }

    /** Wandering on its own, not sitting, fighting, carried, or held by someone's routine. */
    private static boolean free(WCatEntity c) {
        return c.mode == CatMode.WANDER && !c.isOrderedToSit() && c.getTarget() == null && !c.isPassenger()
                && !c.onBorderPatrolFlag && !c.onHuntingPatrolFlag && !c.returningFromPatrol;
    }

    @Override
    public void start() {
        left = 600 + cat.getRandom().nextInt(600);
        repath = 0;
    }

    @Override
    public boolean canContinueToUse() {
        return left > 0 && mentor != null && mentor.isAlive() && free(cat) && mentor.distanceTo(cat) < REACH * 1.5 && cat.level().isDay();
    }

    @Override
    public void tick() {
        left--;
        cat.getLookControl().setLookAt(mentor, 30, 30);
        if (--repath > 0) return;
        repath = 10;
        if (cat.distanceTo(mentor) > CLOSE) cat.getNavigation().moveTo(mentor, 1.0);
        else cat.getNavigation().stop();
    }

    @Override
    public void stop() {
        mentor = null;
        cat.getNavigation().stop();
    }
}
