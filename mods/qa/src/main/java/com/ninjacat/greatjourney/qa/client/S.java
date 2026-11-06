package com.ninjacat.greatjourney.qa.client;

import com.ninjacat.greatjourney.clanlands.client.DialogueScreen;
import com.ninjacat.greatjourney.clanlands.client.JournalScreen;
import com.ninjacat.greatjourney.clanlands.client.TourClient;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** TEST ONLY. The steps playtest scripts are made of. */
final class S {
    private S() {}

    static String lastWorld;
    /** Where the player stood when they last answered a dialogue choice (a start or a join moves them from there). */
    static double[] choicePos;

    /** Marks the start of a scenario: names it for the log (and the server's /qa lines, once in a world). */
    static final class Scenario implements Step {
        final String id;

        Scenario(String id) {
            this.id = id;
        }

        @Override public String name() {
            return "scenario " + id;
        }

        @Override public boolean tick(Minecraft mc, int age) {
            Driver.scenario = id;
            Driver.LOG.info("[QA] SCENARIO {} {}", Driver.player(mc), id);
            if (mc.player != null) {
                mc.player.connection.sendCommand("qa begin " + id);
                told = id;
            }
            return true;
        }
    }

    static Step scenario(String id) {
        return new Scenario(id);
    }

    static Step of(String name, int timeout, boolean critical, StepBody body) {
        return new Step() {
            @Override public String name() {
                return name;
            }

            @Override public boolean tick(Minecraft mc, int age) {
                return body.tick(mc, age);
            }

            @Override public int timeout() {
                return timeout;
            }

            @Override public boolean critical() {
                return critical;
            }
        };
    }

    interface StepBody {
        boolean tick(Minecraft mc, int age);
    }

    static Step waitTicks(int n) {
        return of("wait " + n, n + 20, false, (mc, age) -> age >= n);
    }

    static Step until(String name, int timeoutTicks, Predicate<Minecraft> p) {
        return of(name, timeoutTicks, true, (mc, age) -> p.test(mc));
    }

    static Step shot(String name) {
        return of("shot " + name, 40, false, (mc, age) -> {
            if (age < 3) return false;                       // let a frame or two render first
            Driver.shot(mc, name);
            return true;
        });
    }

    /** Sends a chat command as the player (operators in the harness worlds and on its server). */
    static String told;

    static Step cmd(String command) {
        return of("cmd " + command, 100, false, (mc, age) -> {
            if (mc.player == null) return false;
            if (age == 0) {
                if (!Driver.scenario.equals(told) && !command.startsWith("qa begin")) {   // entered a world mid-scenario
                    mc.player.connection.sendCommand("qa begin " + Driver.scenario);
                    told = Driver.scenario;
                }
                mc.player.connection.sendCommand(command);
            }
            return age >= 6;
        });
    }

    /** A server-side check: /qa check ... logs PASS or FAIL on the server. */
    static Step check(String what) {
        return cmd("qa check " + what);
    }

    static Step clientCheck(String name, Predicate<Minecraft> p, String why) {
        return of("check " + name, 40, false, (mc, age) -> {
            if (p.test(mc)) Driver.pass(mc, name);
            else Driver.fail(mc, name, why);
            return true;
        });
    }

    // screens ---------------------------------------------------------------------------------------------------

    static String key(GuiEventListener l) {
        if (l instanceof AbstractWidget w) {
            if (w.getMessage().getContents() instanceof TranslatableContents t) return t.getKey();
            return w.getMessage().getString();
        }
        return "";
    }

    static AbstractWidget widget(Screen s, String keyOrText) {
        for (GuiEventListener l : s.children()) {
            if (!(l instanceof AbstractWidget w) || !w.visible || !w.active) continue;
            String text = w.getMessage().getString().toLowerCase(Locale.ROOT);
            if (keyOrText.startsWith("~") ? text.contains(keyOrText.substring(1).toLowerCase(Locale.ROOT))
                    : key(l).equals(keyOrText) || text.equalsIgnoreCase(keyOrText)) return w;
        }
        return null;
    }

    /** Clicks a widget by calling its own click handler at its centre (no real mouse involved). */
    static void press(AbstractWidget w) {
        w.mouseClicked(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
        w.mouseReleased(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, 0);
    }

    /** The title screen, ready. A different screen sitting in its place (a mod's warning) is photographed, failed and closed. */
    static Step titleReady() {
        final int[] other = {0};
        return of("title screen", 20 * 60 * 6, true, (mc, age) -> {
            if (mc.getOverlay() != null) return false;
            if (mc.screen != null && !(mc.screen instanceof TitleScreen) && mc.level == null) {
                if (++other[0] == 600) {
                    Driver.shot(mc, "startup-" + mc.screen.getClass().getSimpleName());
                    Driver.fail(mc, "startup screen", mc.screen.getClass().getName() + " instead of the title screen");
                    mc.screen.onClose();
                }
                return false;
            }
            return mc.screen instanceof TitleScreen && age > 60;
        });
    }

    static Step click(String keyOrText) {
        return of("click " + keyOrText, 20 * 20, true, (mc, age) -> {
            if (mc.screen == null || age < 10) return false;
            AbstractWidget w = widget(mc.screen, keyOrText);
            if (w == null) return false;
            press(w);
            return true;
        });
    }

    static Step titleButtons() {
        return of("title buttons", 100, false, (mc, age) -> {
            if (!(mc.screen instanceof TitleScreen t)) return false;
            List<String> keys = new ArrayList<>();
            for (GuiEventListener l : t.children()) if (l instanceof AbstractWidget w && w.visible) keys.add(key(l));
            for (String want : List.of("menu.clanlands.campaign", "menu.clanlands.free", "menu.clanlands.load")) {
                if (keys.contains(want)) Driver.pass(mc, "title has " + want);
                else Driver.fail(mc, "title has " + want, "buttons: " + keys);
            }
            if (keys.contains("menu.online")) Driver.fail(mc, "title has no realms", "a Realms button is showing");
            else Driver.pass(mc, "title has no realms");
            return true;
        });
    }

    /** On the create-world screen the title menu opened: commands on (for the harness), optional seed, create. */
    static Step createWorld(String seed) {
        return of("create world", 20 * 30, true, (mc, age) -> {
            if (!(mc.screen instanceof CreateWorldScreen c) || age < 20) return false;
            c.getUiState().setAllowCommands(true);
            if (seed != null) c.getUiState().setSeed(seed);
            if (age < 30) return false;
            Driver.shot(mc, "create-world");
            AbstractWidget b = widget(c, "selectWorld.create");
            if (b == null) return false;
            press(b);
            return true;
        });
    }

    /** Waits until the player is in a world; answers vanilla's experimental-settings prompt if it appears. */
    static Step inWorld() {
        return of("enter world", 20 * 60 * 8, true, (mc, age) -> {
            if (mc.screen instanceof ConfirmScreen confirm) {
                Driver.shot(mc, "confirm-screen");
                Driver.fail(mc, "no confirm screen", "a confirm screen appeared: " + confirm.getTitle().getString());
                AbstractWidget yes = widget(confirm, "gui.yes");
                if (yes == null) yes = widget(confirm, "selectWorld.backupJoinSkipButton");
                if (yes != null) press(yes);
                return false;
            }
            if (mc.player == null || mc.level == null || age < 40) return false;
            if (mc.getSingleplayerServer() != null)
                lastWorld = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
            return true;
        });
    }

    /**
     * Warrior Cats Events' first-join screens: photographs each, finishes the cat creator with its own "save and
     * next" button, and fails if the spawn-biome picker (which teleported players blind) ever shows.
     */
    static Step wceFirstJoin(int lookTicks) {
        final boolean[] seen = {false, false};
        return of("wce first join", lookTicks + 20 * 60, false, (mc, age) -> {
            Screen s = mc.screen;
            if (s != null && s.getClass().getName().contains("snowteb")) {
                String n = s.getClass().getSimpleName();
                if (n.equals("SpawnLocationScreen")) {
                    if (!seen[1]) {
                        Driver.shot(mc, "wce-spawn-picker");
                        Driver.fail(mc, "no spawn picker", "Warrior Cats Events' spawn-biome picker opened");
                        seen[1] = true;
                    }
                    mc.setScreen(null);
                    return false;
                }
                if (!seen[0]) {
                    if (age < 30) return false;
                    Driver.shot(mc, "wce-" + n);
                    seen[0] = true;
                    Driver.pass(mc, "cat creator shown");
                }
                if (n.equals("ClanSetupScreen")) {            // name, age and sex first, as a new player fills them in
                    if (age % 15 != 0) return false;
                    for (String b : List.of("Random Prefix", "Random Suffix", "Adult", "She-cat")) {
                        AbstractWidget w = widget(s, b);
                        if (w != null) press(w);
                    }
                    AbstractWidget w = widget(s, "Next");
                    if (w != null && age % 45 == 0) {
                        Driver.shot(mc, "wce-name-filled");
                        press(w);
                    }
                    return false;
                }
                AbstractWidget next = widget(s, "~save and next");
                if (next == null) next = widget(s, "~next");
                if (next != null && age % 20 == 0) {
                    Driver.shot(mc, "wce-" + n + "-done");
                    press(next);
                } else if (next == null && age % 100 == 0) {
                    List<String> labels = new ArrayList<>();
                    for (GuiEventListener l : s.children()) if (l instanceof AbstractWidget w) labels.add(w.getMessage().getString());
                    Driver.LOG.info("[QA] NOTE {} {} no next button on {}: {}", Driver.player(mc), Driver.scenario, n, labels);
                    if (age > 20 * 60) mc.setScreen(null);
                }
                return false;
            }
            if (age < lookTicks) return false;
            if (!seen[1]) Driver.pass(mc, "no spawn picker");
            if (!seen[0]) Driver.LOG.info("[QA] NOTE {} {} cat creator never showed", Driver.player(mc), Driver.scenario);
            return true;
        });
    }

    /**
     * Reads dialogue scenes: turns pages, photographs the first lines and every choice, and picks the first choice
     * whose label contains one of {@code prefer} (else the first). Done once no window has been open for
     * {@code quiet} ticks; with {@code require}, fails if none ever opened.
     */
    static Step dialogue(String name, List<String> prefer, int quiet, boolean require, int maxShots) {
        final int[] st = {0, 0, 0};                         // shots, last seen tick, scenes answered
        final boolean[] any = {false};
        return of("dialogue " + name, 20 * 60 * 4, false, (mc, age) -> {
            if (mc.screen instanceof DialogueScreen d) {
                any[0] = true;
                st[1] = age;
                if (!d.autotestReady()) return false;
                if (age % 6 != 0) return false;
                if (d.autotestChoosing()) {
                    if (st[0] < maxShots) {
                        Driver.shot(mc, name + "-choices");
                        st[0]++;
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
                    // where the step's first choice (a start, a join) was made: later ones in the same step (the seek
                    // choice, the guide's offer, answered at the new home) are not where the move began
                    if (mc.player != null && st[2] == 0) choicePos = new double[] {mc.player.getX(), mc.player.getZ()};
                    d.keyPressed(GLFW.GLFW_KEY_1 + pick, 0, 0);
                    st[2]++;
                } else {
                    if (st[0] < maxShots) {
                        Driver.shot(mc, name + "-line");
                        st[0]++;
                    }
                    d.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0);
                }
                return false;
            }
            if (mc.screen != null && mc.screen.getClass().getName().contains("snowteb")) mc.setScreen(null);
            if (age - st[1] < quiet) return false;
            if (require && !any[0]) {
                if (age < 20 * 45) return false;
                Driver.fail(mc, "dialogue " + name, "no dialogue window opened");
                return true;
            }
            if (any[0]) Driver.pass(mc, "dialogue " + name);
            return true;
        });
    }

    /** Opens the journal (as the journal item does) and photographs each of its five tabs. */
    static Step journal() {
        final int[] tab = {0};
        return of("journal", 20 * 40, false, (mc, age) -> {
            if (!(mc.screen instanceof JournalScreen js)) {
                if (mc.screen instanceof DialogueScreen d) {
                    d.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                    return false;
                }
                if (tab[0] == 0 && age % 40 == 0) PacketDistributor.sendToServer(new ClanPayloads.RequestJournal());
                if (tab[0] > 10) return true;
                return false;
            }
            if (age % 8 != 0) return false;
            if (tab[0] <= 10) {
                int t = (tab[0] + 1) / 2;
                if (tab[0] % 2 == 1) js.keyPressed(GLFW.GLFW_KEY_1 + t - 1, 0, 0);
                else if (t > 0) Driver.shot(mc, "journal-tab-" + t);
                tab[0]++;
                return false;
            }
            Driver.pass(mc, "journal opened");
            mc.setScreen(null);
            return true;
        });
    }

    /** /greatjourney tour, photographing every stop halfway through its camera move; checks the player is restored. */
    static Step tour() {
        final int[] st = {-1, 0, 0};                        // last stop shot, seen, end shot
        return of("tour", 20 * 60 * 20, false, (mc, age) -> {   // a new world's stops may each wait up to 25 s to load
            if (age == 0) {
                mc.player.connection.sendCommand("greatjourney tour");
                return false;
            }
            String phase = TourClient.autotestPhase();
            if (!phase.equals("IDLE")) st[1] = 1;
            int i = TourClient.autotestIndex();
            if (phase.equals("SHOWING") && i >= 0 && i != st[0] && TourClient.autotestFraction() > 0.5f) {
                st[0] = i;
                Driver.shot(mc, String.format(Locale.ROOT, "tour-%02d-%s", i + 1, TourClient.autotestTitle()));
            }
            if (phase.equals("END_CARD") && st[2] == 0 && age % 20 == 0) {
                st[2] = 1;
                Driver.shot(mc, "tour-end");
            }
            if (age > 200 && st[1] == 0) {
                Driver.fail(mc, "tour started", "the tour never began");
                return true;
            }
            if (st[1] == 1 && phase.equals("IDLE")) {
                Driver.pass(mc, "tour played " + (st[0] + 1) + " stops");
                return true;
            }
            return false;
        });
    }

    /** Leaves the world for the title screen, as "Save and Quit to Title" does. */
    static Step quitToTitle() {
        return of("quit to title", 20 * 60 * 3, true, (mc, age) -> {
            if (age == 0 && mc.level != null) {
                if (mc.screen != null) mc.setScreen(null);
                boolean local = mc.isLocalServer();
                mc.level.disconnect();
                if (local) mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                else mc.disconnect();
                mc.setScreen(new TitleScreen());
                return false;
            }
            return mc.level == null && mc.screen instanceof TitleScreen && age > 40;
        });
    }

    /** Opens the singleplayer world this client last entered. */
    static Step reopenWorld() {
        return of("reopen world", 20 * 60 * 5, true, (mc, age) -> {
            if (age == 0) {
                if (lastWorld == null) {
                    Driver.fail(mc, "reopen world", "no world to reopen");
                    return true;
                }
                mc.createWorldOpenFlows().openWorld(lastWorld, () -> mc.setScreen(new TitleScreen()));
                return false;
            }
            if (mc.screen instanceof ConfirmScreen confirm) {
                AbstractWidget yes = widget(confirm, "gui.yes");
                if (yes != null) press(yes);
            }
            return mc.player != null && mc.level != null && age > 60;
        });
    }

    /** Joins the harness's dedicated server. */
    static Step connect(String hostPort) {
        return of("connect " + hostPort, 20 * 60 * 6, true, (mc, age) -> {
            if (age == 0) {
                ServerData data = new ServerData("QA", hostPort, ServerData.Type.OTHER);
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(hostPort), data, false, null);
                return false;
            }
            return mc.player != null && mc.level != null && age > 60;
        });
    }

    /** Closes whatever screen is open (a dialogue window by its own Esc). */
    static Step closeScreens() {
        return of("close screens", 100, false, (mc, age) -> {
            if (mc.screen instanceof DialogueScreen d) d.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
            else if (mc.screen != null) mc.setScreen(null);
            return mc.screen == null && age > 4;
        });
    }

    /** Waits until a dialogue window is open (fails after the timeout). */
    static Step awaitDialogue(String name, int timeout) {
        return of("await dialogue " + name, timeout, false, (mc, age) -> {
            if (mc.screen instanceof DialogueScreen d && d.autotestReady()) {
                Driver.pass(mc, "dialogue opens " + name);
                return true;
            }
            if (mc.screen != null && mc.screen.getClass().getName().contains("snowteb")) mc.setScreen(null);
            if (age >= timeout - 2) Driver.fail(mc, "dialogue opens " + name, "none opened");
            return false;
        });
    }

    static Step dimension(String path, int timeout) {
        return of("in " + path, timeout, false, (mc, age) -> {
            if (mc.level != null && mc.level.dimension().location().getPath().equals(path)) {
                Driver.pass(mc, "in " + path);
                return true;
            }
            if (mc.screen != null && mc.screen.getClass().getName().contains("snowteb")) mc.setScreen(null);
            if (age >= timeout - 2)
                Driver.fail(mc, "in " + path, "in " + (mc.level == null ? "no world" : mc.level.dimension().location().toString()));
            return false;
        });
    }

    /** Shoulder Surfing (in the pack) keeps the camera over the shoulder: views for inspection use first person. */
    static Step camera(boolean firstPerson) {
        return of("camera " + (firstPerson ? "first person" : "back"), 20, false, (mc, age) -> {
            mc.options.setCameraType(firstPerson ? net.minecraft.client.CameraType.FIRST_PERSON : net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            return true;
        });
    }

    /** Waits until the player has been moved more than {@code blocks} from where this step began (a start or join). */
    static Step awaitMove(String name, double blocks, int timeout) {
        final double[] from = {Double.NaN, 0, 0};
        return of("await move " + name, timeout, false, (mc, age) -> {
            if (mc.player == null) return false;
            if (Double.isNaN(from[0])) {
                from[0] = choicePos != null ? choicePos[0] : mc.player.getX();
                from[2] = choicePos != null ? choicePos[1] : mc.player.getZ();
                choicePos = null;
                return false;
            }
            if (Math.hypot(mc.player.getX() - from[0], mc.player.getZ() - from[2]) > blocks) {
                Driver.pass(mc, "moved " + name + " after " + age / 20 + "s");
                return true;
            }
            if (mc.screen instanceof DialogueScreen d && d.autotestReady() && !d.autotestChoosing()) d.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0);
            if (age >= timeout - 2) Driver.fail(mc, "moved " + name, "still within " + (int) blocks + " blocks after " + age / 20 + "s");
            return false;
        });
    }

    /**
     * Waits until at least {@code n} Clan cats are near the player. After a long teleport a camp's cats load a few
     * seconds behind its land (on a busy server more): "qa talk" asked before then found no medicine cat at all.
     */
    static Step awaitCats(int n, int timeout) {
        return of("await cats", timeout, false, (mc, age) -> {
            if (mc.player == null || mc.level == null) return false;
            int near = mc.level.getEntitiesOfClass(net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity.class,
                    mc.player.getBoundingBox().inflate(48)).size();
            if (near >= n) return true;
            if (age >= timeout - 2) Driver.fail(mc, "cats loaded", near + " cats near after " + age / 20 + "s");
            return false;
        });
    }

    static Step log(String text) {
        return of("note", 20, false, (mc, age) -> {
            Driver.LOG.info("[QA] NOTE {} {} {}", Driver.player(mc), Driver.scenario, text);
            return true;
        });
    }
}
