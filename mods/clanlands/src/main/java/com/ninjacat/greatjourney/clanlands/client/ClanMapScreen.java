package com.ninjacat.greatjourney.clanlands.client;

import com.ninjacat.greatjourney.clanlands.life.rpg.MapView;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapData;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The Clan map, big: the map as large as the window allows, every place named on it, and beside it the region
 * buttons, where the followed quest lies, and the key. The wheel zooms in and out and dragging moves about; clicking a
 * quest's mark follows that quest. Opened by clicking the map in the journal (Esc goes back there) or straight from the
 * world by its own key (Esc, or the key again, closes it).
 */
public final class ClanMapScreen extends Screen {
    private static final int PANEL = 196;
    /** The journal to go back to, or null when opened straight from the world. */
    private final Screen back;
    private MapView view = MapView.FULL;
    private int viewedRegion = -1;
    private boolean dragging;

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
        if (d.region() != viewedRegion) {                  // another region: start from the whole of it
            viewedRegion = d.region();
            view = MapView.FULL;
        }
        MapPainter.draw(g, font, d, mapX(), mapY(), side(), true, view);
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
        String[] hints = {"Wheel: zoom. Drag: move.", "Click a mark to follow it", back == null ? "Esc: close" : "Esc: back to the journal"};
        for (int i = 0; i < hints.length; i++) {
            g.drawString(font, hints[i], px, py + side() - (hints.length - i) * (font.lineHeight + 1) - 1, UiStyle.argb(UiStyle.INK_SOFT, 1f), false);
        }
        List<Component> tip = MapPainter.hover(d, mapX(), mapY(), side(), view, mouseX, mouseY);
        if (tip != null) g.renderComponentTooltip(font, tip, mouseX, mouseY);
    }

    /** Asks the server to follow a quest; it answers with a fresh journal and, on the next ask, a fresh map. */
    static void follow(String quest) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) player.connection.sendCommand("greatjourney quest follow " + quest);
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
            var mark = MapPainter.markAt(d, mapX(), mapY(), side(), view, mx, my);
            if (mark != null) {
                follow(mark.quest());
                MapPainter.show(d.region());
                return true;
            }
            if (button == 0 && onMap(mx, my)) {
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean onMap(double mx, double my) {
        return mx >= mapX() && my >= mapY() && mx < mapX() + side() && my < mapY() + side();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!onMap(mx, my) || sy == 0) return super.mouseScrolled(mx, my, sx, sy);
        view = view.zoomAt(sy > 0 ? 1.25 : 0.8, (mx - mapX()) / side(), (my - mapY()) / side());
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging) return super.mouseDragged(mx, my, button, dx, dy);
        view = view.drag(dx / side(), dy / side());
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (back == null && ClanClient.MAP_KEY.matches(key, scan)) {
            minecraft.setScreen(null);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.setScreen(back);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
