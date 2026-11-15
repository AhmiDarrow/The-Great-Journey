package com.ninjacat.greatjourney.clanlands.client;

import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapData;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The Clan map, big: the map as large as the window allows, every place named on it, and beside it the region
 * buttons, where the goal lies, and the key. Opened by clicking the map in the journal; Esc goes back there.
 */
public final class ClanMapScreen extends Screen {
    private static final int PANEL = 196;
    private final Screen back;

    public ClanMapScreen(Screen back) {
        super(Component.literal("Clan map"));
        this.back = back;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int side() {
        return Math.max(64, Math.min(height - 28, width - 240));
    }

    private int mapX() {
        return Math.max(10, (width - side() - 214) / 2);
    }

    private int mapY() {
        return (height - side()) / 2;
    }

    private int panelX() {
        return mapX() + side() + 14;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        MapPainter.keepFresh();
        MapData d = MapPainter.data();
        int px = panelX(), py = mapY(), pw = PANEL;
        UiStyle.rounded(g, px - 6, py - 3, pw + 12, side() + 6, 3, UiStyle.argb(UiStyle.PARCHMENT, 1f));
        if (d == null) {
            g.drawString(font, "Drawing the map...", px, py + 4, UiStyle.argb(UiStyle.INK, 1f), false);
            return;
        }
        MapPainter.draw(g, font, d, mapX(), mapY(), side(), true);
        int y = py + 3;
        y = regionButtons(g, d, px, y, pw, mouseX, mouseY) + 4;
        String goal = MapPainter.goalLine(d);
        if (!goal.isEmpty()) {
            for (String line : UiStyle.wrap(font, goal, pw, net.minecraft.network.chat.Style.EMPTY)) {
                g.drawString(font, line, px, y, UiStyle.argb(0x8C2F2F, 1f), false);
                y += font.lineHeight + 1;
            }
            y += 4;
        }
        MapPainter.legend(g, font, d, px, y, pw);
        g.drawString(font, "Esc: back to the journal", px, py + side() - font.lineHeight - 2, UiStyle.argb(UiStyle.INK_SOFT, 1f), false);
        List<Component> tip = MapPainter.hover(d, mapX(), mapY(), side(), mouseX, mouseY);
        if (tip != null) g.renderComponentTooltip(font, tip, mouseX, mouseY);
    }

    /** The three regions as buttons; returns the y below them. */
    static int regionButtons(GuiGraphics g, MapData d, int x, int y, int w, double mx, double my) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        for (int r = 0; r < d.regions().size(); r++) {
            boolean on = r == d.region(), hover = UiStyle.over(mx, my, x, y, w, 12);
            UiStyle.rounded(g, x, y, w, 12, 2, UiStyle.argb(on ? 0x8C2F2F : hover ? 0xD9C190 : 0xE8D6AE, 1f));
            g.drawString(font, d.regions().get(r), x + 5, y + 2, UiStyle.argb(on ? 0xF6E8C8 : UiStyle.INK, 1f), false);
            y += 14;
        }
        return y;
    }

    /** The region button under the mouse, or -1. */
    static int regionAt(MapData d, int x, int y, int w, double mx, double my) {
        for (int r = 0; r < d.regions().size(); r++) {
            if (UiStyle.over(mx, my, x, y + 14 * r, w, 12)) return r;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        MapData d = MapPainter.data();
        if (d != null) {
            int r = regionAt(d, panelX(), mapY() + 3, PANEL, mx, my);
            if (r >= 0) {
                MapPainter.show(r);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.setScreen(back);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
