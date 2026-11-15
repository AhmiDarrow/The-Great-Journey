package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.life.rpg.ClanMaps;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapData;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapPoi;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The journal's Clan map: every region has its legend, the books' places and camps are marked, the big areas are
 * labelled, every cell names a legend entry, and the whole map fits the network message. Writes a preview of each
 * region (zones, markers, names) to build/verification/clanlands-maps/ for checking by eye.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class ClanMapGameTests {
    private ClanMapGameTests() {}

    private static final long[] SEEDS = {4242L, 777001L, -84596914L};

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void theClanMapShowsThePlacesOfTheBooks(GameTestHelper h) {
        for (long seed : SEEDS) {
            ClanWorld w = ClanWorld.layoutOnly(seed);
            for (int region = 0; region < 3; region++) {
                MapData d = ClanMaps.preview(w, region);
                String where = "seed " + seed + " region " + d.regions().get(region);
                h.assertTrue(d.size() == ClanMaps.SIZE && d.cells().length == d.size() * d.size(), where + ": a full grid");
                h.assertTrue(d.legend().size() >= 3 && d.legend().size() <= 64, where + ": a legend (" + d.legend().size() + ")");
                for (byte c : d.cells()) h.assertTrue((c & 0xFF) < d.legend().size(), where + ": every cell is in the legend");
                h.assertTrue(d.pois().size() <= 512, where + ": the places fit the message");
                Set<String> names = new HashSet<>();
                for (MapPoi p : d.pois()) {
                    names.add(p.name());
                    h.assertTrue(MapPainterMath.inside(d, p.x(), p.z()), where + ": " + p.name() + " lies on its map");
                }
                switch (region) {
                    case ClanMaps.LAKE -> {
                        for (String n : new String[] {"ThunderClan camp", "WindClan camp", "RiverClan camp", "ShadowClan camp",
                                "The Gathering island", "The Moonpool", "The lake"}) h.assertTrue(names.contains(n), where + " shows " + n);
                    }
                    case ClanMaps.FOREST -> {
                        for (String n : new String[] {"Fourtrees", "Twolegplace", "ThunderClan camp", "WindClan camp", "RiverClan camp",
                                "ShadowClan camp", "Sunningrocks", "Mothermouth", "Highstones"}) h.assertTrue(names.contains(n), where + " shows " + n);
                    }
                    default -> {
                        for (String n : new String[] {"Midnight's sett", "The Tribe's cave", "Twoleg farms", "The sea", "The mountains"})
                            h.assertTrue(names.contains(n), where + " shows " + n);
                    }
                }
                if (seed == SEEDS[0]) preview(d, Path.of("clanlands-maps", "clan-map-" + region + ".png"));
            }
        }
        h.succeed();
    }

    /** Same rule as the client's MapPainter.inside, without loading a client class on the server. */
    private static final class MapPainterMath {
        static boolean inside(MapData d, double wx, double wz) {
            double span = (double) d.cell() * d.size();
            return wx >= d.minX() && wz >= d.minZ() && wx < d.minX() + span && wz < d.minZ() + span;
        }
    }

    private static void preview(MapData d, Path file) {
        int scale = 5, n = d.size(), s = n * scale;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i, rgb = d.legend().get(d.cells()[k] & 0xFF).colour();
                double f = 0.7 + (d.shade()[k] & 0xFF) / 255.0 * 0.55;
                Color c = new Color(Math.min(255, (int) ((rgb >> 16 & 255) * f)), Math.min(255, (int) ((rgb >> 8 & 255) * f)),
                        Math.min(255, (int) ((rgb & 255) * f)));
                for (int a = 0; a < scale; a++) for (int b = 0; b < scale; b++) img.setRGB(i * scale + a, j * scale + b, c.getRGB());
            }
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double span = (double) d.cell() * n;
        for (MapPoi p : d.pois()) {
            int x = (int) ((p.x() - d.minX()) / span * s), y = (int) ((p.z() - d.minZ()) / span * s);
            g.setFont(new Font(Font.SANS_SERIF, p.kind() == MapPoi.AREA ? Font.ITALIC | Font.BOLD : Font.PLAIN, 14));
            if (p.kind() == MapPoi.CAMP) {
                g.setColor(Color.BLACK);
                g.fillRect(x - 6, y - 6, 13, 13);
                g.setColor(new Color(p.colour()));
                g.fillRect(x - 4, y - 4, 9, 9);
            } else if (p.kind() == MapPoi.PLACE) {
                g.setColor(new Color(0x3B2A1A));
                g.fillPolygon(new int[] {x, x + 6, x, x - 6}, new int[] {y - 6, y, y + 6, y}, 4);
            }
            int w = g.getFontMetrics().stringWidth(p.name());
            g.setColor(p.kind() == MapPoi.AREA ? new Color(0x2A2014) : new Color(0xF6EBD0));
            g.drawString(p.name(), x - w / 2 + 1, y + (p.kind() == MapPoi.AREA ? 1 : 21));
            g.setColor(p.kind() == MapPoi.AREA ? new Color(0xF6EBD0) : new Color(0x2A2014));
            g.drawString(p.name(), x - w / 2, y + (p.kind() == MapPoi.AREA ? 0 : 20));
        }
        g.dispose();
        try {
            Files.createDirectories(file.getParent());
            ImageIO.write(img, "png", file.toFile());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
