package com.ninjacat.greatjourney.qa.client;

import com.ninjacat.greatjourney.clanlands.client.DialogueScreen;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import com.ninjacat.greatjourney.qa.server.Watch;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import org.lwjgl.glfw.GLFW;

/**
 * TEST ONLY. The walking bot: it walks the player on its own legs (a stand-in for the keyboard, no real input) where
 * the server half ({@link Watch}) says: in a guide's or mentor's footsteps, or to the next border point of a patrol.
 * A bot that makes no headway for a few seconds steps sideways and jumps, then asks the server to put it a few blocks
 * on ({@code /qa nudge}, counted in the report). Singleplayer only: it reads the integrated server's watch directly.
 */
final class Walk {
    private Walk() {}

    /** The keys the bot holds down this tick. */
    static final class BotInput extends Input {
        boolean go, jump;
        int strafe;

        @Override
        public void tick(boolean slow, float sneak) {
            forwardImpulse = go ? 1 : 0;
            leftImpulse = strafe;
            up = go;
            down = false;
            left = strafe > 0;
            right = strafe < 0;
            jumping = jump;
            shiftKeyDown = false;
        }
    }

    private static final BotInput BOT = new BotInput();
    private static Input saved;

    private static void hold(Minecraft mc) {
        if (mc.player != null && !(mc.player.input instanceof BotInput)) {
            saved = mc.player.input;
            mc.player.input = BOT;
        }
    }

    static void letGo(Minecraft mc) {
        BOT.go = BOT.jump = false;
        BOT.strafe = 0;
        if (mc.player != null && mc.player.input instanceof BotInput && saved != null) mc.player.input = saved;
    }

    /** Waits until the watch has something to follow (a lead begun, a patrol taken), reading any window meanwhile. */
    static Step await(String name, int timeout, List<String> prefer) {
        return S.of("await " + name, timeout, false, (mc, age) -> {
            if (mc.player == null) return false;
            dialogue(mc, age, prefer);
            Watch.Goal g = Watch.goal(mc.player.getUUID());
            if (g != null && g.active) {
                Driver.pass(mc, name + " began");
                return true;
            }
            if (age >= timeout - 2) Driver.fail(mc, name + " began", "nothing to follow after " + age / 20 + "s");
            return false;
        });
    }

    /**
     * Walks where the watch says until there is nothing more to follow (the lead has ended, or a patrol is home),
     * photographing at the start, at every stop reached (up to {@code maxStopShots}) and every {@code every} ticks.
     */
    static Step follow(String name, int timeout, List<String> prefer, int maxStopShots, int every) {
        final int[] st = {-1, 0, 0, 0, 0};                  // last reached, stop shots, stuck checks, strafe ticks left, nudges
        final double[] was = {Double.NaN, 0};
        return S.of("follow " + name, timeout, false, (mc, age) -> {
            if (mc.player == null) return false;
            Watch.Goal g = Watch.goal(mc.player.getUUID());
            if (g == null || !g.active || g.home) {
                letGo(mc);
                if (age >= timeout - 2) return true;
                if (g != null && g.home) Driver.pass(mc, name + " home");
                Driver.LOG.info("[QA] NOTE {} {} {}: done after {}s, {} stops, {} nudges", Driver.player(mc), Driver.scenario, name, age / 20,
                        g == null ? 0 : g.reached, st[4]);
                return true;
            }
            if (age >= timeout - 2) {
                letGo(mc);
                Driver.shot(mc, name + "-timeout");
                Driver.fail(mc, name + " finished", "still walking after " + age / 20 + "s, " + g.reached + "/" + g.stops);
                return true;
            }
            if (mc.screen instanceof DialogueScreen) {
                BOT.go = false;
                dialogue(mc, age, prefer);
                return false;
            }
            if (mc.screen != null && mc.screen.getClass().getName().contains("snowteb")) mc.setScreen(null);
            hold(mc);
            if (age == 20) Driver.shot(mc, name + "-start");
            if (g.reached != st[0]) {
                if (st[0] >= 0 && st[1] < maxStopShots) {
                    Driver.shot(mc, String.format(Locale.ROOT, "%s-stop-%02d", name, g.reached));
                    st[1]++;
                }
                st[0] = g.reached;
            }
            if (every > 0 && age > 0 && age % every == 0) Driver.shot(mc, name + "-walking");
            var pl = mc.player;
            double dx = g.x - pl.getX(), dz = g.z - pl.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pl.setYRot(yaw);
            pl.setYHeadRot(yaw);
            pl.setXRot(12);
            BOT.go = g.go;
            // a walking player: starting and stopping the forward key reads as a double tap, which sprints
            if (pl.isSprinting()) pl.setSprinting(false);
            BOT.jump = g.go && (pl.horizontalCollision || pl.isInWater() || pl.isInLava());
            if (st[3] > 0) {
                st[3]--;
                BOT.jump = true;
                if (st[3] == 0) BOT.strafe = 0;
            }
            // headway: every two seconds, has a bot that means to walk moved at all?
            if (age % 40 == 0) {
                double moved = Double.isNaN(was[0]) ? 99 : Math.hypot(pl.getX() - was[0], pl.getZ() - was[1]);
                was[0] = pl.getX();
                was[1] = pl.getZ();
                if (g.go && moved < 1.0) {
                    st[2]++;
                    if (st[2] == 1) {
                        BOT.strafe = (age / 40) % 2 == 0 ? 1 : -1;     // step round the trunk or the boulder
                        st[3] = 20;
                    } else if (st[2] >= 2) {
                        pl.connection.sendCommand("qa nudge");
                        st[4]++;
                        st[2] = 0;
                    }
                } else {
                    st[2] = 0;
                }
            }
            return false;
        });
    }

    /** Reads an open dialogue window as {@link S#dialogue} does: turns pages, and picks a preferred choice. */
    private static void dialogue(Minecraft mc, int age, List<String> prefer) {
        if (!(mc.screen instanceof DialogueScreen d) || !d.autotestReady() || age % 6 != 0) return;
        if (!d.autotestChoosing()) {
            d.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0);
            return;
        }
        List<ClanPayloads.ChoiceView> cs = d.autotestChoices();
        int pick = 0;
        outer:
        for (String want : prefer) {
            for (int i = 0; i < cs.size(); i++) {
                if (cs.get(i).label().toLowerCase(Locale.ROOT).contains(want.toLowerCase(Locale.ROOT))) {
                    pick = i;
                    break outer;
                }
            }
        }
        Driver.LOG.info("[QA] NOTE {} {} choosing '{}' of {}", Driver.player(mc), Driver.scenario, cs.get(pick).label(),
                cs.stream().map(ClanPayloads.ChoiceView::label).toList());
        d.keyPressed(GLFW.GLFW_KEY_1 + pick, 0, 0);
    }
}
