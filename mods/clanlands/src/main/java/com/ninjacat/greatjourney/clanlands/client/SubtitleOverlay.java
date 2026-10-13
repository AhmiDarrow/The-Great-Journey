package com.ninjacat.greatjourney.clanlands.client;

import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Ambient lines (Clan news, passing remarks) in a small parchment box above the hotbar: one at a time, fading in
 * and out, each staying long enough to read. Nothing to click; play carries on.
 */
final class SubtitleOverlay implements LayeredDraw.Layer {
    private static final int MAX_W = 300;
    private static final long FADE_MS = 250;
    private static final Deque<ClanPayloads.DialogueLine> QUEUE = new ArrayDeque<>();
    private static ClanPayloads.DialogueLine current;
    private static long shownAt, duration;

    static void add(List<ClanPayloads.DialogueLine> lines) {
        QUEUE.addAll(lines);
        while (QUEUE.size() > 12) QUEUE.poll();                 // never let news pile up endlessly
    }

    static void clear() {
        QUEUE.clear();
        current = null;
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        long now = Util.getMillis();
        if (current == null || now - shownAt > duration) {
            current = QUEUE.poll();
            if (current == null) return;
            shownAt = now;
            duration = Math.max(3500, 1500 + current.text().length() * 55L);
        }
        long t = now - shownAt;
        float a = Math.min(1f, Math.min(t, duration - t) / (float) FADE_MS);
        if (a <= 0) return;

        Font font = mc.font;
        Style style = current.narration() ? Style.EMPTY.withItalic(true) : Style.EMPTY;
        List<String> wrapped = UiStyle.wrap(font, current.text(), MAX_W - 20, style);
        if (wrapped.size() > 3) wrapped = wrapped.subList(0, 3);
        int textW = 0;
        for (String w : wrapped) textW = Math.max(textW, font.width(w));
        boolean named = !current.narration() && !current.speaker().isEmpty();
        if (named) textW = Math.max(textW, font.width(current.speaker()));
        int w = textW + 20, h = 10 + (named ? font.lineHeight + 2 : 0) + wrapped.size() * (font.lineHeight + 1) + 4;
        int sw = g.guiWidth(), sh = g.guiHeight();
        int x = (sw - w) / 2, y = sh - 62 - h;
        UiStyle.panel(g, x, y, w, h, UiStyle.PARCHMENT, UiStyle.PARCHMENT_DARK, UiStyle.BARK, a * 0.92f);
        int ty = y + 6;
        if (named) {
            g.drawString(font, current.speaker(), x + 10, ty, UiStyle.argb(UiStyle.darker(current.colour(), 0.75f), a), false);
            ty += font.lineHeight + 2;
        }
        for (String line : wrapped) {
            g.drawString(font, Component.literal(line).withStyle(style), x + 10, ty,
                    UiStyle.argb(current.narration() ? UiStyle.INK_SOFT : UiStyle.INK, a), false);
            ty += font.lineHeight + 1;
        }
    }
}
