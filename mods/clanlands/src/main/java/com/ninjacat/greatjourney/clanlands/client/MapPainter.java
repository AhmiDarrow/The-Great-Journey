package com.ninjacat.greatjourney.clanlands.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapData;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapLegend;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapPoi;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Draws the Clan map the server sends ({@link MapData}): the coloured ground as one texture, then the area labels,
 * camps, places, the player and their goal on top, and the legend. Shared by the journal's Map page and the
 * full-screen map. Keeps the latest map and asks the server for a fresh one (the player moves) about once a second
 * while a map is on screen.
 */
final class MapPainter {
    private MapPainter() {}

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("clanlands", "clan_map");
    private static MapData data;
    private static DynamicTexture texture;
    private static int textureSize;
    private static long lastAsk;
    private static int wantRegion = -1;

    static MapData data() {
        return data;
    }

    /** A map arrived from the server. */
    static void receive(MapData d) {
        if (d.size() <= 0 || d.cells().length < d.size() * d.size() || d.shade().length < d.size() * d.size()) return;
        boolean sameGround = data != null && data.region() == d.region() && data.minX() == d.minX() && data.minZ() == d.minZ()
                && data.size() == d.size() && java.util.Arrays.equals(data.cells(), d.cells());
        data = d;
        wantRegion = d.region();
        if (!sameGround || texture == null) paint(d);
    }

    /** Asks for the map of {@code region} now (-1: wherever the player is). */
    static void show(int region) {
        wantRegion = region;
        lastAsk = System.currentTimeMillis();
        PacketDistributor.sendToServer(new com.ninjacat.greatjourney.clanlands.net.ClanPayloads.RequestMap(region));
    }

    /** Called every frame a map is drawn: a fresh copy once a second, so the player's marker follows them. */
    static void keepFresh() {
        long now = System.currentTimeMillis();
        if (now - lastAsk > 1000) show(wantRegion);
    }

    private static void paint(MapData d) {
        int n = d.size();
        if (texture == null || textureSize != n) {
            if (texture != null) texture.close();
            texture = new DynamicTexture(n, n, false);
            textureSize = n;
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        }
        NativeImage img = texture.getPixels();
        if (img == null) return;
        List<MapLegend> legend = d.legend();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i, li = d.cells()[k] & 0xFF;
                int rgb = li < legend.size() ? legend.get(li).colour() : 0xB8A57E;
                double f = 0.7 + (d.shade()[k] & 0xFF) / 255.0 * 0.55;
                int r = clamp((rgb >> 16 & 255) * f), g = clamp((rgb >> 8 & 255) * f), b = clamp((rgb & 255) * f);
                img.setPixelRGBA(i, j, 0xFF000000 | b << 16 | g << 8 | r);             // NativeImage is ABGR
            }
        }
        texture.upload();
    }

    private static int clamp(double v) {
        return Math.max(0, Math.min(255, (int) Math.round(v)));
    }

    /** Where a world position lands on a map drawn at x, y with side {@code s}. */
    static double[] toScreen(MapData d, double wx, double wz, int x, int y, int s) {
        double span = (double) d.cell() * d.size();
        return new double[] {x + (wx - d.minX()) / span * s, y + (wz - d.minZ()) / span * s};
    }

    /** What the mouse is over on the map, for a tooltip (null: nothing). */
    static List<Component> hover(MapData d, int x, int y, int s, double mx, double my) {
        if (mx < x || my < y || mx >= x + s || my >= y + s) return null;
        double best = 7;
        String name = null;
        for (MapPoi p : d.pois()) {
            if (p.kind() == MapPoi.AREA) continue;
            double[] q = toScreen(d, p.x(), p.z(), x, y, s);
            double dist = Math.hypot(q[0] - mx, q[1] - my);
            if (dist < best) {
                best = dist;
                name = p.name();
            }
        }
        if (d.hasGoal()) {
            double[] q = clampTo(toScreen(d, d.gx(), d.gz(), x, y, s), x, y, s);
            if (Math.hypot(q[0] - mx, q[1] - my) < best) {
                best = Math.hypot(q[0] - mx, q[1] - my);
                name = "★ Your goal: " + d.goal() + (inside(d, d.gx(), d.gz()) ? "" : " (off this map, that way)");
            }
        }
        if (d.here()) {
            double[] q = toScreen(d, d.px(), d.pz(), x, y, s);
            if (Math.hypot(q[0] - mx, q[1] - my) < best) name = "You are here";
        }
        return name == null ? null : List.of(Component.literal(name));
    }

    static boolean inside(MapData d, double wx, double wz) {
        double span = (double) d.cell() * d.size();
        return wx >= d.minX() && wz >= d.minZ() && wx < d.minX() + span && wz < d.minZ() + span;
    }

    private static double[] clampTo(double[] q, int x, int y, int s) {
        return new double[] {Math.max(x + 4, Math.min(x + s - 4, q[0])), Math.max(y + 4, Math.min(y + s - 4, q[1]))};
    }

    /**
     * Draws the map at x, y, {@code s} pixels square: ground, area labels, camps and places (named, when
     * {@code names} and there is room), the goal and the player.
     */
    static void draw(GuiGraphics g, Font font, MapData d, int x, int y, int s, boolean names) {
        UiStyle.rounded(g, x - 3, y - 3, s + 6, s + 6, 3, UiStyle.argb(UiStyle.BARK, 1f));
        if (texture == null) return;
        g.blit(TEXTURE, x, y, s, s, 0, 0, d.size(), d.size(), d.size(), d.size());
        g.enableScissor(x, y, x + s, y + s);
        List<int[]> taken = new ArrayList<>();
        // big areas: words on the ground, no marker
        for (MapPoi p : d.pois()) {
            if (p.kind() != MapPoi.AREA) continue;
            double[] q = toScreen(d, p.x(), p.z(), x, y, s);
            label(g, font, p.name(), (int) q[0], (int) q[1] - 4, 0xF6EBD0, 0x2A2014, true, taken, x, y, s);
        }
        // camps (Clan colour) and the books' places (ink diamonds)
        for (MapPoi p : d.pois()) {
            if (p.kind() == MapPoi.AREA) continue;
            double[] q = toScreen(d, p.x(), p.z(), x, y, s);
            int px = (int) q[0], py = (int) q[1];
            if (p.kind() == MapPoi.CAMP) {
                g.fill(px - 3, py - 3, px + 4, py + 4, UiStyle.argb(0x1A120A, 1f));
                g.fill(px - 2, py - 2, px + 3, py + 3, UiStyle.argb(p.colour(), 1f));
            } else {
                diamond(g, px, py, 3, 0xF6EBD0);
                diamond(g, px, py, 2, 0x3B2A1A);
            }
        }
        if (names) {
            for (MapPoi p : d.pois()) {
                if (p.kind() == MapPoi.AREA) continue;
                double[] q = toScreen(d, p.x(), p.z(), x, y, s);
                label(g, font, p.name(), (int) q[0], (int) q[1] + 5, 0x2A2014, 0xF6EBD0, false, taken, x, y, s);
            }
        }
        // the goal: a gold star (at the map's edge, pointing the way, when it lies off this map)
        if (d.hasGoal()) {
            double[] q = clampTo(toScreen(d, d.gx(), d.gz(), x, y, s), x, y, s);
            int gx = (int) q[0], gy = (int) q[1];
            g.fill(gx - 4, gy - 4, gx + 5, gy + 5, UiStyle.argb(0x2A190C, 0.85f));
            g.drawString(font, "★", gx - font.width("★") / 2 + 1, gy - 3, UiStyle.argb(0xFFD447, 1f), false);
        }
        // the player: a red dot with a line showing which way they face
        if (d.here()) {
            double[] q = toScreen(d, d.px(), d.pz(), x, y, s);
            int px = (int) q[0], py = (int) q[1];
            double yaw = Math.toRadians(d.yaw()), dx = -Math.sin(yaw), dz = Math.cos(yaw);
            for (int t = 2; t <= 7; t++) {
                int lx = px + (int) Math.round(dx * t), ly = py + (int) Math.round(dz * t);
                g.fill(lx, ly, lx + 1, ly + 1, UiStyle.argb(0xFFFFFF, 1f));
            }
            g.fill(px - 3, py - 3, px + 4, py + 4, UiStyle.argb(0xFFFFFF, 1f));
            g.fill(px - 2, py - 2, px + 3, py + 3, UiStyle.argb(0xD0302A, 1f));
        }
        g.disableScissor();
    }

    private static void diamond(GuiGraphics g, int x, int y, int r, int rgb) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(x - w, y + i, x + w + 1, y + i + 1, UiStyle.argb(rgb, 1f));
        }
    }

    /** A small label centred on x, outlined so it reads on any ground; skipped where it would cover another. */
    private static void label(GuiGraphics g, Font font, String text, int cx, int top, int ink, int outline, boolean italic,
                              List<int[]> taken, int x, int y, int s) {
        float scale = 0.75f;
        int w = Math.round(font.width(text) * scale), h = Math.round(font.lineHeight * scale);
        int lx = Math.max(x + 1, Math.min(x + s - w - 1, cx - w / 2)), ly = Math.max(y + 1, Math.min(y + s - h - 1, top));
        for (int[] r : taken) {
            if (lx < r[0] + r[2] && lx + w > r[0] && ly < r[1] + r[3] && ly + h > r[1]) return;
        }
        taken.add(new int[] {lx - 1, ly - 1, w + 2, h + 2});
        Component c = italic ? Component.literal(text).withStyle(net.minecraft.ChatFormatting.ITALIC) : Component.literal(text);
        g.pose().pushPose();
        g.pose().translate(lx, ly, 0);
        g.pose().scale(scale, scale, 1);
        for (int[] o : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            g.drawString(font, c, o[0], o[1], UiStyle.argb(outline, 1f), false);
        }
        g.drawString(font, c, 0, 0, UiStyle.argb(ink, 1f), false);
        g.pose().popPose();
    }

    /** The legend and the marker key in a column at x, y, {@code w} wide. Returns the y below it. */
    static int legend(GuiGraphics g, Font font, MapData d, int x, int y, int w) {
        g.drawString(font, "Key", x, y, UiStyle.argb(UiStyle.BARK_DARK, 1f), false);
        y += font.lineHeight + 2;
        int col = w / 2, row = font.lineHeight + 1, i = 0;
        for (MapLegend l : d.legend()) {
            int lx = x + (i % 2) * col, ly = y + (i / 2) * row;
            g.fill(lx, ly, lx + 8, ly + 7, UiStyle.argb(0x2A190C, 1f));
            g.fill(lx + 1, ly + 1, lx + 7, ly + 6, UiStyle.argb(l.colour(), 1f));
            g.drawString(font, fitTo(font, l.name(), col - 12), lx + 11, ly, UiStyle.argb(UiStyle.INK, 1f), false);
            i++;
        }
        y += ((i + 1) / 2) * row + 4;
        // the marker key
        int lx = x;
        g.fill(lx, y, lx + 7, y + 7, UiStyle.argb(0x1A120A, 1f));
        g.fill(lx + 1, y + 1, lx + 6, y + 6, UiStyle.argb(0x8C2F2F, 1f));
        g.drawString(font, "Camp", lx + 10, y, UiStyle.argb(UiStyle.INK, 1f), false);
        lx = x + col;
        diamond(g, lx + 3, y + 3, 3, 0xF6EBD0);
        diamond(g, lx + 3, y + 3, 2, 0x3B2A1A);
        g.drawString(font, "Place", lx + 10, y, UiStyle.argb(UiStyle.INK, 1f), false);
        y += row + 1;
        lx = x;
        g.fill(lx, y, lx + 7, y + 7, UiStyle.argb(0xFFFFFF, 1f));
        g.fill(lx + 1, y + 1, lx + 6, y + 6, UiStyle.argb(0xD0302A, 1f));
        g.drawString(font, "You", lx + 10, y, UiStyle.argb(UiStyle.INK, 1f), false);
        lx = x + col;
        g.drawString(font, "★", lx, y, UiStyle.argb(0xC9A020, 1f), false);
        g.drawString(font, "Your goal", lx + 10, y, UiStyle.argb(UiStyle.INK, 1f), false);
        return y + row + 2;
    }

    /** Where the goal is from the player, in words, or "" when there is none. */
    static String goalLine(MapData d) {
        if (!d.hasGoal()) return "";
        if (!d.here()) return "Goal: " + d.goal();
        double dx = d.gx() - d.px(), dz = d.gz() - d.pz();
        long dist = Math.max(10, Math.round(Math.hypot(dx, dz) / 10.0) * 10);
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        String dir = names[Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8)];
        return "Goal: " + d.goal() + ", about " + dist + " blocks " + dir + ".";
    }

    static String fitTo(Font font, String text, int w) {
        if (font.width(text) <= w) return text;
        return font.plainSubstrByWidth(text, w - font.width("…")).stripTrailing() + "…";
    }
}
