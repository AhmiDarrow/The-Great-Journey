package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Draws a map's plan (zones, shaded ground height, landmarks) to a PNG, for checking layouts by eye. */
public final class MapRender {
    private MapRender() {}

    private static final Map<Zone, Integer> COLOURS = new EnumMap<>(Zone.class);

    static {
        COLOURS.put(Zone.LAKE, 0x2E5FA8);
        COLOURS.put(Zone.RIVER, 0x3A72C0);
        COLOURS.put(Zone.ISLAND, 0x4F8A3C);
        COLOURS.put(Zone.THUNDERCLAN_FOREST, 0x3F7A2E);
        COLOURS.put(Zone.WINDCLAN_MOOR, 0xA8A060);
        COLOURS.put(Zone.RIVERCLAN_MARSH, 0x6FA88A);
        COLOURS.put(Zone.RIVERCLAN_WETLAND, 0x7FB070);
        COLOURS.put(Zone.RIVERCLAN_PLAINS, 0x92B865);
        COLOURS.put(Zone.SHADOWCLAN_PINES, 0x2F5040);
        COLOURS.put(Zone.SHADOWCLAN_MARSH, 0x4E6450);
        COLOURS.put(Zone.MOONPOOL_HILLS, 0x8A8A7A);
        COLOURS.put(Zone.TWOLEG_FARMLAND, 0xC8B070);
        COLOURS.put(Zone.FOURTREES, 0x5FA040);
        COLOURS.put(Zone.HIGHSTONES_APPROACH, 0x9A9470);
        COLOURS.put(Zone.HIGHSTONES, 0xB0B0B0);
        COLOURS.put(Zone.TALLPINES, 0x405A30);
        COLOURS.put(Zone.TWOLEGPLACE, 0xB06050);
        COLOURS.put(Zone.CARRIONPLACE, 0x6A5A40);
        COLOURS.put(Zone.HIGHWAY_LAND, 0x505058);
        COLOURS.put(Zone.MOUNTAINS, 0x8C8C80);
        COLOURS.put(Zone.MOUNTAIN_PEAKS, 0xC8C8C8);
        COLOURS.put(Zone.TRIBE_LANDS, 0x7090A8);
        COLOURS.put(Zone.SUN_DROWN_SEA, 0x1E4C9A);
        COLOURS.put(Zone.SUN_DROWN_SHORE, 0xE0D49A);
        COLOURS.put(Zone.JOURNEY_RIDGE, 0x88B060);
    }

    public static void render(ClanMap map, int blocksPerPixel, Path file) throws IOException {
        int half = (int) map.reach();
        int size = 2 * half / blocksPerPixel;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        double[] heights = new double[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double x = map.centreX() - half + i * blocksPerPixel, z = map.centreZ() - half + j * blocksPerPixel;
                Sample s = map.sample(x, z);
                heights[j * size + i] = s.weight() > 0 ? s.height() : 70;
                int rgb = s.zone() == null || s.weight() < 0.5 ? 0x202020 : COLOURS.getOrDefault(s.zone(), 0xFF00FF);
                if (s.weight() > 0 && s.weight() < 0.5) rgb = 0x303830;
                if (s.weight() > 0.5 && s.height() < 63 && s.zone() != Zone.LAKE && s.zone() != Zone.RIVER) rgb = 0x4A80C8;
                img.setRGB(i, j, rgb);
            }
        }
        for (int j = 1; j < size; j++) {
            for (int i = 1; i < size; i++) {
                double h = heights[j * size + i], w = heights[j * size + i - 1], n = heights[(j - 1) * size + i];
                double shade = Math.max(0.6, Math.min(1.35, 1 + ((h - w) + (h - n)) / (6.0 * blocksPerPixel)));
                img.setRGB(i, j, scale(img.getRGB(i, j), shade));
            }
        }
        java.util.List<com.ninjacat.greatjourney.clanlands.layout.Road> roads = map instanceof com.ninjacat.greatjourney.clanlands.layout.LakeLands lake
                ? lake.roads() : map instanceof com.ninjacat.greatjourney.clanlands.layout.OldForest forest ? forest.roads() : java.util.List.of();
        for (var road : roads) {
            int grey = road.kind() == com.ninjacat.greatjourney.clanlands.layout.Road.Kind.OLD_THUNDERPATH ? 0x8A7A5A : 0x3A3A3A;
            for (double s = 0; s <= road.path().length(); s += 1) {
                double[] pt = road.path().pointAt(s);
                int i = (int) ((pt[0] - map.centreX() + half) / blocksPerPixel), j = (int) ((pt[1] - map.centreZ() + half) / blocksPerPixel);
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++)
                    if (i + a >= 0 && j + b >= 0 && i + a < size && j + b < size) img.setRGB(i + a, j + b, grey);
            }
        }
        for (Landmark l : map.landmarks()) {
            int i = (int) ((l.x() - map.centreX() + half) / blocksPerPixel), j = (int) ((l.z() - map.centreZ() + half) / blocksPerPixel);
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++)
                if (i + a >= 0 && j + b >= 0 && i + a < size && j + b < size && Math.abs(a) + Math.abs(b) <= 2) img.setRGB(i + a, j + b, 0xFFFFFF);
        }
        double[] sp = map.spawn();
        int si = (int) ((sp[0] - map.centreX() + half) / blocksPerPixel), sj = (int) ((sp[1] - map.centreZ() + half) / blocksPerPixel);
        for (int a = -3; a <= 3; a++) if (si + a >= 0 && si + a < size && sj >= 0 && sj < size) { img.setRGB(si + a, sj, 0xFF2020); img.setRGB(si, Math.max(0, Math.min(size - 1, sj + a)), 0xFF2020); }
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    private static int scale(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * f), g = (int) Math.min(255, ((rgb >> 8) & 255) * f), b = (int) Math.min(255, (rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }
}
