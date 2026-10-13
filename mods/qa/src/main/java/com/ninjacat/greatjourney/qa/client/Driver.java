package com.ninjacat.greatjourney.qa.client;

import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.qa.Qa;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * TEST ONLY. Plays a playtest suite (the JVM property {@code greatjourney.qa.suite}, see {@link Scenarios}) step by
 * step, logging machine-readable lines ({@code [QA] PASS|FAIL|SHOT|STEP ...}) and saving screenshots to
 * {@code screenshots/qa-*.png}, then quits the game. Never sends real input to the operating system: buttons and
 * keys are pressed by calling the screens' own methods.
 */
@EventBusSubscriber(modid = "greatjourney_qa", value = Dist.CLIENT)
public final class Driver {
    private Driver() {}

    static final Logger LOG = LogUtils.getLogger();
    private static List<Step> plan;
    private static int at, age, total, shots, deadTicks;
    static String scenario = "-";
    private static boolean finished;
    /** Barrier tags the server has released ("[QA] GO tag" in chat). */
    static final java.util.Set<String> GO = new java.util.HashSet<>();

    @SubscribeEvent
    static void chat(net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System event) {
        String text = event.getMessage().getString();
        if (text.startsWith("[QA] GO ")) GO.add(text.substring(8).trim());
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (!Qa.driving() || finished) return;
        Minecraft mc = Minecraft.getInstance();
        if (plan == null) {
            plan = Scenarios.suite(Qa.SUITE);
            LOG.info("[QA] SUITE {} {} steps", Qa.SUITE, plan.size());
        }
        total++;
        Photo.pinCats(mc);
        if (total % 400 == 0) LOG.info("[QA] NOTE {} {} alive: step {} age {}, screen {}, overlay {}, window {}x{}", player(mc), scenario,
                at < plan.size() ? slug(plan.get(at).name()) : "-", age, mc.screen == null ? "none" : mc.screen.getClass().getName(),
                mc.getOverlay() == null ? "none" : mc.getOverlay().getClass().getSimpleName(), mc.getWindow().getWidth(), mc.getWindow().getHeight());
        if (at >= plan.size()) {
            finish(mc, "done");
            return;
        }
        Step s = plan.get(at);
        // an unexpected death is a finding, not the end of the run: photograph it, fail it, respawn and carry on
        if (mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen && mc.player != null && !s.name().startsWith("struck")) {
            if (++deadTicks == 30) {
                shot(mc, "unexpected-death");
                fail(mc, "unexpected death", "during step " + slug(s.name()));
                mc.player.respawn();
                mc.setScreen(null);
                deadTicks = 0;
            }
            return;
        }
        boolean done;
        try {
            done = s.tick(mc, age);
        } catch (Exception e) {
            LOG.error("[QA] FAIL {} {} step-{}: threw {}", player(mc), scenario, slug(s.name()), e.toString(), e);
            done = true;
        }
        age++;
        if (!done && age > s.timeout()) {
            LOG.warn("[QA] FAIL {} {} step-{}: timed out after {}s (screen {})", player(mc), scenario, slug(s.name()),
                    age / 20, mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
            shot(mc, "timeout-" + slug(s.name()));
            if (s.critical()) {
                skipScenario();
                return;
            }
            done = true;
        }
        if (done) {
            at++;
            age = 0;
            if (at < plan.size()) LOG.info("[QA] STEP {} {} {}", player(mc), scenario, slug(plan.get(at).name()));
        }
    }

    /** After a critical failure: on to the next scenario's first step (its "scenario" marker). */
    private static void skipScenario() {
        for (int i = at + 1; i < plan.size(); i++) {
            if (plan.get(i) instanceof S.Scenario) {
                at = i;
                age = 0;
                return;
            }
        }
        at = plan.size();
    }

    static void finish(Minecraft mc, String why) {
        finished = true;
        LOG.info("[QA] END {} {} after {}s, {} screenshots", player(mc), why, total / 20, shots);
        mc.stop();
    }

    static String player(Minecraft mc) {
        return mc.getUser() == null ? "?" : mc.getUser().getName();
    }

    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static void shot(Minecraft mc, String name) {
        String file = String.format(Locale.ROOT, "qa-%s-%s-%03d-%s.png", slug(player(mc)), slug(scenario), ++shots, slug(name));
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), msg -> {});
        LOG.info("[QA] SHOT {} {} {}", player(mc), scenario, file);
    }

    static void pass(Minecraft mc, String check) {
        LOG.info("[QA] PASS {} {} {}", player(mc), scenario, slug(check));
    }

    static void fail(Minecraft mc, String check, String why) {
        LOG.warn("[QA] FAIL {} {} {}: {}", player(mc), scenario, slug(check), why);
    }
}
