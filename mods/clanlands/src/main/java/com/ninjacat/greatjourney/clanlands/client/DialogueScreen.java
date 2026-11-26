package com.ninjacat.greatjourney.clanlands.client;

import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The dialogue window: a parchment box across the lower part of the screen with the speaker's name plate in their
 * Clan's colour, a live portrait of the speaking cat when it is nearby, text that types itself out, and the scene's
 * choices as buttons once the last line is read. Click, Space or Enter to hurry or turn the page; 1–9 or a click to
 * choose; Esc to step away. The game keeps running behind it.
 */
public final class DialogueScreen extends Screen {
    private static final int BOX_MAX_W = 440, BOX_MAX_H = 120, PORTRAIT = 72, PAD = 10;
    private static final float CHARS_PER_SECOND = 55f;
    private static final long FADE_MS = 180;

    private int sceneId;
    private List<ClanPayloads.DialogueLine> lines;
    private List<ClanPayloads.ChoiceView> choices;
    private int index;
    private long lineStart, openedAt;
    private boolean answered;
    private int hoveredChoice = -1;
    private int byNameLine = -1, byNameScene = -1;
    private long byNameAt;
    private LivingEntity byName;

    public DialogueScreen(ClanPayloads.OpenDialogue scene) {
        super(Component.translatable("screen.clanlands.dialogue"));
        show(scene);
        openedAt = Util.getMillis();
    }

    /** Replaces the scene in place (the server sent the next one while this window was open). */
    public void show(ClanPayloads.OpenDialogue scene) {
        this.sceneId = scene.sceneId();
        this.lines = scene.lines().isEmpty() ? List.of(new ClanPayloads.DialogueLine("", 0xAAAAAA, "...", -1, true)) : scene.lines();
        this.choices = scene.choices();
        this.index = 0;
        this.answered = false;
        Portrait.clear();
        this.lineStart = Util.getMillis();
    }

    public int sceneId() {
        return sceneId;
    }

    /** Whether anyone (not just the narrator) speaks in this scene: then the portrait column is shown throughout. */
    private boolean sceneHasSpeakers() {
        for (ClanPayloads.DialogueLine l : lines) if (!l.narration() && !l.speaker().isEmpty()) return true;
        return false;
    }

    @Override
    public void removed() {
        Portrait.clear();
        super.removed();
    }

    // read by the playtest harness (the test-only greatjourney_qa mod, tools/playtest.py)
    public boolean autotestReady() {
        return fullyShown();
    }

    public boolean autotestChoosing() {
        return showingChoices();
    }

    public List<ClanPayloads.ChoiceView> autotestChoices() {
        return choices;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private ClanPayloads.DialogueLine line() {
        return lines.get(Math.min(index, lines.size() - 1));
    }

    private boolean lastLine() {
        return index >= lines.size() - 1;
    }

    private int revealed() {
        return (int) ((Util.getMillis() - lineStart) * CHARS_PER_SECOND / 1000f);
    }

    private boolean fullyShown() {
        return revealed() >= line().text().length();
    }

    private boolean showingChoices() {
        return lastLine() && fullyShown() && !choices.isEmpty();
    }

    // layout ------------------------------------------------------------------------------------------------------

    private int boxW() {
        return Math.min(BOX_MAX_W, width - 24);
    }

    private int boxX() {
        return (width - boxW()) / 2;
    }

    private int boxY() {
        return height - boxH() - 30;
    }

    private int cachedScene = Integer.MIN_VALUE, cachedWidth = -1, cachedH = 96;

    /**
     * The box fits the scene: tall enough for its longest line (plus the bottom row for the page marker), never
     * shorter than the portrait needs. Worked out once per scene, so the box does not jump between lines.
     */
    private int boxH() {
        if (cachedScene == sceneId && cachedWidth == width) return cachedH;
        int most = 1;
        boolean anyPortrait = false;
        boolean portrait = sceneHasSpeakers();
        anyPortrait = portrait;
        for (ClanPayloads.DialogueLine l : lines) {
            int textW = boxW() - 2 * PAD - 12 - (portrait ? PORTRAIT + PAD - 2 : 0);
            Style style = l.narration() ? Style.EMPTY.withItalic(true) : Style.EMPTY;
            most = Math.max(most, UiStyle.wrap(font, l.text(), Math.max(60, textW), style).size());
        }
        int h = PAD + 8 + most * (font.lineHeight + 2) + PAD + 10;
        cachedH = Math.max(anyPortrait ? PORTRAIT + 18 : 58, Math.min(BOX_MAX_H, h));
        cachedScene = sceneId;
        cachedWidth = width;
        return cachedH;
    }

    private LivingEntity portrait() {
        ClanPayloads.DialogueLine l = line();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return null;
        if (l.entityId() >= 0) {
            Entity e = minecraft.level.getEntity(l.entityId());
            if (e instanceof LivingEntity le && e.isAlive()) return le;
            // the cat was sent by id but has not reached this client yet (spawned the moment the scene opened): look
            // for it by name below, so the bust appears as soon as it does
        }
        // no entity sent (the speaker came into view after the scene left the server, like the StarClan teachers
        // as the dream loads, or leaders on the Great Rock across the hollow): look for a creature of that name
        // nearby, once per line (twice a second until found)
        if (l.narration() || l.speaker().isBlank()) return null;
        long now = Util.getMillis();
        if (byNameLine != index || byNameScene != sceneId || (byName == null && now - byNameAt > 500)) {
            byNameLine = index;
            byNameScene = sceneId;
            byNameAt = now;
            String want = l.speaker().trim();
            byName = minecraft.level.getEntitiesOfClass(LivingEntity.class, minecraft.player.getBoundingBox().inflate(64),
                            e -> e != minecraft.player && e.getCustomName() != null
                                    && e.getCustomName().getString().replace("♂", "").replace("♀", "").trim().equals(want))
                    .stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(minecraft.player))).orElse(null);
        }
        return byName != null && byName.isAlive() ? byName : null;
    }

    private int choiceW() {
        int w = 120;
        for (int i = 0; i < choices.size(); i++) w = Math.max(w, font.width((i + 1) + ".  " + choices.get(i).label()) + 28);
        return Math.min(w, boxW());
    }

    /** From one choice to the next: 24, less when there are so many that the first would be off the top of the screen. */
    private int choiceStep() {
        return Math.max(11, Math.min(24, (boxY() - 14) / Math.max(1, choices.size())));
    }

    private int choiceH() {
        return Math.min(20, choiceStep() - 1);
    }

    private int choiceY(int i) {
        return boxY() - 10 - (choices.size() - i) * choiceStep();
    }

    // drawing -----------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        float a = fade();
        // only a gentle darkening behind the box, so the world stays in view
        g.fillGradient(0, height - boxH() - 90, width, height, UiStyle.argb(0x000000, 0f), UiStyle.argb(0x000000, 0.45f * a));
    }

    private float fade() {
        return Math.min(1f, (Util.getMillis() - openedAt) / (float) FADE_MS);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g, mouseX, mouseY, partial);
        float a = fade();
        ClanPayloads.DialogueLine l = line();
        int bx = boxX(), by = boxY(), bw = boxW(), bh = boxH();
        UiStyle.panel(g, bx, by, bw, bh, UiStyle.PARCHMENT, UiStyle.PARCHMENT_DARK, UiStyle.BARK, a);
        // a leafy rule just inside the frame
        g.fill(bx + 6, by + bh - 6, bx + bw - 6, by + bh - 5, UiStyle.argb(UiStyle.GOLD, 0.5f * a));

        int textX = bx + PAD + 4;
        if (sceneHasSpeakers()) {
            // the portrait column is kept for the whole scene, so the text never jumps between lines
            int px = bx + PAD, py = by + (bh - PORTRAIT) / 2;
            if (!l.narration() && !l.speaker().isEmpty()) {
                Portrait.drawFrame(g, px, py, PORTRAIT, l.colour(), a);
                LivingEntity who = portrait();
                LivingEntity bust = who == null ? null : Portrait.copyOf(who);
                if (bust != null) Portrait.drawBust(g, px, py, PORTRAIT, bust);
                else Portrait.drawMedallion(g, font, px, py, PORTRAIT, l.colour(), l.speaker(), a);
                Portrait.drawFrameGlass(g, px, py, PORTRAIT, a);
            } else {
                // a narrator's line: a quiet frame keeps the place, with a faint paw print so it never reads as a
                // picture that failed to load (the playtest harness's screenshots did)
                UiStyle.rounded(g, px - 2, py - 2, PORTRAIT + 4, PORTRAIT + 4, 5, UiStyle.argb(UiStyle.PARCHMENT_DARK, 0.7f * a));
                int ink = UiStyle.argb(UiStyle.BARK, 0.22f * a), u = PORTRAIT / 12, cx = px + PORTRAIT / 2, cy = py + PORTRAIT / 2 + u;
                UiStyle.rounded(g, cx - 2 * u, cy - u, 4 * u, 3 * u, u, ink);                         // the pad
                UiStyle.rounded(g, cx - 3 * u, cy - 3 * u, 2 * u, 2 * u, u, ink);                     // four toes
                UiStyle.rounded(g, cx - u - u / 2, cy - 4 * u - u / 2, 2 * u, 2 * u, u, ink);
                UiStyle.rounded(g, cx + u / 2, cy - 4 * u - u / 2, 2 * u, 2 * u, u, ink);
                UiStyle.rounded(g, cx + u + u, cy - 3 * u, 2 * u, 2 * u, u, ink);
            }
            textX = px + PORTRAIT + PAD + 2;
        }

        // the name plate, sitting on the box's top edge
        if (!l.narration() && !l.speaker().isEmpty()) {
            int nw = font.width(l.speaker()) + 18, nx = textX - 6, ny = by - 11;
            UiStyle.rounded(g, nx - 1, ny - 1, nw + 2, 17, 4, UiStyle.argb(UiStyle.GOLD, a));
            UiStyle.rounded(g, nx, ny, nw, 15, 4, UiStyle.argb(UiStyle.darker(l.colour(), 0.55f), a));
            g.drawString(font, l.speaker(), nx + 9, ny + 4, UiStyle.argb(0xFFF6E0, a), true);
        }

        // the text, typing itself out
        int textW = bx + bw - PAD - 8 - textX;
        Style style = l.narration() ? Style.EMPTY.withItalic(true) : Style.EMPTY;
        int colour = l.narration() ? UiStyle.INK_SOFT : UiStyle.INK;
        List<String> wrapped = UiStyle.wrap(font, l.text(), textW, style);
        int budget = revealed(), ty = by + PAD + 6;
        for (String w : wrapped) {
            if (budget <= 0) break;
            String part = w.length() <= budget ? w : w.substring(0, budget);
            budget -= w.length() + 1;
            g.drawString(font, Component.literal(part).withStyle(style), textX, ty, UiStyle.argb(colour, a), false);
            ty += font.lineHeight + 2;
            if (ty > by + bh - PAD - font.lineHeight - 8) break;
        }

        // the page marker: a blinking arrow once a line is fully shown and there is more to read
        if (fullyShown() && !showingChoices()) {
            boolean on = (Util.getMillis() / 450) % 2 == 0;
            String mark = lastLine() ? "■" : "▼";
            int markY = by + bh - PAD - 8;
            if (on) g.drawString(font, mark, bx + bw - PAD - 8, markY, UiStyle.argb(UiStyle.BARK, a), false);
            if (lines.size() > 1) {                            // the page count sits on the bottom row, by the marker
                String page = (index + 1) + "/" + lines.size();
                g.drawString(font, page, bx + bw - PAD - 14 - font.width(page), markY, UiStyle.argb(UiStyle.INK_SOFT, 0.75f * a), false);
            }
        }

        // the choices, stacked above the box
        hoveredChoice = -1;
        if (showingChoices()) {
            int cw = choiceW(), cx = bx + bw - cw - 6;
            for (int i = 0; i < choices.size(); i++) {
                int cy = choiceY(i);
                int ch = choiceH();
                boolean hover = UiStyle.over(mouseX, mouseY, cx, cy, cw, ch);
                if (hover) hoveredChoice = i;
                int body = hover ? 0xFFF3D0 : UiStyle.PARCHMENT;
                UiStyle.panel(g, cx, cy, cw, ch, body, UiStyle.PARCHMENT_DARK, hover ? UiStyle.GOLD : UiStyle.BARK, a);
                int c = choices.get(i).colour();
                g.fill(cx + 4, cy + 2, cx + 7, cy + ch - 2, UiStyle.argb(UiStyle.darker(c, 0.8f), a));
                g.drawString(font, (i + 1) + ".  " + choices.get(i).label(), cx + 12, cy + (ch - 7) / 2,
                        UiStyle.argb(hover ? UiStyle.BARK_DARK : UiStyle.INK, a), false);
            }
        }
        super.render(g, mouseX, mouseY, partial);
    }

    // input -------------------------------------------------------------------------------------------------------

    /** Hurry the text, else turn the page, else finish a scene that asks nothing. */
    private void advance() {
        if (!fullyShown()) {
            lineStart = Util.getMillis() - (long) (line().text().length() * 1000 / CHARS_PER_SECOND) - 1;
            return;
        }
        if (!lastLine()) {
            index++;
            lineStart = Util.getMillis();
            return;
        }
        if (choices.isEmpty()) finish();
    }

    private void choose(int i) {
        if (!showingChoices() || i < 0 || i >= choices.size() || answered) return;
        answered = true;
        PacketDistributor.sendToServer(new ClanPayloads.DialogueChoice(sceneId, i));
        closeQuietly();
    }

    private void finish() {
        if (answered) return;
        answered = true;
        PacketDistributor.sendToServer(new ClanPayloads.DialogueClosed(sceneId));
        closeQuietly();
    }

    /** Closes without telling the server again (it has already been told). */
    void closeQuietly() {
        answered = true;
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        if (!answered) {
            answered = true;
            PacketDistributor.sendToServer(new ClanPayloads.DialogueClosed(sceneId));
        }
        super.onClose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (showingChoices()) {
            if (hoveredChoice >= 0) {
                choose(hoveredChoice);
                return true;
            }
            return true;                                   // a choice is needed; clicks elsewhere do nothing
        }
        advance();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_E) {
            if (!showingChoices()) advance();
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            choose(key - GLFW.GLFW_KEY_1);
            return true;
        }
        if (key >= GLFW.GLFW_KEY_KP_1 && key <= GLFW.GLFW_KEY_KP_9) {
            choose(key - GLFW.GLFW_KEY_KP_1);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
