package com.ninjacat.greatjourney.clanlands.layout;

/**
 * Four Clan territories as angular sectors around a centre, in the books' compass order. Each border wobbles with
 * seeded noise along its length, so territory lines are never straight spokes.
 */
public final class Sectors {
    public enum Clan { THUNDER, WIND, RIVER, SHADOW }

    private final Clan[] order;
    /** borders[i] is the bearing between sector i and sector i + 1 (wrapping). */
    private final double[] borders;
    private final Noise wobble;
    private final double wobbleBlocks;

    /**
     * @param order   clans in clockwise order
     * @param centres sector centre bearings (degrees), same order
     * @param widths  relative sector widths, same order (they share the gaps between centres in proportion)
     */
    public Sectors(Clan[] order, double[] centres, double[] widths, long seed, String salt, double wobbleBlocks) {
        this.order = order;
        int n = order.length;
        this.borders = new double[n];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double gap = Geo.angleDiff(centres[j], centres[i]);
            if (gap <= 0) gap += 360;
            borders[i] = (centres[i] + gap * widths[i] / (widths[i] + widths[j]) + 360) % 360;
        }
        this.wobble = new Noise(seed, salt + ".wobble", 140, 2);
        this.wobbleBlocks = wobbleBlocks;
    }

    /** The bearing of border i at a distance d from the centre, wobble included. */
    public double border(int i, double d) {
        double off = Math.toDegrees(wobbleBlocks * wobble.at(d, i * 977.0) / Math.max(d, 80));
        return (borders[i] + off + 360) % 360;
    }

    /** Index of the sector holding bearing b at distance d. */
    public int indexAt(double b, double d) {
        int n = order.length;
        for (int i = 0; i < n; i++) {
            double from = border((i + n - 1) % n, d), to = border(i, d);
            double span = (to - from + 360) % 360;
            if ((b - from + 360) % 360 < span) return i;
        }
        return 0;
    }

    public Clan clanAt(double b, double d) {
        return order[indexAt(b, d)];
    }

    public Clan clan(int i) {
        return order[(i + order.length) % order.length];
    }

    public int size() {
        return order.length;
    }

    /**
     * Distance in blocks to the nearer border of the sector at (b, d), and which neighbour lies across it: returns
     * {blocks, neighbourIndex}.
     */
    public double[] nearestBorder(double b, double d) {
        int n = order.length, i = indexAt(b, d);
        double toPrev = Math.abs(Geo.angleDiff(b, border((i + n - 1) % n, d)));
        double toNext = Math.abs(Geo.angleDiff(border(i, d), b));
        double perDeg = Math.toRadians(1) * Math.max(d, 1);
        return toPrev < toNext ? new double[] {toPrev * perDeg, (i + n - 1) % n} : new double[] {toNext * perDeg, (i + 1) % n};
    }

    /** Index of the clan within the order. */
    public int indexOf(Clan c) {
        for (int i = 0; i < order.length; i++) if (order[i] == c) return i;
        throw new IllegalArgumentException(c.name());
    }

    /** Border index between clan a and the clan clockwise after it. */
    public double borderBearing(Clan a) {
        return borders[indexOf(a)];
    }
}
