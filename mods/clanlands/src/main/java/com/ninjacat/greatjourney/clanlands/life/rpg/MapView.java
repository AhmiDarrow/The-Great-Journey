package com.ninjacat.greatjourney.clanlands.life.rpg;

/**
 * Which part of a map fills its frame: how far zoomed in, and the point of the map (as fractions of its width and
 * height) at the frame's middle. The frame never shows past the map's edge. Plain arithmetic, for the client's
 * full-screen map.
 */
public record MapView(double zoom, double cu, double cv) {
    /** The whole map. */
    public static final MapView FULL = new MapView(1, 0.5, 0.5);
    public static final double MAX_ZOOM = 6;

    public static MapView of(double zoom, double cu, double cv) {
        double z = Math.max(1, Math.min(MAX_ZOOM, zoom)), half = 0.5 / z;
        return new MapView(z, Math.max(half, Math.min(1 - half, cu)), Math.max(half, Math.min(1 - half, cv)));
    }

    /** Zoomed by {@code factor}, what lies under the point (fu, fv) of the frame staying under it. */
    public MapView zoomAt(double factor, double fu, double fv) {
        double z = Math.max(1, Math.min(MAX_ZOOM, zoom * factor));
        double u = cu + (fu - 0.5) / zoom, v = cv + (fv - 0.5) / zoom;
        return of(z, u - (fu - 0.5) / z, v - (fv - 0.5) / z);
    }

    /** Dragged by du, dv of the frame's side: the map follows the mouse. */
    public MapView drag(double du, double dv) {
        return of(zoom, cu - du / zoom, cv - dv / zoom);
    }

    /** Where the whole map lies when this view fills a frame at x, y of side {@code s}: {left, top, side}. */
    public int[] whole(int x, int y, int s) {
        double side = s * zoom;
        return new int[] {(int) Math.round(x + s / 2.0 - cu * side), (int) Math.round(y + s / 2.0 - cv * side), (int) Math.round(side)};
    }
}
