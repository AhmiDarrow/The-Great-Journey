package com.ninjacat.greatjourney.clanlands.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The speaker's portrait in the dialogue window: a still bust, like an RPG character portrait. It never draws the
 * live cat (which would show whatever it is doing, mid-leap or mid-run, and turn with the mouse). Instead it draws a
 * client-only copy that carries the cat's synced looks (coat genetics, eyes, accessories, age, rank) but is never
 * ticked: on the ground, standing still, no attack, no motion, posed three-quarters toward the text. When the speaker
 * is not loaded nearby, a Clan-coloured medallion with a cat's head stands in, so the layout never changes.
 */
final class Portrait {
    private Portrait() {}

    private static final Map<Integer, LivingEntity> COPIES = new HashMap<>();
    /** Dev tuning hooks for the autotest's framing sweep: a zoom factor and a body yaw (NaN = the normal pose). */
    static float tuneZoom = 1f, tuneYaw = Float.NaN;
    /** Where the bust is anchored in the frame (fractions of its size) and how close it sits, found by eye with the
     *  animations held: the head fills a little over half the frame, ears clear of the rim. */
    private static final float ANCHOR_X = 0.40f, ANCHOR_Y = 0.46f, ZOOM = 0.92f;

    /** Forget all copies (the window closed, or a new scene began). */
    static void clear() {
        COPIES.clear();
    }

    /** The frozen copy of a live entity, built once per entity id. Null if it cannot be copied. */
    static LivingEntity copyOf(LivingEntity src) {
        LivingEntity cached = COPIES.get(src.getId());
        if (cached != null) return cached;
        Entity made = src.getType().create(src.level());
        if (!(made instanceof LivingEntity copy)) return null;
        List<SynchedEntityData.DataValue<?>> values = src.getEntityData().getNonDefaultValues();
        if (values != null) copy.getEntityData().assignValues(values);
        still(copy);
        COPIES.put(src.getId(), copy);
        return copy;
    }

    /** Everything that would make the copy move or look busy, switched off. */
    private static void still(LivingEntity e) {
        e.setOnGround(true);
        e.setDeltaMovement(Vec3.ZERO);
        e.setSprinting(false);
        e.setSwimming(false);
        e.setShiftKeyDown(false);
        e.setGlowingTag(false);
        e.setCustomNameVisible(false);                 // no name tag floating over the portrait
        e.setRemainingFireTicks(0);
        e.hurtTime = 0;
        e.deathTime = 0;
        e.walkAnimation.setSpeed(0);
        if (e.getPose() != Pose.SITTING) e.setPose(Pose.STANDING);
        clearFlag(e, "ATTACKING");                 // Warrior Cats Events' cats: no attack animation in a portrait
        // Warrior Cats Events draws a cat from its age with formulas made for 0 to 12 moons and no cap past them:
        // WCRenderer scales the body by 0.4 + moons/12 * 0.6 (4.4x at 80 moons) and WCModel scales the head bone by
        // 1 + 0.3 * (12 - moons)/12 and sinks it by 2 * (12 - moons)/12 (scale 0 at 52 moons, negative past it), so an
        // old Clan leader drew as a giant body with its head turned inside out inside the chest. Its own server tick
        // puts every adult at 12 moons, so the copy is drawn at that age: grown, with its face where it belongs
        if (e instanceof WCatEntity cat && !(cat.getAgeInMoons() <= ADULT_MOONS)) cat.setAgeInMoons(ADULT_MOONS);
    }

    /** The age at which Warrior Cats Events draws a cat full grown (the age its tick gives every adult). */
    private static final float ADULT_MOONS = 12f;

    private static void clearFlag(Entity e, String field) {
        for (Class<?> c = e.getClass(); c != null && c != Entity.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                EntityDataAccessor<Boolean> acc = (EntityDataAccessor<Boolean>) f.get(null);
                e.getEntityData().set(acc, false);
                return;
            } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException ignored) {
                // not this class (or not a boolean flag here): keep looking up the hierarchy
            }
        }
    }

    /**
     * Draws the bust of {@code copy} in the square frame at (x, y), {@code size} pixels: head and shoulders filling
     * most of the frame, three-quarters on, the head turned slightly toward the viewer. The scale comes from the
     * bounding box, so kits and elders frame the same as warriors.
     */
    static void drawBust(GuiGraphics g, int x, int y, int size, LivingEntity copy) {
        // the copy's clock never moves: its animations hold their first frame (standing, head up), never the idle
        // grooming, lying down or stretching the live cat plays, which would move the head about the frame
        copy.tickCount = 0;
        // the drawn size: the bounding box, times the size the renderer gives the model on top (Warrior Cats Events
        // draws kits small and every coat at its own size inside the same box), so every cat frames the same
        float vs = modelScale(copy);
        float h = Math.max(0.25f, copy.getBbHeight() * vs), w = Math.max(0.25f, copy.getBbWidth() * vs);
        float body = Float.isNaN(tuneYaw) ? 180f - 32f : tuneYaw;   // three-quarters, facing toward the text on the right
        copy.yBodyRot = body;
        copy.yBodyRotO = body;
        copy.setYRot(body + 14f);                                // the head turns a little toward the viewer
        copy.yRotO = copy.getYRot();
        copy.yHeadRot = body + 14f;
        copy.yHeadRotO = body + 14f;
        copy.setXRot(-4f);
        copy.xRotO = -4f;

        // the bust: the scale comes from the bounding box (so kits and elders frame like warriors), and the anchor
        // puts the head just right of centre and high in the frame, the face turned toward the text, shoulders below
        float scale = size * 0.62f / (h * 0.55f) * ZOOM * tuneZoom;
        Vector3f translate = new Vector3f(-w * 0.38f * 0.55f, h * 0.78f, 0f);
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf camera = new Quaternionf().rotateX((float) Math.toRadians(-6));
        pose.mul(camera);
        float ax = x + size * ANCHOR_X, ay = y + size * ANCHOR_Y;
        g.enableScissor(x, y, x + size, y + size);
        InventoryScreen.renderEntityInInventory(g, ax, ay, scale, translate, pose, camera, copy);
        g.disableScissor();
        if (tuneZoom != 1f) g.fill((int) ax, (int) ay, (int) ax + 1, (int) ay + 1, 0xFFFF0000);   // the anchor, for the framing sweep
    }

    private static final Map<Class<?>, Optional<Method>> VISUAL_SCALE = new HashMap<>();

    /** How much bigger or smaller than its box the renderer draws this creature. */
    private static float modelScale(LivingEntity e) {
        float s = visualScale(e);
        if (e instanceof WCatEntity cat) {
            // WCRenderer.render: wild cats grow with age (a newborn kit at 0.4, full size at 12 moons); a player's
            // morph is drawn small only as a kit
            if (ClanData.EMPTY_UUID.equals(cat.getPlayerBoundUuid()) && !cat.isAnImage()) {
                s *= (float) Math.max(0.4, 0.4 + cat.getAgeInMoons() / 12f * 0.6);
            } else if (cat.isBaby()) {
                s *= cat.isAppScale() ? 0.4f * 1.75f : 0.4f;
            }
        }
        return s;
    }

    /** The renderer's own model size for this creature ({@code getVisualScale}, if it has one), else 1. */
    private static float visualScale(LivingEntity e) {
        Object renderer = mc().getEntityRenderDispatcher().getRenderer(e);
        Method m = VISUAL_SCALE.computeIfAbsent(renderer.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method d : k.getDeclaredMethods()) {
                    if (d.getName().equals("getVisualScale") && d.getParameterCount() == 1 && d.getReturnType() == float.class
                            && d.getParameterTypes()[0].isInstance(e)) {
                        d.setAccessible(true);
                        return Optional.of(d);
                    }
                }
            }
            return Optional.<Method>empty();
        }).orElse(null);
        if (m == null) return 1f;
        try {
            float v = (float) m.invoke(renderer, e);
            return v > 0.05f && v < 10f ? v : 1f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return 1f;
        }
    }

    /**
     * The stand-in when the speaker is not nearby: a Clan-coloured medallion with a cat's head (ears, face, eyes) and
     * the speaker's initial on a small tag.
     */
    static void drawMedallion(GuiGraphics g, Font font, int x, int y, int size, int clanColour, String speaker, float alpha) {
        int cx = x + size / 2, cy = y + size / 2 + 3;
        int ink = UiStyle.argb(UiStyle.darker(clanColour, 0.32f), alpha);
        int light = UiStyle.argb(0xF6E8C8, alpha * 0.9f);
        // the head
        int hw = size * 30 / 72, hh = size * 24 / 72;
        UiStyle.rounded(g, cx - hw / 2, cy - hh / 2, hw, hh, Math.max(3, hh / 3), ink);
        // the ears: stacked rows narrowing to a point
        int ear = size * 12 / 72;
        for (int i = 0; i < ear; i++) {
            int half = Math.max(1, (ear - i) / 2);
            int ly = cy - hh / 2 - i;
            int lx = cx - hw / 2 + ear / 2 + 1, rx = cx + hw / 2 - ear / 2 - 1;
            g.fill(lx - half, ly, lx + half, ly + 1, ink);
            g.fill(rx - half, ly, rx + half, ly + 1, ink);
        }
        // eyes and a small nose
        int ex = hw / 4, ey = cy - hh / 10;
        g.fill(cx - ex - 2, ey - 2, cx - ex + 2, ey + 1, light);
        g.fill(cx + ex - 2, ey - 2, cx + ex + 2, ey + 1, light);
        g.fill(cx - 1, cy + hh / 6, cx + 1, cy + hh / 6 + 2, light);
        // the initial, on a tag at the bottom of the medallion
        if (speaker != null && !speaker.isBlank()) {
            String init = speaker.substring(0, 1).toUpperCase();
            int tw = font.width(init) + 8;
            UiStyle.rounded(g, cx - tw / 2, y + size - 13, tw, 11, 3, UiStyle.argb(UiStyle.GOLD, alpha));
            g.drawString(font, init, cx - font.width(init) / 2, y + size - 11, UiStyle.argb(UiStyle.BARK_DARK, alpha), false);
        }
    }

    /** The portrait frame: a gold rim, then a soft Clan-coloured vignette that the bust or medallion sits in. */
    static void drawFrame(GuiGraphics g, int x, int y, int size, int clanColour, float alpha) {
        UiStyle.rounded(g, x - 3, y - 3, size + 6, size + 6, 6, UiStyle.argb(UiStyle.BARK_DARK, alpha));
        UiStyle.rounded(g, x - 2, y - 2, size + 4, size + 4, 5, UiStyle.argb(UiStyle.GOLD, alpha));
        UiStyle.rounded(g, x, y, size, size, 4, UiStyle.argb(UiStyle.darker(clanColour, 0.55f), alpha));
        // vignette: light in the middle, darker toward the edges
        for (int i = 0; i < 6; i++) {
            int inset = i * size / 14;
            UiStyle.rounded(g, x + inset, y + inset, size - 2 * inset, size - 2 * inset, 4,
                    UiStyle.argb(UiStyle.darker(clanColour, 0.62f + i * 0.06f), alpha * 0.55f));
        }
        g.fillGradient(x + 1, y + size / 2, x + size - 1, y + size - 1, UiStyle.argb(0x000000, 0f), UiStyle.argb(0x000000, 0.28f * alpha));
    }

    /** The frame's top highlight and a thin gold rule, drawn after the bust so it sits on top of it. */
    static void drawFrameGlass(GuiGraphics g, int x, int y, int size, float alpha) {
        g.fillGradient(x + 2, y + 1, x + size - 2, y + size / 3, UiStyle.argb(0xFFFFFF, 0.10f * alpha), UiStyle.argb(0xFFFFFF, 0f));
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
