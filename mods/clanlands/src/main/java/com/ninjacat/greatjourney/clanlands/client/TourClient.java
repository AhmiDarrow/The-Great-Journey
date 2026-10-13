package com.ninjacat.greatjourney.clanlands.client;

import com.ninjacat.greatjourney.clanlands.life.tour.TourCamera;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads.TourStop;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The client half of the guided tour: flies the spectator camera along each stop's path, waits behind a black
 * screen until the land there has arrived (so there is never a glimpse of empty void), and draws the cinema bars,
 * the stop's name and its caption. Any key skips to the next stop; the closing card hands the player back.
 */
public final class TourClient {
    private TourClient() {}

    private enum Phase { IDLE, LOADING, SHOWING, END_CARD, END_WAIT, END_FADE }

    // a stop the world has never made yet can take longer than 15 s to make out to the edge of the picture in the full
    // pack (playtest 2026-10-12: every stop of a new world ran out at 15 s, its far land still missing)
    private static final long FADE_IN_MS = 1000, FADE_OUT_MS = 800, SKIP_FADE_MS = 550, MIN_BLACK_MS = 700, LOAD_TIMEOUT_MS = 25000;
    private static final long END_CARD_MS = 5000, END_FADE_MS = 1600;
    private static final int SAMPLES = 240;

    private static Phase phase = Phase.IDLE;
    private static List<TourStop> stops = List.of();
    private static int index = -1;
    private static boolean awaitingGo;
    private static long phaseStart, tourStart, skipAt = -1;
    private static double[] hold;
    private static double[][] cam, look;
    private static CameraType savedCamera;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(TourClient::tick);
        NeoForge.EVENT_BUS.addListener(TourClient::frame);
        NeoForge.EVENT_BUS.addListener(TourClient::angles);
        // after the weather mods' own fog (which already marked the event handled), so their listeners must be heard
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, true, ViewportEvent.RenderFog.class, TourClient::fog);
        NeoForge.EVENT_BUS.addListener(TourClient::hideLayers);
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post e) -> OVERLAY.render(e.getGuiGraphics(), e.getPartialTick()));
        NeoForge.EVENT_BUS.addListener((RenderHandEvent e) -> {
            if (active()) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener(TourClient::key);
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre e) -> {
            if (!active()) return;
            e.setCanceled(true);                        // a spectator's click would jump into a creature's eyes
            if (e.getAction() == GLFW.GLFW_PRESS && Minecraft.getInstance().screen == null) skip();
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseScrollingEvent e) -> {
            if (active()) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered e) -> {
            if (active()) {
                e.setCanceled(true);
                e.setSwingHand(false);
            }
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Opening e) -> {
            // the game's own windows wait until the tour is over (the chat stays open to type /greatjourney tour stop,
            // and anything else, like a lost connection, still shows)
            var s = e.getNewScreen();
            if (active() && (s instanceof net.minecraft.client.gui.screens.PauseScreen
                    || s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                    || s instanceof DialogueScreen || s instanceof JournalScreen
                    || s != null && s.getClass().getName().startsWith("net.snowteb."))) {
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> reset());
    }

    /** Whether the tour has the screen (from the first black frame to the last fade). */
    public static boolean active() {
        return phase != Phase.IDLE;
    }

    // messages from the server ----------------------------------------------------------------------------------

    static void start(ClanPayloads.TourStart p) {
        Minecraft mc = Minecraft.getInstance();
        stops = p.stops();
        index = -1;
        awaitingGo = true;
        tourStart = Util.getMillis();
        enter(Phase.LOADING);
        if (savedCamera == null) savedCamera = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        if (mc.screen != null && !(mc.screen instanceof ChatScreen)) mc.setScreen(null);
        SubtitleOverlay.clear();
        sodiumFogOcclusion(true);
        verticalReach(true);
    }

    private static Double savedVerticalScaling;

    /**
     * Better FPS Render Distance (in the pack) draws the land only inside an egg-shaped distance round the player,
     * squashed to half the render distance up and down. The tour's cameras stand high, so the ground more than about
     * a hundred blocks below them was left out: a hollow without a floor at Fourtrees, a floating island past a rim.
     * For the tour it reaches as far down as across; afterwards it is put back. Without that mod this does nothing.
     */
    private static void verticalReach(boolean touring) {
        try {
            Object holder = Class.forName("com.betterfpsdist.BetterfpsdistMod").getField("config").get(null);
            Object config = holder.getClass().getMethod("getCommonConfig").invoke(holder);
            java.lang.reflect.Field f = config.getClass().getField("verticalScaling");
            if (touring) {
                if (savedVerticalScaling == null) savedVerticalScaling = f.getDouble(config);
                f.setDouble(config, Math.min(1.0, savedVerticalScaling));
            } else if (savedVerticalScaling != null) {
                f.setDouble(config, savedVerticalScaling);
                savedVerticalScaling = null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // the mod is not there (or has changed): nothing to change
        }
    }

    private static Boolean savedFogOcclusion;

    /**
     * Sodium leaves out every piece of land past the fog's edge, measured straight up and down too; under a weather
     * mod's humid-air fog that is under a hundred blocks, so from the tour's high cameras the ground fell away into
     * sky (a floating hollow, only treetops at Fourtrees). For the tour that culling is off; afterwards it is put back
     * as it was. Without Sodium this does nothing.
     */
    private static void sodiumFogOcclusion(boolean touring) {
        try {
            Object options = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod").getMethod("options").invoke(null);
            Object perf = options.getClass().getField("performance").get(options);
            java.lang.reflect.Field f = perf.getClass().getField("useFogOcclusion");
            if (touring) {
                if (savedFogOcclusion == null) savedFogOcclusion = f.getBoolean(perf);
                f.setBoolean(perf, false);
                com.mojang.logging.LogUtils.getLogger().debug("Tour: Sodium fog occlusion off for the tour (was {})", savedFogOcclusion);
            } else if (savedFogOcclusion != null) {
                f.setBoolean(perf, savedFogOcclusion);
                savedFogOcclusion = null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // no Sodium (or a version without the setting): nothing to change
        }
    }

    static void go(ClanPayloads.TourGo p) {
        if (phase == Phase.IDLE || p.index() < 0 || p.index() >= stops.size()) return;
        Minecraft mc = Minecraft.getInstance();
        index = p.index();
        awaitingGo = false;
        builtSince = -1;
        cam = null;
        look = null;
        skipAt = -1;
        hold = mc.player == null ? null : new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()};
        enter(Phase.LOADING);
    }

    static void end(ClanPayloads.TourEnd p) {
        if (phase == Phase.IDLE) return;
        enter(Phase.END_FADE);
        cam = null;
        look = null;
        restoreCamera();
    }

    private static void reset() {
        sodiumFogOcclusion(false);
        verticalReach(false);
        phase = Phase.IDLE;
        stops = List.of();
        index = -1;
        cam = null;
        look = null;
        hold = null;
        restoreCamera();
    }

    private static void restoreCamera() {
        if (savedCamera != null) {
            Minecraft.getInstance().options.setCameraType(savedCamera);
            savedCamera = null;
        }
    }

    private static void enter(Phase p) {
        phase = p;
        phaseStart = Util.getMillis();
    }

    private static long inPhase() {
        return Util.getMillis() - phaseStart;
    }

    private static long stopMillis() {
        return index >= 0 && index < stops.size() ? stops.get(index).seconds() * 1000L : 10000L;
    }

    // the flow, each client tick -------------------------------------------------------------------------------

    private static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (phase == Phase.IDLE || mc.player == null || mc.level == null) return;
        mc.getToasts().clear();
        switch (phase) {
            case LOADING -> {
                if (awaitingGo || index < 0) return;
                long t = inPhase();
                boolean isReady = t >= MIN_BLACK_MS && ready(mc);
                if (builtSince < 0 && (waitingOn.startsWith("the land looked at") || waitingOn.startsWith("the place itself") || isReady)) builtSince = t;
                if (isReady || t >= LOAD_TIMEOUT_MS) {
                    com.mojang.logging.LogUtils.getLogger().info("Tour stop '{}' ready after {} ms (its land had arrived after {} ms, the rest was drawing it)",
                            stops.get(index).title(), t, builtSince);
                    if (t >= LOAD_TIMEOUT_MS) {
                        com.mojang.logging.LogUtils.getLogger().info("Tour stop '{}' shown before all its land had arrived ({} s; still waiting for {})",
                                stops.get(index).title(), t / 1000, waitingOn);
                    }
                    buildTrack(mc);
                    enter(Phase.SHOWING);
                }
            }
            case SHOWING -> {
                long t = inPhase();
                boolean over = skipAt >= 0 ? Util.getMillis() - skipAt >= SKIP_FADE_MS : t >= stopMillis();
                if (over) {
                    if (index >= stops.size() - 1) {
                        PacketDistributor.sendToServer(new ClanPayloads.TourNext(index));
                        enter(Phase.END_CARD);
                    } else {
                        PacketDistributor.sendToServer(new ClanPayloads.TourNext(index));
                        awaitingGo = true;
                        enter(Phase.LOADING);
                    }
                    cam = null;
                    look = null;
                    skipAt = -1;
                }
            }
            case END_CARD -> {
                if (inPhase() >= END_CARD_MS) {
                    PacketDistributor.sendToServer(new ClanPayloads.TourDone());
                    enter(Phase.END_WAIT);
                }
            }
            case END_WAIT -> {
                if (inPhase() > 10000) reset();          // the server never answered: let go of the screen anyway
            }
            case END_FADE -> {
                if (inPhase() >= END_FADE_MS) reset();
            }
            default -> { }
        }
    }

    /** Whether the land around this stop's camera path has arrived and been built for drawing. */
    private static boolean ready(Minecraft mc) {
        TourStop s = stops.get(index);
        double[] start = TourCamera.startXZ(s);
        if (hold != null && Math.hypot(hold[0] - start[0], hold[2] - start[1]) > 8) return waiting("the move to the stop");   // not moved there yet
        var source = mc.level.getChunkSource();
        double[][][] c = TourCamera.controls(s, (x, z) -> 64);
        for (int set = 0; set < 2; set++) {
            for (double[] p : c[set]) {
                if (Math.hypot(p[0] - start[0], p[2] - start[1]) > 120) continue;       // beyond what loads at once
                int cx = (int) Math.floor(p[0]) >> 4, cz = (int) Math.floor(p[2]) >> 4;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!source.hasChunk(cx + dx, cz + dz)) return waiting("the chunks on the camera's path");
            }
        }
        int ty = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(s.x()), (int) Math.floor(s.z()));
        if (!mc.levelRenderer.isSectionCompiled(BlockPos.containing(s.x(), ty, s.z()))
                || !mc.levelRenderer.isSectionCompiled(BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()))) {
            return waiting("the place itself to be built");
        }
        // not just the place itself: everything in the picture out to VIEW_REACH (or as far as the land can be drawn
        // here, if less: the outermost ring of chunks is never built, as it lacks neighbours), so the land past a
        // hollow's rim is there too. Farther off, the fog takes over; waiting for it all to be made would hold every
        // stop of a new world behind the black screen until the time limit.
        int reach = mc.options.getEffectiveRenderDistance() - 2;
        if (reach < 1) return true;
        double far = Math.min(VIEW_REACH, reach * 16.0);
        return viewArrived(mc, s, start, far) && viewBuilt(mc, far);
    }

    /** How far out from the camera the land in the picture must have arrived and been built before a stop shows. */
    private static final double VIEW_REACH = 128;

    /** What the stop's loading wait is still waiting for (logged if it runs out of time). */
    private static String waitingOn = "";
    private static long builtSince = -1;

    private static boolean waiting(String what) {
        waitingOn = what;
        return false;
    }

    /** The picture's corners, edges and middle, as {x, y} on the screen from -1 to 1. */
    private static final double[][] FRAME = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {0, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

    private static double tanHalfFov(Minecraft mc) {
        return Math.tan(Math.toRadians(mc.options.fov().get() / 2.0));
    }

    private static double aspect(Minecraft mc) {
        return (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
    }

    /**
     * Whether the chunks the camera will see from each point of its path have arrived: the picture's corners, edges and
     * middle carried down onto the ground (taken a little below where the camera looks), within {@code cap} of the start.
     */
    private static boolean viewArrived(Minecraft mc, TourStop s, double[] start, double cap) {
        var source = mc.level.getChunkSource();
        double tv = tanHalfFov(mc), th = tv * aspect(mc);
        double[][][] c = TourCamera.controls(s, (x, z) -> 64);
        for (int k = 0; k < TourCamera.K; k++) {
            Vec3 eye = new Vec3(c[0][k][0], c[0][k][1], c[0][k][2]), at = new Vec3(c[1][k][0], c[1][k][1], c[1][k][2]);
            Vec3 f = at.subtract(eye);
            if (f.lengthSqr() < 1e-6) continue;
            f = f.normalize();
            Vec3 r = f.cross(new Vec3(0, 1, 0));
            r = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize();      // straight down: any side will do
            Vec3 u = r.cross(f).normalize();
            double plane = at.y - 8;
            for (double[] q : FRAME) {
                Vec3 d = f.add(r.scale(q[0] * th)).add(u.scale(q[1] * tv));
                double t = d.y < -1e-6 ? (plane - eye.y) / d.y : -1;
                // a ray that never comes down (the horizon) reaches as far as the land is drawn
                double x = t > 0 ? eye.x + d.x * t : eye.x + d.x * 1e4, z = t > 0 ? eye.z + d.z * t : eye.z + d.z * 1e4;
                double far = Math.hypot(x - start[0], z - start[1]);
                if (far > cap) {
                    double k2 = cap / far;
                    x = start[0] + (x - start[0]) * k2;
                    z = start[1] + (z - start[1]) * k2;
                }
                if (!source.hasChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) return waiting("the chunk in view at " + (int) x + " " + (int) z);
            }
        }
        return true;
    }

    /**
     * Whether the land the camera looks at right now (where it waits, facing the stop's first view) has been built for
     * drawing: rays across the picture are walked out over the land, and where each first meets the ground (within
     * {@code cap}) that piece must be built. A ray that reaches a chunk not yet arrived means not yet.
     */
    private static boolean viewBuilt(Minecraft mc, double cap) {
        LocalPlayer pl = mc.player;
        var level = mc.level;
        var source = level.getChunkSource();
        Vec3 eye = pl.getEyePosition(), f = Vec3.directionFromRotation(pl.getXRot(), pl.getYRot());
        // waiting under a roof (a canopy, a hillside over a tunnel mouth): the land in view cannot be walked out to here
        if (eye.y <= level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(eye.x), (int) Math.floor(eye.z))) return true;
        Vec3 r = f.cross(new Vec3(0, 1, 0));
        r = r.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : r.normalize();
        Vec3 u = r.cross(f).normalize();
        double tv = tanHalfFov(mc), th = tv * aspect(mc);
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 5; j++) {
                Vec3 d = f.add(r.scale((i / 4.0 - 1) * th)).add(u.scale((j / 2.0 - 1) * tv)).normalize();
                for (double t = 2; t < 1000; t += 2) {
                    double x = eye.x + d.x * t, y = eye.y + d.y * t, z = eye.z + d.z * t;
                    if (Math.hypot(x - eye.x, z - eye.z) > cap || y < level.getMinBuildHeight() || y > level.getMaxBuildHeight() && d.y >= 0) break;
                    int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                    if (!source.hasChunk(bx >> 4, bz >> 4)) return waiting("the chunk looked at, at " + bx + " " + bz);
                    int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
                    if (y <= g) {
                        if (!mc.levelRenderer.isSectionCompiled(new BlockPos(bx, Math.max(level.getMinBuildHeight(), g - 1), bz))) return waiting("the land looked at to be built, at " + bx + " " + (g - 1) + " " + bz);
                        break;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The camera's path for this stop: a smooth curve through the control points, then lifted wherever it would
     * dip into the land (with a margin), with the lift spread out so the camera rises and settles gently.
     */
    private static void buildTrack(Minecraft mc) {
        TourStop s = stops.get(index);
        var level = mc.level;
        DoubleBinaryOperator ground = (x, z) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        double[][][] c = TourCamera.controls(s, ground);
        cam = new double[SAMPLES][];
        look = new double[SAMPLES][];
        for (int i = 0; i < SAMPLES; i++) {
            double u = (double) i / (SAMPLES - 1) * (TourCamera.K - 1);
            cam[i] = TourCamera.catmull(c[0], u);
            look[i] = TourCamera.catmull(c[1], u);
        }
        com.mojang.logging.LogUtils.getLogger().debug("Tour stop '{}': target y {}, camera {} -> {}, look {} -> {}", s.title(),
                TourCamera.targetY(s, ground), java.util.Arrays.toString(cam[0]), java.util.Arrays.toString(cam[SAMPLES - 1]),
                java.util.Arrays.toString(look[0]), java.util.Arrays.toString(look[SAMPLES - 1]));
        if (s.style() == TourStop.TRACK) return;                    // inside a tunnel: the bore is the path
        double[] floor = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double g = -1e9;
            for (int dx = -3; dx <= 3; dx += 3) for (int dz = -3; dz <= 3; dz += 3) g = Math.max(g, ground.applyAsDouble(cam[i][0] + dx, cam[i][2] + dz));
            floor[i] = Math.max(cam[i][1], g + 4);
        }
        // moving maximum, then moving average over the same reach: never below the floor, and no sudden steps
        int r = 10;
        double[] mx = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double m = -1e9;
            for (int j = Math.max(0, i - r); j <= Math.min(SAMPLES - 1, i + r); j++) m = Math.max(m, floor[j]);
            mx[i] = m;
        }
        for (int i = 0; i < SAMPLES; i++) {
            double sum = 0;
            int n = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(SAMPLES - 1, i + r); j++) {
                sum += mx[j];
                n++;
            }
            cam[i][1] = sum / n;
        }
    }

    // the camera, every frame ----------------------------------------------------------------------------------

    private static double[] pose;          // x, y, z, yaw, pitch for this frame

    private static void frame(RenderFrameEvent.Pre e) {
        pose = null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (phase == Phase.IDLE || pl == null) return;
        pl.setDeltaMovement(Vec3.ZERO);
        if (phase == Phase.SHOWING && cam != null) {
            double f = Math.min(1, (double) inPhase() / stopMillis());
            double eased = 0.5 - 0.5 * Math.cos(Math.PI * f);
            double u = eased * (SAMPLES - 1);
            int i = Math.min(SAMPLES - 2, (int) u);
            double t = u - i;
            double x = lerp(cam[i][0], cam[i + 1][0], t), y = lerp(cam[i][1], cam[i + 1][1], t), z = lerp(cam[i][2], cam[i + 1][2], t);
            double lx = lerp(look[i][0], look[i + 1][0], t), ly = lerp(look[i][1], look[i + 1][1], t), lz = lerp(look[i][2], look[i + 1][2], t);
            double dx = lx - x, dy = ly - y, dz = lz - z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            place(pl, x, y, z, yaw, pitch);
            pose = new double[] {x, y, z, yaw, pitch};
        } else if (phase == Phase.LOADING && hold != null && !awaitingGo) {
            place(pl, hold[0], hold[1], hold[2], pl.getYRot(), pl.getXRot());
        }
    }

    private static void place(LocalPlayer pl, double x, double y, double z, float yaw, float pitch) {
        pl.setPos(x, y, z);
        pl.setYRot(yaw);
        pl.setXRot(pitch);
        pl.yHeadRot = yaw;
        pl.yHeadRotO = yaw;
        pl.yBodyRot = yaw;
        pl.yBodyRotO = yaw;
        pl.setOldPosAndRot();
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static void angles(ViewportEvent.ComputeCameraAngles e) {
        if (pose == null || phase != Phase.SHOWING) return;
        e.setYaw((float) pose[3]);
        e.setPitch((float) pose[4]);
        e.setRoll(0);
    }

    /**
     * While touring, the land fogs only at the edge of the render distance, as in clear weather: a weather mod's
     * humid-air fog can close in to under a hundred blocks, which would grey out most of every view from the tour's
     * cameras, which stand that far back.
     */
    private static void fog(ViewportEvent.RenderFog e) {
        if (phase == Phase.IDLE || e.getType() != net.minecraft.world.level.material.FogType.NONE) return;
        float far = Minecraft.getInstance().gameRenderer.getRenderDistance();
        if (e.getFarPlaneDistance() >= far) return;
        if (e.getMode() == net.minecraft.client.renderer.FogRenderer.FogMode.FOG_TERRAIN) {
            e.setNearPlaneDistance(far - net.minecraft.util.Mth.clamp(far / 10f, 4f, 64f));
            e.setFogShape(com.mojang.blaze3d.shaders.FogShape.CYLINDER);
        }
        e.setFarPlaneDistance(far);
        e.setCanceled(true);
    }

    private static void hideLayers(RenderGuiLayerEvent.Pre e) {
        if (active()) e.setCanceled(true);   // every layer (and so every mod's drawing after a layer); the tour draws after them all
    }

    private static void key(InputEvent.Key e) {
        if (!active() || e.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;                              // typing in the chat
        int k = e.getKey();
        if (k >= GLFW.GLFW_KEY_F1 && k <= GLFW.GLFW_KEY_F25) return;   // screenshots and the like
        if (k >= GLFW.GLFW_KEY_LEFT_SHIFT && k <= GLFW.GLFW_KEY_RIGHT_SUPER) return;
        if (mc.options.keyChat.matches(k, e.getScanCode()) || mc.options.keyCommand.matches(k, e.getScanCode())) return;
        skip();
    }

    /** Fades out of this stop early (or ends the closing card). */
    private static void skip() {
        if (phase == Phase.SHOWING && skipAt < 0 && inPhase() > 400) skipAt = Util.getMillis();
        else if (phase == Phase.END_CARD && inPhase() > 800) phaseStart = Util.getMillis() - END_CARD_MS;
    }

    // drawing -------------------------------------------------------------------------------------------------

    private static final Overlay OVERLAY = new Overlay();

    /** The tour's screen: black between stops, the cinema bars, the stop's name and caption. */
    static final class Overlay implements LayeredDraw.Layer {
        @Override
        public void render(GuiGraphics g, DeltaTracker delta) {
            if (phase == Phase.IDLE) return;
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            int w = g.guiWidth(), h = g.guiHeight();
            long now = Util.getMillis();

            // the cinema bars slide in at the start and away at the end
            float bars = phase == Phase.END_FADE ? 1 - Math.min(1, inPhase() / (float) END_FADE_MS) : Math.min(1, (now - tourStart) / 700f);
            bars = smooth(bars);
            int top = Math.round(Math.max(18, h * 0.085f) * bars), bottom = Math.round(Math.max(46, h * 0.16f) * bars);

            float black = switch (phase) {
                case LOADING, END_CARD, END_WAIT -> 1f;
                case END_FADE -> 1 - Math.min(1, inPhase() / (float) END_FADE_MS);
                case SHOWING -> {
                    long t = inPhase();
                    float in = 1 - Math.min(1, t / (float) FADE_IN_MS);
                    float out = skipAt >= 0 ? Math.min(1, (now - skipAt) / (float) SKIP_FADE_MS)
                            : Math.max(0, 1 - (stopMillis() - t) / (float) FADE_OUT_MS);
                    yield Math.max(in, out);
                }
                default -> 0f;
            };
            if (black > 0) g.fill(0, 0, w, h, UiStyle.argb(0x000000, black));
            if (top > 0) g.fill(0, 0, w, top, 0xFF000000);
            if (bottom > 0) g.fill(0, h - bottom, w, h, 0xFF000000);
            if (bars < 0.99f) return;

            // the top bar: the tour's name, where we are, and how to move on
            int ty = (top - font.lineHeight) / 2 + 1;
            g.drawString(font, "The Great Journey", 10, ty, UiStyle.argb(UiStyle.GOLD, 0.9f), false);
            if (index >= 0 && phase != Phase.END_CARD && phase != Phase.END_WAIT) {
                String count = (index + 1) + " / " + stops.size();
                g.drawString(font, count, w - 10 - font.width(count), ty, UiStyle.argb(UiStyle.PARCHMENT_DARK, 0.85f), false);
                String hint = "Any key: next stop";
                if (w > font.width(hint) + font.width("The Great Journey") + font.width(count) + 60) {
                    g.drawString(font, hint, (w - font.width(hint)) / 2, ty, UiStyle.argb(0xBFB49A, 0.45f), false);
                }
            }

            switch (phase) {
                case LOADING -> drawBetween(g, font, w, h, now);
                case SHOWING -> drawCaption(g, font, w, h, bottom, black);
                case END_CARD, END_WAIT -> drawEnd(g, font, w, h);
                default -> { }
            }
        }
    }

    /** While the next place loads: the tour's title at the start, then the name of the place ahead. */
    private static void drawBetween(GuiGraphics g, Font font, int w, int h, long now) {
        float a = Math.min(1, inPhase() / 500f);
        if (index <= 0 && (awaitingGo || index == 0)) {
            centred(g, font, "The Great Journey", w / 2, h / 2 - 22, 2.5f, UiStyle.GOLD, a);
            centred(g, font, "A guided tour of the Clans' world", w / 2, h / 2 + 8, 1f, UiStyle.PARCHMENT, a * 0.9f);
        } else {
            int next = awaitingGo ? index + 1 : index;
            if (next >= 0 && next < stops.size()) {
                centred(g, font, "Next", w / 2, h / 2 - 22, 1f, UiStyle.PARCHMENT_DARK, a * 0.7f);
                centred(g, font, stops.get(next).title(), w / 2, h / 2 - 8, 2f, UiStyle.GOLD, a);
            }
        }
        int dots = (int) ((now / 400) % 4);
        centred(g, font, ".".repeat(dots), w / 2, h / 2 + 26, 1f, UiStyle.PARCHMENT_DARK, a * 0.6f);
    }

    private static void drawCaption(GuiGraphics g, Font font, int w, int h, int bottom, float black) {
        TourStop s = stops.get(index);
        float a = Math.min(1, Math.max(0, (inPhase() - 500) / 700f)) * (1 - black * 0.9f);
        if (a <= 0.02f) return;
        int barTop = h - bottom;
        float titleScale = bottom >= 56 ? 2f : 1.5f;
        int titleH = Math.round(font.lineHeight * titleScale);
        List<String> lines = UiStyle.wrap(font, s.caption(), Math.min(w - 40, 440), Style.EMPTY);
        if (lines.size() > 3) lines = lines.subList(0, 3);
        int block = titleH + 4 + lines.size() * (font.lineHeight + 2);
        int y = barTop + Math.max(4, (bottom - block) / 2);
        centred(g, font, s.title(), w / 2, y, titleScale, UiStyle.GOLD, a);
        y += titleH + 4;
        for (String line : lines) {
            centred(g, font, line, w / 2, y, 1f, UiStyle.PARCHMENT, a * 0.95f);
            y += font.lineHeight + 2;
        }
    }

    private static void drawEnd(GuiGraphics g, Font font, int w, int h) {
        long t = inPhase();
        float a = Math.min(1, t / 1000f), b = Math.min(1, Math.max(0, (t - 1300) / 900f));
        if (phase == Phase.END_WAIT) a = b = 1;
        centred(g, font, "Your journey begins…", w / 2, h / 2 - 16, 2.5f, UiStyle.GOLD, a);
        centred(g, font, "May StarClan light your path.", w / 2, h / 2 + 14, 1f, UiStyle.PARCHMENT, b * 0.9f);
    }

    /** Text centred on {@code cx}, scaled, at an alpha. */
    private static void centred(GuiGraphics g, Font font, String text, int cx, int y, float scale, int rgb, float alpha) {
        if (alpha <= 0.02f) return;
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -font.width(text) / 2, 0, UiStyle.argb(rgb, alpha), false);
        g.pose().popPose();
    }

    private static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    // read by the playtest harness (the test-only greatjourney_qa mod, tools/playtest.py)
    public static String autotestPhase() {
        return phase.name();
    }

    public static int autotestIndex() {
        return index;
    }

    public static int autotestStops() {
        return stops.size();
    }

    public static float autotestFraction() {
        return phase == Phase.SHOWING ? Math.min(1, inPhase() / (float) stopMillis()) : 0;
    }

    public static String autotestTitle() {
        return index >= 0 && index < stops.size() ? stops.get(index).title() : "";
    }
}
