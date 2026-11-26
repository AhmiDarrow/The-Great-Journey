package com.ninjacat.greatjourney.clanlands.client;

import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;

/**
 * The Clan Journal: a worn leather book open on a two-page spread, with ribbon bookmarks for Tasks, Duties, Clan,
 * Places, StarClan and the Map. The left page lists, the right page tells: pick a task on the left to read it and its
 * checklist on the right. Everything shown comes from the server; the journal only draws it.
 */
public final class JournalScreen extends Screen {
    private enum Tab {
        TASKS("Tasks"), DUTIES("Duties"), CLAN("Clan"), PLACES("Places"), STARCLAN("StarClan"), MAP("Map");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static Tab lastTab = Tab.TASKS;

    private JournalView view;
    private Tab tab = lastTab;
    private int selected, leftScroll, rightScroll;
    private int bookX, bookY, bookW, bookH;

    public JournalScreen(JournalView view) {
        super(Component.translatable("screen.clanlands.journal"));
        this.view = view;
    }

    /** New contents from the server while open: keep the tab and selection where they make sense. */
    public void update(JournalView v) {
        this.view = v;
        selected = Math.min(selected, Math.max(0, listForTab().size() - 1));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        bookW = Math.min(380, width - 24);
        bookH = Math.min(232, height - 40);
        bookX = (width - bookW) / 2;
        bookY = (height - bookH) / 2 + 8;
        // opened on the Map page: show wherever the cat is now (not when coming back from the big map)
        if (tab == Tab.MAP && !mapAsked) MapPainter.show(-1);
        mapAsked = true;
    }

    private boolean mapAsked;

    // what each tab lists -----------------------------------------------------------------------------------------

    private static boolean started(JournalView.Quest q) {
        for (JournalView.Objective o : q.objectives()) if (o.progress() > 0) return true;
        return false;
    }

    /** Tasks: the story path and duties under way, then finished ones. Duties: optional work not yet begun. */
    private List<JournalView.Quest> listForTab() {
        List<JournalView.Quest> out = new ArrayList<>();
        if (tab == Tab.TASKS) {
            for (JournalView.Quest q : view.quests()) if (!q.done() && (q.story() || started(q))) out.add(q);
            for (JournalView.Quest q : view.quests()) if (q.done()) out.add(q);
        } else if (tab == Tab.DUTIES) {
            for (JournalView.Quest q : view.quests()) if (!q.story() && !q.done() && !started(q)) out.add(q);
        }
        return out;
    }

    // drawing -----------------------------------------------------------------------------------------------------

    private int pageW() {
        return (bookW - 24) / 2;
    }

    private int leftX() {
        return bookX + 10;
    }

    private int rightX() {
        return bookX + bookW / 2 + 2;
    }

    private int pageY() {
        return bookY + 10;
    }

    private int pageH() {
        return bookH - 20;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // the vanilla render draws the blurred background (and any widgets) first; the book goes on top of it
        super.render(g, mouseX, mouseY, partial);
        drawTabs(g, mouseX, mouseY);
        // the cover, then the two pages and the spine between them
        UiStyle.panel(g, bookX, bookY, bookW, bookH, 0x5C3A22, 0x3E2614, 0x2A190C, 1f);
        int lx = leftX(), rx = rightX(), py = pageY(), pw = pageW(), ph = pageH();
        UiStyle.rounded(g, lx, py, pw + 2, ph, 3, UiStyle.argb(UiStyle.PARCHMENT, 1f));
        UiStyle.rounded(g, rx, py, pw + 2, ph, 3, UiStyle.argb(UiStyle.PARCHMENT, 1f));
        g.fillGradient(lx + pw - 10, py, lx + pw + 2, py + ph, UiStyle.argb(0x000000, 0f), UiStyle.argb(0x6B4A2A, 0.35f));
        g.fillGradient(rx, py, rx + 10, py + ph, UiStyle.argb(0x6B4A2A, 0.35f), UiStyle.argb(0x000000, 0f));
        g.fill(bookX + bookW / 2 - 1, py - 2, bookX + bookW / 2 + 1, py + ph + 2, UiStyle.argb(0x2A190C, 1f));

        switch (tab) {
            case TASKS, DUTIES -> drawQuests(g, mouseX, mouseY);
            case CLAN -> drawClan(g);
            case PLACES -> drawPlaces(g);
            case STARCLAN -> drawStarClan(g);
            case MAP -> drawMap(g, mouseX, mouseY);
        }
    }

    /** The map's square on the right page. */
    private int mapSide() {
        return Math.max(40, Math.min(pageW() - 10, pageH() - 26));
    }

    private int mapX() {
        return rightX() + (pageW() + 2 - mapSide()) / 2;
    }

    private int mapY() {
        return pageY() + 6;
    }

    /** Left page: the regions, where the goal is, and the key. Right page: the map. */
    private void drawMap(GuiGraphics g, int mouseX, int mouseY) {
        MapPainter.keepFresh();
        var d = MapPainter.data();
        int x = leftX() + 8, w = pageW() - 14, y = pageY() + 7;
        if (d == null) {
            drawWrapped(g, "Drawing the map...", x, y, w, UiStyle.INK_SOFT, true);
            return;
        }
        y = ClanMapScreen.regionButtons(g, d, x, y, w, mouseX, mouseY) + 3;
        String goal = MapPainter.goalLine(d);
        if (!goal.isEmpty()) y = drawWrapped(g, goal, x, y, w, 0x8C2F2F, false) + 3;
        MapPainter.legend(g, font, d, x, y, w);
        MapPainter.draw(g, font, d, mapX(), mapY(), mapSide(), false);
        String hint = "Click the map to see it big";
        g.drawString(font, hint, rightX() + (pageW() + 2 - font.width(hint)) / 2, mapY() + mapSide() + 6,
                UiStyle.argb(UiStyle.INK_SOFT, 1f), false);
        java.util.List<Component> tip = MapPainter.hover(d, mapX(), mapY(), mapSide(), mouseX, mouseY);
        if (tip != null) g.renderComponentTooltip(font, tip, mouseX, mouseY);
    }

    private void drawTabs(GuiGraphics g, int mouseX, int mouseY) {
        int x = bookX + 14;
        for (Tab t : Tab.values()) {
            int w = font.width(t.label) + 16, h = t == tab ? 18 : 15, y = bookY - h + 4;
            boolean hover = UiStyle.over(mouseX, mouseY, x, y, w, h);
            int colour = t == tab ? 0x8C2F2F : hover ? 0x7A4A2E : 0x5E4030;
            UiStyle.rounded(g, x, y, w, h + 4, 3, UiStyle.argb(colour, 1f));
            // the label sits in the part of the tab above the cover, so the book never cuts it off
            g.drawString(font, t.label, x + 8, y + (h - 12) / 2 + 1, UiStyle.argb(t == tab ? 0xF6E8C8 : 0xE2CFA6, 1f), false);
            x += w + 4;
        }
    }

    private Tab tabAt(double mx, double my) {
        int x = bookX + 14;
        for (Tab t : Tab.values()) {
            int w = font.width(t.label) + 16, h = 18, y = bookY - h + 4;
            if (UiStyle.over(mx, my, x, y, w, h)) return t;
            x += w + 4;
        }
        return null;
    }

    /** The cat's name, Clan and rank across the top of the left page. Returns the y below it. */
    private int header(GuiGraphics g) {
        int x = leftX() + 8, y = pageY() + 7;
        g.drawString(font, view.catName(), x, y, UiStyle.argb(UiStyle.BARK_DARK, 1f), false);
        y += font.lineHeight + 1;
        String sub = view.clan().isEmpty() ? view.rank() : view.rank() + " of " + view.clan();
        g.drawString(font, sub, x, y, UiStyle.argb(UiStyle.darker(view.clanColour(), 0.7f), 1f), false);
        y += font.lineHeight + 3;
        g.fill(x, y, leftX() + pageW() - 8, y + 1, UiStyle.argb(UiStyle.GOLD, 0.7f));
        return y + 5;
    }

    private void drawQuests(GuiGraphics g, int mouseX, int mouseY) {
        int top = header(g);
        List<JournalView.Quest> list = listForTab();
        int x = leftX() + 8, w = pageW() - 14, bottom = pageY() + pageH() - 6;
        if (list.isEmpty()) {
            String empty = tab == Tab.TASKS ? "Nothing to do just now. Ask a Clanmate if they need help." : "No duties on offer. Talk to the cats of your Clan.";
            drawWrapped(g, empty, x, top, w, UiStyle.INK_SOFT, true);
        } else {
            selected = Math.min(selected, list.size() - 1);
            g.enableScissor(leftX(), top, leftX() + pageW(), bottom);
            int y = top - leftScroll;
            for (int i = 0; i < list.size(); i++) {
                JournalView.Quest q = list.get(i);
                boolean sel = i == selected, hover = UiStyle.over(mouseX, mouseY, x - 2, y - 2, w, 22) && mouseY >= top && mouseY < bottom;
                if (sel) UiStyle.rounded(g, x - 3, y - 2, w + 2, 21, 3, UiStyle.argb(0xD9C190, 1f));
                else if (hover) UiStyle.rounded(g, x - 3, y - 2, w + 2, 21, 3, UiStyle.argb(0xE8D6AE, 1f));
                String mark = q.done() ? "✔" : q.story() ? "★" : "•";
                int ink = q.done() ? UiStyle.INK_SOFT : UiStyle.INK;
                g.drawString(font, mark, x, y + 1, UiStyle.argb(q.story() ? 0x8C2F2F : UiStyle.LEAF, 1f), false);
                g.drawString(font, fit(q.title(), w - 14), x + 10, y + 1, UiStyle.argb(ink, 1f), false);
                int done = 0;
                for (JournalView.Objective o : q.objectives()) if (o.complete()) done++;
                String prog = q.done() ? "done" : !q.ready().isEmpty() ? "✔ " + q.ready() : done + "/" + q.objectives().size() + "  ·  " + q.giver();
                g.drawString(font, fit(prog, w - 14), x + 10, y + 11,
                        UiStyle.argb(!q.done() && !q.ready().isEmpty() ? UiStyle.LEAF : UiStyle.INK_SOFT, 1f), false);
                y += 24;
            }
            g.disableScissor();
            JournalView.Quest q = list.get(selected);
            drawQuestDetail(g, q, mouseX, mouseY);
        }
    }

    /** The text cut to {@code w} pixels, ending in an ellipsis when something had to go. */
    private String fit(String text, int w) {
        if (font.width(text) <= w) return text;
        return font.plainSubstrByWidth(text, w - font.width("…")).stripTrailing() + "…";
    }

    /** "Show on map" sits at the foot of a task's page, when the task has a place there. */
    private int showY() {
        return pageY() + pageH() - 18;
    }

    private boolean overShow(JournalView.Quest q, double mx, double my) {
        return q.onMap() && UiStyle.over(mx, my, rightX() + 10, showY(), pageW() - 18, 13);
    }

    private void drawQuestDetail(GuiGraphics g, JournalView.Quest q, int mouseX, int mouseY) {
        int x = rightX() + 10, w = pageW() - 16, top = pageY() + 7, bottom = pageY() + pageH() - 6 - (q.onMap() ? 16 : 0);
        g.enableScissor(rightX(), pageY() + 2, rightX() + pageW(), bottom);
        int y = top - rightScroll;
        y = drawWrapped(g, q.title(), x, y, w, UiStyle.BARK_DARK, false) + 1;
        g.drawString(font, (q.story() ? "Story" : "Duty") + "  ·  from " + q.giver(), x, y, UiStyle.argb(UiStyle.INK_SOFT, 1f), false);
        y += font.lineHeight + 3;
        g.fill(x, y, x + w, y + 1, UiStyle.argb(UiStyle.GOLD, 0.7f));
        y += 5;
        y = drawWrapped(g, q.description(), x, y, w, UiStyle.INK, false) + 6;
        for (JournalView.Objective o : q.objectives()) {
            boolean ok = o.complete();
            UiStyle.rounded(g, x, y, 9, 9, 2, UiStyle.argb(ok ? UiStyle.LEAF : 0xCDB98C, 1f));
            if (ok) g.drawString(font, "✔", x + 1, y + 1, UiStyle.argb(0xFFFFFF, 1f), false);
            String text = o.text() + (o.goal() > 1 ? "  (" + Math.min(o.progress(), o.goal()) + "/" + o.goal() + ")" : "");
            y = drawWrapped(g, text, x + 14, y + 1, w - 14, ok ? UiStyle.INK_SOFT : UiStyle.INK, false) + 4;
        }
        if (!q.ready().isEmpty()) y = drawWrapped(g, "✔ " + q.ready(), x, y + 2, w, UiStyle.LEAF, false) + 2;
        if (!q.reward().isEmpty()) drawWrapped(g, "Reward: " + q.reward(), x, y + 2, w, UiStyle.BARK_DARK, true);
        g.disableScissor();
        if (q.onMap()) {
            boolean hover = overShow(q, mouseX, mouseY);
            UiStyle.rounded(g, x, showY(), pageW() - 18, 13, 3, UiStyle.argb(q.followed() ? 0x8C2F2F : hover ? 0xD9C190 : 0xE8D6AE, 1f));
            String label = q.followed() ? "★ Followed: see the map" : "Show on map";
            g.drawString(font, label, x + (pageW() - 18 - font.width(label)) / 2, showY() + 3,
                    UiStyle.argb(q.followed() ? 0xF6E8C8 : UiStyle.INK, 1f), false);
        }
    }

    private void drawClan(GuiGraphics g) {
        int top = header(g);
        int x = leftX() + 8, w = pageW() - 14;
        int y = top;
        if (view.clan().isEmpty()) {
            y = drawWrapped(g, "You belong to no Clan. Visit a camp, and its cats may ask you to join them.", x, y, w, UiStyle.INK, true) + 6;
        } else {
            g.drawString(font, "Standing in the Clan: " + view.standing(), x, y, UiStyle.argb(UiStyle.INK, 1f), false);
            y += font.lineHeight + 3;
            for (JournalView.Rung r : view.ladder()) {
                boolean got = view.standing() >= r.standing();
                String line = (got ? "✔ " : "• ") + r.standing() + "  " + r.what();
                g.drawString(font, fit(line, w), x, y, UiStyle.argb(got ? UiStyle.LEAF : UiStyle.INK_SOFT, 1f), false);
                y += font.lineHeight + 1;
            }
            y += 4;
        }
        g.drawString(font, "What lies ahead", x, y, UiStyle.argb(UiStyle.BARK_DARK, 1f), false);
        y += font.lineHeight + 2;
        int bottom = pageY() + pageH() - 6;
        int tall = UiStyle.wrap(font, view.nextGoal(), w, Style.EMPTY.withItalic(true)).size() * (font.lineHeight + 1);
        leftScroll = Math.min(leftScroll, Math.max(0, tall - (bottom - y)));
        g.enableScissor(leftX(), y, leftX() + pageW(), bottom);
        drawWrapped(g, view.nextGoal(), x, y - leftScroll, w, UiStyle.INK, true);
        g.disableScissor();
        drawRightList(g, view.clan().isEmpty() ? "" : "Clanmates", view.clanmates(), "No Clanmates to list.");
    }

    private void drawPlaces(GuiGraphics g) {
        int top = header(g);
        int x = leftX() + 8, w = pageW() - 14, bottom = pageY() + pageH() - 6;
        g.drawString(font, "Places you have found", x, top, UiStyle.argb(UiStyle.BARK_DARK, 1f), false);
        g.enableScissor(leftX(), top + font.lineHeight + 2, leftX() + pageW(), bottom);
        int y = top + font.lineHeight + 4 - leftScroll;
        int found = 0;
        for (JournalView.Place p : view.places()) {
            if (!p.found()) continue;
            found++;
            g.drawString(font, "✔ " + font.plainSubstrByWidth(p.name(), w - 10), x, y, UiStyle.argb(UiStyle.INK, 1f), false);
            y += font.lineHeight + 3;
        }
        if (found == 0) drawWrapped(g, "None yet. The books' places are out there waiting.", x, y, w, UiStyle.INK_SOFT, true);
        g.disableScissor();
        List<String> hints = new ArrayList<>();
        for (JournalView.Place p : view.places()) if (!p.found()) hints.add("? " + p.name() + (p.hint().isEmpty() ? "" : " — " + p.hint()));
        drawRightList(g, "Still to find", hints, "You have found every place.");
    }

    private void drawStarClan(GuiGraphics g) {
        int top = header(g);
        int x = leftX() + 8, w = pageW() - 14;
        int y = drawWrapped(g, "Silverpelt shines with the spirits of warriors who have gone before. "
                + "Their names are kept here so they are never forgotten.", x, top, w, UiStyle.INK, true);
        // a few stars, for the night sky
        for (int i = 0; i < 9; i++) {
            int sx = x + 6 + (i * 37) % (w - 12), sy = y + 12 + (i * 23) % 40;
            g.fill(sx, sy, sx + 1, sy + 1, UiStyle.argb(0x8A7A5A, 1f));
        }
        drawRightList(g, "They walk with StarClan", view.starclan(), "No cat of this world has gone to StarClan yet.");
    }

    /** A titled, scrollable list on the right page. */
    private void drawRightList(GuiGraphics g, String title, List<String> items, String empty) {
        int x = rightX() + 10, w = pageW() - 16, top = pageY() + 7, bottom = pageY() + pageH() - 6;
        int y = top;
        if (!title.isEmpty()) {
            g.drawString(font, title, x, y, UiStyle.argb(UiStyle.BARK_DARK, 1f), false);
            y += font.lineHeight + 2;
            g.fill(x, y, x + w, y + 1, UiStyle.argb(UiStyle.GOLD, 0.7f));
            y += 4;
        }
        if (items.isEmpty()) {
            drawWrapped(g, empty, x, y, w, UiStyle.INK_SOFT, true);
            return;
        }
        // a long list (a big Clan, many places) scrolls with the wheel: keep the scroll inside the list, and show
        // arrows where there is more, so nothing looks cut off (the playtest harness found clipped Clanmate lists)
        int listTop = y, room = bottom - listTop, total = 0;
        for (String s : items) total += UiStyle.wrap(font, s, w, Style.EMPTY).size() * (font.lineHeight + 1) + 2;
        int max = Math.max(0, total - room);
        rightScroll = Math.min(rightScroll, max);
        g.enableScissor(rightX(), y, rightX() + pageW(), bottom);
        y -= rightScroll;
        for (String s : items) y = drawWrapped(g, s, x, y, w, UiStyle.INK, false) + 2;
        g.disableScissor();
        int ax = rightX() + pageW() - 12;
        if (rightScroll > 0) g.drawString(font, "▲", ax, listTop, UiStyle.argb(UiStyle.BARK, 0.8f), false);
        if (rightScroll < max) {
            g.fillGradient(rightX() + 2, bottom - 12, rightX() + pageW() - 2, bottom, UiStyle.argb(UiStyle.PARCHMENT, 0f), UiStyle.argb(UiStyle.PARCHMENT, 1f));
            g.drawString(font, "▼", ax, bottom - font.lineHeight, UiStyle.argb(UiStyle.BARK, 0.8f), false);
        }
    }

    /** Wrapped text; returns the y below it. */
    private int drawWrapped(GuiGraphics g, String text, int x, int y, int w, int rgb, boolean italic) {
        Style style = italic ? Style.EMPTY.withItalic(true) : Style.EMPTY;
        for (String line : UiStyle.wrap(font, text, w, style)) {
            g.drawString(font, Component.literal(line).withStyle(style), x, y, UiStyle.argb(rgb, 1f), false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    // input -------------------------------------------------------------------------------------------------------

    private void setTab(Tab t) {
        if (t == null || t == tab) return;
        tab = t;
        lastTab = t;
        if (t == Tab.MAP) MapPainter.show(-1);              // the map of wherever the cat is now
        selected = 0;
        leftScroll = rightScroll = 0;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Tab t = tabAt(mx, my);
        if (t != null) {
            setTab(t);
            return true;
        }
        if (tab == Tab.MAP && MapPainter.data() != null) {
            var d = MapPainter.data();
            int r = ClanMapScreen.regionAt(d, leftX() + 8, pageY() + 7, pageW() - 14, mx, my);
            if (r >= 0) {
                MapPainter.show(r);
                return true;
            }
            if (UiStyle.over(mx, my, mapX(), mapY(), mapSide(), mapSide())) {
                minecraft.setScreen(new ClanMapScreen(this));
                return true;
            }
        }
        if (tab == Tab.TASKS || tab == Tab.DUTIES) {
            List<JournalView.Quest> list = listForTab();
            if (selected < list.size() && overShow(list.get(selected), mx, my)) {
                ClanMapScreen.follow(list.get(selected).id());
                setTab(Tab.MAP);
                return true;
            }
        }
        if ((tab == Tab.TASKS || tab == Tab.DUTIES) && mx >= leftX() && mx < leftX() + pageW()) {
            int top = pageY() + 7 + 2 * (font.lineHeight + 1) + 7;
            int i = (int) Math.floor((my - top + leftScroll) / 24.0);
            if (my >= top && i >= 0 && i < listForTab().size()) {
                selected = i;
                rightScroll = 0;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int step = (int) (-sy * 12);
        if (mx < bookX + bookW / 2.0) leftScroll = Math.max(0, leftScroll + step);
        else rightScroll = Math.max(0, rightScroll + step);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_6) {
            setTab(Tab.values()[key - GLFW.GLFW_KEY_1]);
            return true;
        }
        if (ClanClient.JOURNAL_KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        if (tab == Tab.TASKS || tab == Tab.DUTIES) {
            int n = listForTab().size();
            if (key == GLFW.GLFW_KEY_DOWN && n > 0) {
                selected = Math.min(n - 1, selected + 1);
                rightScroll = 0;
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP && n > 0) {
                selected = Math.max(0, selected - 1);
                rightScroll = 0;
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }
}
