package com.ninjacat.greatjourney.clanlands.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

/**
 * Drawing helpers for The Great Journey's windows: soft-shadowed panels with stepped (pixel-rounded) corners and a
 * thin border, in parchment, bark and leather tones. Everything is drawn with fills, so it stays crisp at any GUI
 * scale and needs no textures.
 */
final class UiStyle {
    private UiStyle() {}

    static final int PARCHMENT = 0xF2E4C6;
    static final int PARCHMENT_DARK = 0xE2CFA6;
    static final int BARK = 0x5A3E26;
    static final int BARK_DARK = 0x3B2816;
    static final int GOLD = 0xC9A45A;
    static final int INK = 0x3B2A1A;
    static final int INK_SOFT = 0x6E5A44;
    static final int LEAF = 0x5E7F3A;

    /** ARGB from an RGB colour and an alpha in 0..1. */
    static int argb(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    static int darker(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }

    /** A filled rectangle with its corners cut in steps of one pixel, so it reads as rounded. */
    static void rounded(GuiGraphics g, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        radius = Math.min(radius, Math.min(w, h) / 2);
        g.fill(x + radius, y, x + w - radius, y + h, argb);
        for (int i = 0; i < radius; i++) {
            int inset = radius - (int) Math.round(Math.sqrt(radius * radius - (radius - i - 0.5) * (radius - i - 0.5)));
            g.fill(x + i, y + inset, x + i + 1, y + h - inset, argb);
            g.fill(x + w - i - 1, y + inset, x + w - i, y + h - inset, argb);
        }
    }

    /**
     * A panel: a soft drop shadow, a border ring and a body with a gentle top-to-bottom shading.
     *
     * @param alpha 0..1, for fading the whole panel in and out
     */
    static void panel(GuiGraphics g, int x, int y, int w, int h, int body, int bodyBottom, int border, float alpha) {
        for (int s = 4; s >= 1; s--) {
            rounded(g, x - s + 2, y - s + 3, w + 2 * s - 2, h + 2 * s - 2, 6 + s, argb(0x000000, alpha * 0.07f));
        }
        rounded(g, x - 1, y - 1, w + 2, h + 2, 6, argb(border, alpha));
        rounded(g, x, y, w, h, 5, argb(body, alpha));
        // shading: a subtly darker lower half
        int half = h / 2;
        g.fillGradient(x + 3, y + half, x + w - 3, y + h - 3, argb(body, 0f), argb(bodyBottom, alpha * 0.85f));
        // a thin inner highlight along the top edge
        g.fill(x + 5, y + 1, x + w - 5, y + 2, argb(0xFFFFFF, alpha * 0.25f));
    }

    /** Wraps plain text to lines no wider than {@code width}. */
    static List<String> wrap(Font font, String text, int width, Style style) {
        List<String> out = new ArrayList<>();
        for (FormattedText line : font.getSplitter().splitLines(text, width, style)) out.add(line.getString());
        return out;
    }

    static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
