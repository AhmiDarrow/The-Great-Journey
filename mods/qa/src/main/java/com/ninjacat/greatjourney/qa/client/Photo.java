package com.ninjacat.greatjourney.qa.client;

import static com.ninjacat.greatjourney.qa.client.S.*;

import com.ninjacat.greatjourney.clanlands.client.DialogueScreen;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.lwjgl.glfw.GLFW;

/**
 * TEST ONLY. Store art, photographed in the real full-pack client: the {@code store} suite (title, the start choice and
 * the joining ceremony, the stone hollow full of cats, duties and the journal, a monster on the Thunderpath and on the
 * Great Journey's highway, the book cats together in the stone hollow, and a casting sweep of book-cat coats in the sky studio)
 * the {@code portraits} suite (the chosen book cats, one by one, for the pack icon; tools/make_pack_icon.py cuts them
 * out) and the {@code gathering} suite (the book cats together, in the stone hollow and at Fourtrees). The HUD is hidden as F1 does,
 * the window is resized (still hidden, never shown), and the camera is placed by command.
 */
final class Photo {
    private Photo() {}

    /** While on, every Warrior Cats Events cat near the player is held at this body yaw, its head turned by {@link #pinHead}. */
    static boolean pin;
    static float pinBody, pinHead;

    /** Called by the driver every client tick, after the world has ticked and before the frame is drawn. */
    static void pinCats(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        for (WCatEntity cat : mc.level.getEntitiesOfClass(WCatEntity.class, mc.player.getBoundingBox().inflate(32))) {
            if (cat.isNoAi()) {
                // a cat with no AI never sends the client an on-ground flag, and Warrior Cats Events then plays its
                // falling (leaping) animation: the photo cats stand on the ground, so say so
                cat.setOnGround(true);
                cat.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            }
            if (!pin) continue;
            cat.yBodyRot = pinBody;
            cat.yBodyRotO = pinBody;
            cat.setYRot(pinBody);
            cat.yRotO = pinBody;
            cat.yHeadRot = pinBody + pinHead;
            cat.yHeadRotO = pinBody + pinHead;
            cat.setXRot(0);
            cat.xRotO = 0;
        }
    }

    // steps ---------------------------------------------------------------------------------------------------------

    /** Resizes the (hidden) game window: screenshots are taken at the framebuffer's size. */
    static Step window(int w, int h) {
        return of("window " + w + "x" + h, 200, false, (mc, age) -> {
            if (age == 0) GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), w, h);
            if (age < 20) return false;
            Driver.LOG.info("[QA] NOTE {} {} window now {}x{} (asked {}x{})", Driver.player(mc), Driver.scenario,
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(), w, h);
            return true;
        });
    }

    /** The photographer's settings: HUD hidden (as F1), field of view, entity shadows, no view bobbing, toasts cleared. */
    static Step looks(boolean hideGui, int fov, boolean shadows) {
        return of("looks", 40, false, (mc, age) -> {
            mc.options.hideGui = hideGui;
            mc.options.fov().set(fov);
            mc.options.entityShadows().set(shadows);
            mc.options.bobView().set(false);
            mc.getToasts().clear();
            // Warrior Cats Events' ambient music writes "Now playing" over the picture, HUD or not: no music
            net.snowteb.warriorcats_events.zconfig.WCEClientConfig.CLIENT.AMBIENT_MUSIC.set(false);
            mc.getMusicManager().stopPlaying();
            mc.getSoundManager().stop(null, net.minecraft.sounds.SoundSource.MUSIC);
            return true;
        });
    }

    static Step pinOff() {
        return of("pin off", 20, false, (mc, age) -> {
            pin = false;
            return true;
        });
    }

    /** Closes any window (a Clan event's dialogue) and clears toasts, so nothing covers the next picture. */
    static Step clean() {
        return of("clean", 100, false, (mc, age) -> {
            if (mc.screen instanceof DialogueScreen d) d.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
            else if (mc.screen != null) mc.setScreen(null);
            mc.getToasts().clear();
            return mc.screen == null && age > 4;
        });
    }

    /** {@code n} pictures, one every {@code every} ticks (a monster driving past, a cat mid-blink). */
    static Step burst(String name, int n, int every) {
        return of("burst " + name, n * every + 100, false, (mc, age) -> {
            if (mc.screen instanceof DialogueScreen d) d.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
            mc.getToasts().clear();
            if (age > 0 && age % every == 0) Driver.shot(mc, name + "-" + (age / every));
            return age >= n * every;
        });
    }

    /**
     * Photographs the one cat standing in the studio: the camera {@code dist} blocks from the aim point, {@code azim}
     * degrees round from the cat's nose (a three-quarter view), {@code up} blocks above it, looking at a point
     * {@code aimFwd} blocks ahead of the cat's middle and {@code aimUp} of its height up. Waits {@code settle} ticks
     * (Warrior Cats Events plays a grooming or stretching animation when a cat first appears), then takes {@code shots}
     * pictures half a second apart (a blink spoils one now and then).
     */
    static Step portrait(String name, double dist, double azim, double up, double aimFwd, double aimUp, int settle, int shots) {
        return of("portrait " + name, settle + shots * 10 + 20 * 30, false, (mc, age) -> {
            if (mc.player == null || mc.level == null) return false;
            if (mc.screen instanceof DialogueScreen d) d.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
            else if (mc.screen != null && mc.screen.getClass().getName().contains("snowteb")) mc.setScreen(null);
            WCatEntity cat = null;
            double best = 1e9;
            for (WCatEntity c : mc.level.getEntitiesOfClass(WCatEntity.class, mc.player.getBoundingBox().inflate(20))) {
                double d2 = c.distanceToSqr(mc.player);
                if (d2 < best) {
                    best = d2;
                    cat = c;
                }
            }
            if (cat == null) {
                if (age > 20 * 20) {
                    Driver.fail(mc, "portrait " + name, "no cat in the studio");
                    return true;
                }
                return false;
            }
            pin = true;
            pinBody = 0;
            pinHead = (float) (azim * 0.5);
            if (age == 2) {
                double b = Math.toRadians(pinBody), a = Math.toRadians(pinBody + azim);
                double fx = -Math.sin(b), fz = Math.cos(b);
                double h = cat.getBbHeight();
                double tx = cat.getX() + fx * aimFwd, ty = cat.getY() + h * aimUp, tz = cat.getZ() + fz * aimFwd;
                double cx = tx - Math.sin(a) * dist, cy = ty + up, cz = tz + Math.cos(a) * dist;
                float yaw = (float) (pinBody + azim + 180);
                float pitch = (float) Math.toDegrees(Math.atan2(up, dist));
                mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f",
                        cx, cy - mc.player.getEyeHeight(), cz, yaw, pitch));
                Driver.LOG.info("[QA] NOTE {} {} portrait {}: cat box {}x{} at {} {} {}, eye height {}", Driver.player(mc), Driver.scenario,
                        name, cat.getBbWidth(), cat.getBbHeight(), cat.getX(), cat.getY(), cat.getZ(), mc.player.getEyeHeight());
                return false;
            }
            if (age < settle) return false;
            int k = age - settle;
            if (k % 10 == 0) {
                mc.getToasts().clear();
                Driver.shot(mc, name + (shots > 1 ? "-" + (k / 10 + 1) : ""));
            }
            return k >= (shots - 1) * 10;
        });
    }

    /** One cat, full length then the head, from the three-quarter front. */
    static void sitter(List<Step> s, String who, String spec, String tag, int shots) {
        s.add(cmd("qa portrait " + who + (spec.isEmpty() ? "" : " " + spec)));
        s.add(portrait(tag + "-full", 2.9, 38, 0.75, 0.0, 0.45, 150, shots));
        s.add(portrait(tag + "-head", 1.9, 28, 0.3, 0.38, 0.62, 12, shots));
    }

    // suites --------------------------------------------------------------------------------------------------------

    static void store(List<Step> s) {
        s.add(scenario("G1-store"));
        s.add(titleReady());
        s.add(window(1920, 1080));
        s.add(waitTicks(60));
        s.add(shot("title"));
        s.add(click("menu.clanlands.free"));
        s.add(click("Bluestar"));
        s.add(createWorld("20261012"));
        s.add(inWorld());
        s.add(cmd("gamerule sendCommandFeedback false"));
        s.add(cmd("gamerule disablePlayerMovementCheck true"));
        s.add(cmd("time set 4000"));
        s.add(cmd("gamerule doDaylightCycle false"));
        s.add(cmd("weather clear"));
        s.add(cmd("gamerule doWeatherCycle false"));
        s.add(wceFirstJoin(20 * 25));
        s.add(looks(true, 70, true));
        s.add(Scenarios.skipDream());
        s.add(awaitDialogue("start choice", 20 * 60));
        s.add(dialogue("start", List.of("join a clan now", "thunderclan"), 120, true, 4));
        s.add(awaitMove("to the camp", 40, 20 * 60));
        s.add(waitTicks(40));
        s.add(dialogue("ceremony", List.of("goodbye"), 60, false, 5));

        s.add(scenario("G2-camp"));
        s.add(awaitCats(6, 20 * 40));
        s.add(clean());
        s.add(camera(false));
        s.add(cmd("qa tp camp lake thunderclan centre"));
        s.add(waitTicks(100));
        s.add(burst("hollow-third", 3, 30));
        s.add(camera(true));
        for (String v : List.of("gate", "centre", "warriors", "nursery", "leader")) {
            s.add(cmd("qa tp camp lake thunderclan " + v));
            s.add(waitTicks(80));
            s.add(burst("hollow-" + v, 2, 30));
        }
        s.add(cmd("qa tp camp lake thunderclan centre"));
        s.add(cmd("gamemode survival"));
        s.add(camera(false));
        s.add(waitTicks(60));
        s.add(cmd("qa talk medicine"));
        s.add(dialogue("medicine", List.of("do you need help", "i'll do it", "goodbye"), 60, true, 4));
        s.add(journal());

        s.add(scenario("G3-monster"));
        s.add(clean());
        s.add(camera(true));
        s.add(cmd("qa tp road lake THUNDERPATH"));
        s.add(waitTicks(200));
        s.add(cmd("qa monster"));
        s.add(burst("thunderpath-monster", 14, 10));

        s.add(scenario("G4-cast"));
        s.add(cmd("gamemode spectator"));
        s.add(cmd("time set 6000"));
        s.add(cmd("gamerule doMobSpawning false"));
        s.add(window(1600, 1600));
        s.add(looks(true, 30, false));
        s.add(camera(true));
        s.add(cmd("qa studio"));
        s.add(waitTicks(60));
        String[][] trials = {
                {"firestar", ""}, {"firestar", "ruf=3,tabv=3"},
                {"graystripe", ""}, {"graystripe", "ag=A-A,tab=Mc-Mc,tabv=0"}, {"graystripe", "ag=A-A,tab=Mc-Mc,tabv=2"},
                {"bluestar", ""}, {"bluestar", "silverv=1"}, {"bluestar", "silver=i-i,bruf=4"},
                {"yellowfang", ""}, {"yellowfang", "dil=d-d,silver=I-i,silverv=1"},
                {"ravenpaw", ""}, {"ravenpaw", "whitev=8"}, {"ravenpaw", "whitev=13"},
                {"sandstorm", ""}, {"sandstorm", "dil=d-d,ruf=0,tabv=2"},
                {"spottedleaf", "torv=0"}, {"spottedleaf", "torv=3"}, {"spottedleaf", "torv=6"}, {"spottedleaf", "torv=9"},
                {"spottedleaf", "torv=12"}, {"spottedleaf", "torv=15"},
                {"tigerstar", ""}, {"tigerstar", "tabv=2"}, {"tigerstar", "tab=Mc-Mc,tabv=1"},
                {"cinderpelt", ""}};
        int i = 0;
        for (String[] t : trials) {
            String tag = String.format(Locale.ROOT, "cast-%02d-%s", ++i, t[0]);
            Driver.LOG.info("[QA] NOTE plan {} = {} {}", tag, t[0], t[1]);
            sitter(s, t[0], t[1], tag, 1);
        }
        s.add(pinOff());
        s.add(cmd("qa portrait clear"));

        s.add(scenario("G5-gathering"));
        s.add(window(1920, 1080));
        s.add(looks(true, 60, true));
        s.add(cmd("qa tp camp lake thunderclan centre"));
        s.add(waitTicks(200));
        s.add(cmd("qa gathering here"));
        s.add(waitTicks(200));
        s.add(burst("gathering", 4, 40));
        s.add(cmd("qa portrait clear"));

        s.add(scenario("G6-highway"));
        s.add(looks(true, 70, true));
        s.add(cmd("qa tp road journey HIGHWAY"));
        s.add(waitTicks(600));
        s.add(cmd("qa monster"));
        s.add(burst("highway-monster", 14, 10));
        s.add(cmd("qa monster"));
        s.add(burst("highway-monster-b", 14, 10));
    }

    /** The chosen coats (from the store suite's casting sweep), each from four angles, for the pack icon; then store shots. */
    static void portraits(List<Step> s) {
        Scenarios.newWorld(s, "P1-portraits", "menu.clanlands.free", "20261012", false);
        s.add(looks(true, 70, true));
        s.add(Scenarios.skipDream());
        s.add(awaitDialogue("start choice", 20 * 60));
        s.add(dialogue("start", List.of("a loner", "loner"), 120, true, 0));
        s.add(waitTicks(200));
        s.add(clean());
        s.add(cmd("gamemode spectator"));
        s.add(cmd("time set 6000"));
        s.add(cmd("gamerule doDaylightCycle false"));
        s.add(cmd("gamerule doWeatherCycle false"));
        s.add(cmd("weather clear"));
        s.add(cmd("gamerule doMobSpawning false"));
        s.add(window(1600, 1600));
        s.add(looks(true, 30, false));
        s.add(camera(true));
        s.add(cmd("qa studio"));
        s.add(waitTicks(60));
        for (String who : FINAL.keySet()) {
            String spec = FINAL.get(who);
            s.add(cmd("qa portrait " + who + (spec.isEmpty() ? "" : " " + spec)));
            s.add(portrait(who + "-full", 2.9, 38, 0.75, 0.0, 0.45, 150, 2));
            s.add(portrait(who + "-head-left", 2.3, 30, 0.35, 0.35, 0.7, 12, 3));
            s.add(portrait(who + "-head-right", 2.3, -30, 0.35, 0.35, 0.7, 12, 3));
            s.add(portrait(who + "-head-front", 2.3, 6, 0.3, 0.35, 0.7, 12, 3));
        }
        s.add(pinOff());
        s.add(cmd("qa portrait clear"));

        s.add(scenario("P2-gathering"));
        s.add(window(1920, 1080));
        s.add(looks(true, 60, true));
        s.add(cmd("time set 4000"));
        s.add(cmd("qa tp camp lake thunderclan centre"));
        s.add(waitTicks(200));
        s.add(cmd("qa gathering here"));
        s.add(waitTicks(200));
        s.add(burst("gathering-hollow", 4, 40));
        s.add(cmd("qa portrait clear"));

        s.add(scenario("P3-monsters"));
        s.add(looks(true, 70, true));
        s.add(cmd("qa tp road journey HIGHWAY on"));
        s.add(waitTicks(500));
        s.add(cmd("qa monster at-me"));
        s.add(burst("highway-head-on", 30, 4));
        s.add(cmd("qa tp road journey HIGHWAY"));
        s.add(waitTicks(60));
        s.add(cmd("qa monster at-me"));
        s.add(burst("highway-roadside", 30, 4));
        s.add(cmd("qa tp road lake THUNDERPATH on"));
        s.add(waitTicks(300));
        s.add(cmd("qa monster at-me"));
        s.add(burst("thunderpath-head-on", 30, 4));
    }

    /** The cast together for the store page: in the stone hollow, then at Fourtrees. */
    static void gathering(List<Step> s) {
        Scenarios.newWorld(s, "P4-gathering", "menu.clanlands.free", "20261012", false);
        s.add(looks(true, 70, true));
        s.add(Scenarios.skipDream());
        s.add(awaitDialogue("start choice", 20 * 60));
        s.add(dialogue("start", List.of("a loner", "loner"), 120, true, 0));
        s.add(waitTicks(200));
        s.add(clean());
        s.add(cmd("gamemode spectator"));
        s.add(cmd("time set 4000"));
        s.add(cmd("gamerule doDaylightCycle false"));
        s.add(cmd("gamerule doWeatherCycle false"));
        s.add(cmd("weather clear"));
        s.add(window(1920, 1080));
        s.add(looks(true, 50, true));
        s.add(camera(true));
        s.add(cmd("qa tp camp lake thunderclan centre"));
        s.add(waitTicks(200));
        s.add(cmd("qa gathering here"));
        s.add(waitTicks(220));
        s.add(burst("gathering-hollow", 4, 30));
        s.add(cmd("qa tp camp lake thunderclan gate"));
        s.add(waitTicks(100));
        s.add(cmd("qa gathering here"));
        s.add(waitTicks(220));
        s.add(burst("gathering-gate", 4, 30));
        s.add(cmd("qa portrait clear"));
        s.add(cmd("qa tp landmark forest FOURTREES"));
        s.add(waitTicks(900));
        s.add(cmd("qa tp landmark forest FOURTREES"));
        s.add(waitTicks(100));
        s.add(cmd("qa gathering here"));
        s.add(waitTicks(220));
        s.add(burst("gathering-fourtrees", 4, 30));
        s.add(cmd("qa portrait clear"));
    }

    /** The coats picked for the icon: overrides on top of the cast in server/Portraits. */
    static final java.util.Map<String, String> FINAL = new java.util.LinkedHashMap<>();

    static {
        FINAL.put("firestar", "");
        FINAL.put("graystripe", "ag=A-A,tab=Mc-Mc,tabv=0");
        FINAL.put("bluestar", "");
        FINAL.put("yellowfang", "dil=d-d,silver=I-i,silverv=1");
        FINAL.put("ravenpaw", "whitev=8");
        FINAL.put("sandstorm", "");
        FINAL.put("spottedleaf", "torv=0");
        FINAL.put("tigerstar", "");
    }
}
